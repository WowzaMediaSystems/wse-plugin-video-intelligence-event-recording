package com.wowza.wms.plugin.videointelligence.eventrecording.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.logging.WMSLoggerFactory;

/**
 * A finished clip becomes a VOD job on a file under the content directory: the clip itself when
 * it is already there, a copy otherwise. The jobs are kept under a cap of their own.
 */
public class ClipAnalysisTest
{
	private static final byte[] CLIP_BYTES = "not really an mp4".getBytes(StandardCharsets.UTF_8);
	private static final String SETTINGS = "/persist/vod-settings";
	private static final String JOBS = "/vod/jobs?tag=event-recording&limit=1000";
	private static final String FILES = "/vod/files";
	private static final String NO_JOBS = "{\"offset\":0,\"limit\":1000,\"count\":0,\"total\":0,\"jobs\":[]}";

	@TempDir
	Path temp;

	/** Answers the reads from a table and keeps every call, in order. */
	private static final class FakeApi implements ClipAnalysis.Api
	{
		final Map<String, String> reads = new HashMap<>();
		final List<String> calls = new ArrayList<>();
		final Set<String> undeletable = new HashSet<>();
		IOException postFailure;

		FakeApi()
		{
			reads.put(JOBS, NO_JOBS);
			reads.put(FILES, "{\"files\":[],\"truncated\":false}");
		}

		@Override
		public String get(String path) throws IOException
		{
			calls.add("GET " + path);
			String body = reads.get(path);
			if (body == null)
				throw new ClipAnalysis.Refused(404, "GET " + path + " answered 404");
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

		@Override
		public void delete(String path) throws IOException
		{
			calls.add("DELETE " + path);
			if (undeletable.contains(path))
				throw new ClipAnalysis.Refused(409, "DELETE " + path + " answered 409");
		}

		List<String> posts()
		{
			return calls.stream().filter(call -> call.startsWith("POST ")).collect(Collectors.toList());
		}
	}

	/** Runs a task where it is handed over; keeps the ones put off until the test runs them. */
	private static final class InlineWorker implements ClipAnalysis.Worker
	{
		final List<Runnable> putOff = new ArrayList<>();

		@Override
		public void run(Runnable task)
		{
			task.run();
		}

		@Override
		public void later(Runnable task)
		{
			putOff.add(task);
		}
	}

	private final InlineWorker worker = new InlineWorker();

	private Path contentDir() throws IOException
	{
		return Files.createDirectories(temp.resolve("content")).toRealPath();
	}

	private static String settings(String contentDir, int maxJobs)
	{
		return "{\"content_dir\":\"" + contentDir.replace("\\", "\\\\") + "\",\"max_jobs\":" + maxJobs + "}";
	}

	private FakeApi apiWithContentDir(Path contentDir)
	{
		FakeApi api = new FakeApi();
		api.reads.put(SETTINGS, settings(contentDir.toString(), 25));
		return api;
	}

	private ClipAnalysis analysis(FakeApi api, String hostJobId)
	{
		return analysis(api, hostJobId, ClipAnalysis.DEFAULT_MAX_JOBS);
	}

	private ClipAnalysis analysis(FakeApi api, String hostJobId, int maxJobs)
	{
		return new ClipAnalysis(WMSLoggerFactory.getLogger(ClipAnalysisTest.class), "test:", api, worker,
				"clips_config", maxJobs, hostJobId, temp);
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

	private static String job(String id, String state, String file)
	{
		return "{\"job_id\":\"" + id + "\",\"tag\":\"event-recording\",\"state\":\"" + state + "\",\"file\":\"" + file
				+ "\"}";
	}

	private static String jobs(String... newestFirst)
	{
		return "{\"offset\":0,\"limit\":1000,\"count\":" + newestFirst.length + ",\"total\":" + newestFirst.length
				+ ",\"jobs\":[" + String.join(",", newestFirst) + "]}";
	}

	private static String file(String name, String modifiedAt)
	{
		return "{\"file\":\"" + name + "\",\"size_bytes\":17,\"modified_at\":\"" + modifiedAt + "\"}";
	}

	private static String post(String file)
	{
		return "POST /vod/jobs {\"file\":\"" + file + "\",\"stream_group_config\":\"clips_config\","
				+ "\"tag\":\"event-recording\"}";
	}

	@Test
	public void aClipUnderTheContentDirectoryIsSubmittedByItsRelativeName() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("races/day1/clip_00.00.01.000.mp4"));
		FakeApi api = apiWithContentDir(content);

		analysis(api, null).submit(clip);

		assertEquals(List.of("GET " + SETTINGS, "GET " + JOBS, post("races/day1/clip_00.00.01.000.mp4")), api.calls);
		assertEquals(List.of("clip_00.00.01.000.mp4"), namesIn(clip.getParent()));
		assertEquals(List.of("races"), namesIn(content));
	}

	@Test
	public void aClipWrittenElsewhereIsCopiedIntoTheCopiesFolderAndTheCopySubmitted() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(temp.resolve("recordings/clip.mp4"));
		FakeApi api = apiWithContentDir(content);

		analysis(api, null).submit(clip);

		assertEquals(List.of("GET " + SETTINGS, "GET " + JOBS, "GET " + FILES, post("event-recording/clip.mp4")),
				api.calls);
		assertEquals(List.of("clip.mp4"), namesIn(content.resolve("event-recording")));
		assertArrayEquals(CLIP_BYTES, Files.readAllBytes(content.resolve("event-recording/clip.mp4")));
		assertArrayEquals(CLIP_BYTES, Files.readAllBytes(clip));
	}

	@Test
	public void aCopyWhoseNameIsTakenGetsASuffix() throws IOException
	{
		Path content = contentDir();
		Path taken = Files.createDirectories(content.resolve("event-recording")).resolve("clip.mp4");
		Files.write(taken, new byte[] {1});
		Path clip = clipAt(temp.resolve("recordings/clip.mp4"));
		FakeApi api = apiWithContentDir(content);

		analysis(api, null).submit(clip);

		assertEquals(List.of(post("event-recording/clip_1.mp4")), api.posts());
		assertEquals(List.of("clip.mp4", "clip_1.mp4"), namesIn(content.resolve("event-recording")));
		assertArrayEquals(new byte[] {1}, Files.readAllBytes(taken));
	}

	@Test
	public void anUnsetContentDirectoryIsTheConfigHomesContentFolder() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("clip.mp4"));
		FakeApi api = new FakeApi();
		api.reads.put(SETTINGS, "{\"content_dir\":null,\"max_jobs\":25}");

		analysis(api, null).submit(clip);

		assertEquals(List.of(post("clip.mp4")), api.posts());
	}

	@Test
	public void aRelativeContentDirectoryIsRelativeToTheWorkingDirectoryAsTheControllerTakesIt() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("clip.mp4"));
		Path fromWorkingDir = Paths.get("").toRealPath().relativize(content);
		FakeApi api = new FakeApi();
		api.reads.put(SETTINGS, settings(fromWorkingDir.toString(), 25));

		analysis(api, null).submit(clip);

		assertFalse(fromWorkingDir.isAbsolute());
		assertEquals(List.of(post("clip.mp4")), api.posts());
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

		assertEquals(List.of("GET /vod/jobs/job-1", "GET " + SETTINGS, "GET " + JOBS, post("clip.mp4")), api.calls);
	}

	@Test
	public void aRefusedSubmissionRemovesTheCopyAndLeavesTheClip() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(temp.resolve("recordings/clip.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.postFailure = new ClipAnalysis.Refused(400, "POST /vod/jobs answered 400");

		analysis(api, null).submit(clip);

		assertEquals(List.of(post("event-recording/clip.mp4")), api.posts());
		assertArrayEquals(CLIP_BYTES, Files.readAllBytes(clip));
		assertEquals(List.of(), namesIn(content.resolve("event-recording")));
	}

	@Test
	public void aRefusedSubmissionOfAClipUnderTheContentDirectoryLeavesItThere() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("clip.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.postFailure = new ClipAnalysis.Refused(400, "POST /vod/jobs answered 400");

		analysis(api, null).submit(clip);

		assertEquals(List.of("clip.mp4"), namesIn(content));
	}

	@Test
	public void aSubmissionThatMayHaveBeenQueuedKeepsItsCopy() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(temp.resolve("recordings/clip.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.postFailure = new IOException("Read timed out");

		analysis(api, null).submit(clip);

		assertEquals(List.of("clip.mp4"), namesIn(content.resolve("event-recording")));
	}

	@Test
	public void theOldestFinishedJobsAreRemovedWithTheirCopiesToStayUnderTheCap() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("clip.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.reads.put(JOBS, jobs(job("j3", "running", "races/a.mp4"), job("j2", "failed", "event-recording/b.mp4"),
				job("j1", "completed", "races/c.mp4")));

		analysis(api, null, 2).submit(clip);

		assertEquals(List.of("GET " + SETTINGS, "GET " + JOBS, "DELETE /vod/jobs/j1", "DELETE /vod/jobs/j2",
				"DELETE /vod/files?file=event-recording%2Fb.mp4", post("clip.mp4")), api.calls);
	}

	@Test
	public void aJobUnderTheCapIsLeftAlone() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("clip.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.reads.put(JOBS, jobs(job("j2", "completed", "races/a.mp4"), job("j1", "completed", "races/c.mp4")));

		analysis(api, null, 3).submit(clip);

		assertEquals(List.of("GET " + SETTINGS, "GET " + JOBS, post("clip.mp4")), api.calls);
	}

	@Test
	public void aCopyAnotherKeptJobStillNamesStays() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("clip.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.reads.put(JOBS, jobs(job("j2", "running", "event-recording/b.mp4"),
				job("j1", "completed", "event-recording/b.mp4")));

		analysis(api, null, 2).submit(clip);

		assertEquals(List.of("GET " + SETTINGS, "GET " + JOBS, "DELETE /vod/jobs/j1", post("clip.mp4")), api.calls);
	}

	@Test
	public void theCapStaysOneBelowTheControllersOwn() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("clip.mp4"));
		FakeApi api = new FakeApi();
		api.reads.put(SETTINGS, settings(content.toString(), 3));
		api.reads.put(JOBS, jobs(job("j2", "completed", "races/a.mp4"), job("j1", "completed", "races/c.mp4")));

		analysis(api, null, 10).submit(clip);

		assertEquals(List.of("GET " + SETTINGS, "GET " + JOBS, "DELETE /vod/jobs/j1", post("clip.mp4")), api.calls);
	}

	@Test
	public void aJobThatCannotBeRemovedYetIsPassedOver() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(content.resolve("clip.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.reads.put(JOBS, jobs(job("j2", "completed", "races/a.mp4"), job("j1", "cancelled", "races/c.mp4")));
		api.undeletable.add("/vod/jobs/j1");

		analysis(api, null, 2).submit(clip);

		assertEquals(List.of("GET " + SETTINGS, "GET " + JOBS, "DELETE /vod/jobs/j1", "DELETE /vod/jobs/j2",
				post("clip.mp4")), api.calls);
	}

	@Test
	public void clipsWaitInOrderWhileEveryKeptJobIsStillInFlight() throws IOException
	{
		Path content = contentDir();
		Path first = clipAt(content.resolve("first.mp4"));
		Path second = clipAt(content.resolve("second.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.reads.put(JOBS, jobs(job("j1", "pending", "races/a.mp4")));
		ClipAnalysis analysis = analysis(api, null, 1);

		analysis.submit(first);
		analysis.submit(second);

		assertEquals(List.of("GET " + SETTINGS, "GET " + JOBS, "GET " + SETTINGS, "GET " + JOBS), api.calls);
		assertEquals(1, worker.putOff.size());

		api.calls.clear();
		api.reads.put(JOBS, jobs(job("j1", "completed", "races/a.mp4")));
		worker.putOff.get(0).run();

		assertEquals(List.of("GET " + SETTINGS, "GET " + JOBS, "DELETE /vod/jobs/j1", post("first.mp4"),
				"GET " + SETTINGS, "GET " + JOBS, "DELETE /vod/jobs/j1", post("second.mp4")), api.calls);
		assertEquals(1, worker.putOff.size());
	}

	@Test
	public void copiesNoJobNamesAreRemovedOnceTheyAreOld() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(temp.resolve("recordings/clip.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.reads.put(JOBS, jobs(job("j1", "completed", "event-recording/named.mp4")));
		api.reads.put(FILES, "{\"files\":[" + file("event-recording/fresh.mp4", Instant.now().toString()) + ","
				+ file("event-recording/named.mp4", "2020-01-01T00:00:00Z") + ","
				+ file("event-recording/orphan.mp4", "2020-01-01T00:00:00+00:00") + ","
				+ file("races/old.mp4", "2020-01-01T00:00:00Z") + "],\"truncated\":false}");

		analysis(api, null).submit(clip);

		assertEquals(List.of("GET " + SETTINGS, "GET " + JOBS, "GET " + FILES,
				"DELETE /vod/files?file=event-recording%2Forphan.mp4", post("event-recording/clip.mp4")), api.calls);
	}

	@Test
	public void copiesAreNotJudgedOnAJobListThatIsNotWhole() throws IOException
	{
		Path content = contentDir();
		Path clip = clipAt(temp.resolve("recordings/clip.mp4"));
		FakeApi api = apiWithContentDir(content);
		api.reads.put(JOBS, "{\"offset\":0,\"limit\":1000,\"count\":1,\"total\":2,\"jobs\":["
				+ job("j1", "running", "races/a.mp4") + "]}");

		analysis(api, null).submit(clip);

		assertEquals(List.of("GET " + SETTINGS, "GET " + JOBS, post("event-recording/clip.mp4")), api.calls);
	}
}
