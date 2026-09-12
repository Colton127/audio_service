package com.ryanheise.audioservice;

import android.os.Process;
import android.util.Log;

/**
 * Diagnostic-only lifecycle logging for the Android side of audio_service.
 *
 * <p>Every line is emitted on a single line with a consistent tag and
 * {@code key=value} fields so it is easy to grep in Logcat or to attach as a
 * Sentry breadcrumb. This class has no effect on lifecycle behaviour.
 */
final class AudioServiceLifecycleLog {
    static final String TAG = "AudioServiceLifecycle";

    private AudioServiceLifecycleLog() {
    }

    /** Logs {@code event=<event> pid=<pid>}. */
    static void log(String event) {
        log(event, null);
    }

    /** Logs {@code event=<event> pid=<pid> <fields>}. */
    static void log(String event, String fields) {
        final StringBuilder sb = new StringBuilder("event=").append(event)
                .append(" pid=").append(Process.myPid());
        if (fields != null && !fields.isEmpty()) {
            sb.append(' ').append(fields);
        }
        Log.i(TAG, sb.toString());
    }

    /** Identity hash of an object, or {@code none} when null. */
    static String hashOf(Object o) {
        return o == null ? "none" : String.valueOf(System.identityHashCode(o));
    }
}
