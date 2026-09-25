#!/usr/bin/env bash
set -euo pipefail
VQ_TOOLS_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
exec python3 "$VQ_TOOLS_DIR/pixel5_evidence.py" verify "$@"
