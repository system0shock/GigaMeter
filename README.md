# GigaMeter

GigaMeter - плагин для Apache JMeter с AI-ассистентом в интерфейсе.  
Он помогает анализировать и улучшать test plan, работать с JSR223-скриптами и ускорять рутинные операции.

## Что умеет плагин

- Чат-панель AI внутри JMeter
- Выбор провайдера и модели в UI (OpenAI, Sber GigaChat, DeepSeek)
- Подсказки и работа с командами чата (есть автодополнение команд)
- Операции с элементами test plan:
  - обзор текущего элемента
  - AI-оптимизация выбранного элемента
  - массовое переименование элементов
  - группировка HTTP Sampler в Transaction Controller
  - генерация backend-плана по сценарию с предпросмотром и применением
- Контекстное меню в JSR223-редакторе:
  - AI-рефакторинг выделенного кода
  - рефакторинг в try/catch/finally
  - базовое форматирование кода

## Установка

### Через Plugins Manager

Интеграция в каталог Plugins Manager пока в работе.  
До публикации используйте ручную установку (stable или nightly).

### Ручная установка

1. Выберите канал релиза:
   - stable: https://github.com/system0shock/GigaMeter/releases/latest
   - nightly: https://github.com/system0shock/GigaMeter/releases/tag/nightly
2. Скачайте JAR и поместите его в `JMETER_HOME/lib/ext`.
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

Показывает информацию о текущем выбранном элементе test plan.

### `@optimize`

Формирует рекомендации по оптимизации выбранного элемента.

### `@lint`

Переименовывает элементы test plan в более единый и читаемый стиль.

### `@wrap`

Группирует HTTP Sampler в Transaction Controller в выбранном Thread Group.

### `@plan`

Работа с черновиком backend test plan:

- `@plan <сценарий>` - генерирует структурированный draft и показывает preview без изменений в дереве
- `@plan apply` - применяет последний draft в текущий test plan
- `@plan analyze` - анализирует текущий план и даёт интерпретацию

### `@rollback`

Откатывает последнее изменение, сделанное AI-командами (по очереди: `@plan apply`, `@lint`, `@wrap`).

### `@usage`

Показывает usage-статистику:

- OpenAI: поддерживается
- Sber GigaChat: поддерживается
- DeepSeek: пока не реализовано

### `@code`

Отправляет выделенный (или текущий) код JSR223 в AI по инструкции после `@code`.  
Ответ возвращается в чат. Автоматической подстановки кода в редактор нет.

## Рекомендации по использованию

- Делайте резервную копию test plan перед массовыми операциями (`@lint`, `@wrap`, `@plan apply`).
- Проверяйте AI-предложения перед запуском на production-стендах.
- Контролируйте лимиты и стоимость API.
- Не отправляйте чувствительные данные в внешние AI API.

## Сборка, тесты и совместимость

- Юнит-тесты: `mvn test`
- Сборка: `mvn -DskipTests package`
- Основной артефакт: `target/jmeter-agent-<version>.jar` (не `original-*`)

### Smoke-тесты с реальным JMeter

- PowerShell: `$env:JMETER_VERSION='5.6.2'; ./ci/smoke/run-smoke.ps1`
- Linux/macOS: `JMETER_VERSION=5.6.2 bash ci/smoke/run-smoke.sh`
- Для обратной совместимости используйте также `JMETER_VERSION=5.5`

Smoke-проверка падает, если в `jmeter.log` найдены критичные ошибки (`StackOverflowError`, `IncompatibleClassChangeError`, `GroovyRuntimeException`, `Conflicting module versions`, а также общие `ERROR/Exception`).

### Integration-тесты (без внешнего AI API)

- PowerShell: `$env:JMETER_VERSION='5.6.2'; ./ci/integration/run-integration.ps1`
- Linux/macOS: `JMETER_VERSION=5.6.2 bash ci/integration/run-integration.sh`

Интеграционный сценарий `ci/integration/integration-commands.jmx` использует `Stub AiService`, поэтому:

- не нужны API-ключи
- нет сетевых вызовов к провайдерам
- проверяется реальная загрузка плагина и выполнение ключевых команд в non-GUI режиме

### CI и nightly

В GitHub Actions запускаются:

- unit tests
- smoke (матрица JMeter 5.5 и 5.6.2)
- integration (JMeter 5.6.2)
- nightly prerelease по тегу `nightly` (ежедневно, 01:00 UTC)

Nightly release:

- ссылка: https://github.com/system0shock/GigaMeter/releases/tag/nightly
- ручной запуск: Actions -> `Nightly Release` -> `Run workflow`
- публикуются:
  - `jmeter-agent-nightly-YYYYMMDD-<sha7>.jar`
  - `jmeter-ai-sample.properties`
  - `SHA256SUMS.txt`
- используется один и тот же prerelease-тег `nightly` (артефакты обновляются)

## Известные проблемы

- Для GigaChat может потребоваться корректная настройка TLS в Java truststore.
- При ошибке PKIX можно временно включить `giga.ssl.insecure=true` (только для отладки).
- Возможны проблемы с отображением кириллицы в отдельных UI-элементах.
- Возможны проблемы с видимостью элементов интерфейса на тёмных темах.

## Поддержка

- Issues: https://github.com/system0shock/GigaMeter/issues
- Репозиторий: https://github.com/system0shock/GigaMeter
