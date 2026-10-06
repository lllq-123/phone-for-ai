package dev.phoneforai.companion;

import java.util.Locale;

/** Plain-Java scrcpy launch rules, kept free of Android types for JVM tests. */
final class ScrcpyLaunch {
    static final String VERSION = "4.1";
    static final int PREPARING = 0, STARTING = 1, ACCEPTING = 2, STREAMING = 3;
    private ScrcpyLaunch() {}

    static String socketName(String scid) {
        return "scrcpy_" + String.format(Locale.ROOT, "%08x", Integer.parseInt(scid, 16));
    }

    static String serverCommand(String pidPath, String jarPath, String scid) {
        return "umask 077; echo $$ > " + quote(pidPath)
                + " && CLASSPATH=" + quote(jarPath)
                + " exec /system/bin/app_process / com.genymobile.scrcpy.Server " + VERSION
                + " scid=" + scid + " tunnel_forward=false audio=false control=true clipboard_autosync=false"
                + " cleanup=false power_on=true stay_awake=false max_size=1280 video_bit_rate=1500000 max_fps=30"
                + " video_codec=h264 send_device_meta=false send_frame_meta=true send_stream_meta=true";
    }

    /** Error code for a session that failed in stage; exitCode is null while su is still running.
        started comes from a sticky flag, not output, which only keeps a truncated tail. Once
        scrcpy has started, su's exit code is ignored: Magisk's su may return 0 early. */
    static String failure(int stage, Integer exitCode, boolean timedOut, boolean started, String output) {
        if (stage == PREPARING) return "stream_server_failed";
        if (stage == STARTING) return "stream_root_denied";
        if (stage == STREAMING) return "stream_capture_failed";
        if (started) return timedOut ? "stream_connect_timeout" : "stream_server_failed";
        if (exitCode != null) return denied(exitCode, output) ? "stream_root_denied" : "stream_server_failed";
        return timedOut ? "stream_root_denied" : "stream_server_failed";
    }

    /** True only when su itself refused: Magisk prints "Permission denied" and exits with EACCES (13). */
    static boolean denied(Integer exitCode, String output) {
        if (exitCode == null) return false;
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("denied") || lower.contains("not allowed") || exitCode == 13;
    }

    private static String quote(String value) {
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException();
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
