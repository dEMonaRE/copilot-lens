# Usage Guide

## Quick Start

```bash
# 1. Enable verbose log in your IDE (do this once)
#    VSCode  : F1 -> Developer: Set Log Level -> GitHub Copilot Chat: Trace
#    IntelliJ: Help -> Diagnostic Tools -> Debug Log Settings -> Add: #com.github.copilot:trace

# 2. Use Copilot for a while (inline suggestions, chat completions)

# 3. Run the report
copilot-lens                      # Git Bash (after ./install.sh)
./copilot-lens.sh                 # Git Bash (from project dir)
.\copilot-lens.ps1                # PowerShell
```

By default, only console output is produced. No files are written to your current directory unless you explicitly use `report` or `export json`.

## Shell Wrappers

| Wrapper | Use it from | Invocation |
|---------|-------------|------------|
| `copilot-lens` | Git Bash / any POSIX shell (after `./install.sh`) | `copilot-lens <args>` |
| `./copilot-lens.sh` | Git Bash (project directory) | `./copilot-lens.sh <args>` |
| `.\copilot-lens.ps1` | PowerShell 5.1+ | `.\copilot-lens.ps1 <args>` |

All three accept the same arguments.

## Commands

### `copilot-lens` (default)

Single-shot report. Console output only.

```bash
copilot-lens                                # auto-detect IDE
copilot-lens --ide=idea                     # force IntelliJ
copilot-lens --ide=vscode                   # force VSCode
copilot-lens --log=/path/to/custom.log      # manual log file
```

### `copilot-lens gain`

Alias for default behavior; matches RTK's `gain` naming.

### `copilot-lens gain --history`

Daily trend of requests over time. Useful for weekly/monthly review.

### `copilot-lens discover`

Finds optimization opportunities:
- Largest single request (and what to do about it)
- Most-frequent-context-file (close it in IDE)
- Low signal/noise requests (big prompt, small response)
- Your peak usage hours

### `copilot-lens watch`

Live monitoring. Polls log file every 500 ms. Refreshes dashboard as new requests come in. `Ctrl+C` to exit.

### `copilot-lens export json`

Writes raw data to `copilot-lens-export.json` in the current directory. Combine with `jq`:

```bash
copilot-lens export json
cat copilot-lens-export.json | jq '.summary.requestCount'
cat copilot-lens-export.json | jq '.requests[] | select(.inputTokens > 1000)'
```

### `copilot-lens report`

Writes HTML report to `copilot-lens-report.html`. Open in any browser. Dark mode auto-detected.

### `copilot-lens init`

Writes default `config.properties` in the project root. Idempotent — does nothing if file exists.

## Options

| Flag | Default | Description |
|------|---------|-------------|
| `--ide=vscode\|idea\|auto` | `auto` | Which IDE log to use (auto = most recent) |
| `--log=<path>` | (auto) | Manual log file path |
| `--no-ansi` | off | Disable colored output |
| `--help`, `-h` | off | Show help |

## Configuration

Edit `./config.properties` in the project root:

```properties
# IDE log globs (default values shown)
log.vscode=${APPDATA}/Code/logs/**/output_logging*.log
log.idea=${LOCALAPPDATA}/JetBrains/**/log/idea.log

# State & cache
state.dir=${HOME}/.copilot-lens
cache.enabled=true
```

Lookup order (highest priority first):
1. `COPILOT_LENS_*` environment variables
2. `./config.properties` (project root)
3. `~/.copilot-lens/config.properties` (user-level)
4. Hard-coded defaults

> **Note:** environment variable override requires shell env-var support. If your environment blocks setting env vars, edit `config.properties` directly — it serves the same purpose.

## Incremental Scanning

By default, copilot-lens remembers which bytes of the log file it has already read:

- **First run**: full parse, results saved to `~/.copilot-lens/cache.json`
- **Subsequent runs**: only reads new bytes appended since last run
- **Log rotated/truncated** (size shrinks): detects and re-reads from 0

Disable with `cache.enabled=false` in `config.properties`.

## Enabling Verbose Logs

### VSCode

1. `F1` → "Developer: Set Log Level"
2. Type: `GitHub Copilot Chat`
3. Set level: `Trace`
4. Reproduce the action you want to analyze
5. Run `copilot-lens`

Log location: `%APPDATA%\Code\logs\<date>\exthost\output_logging_*.log`

### IntelliJ IDEA

1. `Help` → `Diagnostic Tools` → `Debug Log Settings`
2. Click the icon to add a new entry
3. Type: `#com.github.copilot:trace`
4. Save
5. Restart IDE if needed
6. Reproduce the action
7. Run `copilot-lens`

Log location: `%LOCALAPPDATA%\JetBrains\IntelliJIdea<version>\log\idea.log`

## Output Interpretation

### Console Dashboard

```
Overall Usage
----------------------------------------------------------------
  Request count     47 (each = 1 premium request)
  Total input tok   18,432
  Total output tok  6,891
  ...
```

- **Request count** = your actual GitHub premium requests consumed
- **Input/output tokens** = BPE token counts via jtokkit (GPT-4 encoding)
- Numbers approximate server-side count by ~5%

### HTML Report

`copilot-lens report` → `copilot-lens-report.html` → open in any browser. Dark mode auto-detected.

### JSON Export

`copilot-lens export json` → `copilot-lens-export.json` → pipe to `jq` or any JSON tool.

### Discover Findings

Severity-ranked list of optimization opportunities. Highest-severity first.

## Common Workflows

### Weekly Review

```bash
copilot-lens gain --history
```

### Audit Before Refactor

```bash
copilot-lens discover
```

Address findings, then re-run later to compare.

### CI / Git Hook

```bash
copilot-lens --no-ansi > /dev/null
# Fails if log not found or other error
```

Combine with `export json` for trend tracking.
