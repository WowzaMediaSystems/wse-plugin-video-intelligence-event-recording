/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.clip;

import java.nio.channels.SeekableByteChannel;

import org.mp4parser.ParsableBox;
import org.mp4parser.PropertyBoxParserImpl;

/**
 * mp4parser's default box mapping, except that {@code mdat} becomes a
 * {@link SkippedMediaDataBox}. Scoped to one parse, so nothing else in the JVM that uses
 * mp4parser loses its {@code mdat} payload.
 */
final class SkippingMediaDataBoxParser extends PropertyBoxParserImpl
{
	private final SeekableByteChannel source;

	/** @param source the channel the parse reads from */
	SkippingMediaDataBoxParser(SeekableByteChannel source)
	{
		this.source = source;
	}

	@Override
	public ParsableBox createBox(String type, byte[] userType, String parent)
	{
		if ("mdat".equals(type))
			return new SkippedMediaDataBox(source);
		return super.createBox(type, userType, parent);
	}
}
