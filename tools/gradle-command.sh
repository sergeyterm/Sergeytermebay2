#!/usr/bin/env bash
# Source this file; never downloads or executes an unverified bootstrap script.
VEGAS_GRADLE_COMMAND=()
if [[ -n "${VEGAS_GRADLE_BIN:-}" ]]; then
    VEGAS_GRADLE_COMMAND=("$VEGAS_GRADLE_BIN")
elif [[ -x "$PROJECT/gradlew" ]]; then
    VEGAS_GRADLE_COMMAND=("$PROJECT/gradlew")
elif command -v gradle >/dev/null 2>&1; then
    VEGAS_GRADLE_COMMAND=(gradle)
else
    echo 'Gradle 8.11.1 is required for AGP 8.9.2 and Media3 dependencies. Install Gradle 8.11.1 or open this project in Android Studio. GitHub Actions installs the pinned version automatically.' >&2
    return 1
fi
