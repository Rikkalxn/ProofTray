param([switch]$Install, [string]$InstallRoot = 'D:\Android')

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\android-env.ps1" -InstallRoot $InstallRoot
$projectRoot = Split-Path -Parent $PSScriptRoot
$task = if ($Install) { ':app:installDebug' } else { ':app:assembleDebug' }
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') $task
    if ($LASTEXITCODE -ne 0) { throw "Android task failed: $task (exit $LASTEXITCODE)" }
    $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw 'Gradle completed but the expected debug APK was not found.' }
    Write-Host "Debug APK: $apk"
} finally {
    Pop-Location
}
