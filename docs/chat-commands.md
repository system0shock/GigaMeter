# Команды Чата GigaMeter

Этот файл описывает актуальные команды чата плагина и содержит практические примеры запросов.

## Список Команд

| Команда | Что делает | Примечание |
|---|---|---|
| `@this` | Показывает подробную информацию о выбранном элементе JMeter. | Требуется выбрать элемент в дереве. |
| `@optimize` | Запрашивает у AI рекомендации по оптимизации выбранного элемента. | Рекомендации не применяются автоматически. |
| `@lint [инструкция]` | Переименовывает элементы плана для улучшения читаемости и единообразия. | Если инструкция не задана, используется `rename`. |
| `@wrap` | Группирует sampler-ы в Transaction Controller по схожести. | Работает для выбранного Thread Group. |
| `@usage` | Показывает статистику использования AI для активного провайдера/модели. | Поддержаны OpenAI и GigaChat; для DeepSeek — заглушка. |
| `@plan <сценарий>` | Генерирует структурированный draft backend-плана и preview. | Только preview, без изменений в плане. |
| `@plan apply` | Применяет последний draft из `@plan` в дерево JMeter. | Создает TG/sampler/assertion/extractor/timer/CSV по данным draft. |
| `@rollback` | Откатывает последнюю операцию (`@plan apply`, `@lint`, `@wrap`). | Использует контекст последней команды, затем fallback-проверки. |
| `@code ...` | Сейчас отключена в чате. | Используйте контекстное меню в редакторе JSR223. |

## Подробное Поведение И Примеры

### `@this`
Возвращает структурированное описание выбранного узла:
- тип/имя элемента
- ключевые свойства
- родитель/дети в дереве
- рекомендованные следующие элементы

Примеры:
- `@this`

### `@optimize`
Анализирует выбранный элемент и запрашивает у AI 3-5 практических рекомендаций.

Примеры:
- `@optimize`

### `@lint [инструкция]`
Запускает поток переименования элементов плана.

Примеры:
- `@lint`
- `@lint rename HTTP samplers in business language`
- `@lint normalize names by endpoint and method`

### `@wrap`
Ищет sampler-ы в выбранном Thread Group, группирует похожие и оборачивает их в Transaction Controller.

Примеры:
- `@wrap`

### `@usage`
Показывает сводку использования для текущего провайдера/модели:
- OpenAI: сводка по токенам
- GigaChat: сводка использования
- DeepSeek: `not implemented yet`

Примеры:
- `@usage`

### `@plan <сценарий>`
Генерирует JSON-draft и выводит preview (Thread Group, defaults, steps, next action).

Текущая поддерживаемая схема:
- `thread_group`: `name`, `users`, `ramp_up_seconds`, `duration_seconds`
- `defaults`: `base_url`, `think_time_ms`, `csv`
- `steps[]`: HTTP-поля или JSR223-поля
- опционально: `headers`, `query`, `body`, `assert.status_code`, `extract`, `think_time_ms`
- опциональный JSR223-сценарий:
  - `sampler_type = "jsr223"`
  - `script_language`, `script`
  - `pre_processors[]` / `post_processors[]` с `type = "jsr223"`

Примеры:
- `@plan Login, get token, list products, add to cart, checkout. 100 users, ramp-up 60s, duration 10m.`
- `@plan Build API flow with CSV users.csv, default think time 800ms, assert 200 for each step.`
- `@plan Generate token in JSR223 sampler, then call /orders with extracted vars.`

### `@plan apply`
Применяет последний сгенерированный draft в дерево тест-плана. На текущий момент логика может создавать и настраивать:
- Thread Group
- HTTP Defaults
- CSV Data Set (если есть `defaults.csv`)
- HTTP Sampler или JSR223 Sampler (по `steps[].sampler_type`)
- Header Manager с фактическими значениями заголовков
- Response Assertion
- JSON Path Extractor
- Constant Timer (step-level или default think time)
- JSR223 pre/post processors

Примеры:
- `@plan apply`

### `@rollback`
Приоритет отката:
1. Если последняя команда была `@plan apply` -> удаляет последний AI-сгенерированный Thread Group.
2. Если последняя команда была `@lint` -> откат переименования.
3. Если последняя команда была `@wrap` -> откат оборачивания.
4. Если контекста нет -> последовательно пробует откат plan/lint/wrap.

Примеры:
- `@rollback`

### `@code`
Путь через чат специально отключен. Используйте контекстное меню в редакторе JSR223:
- правый клик по области скрипта JSR223
- запуск действий refactor/format из меню плагина

Примеры:
- `@code improve this script` -> в чате вернется сообщение, что команда отключена.

## Ввод Без Команд

Если сообщение не начинается с `@`, плагин сначала пытается распознать запрос на действия с элементами (add/create), затем при необходимости передает сообщение в обычный AI-чат.

Примеры:
- `add http request called Get Catalog`
- `create thread group named Checkout Users`
