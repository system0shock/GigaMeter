package org.gigameter.jmeter.ai.lint;

/**
 * JMeter best-practice naming rules used as the default baseline for @lint.
 */
final class LintRules {

    static final String DEFAULT_RULES =
        "Правила именования элементов JMeter (применяй по умолчанию):\n" +
        "1. ThreadGroup            → TG_<Назначение>          Пример: TG_UserLogin, TG_OrderFlow\n" +
        "2. HTTPSamplerProxy       → HTTP_<NN>_<МЕТОД>_<Ресурс>  Пример: HTTP_10_POST_Login, HTTP_20_GET_Orders\n" +
        "   (нумерация кратная 10 по порядку среди HTTP-самплеров одного уровня)\n" +
        "3. JSR223Sampler / JSR223PreProcessor / JSR223PostProcessor → JSR_<назначение>  Пример: JSR_ExtractToken\n" +
        "4. TransactionController  → TC_<Флоу>                Пример: TC_Checkout, TC_AuthFlow\n" +
        "5. ResponseAssertion / JSONPathAssertion → ASSERT_<что>  Пример: ASSERT_Status200, ASSERT_TokenPresent\n" +
        "6. JSONPostProcessor / RegexExtractor   → EXT_<ИмяПеременной>  Пример: EXT_Token, EXT_OrderId\n" +
        "7. ConstantTimer / UniformRandomTimer   → TIMER_<контекст>  Пример: TIMER_AfterLogin\n" +
        "8. CSVDataSet             → CSV_<ОписаниеДанных>     Пример: CSV_Users, CSV_OrderItems\n" +
        "9. HeaderManager          → Headers_<контекстСамплера>  Пример: Headers_Auth\n" +
        "10. Имена — лаконичные, на английском, без пробелов (snake_case или CamelCase).\n" +
        "11. Отключённые элементы: сохраняй логическое имя, не добавляй префикс Disabled_.\n";

    private LintRules() {}

    /**
     * Builds the full AI prompt for a lint batch.
     *
     * @param planJson    compact JSON of the elements to rename (from JMeterPlanSerializer)
     * @param userCommand custom instruction from the user, or null/empty to use defaults only
     * @param total       total number of elements AI must rename (no skips)
     */
    static String buildPrompt(String planJson, String userCommand, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("Переименуй ВСЕ ").append(total)
          .append(" элементов JMeter тест-плана.\n");
        sb.append("Ты ОБЯЗАН вернуть ровно ").append(total)
          .append(" записей в массиве renames — по одной на каждый элемент, без пропусков.\n\n");

        sb.append("БАЗОВЫЕ ПРАВИЛА ИМЕНОВАНИЯ:\n").append(DEFAULT_RULES).append("\n");

        if (userCommand != null && !userCommand.trim().isEmpty()
                && !userCommand.equalsIgnoreCase("rename")) {
            sb.append("ДОПОЛНЕНИЕ / ПЕРЕОПРЕДЕЛЕНИЕ ОТ ПОЛЬЗОВАТЕЛЯ:\n")
              .append(userCommand.trim()).append("\n")
              .append("(если правило пользователя конфликтует с базовым — следуй правилу пользователя)\n\n");
        }

        sb.append("Элементы тест-плана (JSON):\n").append(planJson).append("\n\n");

        sb.append("Ответ — только JSON без объяснений:\n");
        sb.append("{\"renames\":[{\"id\":1,\"name\":\"NewName\"},...,{\"id\":")
          .append(total).append(",\"name\":\"NewNameN\"}]}\n");

        return sb.toString();
    }
}
