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

This writes `./config.properties` in the project root with default IDE log path globs. Edit the file to override paths if your IDEs are installed in non-standard locations.

> `init` is **idempotent** — if the config file already exists, it does nothing. Delete the file first if you want to regenerate with new defaults.

### 4. Add to PATH (Optional)

**Git Bash:**
```bash
./install.sh
```

This copies the wrapper to `~/.local/bin/copilot-lens` so you can invoke it as `copilot-lens` from anywhere.

If `~/.local/bin` is not in your PATH, the script adds it to `~/.bashrc` automatically.

**PowerShell:** Add the project root to your `$env:PATH`:

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
| `./install.sh` | `~/.local/bin/copilot-lens` | Wrapper copy |
| `copilot-lens` (run) | `~/.copilot-lens/state.json` | Per-file byte offset (incremental) |
| `copilot-lens` (run) | `~/.copilot-lens/cache.json` | Cached parsed requests |
| `copilot-lens report` | `<cwd>/copilot-lens-report.html` | HTML report (explicit) |
| `copilot-lens export json` | `<cwd>/copilot-lens-export.json` | JSON export (explicit) |

The default `copilot-lens` command produces **console output only** — it does not create files in your current directory. Files are only written when you explicitly use `report` or `export json`.

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
Enable verbose log in your IDE — see [USAGE.md](USAGE.md) → "Enabling Verbose Logs".

**Windows path issues / `ClassNotFoundException`**
The wrapper handles `cygpath` conversion automatically. If broken, verify `cygpath` is on PATH (comes with Git for Windows).

**Windows file-association prompt when downloading jar**
Fixed in `build.sh` — the jar is downloaded as `.download` then atomically renamed to `.jar` so Windows never sees a fresh `.jar` file.
