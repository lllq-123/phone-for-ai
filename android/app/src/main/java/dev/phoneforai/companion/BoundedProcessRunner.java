package dev.phoneforai.companion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/** Drains all child pipes while bounding returned bytes and wall time. */
final class BoundedProcessRunner {
    static final class Result {
        final byte[] stdout, stderr;
        final int exitCode;
        final boolean timedOut, truncated, ioFailed;
        Result(byte[] out, byte[] err, int code, boolean timeout, boolean truncated, boolean ioFailed) {
            stdout = out; stderr = err; exitCode = code; timedOut = timeout;
            this.truncated = truncated; this.ioFailed = ioFailed;
        }
    }
    private BoundedProcessRunner() {}

    static Result run(String[] argv, byte[] input, int timeoutSeconds, int maxBytes,
            boolean mergeErrors) throws IOException, InterruptedException {
        if (timeoutSeconds < 1 || timeoutSeconds > 30 || maxBytes < 1 || maxBytes > 49153
                || (input != null && input.length > 49152))
            throw new IllegalArgumentException("invalid_process_limits");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Process process = new ProcessBuilder(argv).redirectErrorStream(mergeErrors).start();
        Collector out = new Collector(process.getInputStream(), maxBytes);
        Collector err = new Collector(process.getErrorStream(), mergeErrors ? 1 : 4096);
        Writer writer = new Writer(process.getOutputStream(), input);
        Thread outThread = start("command-stdout", out);
        Thread errThread = start("command-stderr", err);
        Thread inThread = start("command-stdin", writer);
        boolean timeout = false;
        int code = -1;
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                timeout = true;
                long grace = deadline + TimeUnit.SECONDS.toNanos(2) - System.nanoTime();
                if (grace <= 0 || !process.waitFor(grace, TimeUnit.NANOSECONDS)) process.destroyForcibly();
            } else {
                code = process.exitValue();
                timeout = System.nanoTime() >= deadline;
            }
            join(inThread, deadline); join(outThread, deadline); join(errThread, deadline);
            if (inThread.isAlive() || outThread.isAlive() || errThread.isAlive()) {
                timeout = true; process.destroyForcibly();
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            close(process.getOutputStream()); close(process.getInputStream()); close(process.getErrorStream());
        }
        if (code == -1 && !process.isAlive()) code = process.exitValue();
        return new Result(out.bytes(), err.bytes(), code, timeout, out.truncated || err.truncated,
                out.failed || err.failed || writer.failed || inThread.isAlive()
                        || outThread.isAlive() || errThread.isAlive());
    }
    private static Thread start(String name, Runnable action) {
        Thread thread = new Thread(action, name); thread.setDaemon(true); thread.start(); return thread;
    }
    private static void join(Thread thread, long deadline) throws InterruptedException {
        long left = deadline - System.nanoTime();
        if (left > 0) thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
    }
    private static void close(java.io.Closeable value) { try { value.close(); } catch (IOException ignored) { } }

    private static final class Collector implements Runnable {
        final InputStream stream; final int limit; final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile boolean truncated, failed;
        Collector(InputStream stream, int limit) { this.stream = stream; this.limit = limit; }
        @Override public void run() {
            try (InputStream input = stream) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    synchronized (bytes) {
                        int keep = Math.min(count, limit - bytes.size());
                        if (keep > 0) bytes.write(buffer, 0, keep);
                        if (keep < count) truncated = true;
                    }
                }
            } catch (IOException error) { failed = true; }
        }
        byte[] bytes() { synchronized (bytes) { return bytes.toByteArray(); } }
    }
    private static final class Writer implements Runnable {
        final OutputStream stream; final byte[] input; volatile boolean failed;
        Writer(OutputStream stream, byte[] input) { this.stream = stream; this.input = input; }
        @Override public void run() {
            try (OutputStream output = stream) {
                if (input != null) { output.write(input); output.flush(); }
            } catch (IOException error) { failed = true; }
        }
    }
}
