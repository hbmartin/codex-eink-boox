#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

./gradlew test lintDebug assembleDebug assembleRelease
python3 research/scripts/inspect_bundle.py --help >/dev/null
python3 -m unittest discover -s research/tests -v

if git ls-files | grep -E '\.(apk|xapk|apkm|har|jks|keystore)$' >/dev/null; then
  printf 'Proprietary or secret-bearing binary artifacts are tracked by Git.\n' >&2
  exit 1
fi
