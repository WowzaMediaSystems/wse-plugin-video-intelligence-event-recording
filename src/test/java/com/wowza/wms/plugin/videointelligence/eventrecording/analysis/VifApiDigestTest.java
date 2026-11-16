package com.wowza.wms.plugin.videointelligence.eventrecording.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * The client against a local server that asks for Digest credentials the way the Engine does:
 * MD5 by default, SHA-256 under the {@code sha256} password encoder, {@code qop="auth"}.
 */
public class VifApiDigestTest
{
	private static final Pattern PARAMETER = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))");

	private HttpServer server;
	private String url;
	private String algorithm = "MD5";
	private String nonce = "nonce-1";
	private boolean qop = true;
	private final List<String> unanswered = new ArrayList<>();
	private final List<Map<String, String>> answered = new ArrayList<>();

	@BeforeEach
	public void startServer() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v2/vif", exchange ->
		{
			exchange.getRequestBody().readAllBytes();
			String uri = exchange.getRequestURI().toString();
			String authorization = exchange.getRequestHeaders().getFirst("Authorization");
			boolean valid = false;
			if (authorization == null)
			{
				unanswered.add(exchange.getRequestMethod() + " " + uri);
			}
			else
			{
				Map<String, String> sent = new HashMap<>();
				Matcher matcher = PARAMETER.matcher(authorization.substring("Digest ".length()));
				while (matcher.find())
					sent.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
				answered.add(sent);
				String expected = VifApi.digestResponse(algorithm, "admin", "Wowza", "s3cret",
						exchange.getRequestMethod(), uri, nonce, sent.get("nc"), sent.get("cnonce"));
				valid = authorization.startsWith("Digest ") && nonce.equals(sent.get("nonce"))
						&& uri.equals(sent.get("uri")) && expected.equals(sent.get("response"));
			}
			byte[] answer = (valid ? "{\"ok\":true}" : "").getBytes(StandardCharsets.UTF_8);
			if (!valid)
			{
				exchange.getResponseHeaders().add("WWW-Authenticate", "Digest realm=\"Wowza\", domain=\"/\", nonce=\""
						+ nonce + "\", algorithm=" + algorithm + (qop ? ", qop=\"auth\"" : ""));
			}
			exchange.sendResponseHeaders(valid ? 200 : 401, valid ? answer.length : -1);
			if (valid)
				exchange.getResponseBody().write(answer);
			exchange.close();
		});
		server.start();
		url = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	public void stopServer()
	{
		server.stop(0);
	}

	private VifApi api(String password)
	{
		return new VifApi(url, "admin", () -> password);
	}

	@Test
	public void anMd5ChallengeIsAnswered() throws IOException
	{
		assertEquals("{\"ok\":true}", api("s3cret").get("/persist/vod-settings"));

		assertEquals(List.of("GET /v2/vif/persist/vod-settings"), unanswered);
		assertEquals(1, answered.size());
		assertEquals("MD5", answered.get(0).get("algorithm"));
		assertEquals("auth", answered.get(0).get("qop"));
		assertEquals("00000001", answered.get(0).get("nc"));
		assertEquals("admin", answered.get(0).get("username"));
		assertEquals("Wowza", answered.get(0).get("realm"));
	}

	@Test
	public void aSha256ChallengeIsAnswered() throws IOException
	{
		algorithm = "SHA-256";

		assertEquals("{\"ok\":true}", api("s3cret").post("/vod/jobs", "{\"file\":\"a.mp4\"}"));

		assertEquals(List.of("POST /v2/vif/vod/jobs"), unanswered);
		assertEquals("SHA-256", answered.get(0).get("algorithm"));
	}

	@Test
	public void theQueryIsPartOfTheUriThatIsSigned() throws IOException
	{
		assertEquals("{\"ok\":true}", api("s3cret").get("/vod/jobs?tag=event-recording&limit=1000"));

		assertEquals("/v2/vif/vod/jobs?tag=event-recording&limit=1000", answered.get(0).get("uri"));
	}

	@Test
	public void aChallengeWithoutQopIsAnswered() throws IOException
	{
		qop = false;

		assertEquals("{\"ok\":true}", api("s3cret").get("/persist/vod-settings"));

		assertEquals(null, answered.get(0).get("nc"));
		assertEquals(null, answered.get(0).get("cnonce"));
	}

	@Test
	public void theNextRequestAnswersTheSameChallengeUpFront() throws IOException
	{
		VifApi api = api("s3cret");
		api.get("/persist/vod-settings");

		api.delete("/vod/jobs/j1");

		assertEquals(List.of("GET /v2/vif/persist/vod-settings"), unanswered);
		assertEquals(2, answered.size());
		assertEquals("00000002", answered.get(1).get("nc"));
	}

	@Test
	public void anExpiredNonceIsAnsweredAgainWithTheNewOne() throws IOException
	{
		VifApi api = api("s3cret");
		api.get("/persist/vod-settings");
		nonce = "nonce-2";

		assertEquals("{\"ok\":true}", api.get("/persist/vod-settings"));

		assertEquals(3, answered.size());
		assertEquals("nonce-1", answered.get(1).get("nonce"));
		assertEquals("nonce-2", answered.get(2).get("nonce"));
		assertEquals("00000001", answered.get(2).get("nc"));
	}

	@Test
	public void aWrongPasswordIsARefusalAfterOneAnswer()
	{
		ClipAnalysis.Refused refused = assertThrows(ClipAnalysis.Refused.class,
				() -> api("wrong").get("/persist/vod-settings"));

		assertEquals(401, refused.status());
		assertEquals(1, answered.size());
	}

	@Test
	public void theResponseIsTheOneTheRfcsWorkOut()
	{
		assertEquals("8ca523f5e9506fed4657c9700eebdbec", VifApi.digestResponse("MD5", "Mufasa",
				"http-auth@example.org", "Circle of Life", "GET", "/dir/index.html",
				"7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v", "00000001",
				"f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ"));
		assertEquals("753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1", VifApi.digestResponse(
				"SHA-256", "Mufasa", "http-auth@example.org", "Circle of Life", "GET", "/dir/index.html",
				"7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v", "00000001",
				"f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ"));
		assertEquals("6629fae49393a05397450978507c4ef1", VifApi.digestResponse("MD5", "Mufasa",
				"testrealm@host.com", "Circle Of Life", "GET", "/dir/index.html",
				"dcd98b7102dd2f0e8b11d0f600bfb0c093", "00000001", "0a4f113b"));
	}
}
