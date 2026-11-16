/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class VicWebhookChannelTest
{
	private static final class RecordingQueue implements VicWebhookChannel.Queue
	{
		final List<String> calls = new ArrayList<>();
		String body;

		@Override
		public void enqueue(String url, String secretName, String headerName, String body)
		{
			calls.add("enqueue " + url + " " + secretName + " " + headerName);
			this.body = body;
		}

		@Override
		public void release()
		{
			calls.add("release");
		}
	}

	private static VicWebhookChannel channel(RecordingQueue queue, String source)
	{
		return new VicWebhookChannel(null, "test:", queue, "https://hooks.example.com/vi", "hook-key", null, source,
				new WebhookContext("_defaultVHost_", "live", "_definst_", "cam1"), () -> 1700000000000L);
	}

	@Test
	public void anEventTravelsInTheControllersDetectionEnvelope() throws Exception
	{
		RecordingQueue queue = new RecordingQueue();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("action", "detection.start");
		payload.put("stream_name", "cam1");

		channel(queue, "engine-guid").send(payload);

		assertEquals(List.of("enqueue https://hooks.example.com/vi hook-key null"), queue.calls);
		JsonNode sent = new ObjectMapper().readTree(queue.body);
		String id = sent.path("id").asText();
		assertEquals(id, UUID.fromString(id).toString());
		((com.fasterxml.jackson.databind.node.ObjectNode)sent).remove("id");
		assertEquals(new ObjectMapper().readTree("{\"name\":\"video.intelligence.detection\","
				+ "\"source\":\"engine-guid\",\"version\":\"1.0\",\"timestamp\":1700000000000,"
				+ "\"context\":{\"vhost\":\"_defaultVHost_\",\"app\":\"live\",\"appInstance\":\"_definst_\","
				+ "\"stream\":\"cam1\",\"entity\":\"objects\",\"options\":\"viDetection\"},"
				+ "\"data\":{\"event_recording_data\":{\"action\":\"detection.start\",\"stream_name\":\"cam1\"}}}"),
				sent);
	}

	@Test
	public void anEngineWithNoIdentitySendsNoSource() throws Exception
	{
		RecordingQueue queue = new RecordingQueue();

		channel(queue, null).send(new LinkedHashMap<>());

		assertFalse(new ObjectMapper().readTree(queue.body).has("source"));
	}

	@Test
	public void closingHandsTheQueueBack()
	{
		RecordingQueue queue = new RecordingQueue();

		channel(queue, "engine-guid").close();

		assertEquals(List.of("release"), queue.calls);
	}

	@Test
	public void onlyAnAbsoluteHttpUrlWithAHostIsDeliverable()
	{
		assertTrue(VicWebhookChannel.isDeliverable("https://hooks.example.com/vi"));
		assertTrue(VicWebhookChannel.isDeliverable("http://10.0.0.5:9000"));
		assertFalse(VicWebhookChannel.isDeliverable("hooks.example.com/vi"));
		assertFalse(VicWebhookChannel.isDeliverable("ftp://hooks.example.com/vi"));
		assertFalse(VicWebhookChannel.isDeliverable("https:///vi"));
		assertFalse(VicWebhookChannel.isDeliverable("not a url"));
	}
}
