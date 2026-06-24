#!/usr/bin/env bash
#
# Install a built Jiudge APK onto a connected Android device via adb. With one device it installs
# straight away; with several it lists them numbered from 1 and asks which to use. Build first with
# ./build.sh (this script does not build).
#
# Usage:
#   ./install.sh             # install the debug APK (default)
#   ./install.sh --release   # install the release APK (-r for short)
#   ./install.sh --help      # show this help
#
set -euo pipefail

cd "$(dirname "$0")"

variant="debug"

usage() {
    sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
    case "$arg" in
        -r|--release) variant="release" ;;
        -h|--help) usage; exit 0 ;;
        *)
            echo "Unknown argument: $arg" >&2
            usage >&2
            exit 1
            ;;
    esac
done

apk="app/build/outputs/apk/$variant/app-$variant.apk"
if [ ! -f "$apk" ]; then
    echo "No $variant APK found at: $apk" >&2
    echo "Build it first with: ./build.sh$([ "$variant" = release ] && echo ' --release')" >&2
    exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found on PATH. Install the Android platform-tools first." >&2
    exit 1
fi

# Collect serials of devices in the "device" state (skip header, "offline", "unauthorized", etc.).
devices=()
while read -r serial state; do
    [ "$state" = "device" ] && devices+=("$serial")
done < <(adb devices | tail -n +2)

count=${#devices[@]}
if [ "$count" -eq 0 ]; then
    echo "No connected device found. Plug in a device with USB debugging enabled." >&2
    exit 1
fi

if [ "$count" -eq 1 ]; then
    target="${devices[0]}"
else
    echo "Multiple devices connected:"
    for i in "${!devices[@]}"; do
        printf "  %d) %s\n" "$((i + 1))" "${devices[$i]}"
    done
    printf "Select a device [1-%d]: " "$count"
    read -r choice
    if ! [[ "$choice" =~ ^[0-9]+$ ]] || [ "$choice" -lt 1 ] || [ "$choice" -gt "$count" ]; then
        echo "Invalid selection: $choice" >&2
        exit 1
    fi
    target="${devices[$((choice - 1))]}"
fi

echo "Installing $variant APK to $target ..."
adb -s "$target" install -r "$apk"
echo "Done."
