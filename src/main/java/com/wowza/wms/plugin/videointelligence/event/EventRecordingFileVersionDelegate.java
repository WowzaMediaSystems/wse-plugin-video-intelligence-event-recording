/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.event;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.wowza.wms.livestreamrecord.manager.IStreamRecorder;
import com.wowza.wms.livestreamrecord.manager.StreamRecorderFileVersionDelegate;

public class EventRecordingFileVersionDelegate extends StreamRecorderFileVersionDelegate
{
	public static final String CLASS_NAMES_TAG = "${ClassNames}";
	public static final String TRACKING_IDS_TAG = "${TrackingIds}";

	private static final String DATETIME_FORMAT = "yyyy-MM-dd-HH.mm.ss.SSS-z";
	private final SimpleDateFormat dateFormatter = new SimpleDateFormat(DATETIME_FORMAT);

	private final Set<Integer> trackingIds;
	private final Set<String> classNames;
	private final long recordingStartMs;

	public EventRecordingFileVersionDelegate(Set<Integer> trackingIds, Set<String> classNames, long recordingStartMs)
	{
		this.trackingIds = new LinkedHashSet<>(trackingIds);
		this.classNames = new LinkedHashSet<>(classNames);
		this.recordingStartMs = recordingStartMs;
	}

	@Override
	public String getFilename(IStreamRecorder recContext)
	{
		String baseFilePath = recContext.getBaseFilePath();
		File file = new File(baseFilePath);
		String dir = file.getParent();
		String baseName = file.getName();

		// Strip extension
		String ext = "";
		int extIndex = baseName.lastIndexOf(".");
		if (extIndex >= 0)
		{
			ext = baseName.substring(extIndex);
			baseName = baseName.substring(0, extIndex);
		}

		// Start from template
		String name = getFileTemplate();

		// Strip any trailing .mp4 or .flv from template
		int tempExtIndex = name.lastIndexOf(".");
		if (tempExtIndex >= 0)
		{
			String tempExt = name.substring(tempExtIndex);
			if (tempExt.equalsIgnoreCase(".mp4") || tempExt.equalsIgnoreCase(".flv"))
			{
				name = name.substring(0, tempExtIndex);
			}
		}

		// Standard tokens
		name = name.replace(STREAM_NAME_TAG, recContext.getStreamName());
		name = name.replace(BASE_NAME_TAG, baseName);
		name = name.replace(START_TIME_TAG, dateFormatter.format(new Date(recordingStartMs)));
		name = name.replace(SEGMENT_TIME_TAG, dateFormatter.format(new Date(System.currentTimeMillis())));
		name = name.replace(SEGMENT_NUMBER_TAG, Integer.toString(recContext.getSegmentNumber()));

		// Custom tokens
		String classStr = String.join("_", classNames);
		String trackingStr = trackingIds.stream()
				.map(String::valueOf)
				.collect(Collectors.joining("_"));
		name = name.replace(CLASS_NAMES_TAG, sanitize(classStr));
		name = name.replace(TRACKING_IDS_TAG, sanitize(trackingStr));

		// Build full path with file versioning
		name = dir + File.separator + name;
		if (recContext.isVersionFile())
		{
			int version = 0;
			String candidate = name + ext;
			while (new File(candidate).exists())
			{
				candidate = name + "_" + version + ext;
				version++;
			}
			name = candidate;
		}
		else
		{
			name = name + ext;
		}

		return name;
	}

	private String sanitize(String input)
	{
		if (input == null) return "";
		return input.replaceAll("[^A-Za-z0-9._-]", "_");
	}
}
