#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ ! -x "$ROOT/.venv/bin/python" ]]; then
    python3 -m venv "$ROOT/.venv"
fi
"$ROOT/.venv/bin/python" -m pip install --disable-pip-version-check -q -r "$ROOT/requirements.txt"
"$ROOT/.venv/bin/python" "$ROOT/pipeline.py" "$@"
"$ROOT/.venv/bin/python" -m unittest discover -s "$ROOT/tests" -v
