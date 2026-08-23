#!/usr/bin/env bash
# copilot-lens - RTK-style wrapper.
# Runs compiled Main class with correct classpath.

set -euo pipefail

# Find project root by searching for lib/jtokkit-*.jar.
# Works whether invoked from project dir, from PATH, or via symlink.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT=""

# Try: alongside the script
if [[ -d "$SCRIPT_DIR/lib" ]] && ls "$SCRIPT_DIR"/lib/jtokkit-*.jar >/dev/null 2>&1; then
    PROJECT_ROOT="$SCRIPT_DIR"
fi

# Try: parent of script
if [[ -z "$PROJECT_ROOT" ]] && [[ -d "$SCRIPT_DIR/../lib" ]]; then
    PARENT="$(cd "$SCRIPT_DIR/.." && pwd)"
    if ls "$PARENT"/lib/jtokkit-*.jar >/dev/null 2>&1; then
        PROJECT_ROOT="$PARENT"
    fi
fi

# Try: COPILOT_LENS_HOME env var (already retired — env vars forbidden in env)
# Try: hardcoded location next to .sh source
if [[ -z "$PROJECT_ROOT" ]]; then
    # Last resort: search upward from cwd
    CANDIDATE="$(pwd)"
    while [[ "$CANDIDATE" != "/" ]]; do
        if ls "$CANDIDATE"/lib/jtokkit-*.jar >/dev/null 2>&1; then
            PROJECT_ROOT="$CANDIDATE"
            break
        fi
        CANDIDATE="$(dirname "$CANDIDATE")"
    done
fi

if [[ -z "$PROJECT_ROOT" ]]; then
    echo "ERROR: Could not locate project root (lib/jtokkit-*.jar not found)." >&2
    echo "Run ./build.sh first, or run this script from the project directory." >&2
    exit 1
fi

LIB_DIR="$PROJECT_ROOT/lib"
OUT_DIR="$PROJECT_ROOT/out"

if [[ ! -d "$OUT_DIR" ]] || [[ ! -f "$OUT_DIR/io/copilotlens/Main.class" ]]; then
    echo "ERROR: Compiled classes not found. Run ./build.sh first." >&2
    exit 1
fi

# Build classpath with explicit jars (avoids glob issues on Windows)
JARS=$(find "$LIB_DIR" -maxdepth 1 -name "*.jar" -type f 2>/dev/null | tr '\n' ';')
if [[ -z "$JARS" ]]; then
    echo "ERROR: No jars found in lib/. Run ./build.sh first." >&2
    exit 1
fi

# Convert POSIX paths to Windows paths for Java on Windows
if command -v cygpath >/dev/null 2>&1; then
    OUT_CP=$(cygpath -w "$OUT_DIR")
    # Convert each jar path
    WIN_JARS=""
    for j in $(echo "$JARS" | tr ';' '\n' | grep -v '^$'); do
        WIN_JARS="${WIN_JARS}$(cygpath -w "$j");"
    done
    CP="${OUT_CP};${WIN_JARS}"
else
    CP="${OUT_DIR};${JARS}"
fi

# Run java (exec replaces bash process — clean exit)
exec java -cp "$CP" io.copilotlens.Main "$@"
