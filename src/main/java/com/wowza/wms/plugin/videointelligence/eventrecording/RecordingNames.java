/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
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
	private static final String DATETIME_FORMAT = "yyyy-MM-dd-HH.mm.ss.SSS-z";
	private static final String EXTENSION = ".mp4";

	private RecordingNames()
	{
	}

	/**
	 * The clip's file under {@code directory} for an event starting at media offset
	 * {@code fromMs} of {@code sourceName}: the template rendered, with a {@code _1}, {@code _2}
	 * suffix when the name is already taken, so a clip never overwrites another.
	 */
	public static Path clipFile(Path directory, String template, String sourceName, long fromMs,
			Set<String> classNames, Set<Integer> trackIds)
	{
		String name = withoutExtension(template == null || template.isBlank() ? DEFAULT_TEMPLATE : template);
		name = name.replace(STREAM_NAME_TAG, sourceName);
		name = name.replace(BASE_NAME_TAG, sourceName);
		name = name.replace(START_TIME_TAG, mediaOffset(fromMs));
		name = name.replace(SEGMENT_TIME_TAG,
				new SimpleDateFormat(DATETIME_FORMAT, Locale.ROOT).format(new Date()));
		name = name.replace(SEGMENT_NUMBER_TAG, "0");
		name = name.replace(CLASS_NAMES_TAG, sanitize(String.join("_", classNames)));
		name = name.replace(TRACKING_IDS_TAG,
				sanitize(trackIds.stream().map(String::valueOf).collect(Collectors.joining("_"))));
		name = sanitize(name);
		return unclaimed(directory, name);
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

	private static Path unclaimed(Path directory, String name)
	{
		Path candidate = directory.resolve(name + EXTENSION);
		for (int version = 1; Files.exists(candidate); version++)
			candidate = directory.resolve(name + "_" + version + EXTENSION);
		return candidate;
	}
}
