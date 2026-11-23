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

## API key via GitHub Actions (без публикации секрета)

Если секретный API-ключ был отправлен в сообщение, выпусти новый ключ в Runway Dev и отключи старый. Ключ из чата нельзя копировать в репозиторий, APK или файлы workflow.

1. Открой [Secrets and variables / Actions](https://github.com/sergeyterm/Sergeytermebay2/settings/secrets/actions).
2. В **Repository secrets** нажми **New repository secret**.
3. Имя: `RUNWAYML_API_SECRET`. Значение: **новый**, только что созданный ключ Runway Dev.
4. Сохрани. Secret передаётся в процессы Actions только на время выполнения и не отображается пользователям через GitHub API.
5. Открой `PhotoAvatar — Runway Dev API authorization` в GitHub Actions и повтори проверку (при необходимости перезапусти ранее запущенный job).

Workflow отправляет только `GET https://api.dev.runwayml.com/v1/avatars?limit=1` (чтение списка персонажей). Он выводит только успех или код ошибки, **не** выводит ключ или ответ API. Это не проверка качества видеозвонка и не тест платной генерации.

Runway Dev SDK получает ключ из окружения `RUNWAYML_API_SECRET` на защищённом backend. **Нельзя** передавать постоянный ключ непосредственно в Android, WebView или React frontend. GitHub Actions предназначен для CI и деплоя, но не постоянного обслуживания WebRTC-сессий; отдельный хостинг для API сессий ещё необходимо настроить.
