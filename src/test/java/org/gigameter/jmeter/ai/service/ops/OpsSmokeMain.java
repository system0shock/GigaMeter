package org.gigameter.jmeter.ai.service.ops;

import org.gigameter.jmeter.ai.service.cli.CliResponseParser;
import org.gigameter.jmeter.ai.service.cli.CliTransport;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage-0 end-to-end go/no-go: feeds a REAL local CLI (qwen) the jmeter-ops protocol plus a sample
 * tree and an edit request, then runs the agent's reply through the real extract→parse→validate
 * pipeline. Prints whether the model produced schema-valid operations — the riskiest assumption of
 * CLI-driven editing. Not a JUnit test (talks to an external process).
 *
 * <pre>
 *   java -cp "target/jmeter-agent-Alpha-0.1.jar;target/test-classes" \
 *        org.gigameter.jmeter.ai.service.ops.OpsSmokeMain qwen
 * </pre>
 */
public final class OpsSmokeMain {

    private static final String SAMPLE_TREE =
            "Структура JMeter тест-плана (дерево элементов, отступы = вложенность; #N — id элемента):\n" +
            "#1 └─ [Test Plan] \"Test Plan\"\n" +
            "#2   └─ [Thread Group] \"Главная нагрузка\" | users=10, ramp=5s\n" +
            "#3     └─ [HTTP Sampler] \"Логин\" | POST example.com/login\n" +
            "#4     └─ [HTTP Sampler] \"Главная\" | GET example.com/\n";

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "qwen";
        long timeout = args.length > 1 ? Long.parseLong(args[1]) : 600_000L;

        String userTask = "тест максимума: Ultimate Thread Group со ступенчатым ростом нагрузки "
                + "(10, 50, 100, 200 потоков, ступени по ~1 минуте), внутри HTTP Sampler GET /api/health "
                + "с проверкой кода 200";

        String skill = org.gigameter.jmeter.ai.skills.SkillService.getPrompt("plan");
        String prompt = OpsProtocol.SYSTEM_PROMPT + "\n"
                + skill + "\n\nЗапрос пользователя: " + userTask + "\n\n"
                + OpsProtocol.buildContextBlock("#1 └─ [TestPlan] \"Test Plan\"\n", "deadbeef");

        List<String> command = new ArrayList<>();
        command.add(cmd);
        command.add("--output-format");
        command.add("stream-json");
        command.add("--include-partial-messages");
        command.add("--chat-recording"); // match the GUI session path

        System.out.println("=== Stage-0 ops smoke (stream-json, mirrors GUI): launching " + cmd + " ===");
        StringBuilder deltas = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<String> finalResult =
                new java.util.concurrent.atomic.AtomicReference<>(null);
        java.util.function.Consumer<String> onLine = line -> {
            com.fasterxml.jackson.databind.JsonNode node =
                    org.gigameter.jmeter.ai.service.cli.StreamJsonParser.parseLine(line);
            if (node == null) return;
            String d = org.gigameter.jmeter.ai.service.cli.StreamJsonParser.textDelta(node);
            if (d != null) deltas.append(d);
            String f = org.gigameter.jmeter.ai.service.cli.StreamJsonParser.finalResult(node);
            if (f != null) finalResult.set(f);
        };
        long t0 = System.currentTimeMillis();
        CliTransport.Result r = new CliTransport().run(command, prompt, timeout,
                p -> System.out.println("started pid=" + p.pid()), onLine);
        long ms = System.currentTimeMillis() - t0;
        String fin = finalResult.get();
        System.out.println("elapsedMs=" + ms + " timedOut=" + r.timedOut + " exit=" + r.exitCode);
        System.out.println("LENGTHS: resultEvent=" + (fin == null ? -1 : fin.length())
                + " deltas=" + deltas.length() + " stdout=" + (r.stdout == null ? 0 : r.stdout.length()));

        if (!r.isSuccess()) {
            System.out.println("FAILED to run CLI. stderr:\n" + r.stderr);
            return;
        }

        String reply = (fin != null && !fin.trim().isEmpty()) ? fin.trim() : deltas.toString().trim();
        System.out.println("\n=== AGENT REPLY ===\n" + reply);

        String payload = FencedOpsExtractor.extract(reply);
        if (payload == null) {
            System.out.println("\nNO-GO: ответ не содержит блока jmeter-ops.");
            return;
        }
        System.out.println("\n=== EXTRACTED jmeter-ops ===\n" + payload);

        try {
            List<PlanOp> ops = PlanOpsParser.parse(payload);
            List<String> problems = PlanOpsValidator.collectProblems(ops);
            System.out.println("\nparsed ops: " + ops.size());
            if (problems.isEmpty()) {
                System.out.println("GO: операции прошли валидацию ✓");
                System.out.println("\n=== PREVIEW ===\n" + OpsPreviewRenderer.render(ops));
            } else {
                System.out.println("PARTIAL: операции разобраны, но есть проблемы:");
                problems.forEach(p -> System.out.println("  • " + p));
            }
        } catch (OpsException e) {
            System.out.println("\nNO-GO: разбор/валидация не прошли: " + e.getMessage());
            e.problems().forEach(p -> System.out.println("  • " + p));
        }
    }

    private OpsSmokeMain() {
    }
}
