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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The VIF REST API as the Engine serves it, reached over HTTP with the Engine's own REST
 * credentials. Answers a Basic or a Digest challenge (MD5 or SHA-256, {@code qop=auth}),
 * whichever the Engine is configured to send.
 *
 * <p>The challenge is answered here and not by the JDK's connection: depending on its version
 * that one refuses MD5 over plain HTTP or does not know SHA-256, which are the two the Engine
 * sends.
 */
public final class VifApi implements ClipAnalysis.Api
{
	public static final String DEFAULT_URL = "http://localhost:8087";

	private static final String API_ROOT = "/v2/vif";
	private static final int CONNECT_TIMEOUT_MS = 10_000;
	private static final int READ_TIMEOUT_MS = 30_000;
	private static final String BASIC = "basic";
	private static final String DIGEST = "digest";
	private static final Pattern PARAMETER = Pattern
			.compile("([A-Za-z0-9_-]+)\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\\s,]+))");
	private static final SecureRandom RANDOM = new SecureRandom();
	// Keeps a JVM-wide default authenticator from answering in this client's place.
	private static final Authenticator NONE = new Authenticator()
	{
	};

	/** What the server asked for, kept so the next request carries its answer up front. */
	private static final class Challenge
	{
		final String header;
		final String scheme;
		final Map<String, String> parameters;
		final String algorithm;
		final boolean qopAuth;
		final AtomicInteger nonceCount = new AtomicInteger();

		Challenge(String header, String scheme, Map<String, String> parameters, String algorithm, boolean qopAuth)
		{
			this.header = header;
			this.scheme = scheme;
			this.parameters = parameters;
			this.algorithm = algorithm;
			this.qopAuth = qopAuth;
		}

		/** @return null for a scheme, an algorithm or a qop this client does not answer */
		static Challenge of(String header)
		{
			String trimmed = header.trim();
			int space = trimmed.indexOf(' ');
			String scheme = (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT);
			if (scheme.equals(BASIC))
				return new Challenge(trimmed, BASIC, Map.of(), null, false);
			if (!scheme.equals(DIGEST))
				return null;
			Map<String, String> parameters = new HashMap<>();
			Matcher matcher = PARAMETER.matcher(trimmed.substring(space + 1));
			while (matcher.find())
			{
				String value = matcher.group(2) != null ? matcher.group(2).replaceAll("\\\\(.)", "$1")
						: matcher.group(3);
				parameters.put(matcher.group(1).toLowerCase(Locale.ROOT), value);
			}
			String algorithm = parameters.getOrDefault("algorithm", "MD5").toUpperCase(Locale.ROOT);
			if (!algorithm.equals("MD5") && !algorithm.equals("SHA-256"))
				return null;
			if (parameters.get("realm") == null || parameters.get("nonce") == null)
				return null;
			String qop = parameters.get("qop");
			boolean qopAuth = false;
			if (qop != null)
			{
				for (String offered : qop.split(","))
					qopAuth |= offered.trim().equalsIgnoreCase("auth");
				if (!qopAuth)
					return null;
			}
			return new Challenge(trimmed, DIGEST, parameters, algorithm, qopAuth);
		}

		int strength()
		{
			if (scheme.equals(BASIC))
				return 1;
			return algorithm.equals("SHA-256") ? 3 : 2;
		}

		static Challenge strongest(List<String> headers)
		{
			Challenge best = null;
			for (String header : headers)
			{
				Challenge offered = of(header);
				if (offered != null && (best == null || offered.strength() > best.strength()))
					best = offered;
			}
			return best;
		}

		String authorization(String user, String password, String method, String uri)
		{
			if (scheme.equals(BASIC))
			{
				return "Basic " + Base64.getEncoder()
						.encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
			}
			String realm = parameters.get("realm");
			String nonce = parameters.get("nonce");
			String count = qopAuth ? String.format("%08x", nonceCount.incrementAndGet()) : null;
			String clientNonce = qopAuth ? clientNonce() : null;
			StringBuilder header = new StringBuilder("Digest username=\"").append(quoted(user))
					.append("\", realm=\"").append(quoted(realm))
					.append("\", nonce=\"").append(quoted(nonce))
					.append("\", uri=\"").append(quoted(uri))
					.append("\", response=\"")
					.append(digestResponse(algorithm, user, realm, password, method, uri, nonce, count, clientNonce))
					.append("\", algorithm=").append(algorithm);
			if (parameters.get("opaque") != null)
				header.append(", opaque=\"").append(quoted(parameters.get("opaque"))).append('"');
			if (qopAuth)
				header.append(", qop=auth, nc=").append(count).append(", cnonce=\"").append(clientNonce).append('"');
			return header.toString();
		}
	}

	private static final class Answer
	{
		final int status;
		final String body;
		final List<String> challenges;

		Answer(int status, String body, List<String> challenges)
		{
			this.status = status;
			this.body = body;
			this.challenges = challenges;
		}
	}

	private final String root;
	private final String user;
	private final Supplier<String> password;

	private volatile Challenge challenge;

	/**
	 * @param user null for an Engine whose REST interface asks for no credentials
	 * @param password read at each request, so a changed one applies to the next
	 */
	public VifApi(String url, String user, Supplier<String> password)
	{
		String base = url == null || url.isBlank() ? DEFAULT_URL : url.trim();
		while (base.endsWith("/"))
			base = base.substring(0, base.length() - 1);
		this.root = base + API_ROOT;
		this.user = user;
		this.password = password;
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

	@Override
	public void delete(String path) throws IOException
	{
		send("DELETE", path, null);
	}

	/**
	 * The {@code response} of a Digest answer (RFC 7616; RFC 2069 when {@code nonceCount} is
	 * null, for a challenge that offers no qop), in lower-case hex.
	 */
	static String digestResponse(String algorithm, String user, String realm, String password, String method,
			String uri, String nonce, String nonceCount, String clientNonce)
	{
		String ha1 = hash(algorithm, user + ":" + realm + ":" + password);
		String ha2 = hash(algorithm, method + ":" + uri);
		if (nonceCount == null)
			return hash(algorithm, ha1 + ":" + nonce + ":" + ha2);
		return hash(algorithm, ha1 + ":" + nonce + ":" + nonceCount + ":" + clientNonce + ":auth:" + ha2);
	}

	private static String hash(String algorithm, String text)
	{
		try
		{
			return hex(MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static String hex(byte[] bytes)
	{
		StringBuilder hex = new StringBuilder(bytes.length * 2);
		for (byte b : bytes)
			hex.append(String.format("%02x", b));
		return hex.toString();
	}

	private static String clientNonce()
	{
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return hex(bytes);
	}

	private static String quoted(String value)
	{
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private String send(String method, String path, String json) throws IOException
	{
		URL url = new URL(root + path);
		Challenge known = challenge;
		Answer answer = exchange(method, url, json, known);
		if (answer.status == HttpURLConnection.HTTP_UNAUTHORIZED && user != null)
		{
			// A new challenge is answered once: the first of this client, or a nonce that expired.
			Challenge offered = Challenge.strongest(answer.challenges);
			if (offered != null && (known == null || !offered.header.equals(known.header)))
			{
				challenge = offered;
				answer = exchange(method, url, json, offered);
			}
		}
		if (answer.status >= 200 && answer.status < 300)
			return answer.body;
		throw new ClipAnalysis.Refused(answer.status,
				method + " " + root + path + " answered " + answer.status + ": " + answer.body);
	}

	private Answer exchange(String method, URL url, String json, Challenge answered) throws IOException
	{
		HttpURLConnection connection = (HttpURLConnection)url.openConnection();
		try
		{
			connection.setAuthenticator(NONE);
			connection.setRequestMethod(method);
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestProperty("Accept", "application/json");
			if (user != null && answered != null)
			{
				String secret = password.get();
				connection.setRequestProperty("Authorization",
						answered.authorization(user, secret == null ? "" : secret, method, url.getFile()));
			}
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
			List<String> challenges = new ArrayList<>();
			for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet())
			{
				if ("WWW-Authenticate".equalsIgnoreCase(header.getKey()))
					challenges.addAll(header.getValue());
			}
			boolean ok = status >= 200 && status < 300;
			return new Answer(status, read(ok ? connection.getInputStream() : connection.getErrorStream()),
					challenges);
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
