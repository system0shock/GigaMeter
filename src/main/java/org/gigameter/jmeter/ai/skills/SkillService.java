package org.gigameter.jmeter.ai.skills;

import org.gigameter.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-editable "skills" — curated instruction modules for CLI-driven commands ({@code @lint},
 * {@code @plan}, {@code @optimize}). Each skill ships a built-in default; on first use the defaults
 * are written to an editable directory ({@code gigameter.skills.dir}, default
 * {@code <user.home>/.gigameter/skills}) as {@code <id>.md} files. If a file exists it overrides the
 * default, so end users can tune the rules (e.g. naming conventions for lint) without rebuilding.
 *
 * <p>Mutating skills (lint, plan) are combined with the {@code jmeter-ops} protocol by the caller so
 * the agent returns operations; the analysis skill (optimize) is text-only.
 */
public final class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    /** Built-in skill ids and their default instruction text. */
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("lint",
                "Задача: привести имена элементов тест-плана к единым правилам именования.\n" +
                "Правила по умолчанию:\n" +
                "- HTTP Sampler: «<Метод> <краткий путь>», например «POST /api/login».\n" +
                "- Thread Group: бизнес-смысл нагрузки, например «Основной сценарий».\n" +
                "- Контроллеры/ассершены/экстракторы: понятное назначение на русском.\n" +
                "- Убрать дефолтные имена вида «HTTP Request», «Thread Group» без смысла.\n" +
                "Для каждого переименования верни операцию set_property с key=\"TestElement.name\" и нужным id.\n" +
                "Не меняй ничего, кроме имён.");
        DEFAULTS.put("plan",
                "Задача: построить тест-план JMeter по сценарию пользователя одним add_element с ВЛОЖЕННОЙ\n" +
                "структурой через поле children (так вложенность гарантирована, id угадывать не нужно).\n" +
                "Иерархия: Thread Group → внутри HTTP Sampler'ы → внутри каждого его Response Assertion,\n" +
                "Header Manager, JSON Extractor, таймеры.\n" +
                "\n" +
                "ВЫБОР ГРУППЫ ПОТОКОВ по типу теста:\n" +
                "- обычный функциональный/дымовой тест → elementType \"threadgroup\";\n" +
                "- ТЕСТ МАКСИМУМА / нагрузочный / ступенчатый / stress / capacity / поиск предела →\n" +
                "  elementType \"ultimatethreadgroup\" со ступенями нагрузки в props \"utg.schedule\"\n" +
                "  (строки \"старт,задержка,разгон,удержание,сворачивание\" через ';'). НЕ используй\n" +
                "  обычный threadgroup для таких тестов.\n" +
                "  Пример Ultimate Thread Group: props {\"utg.schedule\":\"10,0,60,60,10; 50,0,60,60,10; "
                + "100,0,60,60,10; 200,0,60,60,10\"}.\n" +
                "- стабильная нагрузка по RPS → \"concurrencythreadgroup\" (props TargetLevel, RampUp, Hold)\n" +
                "  или Throughput Shaping Timer (\"tst.schedule\").\n" +
                "\n" +
                "Сложные свойства — спец-ключами props: тело — \"body\", форм-параметры — \"param.<имя>\",\n" +
                "заголовки (в Header Manager) — \"header.<Имя>\", ассершен — \"assert.code\" или\n" +
                "\"assert.field\"/\"assert.type\"/\"assert.patterns\".\n" +
                "\n" +
                "Пример (в дереве только Test Plan #1):\n" +
                "[{\"op\":\"add_element\",\"parentId\":1,\"elementType\":\"threadgroup\",\"name\":\"Сценарий\",\"children\":[\n" +
                "  {\"elementType\":\"httpsampler\",\"name\":\"POST /login\",\"props\":{\"HTTPSampler.method\":\"POST\",\"HTTPSampler.path\":\"/login\",\"body\":\"{\\\"user\\\":\\\"u\\\"}\"},\"children\":[\n" +
                "    {\"elementType\":\"headermanager\",\"name\":\"Headers\",\"props\":{\"header.Content-Type\":\"application/json\"}},\n" +
                "    {\"elementType\":\"responseassertion\",\"name\":\"200 OK\",\"props\":{\"assert.code\":\"200\"}}\n" +
                "  ]},\n" +
                "  {\"elementType\":\"httpsampler\",\"name\":\"GET /page\",\"props\":{\"HTTPSampler.method\":\"GET\",\"HTTPSampler.path\":\"/page\"},\"children\":[\n" +
                "    {\"elementType\":\"responseassertion\",\"name\":\"200 OK\",\"props\":{\"assert.code\":\"200\"}}\n" +
                "  ]}\n" +
                "]}]\n" +
                "Здесь Header Manager и Assertion вложены в свой сэмплер, сэмплеры — в Thread Group.");
        DEFAULTS.put("optimize",
                "Задача: проанализировать тест-план и предложить улучшения (производительность,\n" +
                "корректность, читаемость, реалистичность нагрузки). Дай конкретные actionable-пункты\n" +
                "списком. Это ТОЛЬКО анализ — НЕ возвращай блок jmeter-ops и ничего не меняй.");
    }

    private SkillService() {
    }

    /** Whether {@code id} is a known skill. */
    public static boolean isSkill(String id) {
        return id != null && DEFAULTS.containsKey(id.toLowerCase());
    }

    /** Whether a skill mutates the plan (needs the ops protocol) vs. pure analysis. */
    public static boolean isMutating(String id) {
        String s = id == null ? "" : id.toLowerCase();
        return "lint".equals(s) || "plan".equals(s);
    }

    /**
     * Returns the instruction text for {@code id}: the user's edited file if present, otherwise the
     * built-in default (which is also seeded to disk for editing on first access).
     */
    public static String getPrompt(String id) {
        String key = id == null ? "" : id.toLowerCase();
        String def = DEFAULTS.get(key);
        if (def == null) {
            return "";
        }
        Path file = skillsDir().resolve(key + ".md");
        try {
            if (Files.isRegularFile(file)) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            } else {
                seed(file, def); // write the default so the user has something to edit
            }
        } catch (IOException e) {
            log.debug("Skill file access failed for {}, using built-in default", key, e);
        }
        return def;
    }

    private static void seed(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
            log.info("Seeded editable skill file: {}", file);
        } catch (IOException e) {
            log.debug("Could not seed skill file {}", file, e);
        }
    }

    private static Path skillsDir() {
        String configured = AiConfig.getProperty("gigameter.skills.dir", "").trim();
        if (!configured.isEmpty()) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("user.home", "."), ".gigameter", "skills");
    }
}
