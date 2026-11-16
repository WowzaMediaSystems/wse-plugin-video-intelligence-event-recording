/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.webhook;

/** Where the events happened: what a consumer routes on. */
public final class WebhookContext
{
	/** The member of an event's data that carries this listener's payload. */
	public static final String DATA_MEMBER = "event_recording_data";

	private final String vHost;
	private final String app;
	private final String appInstance;
	private final String stream;

	public WebhookContext(String vHost, String app, String appInstance, String stream)
	{
		this.vHost = vHost;
		this.app = app;
		this.appInstance = appInstance;
		this.stream = stream;
	}

	public String vHost()
	{
		return vHost;
	}

	public String app()
	{
		return app;
	}

	public String appInstance()
	{
		return appInstance;
	}

	public String stream()
	{
		return stream;
	}
}
