#!/usr/bin/env bash
#
# Bump the app version (versionName/versionCode) in app/build.gradle.kts.
#
# Without any flag, prints the current version and exits without changing files.
#
# Bump flags (optional, mutually exclusive):
#   -M, --major     bump major  (x.y.z -> x+1.0.0)
#   -m, --minor     bump minor  (x.y.z -> x.y+1.0)
#   -p, --patch     bump patch  (x.y.z -> x.y.z+1)
#
# Pre-release flags (optional, mutually exclusive):
#   -a, --alpha     mark as alpha
#   -b, --beta      mark as beta
#   -r, --rc        mark as release candidate
#
# Finalize:
#   -f, --final     strip pre-release suffix (turn x.y.z-stage.N into x.y.z)
#
# Misc:
#   -k, --keep-code leave versionCode unchanged (default: +1 on any change)
#   -h, --help      show this help
#
# Format follows SemVer 2.0.0:  x.y.z-stage.N  (e.g. 0.5.0-rc.1)
#
# Examples:
#   --patch               0.4.0          -> 0.4.1
#   --patch --rc          0.4.0          -> 0.4.1-rc.1
#   --rc   (on alpha)     0.5.0-alpha.3  -> 0.5.0-rc.1     (promote, reset to .1)
#   --rc   (on rc)        0.5.0-rc.1     -> 0.5.0-rc.2     (advance)
#   --rc   (on final)     0.5.0          -> 0.5.1-rc.1     (auto --patch)
#   --minor (on rc)       0.5.0-rc.2     -> 0.6.0          (bump drops pre)
#   --minor --rc (on rc)  0.5.0-rc.2     -> 0.6.0-rc.1
#   --final               0.5.0-rc.2     -> 0.5.0
#
# Rules:
#   - Stages progress alpha < beta < rc; demoting (e.g. rc -> alpha) is rejected.
#   - --final on a version without a pre-release is rejected.
#   - --final cannot combine with any bump or pre-release flag.
#   - versionCode is incremented by 1 on every change unless --keep-code is given.

set -euo pipefail

cd "$(dirname "$0")/.."

gradle_file="app/build.gradle.kts"

bump=""
pre=""
final_flag=false
keep_code=false

usage() {
    sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
}

set_bump() {
    if [ -n "$bump" ]; then
        echo "Only one of -M / -m / -p may be given." >&2
        exit 1
    fi
    bump="$1"
}

set_pre() {
    if [ -n "$pre" ]; then
        echo "Only one of -a / -b / -r may be given." >&2
        exit 1
    fi
    pre="$1"
}

for arg in "$@"; do
    case "$arg" in
        -M|--major) set_bump major ;;
        -m|--minor) set_bump minor ;;
        -p|--patch) set_bump patch ;;
        -a|--alpha) set_pre alpha ;;
        -b|--beta)  set_pre beta ;;
        -r|--rc)    set_pre rc ;;
        -f|--final) final_flag=true ;;
        -k|--keep-code) keep_code=true ;;
        -h|--help) usage; exit 0 ;;
        *)
            echo "Unknown argument: $arg" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [ "$final_flag" = true ] && { [ -n "$bump" ] || [ -n "$pre" ]; }; then
    echo "--final cannot combine with bump or pre-release flags." >&2
    exit 1
fi

cur_name="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$gradle_file" | head -n1)"
cur_code="$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' "$gradle_file" | head -n1)"

if [ -z "$cur_name" ] || [ -z "$cur_code" ]; then
    echo "Failed to parse versionName/versionCode from $gradle_file" >&2
    exit 1
fi

# No flag: pure query.
if [ -z "$bump" ] && [ -z "$pre" ] && [ "$final_flag" = false ]; then
    if [ "$keep_code" = true ]; then
        echo "--keep-code requires a bump, pre-release, or --final flag." >&2
        exit 1
    fi
    echo "versionName: $cur_name"
    echo "versionCode: $cur_code"
    exit 0
fi

# Parse current versionName into core (x.y.z) and optional pre (stage + number).
cur_core=""
cur_stage=""
cur_pre_num=""
if echo "$cur_name" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    cur_core="$cur_name"
elif echo "$cur_name" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[0-9]+$'; then
    cur_core="${cur_name%%-*}"
    cur_pre="${cur_name#*-}"
    cur_stage="${cur_pre%%.*}"
    cur_pre_num="${cur_pre#*.}"
else
    echo "versionName '$cur_name' is not in SemVer x.y.z[-stage.N] form (stage = alpha|beta|rc)." >&2
    exit 1
fi

# Split on '.' via heredoc so the IFS change does not leak.
IFS=. read -r major minor patch <<EOF
$cur_core
EOF

stage_rank() {
    case "$1" in
        alpha) echo 1 ;;
        beta)  echo 2 ;;
        rc)    echo 3 ;;
        *)     echo 0 ;;
    esac
}

if [ "$final_flag" = true ]; then
    if [ -z "$cur_stage" ]; then
        echo "--final on $cur_name: already final (no pre-release to strip)." >&2
        exit 1
    fi
    new_name="$cur_core"
else
    case "$bump" in
        major) major=$((major + 1)); minor=0; patch=0 ;;
        minor) minor=$((minor + 1)); patch=0 ;;
        patch) patch=$((patch + 1)) ;;
    esac

    new_pre_stage=""
    new_pre_num=""

    if [ -n "$pre" ]; then
        if [ -n "$bump" ]; then
            new_pre_stage="$pre"
            new_pre_num=1
        elif [ -z "$cur_stage" ]; then
            # final + lone pre: auto-bump patch and start fresh pre.1.
            patch=$((patch + 1))
            new_pre_stage="$pre"
            new_pre_num=1
        else
            cur_rank="$(stage_rank "$cur_stage")"
            new_rank="$(stage_rank "$pre")"
            if [ "$new_rank" -lt "$cur_rank" ]; then
                echo "Cannot demote from $cur_stage to $pre on $cur_name." >&2
                exit 1
            elif [ "$new_rank" -eq "$cur_rank" ]; then
                new_pre_stage="$pre"
                new_pre_num=$((cur_pre_num + 1))
            else
                new_pre_stage="$pre"
                new_pre_num=1
            fi
        fi
    fi
    # Bump only (no pre): result is clean x.y.z, dropping any pre by design.

    new_core="$major.$minor.$patch"
    if [ -n "$new_pre_stage" ]; then
        new_name="$new_core-$new_pre_stage.$new_pre_num"
    else
        new_name="$new_core"
    fi
fi

if [ "$keep_code" = true ]; then
    new_code="$cur_code"
else
    new_code=$((cur_code + 1))
fi

# macOS sed needs a suffix for -i; pass a tmp suffix and delete the backup to stay portable.
sed -i.bak \
    -e "s|versionName = \"$cur_name\"|versionName = \"$new_name\"|" \
    -e "s|versionCode = $cur_code|versionCode = $new_code|" \
    "$gradle_file"
rm -f "$gradle_file.bak"

echo "$cur_name ($cur_code) -> $new_name ($new_code)"
