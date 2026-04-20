# Концепт: Tool Use + внешние источники данных (Confluence, Jira)

## Идея

Расширить команды плагина возможностью подтягивать контекст из внешних систем (Confluence, Jira) через механизм **function calling** GigaChat API. Это позволит AI сопоставлять тест-скрипт с реальными бизнес-требованиями.

Пример: `@plan сценарий авторизации из Confluence` → плагин достаёт страницу с требованиями → AI генерирует план с реальными endpoint'ами и бизнес-сущностями.

---

## Как работает GigaChat function calling

GigaChat поддерживает tool use через параметр `functions` в REST API (`POST /chat/completions`). MCP как таковой — только через Python SDK (langchain-gigachat + langgraph), для Java используем нативный function calling.

**Flow:**

```
1. Плагин отправляет запрос с описанием функций:
   messages: [user_message]
   functions: [{"name": "get_confluence_page", ...}]

2. GigaChat отвечает: finish_reason = "function_call"
   {"function_call": {"name": "get_confluence_page", "arguments": "{\"query\":\"...\"}"}}

3. Плагин сам выполняет запрос к Confluence REST API

4. Плагин отправляет второй запрос:
   messages: [user_message, assistant_function_call, function_result]

5. GigaChat генерирует финальный ответ с учётом данных из Confluence
```

GigaChat не имеет прямого доступа к сети — плагин является посредником.

---

## Что нужно реализовать

### 1. Поддержка functions в GigaChatService

Расширить `buildChatPayload()` — добавить опциональный параметр `functions[]`.
Расширить `generateResponse()` — добавить цикл обработки `function_call` ответов.

```java
// Новый интерфейс
interface ToolFunction {
    String getName();
    String getDescription();
    JsonNode getParametersSchema();
    String execute(String argumentsJson) throws Exception;
}

// Расширенный вызов
String generateResponseWithTools(List<String> conversation, List<ToolFunction> tools);
```

### 2. ConfluenceToolFunction

```java
// Конфигурация через user.properties:
// confluence.base.url = https://confluence.company.com
// confluence.token = Bearer xxxx

class ConfluenceToolFunction implements ToolFunction {
    String execute(String args) {
        // GET /rest/api/content?title={query}&expand=body.storage
        // Возвращает текст страницы
    }
}
```

### 3. Интеграция в команды

- **`@plan <запрос>`** — если в запросе есть ключевые слова ("из Confluence", "по сценарию"), подключать Confluence tool
- **`@this`** — опционально обогащать контекст элемента данными из Jira (связанные задачи)
- Новая команда **`@check`** — сравнить текущий тест-план с требованиями из Confluence

---

## Технические риски

- GigaChat может не вызвать функцию если не уверен в необходимости → нужен explicit prompt hint
- Confluence может вернуть HTML/XHTML → нужен стриппер разметки
- Токен к Confluence — чувствительные данные, хранить в JMeter properties, не логировать

---

## Приоритет

**После демо 29 апреля.** MVP за 3-5 дней при наличии доступа к тестовому Confluence.
