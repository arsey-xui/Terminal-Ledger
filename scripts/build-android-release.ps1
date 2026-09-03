param(
    [switch]$GenerateKeystore
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$android = Join-Path $root "android"

if ($GenerateKeystore -or -not (Test-Path (Join-Path $android "keystore.properties"))) {
    & "$PSScriptRoot\generate-release-keystore.ps1"
}

Push-Location $android
try {
    if (Test-Path "gradlew.bat") {
        .\gradlew.bat assembleRelease
    } else {
        Write-Warning "gradlew.bat not found. Open android/ in Android Studio, then Build -> Generate Signed Bundle / APK -> APK. Or run: gradle assembleRelease"
    }
} finally {
    Pop-Location
}

$apk = Join-Path $android "app\build\outputs\apk\release\app-release.apk"
if (Test-Path $apk) {
    Write-Host "APK produced: $apk"
    Write-Host "Transfer it to the phone and allow 'install from unknown sources'. To publish formally, replace keystore and bump versionCode/versionName."
} else {
    Write-Host "APK not found (build did not succeed)."
}
