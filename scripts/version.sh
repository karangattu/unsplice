#!/usr/bin/env bash
set -euo pipefail
tag=${1:-}
if [[ ! "$tag" =~ ^v(0|[1-9][0-9]?)\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$ ]]; then
    echo 'Expected a version tag such as v1.2.3 (major <= 99, minor and patch <= 999).' >&2
    exit 1
fi
major=${BASH_REMATCH[1]}; minor=${BASH_REMATCH[2]}; patch=${BASH_REMATCH[3]}
printf 'versionName=%s.%s.%s\nversionCode=%s\n' "$major" "$minor" "$patch" "$((major * 1000000 + minor * 1000 + patch + 1))"
