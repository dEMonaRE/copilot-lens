# Feature Reference

Detailed feature documentation for copilot-lens.

## 1. Token Counting (BPE)

- Uses **jtokkit** (Java port of OpenAI's tiktoken)
- GPT-4 encoding (`cl100k_base`)
- Counts on the full HTTP request body the IDE sends to Copilot
- Accuracy: within ~5% of server-side count

API: `TokenCounter.count(String text)` → int

## 2. IDE Auto-Detection

Default glob patterns (configurable in `./config.properties`):

```properties
log.vscode=${APPDATA}/Code/logs/**/output_logging*.log
log.idea=${LOCALAPPDATA}/JetBrains/**/log/idea.log
```

The `**` enables recursive directory walking. Glob is matched on the **filename**, not full path, so any matching file under the base path is considered.

**Resolution order** (auto mode):
1. Find the most recently modified VSCode log
2. Find the most recently modified IntelliJ log
3. Use whichever was modified more recently
4. Auto-pick the matching parser (JSON for VSCode, plain text for IntelliJ)

## 3. Incremental State Tracking

State files in `~/.copilot-lens/`:

| File | Purpose |
|------|---------|
| `state.json` | Per-file byte offset, modification time, request count |
| `cache.json` | Full list of previously parsed requests |

**Behavior:**

1. **First scan** of a log file: full parse, all requests cached
2. **Subsequent scans**: only bytes after `state.json`'s recorded offset
3. **File rotated** (size decreased): full re-parse from offset 0
4. **Cache disabled** (`cache.enabled=false` in config): always full parse

**Performance:**

| Log size | First run | Subsequent (no new data) | Subsequent (1KB delta) |
|----------|-----------|--------------------------|------------------------|
| 1 MB | ~1 s | <50 ms | ~80 ms |
| 100 MB | ~5-10 s | <50 ms | ~100 ms |
| 1 GB | ~50 s | <50 ms | ~150 ms |

## 4. Output Formats

### Terminal (default, console only)

- ANSI colors, auto-disabled with `--no-ansi` or piped output
- ASCII-safe box drawing (`+`, `-`, `|` instead of Unicode)
- Bar charts using `#` characters
- Locale-independent number formatting (en-US)

### HTML (`copilot-lens report`)

- Self-contained (no external CSS/JS)
- Dark mode via `prefers-color-scheme`
- Mobile-responsive grid
- Open in any browser

### JSON (`copilot-lens export json`)

- Pipe-friendly raw export
- Fields: `timestamp`, `ide`, `endpoint`, `inputTokens`, `outputTokens`, `messageCount`, `summary`, `workspaceHint`
- Compatible with `jq` and similar tools

> **Note:** default `copilot-lens` produces console output only. HTML and JSON files are only created when you explicitly use `report` or `export json`. This avoids Windows auto-opening generated files in your current directory.

## 5. Watch Mode (Live)

- Polls log file every 500 ms
- Detects file rotation (size decrease)
- Re-renders dashboard with new aggregated stats
- `Ctrl+C` to exit

## 6. Shell Wrappers

| Wrapper | Shell | Invocation |
|---------|-------|------------|
| `copilot-lens.sh` | Bash (Git Bash, WSL, MSYS) | `./copilot-lens.sh <args>` |
| `copilot-lens.ps1` | PowerShell 5.1+ | `.\copilot-lens.ps1 <args>` |
| `copilot-lens` (after `./install.sh`) | Bash | `copilot-lens <args>` |

The PowerShell wrapper (`copilot-lens.ps1`) is **PowerShell 5.1 compatible** — uses no PowerShell 7+ syntax. It calls `bash.exe` directly so output flows to the current terminal (no new window).

## 7. Configuration

### Project-Level (`./config.properties`)

Created by `copilot-lens init`. Idempotent.

```properties
log.vscode=...    # VSCode log glob
log.idea=...      # IntelliJ log glob
state.dir=...     # Where to keep state/cache
state.enabled=true
cache.enabled=true
```

### User-Level (`~/.copilot-lens/config.properties`)

Fallback when project config doesn't define a key.

### Environment Variables (highest priority)

```
COPILOT_LENS_LOG_VSCODE
COPILOT_LENS_LOG_IDEA
COPILOT_LENS_STATE_DIR
COPILOT_LENS_STATE_ENABLED
COPILOT_LENS_CACHE_ENABLED
```

## 8. Discover Mode Heuristics

`copilot-lens discover` ranks findings by severity:

1. **Single largest request** — token count above threshold
2. **Most-frequent context file** — workspace file appearing in N+ requests
3. **Low signal/noise ratio** — `inputTokens > 2000 && outputTokens < 100`
4. **High average prompt size** — `avg > 3000 tokens/request`
5. **Peak hour concentration** — daily usage distribution

## 9. RTK Command Parity

| RTK | copilot-lens | Description |
|-----|--------------|-------------|
| `rtk gain` | `copilot-lens gain` | Single-shot report |
| `rtk gain --history` | `copilot-lens gain --history` | Historical trend |
| `rtk discover` | `copilot-lens discover` | Optimization opportunities |
| (manual) | `copilot-lens watch` | Live monitoring |
| `rtk proxy` | `copilot-lens export json` | Pipe-friendly raw data |
| (none) | `copilot-lens report` | HTML-only output |

## 10. Limitations

- **No HTTPS proxy mode**: cannot intercept actual API calls (would need self-signed CA). Works with IDE's own logs only.
- **Token count is approximate**: server-side BPE may differ slightly from jtokkit's count.
- **Single-file parsing**: doesn't merge multiple log files into one timeline.
- **No time zone awareness**: uses local system timezone for all timestamps.
- **JSON parser is hand-rolled regex**: only works for our own cache file format.
