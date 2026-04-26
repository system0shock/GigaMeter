# GigaMeter

GigaMeter — плагин для Apache JMeter с AI-ассистентом в интерфейсе.  
Помогает анализировать и улучшать тест-план, объяснять JSR223-скрипты и ускорять рутинные операции.

## Что умеет плагин

- Чат-панель AI внутри JMeter с историей диалога
- Выбор провайдера и модели прямо в UI (OpenAI, Sber GigaChat, DeepSeek)
- Автодополнение команд в поле ввода
- Команды для работы с элементами тест-плана (`@this`, `@lint`, `@wrap`, `@plan`, `@optimize`)
- Контекстное меню AI в JSR223-редакторе (рефакторинг, try/catch, форматирование)
- Откат любого AI-изменения командой `@rollback`

## Установка

### Через Plugins Manager

Интеграция в каталог Plugins Manager пока в работе.  
До публикации используйте ручную установку.

### Ручная установка

1. Скачайте JAR из [Releases](https://github.com/system0shock/GigaMeter/releases/latest).
2. Поместите JAR в `JMETER_HOME/lib/ext`.
3. Скопируйте настройки из `jmeter-ai-sample.properties` в:
   - `JMETER_HOME/bin/user.properties`, или
   - `JMETER_HOME/bin/jmeter.properties`
4. Заполните параметры выбранного AI-провайдера.
5. Перезапустите JMeter.

## Требования

- Java 17 (для сборки и CI)
- Maven 3.9+
- Apache JMeter 5.5 или 5.6.2

## Конфигурация

### Общие параметры

- `jmeter.ai.service.type` (`openai`, `giga`, `deepseek`)
- `jmeter.ai.refactoring.enabled`

### OpenAI

- `openai.api.key`
- `openai.default.model`
- `openai.temperature`
- `openai.max.tokens`
- `openai.max.history.size`
- `openai.system.prompt`
- `openai.log.level`

### Sber GigaChat

- `giga.auth.key`
- `giga.access.token`
- `giga.auth.url`
- `giga.scope`
- `giga.api.base.url`
- `giga.default.model`
- `giga.temperature`
- `giga.max.tokens`
- `giga.max.history.size`
- `giga.timeout.ms`
- `giga.ssl.insecure`
- `giga.system.prompt`

### DeepSeek

- `deepseek.api.key`
- `deepseek.api.base.url`
- `deepseek.default.model`
- `deepseek.temperature`
- `deepseek.max.tokens`
- `deepseek.max.history.size`
- `deepseek.timeout.ms`
- `deepseek.system.prompt`

## Команды чата

### `@this`

Анализирует текущий выбранный элемент тест-плана.

- Показывает, что делает элемент, его ключевые настройки и потенциальные проблемы.
- Учитывает соседние элементы и родительский контейнер для контекстного анализа.
- **Для JSR223-элементов** (Sampler, PreProcessor, PostProcessor): переключается в режим объяснения скрипта — раскрывает цель кода, какие JMeter-переменные читает и записывает, побочные эффекты, потенциальные проблемы.
- Поддерживает уточняющий вопрос: `@this <вопрос>`.

### `@optimize`

Формирует рекомендации по оптимизации выбранного элемента.

### `@lint`

Переименовывает элементы тест-плана в единый читаемый стиль по правилам JMeter best practices.

**Правила по умолчанию:**

| Тип элемента | Шаблон имени | Пример |
|---|---|---|
| Thread Group | `TG_<Назначение>` | `TG_UserLogin` |
| HTTP Sampler | `HTTP_<NN>_<МЕТОД>_<Ресурс>` | `HTTP_10_POST_Login` |
| JSR223 (любой) | `JSR_<назначение>` | `JSR_ExtractToken` |
| Transaction Controller | `TC_<Флоу>` | `TC_Checkout` |
| Response/JSONPath Assertion | `ASSERT_<что>` | `ASSERT_Status200` |
| JSON/Regex Extractor | `EXT_<Переменная>` | `EXT_Token` |
| Timer | `TIMER_<контекст>` | `TIMER_AfterLogin` |
| CSV Data Set | `CSV_<Данные>` | `CSV_Users` |
| Header Manager | `Headers_<контекст>` | `Headers_Auth` |

Область действия определяется выбором в дереве:
- выбран Test Plan — переименовываются все элементы
- выбран Thread Group — переименовывается вся группа с вложенными элементами
- выбраны несколько узлов — переименовываются только они

Поддерживает пользовательский стиль: `@lint <инструкция>` (например: `@lint используй snake_case, английский язык`).

Поддерживает откат: `@rollback`.

### `@wrap`

Группирует HTTP Sampler в Transaction Controller в выбранном Thread Group.  
Поддерживает откат: `@rollback`.

### `@plan`

Работа с черновиком backend тест-плана:

- `@plan <сценарий>` — генерирует структурированный черновик и показывает preview без изменений в дереве.
- `@plan apply` — применяет последний черновик в текущий тест-план (Thread Group, HTTP Sampler, Timer, Assertion, Extractor, Cookie Manager и т.д.).
- `@plan analyze` — анализирует текущий тест-план: структуру, HTTP-эндпоинты, Thread Groups, AI-интерпретацию бизнес-логики.

**Что генерирует `@plan apply`:**
- Thread Group с настройками users/ramp/duration
- HTTP Sampler с методом, путём, телом запроса и заголовками
- HTTP Header Manager для заголовков
- HTTP Cookie Manager (добавляется автоматически)
- Response Assertion (по status code)
- JSON Extractor (extract переменных)
- JSR223 Sampler/PreProcessor с реальным Groovy-кодом
- Constant Timer (think time)
- Transaction Controller для группировки шагов
- CSV Data Set (если нужны тестовые данные)

Поддерживает откат: `@rollback`.

### `@rollback`

Откатывает последнее изменение, сделанное AI-командами, по очереди:
- `@plan apply` → удаляет добавленные элементы
- `@lint` → восстанавливает предыдущие имена
- `@wrap` → разворачивает Transaction Controller обратно

### `@usage`

Показывает статистику использования токенов:
- OpenAI: поддерживается
- Sber GigaChat: поддерживается
- DeepSeek: не реализовано

### `@code`

Отправляет выделенный (или весь) код JSR223 в AI вместе с инструкцией после `@code`.  
Ответ возвращается в чат. Автоматической подстановки кода в редактор нет.

## Контекстное меню в JSR223-редакторе

Вызывается правой кнопкой мыши в поле скрипта:

- **AI Refactor** — рефакторинг выделенного фрагмента или всего скрипта
- **Wrap in try/catch/finally** — оборачивает код в стандартный JMeter-шаблон обработки ошибок
- **Format code** — базовое форматирование отступов

## Рекомендации по использованию

- Делайте резервную копию тест-плана перед массовыми операциями (`@lint`, `@wrap`, `@plan apply`).
- Проверяйте AI-предложения перед запуском на production-стендах.
- Контролируйте лимиты и стоимость API.
- Не отправляйте чувствительные данные во внешние AI API.

## Сборка из исходников

- Юнит-тесты: `mvn test`
- Сборка: `mvn -DskipTests package`
- Основной артефакт: `target/jmeter-agent-<version>.jar` (не `original-*`)

## Известные проблемы

- Для GigaChat может потребоваться корректная настройка TLS в Java truststore; при ошибке PKIX можно временно включить `giga.ssl.insecure=true` (только для отладки).
- Возможны проблемы с отображением кириллицы в отдельных UI-элементах.
- Возможны проблемы с видимостью элементов интерфейса на тёмных темах.
- Сериализация плана для AI ограничена 300 элементами; на очень больших планах дерево обрезается.
- `@code` не вставляет ответ AI обратно в редактор автоматически — только в чат.

## Поддержка

- Issues: https://github.com/system0shock/GigaMeter/issues
- Репозиторий: https://github.com/system0shock/GigaMeter
