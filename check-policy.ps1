<#
.SYNOPSIS
  PowerShell execution policy durumunu ve script calistirma kisitini gosterir.

.DESCRIPTION
  Tum scope'lardaki policy'leri listeler, gecerli (effective) policy'yi hesaplar
  ve "script calistirabilir miyim?" sorusuna evet/hayir olarak cevap verir.
  env-kaydi.ps1'i etkilemez — sadece okuma/raporlama yapar.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File "D:\fe-workspace\copilot-lens\check-policy.ps1"
#>

$ErrorActionPreference = 'Stop'

Write-Host ""
Write-Host "=== Execution Policy (tum scope'lar, ustten alta oncelik) ===" -ForegroundColor Cyan
Write-Host "  Ust scope, alt scope'u ezer. Process > CurrentUser > LocalMachine." -ForegroundColor DarkGray
Get-ExecutionPolicy -List | Format-Table -AutoSize

$effective = Get-ExecutionPolicy

Write-Host ""
Write-Host "=== Gecerli (effective) policy: $effective ===" -ForegroundColor Cyan
Write-Host ""

switch ($effective) {
    'Restricted' {
        Write-Host "  DURUM: BLOKLU" -ForegroundColor Red
        Write-Host "  Hicbir .ps1 scripti calismaz. Sadece interaktif komutlar calisir." -ForegroundColor Red
        Write-Host "  (Windows istemcilerinin on-varsayilan policy'si budur.)" -ForegroundColor DarkGray
    }
    'AllSigned' {
        Write-Host "  DURUM: KISITLI" -ForegroundColor Yellow
        Write-Host "  Sadece guvenilir sertifikayla imzalanmis scriptler calisir." -ForegroundColor Yellow
    }
    'RemoteSigned' {
        Write-Host "  DURUM: ACIK (yerel scriptler icin)" -ForegroundColor Green
        Write-Host "  Yerel scriptler calisir; internetten indirilenler imza ister." -ForegroundColor Green
        Write-Host "  (Windows server varsayilani budur.)" -ForegroundColor DarkGray
    }
    'Unrestricted' {
        Write-Host "  DURUM: TAMAMEN ACIK" -ForegroundColor Green
        Write-Host "  Tum scriptler calisir; indirilenler icin onay sorar." -ForegroundColor Green
    }
    'Bypass' {
        Write-Host "  DURUM: TAMAMEN ACIK" -ForegroundColor Green
        Write-Host "  Hicbir engel yok, hicbir onay sorulmaz." -ForegroundColor Green
    }
    'Undefined' {
        Write-Host "  DURUM: BELIRSIZ" -ForegroundColor Yellow
        Write-Host "  Hicbir scope'ta policy set edilmemis (genelde Restricted gibi davranir)." -ForegroundColor Yellow
    }
    default {
        Write-Host "  DURUM: TANIMSIZ ($effective)" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "=== Eger BLOKLU/KISITLI ise ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Tek seferlik bypass (script'in basina ekle veya tek satir):" -ForegroundColor Gray
Write-Host '    powershell -ExecutionPolicy Bypass -File "D:\fe-workspace\copilot-lens\env-kaydi.ps1"' -ForegroundColor White
Write-Host ""
Write-Host "  Kalici cozum (sadece senin kullanicin icin, admin gerekmez):" -ForegroundColor Gray
Write-Host "    Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned" -ForegroundColor White
Write-Host ""
Write-Host "=== Notlar ===" -ForegroundColor Cyan
Write-Host "  - Policy bir guvenlik duvari degildir; sadece yanlislikla calistirmayi engeller." -ForegroundColor DarkGray
Write-Host "  - 'env-kaydi.ps1' zaten idempotent — kac kere calistirirsan calistir zarar vermez." -ForegroundColor DarkGray
Write-Host "  - Tek bir .ps1 calistirmak istiyorsan bypass yeter; kalici degisiklik gereksiz." -ForegroundColor DarkGray
