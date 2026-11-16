/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The VIF REST API as the Engine serves it, reached over HTTP with the Engine's own REST
 * credentials. The JDK's connection answers a Basic or a Digest challenge, whichever the
 * Engine is configured to send.
 */
public final class VifApi implements ClipAnalysis.Api
{
	public static final String DEFAULT_URL = "http://localhost:8087";

	private static final String API_ROOT = "/v2/vif";
	private static final int CONNECT_TIMEOUT_MS = 10_000;
	private static final int READ_TIMEOUT_MS = 30_000;

	private final String root;
	private final Authenticator authenticator;

	/** @param user null for an Engine whose REST interface asks for no credentials */
	public VifApi(String url, String user, String password)
	{
		String base = url == null || url.isBlank() ? DEFAULT_URL : url.trim();
		while (base.endsWith("/"))
			base = base.substring(0, base.length() - 1);
		this.root = base + API_ROOT;
		this.authenticator = user == null ? null : new Authenticator()
		{
			@Override
			protected PasswordAuthentication getPasswordAuthentication()
			{
				if (getRequestorType() != RequestorType.SERVER)
					return null;
				return new PasswordAuthentication(user, (password == null ? "" : password).toCharArray());
			}
		};
	}

	@Override
	public String get(String path) throws IOException
	{
		return send("GET", path, null);
	}

	@Override
	public String post(String path, String json) throws IOException
	{
		return send("POST", path, json);
	}

	private String send(String method, String path, String json) throws IOException
	{
		HttpURLConnection connection = (HttpURLConnection)new URL(root + path).openConnection();
		try
		{
			connection.setRequestMethod(method);
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestProperty("Accept", "application/json");
			if (authenticator != null)
				connection.setAuthenticator(authenticator);
			if (json != null)
			{
				connection.setDoOutput(true);
				connection.setRequestProperty("Content-Type", "application/json");
				try (OutputStream out = connection.getOutputStream())
				{
					out.write(json.getBytes(StandardCharsets.UTF_8));
				}
			}
			int status = connection.getResponseCode();
			if (status >= 200 && status < 300)
				return read(connection.getInputStream());
			throw new IOException(method + " " + root + path + " answered " + status + ": "
					+ read(connection.getErrorStream()));
		}
		finally
		{
			connection.disconnect();
		}
	}

	private static String read(InputStream in) throws IOException
	{
		if (in == null)
			return "";
		try (InputStream body = in)
		{
			return new String(body.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
