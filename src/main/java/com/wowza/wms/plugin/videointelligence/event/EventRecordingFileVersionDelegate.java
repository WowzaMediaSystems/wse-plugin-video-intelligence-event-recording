/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.event;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.wowza.wms.livestreamrecord.manager.IStreamRecorder;
import com.wowza.wms.livestreamrecord.manager.StreamRecorderFileVersionDelegate;
import com.wowza.wms.plugin.videointelligence.eventrecording.RecordingNames;

/**
 * Names a live recording from the template: the recorder's standard tokens plus the event's
 * classes and track ids, and {@code ${RecordingStartTime}} as the wall clock of the first
 * frame the back buffer reaches rather than the moment the recorder was asked to start.
 *
 * <p>With {@code SEGMENT_NONE} the base delegate returns the base file path untouched, so every
 * token is expanded here.
 */
public class EventRecordingFileVersionDelegate extends StreamRecorderFileVersionDelegate
{
	public static final String CLASS_NAMES_TAG = RecordingNames.CLASS_NAMES_TAG;
	public static final String TRACKING_IDS_TAG = RecordingNames.TRACKING_IDS_TAG;

	private static final String DATETIME_FORMAT = "yyyy-MM-dd-HH.mm.ss.SSS-z";

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
		File base = new File(recContext.getBaseFilePath());
		String dir = base.getParent();
		String baseName = base.getName();
		String ext = "";
		int extIndex = baseName.lastIndexOf('.');
		if (extIndex >= 0)
		{
			ext = baseName.substring(extIndex);
			baseName = baseName.substring(0, extIndex);
		}

		SimpleDateFormat dates = new SimpleDateFormat(DATETIME_FORMAT, Locale.ROOT);
		String name = RecordingNames.withoutExtension(getFileTemplate());
		name = name.replace(STREAM_NAME_TAG, recContext.getStreamName());
		name = name.replace(BASE_NAME_TAG, baseName);
		name = name.replace(START_TIME_TAG, dates.format(new Date(recordingStartMs)));
		name = name.replace(SEGMENT_TIME_TAG, dates.format(new Date()));
		name = name.replace(SEGMENT_NUMBER_TAG, Integer.toString(recContext.getSegmentNumber()));
		name = name.replace(CLASS_NAMES_TAG, RecordingNames.sanitize(String.join("_", classNames)));
		name = name.replace(TRACKING_IDS_TAG, RecordingNames.sanitize(
				trackingIds.stream().map(String::valueOf).collect(Collectors.joining("_"))));

		String path = dir + File.separator + name;
		if (!recContext.isVersionFile())
			return path + ext;
		String candidate = path + ext;
		for (int version = 1; new File(candidate).exists(); version++)
			candidate = path + "_" + version + ext;
		return candidate;
	}
}
