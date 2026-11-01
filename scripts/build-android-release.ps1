# ============================================================
#  build-android-release.ps1 - 打包 Android apk + zip
# ------------------------------------------------------------
#  用法（仓库根目录或 scripts 目录执行）：
#    .\scripts\build-android-release.ps1
#        正式包：只打 arm64-v8a（真机与 ARM64 模拟器均可）
#        产物：outputs\apk\TerminalLedger-<version>-b<versionCode>-arm64-v8a.apk 与同名 zip
#        每次出包 versionCode 自增（设置页显示 versionCode + 构建时间 + 提交号，用于分辨构建）
#    .\scripts\build-android-release.ps1 -Version 1.0.2-test
#        自定义产物后缀，便于同时保留多份测试包（不改 build.gradle 里的版本号）
#    .\scripts\build-android-release.ps1 -Clean
#        先 clean 再构建（怀疑产物没更新时用）
#    .\scripts\build-android-release.ps1 -GenerateKeystore
#        先重新生成签名 keystore
# ============================================================
param(
    [switch]$GenerateKeystore,
    [string]$Version,
    [switch]$Clean,
    # 不动 versionCode，原样重打同一个版本（排查问题、重出包时用）
    [switch]$NoBump
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$android = Join-Path $root "android"
$outputs = Join-Path $root "outputs"
$apkOutDir = Join-Path $outputs "apk"

# PS 5.1 下原生命令写 stderr 会变成终止性错误（Gradle / javac 常年往 stderr 写提示），
# 所以执行原生命令时临时放开，并显式检查退出码。
function Invoke-NativeSafe {
    param([string]$FilePath, [string[]]$ArgumentList)
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $exitCode = 0
    try {
        # 输出交给宿主，否则会被当作本函数的返回值（调用方会把构建日志当成退出码）
        if ($ArgumentList.Count -gt 0) { & $FilePath @ArgumentList | Out-Host } else { & $FilePath | Out-Host }
        # 必须先取出 $LASTEXITCODE 再执行其它命令：任何 cmdlet 都会把它重置
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
    if ($null -eq $exitCode) { return 0 }
    return [int]$exitCode
}

if ($GenerateKeystore -or -not (Test-Path (Join-Path $android "keystore.properties"))) {
    & "$PSScriptRoot\generate-release-keystore.ps1"
}

# 出包前自增 versionCode。
#
# 一个 versionName 会出很多次包（同一版本号下继续修 bug 是常态），只靠版本名分不出手机上装的是
# 哪一次。设置页会显示 versionCode + 构建时间 + 提交号，所以这里保证每次出包 versionCode 都不同。
# -NoBump 用于原样重打同一个版本。
$buildGradleFile = Join-Path $android "app\build.gradle"
if (-not (Test-Path $buildGradleFile)) { throw "未找到 $buildGradleFile" }
$gradleText = [IO.File]::ReadAllText($buildGradleFile, [Text.Encoding]::UTF8)
$codePattern = [regex]'versionCode\s+(\d+)'
$codeMatch = $codePattern.Match($gradleText)
if (-not $codeMatch.Success) { throw "app/build.gradle 里找不到 versionCode" }
$versionCode = [int]$codeMatch.Groups[1].Value
if ($NoBump) {
    Write-Host "versionCode: $versionCode (未自增，-NoBump)"
} else {
    $previousCode = $versionCode
    $versionCode = $versionCode + 1
    $gradleText = $codePattern.Replace($gradleText, "versionCode $versionCode", 1)
    # 不带 BOM 写回：Groovy 能读 BOM，但没必要给构建文件加一个
    [IO.File]::WriteAllText($buildGradleFile, $gradleText, (New-Object Text.UTF8Encoding($false)))
    Write-Host "versionCode: $previousCode -> $versionCode"
}

Push-Location $android
try {
    if (-not (Test-Path "gradlew.bat")) {
        throw "未找到 android\gradlew.bat，请用 Android Studio 构建或使用 gradle assembleRelease"
    }
    $gradleTasks = @("assembleRelease")
    if ($Clean) { $gradleTasks = @("clean") + $gradleTasks }
    $code = Invoke-NativeSafe -FilePath (Join-Path $android "gradlew.bat") -ArgumentList ($gradleTasks + @("--no-daemon", "--console=plain"))
    if ($code -ne 0) { throw "gradle 构建失败（退出码 $code），请看上面的构建日志" }
} finally {
    Pop-Location
}

if (-not $Version) {
    $Version = "0.1.0"
    $buildGradle = Join-Path $android "app\build.gradle"
    if (Test-Path $buildGradle) {
        $m = [regex]::Match((Get-Content $buildGradle -Raw), 'versionName\s*"([^"]+)"')
        if ($m.Success) { $Version = $m.Groups[1].Value }
    }
}

$stageDir = Join-Path $android "app\build\outputs\apk\release"
$sources = @(Get-ChildItem (Join-Path $stageDir "app-*-release.apk") -ErrorAction SilentlyContinue)
if ($sources.Count -eq 0) { throw "未找到构建产物：$stageDir\app-*-release.apk" }

New-Item -ItemType Directory -Force -Path $apkOutDir | Out-Null
$destFiles = @()
foreach ($file in $sources) {
    $abi = $file.BaseName -replace "^app-", "" -replace "-release$", ""
    # 文件名带上 versionCode：同一版本号出多次包时，下载目录里不会互相覆盖
    $dest = Join-Path $apkOutDir "TerminalLedger-$Version-b$versionCode-$abi.apk"
    Copy-Item $file.FullName -Destination $dest -Force
    $destFiles += $dest
    Write-Host ("APK produced: {0}  ({1:N0} bytes, {2:yyyy-MM-dd HH:mm:ss})" -f $dest, (Get-Item $dest).Length, (Get-Item $dest).LastWriteTime)
}

# 额外打包 zip，方便传到手机（部分渠道限制直接传 apk）
$zipDest = Join-Path $apkOutDir "TerminalLedger-$Version-b$versionCode.zip"
if (Test-Path $zipDest) { Remove-Item $zipDest -Force }
Compress-Archive -Path $destFiles -DestinationPath $zipDest
Write-Host "ZIP produced: $zipDest"

# 产物只保留在 outputs：清掉构建目录里那一份
$gradleApkDir = Join-Path $android "app\build\outputs"
if (Test-Path -LiteralPath $gradleApkDir) { Remove-Item -LiteralPath $gradleApkDir -Recurse -Force }

Write-Host ""
Write-Host "Transfer the APK/ZIP to the phone and allow 'install from unknown sources'."
