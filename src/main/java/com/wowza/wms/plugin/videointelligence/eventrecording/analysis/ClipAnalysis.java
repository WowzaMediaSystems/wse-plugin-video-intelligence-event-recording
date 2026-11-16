/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.plugin.videointelligence.eventrecording.RecordingNames;

/**
 * Submits each finished clip as a VOD job of its own, under a stream group config the operator
 * names. A job reads its source from the content directory, so a clip written anywhere else is
 * copied there first; the copy is what the job is named by, the clip stays where it was put.
 *
 * <p>Jobs are tagged {@link #JOB_TAG}, and a listener running inside a job that carries the tag
 * submits nothing: the config a clip is analysed with may hold this same listener, and its
 * clips of a clip would otherwise be analysed in turn, without end.
 */
public final class ClipAnalysis
{
	/** The two calls this needs of the VIF REST API; paths are relative to the API root. */
	public interface Api
	{
		String get(String path) throws IOException;

		String post(String path, String json) throws IOException;
	}

	public static final String JOB_TAG = "event-recording";

	private static final String DEFAULT_CONTENT_DIR = "content";
	private static final ObjectMapper JSON = new ObjectMapper();
	// Outlives the listener: a clip is reported as the listener is being shut down.
	private static final Executor SHARED = Executors.newSingleThreadExecutor(task ->
	{
		Thread thread = new Thread(task, "EventRecordingPlugin-analysis");
		thread.setDaemon(true);
		return thread;
	});

	private final WMSLogger logger;
	private final String logPrefix;
	private final Api api;
	private final Executor executor;
	private final String config;
	private final String hostJobId;
	private final Path engineHome;

	private Boolean hostIsClipJob;

	/**
	 * @param config the stream group config the clips are analysed with, by name
	 * @param hostJobId the job this listener runs in, null on a live stream
	 * @param engineHome what a relative content directory is relative to
	 */
	public ClipAnalysis(WMSLogger logger, String logPrefix, Api api, Executor executor, String config,
			String hostJobId, Path engineHome)
	{
		this.logger = logger;
		this.logPrefix = logPrefix;
		this.api = api;
		this.executor = executor;
		this.config = config;
		this.hostJobId = hostJobId;
		this.engineHome = engineHome;
	}

	public static Executor sharedExecutor()
	{
		return SHARED;
	}

	/** Queues the clip; nothing here fails the recording it came from. */
	public void submit(Path clip)
	{
		executor.execute(() -> analyse(clip));
	}

	private void analyse(Path clip)
	{
		try
		{
			if (hostIsClipJob())
			{
				logger.info(logPrefix + "Not analysing " + clip + ": job " + hostJobId
						+ " is itself the analysis of a clip");
				return;
			}
			Path contentDir = contentDir();
			Path source = inContentDir(clip, contentDir);
			ObjectNode request = JSON.createObjectNode();
			request.put("file", relativeName(contentDir, source));
			request.put("stream_group_config", config);
			request.put("tag", JOB_TAG);
			JsonNode job = JSON.readTree(api.post("/vod/jobs", JSON.writeValueAsString(request)));
			logger.info(logPrefix + "Clip " + clip + " submitted for analysis with config '" + config + "' as job "
					+ job.path("job_id").asText() + " on " + request.get("file").asText());
		}
		catch (IOException | RuntimeException e)
		{
			logger.error(logPrefix + "Could not submit " + clip + " for analysis with config '" + config + "': " + e,
					e);
		}
	}

	private synchronized boolean hostIsClipJob() throws IOException
	{
		if (hostJobId == null)
			return false;
		if (hostIsClipJob == null)
			hostIsClipJob = JOB_TAG.equals(JSON.readTree(api.get("/vod/jobs/" + hostJobId)).path("tag").asText(null));
		return hostIsClipJob;
	}

	private Path contentDir() throws IOException
	{
		String configured = JSON.readTree(api.get("/persist/vod-settings")).path("content_dir").asText(null);
		Path dir = configured == null || configured.isBlank() ? engineHome.resolve(DEFAULT_CONTENT_DIR)
				: engineHome.resolve(Paths.get(configured));
		return dir.toRealPath();
	}

	private Path inContentDir(Path clip, Path contentDir) throws IOException
	{
		Path real = clip.toRealPath();
		if (real.startsWith(contentDir))
			return real;
		String name = RecordingNames.withoutExtension(real.getFileName().toString());
		Path part = RecordingNames.partFile(contentDir, name);
		try
		{
			Files.copy(real, part);
			Path copy = RecordingNames.publish(part, contentDir, name);
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
