# Sony Vegas Video — Android

Личный неофициальный видеоредактор для Android с классической компоновкой Vegas.
Это самостоятельное приложение, не продукт Sony/VEGAS и не порт Windows-версии.

Версия 2.4 использует AndroidX Media3 1.8.0 для общего предпросмотра и экспорта:
обрезка, последовательность клипов, эффекты, текст, фоновые генераторы и дополнительные
слои. Классический интерфейс и имя пакета `com.personal.sonyvegasvideo` сохранены.
Описание операций и практических ограничений — [README_RU.md](README_RU.md).

## Сборка

Нужны JDK 17, Gradle 8.9, Android SDK Platform 35 / Build Tools 35.0.0,
Bash и Python 3. Для тестовых роликов нужен FFmpeg 6 или новее.
При первой Gradle-сборке требуется доступ к Google Maven и Maven Central.

```sh
export ANDROID_HOME=/path/to/android-sdk
bash tools/run-logic-tests.sh
bash tools/build-sdk.sh
bash tools/make-test-fixture.sh
bash tools/build-device-tests.sh
```

`VEGAS_GRADLE_BIN` позволяет указать путь к установленному Gradle.
Сборка вручную через javac/D8 больше не используется: Media3 содержит зависимости
и Android-ресурсы, которые разрешает Android Gradle Plugin.

Выходные файлы:

- `build/sdk/Sony_Vegas_Video_v2.4.apk`
- `build/device-tests/VegasVideo_device_tests.apk`

## GitHub Actions

Workflow `Build and test Vegas Video` запускается при push в main/master и вручную.
Он устанавливает Gradle 8.9 и SDK 35, выполняет JVM-проверки, генерирует тестовые
видео и собирает оба APK. Артефакт — `Vegas_Video_2.4_CI_APKs`.

Матрица Android-тестов: API 30 (AOSP) и API 35 (Google APIs), x86_64 с KVM.
На каждом эмуляторе выполняются четыре запуска:

1. Проверки исходного экспорта/склейки без перекодирования.
2. Проверки обработанного Media3-видео: эффекты, длительность, аудио и очистка отмены.
3. Функциональные UI-проверки при 1768×2208 / density 320.
4. Те же UI-проверки при 1080×2400 / density 420.

Результаты лежат в артефактах `Android_30_test_results` и
`Android_35_test_results`: отдельные журналы, сводка, logcat и снимки интерфейса.
Успех отдельного запуска требует одновременно `PASS:` и
`INSTRUMENTATION_CODE: -1`; сборка APK сама по себе не означает успешное тестирование.
Текущий фактический статус нужно смотреть на вкладке Actions. Эмуляторы не заменяют
проверку на физическом Samsung Galaxy Z Fold7.

## Подпись

CI использует временный debug-ключ. Он не хранит личный ключ прежних APK и не сможет
обновить установленное приложение с другой подписью. Для совместимого обновления
готовый APK подписывается прежним ключом отдельно, вне публичного репозитория.
Если локально доступен `signing/vegas-debug.keystore`, debug-сборка использует его;
каталог signing исключён из git. Ключи, пользовательские видео и готовые APK в
исходники не добавляются.
