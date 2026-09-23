package com.ryanheise.audioservice;

import android.util.Log;

/**
 * Unified handling of the errors the plugin catches instead of letting them
 * crash the app. Each one is logged to logcat with its stack trace and, when a
 * Dart AudioHandler is connected, delivered to Dart, where it is added to
 * AudioService.asyncError as a PlatformException whose code is {@code where}.
 */
final class AudioServiceErrors {
    private AudioServiceErrors() {}

    /**
     * Logs {@code error} and delivers it to Dart when possible. Safe to call
     * from any thread; never throws.
     *
     * @param where the plugin method that caught it, e.g. "AudioService.onStartCommand"
     */
    static void report(String where, Throwable error) {
        log(where, error);
        AudioServicePlugin.sendErrorToDart(where, error);
    }

    /**
     * Logs {@code error} only, for callers that already hand it to Dart, such
     * as a method call answered with an error.
     */
    static void log(String where, Throwable error) {
        Log.e(AudioServiceLifecycleLog.TAG, where + " failed", error);
    }

    /** The code for a method call answered with {@code error}: its message, or its type if it has none. */
    static String errorCode(Throwable error) {
        final String message = error.getMessage();
        return message != null ? message : error.getClass().getName();
    }
}
