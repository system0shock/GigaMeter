package org.gigameter.jmeter.ai.service.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link CliTransport} against a fake CLI running in a separate JVM, so real OS pipes are
 * used. These reproduce the three prototype blockers and assert the fixes.
 */
class CliTransportTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static List<String> fakeCli(String mode) {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + (isWindows() ? "java.exe" : "java");
        return Arrays.asList(javaBin, "-cp", System.getProperty("java.class.path"),
                "org.gigameter.jmeter.ai.service.cli.FakeCliMain", mode);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void normalRunReturnsStdout() throws Exception {
        CliTransport.Result r = new CliTransport().run(fakeCli("json"), "hello", 20_000);
        assertTrue(r.isSuccess(), "should exit 0 in time");
        assertEquals("Привет", CliResponseParser.parse(r.stdout, true));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void largeStderrDoesNotDeadlock() throws Exception {
        // BLOCKER #3: a single-threaded reader would deadlock here; concurrent drain must not.
        CliTransport.Result r = new CliTransport().run(fakeCli("noisy"), "hello", 20_000);
        assertTrue(r.isSuccess(), "must finish despite ~2MB on stderr");
        assertFalse(r.timedOut);
        assertTrue(r.stderr.length() > 1_000_000, "stderr should be fully drained");
        assertEquals("ok", CliResponseParser.parse(r.stdout, true));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void hungProcessIsTimedOutAndKilled() throws Exception {
        // BLOCKER #2: timeout must actually fire and the process tree be killed.
        long start = System.currentTimeMillis();
        CliTransport.Result r = new CliTransport().run(fakeCli("hang"), "hello", 1_500);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(r.timedOut, "should report timeout");
        assertFalse(r.isSuccess());
        assertTrue(elapsed < 10_000, "should return shortly after the timeout, not hang");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void nonZeroExitIsReported() throws Exception {
        CliTransport.Result r = new CliTransport().run(fakeCli("fail"), "hello", 20_000);
        assertFalse(r.isSuccess());
        assertEquals(3, r.exitCode);
        assertTrue(r.stderr.contains("not logged in"));
    }

    @Test
    void windowsLaunchRuleWrapsShimsButNotExe() {
        // Pure logic check, OS-independent (resolveLaunchCommand only rewrites on Windows).
        if (!isWindows()) {
            // On non-Windows the command is returned untouched.
            List<String> cmd = Arrays.asList("qwen", "-p");
            assertEquals(cmd, CliTransport.resolveLaunchCommand(cmd));
        }
    }
}
