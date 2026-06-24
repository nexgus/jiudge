#!/usr/bin/env bash
#
# Build the Jiudge APK. Runs the ktlint style check, then assembles the APK and prints where it
# landed. The build stamps the current git short hash and dirty state into BuildConfig (see
# app/build.gradle.kts), so commit before a release build if you want a clean version label.
#
# Usage:
#   ./build.sh             # assemble a debug APK (default)
#   ./build.sh --release   # assemble a release APK (-r for short)
#   ./build.sh --clean     # clean before building (-c for short)
#   ./build.sh --help      # show this help
#
set -euo pipefail

cd "$(dirname "$0")"

variant="debug"
clean=false

usage() {
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
    case "$arg" in
        -r|--release) variant="release" ;;
        -c|--clean) clean=true ;;
        -h|--help) usage; exit 0 ;;
        *)
            echo "Unknown argument: $arg" >&2
            usage >&2
            exit 1
            ;;
    esac
done

# Gradle assemble task for the variant (avoid bash-4 ${var^}; macOS ships bash 3.2).
case "$variant" in
    debug) task="assembleDebug" ;;
    release) task="assembleRelease" ;;
esac

if [ "$clean" = true ]; then
    ./gradlew clean
fi

./gradlew ktlintCheck "$task"

echo
echo "Build complete ($variant). Artifact:"
find "app/build/outputs/apk/$variant" -name '*.apk' -print
