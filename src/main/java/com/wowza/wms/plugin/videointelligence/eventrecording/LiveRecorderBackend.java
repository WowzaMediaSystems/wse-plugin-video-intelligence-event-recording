/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.wowza.util.FLVUtils;
import com.wowza.wms.amf.AMFPacket;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.livestreamrecord.manager.IStreamRecorder;
import com.wowza.wms.livestreamrecord.manager.IStreamRecorderActionNotify;
import com.wowza.wms.livestreamrecord.manager.IStreamRecorderConstants;
import com.wowza.wms.livestreamrecord.manager.StreamRecorderParameters;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.plugin.videointelligence.api.ShutdownEvent;
import com.wowza.wms.plugin.videointelligence.event.EventRecordingFileVersionDelegate;
import com.wowza.wms.stream.IMediaStream;

/**
 * Records a live stream's presence events through the Engine's own recorder: the back buffer
 * supplies the pre-roll, a scheduled stop the post-roll, and the recorder's callbacks say when
 * the file exists and when it is complete.
 *
 * <p>The recorder manager starts asynchronously, so {@code activeRecorder} is null for a while
 * after a start was asked for. {@code starting} is set on the calling thread before the start is
 * queued, which is what keeps a second response in that window from asking for a second
 * recorder. Recorder calls block, and the listener runs on the thread that paces the
 * frame/response cycle, so they are queued on the listener's executor.
 */
public final class LiveRecorderBackend implements RecordingBackend, IStreamRecorderActionNotify
{
	private final WMSLogger logger;
	private final String logPrefix;
	private final IApplicationInstance appInstance;
	private final IMediaStream stream;
	private final String streamName;
	private final ScheduledExecutorService executor;
	private final Reporter reporter;
	private final long preRollMs;
	private final long postRollMs;
	private final String nameTemplate;
	private final String outputPath;
	private final Pattern recordStream;

	/** How long a recorder that failed to start is left alone before the event asks again. */
	static final long START_RETRY_MS = 1000;

	private final Object lock = new Object();
	private boolean finished;
	private boolean starting;
	private long retryNotBeforeMs;
	private IStreamRecorder activeRecorder;
	private String recordingStreamName;
	private ScheduledFuture<?> pendingStop;
	private long recordingStartMs;
	// Where the file begins on the stream's timeline: the keyframe the back buffer reached.
	private long fileFromTc;
	private long eventFromMs;
	private Set<String> eventClasses = Set.of();
	private Set<Integer> eventTrackIds = Set.of();

	public LiveRecorderBackend(WMSLogger logger, String logPrefix, IApplicationInstance appInstance,
			IMediaStream stream, ScheduledExecutorService executor, Reporter reporter, long preRollMs,
			long postRollMs, String nameTemplate, String outputPath, Pattern recordStream)
	{
		this.logger = logger;
		this.logPrefix = logPrefix;
		this.appInstance = appInstance;
		this.stream = stream;
		this.streamName = stream.getName();
		this.executor = executor;
		this.reporter = reporter;
		this.preRollMs = preRollMs;
		this.postRollMs = postRollMs;
		this.nameTemplate = nameTemplate;
		this.outputPath = outputPath;
		this.recordStream = recordStream;
	}

	/** Whether a recorder is running or has been asked for. */
	public boolean engaged()
	{
		synchronized (lock)
		{
			return starting || activeRecorder != null;
		}
	}

	/**
	 * Whether an open event has no recorder and may ask for one: the start failed, the recorder
	 * errored or was stopped from outside, or the event began while the previous file was
	 * still closing.
	 */
	public boolean idle()
	{
		synchronized (lock)
		{
			return !starting && activeRecorder == null && System.currentTimeMillis() >= retryNotBeforeMs;
		}
	}

	@Override
	public void onStart(PresenceGate.Start start, long nowMs)
	{
		synchronized (lock)
		{
			cancelPendingStop();
			if (finished || starting || activeRecorder != null)
				return;
			starting = true;
			eventFromMs = start.fromMs();
			eventClasses = start.classes();
			eventTrackIds = start.trackIds();
		}
		executor.execute(() -> startRecording(start));
	}

	@Override
	public void onEnd(PresenceGate.End end, long nowMs)
	{
		long delayMs = Math.max(0, end.toMs() + postRollMs - nowMs);
		synchronized (lock)
		{
			cancelPendingStop();
			if (!starting && activeRecorder == null)
				return;
			if (end.capped())
			{
				// Not a pending stop: the event that follows must not cancel it. Its own file is
				// asked for once this one has closed, the back buffer covering the gap.
				executor.execute(() -> stopRecording("max_clip_ms reached"));
				return;
			}
			pendingStop = executor.schedule(() -> stopRecording("post-detection window elapsed"), delayMs,
					TimeUnit.MILLISECONDS);
		}
	}

	@Override
	public void onClock(long nowMs)
	{
	}

	@Override
	public void finish(ShutdownEvent.Reason reason)
	{
		synchronized (lock)
		{
			finished = true;
			cancelPendingStop();
		}
		// On the caller's thread: the executor is about to be shut down, and a recorder left
		// behind would be restarted by the manager when the stream publishes again.
		stopRecording("listener shutting down");
	}

	private void startRecording(PresenceGate.Start start)
	{
		try
		{
			long earliestKeyTc = earliestKeyframeTimecode();
			recordingStartMs = firstFrameWallClock(earliestKeyTc);
			String target = resolveRecordStreamName();

			StreamRecorderParameters params = new StreamRecorderParameters(appInstance);
			params.fileFormat = IStreamRecorderConstants.FORMAT_MP4;
			params.backBufferTime = preRollMs;
			params.startOnKeyFrame = true;
			params.recordData = true;
			params.segmentationType = IStreamRecorderConstants.SEGMENT_NONE;
			params.fileTemplate = nameTemplate;
			params.notifyListener = this;
			params.fileVersionDelegate = new EventRecordingFileVersionDelegate(start.trackIds(), start.classes(),
					recordingStartMs);
			if (outputPath != null)
				params.outputPath = outputPath;

			synchronized (lock)
			{
				if (finished)
				{
					starting = false;
					return;
				}
				recordingStreamName = target;
				fileFromTc = earliestKeyTc >= 0 ? earliestKeyTc : Math.max(0, eventFromMs - preRollMs);
			}
			logger.info(logPrefix + "Starting recording for stream:" + target + " classes:" + start.classes()
					+ " trackingIds:" + start.trackIds() + " backBuffer:" + preRollMs + "ms recordingStartMs:"
					+ recordingStartMs + " earliestKeyTC:" + earliestKeyTc);
			appInstance.getVHost().getLiveStreamRecordManager().startRecording(appInstance, target, params);
			// finish() may have looked for a recorder to stop before this one was registered.
			boolean abandoned;
			synchronized (lock)
			{
				abandoned = finished;
			}
			if (abandoned)
				stopRecording("listener shut down while the recorder was starting");
		}
		catch (Exception e)
		{
			logger.error(logPrefix + "Failed to start recording: " + e, e);
			synchronized (lock)
			{
				starting = false;
				recordingStreamName = null;
				retryNotBeforeMs = System.currentTimeMillis() + START_RETRY_MS;
			}
		}
	}

	private void stopRecording(String why)
	{
		String target;
		synchronized (lock)
		{
			pendingStop = null;
			target = recordingStreamName;
			if (target == null || (!starting && activeRecorder == null))
				return;
		}
		try
		{
			// The manager's stop both stops and removes the recorder; stopping the recorder
			// directly leaves it registered, to restart on the next publish.
			logger.info(logPrefix + "Stopping recording of " + target + ": " + why);
			appInstance.getVHost().getLiveStreamRecordManager().stopRecording(appInstance, target);
		}
		catch (Exception e)
		{
			logger.warn(logPrefix + "Could not stop recording of " + target + ": " + e);
		}
	}

	private void cancelPendingStop()
	{
		if (pendingStop != null)
		{
			pendingStop.cancel(false);
			pendingStop = null;
		}
	}

	/**
	 * The timecode of the earliest video keyframe within the pre-roll of the latest one — the
	 * frame the recorder's back buffer will actually begin on — or -1 without packets.
	 */
	private long earliestKeyframeTimecode()
	{
		AMFPacket lastKey = stream.getLastKeyFrame();
		if (lastKey == null)
			return -1;
		long firstKeyTc = lastKey.getAbsTimecode();
		long earliest = firstKeyTc;
		if (preRollMs <= 0)
			return earliest;
		long targetTc = firstKeyTc - preRollMs;
		List<AMFPacket> packets = stream.getPlayPackets();
		if (packets == null)
			return earliest;
		for (int i = packets.size() - 1; i >= 0; i--)
		{
			AMFPacket packet = packets.get(i);
			long tc = packet.getAbsTimecode();
			if (tc >= firstKeyTc)
				continue;
			if (tc < targetTc)
				break;
			if (packet.isVideo() && FLVUtils.isVideoKeyFrame(packet))
				earliest = tc;
		}
		return earliest;
	}

	private long firstFrameWallClock(long earliestKeyTc)
	{
		AMFPacket lastPacket = stream.getLastPacket();
		long offsetMs = earliestKeyTc < 0 || lastPacket == null ? 0 : lastPacket.getAbsTimecode() - earliestKeyTc;
		return System.currentTimeMillis() - offsetMs;
	}

	private String resolveRecordStreamName()
	{
		if (recordStream == null)
			return streamName;
		try
		{
			for (String name : appInstance.getPublishStreamNames())
			{
				if (recordStream.matcher(name).matches())
					return name;
			}
		}
		catch (Exception e)
		{
			logger.warn(logPrefix + "Error resolving record stream name: " + e);
		}
		logger.warn(logPrefix + "No published stream matches pattern '" + recordStream.pattern()
				+ "'. Falling back to source stream: " + streamName);
		return streamName;
	}

	@Override
	public void onCreateRecorder(IStreamRecorder recorder)
	{
		logger.info(logPrefix + "Recorder created");
	}

	@Override
	public void onStartRecorder(IStreamRecorder recorder)
	{
		synchronized (lock)
		{
			activeRecorder = recorder;
			starting = false;
		}
		logger.info(logPrefix + "Recording started: " + recorder.getFilePath());
	}

	@Override
	public void onSegmentStart(IStreamRecorder recorder)
	{
		// The file is open from here; getFilePath() is the path being written, which
		// getCurrentFile() does not answer until later in the recorder's life.
		Set<String> classes;
		Set<Integer> trackIds;
		long fromMs;
		long startedAt;
		synchronized (lock)
		{
			classes = eventClasses;
			trackIds = eventTrackIds;
			fromMs = fileFromTc;
			startedAt = recordingStartMs;
		}
		reporter.recordingStarted(recorder.getFilePath(), fromMs, startedAt, classes, trackIds);
	}

	@Override
	public void onSegmentEnd(IStreamRecorder recorder)
	{
	}

	@Override
	public void onStopRecorder(IStreamRecorder recorder)
	{
		long fromMs;
		synchronized (lock)
		{
			fromMs = fileFromTc;
			activeRecorder = null;
			starting = false;
			recordingStreamName = null;
			cancelPendingStop();
		}
		reporter.recordingStopped(recorder.getCurrentFile(), fromMs, fromMs + recorder.getCurrentDuration(),
				recorder.getCurrentSize());
	}

	@Override
	public void onSplitRecorder(IStreamRecorder recorder)
	{
	}

	@Override
	public void onSwitchRecorder(IStreamRecorder recorder, IMediaStream newStream)
	{
	}

	@Override
	public void onRecorderError(IStreamRecorder recorder)
	{
		logger.error(logPrefix + "Recorder error: " + recorder.getErrorString());
		synchronized (lock)
		{
			activeRecorder = null;
			starting = false;
			recordingStreamName = null;
			retryNotBeforeMs = System.currentTimeMillis() + START_RETRY_MS;
			cancelPendingStop();
		}
	}
}
