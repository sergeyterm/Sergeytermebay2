#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$PROJECT/build/unit"
mkdir -p "$OUT"
BASE="$PROJECT/app/src/main/java/com/personal/sonyvegasvideo"
java com.sun.tools.javac.Main --release 8 -d "$OUT" "$BASE/ClipMath.java" "$BASE/EditHistory.java" "$BASE/Sequence.java" "$BASE/Mp4Duration.java" "$PROJECT/tests/ClipMathTest.java" "$PROJECT/tests/WorkspaceLogicTest.java" "$PROJECT/tests/SequenceTest.java" "$PROJECT/tests/Mp4DurationTest.java"
for TEST in ClipMathTest WorkspaceLogicTest SequenceTest Mp4DurationTest; do java -cp "$OUT" "com.personal.sonyvegasvideo.$TEST"; done
