package org.gigameter.jmeter.ai.service.cli;

import java.io.IOException;
import java.util.Arrays;

/**
 * A fake CLI used by {@link CliTransportTest}. Runs as a separate JVM process so the test exercises
 * real OS pipes (the only faithful way to reproduce the stderr-deadlock and timeout behavior).
 * Mode is the first argument: {@code json | noisy | hang | fail}.
 */
public final class FakeCliMain {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "json";

        // Consume stdin so the parent's write/close never blocks, mimicking a CLI reading its prompt.
        Thread drain = new Thread(() -> {
            try {
                byte[] b = new byte[4096];
                while (System.in.read(b) != -1) { /* discard */ }
            } catch (IOException ignored) {
            }
        });
        drain.setDaemon(true);
        drain.start();

        switch (mode) {
            case "json":
                // Cyrillic escaped as \\uXXXX, exactly like --output-format json (ensure_ascii)
                System.out.print("{\"response\": \"\\u041f\\u0440\\u0438\\u0432\\u0435\\u0442\"}");
                System.out.flush();
                break;
            case "noisy":
                System.out.print("{\"response\": \"ok\"}");
                System.out.flush();
                char[] chunk = new char[1024];
                Arrays.fill(chunk, 'E');
                for (int i = 0; i < 2048; i++) {  // ~2 MiB to stderr, far beyond any pipe buffer
                    System.err.print(chunk);
                }
                System.err.flush();
                break;
            case "hang":
                Thread.sleep(120_000);
                break;
            case "fail":
                System.err.print("fatal: not logged in");
                System.err.flush();
                System.exit(3);
                break;
            default:
                System.out.print("{\"response\": \"unknown mode\"}");
                System.out.flush();
        }
    }

    private FakeCliMain() {
    }
}
