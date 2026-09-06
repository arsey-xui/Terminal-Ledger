param(
    [string]$Version = "0.1.0",
    [switch]$Msi
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$desktop = Join-Path $root "desktop"
$outputs = Join-Path $root "outputs"

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "Maven not found. Install it and add its bin to PATH."
}
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw "jpackage not found. Use JDK 17+ and add its bin to PATH."
}

New-Item -ItemType Directory -Force -Path $outputs | Out-Null

# Generate the Windows app icon (.ico) for jpackage --icon.
$iconPngFile = Join-Path $root "icon.png"
$iconIcoFile = Join-Path $desktop "app.ico"
if (Test-Path $iconPngFile) {
    & "$PSScriptRoot\generate-desktop-icon.ps1" -IconPng $iconPngFile -OutIco $iconIcoFile
}

Push-Location $desktop
try {
    mvn -q -DskipTests package

    $jar = Get-ChildItem (Join-Path $desktop "target\accounting-desktop-$Version.jar") -ErrorAction SilentlyContinue
    if (-not $jar) {
        $jar = Get-ChildItem (Join-Path $desktop "target\*.jar") |
            Where-Object { $_.Name -notmatch '^original-' } |
            Select-Object -First 1
    }
    if (-not $jar) { throw "Build output jar not found." }

    $inputDir = Join-Path $desktop "target\jpackage-input"
    # Clean jpackage-input so a stale jar from a previous version is not bundled.
    if (Test-Path -LiteralPath $inputDir) {
        Remove-Item -LiteralPath $inputDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
    Copy-Item $jar.FullName (Join-Path $inputDir $jar.Name) -Force

    $type = if ($Msi) { "msi" } else { "app-image" }

    # jpackage will not overwrite an existing app-image dir; clear a previous one first.
    $appDir = Join-Path $outputs "TerminalLedger"
    if (Test-Path -LiteralPath $appDir) {
        Remove-Item -LiteralPath $appDir -Recurse -Force
    }

    # Output directly to the repo outputs dir: app-image -> outputs\TerminalLedger\TerminalLedger.exe
    if (Test-Path $iconIcoFile) {
        jpackage `
            --type $type `
            --name "TerminalLedger" `
            --app-version $Version `
            --icon $iconIcoFile `
            --input $inputDir `
            --main-jar $jar.Name `
            --main-class com.personal.accounting.desktop.DesktopLauncher `
            --dest $outputs
    } else {
        jpackage `
            --type $type `
            --name "TerminalLedger" `
            --app-version $Version `
            --input $inputDir `
            --main-jar $jar.Name `
            --main-class com.personal.accounting.desktop.DesktopLauncher `
            --dest $outputs
    }

    Write-Host "Output: $outputs"
    if ($Msi) {
        Write-Host "MSI requires WiX 3.x installed. If unavailable, run without -Msi to get app-image (double-click the .exe)."
        Write-Host "MSI: $outputs\TerminalLedger-$Version.msi"
    } else {
        Write-Host "Double-click: $outputs\TerminalLedger\TerminalLedger.exe"
    }
} finally {
    Pop-Location
}
