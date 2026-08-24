# Installation Guide

## Requirements

- **JDK 17 or newer** (JDK 21 recommended)
- Git Bash (or any POSIX shell) OR PowerShell 5.1+
- Internet access for one-time download of `jtokkit` jar (~1.5 MB)

No Maven, Gradle, Node.js, or other toolchain needed.

## Steps

### 1. Verify Java

```bash
javac -version
# Expected: javac 17.x.x or newer
```

If not installed, get JDK from https://adoptium.net/ or your corporate portal.

### 2. Build

```bash
cd /d/fe-workspace/copilot-lens
./build.sh
```

This will:
- Check your JDK version
- Download `jtokkit-0.6.1.jar` from Maven Central (one-time)
- Download uses a `.download` extension first then renames to `.jar` to avoid Windows file-association prompts
- Compile all `.java` files into `out/`

Expected output:
```
==> JDK kontrol
OK 17.0.1 (...)
OK --release 17
==> lib/ dizini
==> jtokkit-0.6.1 indiriliyor (.download uzantisi ile)
OK jtokkit indirildi (1535888 bytes)
==> Kaynak kodlar derleniyor
OK Derleme basarili
```

### 3. Initialize Config (Optional but Recommended)

**Git Bash:**
```bash
./copilot-lens.sh init
```

**PowerShell:**
```powershell
.\copilot-lens.ps1 init
```

This writes `./config.properties` in the current directory (project root by convention) with default IDE log path globs. Edit the file to override paths if your IDEs are installed in non-standard locations, then commit it so teammates inherit it.

> `init` is **idempotent** — if `./config.properties` already exists, it does nothing. Delete the file first if you want to regenerate with new defaults.
>
> `init` only writes the project-level config. The user-level fallback at `~/.copilot-lens/config.properties` is only consulted when no project config exists and is never touched by `init`.

### 4. Add to PATH (Optional)

**Git Bash:**
```bash
./copilot-lens.sh install
```

**PowerShell:**
```powershell
.\copilot-lens.ps1 install
```

This copies the wrapper to `~/.local/bin/copilot-lens` so you can invoke it as `copilot-lens` from anywhere. If `~/.local/bin` is not in your PATH, it appends an export line to `~/.bashrc`.

The old `./install.sh` still works as a thin wrapper around `./copilot-lens.sh install` for backward compatibility.

**Manual PATH setup (if you skip `install`):**

PowerShell:
```powershell
$env:PATH += ";D:\fe-workspace\copilot-lens"
# Or permanently in $PROFILE:
Add-Content $PROFILE "`n`$env:PATH += ';D:\fe-workspace\copilot-lens'"
```

Then invoke as `.\copilot-lens.ps1 <args>` from anywhere.

## What Gets Created When

| When | Where | What |
|------|-------|------|
| `./build.sh` | `lib/jtokkit-*.jar` | Downloaded dependency |
| `./build.sh` | `out/` | Compiled `.class` files |
| `copilot-lens init` | `config.properties` | Project-level config (idempotent) |
| `./copilot-lens.sh install` | `~/.local/bin/copilot-lens` | Wrapper copy |
| `copilot-lens` (run) | `~/.copilot-lens/state.json` | Per-file byte offset (incremental) |
| `copilot-lens` (run) | `~/.copilot-lens/cache.json` | Cached parsed requests |
| `copilot-lens snapshot` | `~/.copilot-lens/snapshots/YYYY-MM-DD.json` | Daily totals (atomic write) |
| `copilot-lens report` | `<cwd>/copilot-lens-report.html` | HTML report (explicit) |
| `copilot-lens export json` | `<cwd>/copilot-lens-export.json` | JSON export (explicit) |

The default `copilot-lens` command produces **console output only** — it does not create files in your current directory. Files are only written when you explicitly use `report` or `export json`.

## Enable Verbose Log (Required for copilot-lens to find anything)

Copilot's verbose log is **OFF by default** in both IDEs. Until you turn it on, the log files matched by the globs in `config.properties` simply do not exist and `copilot-lens` will print `Log file not found`. That is expected — enable verbose log first, then use Copilot for a while, then run `copilot-lens`.

### VSCode

1. Open the Command Palette: `F1` (or `Ctrl+Shift+P`)
2. Type `Developer: Set Log Level` and press Enter
3. In the dropdown, pick **`GitHub Copilot Chat`** (extension-specific selector)
4. Choose level **`Trace`** (most verbose; you can switch back to `Info` later to keep logs smaller)
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

### IntelliJ IDEA / IDEA Community

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

Example for this PC (2026-08-24):

```
C:\Users\<you>\AppData\Local\JetBrains\IdeaIC2025.2\log\idea.log
C:\Users\<you>\AppData\Local\JetBrains\IntelliJIdea2022.2\log\idea.log
```

If you have multiple JetBrains variants installed, the auto-detect picks whichever `idea.log` was modified most recently — so just use the IDE you want to analyze and its log wins. Force a specific one in `config.properties`:

```properties
log.idea=${LOCALAPPDATA}/JetBrains/IdeaIC2025.2/log/idea.log
```

Quick check from PowerShell:

```powershell
Get-ChildItem "$env:LOCALAPPDATA\JetBrains\*\log\idea.log" | Sort-Object LastWriteTime -Descending | Select-Object FullName, LastWriteTime
```

### Verifying Both Logs Are Active

After enabling both and using Copilot in each IDE for a minute, run:

```bash
copilot-lens
```

You should see a non-zero `Request count`. If you see `Log file not found`, the verbose log still isn't producing output for that IDE — re-check the steps above.

To turn verbose log back off (keep IDE log noise low):

- **VSCode**: `F1` → `Developer: Set Log Level` → `GitHub Copilot Chat` → `Info` (or `Off`)
- **IntelliJ**: `Help` → `Diagnostic Tools` → `Debug Log Settings` → remove the `#com.github.copilot:trace` entry

## Upgrade / Reinstall

```bash
cd /d/fe-workspace/copilot-lens
./build.sh   # recompiles; doesn't re-download jars if cached
```

To clear cached state (forces a full re-parse on next run):

```bash
rm ~/.copilot-lens/state.json ~/.copilot-lens/cache.json
```

## Troubleshooting

**`./copilot-lens.sh: not found` / `./copilot-lens: not found`**
The project wrapper is named `copilot-lens.sh` (with `.sh` extension). Run `./copilot-lens.sh` from the project directory, or install via `./install.sh`.

**PowerShell: `not recognized as the name of a cmdlet`**
Use the dedicated PowerShell wrapper: `.\copilot-lens.ps1 <args>`. The `.sh` script and `copilot-lens` (bash) wrapper may trigger Windows file-association prompts in PowerShell; the `.ps1` wrapper runs in the current terminal cleanly.

**Sub-terminal opens and closes immediately**
You're probably double-clicking the `.sh` file or invoking via Windows file association. Run from an already-open Git Bash or PowerShell terminal instead.

**`javac: command not found`**
Install JDK 17+ and ensure it's on PATH.

**`Error: jtokkit jar not found`**
Run `./build.sh` first.

**`Log file not found`**
Verbose log is not enabled in your IDE, OR no Copilot activity since enabling it. See [Enable Verbose Log](#enable-verbose-log-required-for-copilot-lens-to-find-anything) above.

**Windows path issues / `ClassNotFoundException`**
The wrapper handles `cygpath` conversion automatically. If broken, verify `cygpath` is on PATH (comes with Git for Windows).

**Windows file-association prompt when downloading jar**
Fixed in `build.sh` — the jar is downloaded as `.download` then atomically renamed to `.jar` so Windows never sees a fresh `.jar` file.
