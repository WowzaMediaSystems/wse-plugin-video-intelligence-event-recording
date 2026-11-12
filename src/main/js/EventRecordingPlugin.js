/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
window.VIF_LISTENER_PROPERTIES = window.VIF_LISTENER_PROPERTIES || {};
window.VIF_LISTENER_PROPERTIES['EventRecordingPlugin'] = [
    { object_methods: ['immediate'] },
    { type: 'separator', label: 'Trigger' },
    { key: 'class_names', label: 'Class Names', type: 'string_list', default: ['all'], tooltip: 'Object classes that trigger recording. Use "all" to match any class.' },
    { key: 'min_count', label: 'Minimum Count', type: 'number', default: 1, tooltip: 'How many detections of those classes one response needs before it counts as presence' },
    { key: 'require_track', label: 'Require Tracking ID', type: 'boolean', default: false, tooltip: 'Count only detections that carry a tracking ID (needs tracking on the detector). Off: any detection of the classes counts.' },
    { key: 'lost_object_duration_ms', label: 'Lost Object Duration (ms)', type: 'number', default: 3000, tooltip: 'How long the classes must be absent before the event ends. Measured on the media timeline: stream time live, file time on a job.' },
    { key: 'merge_gap_ms', label: 'Merge Gap (ms)', type: 'number', default: 0, tooltip: 'Two events closer than this become one recording' },
    { key: 'max_clip_ms', label: 'Maximum Clip (ms)', type: 'number', default: null, tooltip: 'Ends an event that never stops after this long and starts the next one. Empty: no limit.' },
    { type: 'separator', label: 'Recording Options' },
    { key: 'back_buffer_time', label: 'Back Buffer Time (ms)', type: 'number', default: 5000, tooltip: 'Milliseconds of video before the first detection. Live: from the stream buffer (~8 seconds available), starting on a keyframe. File job: cut from the file, any length.' },
    { key: 'post_detection_duration', label: 'Post Detection Duration (ms)', type: 'number', default: 5000, tooltip: 'Milliseconds to keep recording after the event ends' },
    { key: 'recording_name_template', label: 'Recording Name Template', type: 'text', default: '${SourceStreamName}_${RecordingStartTime}', tooltip: 'File naming template. Tokens: ${SourceStreamName}, ${BaseFileName}, ${RecordingStartTime}, ${SegmentNumber}, ${SegmentTime}, ${ClassNames}, ${TrackingIds}. On a file job ${SourceStreamName} is the source file name and ${RecordingStartTime} the clip\'s offset into it (HH.mm.ss.SSS).' },
    { key: 'date_time_format', label: 'Date-Time Format', type: 'text', default: '', tooltip: 'Java DateTimeFormatter pattern for ${RecordingStartTime} (live) and ${SegmentTime}, for example yyyyMMdd_HHmmss. Empty: the application\'s streamRecorderFileVersionDateTimeFormat live, else yyyy-MM-dd-HH.mm.ss.SSS-z.' },
    { key: 'record_stream_name', label: 'Record Stream Name (Regex)', type: 'text', default: '', tooltip: 'Live only. Regex matching the stream to record. Empty = source stream. Use ".*-vi$" to record the VIF transcoder output (with overlays). Requires use_transcoder=true.' },
    { key: 'output_path', label: 'Output Path', type: 'text', default: '', tooltip: 'Directory for recorded files. Empty: the WSE application default live, the source file\'s directory on a job.' },
    { type: 'separator', label: 'Notifications' },
    { key: 'webhooks', label: 'Webhooks', type: 'boolean', default: true, tooltip: 'Send webhook events: detection start/stop live, recording started/stopped per clip on a file job' }
];
