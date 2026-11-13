/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a stream of detection responses into presence events: one event from the first
 * response that matches until the matches have been absent for {@code lostMs}.
 *
 * <p>Everything is in the responses' own time — media milliseconds on a file job, the stream
 * timecode on a live stream — so the same rules cut the same clips whether a file is analysed
 * faster or slower than real time. Every observation advances the clock, an empty one included,
 * which is what lets the gate notice a loss without a timer of its own.
 *
 * <p>No Wowza types: the class is the same for both hosts and unit-testable without either.
 */
public final class PresenceGate
{
	/** The matching rule and the timing an event is judged by. */
	public static final class Settings
	{
		private final Set<String> classes;
		private final int minCount;
		private final boolean requireTrack;
		private final long lostMs;
		private final long mergeGapMs;
		private final Long maxClipMs;

		public Settings(Set<String> classes, int minCount, boolean requireTrack, long lostMs, long mergeGapMs,
				Long maxClipMs)
		{
			if (minCount < 1)
				throw new IllegalArgumentException("min_count must be at least 1, not " + minCount);
			if (lostMs < 0 || mergeGapMs < 0)
				throw new IllegalArgumentException("lost_ms and merge_gap_ms cannot be negative");
			if (maxClipMs != null && maxClipMs < 1)
				throw new IllegalArgumentException("max_clip_ms must be positive, not " + maxClipMs);
			this.classes = lowerCased(classes);
			this.minCount = minCount;
			this.requireTrack = requireTrack;
			this.lostMs = lostMs;
			this.mergeGapMs = mergeGapMs;
			this.maxClipMs = maxClipMs;
		}

		public Set<String> classes()
		{
			return classes;
		}

		public int minCount()
		{
			return minCount;
		}

		public boolean requireTrack()
		{
			return requireTrack;
		}

		public long lostMs()
		{
			return lostMs;
		}

		public long mergeGapMs()
		{
			return mergeGapMs;
		}

		public Long maxClipMs()
		{
			return maxClipMs;
		}

		/** Whether a detection of this class counts; an empty list or {@code all} matches every class. */
		public boolean matches(String className)
		{
			if (classes.isEmpty() || classes.contains("all"))
				return true;
			return className != null && classes.contains(className.toLowerCase(Locale.ROOT));
		}

		private static Set<String> lowerCased(Set<String> names)
		{
			Set<String> lowered = new LinkedHashSet<>();
			if (names != null)
			{
				for (String name : names)
				{
					if (name != null && !name.isBlank())
						lowered.add(name.trim().toLowerCase(Locale.ROOT));
				}
			}
			return Collections.unmodifiableSet(lowered);
		}
	}

	/** One detection as the gate sees it: a class and, with tracking, the track it belongs to. */
	public static final class Seen
	{
		private final String className;
		private final Integer trackId;

		public Seen(String className, Integer trackId)
		{
			this.className = className;
			this.trackId = trackId;
		}

		public String className()
		{
			return className;
		}

		public Integer trackId()
		{
			return trackId;
		}
	}

	/** What one observation changed: a {@link Start} or an {@link End}. */
	public interface Transition
	{
	}

	/** Presence began at {@code fromMs}. */
	public static final class Start implements Transition
	{
		private final long fromMs;
		private final Set<String> classes;
		private final Set<Integer> trackIds;

		public Start(long fromMs, Set<String> classes, Set<Integer> trackIds)
		{
			this.fromMs = fromMs;
			this.classes = classes;
			this.trackIds = trackIds;
		}

		public long fromMs()
		{
			return fromMs;
		}

		public Set<String> classes()
		{
			return classes;
		}

		public Set<Integer> trackIds()
		{
			return trackIds;
		}

		@Override
		public boolean equals(Object other)
		{
			if (!(other instanceof Start))
				return false;
			Start that = (Start)other;
			return fromMs == that.fromMs && classes.equals(that.classes) && trackIds.equals(that.trackIds);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(fromMs, classes, trackIds);
		}

		@Override
		public String toString()
		{
			return "Start[" + fromMs + "ms " + classes + " " + trackIds + "]";
		}
	}

	/**
	 * The presence that began at {@code fromMs} is over: nothing matched since
	 * {@code toMs - lostMs}, or the event reached {@code maxClipMs} — {@code capped}, which is
	 * the one end a backend may not hold open for the event that follows it.
	 */
	public static final class End implements Transition
	{
		private final long fromMs;
		private final long toMs;
		private final Set<String> classes;
		private final Set<Integer> trackIds;
		private final boolean capped;

		public End(long fromMs, long toMs, Set<String> classes, Set<Integer> trackIds)
		{
			this(fromMs, toMs, classes, trackIds, false);
		}

		public End(long fromMs, long toMs, Set<String> classes, Set<Integer> trackIds, boolean capped)
		{
			this.fromMs = fromMs;
			this.toMs = toMs;
			this.classes = classes;
			this.trackIds = trackIds;
			this.capped = capped;
		}

		public long fromMs()
		{
			return fromMs;
		}

		public long toMs()
		{
			return toMs;
		}

		public Set<String> classes()
		{
			return classes;
		}

		public Set<Integer> trackIds()
		{
			return trackIds;
		}

		public boolean capped()
		{
			return capped;
		}

		@Override
		public boolean equals(Object other)
		{
			if (!(other instanceof End))
				return false;
			End that = (End)other;
			return fromMs == that.fromMs && toMs == that.toMs && capped == that.capped
					&& classes.equals(that.classes) && trackIds.equals(that.trackIds);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(fromMs, toMs, classes, trackIds, capped);
		}

		@Override
		public String toString()
		{
			return "End[" + fromMs + "-" + toMs + "ms " + classes + " " + trackIds + (capped ? " capped" : "") + "]";
		}
	}

	private final Settings settings;

	private boolean active;
	private long fromMs;
	private long lastMatchMs;
	private long lastObservedMs = Long.MIN_VALUE;
	private final Set<String> classes = new LinkedHashSet<>();
	private final Set<Integer> trackIds = new LinkedHashSet<>();

	public PresenceGate(Settings settings)
	{
		this.settings = settings;
	}

	public boolean active()
	{
		return active;
	}

	/** Whether the last observation matched: the open event was seen in it. */
	public boolean matchedLast()
	{
		return active && lastMatchMs == lastObservedMs;
	}

	/** The open event as it stands — where it began and what it has seen so far — or null. */
	public Start open()
	{
		return active ? new Start(fromMs, snapshot(classes), snapshot(trackIds)) : null;
	}

	/**
	 * Feeds one response. {@code atMs} is the response's position on the timeline; a position
	 * behind the last one is a discontinuity, which ends the open event where it was last seen
	 * and starts the clock again.
	 *
	 * <p>The loss is judged against the last match, not the last response: a host that withholds
	 * empty responses, or answers sparsely, still gets two events out of two presences.
	 */
	public List<Transition> observe(long atMs, List<Seen> detections)
	{
		List<Transition> transitions = new ArrayList<>(2);
		if (active && lastObservedMs != Long.MIN_VALUE && atMs < lastObservedMs)
			transitions.add(end(lastMatchMs, false));
		lastObservedMs = atMs;

		if (active)
		{
			boolean lost = atMs - lastMatchMs > settings.lostMs() + settings.mergeGapMs();
			boolean capped = settings.maxClipMs() != null && atMs - fromMs >= settings.maxClipMs();
			long lostAtMs = lastMatchMs + settings.lostMs();
			if (capped && (!lost || fromMs + settings.maxClipMs() < lostAtMs))
				transitions.add(end(fromMs + settings.maxClipMs(), true));
			else if (lost)
				transitions.add(end(lostAtMs, false));
		}
		if (countMatching(detections) < settings.minCount())
			return transitions;

		boolean opened = !active;
		if (opened)
		{
			active = true;
			fromMs = atMs;
		}
		lastMatchMs = atMs;
		remember(detections);
		if (opened)
			transitions.add(new Start(fromMs, snapshot(classes), snapshot(trackIds)));
		return transitions;
	}

	/** Ends the open event, if any, as a host that is winding down asks: the timeline stops here. */
	public List<Transition> close()
	{
		if (!active)
			return List.of();
		return List.of(end(lastMatchMs + settings.lostMs(), false));
	}

	private End end(long toMs, boolean capped)
	{
		End end = new End(fromMs, Math.max(fromMs, toMs), snapshot(classes), snapshot(trackIds), capped);
		active = false;
		classes.clear();
		trackIds.clear();
		return end;
	}

	private int countMatching(List<Seen> detections)
	{
		int count = 0;
		if (detections == null)
			return 0;
		for (Seen seen : detections)
		{
			if (seen == null || !settings.matches(seen.className()))
				continue;
			if (settings.requireTrack() && seen.trackId() == null)
				continue;
			count++;
		}
		return count;
	}

	private void remember(List<Seen> detections)
	{
		for (Seen seen : detections)
		{
			if (seen == null || !settings.matches(seen.className()))
				continue;
			if (settings.requireTrack() && seen.trackId() == null)
				continue;
			if (seen.className() != null)
				classes.add(seen.className().toLowerCase(Locale.ROOT));
			if (seen.trackId() != null)
				trackIds.add(seen.trackId());
		}
	}

	private static <T> Set<T> snapshot(Set<T> values)
	{
		return Collections.unmodifiableSet(new LinkedHashSet<>(values));
	}
}
