// The plugin class lives in com.wowza.wms.plugin.videointelligence.event so VIF
// short-names it in available_event_listeners (and the manager UI labels the
// dropdown entry "<Name> v<version>" instead of falling back to "?.?.?"). The
// build's `group` is a different per-plugin namespace so the generated
// ReleaseInfo class doesn't shadow VIF's own ReleaseInfo.
package com.wowza.wms.plugin.videointelligence.event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.plugin.videointelligence.message.DetectionResponse;
import com.wowza.wms.plugin.videointelligence.template.ReleaseInfo;
import com.wowza.wms.stream.IMediaStream;

/**
 * Skeleton VIF event listener.
 *
 * Rename this class for your plugin and fill in the listener methods.
 * The methods VIF invokes are driven by the listener properties exposed
 * in {@code examples/TemplatePlugin.js} ({@code object_methods} /
 * {@code scene_methods}); return {@code true} from a handler to signal the
 * detection was consumed.
 */
public class TemplatePlugin implements IVifEventListener {

    public static final String NAME = "wse-plugin-video-intelligence-<template>";

    public static final String MODULE_VERSION = ReleaseInfo.getVersion();

    private static final WMSLogger logger = WMSLoggerFactory.getLogger(TemplatePlugin.class);

    private String logPrefix = NAME + ":";

    public String getName() {
        return NAME;
    }

    // VIF calls this reflectively to log the listener version at startup.
    public static String getVersion() {
        return MODULE_VERSION;
    }

    @Override
    public void onInit(IApplicationInstance appInstance, IMediaStream stream,
            Set<String> methods, HashMap<String, Object> properties) {
        this.logPrefix = NAME + ":" + (stream != null ? stream.getName() : "?") + ":";
        logger.info(logPrefix + "onInit methods=" + methods + " properties=" + properties);
    }

    @Override
    public void onShutdown() {
        logger.info(logPrefix + "onShutdown");
    }

    @Override
    public boolean immediate(DetectionResponse response) {
        logger.info(logPrefix + "immediate detections=" + response.getDetections().size());
        return false;
    }

    @Override
    public boolean batch(ArrayList<DetectionResponse> responses) {
        logger.info(logPrefix + "batch responses=" + responses.size());
        return false;
    }

    @Override
    public boolean rollup(DetectionResponse response) {
        logger.info(logPrefix + "rollup detections=" + response.getDetections().size());
        return false;
    }
}
