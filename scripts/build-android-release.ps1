param(
    [switch]$GenerateKeystore
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$android = Join-Path $root "android"
$outputs = Join-Path $root "outputs"
$apkOutDir = Join-Path $outputs "apk"
$apkSource = Join-Path $android "app\build\outputs\apk\release\app-release.apk"

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

if (-not (Test-Path $apkSource)) {
    Write-Host "APK not found (build did not succeed): $apkSource"
    exit 1
}

# Read versionName from build.gradle for the artifact name (fallback 0.1.0)
$version = "0.1.0"
$buildGradle = Join-Path $android "app\build.gradle"
if (Test-Path $buildGradle) {
    $m = [regex]::Match((Get-Content $buildGradle -Raw), 'versionName\s*"([^"]+)"')
    if ($m.Success) { $version = $m.Groups[1].Value }
}

New-Item -ItemType Directory -Force -Path $apkOutDir | Out-Null

$apkDest = Join-Path $apkOutDir "TerminalLedger-$version.apk"
$zipDest = Join-Path $apkOutDir "TerminalLedger-$version.zip"

Copy-Item $apkSource -Destination $apkDest -Force
if (Test-Path $zipDest) { Remove-Item $zipDest -Force }
# Pack into a zip for easy transfer to the phone (some channels restrict direct .apk)
Compress-Archive -Path $apkDest -DestinationPath $zipDest

Write-Host "APK produced: $apkDest"
Write-Host "ZIP produced: $zipDest"
Write-Host ""
Write-Host "Transfer the APK/ZIP to the phone and allow 'install from unknown sources'. To publish formally, replace keystore and bump versionCode/versionName."
