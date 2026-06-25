package org.gigameter.jmeter.ai.service.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Manual end-to-end smoke check against a REAL locally installed CLI (e.g. qwen). Not a JUnit test
 * — it talks to an external process — so it lives as a {@code main}. Run it on the machine where
 * the CLI is installed:
 *
 * <pre>
 *   # from the project root, after `mvn test-compile`
 *   java -cp "target/classes;target/test-classes;$JMETER_HOME/lib/*" \
 *        org.gigameter.jmeter.ai.service.cli.CliSmokeMain qwen "что делает Transaction Controller?"
 * </pre>
 *
 * (Use ';' as the classpath separator on Windows, ':' on Linux/macOS.) It exercises the real
 * Windows {@code .cmd} resolution, stdin prompt delivery, concurrent stream drain and timeout.
 */
public final class CliSmokeMain {

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "qwen";
        String prompt = args.length > 1 ? args[1]
                : "Одной строкой: что делает Transaction Controller в JMeter?";

        List<String> command = new ArrayList<>();
        command.add(cmd);
        command.add("--output-format");
        command.add("json");

        System.out.println("Launching: " + command + "  (prompt via stdin)");
        long t0 = System.currentTimeMillis();
        CliTransport.Result r = new CliTransport().run(command, prompt, 60_000,
                p -> System.out.println("started pid=" + p.pid()));
        long ms = System.currentTimeMillis() - t0;

        System.out.println("---");
        System.out.println("elapsedMs=" + ms + " timedOut=" + r.timedOut + " exit=" + r.exitCode);
        System.out.println("stderrLen=" + (r.stderr == null ? 0 : r.stderr.length()));
        if (!r.isSuccess()) {
            System.out.println("FAILED. stderr:\n" + r.stderr);
            return;
        }
        System.out.println("REPLY:\n" + CliResponseParser.parse(r.stdout, true));
    }

    private CliSmokeMain() {
    }
}
