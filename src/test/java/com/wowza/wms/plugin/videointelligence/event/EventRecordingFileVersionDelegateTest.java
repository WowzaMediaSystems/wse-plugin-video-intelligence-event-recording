package com.wowza.wms.plugin.videointelligence.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wowza.wms.livestreamrecord.manager.IStreamRecorder;

/**
 * The date-time format of a live recording's name: the listener's own, else the recorder's, else
 * the Engine's default.
 */
public class EventRecordingFileVersionDelegateTest
{
	private static final long STARTED_AT_MS = 1_777_636_800_123L;
	private static final String DIR = File.separator + "content";

	/** A recorder that answers what the delegate asks of it: where it records and under what name. */
	private static IStreamRecorder recorder()
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "getBaseFilePath":
					return DIR + File.separator + "myStream.mp4";
				case "getStreamName":
					return "myStream";
				case "getSegmentNumber":
					return 0;
				case "isVersionFile":
					return false;
				default:
					throw new UnsupportedOperationException(method.getName());
			}
		};
		return (IStreamRecorder)Proxy.newProxyInstance(IStreamRecorder.class.getClassLoader(),
				new Class<?>[] {IStreamRecorder.class}, handler);
	}

	private static String startedAt(String pattern)
	{
		return DateTimeFormatter.ofPattern(pattern)
				.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(STARTED_AT_MS), ZoneId.systemDefault()));
	}

	private static EventRecordingFileVersionDelegate delegate(DateTimeFormatter listenerFormat)
	{
		EventRecordingFileVersionDelegate delegate = new EventRecordingFileVersionDelegate(Set.of(7), Set.of("person"),
				STARTED_AT_MS, listenerFormat);
		delegate.setFileTemplate("${SourceStreamName}_${RecordingStartTime}_${ClassNames}_${TrackingIds}");
		return delegate;
	}

	private static String named(String startTime)
	{
		return DIR + File.separator + "myStream_" + startTime + "_person_7.mp4";
	}

	@Test
	public void withoutAFormatTheNameCarriesTheEnginesDefaultOne()
	{
		assertEquals(named(startedAt("yyyy-MM-dd-HH.mm.ss.SSS-z")), delegate(null).getFilename(recorder()));
	}

	@Test
	public void theRecordersFormatIsUsedOnceItIsSet()
	{
		EventRecordingFileVersionDelegate delegate = delegate(null);
		delegate.setDateTimeFormat("yyyyMMdd_HHmmss");

		assertEquals(named(startedAt("yyyyMMdd_HHmmss")), delegate.getFilename(recorder()));
	}

	@Test
	public void theListenersFormatWinsOverTheRecorders()
	{
		EventRecordingFileVersionDelegate delegate = delegate(DateTimeFormatter.ofPattern("yyyy"));
		delegate.setDateTimeFormat("yyyyMMdd_HHmmss");

		assertEquals(named(startedAt("yyyy")), delegate.getFilename(recorder()));
	}

	@Test
	public void aRecorderFormatThatIsEmptyOrInvalidLeavesTheDefault()
	{
		EventRecordingFileVersionDelegate delegate = delegate(null);
		String byDefault = named(startedAt("yyyy-MM-dd-HH.mm.ss.SSS-z"));

		delegate.setDateTimeFormat(null);
		assertEquals(byDefault, delegate.getFilename(recorder()));
		delegate.setDateTimeFormat("  ");
		assertEquals(byDefault, delegate.getFilename(recorder()));
		delegate.setDateTimeFormat("not a {pattern");
		assertEquals(byDefault, delegate.getFilename(recorder()));
	}
}
