/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.webhook;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.logging.WMSLogger;

/**
 * The controller's detection webhooks: a destination and a credential set on the listener, in
 * the envelope the controller's own webhook listener delivers, so one consumer reads both.
 */
public final class VicWebhookChannel implements WebhookChannel
{
	/** The controller's delivery queue for one listener of one stream. */
	public interface Queue
	{
		/** Never throws and never blocks. A null secret sends unauthenticated; a null header means Authorization. */
		void enqueue(String url, String secretName, String headerName, String body);

		void release();
	}

	static final String EVENT_NAME = "video.intelligence.detection";
	static final String VERSION = "1.0";
	static final String ENTITY = "objects";
	static final String OPTIONS = "viDetection";

	private static final ObjectMapper JSON = new ObjectMapper();

	private final WMSLogger logger;
	private final String logPrefix;
	private final Queue queue;
	private final String url;
	private final String secretName;
	private final String headerName;
	private final String source;
	private final WebhookContext context;
	private final LongSupplier clock;

	public VicWebhookChannel(WMSLogger logger, String logPrefix, Queue queue, String url, String secretName,
			String headerName, String source, WebhookContext context)
	{
		this(logger, logPrefix, queue, url, secretName, headerName, source, context, System::currentTimeMillis);
	}

	VicWebhookChannel(WMSLogger logger, String logPrefix, Queue queue, String url, String secretName,
			String headerName, String source, WebhookContext context, LongSupplier clock)
	{
		this.logger = logger;
		this.logPrefix = logPrefix;
		this.queue = queue;
		this.url = url;
		this.secretName = secretName;
		this.headerName = headerName;
		this.source = source;
		this.context = context;
		this.clock = clock;
	}

	/** Whether {@code url} is an absolute http(s) URL with a host, which is what can be delivered to. */
	public static boolean isDeliverable(String url)
	{
		try
		{
			URI uri = URI.create(url);
			String scheme = uri.getScheme();
			return uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	@Override
	public void send(Map<String, Object> payload)
	{
		try
		{
			Map<String, Object> where = new LinkedHashMap<>();
			where.put("vhost", context.vHost());
			where.put("app", context.app());
			where.put("appInstance", context.appInstance());
			where.put("stream", context.stream());
			where.put("entity", ENTITY);
			where.put("options", OPTIONS);

			Map<String, Object> data = new LinkedHashMap<>();
			data.put(WebhookContext.DATA_MEMBER, payload);

			Map<String, Object> envelope = new LinkedHashMap<>();
			envelope.put("id", UUID.randomUUID().toString());
			envelope.put("name", EVENT_NAME);
			if (source != null)
				envelope.put("source", source);
			envelope.put("version", VERSION);
			envelope.put("timestamp", clock.getAsLong());
			envelope.put("context", where);
			envelope.put("data", data);
			queue.enqueue(url, secretName, headerName, JSON.writeValueAsString(envelope));
		}
		catch (Exception e)
		{
			logger.error(logPrefix + "Webhook Error: " + e.getMessage(), e);
		}
	}

	@Override
	public void close()
	{
		queue.release();
	}
}
