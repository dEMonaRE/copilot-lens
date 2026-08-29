# copilot-lens

GitHub Copilot token & premium usage analyzer for VSCode, IntelliJ IDEA, Cursor, and Windsurf.

RTK-style CLI: **read IDE logs, count tokens locally, show what you're spending your premium requests on.** No cloud calls, no AI service — pure local log parsing with BPE token counting.

## Why

- **No cloud AI dependency** — only reads IDE's own verbose logs
- **BPE token counting** via jtokkit (OpenAI tiktoken Java port)
- **No install of external tools** — JDK 17+ is enough
- **Works in PowerShell and Git Bash**
- **One-shot, watch, history, JSON export** modes

## Prerequisites

**JDK 17+** gerekli (21 LTS önerilir — projeyi Eclipse Adoptium Temurin ile derliyoruz). Kurulu değilse veya sürüm eskiyse:

Windows PowerShell — `winget` ile:

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK
```

Windows MSI ile (winget yoksa): <https://adoptium.net/temurin/releases/?version=21> adresinden `Temurin-21-jdk_x64.msi` indirip kurun.

WSL / Ubuntu / Debian — Adoptium APT repo:

```bash
sudo apt update && sudo apt install -y wget apt-transport-https gnupg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifamily/ubuntu $(lsb_release -sc) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update && sudo apt install -y temurin-21-jdk
```

Fedora / RHEL:

```bash
sudo dnf install -y temurin-21-jdk
```

macOS (Homebrew):

```bash
brew install --cask temurin@21
```

SDKMAN (tüm platformlar için ortak yol):

```bash
sdk install java 21-tem
```

Kurulumu doğrulayın:

```bash
java -version
# openjdk version "21.x.x" ... görmeli
```

---

## Quick Start

### 1. Projeyi edinin

**Git ile (önerilen):**

```bash
# Windows PowerShell veya WSL / Linux / macOS — hepsi aynı
git clone https://github.com/dEMonaRE/copilot-lens.git
cd copilot-lens
```

**Git yoksa (doğrudan ZIP indir):**

Windows PowerShell:

```powershell
$url = "https://github.com/dEMonaRE/copilot-lens/archive/refs/heads/master.zip"
Invoke-WebRequest -Uri $url -OutFile "copilot-lens.zip"
Expand-Archive copilot-lens.zip
cd copilot-lens-master
```

WSL / Linux / macOS:

```bash
curl -L -o copilot-lens.zip https://github.com/dEMonaRE/copilot-lens/archive/refs/heads/master.zip
unzip copilot-lens.zip
cd copilot-lens-master
```

### 2. Derleyin

```bash
# Build (downloads jtokkit jar, compiles)
./build.sh                     # Git Bash / WSL / Linux / macOS
.\build.ps1                    # PowerShell / cmd (uses current terminal)
```

### 3. Yapılandırın ve çalıştırın

```bash
# Initialize config (writes ./config.properties for IDE log paths)
./copilot-lens.sh init        # Git Bash / WSL / Linux / macOS
.\copilot-lens.ps1 init        # PowerShell

# Add wrapper to PATH (optional)
./copilot-lens.sh install
.\copilot-lens.ps1 install

# Run (auto-detects which IDE was used most recently)
copilot-lens
# or
.\copilot-lens.ps1
```

See [INSTALL.md](docs/INSTALL.md) for full setup, [USAGE.md](docs/USAGE.md) for command reference, [FEATURES.md](docs/FEATURES.md) for feature deep-dives, and [LOG_ACTIVATION.md](docs/LOG_ACTIVATION.md) for verbose-log setup (required for VSCode / IntelliJ / Cursor / Windsurf).

## Commands

| Command | What |
|---------|------|
| `copilot-lens` | Single-shot report (console only) |
| `copilot-lens gain --history` | Daily usage trend |
| `copilot-lens discover` | Optimization findings |
| `copilot-lens watch` | Live monitoring |
| `copilot-lens snapshot` | Persist today's totals to `~/.copilot-lens/snapshots/` |
| `copilot-lens trend` | ASCII trend chart from stored snapshots |
| `copilot-lens export json` | JSON export to file |
| `copilot-lens report` | HTML report to file (includes trend section if snapshots exist) |
| `copilot-lens init` | Write `./config.properties` in cwd (idempotent) |
| `copilot-lens install` | Copy wrapper to `~/.local/bin` and update PATH |

## IDE Auto-Detection

By default, copilot-lens finds whichever IDE log was modified most recently. No `--ide` flag needed. Force one with `--ide=vscode`, `--ide=idea`, `--ide=cursor`, or `--ide=windsurf`.

## Configuration

Project-level config at `./config.properties` (created by `init` in the current directory). Override IDE log paths without env vars:

```properties
log.vscode=${APPDATA}/Code/logs/**/output_logging*.log
log.idea=${LOCALAPPDATA}/JetBrains/**/log/idea.log
log.cursor=${APPDATA}/Cursor/logs/**/output_logging*.log
log.windsurf=${APPDATA}/Windsurf/logs/**/output_logging*.log
cache.enabled=true
state.enabled=true
```

`init` only writes the project-level file. `~/.copilot-lens/config.properties` is a fallback consulted when no project config exists; it is never created or modified by `init`.

## Project Layout

```
copilot-lens/
├── README.md
├── build.sh                 Build script (downloads jtokkit jar)
├── build.ps1                PowerShell wrapper for build.sh (current terminal)
├── install.sh               Backward-compat thin wrapper around `install` subcommand
├── copilot-lens.sh          Bash wrapper (script source)
├── copilot-lens.ps1         PowerShell wrapper (5.1 compatible)
├── config.properties        Project-level IDE log path config
├── src/io/copilotlens/
│   ├── Main.java            CLI entry + routing
│   ├── Args.java            Argument parsing
│   ├── config/
│   │   └── CopilotLensConfig.java   Reads project + user config
│   ├── detector/
│   │   └── IdeDetector.java Glob-based IDE log discovery
│   ├── parser/
│   │   ├── CopilotRequest.java     Domain model
│   │   ├── LogParser.java          Interface
│   │   ├── VsCodeParser.java       JSON log format
│   │   ├── VsCodeForkParser.java   Cursor / Windsurf (VSCode fork)
│   │   └── IntelliJParser.java     Plain-text log format
│   ├── analyzer/
│   │   ├── TokenCounter.java       BPE token counting (jtokkit)
│   │   ├── StatsAggregator.java    Summary statistics
│   │   ├── Discoverer.java         Optimization findings
│   │   └── IncrementalState.java   File offset cache
│   ├── reporter/
│   │   ├── CliReporter.java        Terminal output (ANSI)
│   │   ├── HtmlReporter.java       HTML report
│   │   └── JsonReporter.java       JSON export
│   └── watch/
│       └── LogWatcher.java         Live monitoring
├── docs/
│   ├── INSTALL.md
│   ├── USAGE.md
│   ├── FEATURES.md
│   └── LOG_ACTIVATION.md
├── lib/                     Downloaded jars (created by build.sh)
└── out/                     Compiled classes (created by build.sh)
```

## Requirements

- **JDK 17+** (JDK 21 recommended)
- Git Bash on Windows (or any POSIX shell)
- PowerShell 5.1+ for the `.ps1` wrapper
- ~5 MB disk (jar + compiled classes)

No Maven/Gradle/Node/external tools.

## License

MIT.
