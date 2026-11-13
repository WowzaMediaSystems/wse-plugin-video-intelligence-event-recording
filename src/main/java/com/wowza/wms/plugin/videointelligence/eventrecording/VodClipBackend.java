/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.plugin.videointelligence.api.ShutdownEvent;
import com.wowza.wms.plugin.videointelligence.eventrecording.clip.Mp4FileClipper;

/**
 * Cuts a file job's presence events out of its source. Nothing is written while an event is
 * open: the cut is issued once the job's clock has passed the event's post-roll — every later
 * response advances it — or when the job completes, so the clip has exact bounds and is written
 * once, under its final name only when complete. A job that fails or is cancelled still cuts
 * the events it had seen, since a resume continues after them.
 */
public final class VodClipBackend implements RecordingBackend
{
	/** A cut waiting for the timeline to reach {@code readyAtMs}. */
	private static final class PendingCut
	{
		private final PresenceGate.End end;
		private final long readyAtMs;

		PendingCut(PresenceGate.End end, long readyAtMs)
		{
			this.end = end;
			this.readyAtMs = readyAtMs;
		}

		PresenceGate.End end()
		{
			return end;
		}

		long readyAtMs()
		{
			return readyAtMs;
		}
	}

	private final WMSLogger logger;
	private final String logPrefix;
	private final Path source;
	private final String sourceName;
	private final Path outputDir;
	private final Executor executor;
	private final Reporter reporter;
	private final long preRollMs;
	private final long postRollMs;
	private final String nameTemplate;
	private final DateTimeFormatter dateTimeFormat;

	private final Object lock = new Object();
	private final List<PendingCut> pending = new ArrayList<>();

	public VodClipBackend(WMSLogger logger, String logPrefix, Path source, Path outputDir, Executor executor,
			Reporter reporter, long preRollMs, long postRollMs, String nameTemplate, DateTimeFormatter dateTimeFormat)
	{
		this.logger = logger;
		this.logPrefix = logPrefix;
		this.source = source;
		this.sourceName = baseName(source);
		this.outputDir = outputDir;
		this.executor = executor;
		this.reporter = reporter;
		this.preRollMs = preRollMs;
		this.postRollMs = postRollMs;
		this.nameTemplate = nameTemplate;
		this.dateTimeFormat = dateTimeFormat;
	}

	@Override
	public void onStart(PresenceGate.Start start, long nowMs)
	{
	}

	@Override
	public void onEnd(PresenceGate.End end, long nowMs)
	{
		synchronized (lock)
		{
			pending.add(new PendingCut(end, end.toMs() + postRollMs));
		}
		onClock(nowMs);
	}

	@Override
	public void onClock(long nowMs)
	{
		List<PendingCut> due = new ArrayList<>();
		synchronized (lock)
		{
			for (Iterator<PendingCut> it = pending.iterator(); it.hasNext();)
			{
				PendingCut cut = it.next();
				if (cut.readyAtMs() <= nowMs)
				{
					due.add(cut);
					it.remove();
				}
			}
		}
		for (PendingCut cut : due)
			executor.execute(() -> cut(cut.end()));
	}

	@Override
	public void finish(ShutdownEvent.Reason reason)
	{
		List<PendingCut> owed;
		synchronized (lock)
		{
			owed = new ArrayList<>(pending);
			pending.clear();
		}
		// A listener being torn down for throwing is not trusted with more work. A job that
		// failed or was cancelled still analysed what it owes: a resume continues after it and
		// would never see these events again.
		if (reason == ShutdownEvent.Reason.LISTENER_DISABLED)
		{
			if (!owed.isEmpty())
				logger.info(logPrefix + "dropping " + owed.size() + " clip(s) not yet cut: " + reason);
			return;
		}
		for (PendingCut cut : owed)
			executor.execute(() -> cut(cut.end()));
	}

	private void cut(PresenceGate.End end)
	{
		long fromMs = Math.max(0, end.fromMs() - preRollMs);
		long toMs = end.toMs() + postRollMs;
		String name = RecordingNames.clipName(nameTemplate, sourceName, fromMs, end.classes(), end.trackIds(),
				dateTimeFormat);
		Path part = null;
		try
		{
			part = RecordingNames.partFile(outputDir, name);
			Mp4FileClipper.Result written = Mp4FileClipper.clip(source, fromMs, toMs, part);
			Path target = RecordingNames.publish(part, outputDir, name);
			reporter.recordingStarted(target.toString(), written.fromMs(), null, end.classes(), end.trackIds());
			reporter.recordingStopped(target.toString(), written.fromMs(), written.toMs(), Files.size(target));
		}
		catch (IOException | RuntimeException e)
		{
			logger.error(logPrefix + "could not cut " + fromMs + "-" + toMs + "ms of " + source + " as " + name
					+ ": " + e, e);
			discard(part);
		}
	}

	private void discard(Path part)
	{
		if (part == null)
			return;
		try
		{
			Files.deleteIfExists(part);
		}
		catch (IOException | RuntimeException e)
		{
			logger.warn(logPrefix + "could not remove " + part + ": " + e);
		}
	}

	private static String baseName(Path file)
	{
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}
}
