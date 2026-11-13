/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.clip;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.mp4parser.PropertyBoxParserImpl;

/**
 * Fills mp4parser's box-type mapping from this jar's classloader before any file is parsed.
 *
 * <p>Inside the Engine mp4parser reads its mapping through the JVM's system classloader, which
 * cannot see the plugin's {@code lib/} jars: the lookup returns null and the parser dies in
 * {@code Properties.load} with "inStream parameter is null" — a failure that reads like a
 * corrupt MP4 but happens before the file is touched. The parser's own cache short-circuits
 * that lookup, so it is filled here. Only a successful load is cached: an empty mapping would
 * silently parse every box as unknown. Unit tests never need this; there mp4parser sits on the
 * system classpath.
 */
public final class IsoBoxMapping
{
	private static final String BOX_MAPPING_RESOURCE = "isoparser2-default.properties";

	private IsoBoxMapping()
	{
	}

	public static synchronized void ensure() throws IOException
	{
		if (PropertyBoxParserImpl.BOX_MAP_CACHE != null)
			return;
		Properties mapping = new Properties();
		try (InputStream in = IsoBoxMapping.class.getClassLoader().getResourceAsStream(BOX_MAPPING_RESOURCE))
		{
			if (in == null)
			{
				throw new IOException("could not load " + BOX_MAPPING_RESOURCE
						+ " from the plugin classloader; is isoparser deployed alongside the plugin jar?");
			}
			mapping.load(in);
		}
		PropertyBoxParserImpl.BOX_MAP_CACHE = mapping;
	}
}
