#!/usr/bin/env bash
set -euo pipefail
[[ "$(scripts/version.sh v1.2.3)" == $'versionName=1.2.3\nversionCode=1002004' ]]
[[ "$(scripts/version.sh v0.0.0)" == $'versionName=0.0.0\nversionCode=1' ]]
for tag in v1.02.3 v1.2 v1.2.1000 v100.0.0 v1.2.3-beta 'v1.2.3;bad'; do
    if scripts/version.sh "$tag" >/dev/null 2>&1; then
        printf 'Invalid tag accepted: %s\n' "$tag" >&2
        exit 1
    fi
done
