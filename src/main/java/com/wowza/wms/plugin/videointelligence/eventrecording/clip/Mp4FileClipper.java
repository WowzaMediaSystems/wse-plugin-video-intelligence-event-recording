/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.clip;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.mp4parser.Container;
import org.mp4parser.muxer.FileRandomAccessSourceImpl;
import org.mp4parser.muxer.Movie;
import org.mp4parser.muxer.Track;
import org.mp4parser.muxer.builder.DefaultMp4Builder;
import org.mp4parser.muxer.container.mp4.MovieCreator;
import org.mp4parser.muxer.tracks.ClippedTrack;

/**
 * Cuts a window out of an MP4 file by media time without re-encoding: the video and sound
 * tracks are copied sample for sample, the rest are dropped. The cut starts on the video sync
 * sample at or before the requested start, so a clip may begin up to one GOP early and never
 * late; it ends at the first sample at or after the requested end, or at the file's end.
 *
 * <p>This keeps the
 * source's own timing and its audio — it is a recording for people, not an input for a model.
 */
public final class Mp4FileClipper
{
	/** What was written: the clip's bounds in the source's media time and its video sample count. */
	public static final class Result
	{
		private final Path file;
		private final long fromMs;
		private final long toMs;
		private final int videoSamples;

		Result(Path file, long fromMs, long toMs, int videoSamples)
		{
			this.file = file;
			this.fromMs = fromMs;
			this.toMs = toMs;
			this.videoSamples = videoSamples;
		}

		public Path file()
		{
			return file;
		}

		public long fromMs()
		{
			return fromMs;
		}

		public long toMs()
		{
			return toMs;
		}

		public int videoSamples()
		{
			return videoSamples;
		}
	}

	private Mp4FileClipper()
	{
	}

	/**
	 * Writes {@code [fromMs, toMs)} of {@code source} to {@code target}. An end past the file is
	 * the file's end; a start past it is an error, since no sample would be selected.
	 */
	public static Result clip(Path source, long fromMs, long toMs, Path target) throws IOException
	{
		List<AutoCloseable> handles = new ArrayList<>();
		try
		{
			Movie movie = open(source, handles);
			return write(movie, fromMs, toMs, target);
		}
		finally
		{
			closeAll(handles);
		}
	}

	/** The media length of {@code file}'s video track in milliseconds. */
	public static long durationMs(Path file) throws IOException
	{
		List<AutoCloseable> handles = new ArrayList<>();
		try
		{
			return durationMs(videoTrack(open(file, handles)));
		}
		finally
		{
			closeAll(handles);
		}
	}

	/**
	 * {@code MovieCreator.build(String)} opens a {@code RandomAccessFile} it never closes; the
	 * handles are opened here and closed by the caller once the clip is written.
	 */
	private static Movie open(Path file, List<AutoCloseable> handles) throws IOException
	{
		IsoBoxMapping.ensure();
		FileInputStream boxes = new FileInputStream(file.toFile());
		handles.add(boxes);
		RandomAccessFile samples = new RandomAccessFile(file.toFile(), "r");
		handles.add(samples);
		return MovieCreator.build(boxes.getChannel(), new FileRandomAccessSourceImpl(samples),
				file.getFileName().toString());
	}

	private static void closeAll(List<AutoCloseable> handles) throws IOException
	{
		IOException failure = null;
		for (AutoCloseable handle : handles)
		{
			try
			{
				handle.close();
			}
			catch (Exception e)
			{
				if (failure == null)
					failure = new IOException("closing " + handle, e);
				else
					failure.addSuppressed(e);
			}
		}
		if (failure != null)
			throw failure;
	}

	private static Result write(Movie movie, long fromMs, long toMs, Path target) throws IOException
	{
		Track video = videoTrack(movie);
		long[] videoStarts = sampleStartsMs(video);
		int requestedFrom = firstSampleAtOrAfter(videoStarts, Math.max(0, fromMs));
		int toSample = firstSampleAtOrAfter(videoStarts, toMs);
		if (requestedFrom >= videoStarts.length || toSample <= requestedFrom)
			throw new IOException("empty clip: " + fromMs + "-" + toMs + "ms selects no video sample");
		int fromSample = alignToSync(video, requestedFrom);
		long clipFromMs = videoStarts[fromSample];
		long clipToMs = toSample < videoStarts.length ? videoStarts[toSample] : durationMs(video);

		Movie clipped = new Movie();
		for (Track track : movie.getTracks())
		{
			if (track == video)
			{
				clipped.addTrack(new ClippedTrack(track, fromSample, toSample));
				continue;
			}
			// Timecode and other data tracks carry nothing a clip needs and would keep the
			// source's length.
			if (!"soun".equals(track.getHandler()))
				continue;
			long[] starts = sampleStartsMs(track);
			int from = firstSampleAtOrAfter(starts, clipFromMs);
			int to = firstSampleAtOrAfter(starts, clipToMs);
			if (to > from)
				clipped.addTrack(new ClippedTrack(track, from, to));
		}

		if (target.getParent() != null)
			Files.createDirectories(target.getParent());
		Container container = new DefaultMp4Builder().build(clipped);
		try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING))
		{
			container.writeContainer(out);
		}
		return new Result(target, clipFromMs, clipToMs, toSample - fromSample);
	}

	private static Track videoTrack(Movie movie) throws IOException
	{
		for (Track track : movie.getTracks())
		{
			if ("vide".equals(track.getHandler()))
				return track;
		}
		throw new IOException("no video track");
	}

	private static long durationMs(Track track)
	{
		return track.getDuration() * 1000 / track.getTrackMetaData().getTimescale();
	}

	/** Decode start time of every sample in milliseconds, in sample order. */
	private static long[] sampleStartsMs(Track track)
	{
		long timescale = track.getTrackMetaData().getTimescale();
		long[] durations = track.getSampleDurations();
		long[] starts = new long[durations.length];
		long elapsed = 0;
		for (int i = 0; i < durations.length; i++)
		{
			starts[i] = elapsed * 1000 / timescale;
			elapsed += durations[i];
		}
		return starts;
	}

	/** Index of the first sample starting at or after {@code ms}; the sample count when none does. */
	private static int firstSampleAtOrAfter(long[] starts, long ms)
	{
		int index = Arrays.binarySearch(starts, ms);
		if (index >= 0)
		{
			while (index > 0 && starts[index - 1] == ms)
				index--;
			return index;
		}
		return -index - 1;
	}

	/** The sync sample at or before {@code sample}; sync sample numbers are 1-based in the table. */
	private static int alignToSync(Track track, int sample)
	{
		long[] syncSamples = track.getSyncSamples();
		if (syncSamples == null || syncSamples.length == 0)
			return sample;
		int aligned = 0;
		for (long sync : syncSamples)
		{
			int index = (int)sync - 1;
			if (index > sample)
				break;
			aligned = index;
		}
		return aligned;
	}
}
