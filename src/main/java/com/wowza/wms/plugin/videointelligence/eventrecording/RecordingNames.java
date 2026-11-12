/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * The tokens a recording's file name template understands, rendered for a file job. On a live
 * stream the Engine's own recorder expands the standard tokens through
 * {@link com.wowza.wms.plugin.videointelligence.event.EventRecordingFileVersionDelegate}; this is the same vocabulary for a clip cut from a file,
 * where there is no recorder to ask.
 */
public final class RecordingNames
{
	public static final String STREAM_NAME_TAG = "${SourceStreamName}";
	public static final String BASE_NAME_TAG = "${BaseFileName}";
	public static final String START_TIME_TAG = "${RecordingStartTime}";
	public static final String SEGMENT_TIME_TAG = "${SegmentTime}";
	public static final String SEGMENT_NUMBER_TAG = "${SegmentNumber}";
	public static final String CLASS_NAMES_TAG = "${ClassNames}";
	public static final String TRACKING_IDS_TAG = "${TrackingIds}";

	public static final String DEFAULT_TEMPLATE = STREAM_NAME_TAG + "_" + START_TIME_TAG;
	/** The Engine's own format for {@code ${RecordingStartTime}} and {@code ${SegmentTime}}. */
	public static final String DEFAULT_DATE_TIME_FORMAT = "yyyy-MM-dd-HH.mm.ss.SSS-z";
	public static final DateTimeFormatter DEFAULT_DATE_TIME_FORMATTER = DateTimeFormatter
			.ofPattern(DEFAULT_DATE_TIME_FORMAT);
	private static final String EXTENSION = ".mp4";
	private static final Object PUBLISH_LOCK = new Object();
	private static final AtomicLong PART_SEQUENCE = new AtomicLong();

	private RecordingNames()
	{
	}

	/**
	 * The clip's name, without extension, for an event starting at media offset {@code fromMs}
	 * of {@code sourceName}: the template rendered.
	 */
	public static String clipName(String template, String sourceName, long fromMs, Set<String> classNames,
			Set<Integer> trackIds, DateTimeFormatter dateTimeFormat)
	{
		String name = withoutExtension(template == null || template.isBlank() ? DEFAULT_TEMPLATE : template);
		name = name.replace(STREAM_NAME_TAG, sourceName);
		name = name.replace(BASE_NAME_TAG, sourceName);
		name = name.replace(START_TIME_TAG, mediaOffset(fromMs));
		name = name.replace(SEGMENT_TIME_TAG, formatTime(System.currentTimeMillis(), dateTimeFormat));
		name = name.replace(SEGMENT_NUMBER_TAG, "0");
		name = name.replace(CLASS_NAMES_TAG, sanitize(String.join("_", classNames)));
		name = name.replace(TRACKING_IDS_TAG,
				sanitize(trackIds.stream().map(String::valueOf).collect(Collectors.joining("_"))));
		return sanitize(name);
	}

	/**
	 * Where a clip is written before it is complete: a sibling no listing takes for a clip. Only
	 * named here — the writer creates it, so the clip keeps ordinary file permissions.
	 */
	public static Path partFile(Path directory, String name)
	{
		return directory.resolve("." + name + "-" + PART_SEQUENCE.incrementAndGet() + "-" + System.nanoTime()
				+ EXTENSION + ".part");
	}

	/**
	 * Gives the finished {@code part} its name under {@code directory}, with a {@code _1},
	 * {@code _2} suffix when the name is already taken. One clip at a time across the Engine, so
	 * two jobs cutting the same source cannot be handed the same name; the file appears under
	 * it whole.
	 */
	public static Path publish(Path part, Path directory, String name) throws IOException
	{
		synchronized (PUBLISH_LOCK)
		{
			Path candidate = directory.resolve(name + EXTENSION);
			for (int version = 1; Files.exists(candidate); version++)
				candidate = directory.resolve(name + "_" + version + EXTENSION);
			return Files.move(part, candidate, StandardCopyOption.ATOMIC_MOVE);
		}
	}

	/**
	 * The formatter for a {@code DateTimeFormatter} pattern, built as the Engine's recorder
	 * builds the one for {@code streamRecorderFileVersionDateTimeFormat}: surrounding whitespace
	 * ignored, the JVM's locale.
	 *
	 * @return null when the pattern is null, blank or not a valid pattern.
	 */
	public static DateTimeFormatter dateTimeFormatter(String pattern)
	{
		if (pattern == null || pattern.isBlank())
			return null;
		try
		{
			return DateTimeFormatter.ofPattern(pattern.trim());
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/** A wall-clock instant in the Engine's time zone; a null format is the default one. */
	public static String formatTime(long epochMs, DateTimeFormatter format)
	{
		DateTimeFormatter formatter = format != null ? format : DEFAULT_DATE_TIME_FORMATTER;
		return formatter.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()));
	}

	/** {@code HH.mm.ss.SSS} of a media offset — sortable, and legal in a file name. */
	public static String mediaOffset(long ms)
	{
		long clamped = Math.max(0, ms);
		return String.format(Locale.ROOT, "%02d.%02d.%02d.%03d", clamped / 3_600_000,
				(clamped / 60_000) % 60, (clamped / 1000) % 60, clamped % 1000);
	}

	public static String sanitize(String input)
	{
		if (input == null)
			return "";
		return input.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	/** The template's own extension, when it carries a recorder one, is not part of the name. */
	public static String withoutExtension(String template)
	{
		int dot = template.lastIndexOf('.');
		if (dot < 0)
			return template;
		String extension = template.substring(dot);
		if (extension.equalsIgnoreCase(".mp4") || extension.equalsIgnoreCase(".flv"))
			return template.substring(0, dot);
		return template;
	}
}
