package com.wowza.wms.plugin.videointelligence.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplatePluginTest {

    @Test
    void exposesPluginName() {
        assertEquals("wse-plugin-video-intelligence-<template>", new TemplatePlugin().getName());
    }
}
