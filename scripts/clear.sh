#!/usr/bin/env bash
#
# Uninstall Jiudge from a connected Android device and delete every directory it generated, putting
# the device back to a clean "first run" state. With one device it acts straight away; with several
# it lists them numbered from 1 and asks which to use (same selection as install.sh).
#
# Removes:
#   - the app package io.github.nexgus.jiudge (and its private / Android/data storage)
#   - the route folder Documents/Jiudge (saved route plans; this survives an uninstall on its own)
#
# Usage:
#   ./scripts/clear.sh         # clear the selected device
#   ./scripts/clear.sh --help  # show this help
#
set -euo pipefail

package="io.github.nexgus.jiudge"
route_dir="/storage/emulated/0/Documents/Jiudge"
app_data_dir="/storage/emulated/0/Android/data/$package"

usage() {
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
    case "$arg" in
        -h|--help) usage; exit 0 ;;
        *)
            echo "Unknown argument: $arg" >&2
            usage >&2
            exit 1
            ;;
    esac
done

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

echo "Clearing Jiudge from $target ..."

# Uninstall the app; this also drops its Android/data storage. Ignore failure when it isn't installed.
if adb -s "$target" uninstall "$package" >/dev/null 2>&1; then
    echo "  uninstalled $package"
else
    echo "  $package not installed (skipping uninstall)"
fi

# The route folder lives in public storage and survives an uninstall, so remove it explicitly.
adb -s "$target" shell rm -rf "$route_dir"
echo "  removed $route_dir"

# Belt-and-suspenders: drop the app's external data dir in case the uninstall left it behind.
adb -s "$target" shell rm -rf "$app_data_dir"
echo "  removed $app_data_dir"

echo "Done. The device is back to a clean first-run state."
