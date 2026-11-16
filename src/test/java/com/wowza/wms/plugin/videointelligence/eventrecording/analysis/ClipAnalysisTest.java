package com.wowza.wms.plugin.videointelligence.eventrecording.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.logging.WMSLoggerFactory;

/**
 * A finished clip becomes a VOD job on a file under the content directory: the clip itself when
 * it is already there, a copy otherwise.
 */
public class ClipAnalysisTest
{
	private static final byte[] CLIP_BYTES = "not really an mp4".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path temp;

	/** Answers the reads from a table and keeps every call, in order. */
	private static final class FakeApi implements ClipAnalysis.Api
	{
		final Map<String, String> reads = new HashMap<>();
		final List<String> calls = new ArrayList<>();
		IOException postFailure;

		@Override
		public String get(String path) throws IOException
		{
			calls.add("GET " + path);
			String body = reads.get(path);
			if (body == null)
				throw new IOException("GET " + path + " answered 404");
			return body;
		}

		@Override
		public String post(String path, String json) throws IOException
		{
			calls.add("POST " + path + " " + json);
			if (postFailure != null)
				throw postFailure;
			return "{\"job_id\":\"job-2\"}";
		}
	}

	private Path contentDir() throws IOException
	{
		return Files.createDirectories(temp.resolve("content")).toRealPath();
	}

	private FakeApi apiWithContentDir(Path contentDir)
	{
		FakeApi api = new FakeApi();
		api.reads.put("/persist/vod-settings", "{\"content_dir\":\"" + contentDir.toString().replace("\\", "\\\\") + "\"}");
		return api;
	}

	private ClipAnalysis analysis(FakeApi api, String hostJobId)
	{
		return new ClipAnalysis(WMSLoggerFactory.getLogger(ClipAnalysisTest.class), "test:", api, Runnable::run,
				"clips_config", hostJobId, temp);
	}

	private static Path clipAt(Path file) throws IOException
	{
		Files.createDirectories(file.getParent());
		return Files.write(file, CLIP_BYTES);
	}

	private static List<String> namesIn(Path dir) throws IOException
	{
		try (Stream<Path> files = Files.list(dir))
		{
			return files.map(f -> f.getFileName().toString()).sorted().collect(Collectors.toList());
		}
	}

	@Test
	public void aClipUnderTheContentDirectoryIsSubmittedByItsRelativeName() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("races/day1/clip_00.00.01.000.mp4"));
		FakeApi api = apiWithContentDir(content);

		analysis(api, null).submit(clip);

		assertEquals(List.of("GET /persist/vod-settings",
				"POST /vod/jobs {\"file\":\"races/day1/clip_00.00.01.000.mp4\","
						+ "\"stream_group_config\":\"clips_config\",\"tag\":\"event-recording\"}"),
				api.calls);
		assertEquals(List.of("clip_00.00.01.000.mp4"), namesIn(clip.getParent()));
	}

	@Test
	public void aClipWrittenElsewhereIsCopiedIntoTheContentDirectoryAndTheCopySubmitted() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(temp.resolve("recordings/clip.mp4"));
		FakeApi api = apiWithContentDir(content);

		analysis(api, null).submit(clip);

		assertEquals("POST /vod/jobs {\"file\":\"clip.mp4\",\"stream_group_config\":\"clips_config\","
				+ "\"tag\":\"event-recording\"}", api.calls.get(1));
		assertEquals(List.of("clip.mp4"), namesIn(content));
		assertArrayEquals(CLIP_BYTES, Files.readAllBytes(content.resolve("clip.mp4")));
		assertArrayEquals(CLIP_BYTES, Files.readAllBytes(clip));
	}

	@Test
	public void aCopyWhoseNameIsTakenGetsASuffix() throws IOException
	{
		Path content = contentDir();
		Files.write(content.resolve("clip.mp4"), new byte[] {1});
		Path clip = clipAt(temp.resolve("recordings/clip.mp4"));
		FakeApi api = apiWithContentDir(content);

		analysis(api, null).submit(clip);

		assertEquals("POST /vod/jobs {\"file\":\"clip_1.mp4\",\"stream_group_config\":\"clips_config\","
				+ "\"tag\":\"event-recording\"}", api.calls.get(1));
		assertEquals(List.of("clip.mp4", "clip_1.mp4"), namesIn(content));
		assertArrayEquals(new byte[] {1}, Files.readAllBytes(content.resolve("clip.mp4")));
	}

	@Test
	public void anUnsetContentDirectoryIsTheEngineHomesContentFolder() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("clip.mp4"));
		FakeApi api = new FakeApi();
		api.reads.put("/persist/vod-settings", "{\"content_dir\":null,\"max_jobs\":25}");

		analysis(api, null).submit(clip);

		assertEquals("POST /vod/jobs {\"file\":\"clip.mp4\",\"stream_group_config\":\"clips_config\","
				+ "\"tag\":\"event-recording\"}", api.calls.get(1));
	}

	@Test
	public void aListenerInsideAClipsOwnAnalysisSubmitsNothing() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("clip.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.reads.put("/vod/jobs/job-1", "{\"job_id\":\"job-1\",\"tag\":\"event-recording\"}");
		ClipAnalysis analysis = analysis(api, "job-1");

		analysis.submit(clip);
		analysis.submit(clip);

		assertEquals(List.of("GET /vod/jobs/job-1"), api.calls);
	}

	@Test
	public void aListenerInsideAnyOtherJobSubmitsItsClips() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("clip.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.reads.put("/vod/jobs/job-1", "{\"job_id\":\"job-1\",\"tag\":null}");

		analysis(api, "job-1").submit(clip);

		assertEquals(List.of("GET /vod/jobs/job-1", "GET /persist/vod-settings",
				"POST /vod/jobs {\"file\":\"clip.mp4\",\"stream_group_config\":\"clips_config\","
						+ "\"tag\":\"event-recording\"}"),
				api.calls);
	}

	@Test
	public void aRefusedSubmissionLeavesTheClipAndItsCopyInPlace() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(temp.resolve("recordings/clip.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.postFailure = new IOException("POST /vod/jobs answered 400");

		analysis(api, null).submit(clip);

		assertEquals(2, api.calls.size());
		assertTrue(Files.exists(clip));
		assertEquals(List.of("clip.mp4"), namesIn(content));
	}
}
