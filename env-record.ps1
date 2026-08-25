<#
.SYNOPSIS
  Calisma ortami icin gerekli dizinleri kullanici PATH'ine ekler.

.DESCRIPTION
  Bu script asagidaki dizinleri kullanici PATH'ine ekler:
    - rtkx           -> C:\
    - copilot-lens  -> C:\workspace\copilot-lens

  Idempotent: zaten PATH'te olan dizinlere dokunmaz, tekrar calistirilabilir.
  Kullanici PATH'ine yazar (admin gerekmez, sistem genelini etkilemez).

.NOTES
  Dosya: D:\fe-workspace\copilot-lens\env-record.ps1
  Yollari degistirmek / yeni arac eklemek icin asagidaki $Paths dizisini duzenle.

  PERSISTENCE: Bu script kullanici PATH'ini Windows registry'sine yazar (HKCU\Environment\Path).
  Restart'larda sifirlanmaz — bir kere calistirman yeterli, makineyi kapatsan da PATH'te kalir.
  Yeni acilan her terminal registry'den okur. Tekrar calistirmanin tek etkisi mevcut
  oturumun $env:Path'ini yenilemektir (idempotent — zarar vermez).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File "D:\fe-workspace\copilot-lens\env-record.ps1"
#>

$ErrorActionPreference = 'Stop'

# --- YOLLAR BURADA ---
# Yeni arac eklemek icin: @{ Name = 'gorunen-ad'; Dir = 'C:\tam\yol' } formatinda satir ekle.
$Paths = @(
    @{ Name = 'rtkx';          Dir = 'C:\' },
    @{ Name = 'copilot-lens'; Dir = 'C:\workspace\copilot-lens' }
)

function Add-ToUserPath {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Dir
    )

    if (-not (Test-Path -LiteralPath $Dir)) {
        Write-Warning "[$Name] Dizin yok, atlaniyor: $Dir"
        return
    }

    $canonical = (Resolve-Path -LiteralPath $Dir).Path.TrimEnd('\')
    $current   = [Environment]::GetEnvironmentVariable('Path', 'User')
    $entries   = if ($current) {
        $current -split ';' | Where-Object { $_ } | ForEach-Object { $_.TrimEnd('\') }
    } else { @() }

    if ($entries -contains $canonical) {
        Write-Host "[$Name] Zaten PATH'te: $canonical" -ForegroundColor Yellow
        return
    }

    $new = if ($current) { "$current;$canonical" } else { $canonical }
    [Environment]::SetEnvironmentVariable('Path', $new, 'User')
    $env:Path = "$env:Path;$canonical"

    Write-Host "[$Name] Eklendi: $canonical" -ForegroundColor Green
}

foreach ($entry in $Paths) {
    Add-ToUserPath -Name $entry.Name -Dir $entry.Dir
}

# --- Dogrulama ---
Write-Host ""
Write-Host "Dogrulama (bu oturum):" -ForegroundColor Cyan
foreach ($entry in $Paths) {
    $cmd = Get-Command $entry.Name -ErrorAction SilentlyContinue
    if ($cmd) {
        Write-Host "  $($entry.Name) -> $($cmd.Source)" -ForegroundColor Green
    } else {
        Write-Host "  $($entry.Name) -> bulunamadi (PATH'e eklendi ama binary'yi kontrol et: $($entry.Dir))" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "Yeni acilan terminallerde yeni PATH otomatik aktif olur." -ForegroundColor DarkGray

# --- Registry'deki nihai durum ---
Write-Host ""
Write-Host "Registry'deki User PATH girdileri (bu araclar icin):" -ForegroundColor Cyan
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
foreach ($entry in $Paths) {
    $canonical = if (Test-Path -LiteralPath $entry.Dir) {
        (Resolve-Path -LiteralPath $entry.Dir).Path.TrimEnd('\')
    } else { $entry.Dir.TrimEnd('\') }
    $found = $userPath -split ';' | Where-Object { $_.TrimEnd('\') -ieq $canonical }
    if ($found) {
        Write-Host "  [OK]   $($entry.Name) -> $canonical" -ForegroundColor Green
    } else {
        Write-Host "  [YOK]  $($entry.Name) -> $canonical (eklenmedi — dizin mevcut mu?)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "Kalici: HKCU\Environment\Path registry'sine yazildi. Restart sonrasi da gecerli." -ForegroundColor DarkGray
