# build.ps1 - PowerShell wrapper for build.sh
# Runs in the current terminal window (does NOT spawn a new one).
# Mirrors the pattern used by copilot-lens.ps1.
# Usage: .\build.ps1

$ErrorActionPreference = "Stop"

# Force UTF-8 output so Turkish/non-ASCII characters render correctly
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# Resolve script directory (works whether invoked as .\build.ps1 or full path)
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Locate bash.exe (Git Bash, WSL, MSYS, Cygwin)
# PowerShell 5.1 compatible — no null-conditional operators
$Bash = $null
$Candidates = @(
    "C:\Program Files\Git\bin\bash.exe",
    "C:\Program Files\Git\usr\bin\bash.exe",
    "C:\Program Files (x86)\Git\bin\bash.exe"
)
# Add bash from PATH if available
$bashCmd = Get-Command bash.exe -ErrorAction SilentlyContinue
if ($bashCmd) {
    $Candidates = $Candidates + $bashCmd.Source
}
foreach ($candidate in $Candidates) {
    if ($candidate -and (Test-Path $candidate)) {
        $Bash = $candidate
        break
    }
}

if (-not $Bash) {
    Write-Host "ERROR: bash.exe not found. Install Git for Windows." -ForegroundColor Red
    exit 1
}

# Run build.sh directly. build.sh cd's into its own PROJECT_ROOT, so
# the caller's cwd doesn't matter — unlike copilot-lens.sh which must
# run init/install from the directory it was invoked in.
# Do NOT capture output — let it flow to the console directly.
& $Bash "$ScriptDir\build.sh" $args

# PowerShell exit only accepts 0-255; clamp bash's exit code.
$code = $LASTEXITCODE
if ($code -gt 255) { $code = 255 }
if ($code -lt 0) { $code = 0 }
exit $code
