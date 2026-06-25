package org.gigameter.jmeter.ai.service.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Subprocess execution layer for CLI-backed AI providers.
 *
 * <p>Designed to close the three known blockers of the original prototype:
 * <ul>
 *   <li><b>Windows command resolution</b> — npm shims are {@code *.cmd} files which
 *       {@code CreateProcess} cannot launch directly; they are run through {@code cmd /c}.</li>
 *   <li><b>Effective timeout</b> — stdout/stderr are drained on separate threads, so
 *       {@link Process#waitFor(long, TimeUnit)} actually governs the process lifetime instead of
 *       blocking forever on a read from a hung child.</li>
 *   <li><b>No two-channel deadlock</b> — stdout and stderr are consumed concurrently, so a child
 *       that writes a large volume to stderr cannot block while the parent reads stdout.</li>
 * </ul>
 *
 * <p>The prompt is delivered via the child's stdin (not as an argv argument), which sidesteps the
 * Windows ~32 KiB command-line limit and JDK-8221858 argument-quoting issues.
 */
public final class CliTransport {

    private static final Logger log = LoggerFactory.getLogger(CliTransport.class);

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Outcome of a CLI invocation. */
    public static final class Result {
        public final String stdout;
        public final String stderr;
        public final int exitCode;
        public final boolean timedOut;

        Result(String stdout, String stderr, int exitCode, boolean timedOut) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
        }

        public boolean isSuccess() {
            return !timedOut && exitCode == 0;
        }
    }

    private final Charset charset;
    private final java.io.File workingDir;

    public CliTransport() {
        this(StandardCharsets.UTF_8);
    }

    public CliTransport(Charset charset) {
        this(charset, null);
    }

    /**
     * @param workingDir directory to launch the child in, or {@code null} to inherit the JVM's. Use
     *                   an <b>empty</b> directory for agentic CLIs (e.g. qwen-code): launched in a
     *                   real project they treat it as a coding task and explore files over many
     *                   slow, flaky tool-using turns instead of answering the prompt directly.
     */
    public CliTransport(Charset charset, java.io.File workingDir) {
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
        this.workingDir = workingDir;
    }

    /**
     * Runs {@code command} (executable + flags, without the prompt), feeding {@code stdinPayload}
     * to the child's standard input. Returns once the process finishes or the timeout elapses.
     *
     * @param command       executable followed by flags; must not be empty
     * @param stdinPayload  text written to the child's stdin, or {@code null} for no input
     * @param timeoutMs      hard wall-clock limit; on expiry the process tree is force-killed
     * @param onStart        optional callback receiving the live {@link Process} (e.g. for a Stop
     *                       button); may be {@code null}
     */
    public Result run(List<String> command, String stdinPayload, long timeoutMs,
                      Consumer<Process> onStart) throws IOException, InterruptedException {
        return run(command, stdinPayload, timeoutMs, onStart, null);
    }

    /**
     * Streaming variant: identical lifecycle to {@link #run}, but {@code onStdoutLine} is invoked on
     * a background thread for each line of stdout as it arrives (for incremental UI rendering of
     * {@code --output-format stream-json}). The returned {@link Result#stdout} still holds the full
     * output. Pass {@code null} for {@code onStdoutLine} to fall back to plain buffered reads.
     */
    public Result run(List<String> command, String stdinPayload, long timeoutMs,
                      Consumer<Process> onStart, Consumer<String> onStdoutLine)
            throws IOException, InterruptedException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        List<String> launch = resolveLaunchCommand(command);
        log.info("Launching CLI: {}", launch);

        ProcessBuilder pb = new ProcessBuilder(launch);
        pb.redirectErrorStream(false);
        if (workingDir != null && workingDir.isDirectory()) {
            pb.directory(workingDir);
        }

        Process process = pb.start();
        if (onStart != null) {
            try {
                onStart.accept(process);
            } catch (RuntimeException e) {
                log.warn("onStart callback threw", e);
            }
        }

        // Drain stdout and stderr concurrently so neither pipe can fill and deadlock.
        StreamCollector out = new StreamCollector(process.getInputStream(), charset, "cli-stdout", onStdoutLine);
        StreamCollector err = new StreamCollector(process.getErrorStream(), charset, "cli-stderr", null);
        out.start();
        err.start();

        // Write the prompt to stdin and close it so the CLI knows input is complete.
        try (OutputStream stdin = process.getOutputStream()) {
            if (stdinPayload != null) {
                stdin.write(stdinPayload.getBytes(charset));
                stdin.flush();
            }
        } catch (IOException e) {
            // Child may have exited early / not consumed stdin; not fatal to result collection.
            log.debug("Writing to CLI stdin failed (child may have exited early)", e);
        }

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            log.error("CLI timed out after {}ms, killing process tree", timeoutMs);
            killTree(process);
            joinQuietly(out, err);
            return new Result(out.text(), err.text(), -1, true);
        }

        joinQuietly(out, err);
        int exit = process.exitValue();
        if (exit != 0) {
            log.warn("CLI exited with code {}: {}", exit, truncate(err.text()));
        }
        return new Result(out.text(), err.text(), exit, false);
    }

    /** Convenience overload without a start callback. */
    public Result run(List<String> command, String stdinPayload, long timeoutMs)
            throws IOException, InterruptedException {
        return run(command, stdinPayload, timeoutMs, null);
    }

    /**
     * Resolves a runnable launch command. On Windows, {@code *.cmd}/{@code *.bat} shims (how npm
     * installs CLIs such as {@code qwen}) cannot be launched by {@code CreateProcess} directly, so
     * they are wrapped in {@code cmd /c}. Plain {@code *.exe} and non-Windows commands are launched
     * as given.
     */
    static List<String> resolveLaunchCommand(List<String> command) {
        if (!IS_WINDOWS) {
            return command;
        }
        String exe = command.get(0);
        if (needsCmdWrapper(exe)) {
            List<String> wrapped = new ArrayList<>(command.size() + 2);
            wrapped.add("cmd");
            wrapped.add("/c");
            wrapped.addAll(command);
            return wrapped;
        }
        return command;
    }

    /**
     * A bare command name (no extension, e.g. {@code "qwen"}) or one ending in {@code .cmd}/{@code
     * .bat} must be run via {@code cmd /c} on Windows. Explicit {@code .exe} can be launched
     * directly.
     */
    private static boolean needsCmdWrapper(String exe) {
        String lower = exe.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".com")) {
            return false;
        }
        return true;
    }

    /** Force-kill the process and all descendants (Java 9+ {@link ProcessHandle}). */
    private static void killTree(Process process) {
        try {
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Throwable t) {
            log.debug("Could not enumerate descendants for kill", t);
        }
        process.destroyForcibly();
    }

    private static void joinQuietly(StreamCollector... collectors) {
        for (StreamCollector c : collectors) {
            try {
                c.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }

    /**
     * Reads a stream fully on its own thread. Without an {@code onLine} callback it buffers raw bytes
     * (exact output preserved). With one, it reads line by line and invokes the callback per line as
     * data arrives (for streaming), while still accumulating the full text.
     */
    private static final class StreamCollector extends Thread {
        private final InputStream in;
        private final Charset charset;
        private final Consumer<String> onLine;
        private final AtomicReference<String> result = new AtomicReference<>("");

        StreamCollector(InputStream in, Charset charset, String name, Consumer<String> onLine) {
            super(name);
            setDaemon(true);
            this.in = in;
            this.charset = charset;
            this.onLine = onLine;
        }

        @Override
        public void run() {
            if (onLine != null) {
                runLineMode();
            } else {
                runByteMode();
            }
        }

        private void runByteMode() {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                }
            } catch (IOException e) {
                // Stream closed due to process kill; keep whatever was read so far.
            } finally {
                result.set(new String(buf.toByteArray(), charset));
            }
        }

        private void runLineMode() {
            StringBuilder all = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, charset))) {
                String line;
                while ((line = r.readLine()) != null) {
                    all.append(line).append('\n');
                    try {
                        onLine.accept(line);
                    } catch (RuntimeException e) {
                        log.debug("onStdoutLine callback threw", e);
                    }
                }
            } catch (IOException e) {
                // Stream closed due to process kill; keep whatever was read so far.
            } finally {
                result.set(all.toString());
            }
        }

        String text() {
            return result.get();
        }
    }
}
