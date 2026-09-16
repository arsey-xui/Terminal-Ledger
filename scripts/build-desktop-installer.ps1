# ============================================================
#  build-desktop-installer.ps1 - 打包 JavaFX 桌面端
# ------------------------------------------------------------
#  用法（仓库根目录或 scripts 目录执行）：
#    .\scripts\build-desktop-installer.ps1
#        免安装 app-image -> outputs\TerminalLedger\TerminalLedger.exe
#    .\scripts\build-desktop-installer.ps1 -Version 1.0.2
#        传给 jpackage 的版本号（默认 0.1.0）；建议与 desktop/pom.xml 保持一致
#    .\scripts\build-desktop-installer.ps1 -Msi
#        构建 MSI（需要先安装 WiX 3.x）
# ============================================================
param(
    [string]$Version = "0.1.0",
    [switch]$Msi
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$desktop = Join-Path $root "desktop"
$outputs = Join-Path $root "outputs"

# PS 5.1 下原生命令写 stderr 会变成终止性错误（mvn / jpackage 会往 stderr 写提示信息），
# 所以执行原生命令时临时放开，并显式检查退出码。
function Invoke-NativeSafe {
    param([string]$FilePath, [string[]]$ArgumentList)
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $exitCode = 0
    try {
        # 输出交给宿主，否则会被当作本函数的返回值（调用方会把构建日志当成退出码）
        if ($ArgumentList.Count -gt 0) { & $FilePath @ArgumentList | Out-Host } else { & $FilePath | Out-Host }
        # 必须先取出 $LASTEXITCODE 再执行其它命令：任何 cmdlet（如 Test-Path）都会把它重置。
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
    if ($null -eq $exitCode) { return 0 }
    return [int]$exitCode
}

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
    $mvnExe = (Get-Command mvn).Source
    $code = Invoke-NativeSafe -FilePath $mvnExe -ArgumentList @("-q", "-DskipTests", "package")
    if ($code -ne 0) {
        throw "mvn package 失败（退出码 $code）。请查看上面的构建日志。"
    }

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
    $jpArgs = @(
        "--type", $type,
        "--name", "TerminalLedger",
        "--app-version", $Version,
        "--input", $inputDir,
        "--main-jar", $jar.Name,
        "--main-class", "com.personal.accounting.desktop.DesktopLauncher",
        "--dest", $outputs
    )
    if (Test-Path $iconIcoFile) {
        $jpArgs += @("--icon", $iconIcoFile)
    }

    $jpCode = Invoke-NativeSafe -FilePath "jpackage" -ArgumentList $jpArgs
    if ($jpCode -ne 0) {
        throw "jpackage 失败（退出码 $jpCode）。若使用 -Msi，请先安装 WiX 3.x。"
    }

    # jpackage 会复制一份 jar 到 target\jpackage-input，清掉避免累积
    if (Test-Path -LiteralPath $inputDir) { Remove-Item -LiteralPath $inputDir -Recurse -Force }

    # mvn package / shade 会在 target 留 jar（含 original-*.jar），那是中间产物、与 outputs 里的重复，
    # 这里一并清掉，保证可分发产物只存在于 outputs；保留 classes 以便增量编译更快。
    Get-ChildItem (Join-Path $desktop "target") -Filter "*.jar" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue


    # 顺手把免安装目录打成 zip，方便整包分发
    $zipDest = Join-Path $outputs "TerminalLedger-Desktop-$Version.zip"
    $zipTmp = "$zipDest.part"
    if (Test-Path -LiteralPath $zipTmp) { Remove-Item -LiteralPath $zipTmp -Force }
    if (Test-Path -LiteralPath $zipDest) { Remove-Item -LiteralPath $zipDest -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::CreateFromDirectory($appDir, $zipTmp, [IO.Compression.CompressionLevel]::Optimal, $true)
    Move-Item -LiteralPath $zipTmp -Destination $zipDest -Force
    Write-Host "Output: $outputs"
    if ($Msi) {
        Write-Host "MSI requires WiX 3.x. If unavailable, run without -Msi to get an app-image."
        Write-Host "MSI: $outputs\TerminalLedger-$Version.msi"
    } else {
        Write-Host "Double-click: $outputs\TerminalLedger\TerminalLedger.exe"
    }
} finally {
    Pop-Location
}
