/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.webhook;

import java.util.HashMap;
import java.util.Map;

import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.server.Server;
import com.wowza.wms.webhooks.WebhookEvent;
import com.wowza.wms.webhooks.WebhookEventContext;
import com.wowza.wms.webhooks.WebhookEventEntity;
import com.wowza.wms.webhooks.WebhookEventName;
import com.wowza.wms.webhooks.WebhookListener;

/** The Engine's own webhooks: the destinations and filters are those of {@code Webhooks.json}. */
public final class EngineWebhookChannel implements WebhookChannel
{
	private final WMSLogger logger;
	private final String logPrefix;
	private final WebhookListener listener;
	private final WebhookContext context;

	private EngineWebhookChannel(WMSLogger logger, String logPrefix, WebhookListener listener, WebhookContext context)
	{
		this.logger = logger;
		this.logPrefix = logPrefix;
		this.listener = listener;
		this.context = context;
	}

	/** @return null when the Engine runs no webhook listener. */
	public static EngineWebhookChannel open(WMSLogger logger, String logPrefix, WebhookContext context)
	{
		try
		{
			WebhookListener listener =
					(WebhookListener)Server.getInstance().getProperties().getProperty("WebhookListener");
			return listener == null ? null : new EngineWebhookChannel(logger, logPrefix, listener, context);
		}
		catch (Exception e)
		{
			logger.warn(logPrefix + "Could not reach the Engine's webhook listener: " + e);
			return null;
		}
	}

	@Override
	public void send(Map<String, Object> payload)
	{
		try
		{
			Map<String, Object> data = new HashMap<>();
			data.put(WebhookContext.DATA_MEMBER, payload);
			WebhookEvent event = new WebhookEvent(WebhookEventName.VI_DETECTION);
			event.setContext(WebhookEventContext.forEntity(context.vHost(), context.app(), context.appInstance(),
					context.stream(), WebhookEventEntity.OBJECTS));
			event.setData(data);
			listener.addEvent(event);
		}
		catch (Exception e)
		{
			logger.error(logPrefix + "Webhook Error: " + e.getMessage(), e);
		}
	}

	@Override
	public void close()
	{
	}
}
