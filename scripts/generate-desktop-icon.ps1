<#
.SYNOPSIS
Generate the Windows app icon (app.ico) from the repo root icon.png.

.DESCRIPTION
jpackage on Windows requires the --icon file to be in .ico format.
This script scales the repo root icon.png (1254x1254) into multiple PNG
frames and assembles a standard multi-size ICO (256/128/64/48/32/16),
writing it to desktop\app.ico. The file is used by build-desktop-installer.ps1
via jpackage --icon.

.PARAMETER IconPng
Source PNG path. Defaults to the repo root icon.png.

.PARAMETER OutIco
Output .ico path. Defaults to desktop\app.ico.

.EXAMPLE
.\scripts\generate-desktop-icon.ps1
#>
param(
    [string]$IconPng,
    [string]$OutIco
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $IconPng) { $IconPng = Join-Path $root "icon.png" }
if (-not $OutIco)  { $OutIco  = Join-Path $root "desktop\app.ico" }

if (-not (Test-Path -LiteralPath $IconPng)) {
    throw "Source icon not found: $IconPng"
}

Add-Type -AssemblyName System.Drawing

function New-IcoFromPng {
    param([string]$PngPath, [string]$IcoPath, [int[]]$Sizes = @(256,128,64,48,32,16))
    $src = [System.Drawing.Image]::FromFile($PngPath)
    try {
        $frames = @()
        foreach ($s in $Sizes) {
            $bmp = New-Object System.Drawing.Bitmap($s, $s)
            $g = [System.Drawing.Graphics]::FromImage($bmp)
            $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
            $g.Clear([System.Drawing.Color]::Transparent)
            $g.DrawImage($src, 0, 0, $s, $s)
            $g.Dispose()
            $ms = New-Object System.IO.MemoryStream
            $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
            $frames += ,@($s, $ms.ToArray())
            $ms.Dispose(); $bmp.Dispose()
        }
    } finally {
        $src.Dispose()
    }

    $count = $frames.Count
    $out = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($out)
    $bw.Write([uint16]0)          # reserved
    $bw.Write([uint16]1)          # type = icon
    $bw.Write([uint16]$count)     # image count

    $offset = 6 + (16 * $count)
    foreach ($f in $frames) {
        $s = $f[0]; $data = $f[1]
        $dim = if ($s -ge 256) { 0 } else { $s }
        $bw.Write([byte]$dim)          # width
        $bw.Write([byte]$dim)          # height
        $bw.Write([byte]0)             # color count
        $bw.Write([byte]0)             # reserved
        $bw.Write([uint16]1)           # planes
        $bw.Write([uint16]32)          # bpp
        $bw.Write([uint32]$data.Length)
        $bw.Write([uint32]$offset)
        $offset += $data.Length
    }
    foreach ($f in $frames) { $bw.Write($f[1]) }
    $bw.Flush()
    [System.IO.File]::WriteAllBytes($IcoPath, $out.ToArray())
    $bw.Dispose(); $out.Dispose()
}

$dir = Split-Path -Parent $OutIco
if ($dir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
New-IcoFromPng -PngPath $IconPng -IcoPath $OutIco
Write-Host "ICO written: $OutIco"
