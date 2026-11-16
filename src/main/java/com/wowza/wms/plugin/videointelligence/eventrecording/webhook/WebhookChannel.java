/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.webhook;

import java.util.Map;

/** Where the listener's events go. Sending never throws and never blocks on a consumer. */
public interface WebhookChannel
{
	/** @param payload the event, as the {@code event_recording_data} member carries it. */
	void send(Map<String, Object> payload);

	/** The listener is done with the channel; what it already sent is still delivered. */
	void close();
}
