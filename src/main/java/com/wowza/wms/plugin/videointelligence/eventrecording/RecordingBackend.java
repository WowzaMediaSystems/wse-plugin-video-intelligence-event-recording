/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording;

import java.util.Set;

import com.wowza.wms.plugin.videointelligence.api.ShutdownEvent;

/**
 * What turns a presence event into a file: the Engine's recorder on a live stream, a cut of
 * the source on a file job. The listener drives it in the responses' own time.
 */
public interface RecordingBackend
{
	/** Presence began; {@code nowMs} is the timeline position of the response that said so. */
	void onStart(PresenceGate.Start start, long nowMs);

	/** Presence ended; the clip is owed the post-roll past {@code end.toMs()}. */
	void onEnd(PresenceGate.End end, long nowMs);

	/** The timeline advanced to {@code nowMs} with nothing else to say. */
	void onClock(long nowMs);

	/**
	 * The host is done with the listener. A job that ended, however it ended, still writes what
	 * it owes; a listener torn down early drops it.
	 */
	void finish(ShutdownEvent.Reason reason);

	/** How a backend reports the files it produces. */
	interface Reporter
	{
		/**
		 * A file is being written for the event that began at {@code fromMs} on the timeline.
		 * {@code startedAtEpochMs} is the wall clock of its first frame when the host has one
		 * (a live stream), null on a file job.
		 */
		void recordingStarted(String file, long fromMs, Long startedAtEpochMs, Set<String> classNames,
				Set<Integer> trackIds);

		/** The file is complete; {@code toMs} is where it ends on the timeline. */
		void recordingStopped(String file, long fromMs, long toMs, long sizeBytes);
	}
}
