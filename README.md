# GigaMeter

GigaMeter — это плагин для Apache JMeter с AI-ассистентом внутри интерфейса. Он помогает создавать, улучшать и поддерживать test plan: объясняет элементы JMeter, предлагает структуру, подсказывает оптимизации и помогает с JSR223-скриптами.

## Возможности

- Чат с AI прямо в JMeter
- Поддержка провайдеров: Anthropic Claude, OpenAI, Sber GigaChat
- Подсказки по элементам JMeter на основе контекста
- Поддержка специальных команд в чате:
  - `@this`
  - `@optimize`
  - `@lint`
  - `@wrap`
  - `@usage`
  - `@code`
- Контекстное меню в JSR223-редакторе: рефакторинг, форматирование, вставка функций
- Гибкая настройка поведения через `jmeter.properties` / `user.properties`

## Установка

### Через Plugins Manager (рекомендуется)

1. Установите JMeter Plugins Manager: https://jmeter-plugins.org/
2. Перезапустите JMeter.
3. Откройте Plugins Manager.
4. Найдите плагин по запросу `gigameter`.
5. Нажмите `Apply Changes and Restart JMeter`.

### Ручная установка

1. Скачайте последний релиз с GitHub: https://github.com/system0shock/GigaMeter/releases
2. Поместите JAR в `JMETER_HOME/lib/ext`.
3. Скопируйте настройки из `jmeter-ai-sample.properties` в `JMETER_HOME/bin/user.properties` или `JMETER_HOME/bin/jmeter.properties`.
4. Заполните API-настройки выбранного провайдера.
5. Перезапустите JMeter.

## Конфигурация

Основные параметры:

- Общие:
  - `jmeter.ai.refactoring.enabled`
  - `jmeter.ai.service.type` (`openai`, `anthropic`, `giga`)

- Anthropic Claude:
  - `anthropic.api.key`
  - `claude.default.model`
  - `claude.temperature`
  - `claude.max.tokens`
  - `claude.max.history.size`
  - `claude.system.prompt`
  - `anthropic.log.level`

- OpenAI:
  - `openai.api.key`
  - `openai.default.model`
  - `openai.temperature`
  - `openai.max.tokens`
  - `openai.max.history.size`
  - `openai.system.prompt`
  - `openai.log.level`

- Sber GigaChat:
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

## Команды чата

### `@this`
Показывает детальную информацию о текущем выбранном элементе test plan.

### `@optimize`
Генерирует рекомендации по оптимизации выбранного элемента.

### `@lint`
Переименовывает элементы test plan в более понятный и единообразный стиль.

### `@wrap`
Группирует HTTP sampler'ы в Transaction Controller'ы для лучшей структуры и отчётности.

### `@usage`
Показывает статистику использования токенов (для поддерживаемых провайдеров).

### `@code`
Команда зарезервирована для работы с кодом; в текущем UI рекомендуется использовать контекстное меню JSR223.

## Рекомендации по использованию

- Делайте резервную копию test plan перед массовыми изменениями (`@lint`, `@wrap`).
- Проверяйте AI-рекомендации перед запуском на production-стендах.
- Следите за лимитами и стоимостью API.
- Не передавайте в AI чувствительные данные.

## Известные проблемы

- Для GigaChat может потребоваться корректная настройка TLS в Java truststore.
- Если видите ошибку PKIX, временно можно включить `giga.ssl.insecure=true`. Использовать только для отладки.
- Проблемы с кодировкой на кириллице в некоторых элементах интерфейса.
- Проблемы с видимостью элементов интерфейса на темных темах.




## Поддержка

- Issues: https://github.com/system0shock/GigaMeter/issues
- Репозиторий: https://github.com/system0shock/GigaMeter


