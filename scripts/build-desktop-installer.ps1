param(
    [string]$Version = "0.1.0",
    [switch]$Msi
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$desktop = Join-Path $root "desktop"

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "Maven not found. Install it and add its bin to PATH."
}
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw "jpackage not found. Use JDK 17+ and add its bin to PATH."
}

Push-Location $desktop
try {
    mvn -q -DskipTests package

    $jar = Get-ChildItem "$desktop\target\accounting-desktop-$Version.jar" -ErrorAction SilentlyContinue
    if (-not $jar) {
        $jar = Get-ChildItem "$desktop\target\*.jar" |
            Where-Object { $_.Name -notmatch '^original-' } |
            Select-Object -First 1
    }
    if (-not $jar) { throw "Build output jar not found." }

    $inputDir = Join-Path $desktop "target\jpackage-input"
    New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
    Copy-Item $jar.FullName (Join-Path $inputDir $jar.Name) -Force

    $type = if ($Msi) { "msi" } else { "app-image" }
    $dest = Join-Path $desktop "target\dist"

    jpackage `
        --type $type `
        --name "PersonalAccounting" `
        --app-version $Version `
        --input $inputDir `
        --main-jar $jar.Name `
        --main-class com.personal.accounting.desktop.DesktopLauncher `
        --dest $dest

    Write-Host "Output: $dest"
    if ($Msi) {
        Write-Host "MSI requires WiX 3.x installed. If unavailable, run without -Msi to get app-image (double-click the .exe)."
    } else {
        Write-Host "Double-click: $dest\PersonalAccounting\PersonalAccounting.exe"
    }
} finally {
    Pop-Location
}
