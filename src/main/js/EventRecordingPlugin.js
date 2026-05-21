/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
window.VIF_LISTENER_PROPERTIES = window.VIF_LISTENER_PROPERTIES || {};
window.VIF_LISTENER_PROPERTIES['EventRecordingPlugin'] = [
    { object_methods: ['immediate'] },
    { key: 'class_names', label: 'Class Names', type: 'string_list', default: ['all'], tooltip: 'Object classes that trigger recording. Use "all" to match any class.' },
    { type: 'separator', label: 'Recording Options' },
    { key: 'back_buffer_time', label: 'Back Buffer Time (ms)', type: 'number', default: 5000, tooltip: 'Milliseconds of video to capture before the detection event. Stream buffer holds ~8 seconds.' },
    { key: 'post_detection_duration', label: 'Post Detection Duration (ms)', type: 'number', default: 5000, tooltip: 'Milliseconds to continue recording after the last qualifying detection' },
    { key: 'lost_object_duration_ms', label: 'Lost Object Duration (ms)', type: 'number', default: 3000, tooltip: 'Milliseconds since an object was last seen before it is considered lost. Wall-clock based, so independent of detection cadence.' },
    { key: 'recording_name_template', label: 'Recording Name Template', type: 'text', default: '${SourceStreamName}_${RecordingStartTime}', tooltip: 'File naming template. Tokens: ${SourceStreamName}, ${BaseFileName}, ${RecordingStartTime}, ${SegmentNumber}, ${SegmentTime}, ${ClassNames}, ${TrackingIds}' },
    { key: 'record_stream_name', label: 'Record Stream Name (Regex)', type: 'text', default: '', tooltip: 'Regex matching the stream to record. Empty = source stream. Use ".*-vi$" to record the VIF transcoder output (with overlays). Requires use_transcoder=true.' },
    { key: 'output_path', label: 'Output Path', type: 'text', default: '', tooltip: 'Directory for recorded files. Leave empty for WSE application default.' },
    { type: 'separator', label: 'Notifications' },
    { key: 'webhooks', label: 'Webhooks', type: 'boolean', default: true, tooltip: 'Send webhook events when detections start and stop' }
];
