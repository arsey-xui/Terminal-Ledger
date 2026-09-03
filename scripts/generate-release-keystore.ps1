param(
    [string]$Alias = "accounting",
    [string]$Store = "release.jks",
    [string]$StorePass = "accounting-pass",
    [string]$KeyPass = "accounting-pass"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$android = Join-Path $root "android"

if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    throw "keytool not found. Install JDK 17+ and add its bin to PATH."
}

$storePath = Join-Path $android $Store
if (Test-Path $storePath) {
    throw "Keystore already exists: $storePath"
}

keytool -genkeypair -v `
    -keystore $storePath `
    -alias $Alias `
    -keyalg RSA -keysize 2048 -validity 3650 `
    -storepass $StorePass -keypass $KeyPass `
    -dname "CN=PersonalAccounting, OU=Dev, O=Dev, L=City, ST=State, C=CN"

$props = "storeFile=$Store`nstorePassword=$StorePass`nkeyAlias=$Alias`nkeyPassword=$KeyPass"
Set-Content -LiteralPath (Join-Path $android "keystore.properties") -Value $props -Encoding ascii
Write-Host "Keystore created: $storePath"
Write-Host "Config written: $(Join-Path $android 'keystore.properties')"
