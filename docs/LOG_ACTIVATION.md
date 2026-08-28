# Log Activation Guide

Copilot's verbose log is **OFF by default** in every supported IDE. Until you turn it on, the log files matched by the globs in `config.properties` simply do not exist and `copilot-lens` will print `Log file not found`. That is expected — enable verbose log first, then use Copilot for a while, then run `copilot-lens`.

This guide covers all four supported IDEs (VSCode, IntelliJ, Cursor, Windsurf). Pick the section that matches the IDE you use.

## Overview

| IDE | How to enable verbose log | Where the log lives (Windows default) |
|---|---|---|
| **VSCode** | `F1` → `Developer: Set Log Level` → `GitHub Copilot Chat` → `Trace` | `%APPDATA%\Code\logs\<YYYYMMDDTHHMMSS>\exthost\output_logging_<TS>.log` |
| **IntelliJ IDEA** | `Help` → `Diagnostic Tools` → `Debug Log Settings` → add `#com.github.copilot:trace` | `%LOCALAPPDATA%\JetBrains\<variant><ver>\log\idea.log` |
| **Cursor** | `F1` → `Developer: Set Log Level` → `GitHub Copilot Chat` → `Trace` (same as VSCode) | `%APPDATA%\Cursor\logs\<YYYYMMDDTHHMMSS>\exthost\output_logging_<TS>.log` |
| **Windsurf** | `[NEEDS VERIFICATION]` — see Windsurf section below | `%APPDATA%\Windsurf\logs\<YYYYMMDDTHHMMSS>\window1\exthost\output_logging_<TS>` |

## VSCode

1. Open the Command Palette: `F1` (or `Ctrl+Shift+P`)
2. Type `Developer: Set Log Level` and press Enter
3. In the dropdown, pick **`GitHub Copilot Chat`** (extension-specific selector)
4. Choose level **`Trace`** (most verbose; switch back to `Info` later to keep logs smaller)
5. Reproduce the action you want to analyze: open a chat, send a message, accept an inline suggestion
6. Run `copilot-lens`

Where the log appears:

```
%APPDATA%\Code\logs\<YYYYMMDDTHHMMSS>\exthost\output_logging_<YYYY-MM-DDTHH-MM-SS>.log
```

Quick check from PowerShell:

```powershell
Get-ChildItem "$env:APPDATA\Code\logs\*\*\output_logging*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
```

If the result is empty, verbose log is still off (or you haven't triggered Copilot since enabling it).

**Note on token counts**: VSCode Copilot Chat 0.60+ no longer logs `prompt_tokens` / `completion_tokens`. copilot-lens handles this by look-ahead scanning surrounding log lines for the request body and running BPE counting locally. Token totals include a `Token source:` footer note showing how many records were `reported` (from log usage lines), `BPE-estimated` (local jtokkit count of recovered body), `heuristic` (char-based estimate when body is unavailable), or `unknown` (tokenless).

### VSCode + Copilot Enterprise (`proxy.business.githubcopilot.com`)

Enterprise hesaplarında log URL'i `proxy.individual.githubcopilot.com`
yerine `proxy.business.githubcopilot.com` olur. Parser her iki URL'i de
destekler; beklentiler:

- **Input token'lar** model-aware BPE tahmini olarak hesaplanır
  (look-ahead ile body bulunursa `ESTIMATED`, bulunamazsa
  `ESTIMATED_HEURISTIC`).
- **Output token'lar her zaman 0** kalır — yeni VSCode log formatında
  response body'si hiç loglanmaz; bu bir bug değil, format kısıtıdır.
- Raporlarda `(response not logged)` rozeti ile bu durum açıkça işaretlenir.
- Toplam kullanım için premium request sayısına bakın; bu Copilot
  Enterprise faturalandırma mantığıyla da uyumludur.

## IntelliJ IDEA / IDEA Community

1. `Help` → `Diagnostic Tools` → **`Debug Log Settings`**
2. Click the `+` icon (top right) to add a new entry
3. Type exactly: **`#com.github.copilot:trace`** (case-sensitive, with the leading `#`)
4. Save with OK
5. Restart the IDE if the new entry doesn't take effect immediately
6. Reproduce the action
7. Run `copilot-lens`

Where the log appears:

```
%LOCALAPPDATA%\JetBrains\<variant><version>\log\idea.log
```

If you have multiple JetBrains variants installed, the auto-detect picks whichever `idea.log` was modified most recently — so just use the IDE you want to analyze and its log wins. Force a specific one in `config.properties`:

```properties
log.idea=${LOCALAPPDATA}/JetBrains/IdeaIC2025.2/log/idea.log
```

Quick check from PowerShell:

```powershell
Get-ChildItem "$env:LOCALAPPDATA\JetBrains\*\log\idea.log" | Sort-Object LastWriteTime -Descending | Select-Object FullName, LastWriteTime
```

## Cursor

Cursor is a VSCode fork and ships with the same `Developer: Set Log Level` mechanism and the same `output_logging_*.log` convention — the only thing that changes is the appdata directory.

1. Open the Command Palette: `F1` (or `Ctrl+Shift+P`)
2. Type `Developer: Set Log Level` and press Enter
3. Pick **`GitHub Copilot Chat`** → **`Trace`**
4. Reproduce the action
5. Run `copilot-lens --ide=cursor` (or let auto-detect pick it up)

Where the log appears:

```
%APPDATA%\Cursor\logs\<YYYYMMDDTHHMMSS>\exthost\output_logging_<TS>.log
```

Quick check:

```powershell
Get-ChildItem "$env:APPDATA\Cursor\logs\*\*\output_logging*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
```

## Windsurf

Windsurf (Codeium) is also a VSCode fork, so the generic extension-host log at the path below contains `[fetchCompletions]` / `ccreq` lines that copilot-lens parses the same way it does for VSCode / Cursor.

> **[NEEDS VERIFICATION]** The exact menu path to enable verbose logging for the GitHub Copilot Chat extension (or the Cascade AI assistant) inside Windsurf has not been confirmed against a live Windsurf installation at the time of writing. Try `F1` → `Developer: Set Log Level` → any GitHub Copilot Chat / Codeium / Cascade entry you find in the picker. If that doesn't emit log lines, check Windsurf's own diagnostic-download flow (`Cascade panel → ⋯ → Download Diagnostics`) and consult https://docs.windsurf.com for the latest instructions.

Where the generic extension-host log appears (verified on Windows):

```
%APPDATA%\Windsurf\logs\<YYYYMMDDTHHMMSS>\window1\exthost\output_logging_<TS>
```

Quick check:

```powershell
Get-ChildItem "$env:APPDATA\Windsurf\logs\*\*\exthost\output_logging*" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
```

> **Note on Cascade logs**: Windsurf also produces a Cascade-specific internal log (`Windsurf (Lifeguard).log`) that contains free-form prose, not structured HTTP request data. copilot-lens **does not** parse that file — it only reads the generic extension-host `output_logging_*.log` file. This is by design: token accounting requires structured request/response data, not AI reasoning traces.

To run analysis against a Windsurf log:

```powershell
.\copilot-lens.ps1 --ide=windsurf
```

## Verifying Logs Are Active

After enabling verbose log in one or more IDEs and using Copilot in each for a minute, run:

```powershell
copilot-lens
```

You should see a non-zero `Request count`. If you see `Log file not found`, the verbose log still isn't producing output for that IDE — re-check the steps above for the specific IDE.

For per-IDE quick checks:

```powershell
# VSCode
Get-ChildItem "$env:APPDATA\Code\logs\*\*\output_logging*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 1

# IntelliJ
Get-ChildItem "$env:LOCALAPPDATA\JetBrains\*\log\idea.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 1

# Cursor
Get-ChildItem "$env:APPDATA\Cursor\logs\*\*\output_logging*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 1

# Windsurf
Get-ChildItem "$env:APPDATA\Windsurf\logs\*\*\exthost\output_logging*" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
```

## Turning Logs Back Off

To reduce IDE log noise once you've finished your analysis:

- **VSCode / Cursor**: `F1` → `Developer: Set Log Level` → `GitHub Copilot Chat` → `Info` (or `Off`)
- **IntelliJ**: `Help` → `Diagnostic Tools` → `Debug Log Settings` → remove the `#com.github.copilot:trace` entry
- **Windsurf**: reverse whatever menu entry you used to enable it (depends on what worked — see the Windsurf section above)