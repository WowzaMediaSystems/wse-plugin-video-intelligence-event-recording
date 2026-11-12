/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.event;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.plugin.videointelligence.ModuleVideoIntelligence;
import com.wowza.wms.plugin.videointelligence.api.IVifEventListener;
import com.wowza.wms.plugin.videointelligence.api.IVifService;
import com.wowza.wms.plugin.videointelligence.api.ShutdownEvent;
import com.wowza.wms.plugin.videointelligence.api.SinkRequirement;
import com.wowza.wms.plugin.videointelligence.eventrecording.LiveRecorderBackend;
import com.wowza.wms.plugin.videointelligence.eventrecording.PresenceGate;
import com.wowza.wms.plugin.videointelligence.eventrecording.RecordingBackend;
import com.wowza.wms.plugin.videointelligence.eventrecording.RecordingNames;
import com.wowza.wms.plugin.videointelligence.eventrecording.ReleaseInfo;
import com.wowza.wms.plugin.videointelligence.eventrecording.VodClipBackend;
import com.wowza.wms.plugin.videointelligence.message.DetectionData;
import com.wowza.wms.plugin.videointelligence.message.DetectionResponse;
import com.wowza.wms.plugin.videointelligence.message.ObjectDetectionData;
import com.wowza.wms.server.Server;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.vhost.IVHost;
import com.wowza.wms.webhooks.WebhookEvent;
import com.wowza.wms.webhooks.WebhookEventContext;
import com.wowza.wms.webhooks.WebhookEventEntity;
import com.wowza.wms.webhooks.WebhookEventName;
import com.wowza.wms.webhooks.WebhookListener;

/**
 * Records one MP4 per presence event of the configured classes: a back buffer before the first
 * detection, a post-detection window after the last. On a live stream the Engine's recorder
 * writes the file as the event unfolds; on a file job the event is cut out of the source once
 * its bounds are known. One configuration, chosen by what the host hands {@link #onInit}: a job
 * passes its id and source file, a stream passes an application instance to record into.
 *
 * <p>The trigger is {@link PresenceGate}, in the responses' own time — media milliseconds on a
 * job, the stream timecode live — so the same settings cut the same clips on both hosts. The
 * listener declares no host requirement; a host offering neither a job nor an application
 * instance gets a warning and an inert listener.
 */
public class EventRecordingPlugin implements IVifEventListener, RecordingBackend.Reporter
{
	public static final String MODULE_VERSION = ReleaseInfo.getVersion();

	static final long DEFAULT_BACK_BUFFER_MS = 5000;
	static final long DEFAULT_POST_DETECTION_MS = 5000;
	static final long DEFAULT_LOST_OBJECT_MS = 3000;
	/** How long a job that ended waits for the clips it still owes before ending ahead of them. */
	static final long FINISH_TIMEOUT_SECONDS = 60;
	/** Detection webhooks held for a recorder that has not opened its file yet. */
	static final int MAX_PENDING_DETECTIONS = 256;

	private static final WMSLogger logger = WMSLoggerFactory.getLogger(EventRecordingPlugin.class);
	private static final String LOGPREFIX = "VideoIntelligenceService";

	/** A tracked object seen inside the current live event, for the per-track webhooks. */
	private static final class ObjectInfo
	{
		String className;
		Double lastConfidence;
		long lastSeenMs;
	}

	private static final class PendingDetection
	{
		final String action;
		final int trackingId;
		final String className;
		final Double confidence;
		final long detectionTc;
		final int activeObjects;

		PendingDetection(String action, int trackingId, String className, Double confidence, long detectionTc,
				int activeObjects)
		{
			this.action = action;
			this.trackingId = trackingId;
			this.className = className;
			this.confidence = confidence;
			this.detectionTc = detectionTc;
			this.activeObjects = activeObjects;
		}
	}

	private String logPrefix;
	private String streamName;
	private String vHostName;
	private String appName;
	private String appInstanceName;
	private String jobId;
	private String sourceFile;
	private boolean fileJob;

	private PresenceGate gate;
	private PresenceGate.Settings settings;
	private RecordingBackend backend;
	private LiveRecorderBackend live;
	private ScheduledExecutorService executor;

	private boolean webhooksEnabled = true;
	private WebhookListener webhookListener;

	private final Object lock = new Object();
	private long lastAtMs = Long.MIN_VALUE;
	private boolean stopped;

	// Live only: the objects of the open event, for detection.start / detection.stop.
	private final Map<Integer, ObjectInfo> activeObjects = new LinkedHashMap<>();
	private final List<PendingDetection> pendingDetectionEvents = new ArrayList<>();
	private String recordingFile;
	private long recordingFromMs;
	private Long recordingStartedAtMs;

	public static String getVersion()
	{
		return MODULE_VERSION;
	}

	@Override
	public Set<SinkRequirement> requires()
	{
		return Set.of();
	}

	@Override
	public void onInit(IApplicationInstance appInstance, IMediaStream stream, Set<String> methods,
			HashMap<String, Object> properties)
	{
		this.streamName = stream.getName();
		this.logPrefix = LOGPREFIX + streamName + ":EventRecordingPlugin:";
		// The host re-initialises the same instance after a config update: nothing of the run
		// that was shut down may survive into this one.
		synchronized (lock)
		{
			stopped = false;
			lastAtMs = Long.MIN_VALUE;
			activeObjects.clear();
			pendingDetectionEvents.clear();
			recordingFile = null;
			recordingFromMs = 0;
			recordingStartedAtMs = null;
		}
		this.backend = null;
		this.live = null;
		this.webhookListener = null;
		this.jobId = trimmedOrNull(properties.get("job_id"));
		this.sourceFile = trimmedOrNull(properties.get("source_file"));
		this.fileJob = jobId != null;
		if (appInstance != null)
		{
			this.vHostName = appInstance.getVHost().getName();
			this.appName = appInstance.getApplication().getName();
			this.appInstanceName = appInstance.getName();
		}
		else
		{
			this.vHostName = IVHost.VHOST_DEFAULT;
			this.appName = "vod";
			this.appInstanceName = IApplicationInstance.DEFAULT_APPINSTANCE_NAME;
		}

		this.settings = new PresenceGate.Settings(classesOf(properties.get("class_names")),
				intProperty(properties, "min_count", 1), boolProperty(properties, "require_track", false),
				longProperty(properties, "lost_object_duration_ms", DEFAULT_LOST_OBJECT_MS),
				longProperty(properties, "merge_gap_ms", 0), optionalLongProperty(properties, "max_clip_ms"));
		this.gate = new PresenceGate(settings);
		long backBufferMs = longProperty(properties, "back_buffer_time", DEFAULT_BACK_BUFFER_MS);
		long postDetectionMs = longProperty(properties, "post_detection_duration", DEFAULT_POST_DETECTION_MS);
		String nameTemplate = stringProperty(properties, "recording_name_template", RecordingNames.DEFAULT_TEMPLATE);
		String dateTimePattern = trimmedOrNull(properties.get("date_time_format"));
		DateTimeFormatter dateTimeFormat = RecordingNames.dateTimeFormatter(dateTimePattern);
		if (dateTimePattern != null && dateTimeFormat == null)
			logger.warn(logPrefix + "Invalid date_time_format '" + dateTimePattern + "'. Using "
					+ RecordingNames.DEFAULT_DATE_TIME_FORMAT);
		String outputPath = trimmedOrNull(properties.get("output_path"));
		this.webhooksEnabled = boolProperty(properties, "webhooks", true);

		this.executor = Executors.newSingleThreadScheduledExecutor(task ->
		{
			Thread thread = new Thread(task, "EventRecordingPlugin-" + streamName);
			thread.setDaemon(true);
			return thread;
		});

		if (fileJob)
		{
			this.backend = fileJobBackend(properties, outputPath, backBufferMs, postDetectionMs, nameTemplate,
					dateTimeFormat);
		}
		else if (appInstance != null)
		{
			Pattern recordStream = recordStreamOf(properties.get("record_stream_name"));
			if (recordStream != null && !isTranscoderEnabled())
			{
				logger.warn(logPrefix + "record_stream_name is set but use_transcoder is false."
						+ " Ignoring pattern and recording source stream.");
				recordStream = null;
			}
			this.live = new LiveRecorderBackend(logger, logPrefix, appInstance, stream, executor, this,
					backBufferMs, postDetectionMs, nameTemplate, dateTimeFormat, outputPath, recordStream);
			this.backend = live;
		}
		else
		{
			logger.warn(logPrefix + "This host offers neither a file job nor an application instance to"
					+ " record into. Nothing will be recorded.");
		}

		if (webhooksEnabled)
		{
			this.webhookListener = engineWebhookListener();
			if (webhookListener == null)
				logger.warn(logPrefix + "No WebhookListener found. Webhooks will be disabled");
		}

		logger.info(logPrefix + "Starting EventRecordingPlugin v" + MODULE_VERSION
				+ (fileJob ? " for file job " + jobId : " for live stream")
				+ " classes:" + settings.classes()
				+ " minCount:" + settings.minCount()
				+ " requireTrack:" + settings.requireTrack()
				+ " backBuffer:" + backBufferMs + "ms"
				+ " postDetection:" + postDetectionMs + "ms"
				+ " lostObjectDuration:" + settings.lostMs() + "ms"
				+ " mergeGap:" + settings.mergeGapMs() + "ms"
				+ " maxClip:" + settings.maxClipMs() + "ms");
	}

	private RecordingBackend fileJobBackend(HashMap<String, Object> properties, String outputPath,
			long backBufferMs, long postDetectionMs, String nameTemplate, DateTimeFormatter dateTimeFormat)
	{
		if (sourceFile == null)
		{
			logger.warn(logPrefix + "The job named no source_file. Nothing will be recorded.");
			return null;
		}
		if (trimmedOrNull(properties.get("record_stream_name")) != null)
			logger.info(logPrefix + "record_stream_name does not apply to a file job; cutting the source file");
		Path source = Paths.get(sourceFile);
		Path outputDir = outputPath != null ? Paths.get(outputPath) : source.toAbsolutePath().getParent();
		return new VodClipBackend(logger, logPrefix, source, outputDir, executor, this, backBufferMs,
				postDetectionMs, nameTemplate, dateTimeFormat);
	}

	@Override
	public boolean immediate(DetectionResponse response)
	{
		if (backend == null || response.detectionWindow == null)
			return false;
		Long atMs = response.detectionWindow.fromTimeCode != null ? response.detectionWindow.fromTimeCode
				: response.detectionWindow.toTimeCode;
		if (atMs == null)
			return false;
		List<DetectionData> detections = response.getDetections();
		List<PresenceGate.Seen> seen = seenIn(detections);
		List<PresenceGate.Transition> transitions;
		synchronized (lock)
		{
			if (stopped)
				return false;
			lastAtMs = atMs;
			transitions = gate.observe(atMs, seen);
			for (PresenceGate.Transition transition : transitions)
			{
				if (transition instanceof PresenceGate.Start)
					backend.onStart((PresenceGate.Start)transition, atMs);
				else if (transition instanceof PresenceGate.End)
					backend.onEnd((PresenceGate.End)transition, atMs);
			}
			backend.onClock(atMs);
			if (live != null)
			{
				// An open event whose recorder is gone asks for one again on its next match.
				if (gate.matchedLast() && live.idle())
					live.onStart(gate.open(), atMs);
				// Tracks are followed for the webhooks of a recording; with no event open and no
				// recorder running there is none for them to belong to.
				if (gate.active() || live.engaged())
				{
					trackObjects(atMs, detections);
				}
				else
				{
					activeObjects.clear();
					pendingDetectionEvents.clear();
				}
			}
		}
		return !transitions.isEmpty();
	}

	@Override
	public boolean batch(ArrayList<DetectionResponse> responses)
	{
		return false;
	}

	@Override
	public boolean rollup(DetectionResponse response)
	{
		return false;
	}

	@Override
	public void onShutdown()
	{
		onShutdown(ShutdownEvent.listenerDisabled(streamName));
	}

	@Override
	public void onShutdown(ShutdownEvent event)
	{
		if (backend == null)
			return;
		ShutdownEvent.Reason reason = event == null ? ShutdownEvent.Reason.LISTENER_DISABLED : event.reason();
		boolean jobEnded = fileJob && reason != ShutdownEvent.Reason.LISTENER_DISABLED;
		synchronized (lock)
		{
			if (stopped)
				return;
			stopped = true;
			if (jobEnded)
			{
				// Failed or cancelled as much as completed: what the job analysed is cut, since a
				// resume continues after it and would never see the open event's beginning.
				for (PresenceGate.Transition transition : gate.close())
				{
					if (transition instanceof PresenceGate.End)
						backend.onEnd((PresenceGate.End)transition, lastAtMs);
				}
			}
			else if (live != null && gate.active())
			{
				logger.info(logPrefix + "Shutting down, stopping active recorder");
			}
			backend.finish(reason);
			activeObjects.clear();
			pendingDetectionEvents.clear();
		}
		if (!jobEnded)
		{
			executor.shutdownNow();
			return;
		}
		executor.shutdown();
		try
		{
			if (!executor.awaitTermination(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
				logger.warn(logPrefix + "Clips still being cut after " + FINISH_TIMEOUT_SECONDS
						+ "s; the job ends ahead of them");
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	// Live only: which tracked objects are inside the event, for the per-track webhooks. Media
	// time, like the gate, so VIF's mix of empty and untracked responses neither advances nor
	// suppresses the eviction clock.
	private void trackObjects(long atMs, List<DetectionData> detections)
	{
		List<Integer> newObjectIds = new ArrayList<>();
		if (detections != null)
		{
			for (DetectionData data : detections)
			{
				if (!(data instanceof ObjectDetectionData))
					continue;
				ObjectDetectionData detection = (ObjectDetectionData)data;
				if (detection.trackId == null || !settings.matches(detection.className))
					continue;
				ObjectInfo info = activeObjects.get(detection.trackId);
				if (info == null)
				{
					info = new ObjectInfo();
					activeObjects.put(detection.trackId, info);
					newObjectIds.add(detection.trackId);
				}
				info.className = detection.className;
				info.lastConfidence = detection.confidence;
				info.lastSeenMs = atMs;
			}
		}
		List<Integer> lostObjectIds = new ArrayList<>();
		List<ObjectInfo> lostObjectInfos = new ArrayList<>();
		for (Iterator<Map.Entry<Integer, ObjectInfo>> it = activeObjects.entrySet().iterator(); it.hasNext();)
		{
			Map.Entry<Integer, ObjectInfo> entry = it.next();
			if (atMs - entry.getValue().lastSeenMs > settings.lostMs())
			{
				lostObjectIds.add(entry.getKey());
				lostObjectInfos.add(entry.getValue());
				it.remove();
			}
		}
		for (Integer trackingId : newObjectIds)
		{
			ObjectInfo info = activeObjects.get(trackingId);
			enqueueOrSendDetectionWebhook("detection.start", trackingId, info.className, info.lastConfidence,
					info.lastSeenMs);
		}
		for (int i = 0; i < lostObjectIds.size(); i++)
		{
			ObjectInfo info = lostObjectInfos.get(i);
			enqueueOrSendDetectionWebhook("detection.stop", lostObjectIds.get(i), info.className,
					info.lastConfidence, info.lastSeenMs);
		}
	}

	@Override
	public void recordingStarted(String file, long fromMs, Long startedAtEpochMs, Set<String> classNames,
			Set<Integer> trackIds)
	{
		List<PendingDetection> owed;
		synchronized (lock)
		{
			recordingFile = file;
			recordingFromMs = fromMs;
			recordingStartedAtMs = startedAtEpochMs;
			owed = new ArrayList<>(pendingDetectionEvents);
			pendingDetectionEvents.clear();
		}
		logger.info(logPrefix + (fileJob ? "Clip started: " : "Segment started: ") + file + " from:" + fromMs
				+ "ms classes:" + classNames + " trackingIds:" + trackIds);
		if (fileJob)
		{
			Map<String, Object> payload = recordingPayload("recording.started", file, fromMs, startedAtEpochMs);
			payload.put("class_names", new ArrayList<>(classNames));
			payload.put("tracking_ids", new ArrayList<>(trackIds));
			sendWebhook(payload);
		}
		// Queued while the recorder was starting; they now carry the file they belong to.
		for (PendingDetection pending : owed)
			sendDetectionWebhook(pending);
	}

	@Override
	public void recordingStopped(String file, long fromMs, long toMs, long sizeBytes)
	{
		Long startedAt;
		synchronized (lock)
		{
			startedAt = recordingStartedAtMs;
			recordingFile = null;
			recordingStartedAtMs = null;
			if (!fileJob)
			{
				activeObjects.clear();
				pendingDetectionEvents.clear();
			}
		}
		logger.info(logPrefix + (fileJob ? "Clip written: " : "Recording stopped: ") + file + " from:" + fromMs
				+ "ms to:" + toMs + "ms duration:" + (toMs - fromMs) + "ms size:" + sizeBytes + " bytes");
		if (fileJob)
		{
			Map<String, Object> payload = recordingPayload("recording.stopped", file, fromMs, startedAt);
			payload.put("to_ms", toMs);
			payload.put("size_bytes", sizeBytes);
			sendWebhook(payload);
		}
	}

	private Map<String, Object> recordingPayload(String action, String file, long fromMs, Long startedAtEpochMs)
	{
		Map<String, Object> payload = new HashMap<>();
		payload.put("action", action);
		payload.put("stream_name", streamName);
		Map<String, Object> rec = new HashMap<>();
		rec.put("file", file);
		rec.put("from_ms", fromMs);
		if (startedAtEpochMs != null)
			rec.put("started_at", Instant.ofEpochMilli(startedAtEpochMs).toString());
		payload.put("recording", rec);
		return payload;
	}

	/**
	 * Sends the detection webhook now when the segment file is open, else queues it; the queue
	 * is flushed by {@link #recordingStarted}.
	 */
	private void enqueueOrSendDetectionWebhook(String action, int trackingId, String className, Double confidence,
			long detectionTc)
	{
		if (!webhooksEnabled)
			return;
		PendingDetection detection = new PendingDetection(action, trackingId, className, confidence, detectionTc,
				activeObjects.size());
		if (recordingFile != null)
		{
			sendDetectionWebhook(detection);
			return;
		}
		// Bounded: a recorder that keeps failing to start must not grow the queue for good.
		if (pendingDetectionEvents.size() >= MAX_PENDING_DETECTIONS)
			pendingDetectionEvents.remove(0);
		pendingDetectionEvents.add(detection);
	}

	private void sendDetectionWebhook(PendingDetection detection)
	{
		String file;
		long fromMs;
		Long startedAt;
		synchronized (lock)
		{
			file = recordingFile;
			fromMs = recordingFromMs;
			startedAt = recordingStartedAtMs;
		}
		Map<String, Object> payload = new HashMap<>();
		payload.put("action", detection.action);
		payload.put("stream_name", streamName);
		Map<String, Object> det = new HashMap<>();
		det.put("tracking_id", detection.trackingId);
		det.put("class_name", detection.className);
		det.put("confidence", detection.confidence);
		if (file != null)
			det.put("recording_offset_ms", Math.max(0, detection.detectionTc - fromMs));
		payload.put("detection", det);
		payload.put("active_object_count", detection.activeObjects);
		if (file != null)
		{
			Map<String, Object> rec = new HashMap<>();
			rec.put("file", file);
			if (startedAt != null)
				rec.put("started_at", Instant.ofEpochMilli(startedAt).toString());
			payload.put("recording", rec);
		}
		sendWebhook(payload);
	}

	private void sendWebhook(Map<String, Object> payload)
	{
		if (webhookListener == null)
			return;
		if (fileJob)
		{
			payload.put("job_id", jobId);
			payload.put("source_file", sourceFile);
		}
		try
		{
			Map<String, Object> data = new HashMap<>();
			data.put("event_recording_data", payload);
			WebhookEvent event = new WebhookEvent(WebhookEventName.VI_DETECTION);
			event.setContext(WebhookEventContext.forEntity(vHostName, appName, appInstanceName, streamName,
					WebhookEventEntity.OBJECTS));
			event.setData(data);
			webhookListener.addEvent(event);
		}
		catch (Exception e)
		{
			logger.error(logPrefix + "Webhook Error: " + e.getMessage(), e);
		}
	}

	private WebhookListener engineWebhookListener()
	{
		try
		{
			return (WebhookListener)Server.getInstance().getProperties().getProperty("WebhookListener");
		}
		catch (Exception e)
		{
			logger.warn(logPrefix + "Could not reach the Engine's webhook listener: " + e);
			return null;
		}
	}

	private boolean isTranscoderEnabled()
	{
		try
		{
			ModuleVideoIntelligence vifModule = ModuleVideoIntelligence.getModule(vHostName, appName,
					appInstanceName);
			if (vifModule == null)
				return false;
			IVifService vifService = vifModule.getVifService(streamName);
			if (vifService == null)
				return false;
			return vifService.getDetectorInfo().useTranscoder;
		}
		catch (Exception e)
		{
			logger.warn(logPrefix + "Could not determine transcoder state: " + e.getMessage());
			return false;
		}
	}

	private static List<PresenceGate.Seen> seenIn(List<DetectionData> detections)
	{
		List<PresenceGate.Seen> seen = new ArrayList<>();
		if (detections == null)
			return seen;
		for (DetectionData detection : detections)
		{
			if (detection == null)
				continue;
			Integer trackId = detection instanceof ObjectDetectionData ? ((ObjectDetectionData)detection).trackId
					: null;
			seen.add(new PresenceGate.Seen(detection.className, trackId));
		}
		return seen;
	}

	/** A list, or one comma-separated string — the two shapes a listener property arrives in. */
	static Set<String> classesOf(Object value)
	{
		Set<String> classes = new LinkedHashSet<>();
		if (value instanceof List)
		{
			for (Object item : (List<?>)value)
			{
				if (item != null)
					classes.add(item.toString().toLowerCase(Locale.ROOT));
			}
		}
		else if (value != null)
		{
			for (String item : value.toString().split(","))
			{
				if (!item.isBlank())
					classes.add(item.trim().toLowerCase(Locale.ROOT));
			}
		}
		if (classes.isEmpty())
			classes.add("all");
		return classes;
	}

	private Pattern recordStreamOf(Object value)
	{
		String pattern = trimmedOrNull(value);
		if (pattern == null)
			return null;
		try
		{
			return Pattern.compile(pattern);
		}
		catch (PatternSyntaxException e)
		{
			logger.warn(logPrefix + "Invalid record_stream_name regex '" + pattern + "': " + e.getDescription()
					+ ". Will record source stream.");
			return null;
		}
	}

	static String trimmedOrNull(Object value)
	{
		if (value == null)
			return null;
		String text = value.toString().trim();
		return text.isEmpty() ? null : text;
	}

	private static String stringProperty(Map<String, Object> properties, String key, String fallback)
	{
		String value = trimmedOrNull(properties.get(key));
		return value == null ? fallback : value;
	}

	private static long longProperty(Map<String, Object> properties, String key, long fallback)
	{
		Long value = optionalLongProperty(properties, key);
		return value == null ? fallback : value;
	}

	private static Long optionalLongProperty(Map<String, Object> properties, String key)
	{
		String value = trimmedOrNull(properties.get(key));
		if (value == null)
			return null;
		return (long)Double.parseDouble(value);
	}

	private static int intProperty(Map<String, Object> properties, String key, int fallback)
	{
		return (int)longProperty(properties, key, fallback);
	}

	private static boolean boolProperty(Map<String, Object> properties, String key, boolean fallback)
	{
		String value = trimmedOrNull(properties.get(key));
		return value == null ? fallback : Boolean.parseBoolean(value);
	}
}
