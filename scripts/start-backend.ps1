# ============================================================
#  start-backend.ps1 - 快速启动后端（一键起服务）
# ------------------------------------------------------------
#  用法（在仓库根目录或 scripts 目录执行）：
#    .\scripts\start-backend.ps1                默认开发模式：H2 嵌入式库，无需 MySQL/Docker
#    .\scripts\start-backend.ps1 -Docker        Docker Compose 启动 MySQL 8 + 后端（生产同构）
#    .\scripts\start-backend.ps1 -Foreground    开发模式前台运行，实时看日志（Ctrl+C 停止）
#    .\scripts\start-backend.ps1 -NoWait        启动后不等待健康检查
#    .\scripts\start-backend.ps1 -Stop          停止（Docker: compose down；开发: 结束进程）
#  双击 scripts\start-backend.bat 亦可启动（默认开发模式）。
# ============================================================
param(
    [switch]$Docker,
    [switch]$Foreground,
    [switch]$NoWait,
    [switch]$Stop
)

$ErrorActionPreference = "Stop"

$root    = Split-Path $PSScriptRoot -Parent
$backend = Join-Path $root "backend"
$logDir  = Join-Path $backend "logs"
$pidFile = Join-Path $backend ".backend.pid"
$health  = "http://localhost:8080/api/health"
$port    = 8080

# PS 5.1 下原生命令写 stderr 会变成终止性错误；需要执行原生命令时临时放开。
# -Silent 时丢弃该命令的全部输出（只保留退出码）。
function Invoke-NativeSafe {
    param([string]$FilePath, [string[]]$ArgumentList, [switch]$Silent)
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($ArgumentList.Count -gt 0) {
            if ($Silent) { & $FilePath @ArgumentList *> $null } else { & $FilePath @ArgumentList }
        } else {
            if ($Silent) { & $FilePath *> $null } else { & $FilePath }
        }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
}

function Test-PortBusy {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Healthy {
    param([int]$TimeoutSec, [int]$CheckProc)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($CheckProc -gt 0 -and -not (Get-Process -Id $CheckProc -ErrorAction SilentlyContinue)) {
            Write-Host "[wait] 进程已退出，启动失败。" -ForegroundColor Yellow
            return $false
        }
        try {
            $r = Invoke-WebRequest -Uri $health -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Write-Summary {
    Write-Host ""
    Write-Host "================ 后端已就绪 ================" -ForegroundColor Green
    Write-Host "  API 基址     : http://localhost:$port/api"
    Write-Host "  健康检查     : $health"
    Write-Host "  接口文档     : docs\API.md / docs\SYNC-PROTOCOL.md"
    Write-Host "--------------------------------------------"
    Write-Host "  桌面端地址   : http://localhost:$port  (默认，无需改)"
    Write-Host "  Android 真机 : http://192.168.43.84:$port  (App 设置 -> 云端地址)"
    Write-Host "  Android 模拟器: http://10.0.2.2:$port"
    Write-Host "============================================" -ForegroundColor Green
    Write-Host "  停止: .\scripts\start-backend.ps1 -Stop"
    if (-not $Docker) { Write-Host "  日志: backend\logs\backend.log" }
    else { Write-Host "  日志: docker compose logs -f app" }
    Write-Host ""
}

# ---------------- 停止 ----------------
if ($Stop) {
    if ($Docker) {
        Write-Host "[stop] docker compose down ..."
        Push-Location $root
        try { Invoke-NativeSafe -FilePath "docker" -ArgumentList @("compose", "down") | Out-Null } finally { Pop-Location }
        Write-Host "[stop] 完成。"
        exit 0
    }
    if (Test-Path $pidFile) {
        $procId = (Get-Content $pidFile | Select-Object -First 1).Trim()
        if ($procId -match '^\d+$') {
            Write-Host "[stop] 结束开发进程 (PID $procId) ..."
            cmd /c "taskkill /PID $procId /T /F >nul 2>&1"
        }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        Write-Host "[stop] 结束占用 $port 端口的进程 ..."
        $conn | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
    }
    Write-Host "[stop] 完成。"
    exit 0
}

# ---------------- Docker Compose 模式 ----------------
if ($Docker) {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "未找到 docker。请安装 Docker Desktop，或改用默认开发模式（H2，无需 Docker）。"
    }
    $code = Invoke-NativeSafe -FilePath "docker" -ArgumentList @("info") -Silent
    if ($code -ne 0) {
        throw "Docker 守护进程未运行。请先启动 Docker Desktop，或改用默认开发模式。"
    }
    $envFile = Join-Path $root ".env"
    if (-not (Test-Path $envFile)) {
        Copy-Item (Join-Path $root ".env.example") $envFile
        Write-Host "[docker] 已从 .env.example 生成 .env（默认 account / account_pass，可自行修改）"
    }
    if (Test-PortBusy) { Write-Warning "8080 端口已被占用，可能已有后端在运行。" }
    Write-Host "[docker] 构建并启动 MySQL + 后端（首次构建需下载镜像与依赖，较慢）..."
    Push-Location $root
    try { Invoke-NativeSafe -FilePath "docker" -ArgumentList @("compose", "up", "-d", "--build") | Out-Null } finally { Pop-Location }
    if ($NoWait) {
        Write-Host "[docker] 容器已后台启动（未等待健康检查）。查看日志: docker compose logs -f app"
        exit 0
    }
    Write-Host "[docker] 等待健康检查（首次最多 300 秒）..."
    if (-not (Wait-Healthy -TimeoutSec 300 -CheckProc 0)) {
        Write-Host "[docker] 健康检查超时。查看: docker compose logs app" -ForegroundColor Yellow
        exit 1
    }
    Write-Summary
    exit 0
}

# ---------------- 开发模式（H2，默认） ----------------
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "未找到 mvn。请安装 Maven 3.8+ 并加入 PATH，或改用 -Docker 模式。"
}

# 清理失效的 pidfile（进程已退出但文件残留）
if (Test-Path $pidFile) {
    $stale = (Get-Content $pidFile | Select-Object -First 1).Trim()
    if ($stale -match '^\d+$' -and -not (Get-Process -Id ([int]$stale) -ErrorAction SilentlyContinue)) {
        Write-Host "[dev] 清理失效的 pidfile（旧进程 PID $stale 已退出）"
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }
}

$settings = Join-Path $root "maven-settings-local.xml"
$mvnArgs = @()
if (Test-Path $settings) {
    $mvnArgs += @("-s", $settings)
    Write-Host "[dev] 使用 maven-settings-local.xml 镜像配置"
}
$mvnArgs += @("spring-boot:run", "-Dspring-boot.run.profiles=dev")

if ($Foreground) {
    Write-Host "[dev] 前台启动，Ctrl+C 停止。数据库: backend/accounting-dev (H2)"
    Push-Location $backend
    try {
        $mvnExe = (Get-Command mvn).Source
        Invoke-NativeSafe -FilePath $mvnExe -ArgumentList $mvnArgs
    } finally { Pop-Location }
    exit 0
}

if (Test-PortBusy) {
    Write-Warning "8080 端口已被占用，可能已有后端在运行；如需重启请先执行 -Stop。"
}
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$outLog = Join-Path $logDir "backend.log"
$errLog = Join-Path $logDir "backend.err.log"
Write-Host "[dev] 后台启动（H2 数据库，无需 MySQL）..."
Write-Host "[dev] 日志: $outLog"
$proc = Start-Process -FilePath (Get-Command mvn).Source -ArgumentList $mvnArgs -WorkingDirectory $backend -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
$proc.Id | Set-Content -Path $pidFile
Write-Host "[dev] 进程 PID: $($proc.Id)"

if ($NoWait) {
    Write-Host ""
    Write-Host "=========== 后端已后台启动（未等健康检查） ===========" -ForegroundColor Green
    Write-Host "  日志: $outLog"
    Write-Host "  健康检查: $health"
    Write-Host "  停止: .\scripts\start-backend.ps1 -Stop"
    Write-Host "==================================================" -ForegroundColor Green
    exit 0
}

Write-Host "[dev] 等待健康检查（首次需下载依赖，最多 240 秒）..."
if (-not (Wait-Healthy -TimeoutSec 240 -CheckProc $proc.Id)) {
    Write-Host "[dev] 后端未能就绪，最近日志：" -ForegroundColor Yellow
    if (Test-Path $errLog) { Get-Content $errLog -Tail 15 }
    if (Test-Path $outLog) { Get-Content $outLog -Tail 10 }
    exit 1
}
Write-Summary
