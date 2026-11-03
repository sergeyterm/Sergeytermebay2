#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$PROJECT/build/unit"
mkdir -p "$OUT"
BASE="$PROJECT/app/src/main/java/com/personal/sonyvegasvideo"
SOURCES=("$BASE/ClipMath.java" "$BASE/EditHistory.java" "$BASE/Sequence.java" "$BASE/Mp4Duration.java")
TESTS=(ClipMathTest WorkspaceLogicTest SequenceTest Mp4DurationTest)
if [[ -f "$BASE/EffectSpec.java" ]]; then SOURCES+=("$BASE/EffectSpec.java"); fi
if [[ -f "$PROJECT/tests/EffectSpecTest.java" ]]; then TESTS+=(EffectSpecTest); fi
for TEST in "${TESTS[@]}"; do SOURCES+=("$PROJECT/tests/$TEST.java"); done
java com.sun.tools.javac.Main --release 8 -d "$OUT" "${SOURCES[@]}"
for TEST in "${TESTS[@]}"; do java -cp "$OUT" "com.personal.sonyvegasvideo.$TEST"; done
