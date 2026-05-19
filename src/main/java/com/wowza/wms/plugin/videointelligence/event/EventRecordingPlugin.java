/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

import com.wowza.util.FLVUtils;
import com.wowza.wms.amf.AMFPacket;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.livestreamrecord.manager.IStreamRecorder;
import com.wowza.wms.livestreamrecord.manager.IStreamRecorderActionNotify;
import com.wowza.wms.livestreamrecord.manager.IStreamRecorderConstants;
import com.wowza.wms.livestreamrecord.manager.StreamRecorderParameters;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.plugin.videointelligence.ModuleVideoIntelligence;
import com.wowza.wms.plugin.videointelligence.VideoIntelligenceService;
import com.wowza.wms.plugin.videointelligence.eventrecording.ReleaseInfo;
import com.wowza.wms.plugin.videointelligence.message.DetectionResponse;
import com.wowza.wms.plugin.videointelligence.message.ObjectDetectionData;
import com.wowza.wms.server.Server;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.webhooks.WebhookEvent;
import com.wowza.wms.webhooks.WebhookEventContext;
import com.wowza.wms.webhooks.WebhookEventEntity;
import com.wowza.wms.webhooks.WebhookEventName;
import com.wowza.wms.webhooks.WebhookListener;

public class EventRecordingPlugin implements IVifEventListener, IStreamRecorderActionNotify
{
	public static final String MODULE_VERSION = ReleaseInfo.getVersion();

	private static final WMSLogger logger = WMSLoggerFactory.getLogger(EventRecordingPlugin.class);

	private static class ObjectInfo
	{
		String className;
		Double lastConfidence;
		long lastSeenTC;      // stream TC of the most recent confirmation, used for recording_offset_ms
		long lastSeenAtMs;    // System.currentTimeMillis() at the most recent confirmation
	}

	private record PendingDetection(String action, int trackingId, String className, Double confidence, long detectionTC)
	{
	}

	private final Set<String> classNames = new LinkedHashSet<>();

	// Configuration
	private IApplicationInstance appInstance;
	private IMediaStream stream;
	private String logPrefix;
	private String vHostName;
	private String appName;
	private String appInstanceName;
	private String streamName;

	private long backBufferTime = 5000;
	private long postDetectionDuration = 5000;
	private long lostObjectDurationMs = 3000;
	private String recordingNameTemplate = "${SourceStreamName}_${RecordingStartTime}";
	private Pattern recordStreamNamePattern;
	private String outputPath = null;
	private boolean webhooksEnabled = true;

	private WebhookListener webhookListener;

	// Runtime state -- map of objects currently being tracked, keyed by their tracking ID.
	private IStreamRecorder activeRecorder;
	private boolean recordingActive;  // true once the segment file is open (onSegmentStart)
	private String recordingStreamName;
	private Timer stopTimer;
	private final Map<Integer, ObjectInfo> activeObjects = new LinkedHashMap<>();
	private final List<PendingDetection> pendingDetectionEvents = new ArrayList<>();
	private long recordingStartMs;
	private long earliestKeyTC;

	public static String getVersion()
	{
		return MODULE_VERSION;
	}

	@Override
	public void onInit(IApplicationInstance appInstance, IMediaStream stream, Set<String> methods, HashMap<String, Object> properties)
	{
		this.appInstance = appInstance;
		this.stream = stream;
		this.logPrefix = VideoIntelligenceService.LOGPREFIX + stream.getName() + ":EventRecordingPlugin:";

		this.vHostName = appInstance.getVHost().getName();
		this.appName = appInstance.getApplication().getName();
		this.appInstanceName = appInstance.getName();
		this.streamName = stream.getName();

		if (properties.containsKey("class_names"))
		{
			Object val = properties.get("class_names");
			if (val instanceof List)
			{
				for (Object item : (List<?>) val)
					classNames.add(item.toString().toLowerCase());
			}
		}
		else
		{
			classNames.add("all");
		}

		if (properties.containsKey("back_buffer_time"))
			this.backBufferTime = Long.parseLong(properties.get("back_buffer_time").toString());
		if (properties.containsKey("post_detection_duration"))
			this.postDetectionDuration = Long.parseLong(properties.get("post_detection_duration").toString());
		if (properties.containsKey("lost_object_duration_ms"))
			this.lostObjectDurationMs = Long.parseLong(properties.get("lost_object_duration_ms").toString());
		if (properties.containsKey("recording_name_template"))
			this.recordingNameTemplate = properties.get("recording_name_template").toString();
		if (properties.containsKey("record_stream_name"))
		{
			String pattern = properties.get("record_stream_name").toString();
			if (!pattern.isEmpty())
			{
				try
				{
					this.recordStreamNamePattern = Pattern.compile(pattern);
				}
				catch (Exception e)
				{
					logger.warn(logPrefix + "Invalid record_stream_name regex '" + pattern + "': " + e.getMessage()
							+ ". Will record source stream.");
				}
			}
		}
		if (properties.containsKey("output_path"))
		{
			String path = properties.get("output_path").toString();
			if (!path.isEmpty())
				this.outputPath = path;
		}
		if (properties.containsKey("webhooks"))
			this.webhooksEnabled = Boolean.parseBoolean(properties.get("webhooks").toString());

		if (this.webhooksEnabled)
		{
			this.webhookListener = (WebhookListener) Server.getInstance().getProperties().getProperty("WebhookListener");
			if (webhookListener == null)
				logger.warn(logPrefix + "No WebhookListener found. Webhooks will be disabled");
		}

		if (recordStreamNamePattern != null && !isTranscoderEnabled())
		{
			logger.warn(logPrefix + "record_stream_name is set but use_transcoder is false."
					+ " Ignoring pattern and recording source stream.");
			recordStreamNamePattern = null;
		}

		logger.info(logPrefix + "Starting EventRecordingPlugin v" + MODULE_VERSION
				+ " classes:" + classNames
				+ " backBuffer:" + backBufferTime + "ms"
				+ " postDetection:" + postDetectionDuration + "ms"
				+ " lostObjectDuration:" + lostObjectDurationMs + "ms");
	}

	@Override
	public void onShutdown()
	{
		cancelStopTimer();
		if (activeRecorder != null && recordingStreamName != null)
		{
			logger.info(logPrefix + "Shutting down, stopping active recorder");
			appInstance.getVHost().getLiveStreamRecordManager()
					.stopRecording(appInstance, recordingStreamName);
		}
		activeObjects.clear();
		pendingDetectionEvents.clear();
		recordingActive = false;
	}

	@Override
	public boolean immediate(DetectionResponse response)
	{
		long detectionTC = (response.detectionWindow != null && response.detectionWindow.fromTimeCode != null)
				? response.detectionWindow.fromTimeCode : -1L;
		long nowMs = System.currentTimeMillis();

		List<ObjectDetectionData> qualified = new ArrayList<>();

		for (Object data : response.getDetections())
		{
			if (data instanceof ObjectDetectionData detection)
			{
				if (detection.trackId == null)
					continue;
				if (!classNames.contains("all") && !classNames.contains(detection.className.toLowerCase()))
					continue;

				qualified.add(detection);
			}
		}

		// Update lastSeen for qualified objects; collect tracking IDs of newly-seen objects.
		List<Integer> newObjectIds = new ArrayList<>();
		for (ObjectDetectionData d : qualified)
		{
			ObjectInfo info = activeObjects.get(d.trackId);
			if (info == null)
			{
				info = new ObjectInfo();
				activeObjects.put(d.trackId, info);
				newObjectIds.add(d.trackId);
			}
			info.className = d.className;
			info.lastConfidence = d.confidence;
			info.lastSeenTC = detectionTC;
			info.lastSeenAtMs = nowMs;
		}

		// Evict objects not seen within lostObjectDurationMs. This runs every call (regardless
		// of whether qualified is empty) and uses wall-clock time, so VIF's mix of empty and
		// untracked responses doesn't artificially advance or suppress the eviction clock.
		List<Integer> lostObjectIds = new ArrayList<>();
		List<ObjectInfo> lostObjectInfos = new ArrayList<>();
		Iterator<Map.Entry<Integer, ObjectInfo>> it = activeObjects.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Integer, ObjectInfo> entry = it.next();
			if (nowMs - entry.getValue().lastSeenAtMs > lostObjectDurationMs)
			{
				lostObjectIds.add(entry.getKey());
				lostObjectInfos.add(entry.getValue());
				it.remove();
			}
		}

		// Start the recorder before firing detection.start so the webhooks include
		// the recording context.
		if (!qualified.isEmpty() && activeRecorder == null)
		{
			// LiveStreamRecordManager.startRecording() is asynchronous -- the actual recorder
			// start happens on a worker thread. Detection webhooks for new objects are queued
			// in pendingDetectionEvents and flushed in onSegmentStart once the file is open.
			startRecording();
		}
		else if (!qualified.isEmpty())
		{
			// Extend: cancel pending stop
			cancelStopTimer();
		}

		for (Integer trackingId : newObjectIds)
		{
			ObjectInfo info = activeObjects.get(trackingId);
			if (info != null)
				enqueueOrSendDetectionWebhook("detection.start", trackingId,
						info.className, info.lastConfidence, info.lastSeenTC);
		}

		for (int i = 0; i < lostObjectIds.size(); i++)
		{
			ObjectInfo info = lostObjectInfos.get(i);
			enqueueOrSendDetectionWebhook("detection.stop", lostObjectIds.get(i),
					info.className, info.lastConfidence, info.lastSeenTC);
		}

		if (activeObjects.isEmpty() && activeRecorder != null && stopTimer == null)
		{
			scheduleStop();
		}

		return !qualified.isEmpty();
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

	private void startRecording()
	{
		try
		{
			computeRecordingStart();

			recordingStreamName = resolveRecordStreamName();

			Set<Integer> trackingIds = new LinkedHashSet<>(activeObjects.keySet());
			Set<String> classNamesSnapshot = new LinkedHashSet<>();
			for (ObjectInfo info : activeObjects.values())
				classNamesSnapshot.add(info.className);

			StreamRecorderParameters params = new StreamRecorderParameters(appInstance);
			params.fileFormat = IStreamRecorderConstants.FORMAT_MP4;
			params.backBufferTime = this.backBufferTime;
			params.startOnKeyFrame = true;
			params.recordData = true;
			params.segmentationType = IStreamRecorderConstants.SEGMENT_NONE;
			params.fileTemplate = this.recordingNameTemplate;
			params.notifyListener = this;
			params.fileVersionDelegate = new EventRecordingFileVersionDelegate(trackingIds, classNamesSnapshot, recordingStartMs);

			if (this.outputPath != null)
				params.outputPath = this.outputPath;

			logger.info(logPrefix + "Starting recording for stream:" + recordingStreamName
					+ " classes:" + classNamesSnapshot
					+ " trackingIds:" + trackingIds
					+ " backBuffer:" + backBufferTime + "ms"
					+ " recordingStartMs:" + recordingStartMs
					+ " earliestKeyTC:" + earliestKeyTC);

			appInstance.getVHost().getLiveStreamRecordManager()
					.startRecording(appInstance, recordingStreamName, params);
		}
		catch (Exception e)
		{
			logger.error(logPrefix + "Failed to start recording: " + e.getMessage(), e);
		}
	}

	/**
	 * Find the earliest video keyframe within backBufferTime of the most recent keyframe,
	 * matching what LiveStreamRecorder will actually pull from the stream's packet buffer.
	 * Sets recordingStartMs (wall clock of the first frame) and earliestKeyTC (its stream TC).
	 */
	private void computeRecordingStart()
	{
		long offsetMs = 0;
		earliestKeyTC = -1;

		AMFPacket lastKey = stream.getLastKeyFrame();
		AMFPacket lastPacket = stream.getLastPacket();
		if (lastKey != null && lastPacket != null)
		{
			long firstKeyTC = lastKey.getAbsTimecode();
			long lastTC = lastPacket.getAbsTimecode();
			long targetTC = firstKeyTC - backBufferTime;
			earliestKeyTC = firstKeyTC;

			if (backBufferTime > 0)
			{
				List<AMFPacket> packets = stream.getPlayPackets();
				if (packets != null)
				{
					for (int i = packets.size() - 1; i >= 0; i--)
					{
						AMFPacket p = packets.get(i);
						long tc = p.getAbsTimecode();
						if (tc >= firstKeyTC) continue;
						if (tc < targetTC) break;
						if (p.isVideo() && FLVUtils.isVideoKeyFrame(p))
							earliestKeyTC = tc;
					}
				}
			}

			offsetMs = lastTC - earliestKeyTC;
		}

		recordingStartMs = System.currentTimeMillis() - offsetMs;
	}

	private void scheduleStop()
	{
		cancelStopTimer();
		stopTimer = new Timer("EventRecordingPlugin-StopTimer-" + streamName);
		stopTimer.schedule(new TimerTask()
		{
			@Override
			public void run()
			{
				stopTimer = null;
				if (activeRecorder != null && recordingStreamName != null)
				{
					logger.info(logPrefix + "Post-detection timer expired, stopping recording");
					appInstance.getVHost().getLiveStreamRecordManager()
							.stopRecording(appInstance, recordingStreamName);
				}
			}
		}, postDetectionDuration);
	}

	private boolean isTranscoderEnabled()
	{
		try
		{
			ModuleVideoIntelligence vifModule = ModuleVideoIntelligence.getModule(
					vHostName, appName, appInstanceName);
			if (vifModule == null)
				return false;
			VideoIntelligenceService vifService = vifModule.getVifService(streamName);
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

	private String resolveRecordStreamName()
	{
		if (recordStreamNamePattern == null)
			return streamName;

		try
		{
			List<String> streamNames = appInstance.getPublishStreamNames();
			for (String name : streamNames)
			{
				if (recordStreamNamePattern.matcher(name).matches())
					return name;
			}
		}
		catch (Exception e)
		{
			logger.warn(logPrefix + "Error resolving record stream name: " + e.getMessage());
		}

		logger.warn(logPrefix + "No published stream matches pattern '" + recordStreamNamePattern.pattern()
				+ "'. Falling back to source stream: " + streamName);
		return streamName;
	}

	private void cancelStopTimer()
	{
		if (stopTimer != null)
		{
			stopTimer.cancel();
			stopTimer = null;
		}
	}

	// IStreamRecorderActionNotify callbacks

	@Override
	public void onCreateRecorder(IStreamRecorder recorder)
	{
		logger.info(logPrefix + "Recorder created");
	}

	@Override
	public void onStartRecorder(IStreamRecorder recorder)
	{
		this.activeRecorder = recorder;
		logger.info(logPrefix + "Recording started: " + recorder.getFilePath());
		// No webhook here -- WSE emits recording.started automatically.
	}

	@Override
	public void onStopRecorder(IStreamRecorder recorder)
	{
		logger.info(logPrefix + "Recording stopped: " + recorder.getCurrentFile()
				+ " duration:" + recorder.getCurrentDuration() + "ms"
				+ " size:" + recorder.getCurrentSize() + " bytes");
		// No webhook here -- WSE emits recording.stopped automatically.

		this.activeRecorder = null;
		this.recordingActive = false;
		this.recordingStreamName = null;
		this.recordingStartMs = 0;
		this.earliestKeyTC = -1;
		activeObjects.clear();
		pendingDetectionEvents.clear();
		cancelStopTimer();
	}

	@Override
	public void onSplitRecorder(IStreamRecorder recorder)
	{
		logger.debug(logPrefix + "Recorder split");
	}

	@Override
	public void onSwitchRecorder(IStreamRecorder recorder, IMediaStream newStream)
	{
		logger.debug(logPrefix + "Recorder switched to stream: " + newStream.getName());
	}

	@Override
	public void onSegmentStart(IStreamRecorder recorder)
	{
		logger.info(logPrefix + "Segment started: " + recorder.getFilePath());
		// The file is now open for writing -- safe to emit detection webhooks with the
		// full recording context. Flush any detection events that were queued while we
		// were waiting for the recorder to fully start.
		this.recordingActive = true;
		flushPendingDetectionEvents();
	}

	@Override
	public void onSegmentEnd(IStreamRecorder recorder)
	{
		logger.debug(logPrefix + "Segment ended: " + recorder.getCurrentFile());
		this.recordingActive = false;
	}

	@Override
	public void onRecorderError(IStreamRecorder recorder)
	{
		logger.error(logPrefix + "Recorder error: " + recorder.getErrorString());
		pendingDetectionEvents.clear();
		recordingActive = false;
	}

	// Webhook

	/**
	 * If the recording is fully started (segment file open), send the detection webhook now.
	 * Otherwise queue it; the queue is flushed in onSegmentStart.
	 */
	private void enqueueOrSendDetectionWebhook(String action, int trackingId,
			String className, Double confidence, long detectionTC)
	{
		if (!webhooksEnabled)
			return;

		if (recordingActive)
		{
			sendDetectionWebhook(action, trackingId, className, confidence, detectionTC);
		}
		else
		{
			pendingDetectionEvents.add(new PendingDetection(action, trackingId, className, confidence, detectionTC));
		}
	}

	private void flushPendingDetectionEvents()
	{
		if (pendingDetectionEvents.isEmpty())
			return;

		for (PendingDetection pd : pendingDetectionEvents)
		{
			sendDetectionWebhook(pd.action, pd.trackingId, pd.className, pd.confidence, pd.detectionTC);
		}
		pendingDetectionEvents.clear();
	}

	private void sendDetectionWebhook(String action, int trackingId, String className, Double confidence,
			long detectionTC)
	{
		Map<String, Object> payload = new HashMap<>();
		payload.put("action", action);
		payload.put("stream_name", streamName);

		Map<String, Object> det = new HashMap<>();
		det.put("tracking_id", trackingId);
		det.put("class_name", className);
		det.put("confidence", confidence);
		if (detectionTC >= 0 && earliestKeyTC >= 0)
			det.put("recording_offset_ms", detectionTC - earliestKeyTC);
		payload.put("detection", det);

		payload.put("active_object_count", activeObjects.size());

		if (activeRecorder != null)
		{
			String file = activeRecorder.getFilePath();
			if (file != null)
			{
				Map<String, Object> rec = new HashMap<>();
				rec.put("file", file);
				rec.put("started_at", Instant.ofEpochMilli(recordingStartMs).toString());
				payload.put("recording", rec);
			}
		}

		sendWebhook(payload);
	}

	private void sendWebhook(Map<String, Object> payload)
	{
		if (webhookListener == null)
			return;

		try
		{
			Map<String, Object> data = new HashMap<>();
			data.put("event_recording_data", payload);

			WebhookEvent event = new WebhookEvent(WebhookEventName.VI_DETECTION);
			event.setContext(WebhookEventContext.forEntity(
					vHostName,
					appName,
					appInstanceName,
					streamName,
					WebhookEventEntity.OBJECTS
			));
			event.setData(data);
			webhookListener.addEvent(event);
		}
		catch (Exception e)
		{
			logger.error(logPrefix + "Webhook Error: " + e.getMessage(), e);
		}
	}
}
