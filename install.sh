#!/usr/bin/env bash
# install.sh - thin wrapper that delegates to the Java install subcommand.
# Kept for backward compatibility; new code should use ./copilot-lens.sh install
# or .\copilot-lens.ps1 install instead so output stays in the current terminal.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/copilot-lens.sh" install "$@"
