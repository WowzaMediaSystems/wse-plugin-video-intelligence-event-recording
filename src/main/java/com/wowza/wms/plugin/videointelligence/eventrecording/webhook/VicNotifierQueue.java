/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.videointelligence.eventrecording.webhook;

import com.wowza.wms.plugin.videointelligence.event.DetectionWebhookNotifier;

/**
 * Binds a queue to the controller's notifier. Kept apart from everything else so that a
 * controller without the notifier fails here alone, as a {@link LinkageError} the caller reads
 * as "not available".
 */
public final class VicNotifierQueue implements VicWebhookChannel.Queue
{
	private final DetectionWebhookNotifier notifier;
	private final DetectionWebhookNotifier.Key key;

	private VicNotifierQueue(DetectionWebhookNotifier notifier, DetectionWebhookNotifier.Key key)
	{
		this.notifier = notifier;
		this.key = key;
	}

	/** @return null when the controller has no notifier running. */
	public static VicNotifierQueue open(WebhookContext context, String listenerName)
	{
		DetectionWebhookNotifier notifier = DetectionWebhookNotifier.getInstance();
		if (notifier == null)
			return null;
		return new VicNotifierQueue(notifier,
				new DetectionWebhookNotifier.Key(context.app(), context.appInstance(), context.stream(), listenerName));
	}

	@Override
	public void enqueue(String url, String secretName, String headerName, String body)
	{
		notifier.enqueue(key, url, secretName, headerName, body);
	}

	@Override
	public void release()
	{
		notifier.release(key);
	}
}
