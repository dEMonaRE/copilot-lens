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

## 6. Persistent Snapshots

`copilot-lens snapshot` writes one small JSON file per day to `~/.copilot-lens/snapshots/YYYY-MM-DD.json`:

```json
{
  "date": "2026-08-24",
  "createdAt": "2026-08-24T18:30:00",
  "ide": "vscode",
  "requestCount": 47,
  "totalInputTokens": 18432,
  "totalOutputTokens": 6891
}
```

**Behavior:**

- One file per day; running the command again on the same day overwrites the file
- Atomic write: tmp file → rename, so partial writes never replace an existing snapshot
- Snapshot is filtered to that day's requests from the incremental cache — each daily snapshot is self-contained and additive across runs
- `ide` is `"vscode"`, `"idea"`, or `"mixed"` depending on which IDEs contributed that day

**Suggested cadence:** run after each coding session, or once at the end of the workday. A simple Windows Task Scheduler or cron entry can automate it.

## 7. Trend Graph

`copilot-lens trend` aggregates stored snapshots into an ASCII bar chart:

- `--period=daily|weekly|monthly` — bucket size (default: daily)
- `--days=N` — how many recent buckets to show (default: 30)

Example weekly view:

```
Weekly Trend (last 8 of 47 snapshots)
----------------------------------------------------------------
  2026-W32  ##############################################    142 req   78,234 tok
  2026-W33  ####################                                61 req   31,118 tok
  2026-W34  ##################################################  152 req   82,991 tok
```

The HTML report (`copilot-lens report`) automatically embeds the daily trend section when one or more snapshots exist; if none exist yet, a hint to run `snapshot` is shown in its place.

## 8. Shell Wrappers

| Wrapper | Shell | Invocation |
|---------|-------|------------|
| `copilot-lens.sh` | Bash (Git Bash, WSL, MSYS) | `./copilot-lens.sh <args>` |
| `copilot-lens.ps1` | PowerShell 5.1+ | `.\copilot-lens.ps1 <args>` |
| `copilot-lens` (after `./copilot-lens.sh install`) | Bash / PowerShell | `copilot-lens <args>` |

The PowerShell wrapper (`copilot-lens.ps1`) is **PowerShell 5.1 compatible** — uses no PowerShell 7+ syntax. It calls `bash.exe` directly so output flows to the current terminal (no new window).

## 7. Configuration

### Project-Level (`./config.properties`)

Created by `copilot-lens init` in the current directory. Idempotent — if the file exists, `init` is a no-op. Commit it so the project's IDE log paths are version-controlled.

### User-Level (`~/.copilot-lens/config.properties`)

Fallback consulted only when no project config exists in the working directory. **Not** created or modified by `init` — if you want to override defaults project-wide, edit `./config.properties` instead.

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

## 9. Discover Mode Heuristics

`copilot-lens discover` ranks findings by severity:

1. **Single largest request** — token count above threshold
2. **Most-frequent context file** — workspace file appearing in N+ requests
3. **Low signal/noise ratio** — `inputTokens > 2000 && outputTokens < 100`
4. **High average prompt size** — `avg > 3000 tokens/request`
5. **Peak hour concentration** — daily usage distribution

## 10. RTK Command Parity

| RTK | copilot-lens | Description |
|-----|--------------|-------------|
| `rtk gain` | `copilot-lens gain` | Single-shot report |
| `rtk gain --history` | `copilot-lens gain --history` | Historical trend |
| `rtk discover` | `copilot-lens discover` | Optimization opportunities |
| (manual) | `copilot-lens watch` | Live monitoring |
| `rtk proxy` | `copilot-lens export json` | Pipe-friendly raw data |
| (none) | `copilot-lens snapshot` | Persist daily totals |
| (none) | `copilot-lens trend` | ASCII chart from snapshots |
| (none) | `copilot-lens report` | HTML-only output with trend section |

## 11. Limitations

- **No HTTPS proxy mode**: cannot intercept actual API calls (would need self-signed CA). Works with IDE's own logs only.
- **Token count is approximate**: server-side BPE may differ slightly from jtokkit's count.
- **Single-file parsing**: doesn't merge multiple log files into one timeline.
- **No time zone awareness**: uses local system timezone for all timestamps.
- **JSON parser is hand-rolled regex**: only works for our own cache file format.

## 12. Known Issues & Architecture

GitHub Copilot's modern architecture (Copilot Chat 0.60+ on VSCode, IntelliJ 2025+ with `backgroundAgent`) routes requests through a **local Node.js Agent process** that talks to GitHub over HTTPS. The IDE never sees the raw HTTPS response — only JSON-RPC traffic between IDE and the local Agent.

```
   IDE (IntelliJ / VSCode)
     |
     | JSON-RPC (visible in plugin log)
     v
   Local Copilot Agent (Node.js process)
     |
     | HTTPS (NOT visible in plugin log)
     v
   GitHub Copilot API
```

This has direct consequences for what `copilot-lens` can extract:

### IntelliJ (`idea.log`)

- **Combined token total only**: The `backgroundAgent/sessionUpdate` events of type `session.usage_info` expose `currentTokens` (total context size at a point in time) and `conversationTokens` (user + assistant messages). They do **not** separate input from output.
- **Per-turn delta** = `currentTokens[n] - currentTokens[n-1]` includes **user prompt + assistant response + tool-call outputs** added during that turn. There is no way to split these without inspecting the Agent's HTTPS traffic.
- **Constant overhead visible**: `systemTokens` and `toolDefinitionsTokens` are reported separately and stay stable across turns.
- **Output token field is always `0`** in snapshots/reports — it cannot be filled from `idea.log`. To approximate output cost, divide the per-turn delta by ~2 if you assume roughly equal input/output (very rough).

### VSCode (`GitHub Copilot Chat.log`)

- **No token counts at all**: `fetchCompletions` lines expose only the request URL (`proxy.individual.githubcopilot.com/v1/engines/<model>/completions`) and latency. `ccreq success` lines expose model name, duration, and provider (e.g. `XtabProvider`, `nes.nextCursorPosition`).
- **No `prompt_tokens` / `completion_tokens`**: Copilot Chat extension does not log them at any level seen so far.
- **Model + provider + latency** are the only per-request metrics available.

### Older HTTP format (legacy)

If you ever see raw `POST /v1/chat/completions` lines plus `usage: prompt_tokens=N, completion_tokens=M` in either log (older Copilot extension versions), copilot-lens extracts those correctly. The "no separate output tokens" limitation only applies to the modern JSON-RPC architecture.

### What would change this

- GitHub Copilot plugin exposing per-message output token counts in `session.usage_info` events (Microsoft roadmap)
- A new verbose log channel in the local Agent process itself (none visible at the time of writing)
