package dev.phoneforai.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ScrcpyLaunchTest {
    private static final String DEVICE_LINE = "[server] INFO: Device: [vendor] model (Android 14)\n";
    private static final int ACCEPTING = ScrcpyLaunch.ACCEPTING;

    @Test
    public void socketNamePadsTheScidToEightHexDigits() {
        assertEquals("scrcpy_00123abc", ScrcpyLaunch.socketName("0123abc"));
        assertEquals("scrcpy_0fffffff", ScrcpyLaunch.socketName("fffffff"));
        assertEquals("scrcpy_00000001", ScrcpyLaunch.socketName("0000001"));
    }

    @Test
    public void serverCommandAsksScrcpyToConnectBackWithoutADummyByte() {
        String command = ScrcpyLaunch.serverCommand("/data/files/screen.pid", "/data/files/scrcpy-server-4.1.jar", "0123abc");
        assertEquals("umask 077; echo $$ > '/data/files/screen.pid' && CLASSPATH='/data/files/scrcpy-server-4.1.jar'"
                + " exec /system/bin/app_process / com.genymobile.scrcpy.Server 4.1"
                + " scid=0123abc tunnel_forward=false audio=false control=true clipboard_autosync=false"
                + " cleanup=false power_on=true stay_awake=false max_size=1280 video_bit_rate=1500000 max_fps=30"
                + " video_codec=h264 send_device_meta=false send_frame_meta=true send_stream_meta=true", command);
        assertTrue(command.contains(" scid=0123abc "));
        assertTrue(command.contains(" tunnel_forward=false "));
        assertFalse(command.contains("tunnel_forward=true"));
        assertFalse(command.contains("send_dummy_byte"));
        assertTrue(command.contains("com.genymobile.scrcpy.Server " + ScrcpyLaunch.VERSION + " scid=0123abc "));
    }

    @Test
    public void serverCommandQuotesPathsForTheShell() {
        String command = ScrcpyLaunch.serverCommand("/data/it's.pid", "/data/o'k/server.jar", "0000001");
        assertTrue(command.startsWith("umask 077; echo $$ > '/data/it'\\''s.pid' && CLASSPATH='/data/o'\\''k/server.jar' exec "));
        assertThrows(IllegalArgumentException.class,
                () -> ScrcpyLaunch.serverCommand("/data/a\0b.pid", "/data/server.jar", "0000001"));
    }

    @Test
    public void failuresBeforeSuLaunchMeanTheServerCouldNotStart() {
        assertEquals("stream_server_failed", ScrcpyLaunch.failure(ScrcpyLaunch.PREPARING, null, false, false, ""));
        assertEquals("stream_server_failed", ScrcpyLaunch.failure(ScrcpyLaunch.PREPARING, 13, true, true, "Permission denied\n"));
    }

    @Test
    public void suThatCannotStartMeansRootWasDenied() {
        assertEquals("stream_root_denied", ScrcpyLaunch.failure(ScrcpyLaunch.STARTING, null, false, false, ""));
    }

    @Test
    public void suReturningZeroEarlyWhileScrcpyRunsIsAConnectTimeout() {
        assertEquals("stream_connect_timeout", ScrcpyLaunch.failure(ACCEPTING, 0, true, true, DEVICE_LINE));
    }

    @Test
    public void afterScrcpyStartedOnlyTheTimeoutMatters() {
        assertEquals("stream_connect_timeout", ScrcpyLaunch.failure(ACCEPTING, null, true, true, DEVICE_LINE));
        assertEquals("stream_server_failed", ScrcpyLaunch.failure(ACCEPTING, null, false, true, DEVICE_LINE));
        assertEquals("stream_server_failed", ScrcpyLaunch.failure(ACCEPTING, 0, false, true, DEVICE_LINE));
        assertEquals("stream_server_failed", ScrcpyLaunch.failure(ACCEPTING, 1, false, true, "[server] ERROR: Could not open video stream\n"));
        assertEquals("stream_server_failed", ScrcpyLaunch.failure(ACCEPTING, 13, false, true, DEVICE_LINE + "[server] ERROR: Permission denied\n"));
    }

    @Test
    public void suExitBeforeScrcpyStartedIsClassifiedByItsOutputAndExitCode() {
        assertEquals("stream_root_denied", ScrcpyLaunch.failure(ACCEPTING, 13, false, false, "Permission denied\n"));
        assertEquals("stream_root_denied", ScrcpyLaunch.failure(ACCEPTING, 1, false, false, "Permission denied\n"));
        assertEquals("stream_root_denied", ScrcpyLaunch.failure(ACCEPTING, 13, false, false, ""));
        assertEquals("stream_root_denied", ScrcpyLaunch.failure(ACCEPTING, 1, false, false, "SU: NOT ALLOWED\n"));
        assertEquals("stream_server_failed", ScrcpyLaunch.failure(ACCEPTING, 0, false, false, ""));
        assertEquals("stream_server_failed", ScrcpyLaunch.failure(ACCEPTING, 1, false, false, "sh: app_process: inaccessible\n"));
    }

    @Test
    public void runningSuBeforeScrcpyStartedDependsOnTheTimeout() {
        assertEquals("stream_root_denied", ScrcpyLaunch.failure(ACCEPTING, null, true, false, ""));
        assertEquals("stream_root_denied", ScrcpyLaunch.failure(ACCEPTING, null, true, false, "waiting for su\n"));
        assertEquals("stream_server_failed", ScrcpyLaunch.failure(ACCEPTING, null, false, false, ""));
    }

    @Test
    public void startedFlagStillCountsAfterTheServerPrefixWasTruncatedAway() {
        String trace = "\tat com.genymobile.scrcpy.Server.main(Server.java:1)\n".repeat(120);
        String tail = trace.substring(trace.length() - 4096);
        assertTrue(trace.length() > 4096);
        assertFalse(tail.contains("[server]"));
        assertEquals("stream_connect_timeout", ScrcpyLaunch.failure(ACCEPTING, null, true, true, tail));
        assertEquals("stream_connect_timeout", ScrcpyLaunch.failure(ACCEPTING, 0, true, true, tail));
        assertEquals("stream_server_failed", ScrcpyLaunch.failure(ACCEPTING, 1, false, true, tail));
        assertEquals("stream_server_failed", ScrcpyLaunch.failure(ACCEPTING, 13, false, true, tail + "Permission denied\n"));
    }

    @Test
    public void failuresAfterBothConnectionsAreCaptureFailures() {
        int streaming = ScrcpyLaunch.STREAMING;
        assertEquals("stream_capture_failed", ScrcpyLaunch.failure(streaming, null, false, true, DEVICE_LINE));
        assertEquals("stream_capture_failed", ScrcpyLaunch.failure(streaming, null, true, false, ""));
        assertEquals("stream_capture_failed", ScrcpyLaunch.failure(streaming, 13, false, false, "Permission denied\n"));
    }

    @Test
    public void onlyAnExplicitSuRefusalCountsAsDenied() {
        assertFalse(ScrcpyLaunch.denied(null, "Permission denied\n"));
        assertTrue(ScrcpyLaunch.denied(13, ""));
        assertTrue(ScrcpyLaunch.denied(1, "Permission denied\n"));
        assertTrue(ScrcpyLaunch.denied(1, "su: NOT ALLOWED\n"));
        // Magisk's su can return 0 silently while scrcpy keeps running under the shell uid.
        assertFalse(ScrcpyLaunch.denied(0, ""));
        assertFalse(ScrcpyLaunch.denied(1, "Error: Could not find class\n"));
    }
}
