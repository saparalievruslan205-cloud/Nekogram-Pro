package org.telegram.messenger;

import android.os.SystemClock;
import android.util.Log;

/** Local, opt-in troubleshooting only. Never log URIs or document identifiers. */
public final class VideoStreamDiagnostics {
    public static final boolean ENABLED = BuildConfig.VIDEO_STREAM_DIAGNOSTICS;

    private VideoStreamDiagnostics() {}

    public static long now() {
        return ENABLED ? SystemClock.elapsedRealtime() : 0;
    }

    public static void event(Object source, String event) {
        if (ENABLED) {
            Log.d("VideoStreamDiag", Integer.toHexString(System.identityHashCode(source)) + " " + event);
        }
    }
}
