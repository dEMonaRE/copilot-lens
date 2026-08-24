# copilot-lens.ps1 - PowerShell launcher
# Runs the bash wrapper in the current terminal window (does NOT spawn a new one).
# Usage: .\copilot-lens.ps1 <args>

$ErrorActionPreference = "Stop"

# Force UTF-8 output so Turkish/non-ASCII characters from Java render correctly
# in PowerShell (default code page is Windows-1252/1254).
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# Resolve script directory (works whether invoked as .\script.ps1 or full path)
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

# Run the bash wrapper, passing all arguments through.
# Do NOT capture output — let it flow to the console directly.
# Preserve caller's cwd so commands like `init` and `install` operate
# in the directory the user actually invoked them from, not in $ScriptDir.
# PowerShell exit only accepts 0-255; clamp bash's exit code.
Push-Location -Path (Get-Location).ProviderPath -ErrorAction SilentlyContinue
try {
    & $Bash "$ScriptDir\copilot-lens.sh" $args
} finally {
    Pop-Location -ErrorAction SilentlyContinue
}
$code = $LASTEXITCODE
if ($code -gt 255) { $code = 255 }
if ($code -lt 0) { $code = 0 }
exit $code
