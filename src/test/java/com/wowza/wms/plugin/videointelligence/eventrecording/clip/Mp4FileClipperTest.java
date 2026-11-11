package com.wowza.wms.plugin.videointelligence.eventrecording.clip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mp4parser.muxer.Movie;
import org.mp4parser.muxer.Track;
import org.mp4parser.muxer.container.mp4.MovieCreator;

/**
 * Cuts of the 4 s, 30 fps fixture, whose keyframes sit at every whole second.
 */
public class Mp4FileClipperTest
{
	private static final Path FIXTURE = Paths.get("src/test/resources/testsrc-320x240-30fps-4s.mp4");

	@TempDir
	Path temp;

	private static int videoSamplesOf(Path file) throws IOException
	{
		Movie movie = MovieCreator.build(file.toString());
		for (Track track : movie.getTracks())
		{
			if ("vide".equals(track.getHandler()))
				return track.getSamples().size();
		}
		throw new IOException("no video track in " + file);
	}

	@Test
	public void theFixtureIsFourSecondsLong() throws IOException
	{
		assertEquals(4000, Mp4FileClipper.durationMs(FIXTURE));
	}

	@Test
	public void aCutStartsOnTheKeyframeAtOrBeforeTheRequestedStart() throws IOException
	{
		Path clip = temp.resolve("clip.mp4");
		Mp4FileClipper.Result result = Mp4FileClipper.clip(FIXTURE, 1500, 3000, clip);

		assertEquals(1000, result.fromMs());
		assertEquals(3000, result.toMs());
		assertEquals(60, result.videoSamples());
		assertEquals(2000, Mp4FileClipper.durationMs(clip));
		assertEquals(60, videoSamplesOf(clip));
	}

	@Test
	public void aStartOnAKeyframeIsKeptAsIs() throws IOException
	{
		Mp4FileClipper.Result result = Mp4FileClipper.clip(FIXTURE, 2000, 3000, temp.resolve("exact.mp4"));
		assertEquals(2000, result.fromMs());
		assertEquals(3000, result.toMs());
		assertEquals(30, result.videoSamples());
	}

	@Test
	public void anEndPastTheFileIsTheFilesEnd() throws IOException
	{
		Mp4FileClipper.Result result = Mp4FileClipper.clip(FIXTURE, 3000, 10_000, temp.resolve("tail.mp4"));
		assertEquals(3000, result.fromMs());
		assertEquals(4000, result.toMs());
		assertEquals(30, result.videoSamples());
	}

	@Test
	public void aNegativeStartIsTheFilesStart() throws IOException
	{
		Mp4FileClipper.Result result = Mp4FileClipper.clip(FIXTURE, -2000, 1000, temp.resolve("head.mp4"));
		assertEquals(0, result.fromMs());
		assertEquals(1000, result.toMs());
		assertEquals(30, result.videoSamples());
	}

	@Test
	public void aStartPastTheFileSelectsNothingAndIsRefused()
	{
		IOException refusal = assertThrows(IOException.class,
				() -> Mp4FileClipper.clip(FIXTURE, 5000, 6000, temp.resolve("none.mp4")));
		assertTrue(refusal.getMessage().startsWith("empty clip: 5000-6000ms"), refusal.getMessage());
	}

	@Test
	public void theTargetDirectoryIsCreated() throws IOException
	{
		Path clip = temp.resolve("nested").resolve("dir").resolve("clip.mp4");
		Mp4FileClipper.clip(FIXTURE, 0, 1000, clip);
		assertTrue(Files.size(clip) > 0);
	}
}
