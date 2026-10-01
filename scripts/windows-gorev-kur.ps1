# Bilinçli Kupon'u her sabah 06:00'da çalıştıran Windows Görev Zamanlayıcı görevini kurar.
#
# Kullanım (proje klasöründe PowerShell açıp):
#   powershell -ExecutionPolicy Bypass -File scripts\windows-gorev-kur.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\windows-gorev-kur.ps1 -Saat 07:30
#
# Bilgisayar 06:00'da kapalı/uykudaysa görev açılınca hemen çalışır. `bilincli gunluk`
# aynı gün ikinci kez kupon üretmediği için bu güvenlidir.
# Not: Bilgisayarın saat dilimi Türkiye (UTC+3) olmalı.

param([string]$Saat = "06:00")
$ErrorActionPreference = "Stop"

$proje = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

$python = Get-Command python -ErrorAction SilentlyContinue
if ($python -and $python.Source -notlike "*WindowsApps*") {
    $calistir = "`"$($python.Source)`" -m bilincli"
} elseif (Get-Command py -ErrorAction SilentlyContinue) {
    $calistir = "py -3 -m bilincli"
} else {
    throw "Python bulunamadı. python.org'dan Python 3.11+ kur ve 'Add to PATH' seçeneğini işaretle."
}

New-Item -ItemType Directory -Force -Path (Join-Path $proje "veri") | Out-Null
$log = Join-Path $proje "veri\gunluk.log"
$arguman = "/c cd /d `"$proje`" && $calistir gunluk >> `"$log`" 2>&1"

$action = New-ScheduledTaskAction -Execute "cmd.exe" -Argument $arguman -WorkingDirectory $proje
$trigger = New-ScheduledTaskTrigger -Daily -At $Saat
# Veri kaynağına ulaşılamazsa (çıkış kodu 1) 20 dakika arayla 3 kez daha dener.
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -WakeToRun `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 30) `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 20)

Register-ScheduledTask -TaskName "BilincliKupon" -Action $action -Trigger $trigger `
    -Settings $settings -Description "Bilinçli Kupon günlük karar ve sonuç kontrolü" -Force | Out-Null

Write-Host "Görev kuruldu: her gün $Saat -> $calistir gunluk"
Write-Host "Kayıtlar: $log"
Write-Host "Hemen denemek için: Start-ScheduledTask -TaskName BilincliKupon"
Write-Host "Kaldırmak için:     Unregister-ScheduledTask -TaskName BilincliKupon"
