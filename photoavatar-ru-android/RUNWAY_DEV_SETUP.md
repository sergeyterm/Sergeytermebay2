# Подключение Runway Dev к Codex для PhotoAvatar RU

**Статус:** инструкция подготовлена в GitHub. Установка плагина и OAuth-вход в аккаунт Runway Dev должны быть завершены в самом Codex; эта запись в репозитории сама по себе не устанавливает плагин.

## Вариант A: интерфейс Codex
1. Открой Codex → **Plugins**, найди **Runway Dev** (именно Dev, не обычный Runway) и установи. Доступность в каталоге может различаться.
2. Выполни вход в Runway Developer Portal, когда появится окно авторизации.
3. Создай новую сессию Codex, открой ветку `photoavatar-ru-android`, а рабочей папкой выбери `photoavatar-ru-android/`.

## Вариант B: Codex CLI
Запусти в терминале среды, где установлен **твой** Codex:

```sh
codex plugin marketplace add runwayml/skills
codex plugin add runway-api@runway-dev
codex mcp login runway-dev
```

Последняя команда открывает браузер для OAuth. Затем **начни новую сессию Codex**. Не устанавливай плагин параллельно через каталог и CLI, если он уже установлен.

## Проверка соединения
Попроси Codex:

> Проверь подключение Runway Dev MCP. Назови мой активный проект Runway Dev и доступный баланс. Покажи, какие возможности Characters доступны в этом проекте, без платных вызовов. Прочитай инструкцию AGENTS.md и проведи аудит текущего PhotoAvatar RU на предмет миграции с D-ID на Runway Characters + необязательный ElevenLabs Agents.

Для API-вызовов создаваемого приложения потребуется **отдельный** `RUNWAYML_API_SECRET` в серверном окружении (не в чате, не в Android APK и не в репозитории). OAuth-подключение плагина не заменяет ключ приложения.

## Важные ограничения
- Плагин Runway (генерация медиа внутри ChatGPT) и **Runway Dev** (разработка и доступ к Dev API) — разные инструменты.
- Девять ракурсов в одном файле не превращаются в обученную 3D-модель. Для Characters требуется соответствующий требованиям API фронтальный портрет; коллаж должен быть обрезан.
- Клонирование голоса и русский язык необходимо тестировать с реальными правами доступа и тарифом.
- Любые платные вызовы/создание Characters требуют отдельного согласования с пользователем.

Ссылки: [официальная установка](https://docs.dev.runwayml.com/guides/plugin/), [интеграция с Codex](https://dev.runwayml.com/learn/build-with-codex), [Characters](https://docs.dev.runwayml.com/characters/), [ElevenLabs Agents](https://docs.dev.runwayml.com/characters/elevenlabs/).
