package com.wowza.wms.plugin.videointelligence.eventrecording.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;

/** The client against a local server that asks for Basic credentials, as the Engine may. */
public class VifApiTest
{
	private HttpServer server;
	private String url;
	private final List<String> requests = new ArrayList<>();

	@BeforeEach
	public void startServer() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		HttpContext context = server.createContext("/v2/vif", exchange ->
		{
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
					+ exchange.getRequestHeaders().getFirst("Content-Type") + " " + body);
			boolean refused = exchange.getRequestURI().getPath().endsWith("/refused");
			byte[] answer = (refused ? "{\"detail\":\"no such config\"}" : "{\"ok\":true}")
					.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(refused ? 400 : 200, answer.length);
			exchange.getResponseBody().write(answer);
			exchange.close();
		});
		context.setAuthenticator(new BasicAuthenticator("engine")
		{
			@Override
			public boolean checkCredentials(String user, String password)
			{
				return "admin".equals(user) && "s3cret".equals(password);
			}
		});
		server.start();
		url = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	public void stopServer()
	{
		server.stop(0);
	}

	@Test
	public void aReadIsAuthenticatedAndAnswersTheBody() throws IOException
	{
		assertEquals("{\"ok\":true}", new VifApi(url + "/", "admin", "s3cret").get("/persist/vod-settings"));
		assertEquals(List.of("GET /v2/vif/persist/vod-settings null "), requests);
	}

	@Test
	public void aPostCarriesItsJsonBody() throws IOException
	{
		assertEquals("{\"ok\":true}", new VifApi(url, "admin", "s3cret").post("/vod/jobs", "{\"file\":\"a.mp4\"}"));
		assertEquals(List.of("POST /v2/vif/vod/jobs application/json {\"file\":\"a.mp4\"}"), requests);
	}

	@Test
	public void aRefusalIsAnErrorCarryingTheStatusAndTheBody()
	{
		IOException refused = assertThrows(IOException.class,
				() -> new VifApi(url, "admin", "s3cret").post("/refused", "{}"));
		assertEquals("POST " + url + "/v2/vif/refused answered 400: {\"detail\":\"no such config\"}",
				refused.getMessage());
	}

	@Test
	public void wrongCredentialsAreAnError()
	{
		assertThrows(IOException.class, () -> new VifApi(url, "admin", "wrong").get("/persist/vod-settings"));
		assertEquals(List.of(), requests);
	}
}
