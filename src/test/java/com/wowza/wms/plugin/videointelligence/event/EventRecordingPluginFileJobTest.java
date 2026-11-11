package com.wowza.wms.plugin.videointelligence.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.plugin.videointelligence.api.ShutdownEvent;
import com.wowza.wms.plugin.videointelligence.eventrecording.clip.Mp4FileClipper;
import com.wowza.wms.plugin.videointelligence.message.BoundingBoxData;
import com.wowza.wms.plugin.videointelligence.message.DetectionWindowData;
import com.wowza.wms.plugin.videointelligence.message.ObjectDetectionData;
import com.wowza.wms.plugin.videointelligence.message.ObjectDetectionResponse;
import com.wowza.wms.stream.IMediaStream;

/**
 * The listener on a file job: fed the responses of the 4 s fixture (keyframes every second) it
 * cuts the presence events out of the source, deferring each cut until the job's clock has
 * passed the post-detection window, and writes what it still owes when the job completes.
 */
public class EventRecordingPluginFileJobTest
{
	private static final Path FIXTURE = Paths.get("src/test/resources/testsrc-320x240-30fps-4s.mp4");

	@TempDir
	Path temp;

	/** The offline identity a VOD job hands its listeners: a stream that answers only its name. */
	private static IMediaStream namedStream(String name)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			if ("getName".equals(method.getName()))
				return name;
			throw new UnsupportedOperationException(method.getName());
		};
		return (IMediaStream)Proxy.newProxyInstance(IMediaStream.class.getClassLoader(),
				new Class<?>[] {IMediaStream.class}, handler);
	}

	private static HashMap<String, Object> fileJobProperties(Path source, long backBufferMs, long postDetectionMs,
			long lostMs)
	{
		HashMap<String, Object> properties = new HashMap<>();
		properties.put("job_id", "job-1");
		properties.put("source_file", source.toAbsolutePath().toString());
		properties.put("class_names", List.of("person"));
		properties.put("lost_object_duration_ms", lostMs);
		properties.put("back_buffer_time", backBufferMs);
		properties.put("post_detection_duration", postDetectionMs);
		properties.put("recording_name_template", "${SourceStreamName}_${RecordingStartTime}_${ClassNames}");
		properties.put("webhooks", false);
		return properties;
	}

	private static EventRecordingPlugin initialised(HashMap<String, Object> properties)
	{
		EventRecordingPlugin plugin = new EventRecordingPlugin();
		plugin.onInit(null, namedStream("vod-job-1"), Set.of("immediate"), properties);
		return plugin;
	}

	private static ObjectDetectionResponse response(long timeCode, String... classNames)
	{
		ObjectDetectionResponse resp = new ObjectDetectionResponse();
		DetectionWindowData window = new DetectionWindowData();
		window.fromFrameId = timeCode / 33;
		window.toFrameId = window.fromFrameId;
		window.frameCount = 1L;
		window.fromTimeCode = timeCode;
		window.toTimeCode = timeCode;
		resp.detectionWindow = window;
		resp.detections = new ArrayList<>();
		int track = 1;
		for (String className : classNames)
		{
			ObjectDetectionData d = new ObjectDetectionData();
			d.trackId = track++;
			d.className = className;
			d.confidence = 0.9;
			d.frameId = (int)(timeCode / 33);
			d.bbox = new BoundingBoxData();
			resp.detections.add(d);
		}
		return resp;
	}

	private static ShutdownEvent jobEnded(ShutdownEvent.Reason reason)
	{
		return ShutdownEvent.jobEnded(reason, "vod-job-1", "job-1", "testsrc.mp4", null, null, 0);
	}

	/** A copy of the fixture in its own directory, since clips land next to the source by default. */
	private Path sourceIn(String dir) throws IOException
	{
		Path folder = temp.resolve(dir);
		Files.createDirectories(folder);
		Path source = folder.resolve(FIXTURE.getFileName());
		Files.copy(FIXTURE, source);
		return source;
	}

	private static List<Path> clipsBeside(Path source) throws IOException
	{
		try (Stream<Path> files = Files.list(source.getParent()))
		{
			return files.filter(f -> !f.equals(source)).sorted().collect(Collectors.toList());
		}
	}

	@Test
	public void anEventIsCutWithItsBackBufferAndPostDetectionWindowOnceTheClockPassesIt() throws IOException
	{
		Path source = sourceIn("job");
		EventRecordingPlugin plugin = initialised(fileJobProperties(source, 500, 500, 300));

		assertTrue(plugin.immediate(response(1500, "person")));
		assertFalse(plugin.immediate(response(1600, "person")));
		assertTrue(plugin.immediate(response(2000, "car")), "lost after 300 ms of silence ends the event at 1900");
		// The cut is owed at 1900 + 500: not yet.
		assertEquals(List.of(), clipsBeside(source));
		plugin.immediate(response(2500, "car"));
		plugin.onShutdown(jobEnded(ShutdownEvent.Reason.JOB_COMPLETED));

		List<Path> clips = clipsBeside(source);
		assertEquals(1, clips.size());
		assertEquals("testsrc-320x240-30fps-4s_00.00.01.000_person.mp4", clips.get(0).getFileName().toString());
		// [1500 - 500, 1900 + 500] = [1000, 2400], and 1000 is a keyframe.
		assertEquals(1400, Mp4FileClipper.durationMs(clips.get(0)));
	}

	@Test
	public void aCompletedJobWritesTheEventStillOpenAndTheCutsStillOwed() throws IOException
	{
		Path source = sourceIn("job");
		EventRecordingPlugin plugin = initialised(fileJobProperties(source, 0, 0, 200));

		plugin.immediate(response(500, "person"));
		plugin.immediate(response(1000, "car"));
		plugin.immediate(response(3000, "person"));
		plugin.immediate(response(3200, "person"));
		plugin.onShutdown(jobEnded(ShutdownEvent.Reason.JOB_COMPLETED));

		List<Path> clips = clipsBeside(source);
		assertEquals(2, clips.size());
		// [500, 700] snaps back to the keyframe at 0.
		assertEquals("testsrc-320x240-30fps-4s_00.00.00.500_person.mp4", clips.get(0).getFileName().toString());
		assertEquals(700, Mp4FileClipper.durationMs(clips.get(0)));
		// Open at completion: ends lost_object_duration_ms after the last match, [3000, 3400].
		assertEquals("testsrc-320x240-30fps-4s_00.00.03.000_person.mp4", clips.get(1).getFileName().toString());
		assertEquals(400, Mp4FileClipper.durationMs(clips.get(1)));
	}

	@Test
	public void aCancelledJobWritesNothing() throws IOException
	{
		Path source = sourceIn("job");
		EventRecordingPlugin plugin = initialised(fileJobProperties(source, 0, 0, 200));

		plugin.immediate(response(500, "person"));
		plugin.immediate(response(1000, "car"));
		plugin.onShutdown(jobEnded(ShutdownEvent.Reason.JOB_CANCELLED));

		assertEquals(List.of(), clipsBeside(source));
	}

	@Test
	public void aTakenNameGetsASuffixRatherThanBeingOverwritten() throws IOException
	{
		Path source = sourceIn("job");
		Path taken = source.getParent().resolve("testsrc-320x240-30fps-4s_00.00.00.500_person.mp4");
		Files.writeString(taken, "taken");
		EventRecordingPlugin plugin = initialised(fileJobProperties(source, 0, 0, 200));

		plugin.immediate(response(500, "person"));
		plugin.onShutdown(jobEnded(ShutdownEvent.Reason.JOB_COMPLETED));

		List<Path> clips = clipsBeside(source);
		assertEquals(2, clips.size());
		assertEquals("taken", Files.readString(clips.get(0)));
		assertEquals("testsrc-320x240-30fps-4s_00.00.00.500_person_1.mp4", clips.get(1).getFileName().toString());
	}

	@Test
	public void anOutputPathOfItsOwnWinsOverTheSourceDirectory() throws IOException
	{
		Path source = sourceIn("job");
		Path out = temp.resolve("elsewhere");
		HashMap<String, Object> properties = fileJobProperties(source, 0, 0, 200);
		properties.put("output_path", out.toString());
		EventRecordingPlugin plugin = initialised(properties);

		plugin.immediate(response(500, "person"));
		plugin.onShutdown(jobEnded(ShutdownEvent.Reason.JOB_COMPLETED));

		assertEquals(List.of(), clipsBeside(source));
		try (Stream<Path> files = Files.list(out))
		{
			assertEquals(1, files.count());
		}
	}

	@Test
	public void anUntrackedDetectionCountsUnlessATrackIsRequired() throws IOException
	{
		Path source = sourceIn("job");
		HashMap<String, Object> properties = fileJobProperties(source, 0, 0, 200);
		properties.put("require_track", true);
		EventRecordingPlugin plugin = initialised(properties);

		ObjectDetectionResponse untracked = response(500, "person");
		untracked.detections.get(0).trackId = null;
		assertFalse(plugin.immediate(untracked));
		assertTrue(plugin.immediate(response(1000, "person")));
		plugin.onShutdown(jobEnded(ShutdownEvent.Reason.JOB_COMPLETED));

		List<Path> clips = clipsBeside(source);
		assertEquals(1, clips.size());
		assertEquals("testsrc-320x240-30fps-4s_00.00.01.000_person.mp4", clips.get(0).getFileName().toString());
	}
}
