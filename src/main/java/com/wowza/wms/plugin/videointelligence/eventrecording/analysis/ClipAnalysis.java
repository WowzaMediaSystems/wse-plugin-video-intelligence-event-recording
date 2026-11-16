/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.analysis;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.plugin.videointelligence.eventrecording.RecordingNames;

/**
 * Submits each finished clip as a VOD job of its own, under a stream group config the operator
 * names. A job reads its source from the content directory, so a clip written anywhere else is
 * copied into {@link #COPIES_DIR} under it first; the copy is what the job is named by, the clip
 * stays where it was put.
 *
 * <p>Jobs are tagged {@link #JOB_TAG}, and a listener running inside a job that carries the tag
 * submits nothing: the config a clip is analysed with may hold this same listener, and its
 * clips of a clip would otherwise be analysed in turn, without end.
 *
 * <p>The controller keeps a fixed number of jobs and forgets the oldest finished one for every
 * job past it, whoever submitted it. So the tagged jobs are kept under a cap of their own: the
 * oldest finished one is removed to make room for a clip, with its copy, and while every one of
 * them is still queued or running the clip waits.
 */
public final class ClipAnalysis
{
	/** The calls this needs of the VIF REST API; paths are relative to the API root. */
	public interface Api
	{
		String get(String path) throws IOException;

		String post(String path, String json) throws IOException;

		void delete(String path) throws IOException;
	}

	/** The API answered, and the answer was no: nothing was done on the other side. */
	public static final class Refused extends IOException
	{
		private static final long serialVersionUID = 1L;

		private final int status;

		public Refused(int status, String message)
		{
			super(message);
			this.status = status;
		}

		public int status()
		{
			return status;
		}
	}

	/** One thread the submissions run on, in order, and run on again after a wait. */
	public interface Worker
	{
		void run(Runnable task);

		void later(Runnable task);
	}

	public static final String JOB_TAG = "event-recording";
	public static final String COPIES_DIR = "event-recording";
	public static final int DEFAULT_MAX_JOBS = 10;

	static final int MAX_WAITING = 200;

	private static final String DEFAULT_CONTENT_DIR = "content";
	private static final int CONTROLLER_DEFAULT_MAX_JOBS = 25;
	private static final int JOBS_PAGE = 1000;
	private static final long RETRY_SECONDS = 15;
	// A copy this young may be one whose job is not submitted yet.
	private static final Duration ORPHAN_AGE = Duration.ofMinutes(10);
	private static final Set<String> FINISHED = Set.of("completed", "failed", "cancelled");
	private static final ObjectMapper JSON = new ObjectMapper();
	// Outlives the listener: a clip is reported as the listener is being shut down.
	private static final Worker SHARED = newSharedWorker();

	private static final class Job
	{
		final String id;
		final String file;
		final String state;

		Job(JsonNode job)
		{
			this.id = job.path("job_id").asText();
			this.file = job.path("file").asText();
			this.state = job.path("state").asText();
		}
	}

	private final WMSLogger logger;
	private final String logPrefix;
	private final Api api;
	private final Worker worker;
	private final String config;
	private final int maxJobs;
	private final String hostJobId;
	private final Path configHome;

	// Touched on the worker's thread only.
	private final Deque<Path> waiting = new ArrayDeque<>();
	private boolean retryPending;
	private Boolean hostIsClipJob;

	/**
	 * @param config the stream group config the clips are analysed with, by name
	 * @param maxJobs how many tagged jobs are kept at most
	 * @param hostJobId the job this listener runs in, null on a live stream
	 * @param configHome the Engine's config home, which the default content directory hangs off
	 */
	public ClipAnalysis(WMSLogger logger, String logPrefix, Api api, Worker worker, String config, int maxJobs,
			String hostJobId, Path configHome)
	{
		this.logger = logger;
		this.logPrefix = logPrefix;
		this.api = api;
		this.worker = worker;
		this.config = config;
		this.maxJobs = Math.max(1, maxJobs);
		this.hostJobId = hostJobId;
		this.configHome = configHome;
	}

	public static Worker sharedWorker()
	{
		return SHARED;
	}

	private static Worker newSharedWorker()
	{
		ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(task ->
		{
			Thread created = new Thread(task, "EventRecordingPlugin-analysis");
			created.setDaemon(true);
			return created;
		});
		return new Worker()
		{
			@Override
			public void run(Runnable task)
			{
				thread.execute(task);
			}

			@Override
			public void later(Runnable task)
			{
				thread.schedule(task, RETRY_SECONDS, TimeUnit.SECONDS);
			}
		};
	}

	/** Queues the clip; nothing here fails the recording it came from. */
	public void submit(Path clip)
	{
		worker.run(() ->
		{
			if (waiting.size() >= MAX_WAITING)
			{
				logger.warn(logPrefix + "Not analysing " + clip + ": " + MAX_WAITING
						+ " clips are already waiting for an analysis job to finish");
				return;
			}
			waiting.add(clip);
			drain();
		});
	}

	private void drain()
	{
		while (!waiting.isEmpty())
		{
			Path clip = waiting.peek();
			try
			{
				if (!analyse(clip))
				{
					retryLater();
					return;
				}
			}
			catch (IOException | RuntimeException e)
			{
				logger.error(logPrefix + "Could not submit " + clip + " for analysis with config '" + config + "': " + e,
						e);
			}
			waiting.poll();
		}
	}

	private void retryLater()
	{
		if (retryPending)
			return;
		retryPending = true;
		logger.info(logPrefix + waiting.size() + " clip(s) waiting: all " + JOB_TAG
				+ " analysis jobs kept are still queued or running");
		worker.later(() ->
		{
			retryPending = false;
			drain();
		});
	}

	/** @return false when the clip has to wait for a job to finish */
	private boolean analyse(Path clip) throws IOException
	{
		if (hostIsClipJob())
		{
			logger.info(logPrefix + "Not analysing " + clip + ": job " + hostJobId
					+ " is itself the analysis of a clip");
			return true;
		}
		JsonNode settings = JSON.readTree(api.get("/persist/vod-settings"));
		JsonNode page = JSON.readTree(api.get("/vod/jobs?tag=" + JOB_TAG + "&limit=" + JOBS_PAGE));
		List<Job> jobs = new ArrayList<>();
		for (JsonNode job : page.path("jobs"))
			jobs.add(new Job(job));
		int total = Math.max(jobs.size(), page.path("total").asInt(0));
		boolean allListed = total == jobs.size();
		if (!makeRoom(jobs, total, cap(settings)))
			return false;

		Path contentDir = contentDir(settings);
		Path real = clip.toRealPath();
		boolean copied = !real.startsWith(contentDir);
		Path source = real;
		if (copied)
		{
			if (allListed)
				removeOrphanedCopies(jobs);
			source = copyInto(contentDir, real);
		}
		ObjectNode request = JSON.createObjectNode();
		request.put("file", relativeName(contentDir, source));
		request.put("stream_group_config", config);
		request.put("tag", JOB_TAG);
		JsonNode job;
		try
		{
			job = JSON.readTree(api.post("/vod/jobs", JSON.writeValueAsString(request)));
		}
		catch (Refused e)
		{
			// Any other failure may have queued the job all the same; that copy is left for
			// removeOrphanedCopies to judge.
			if (copied)
				Files.deleteIfExists(source);
			throw e;
		}
		logger.info(logPrefix + "Clip " + clip + " submitted for analysis with config '" + config + "' as job "
				+ job.path("job_id").asText() + " on " + request.get("file").asText());
		return true;
	}

	private boolean hostIsClipJob() throws IOException
	{
		if (hostJobId == null)
			return false;
		if (hostIsClipJob == null)
			hostIsClipJob = JOB_TAG.equals(JSON.readTree(api.get("/vod/jobs/" + hostJobId)).path("tag").asText(null));
		return hostIsClipJob;
	}

	/** One below the controller's own cap at most, so a clip's job never costs the last record of another. */
	private int cap(JsonNode settings)
	{
		int controllerCap = settings.path("max_jobs").asInt(CONTROLLER_DEFAULT_MAX_JOBS);
		return Math.max(1, Math.min(maxJobs, controllerCap - 1));
	}

	/** Removes the oldest finished jobs, and their copies, until one more fits under the cap. */
	private boolean makeRoom(List<Job> newestFirst, int total, int cap)
	{
		int kept = total;
		for (int i = newestFirst.size() - 1; i >= 0 && kept >= cap; i--)
		{
			Job job = newestFirst.get(i);
			if (!FINISHED.contains(job.state))
				continue;
			try
			{
				api.delete("/vod/jobs/" + job.id);
			}
			catch (IOException e)
			{
				logger.info(logPrefix + "Analysis job " + job.id + " could not be removed yet: " + e);
				continue;
			}
			newestFirst.remove(i);
			kept--;
			logger.info(logPrefix + "Removed analysis job " + job.id + " (" + job.state + ") on " + job.file
					+ ": at most " + cap + " " + JOB_TAG + " jobs are kept");
			if (isCopy(job.file) && newestFirst.stream().noneMatch(other -> other.file.equals(job.file)))
				removeFile(job.file);
		}
		return kept < cap;
	}

	/** The copies no tagged job names: their jobs were removed by the controller or by hand. */
	private void removeOrphanedCopies(List<Job> jobs)
	{
		try
		{
			Set<String> named = new HashSet<>();
			for (Job job : jobs)
				named.add(job.file);
			Instant youngest = Instant.now().minus(ORPHAN_AGE);
			for (JsonNode file : JSON.readTree(api.get("/vod/files")).path("files"))
			{
				String name = file.path("file").asText();
				if (isCopy(name) && !named.contains(name) && modifiedAt(file).isBefore(youngest))
					removeFile(name);
			}
		}
		catch (IOException | RuntimeException e)
		{
			logger.warn(logPrefix + "Could not look for clip copies left without a job: " + e);
		}
	}

	private static Instant modifiedAt(JsonNode file)
	{
		try
		{
			return OffsetDateTime.parse(file.path("modified_at").asText()).toInstant();
		}
		catch (DateTimeParseException e)
		{
			return Instant.MAX;
		}
	}

	private static boolean isCopy(String file)
	{
		return file.startsWith(COPIES_DIR + "/");
	}

	// Through the API rather than the file system: it refuses a file a queued or running job reads.
	private void removeFile(String name)
	{
		try
		{
			api.delete("/vod/files?file=" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
			logger.info(logPrefix + "Removed the clip copy " + name);
		}
		catch (IOException e)
		{
			logger.warn(logPrefix + "Could not remove the clip copy " + name + ": " + e);
		}
	}

	/**
	 * As the controller resolves it, in this same process: a configured directory as it is
	 * written, so a relative one against the Engine's working directory, and the default under
	 * the config home.
	 */
	private Path contentDir(JsonNode settings) throws IOException
	{
		String configured = settings.path("content_dir").asText(null);
		Path dir = configured == null || configured.isBlank() ? configHome.resolve(DEFAULT_CONTENT_DIR)
				: Paths.get(configured);
		return dir.toAbsolutePath().toRealPath();
	}

	private Path copyInto(Path contentDir, Path clip) throws IOException
	{
		Path copies = Files.createDirectories(contentDir.resolve(COPIES_DIR));
		String name = RecordingNames.withoutExtension(clip.getFileName().toString());
		Path part = RecordingNames.partFile(copies, name);
		try
		{
			Files.copy(clip, part);
			Path copy = RecordingNames.publish(part, copies, name);
			logger.info(logPrefix + "Copied " + clip + " to " + copy + " for analysis");
			return copy;
		}
		catch (IOException | RuntimeException e)
		{
			Files.deleteIfExists(part);
			throw e;
		}
	}

	/** The name a job knows a content file by: its path under the content directory, slash-separated. */
	private static String relativeName(Path contentDir, Path file)
	{
		List<String> segments = new ArrayList<>();
		for (Path segment : contentDir.relativize(file))
			segments.add(segment.toString());
		return String.join("/", segments);
	}
}
