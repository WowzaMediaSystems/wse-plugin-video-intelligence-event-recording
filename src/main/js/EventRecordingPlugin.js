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
    { type: 'separator', label: 'Clip Analysis' },
    { key: 'analyze_clips', label: 'Analyze Clips', type: 'boolean', default: false, tooltip: 'Submit every finished clip as a VOD analysis job of its own. A clip written outside the content directory is copied into its event-recording folder first.' },
    { key: 'analysis_config', label: 'Analysis Config', type: 'text', default: '', tooltip: 'The stream group config the clips are analysed with. Required when Analyze Clips is on.' },
    { key: 'api_url', label: 'API URL', type: 'text', default: '', tooltip: 'Base URL of the Engine REST interface the jobs are submitted to. Empty: http://localhost:8087.' },
    { key: 'api_user', label: 'API User', type: 'text', default: '', tooltip: 'Engine REST user. Empty when the REST interface asks for no credentials.' },
    { key: 'api_password_secret', label: 'API Password', type: 'secret', default: '', tooltip: 'The secret that holds the password of the Engine REST user, as named on the Secrets page.' },
    { key: 'max_analysis_jobs', label: 'Maximum Analysis Jobs', type: 'number', default: 10, tooltip: 'How many clip analysis jobs are kept, over every listener. The oldest finished one is removed, with its copy of the clip, to make room for a new clip; while all of them are still queued or running the clip waits. Kept below Maximum Jobs of the VOD settings.' },
    { type: 'separator', label: 'Notifications' },
    { key: 'webhooks', label: 'Webhooks', type: 'boolean', default: true, tooltip: 'Send webhook events: detection start/stop live, recording started/stopped per clip on a file job' }
];

// The listener editor has no field that lists the stream group configs, so Analysis Config is
// declared as text and shown as a dropdown here: the text box stays the stored value and is
// hidden behind a select filled from the page's API client. Without that client, or on a page
// that renders differently, the text box is simply left as it is.
(function () {
    var KEY = 'analysis_config';
    var client = window.VIF && window.VIF.core && typeof window.VIF.core.client === 'function'
        ? window.VIF.core.client : null;
    if (!client || typeof MutationObserver !== 'function') return;

    var names = [];
    var loading = null;
    var loadedAt = 0;

    function load() {
        if (!loading) {
            loading = Promise.resolve()
                .then(function () { return client().persist.streamGroupConfigs.list(); })
                .then(function (configs) {
                    names = (configs || []).map(function (config) { return config && config.name; })
                        .filter(Boolean).sort();
                })
                .catch(function () {})
                .then(function () {
                    loading = null;
                    loadedAt = Date.now();
                    document.querySelectorAll('select[data-evrec-picker]').forEach(fill);
                });
        }
        return loading;
    }

    function fill(picker) {
        var stored = picker._input.value;
        picker.innerHTML = '';
        option(picker, '', '(none)');
        names.forEach(function (name) { option(picker, name, name); });
        // A stored name always shows, or opening the listener would lose it.
        if (stored && names.indexOf(stored) === -1)
            option(picker, stored, loadedAt ? stored + ' (not found)' : stored);
        picker.value = stored;
    }

    function option(picker, value, text) {
        var opt = document.createElement('option');
        opt.value = value;
        opt.textContent = text;
        picker.appendChild(opt);
    }

    function enhance(input) {
        if (input.dataset.evrecEnhanced) return;
        input.dataset.evrecEnhanced = 'true';
        var picker = document.createElement('select');
        picker.dataset.evrecPicker = 'true';
        picker._input = input;
        picker.addEventListener('change', function () {
            input.value = picker.value;
            input.dispatchEvent(new Event('input', { bubbles: true }));
            input.dispatchEvent(new Event('change', { bubbles: true }));
        });
        input.style.display = 'none';
        input.parentNode.insertBefore(picker, input);
        fill(picker);
        if (Date.now() - loadedAt > 5000) load();
    }

    function scan() {
        document.querySelectorAll('input[data-prop-key="' + KEY + '"]').forEach(enhance);
    }

    new MutationObserver(scan).observe(document.body, { childList: true, subtree: true });
    load();
    scan();
})();
