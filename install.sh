#!/usr/bin/env bash
# install.sh - copies copilot-lens wrapper to PATH so it's runnable from anywhere.
# Avoids Windows file-association prompts by giving the source file a .sh extension.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_SRC="$PROJECT_ROOT/copilot-lens.sh"
WRAPPER_DST_NAME="copilot-lens"

# Target: ~/.local/bin (Git Bash XDG-friendly)
INSTALL_DIR="$HOME/.local/bin"
mkdir -p "$INSTALL_DIR"
WRAPPER_DST="$INSTALL_DIR/$WRAPPER_DST_NAME"

if [[ ! -f "$WRAPPER_SRC" ]]; then
    echo "ERROR: Wrapper source not found: $WRAPPER_SRC" >&2
    exit 1
fi

# Remove any existing symlink/file at destination
if [[ -L "$WRAPPER_DST" ]] || [[ -f "$WRAPPER_DST" ]]; then
    rm -f "$WRAPPER_DST"
fi

# Copy wrapper. On Windows, ln -sf materializes as a copy anyway,
# so just copy explicitly. Destination has no extension (command-style),
# source has .sh (Windows file-association friendly).
cp "$WRAPPER_SRC" "$WRAPPER_DST"
chmod +x "$WRAPPER_SRC" "$WRAPPER_DST"
echo "OK Installed: $WRAPPER_DST (from $WRAPPER_SRC)"

# Check PATH
case ":$PATH:" in
    *":$INSTALL_DIR:"*) echo "OK $INSTALL_DIR is already in PATH" ;;
    *)
        SHELL_RC=""
        if [[ -f "$HOME/.bashrc" ]]; then
            SHELL_RC="$HOME/.bashrc"
        elif [[ -f "$HOME/.zshrc" ]]; then
            SHELL_RC="$HOME/.zshrc"
        fi

        if [[ -n "$SHELL_RC" ]]; then
            echo "" >> "$SHELL_RC"
            echo "# copilot-lens PATH" >> "$SHELL_RC"
            echo "export PATH=\"\$HOME/.local/bin:\$PATH\"" >> "$SHELL_RC"
            echo "OK PATH updated in $SHELL_RC"
            echo "  Activate with: source $SHELL_RC"
        else
            echo "WARN ~/.bashrc/.zshrc not found."
            echo "  Add manually: export PATH=\"\$HOME/.local/bin:\$PATH\""
        fi
        ;;
esac

echo ""
echo "Install complete. Test with:"
echo "  copilot-lens --help"
