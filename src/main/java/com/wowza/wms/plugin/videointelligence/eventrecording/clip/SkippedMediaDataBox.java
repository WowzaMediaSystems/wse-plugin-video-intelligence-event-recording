/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.clip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;

import org.mp4parser.BoxParser;
import org.mp4parser.ParsableBox;

/**
 * An {@code mdat} that is stepped over rather than read.
 *
 * <p>mp4parser's own {@code MediaDataBox} copies the payload to a temp file it only deletes when
 * the JVM exits — one copy of the source per cut — and on Linux that copy stops at 0x7ffff000
 * bytes without an error, leaving the parse mid-video for any larger {@code mdat}. Nothing here
 * needs the payload: samples are read through a separate handle at the offsets the sample
 * tables give.
 *
 * <p>The channel to skip on must be the one the parse reads from: {@link ParsableBox#parse}
 * only offers a {@link ReadableByteChannel}, which cannot be repositioned.
 */
final class SkippedMediaDataBox implements ParsableBox
{
	private final SeekableByteChannel source;
	private long size;

	SkippedMediaDataBox(SeekableByteChannel source)
	{
		this.source = source;
	}

	@Override
	public String getType()
	{
		return "mdat";
	}

	@Override
	public long getSize()
	{
		return size;
	}

	@Override
	public void getBox(WritableByteChannel writableByteChannel)
	{
		throw new UnsupportedOperationException("the mdat payload was skipped, not kept");
	}

	@Override
	public void parse(ReadableByteChannel dataSource, ByteBuffer header, long contentSize, BoxParser boxParser)
			throws IOException
	{
		source.position(source.position() + contentSize);
		size = header.limit() + contentSize;
	}
}
