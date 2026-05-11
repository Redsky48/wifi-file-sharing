<#
.SYNOPSIS
    Register WiFi Share as a Windows 11 share target so it appears in the
    Win+H native Share dialog.

.DESCRIPTION
    Uses a sparse-package manifest (AppxManifest.xml) to graft shell
    identity onto the existing WiFiShareTray.exe without converting it to
    a full MSIX bundle. This is Microsoft's recommended path for legacy
    Win32 apps that want UWP-style integrations.

    Requires Windows 10 22H2 / Windows 11 with Developer Mode ON
    (Settings → System → For developers → Developer Mode).

.PARAMETER ExePath
    Full path to WiFiShareTray.exe. Defaults to the installed copy in
    %LOCALAPPDATA%\WiFiShare or the project's bin\Release\dist folder.
#>
param(
    [string]$ExePath = ""
)

$ErrorActionPreference = "Stop"
# Strict mode left off intentionally — many of the registry / appx
# probes below rely on values being possibly-missing, and strict mode
# turns "missing property" into a hard parse-time error.

# ---- 1. Locate the .exe -----------------------------------------------------

if (-not $ExePath) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "WiFiShare\WiFiShareTray.exe"),
        (Join-Path $PSScriptRoot "..\bin\Release\dist\WiFiShareTray.exe")
    )
    $ExePath = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $ExePath -or -not (Test-Path $ExePath)) {
    throw "WiFiShareTray.exe not found. Pass -ExePath <full-path> or install the tray first (tray menu → Install on this PC)."
}
$ExePath = (Resolve-Path $ExePath).Path
$installDir = Split-Path -Parent $ExePath
Write-Host "Using .exe at: $ExePath"

# ---- 2. Verify Developer Mode (otherwise registration silently fails) ------

$devKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\AppModelUnlock"
$devOn = $false
try {
    $val = Get-ItemPropertyValue -Path $devKey -Name AllowDevelopmentWithoutDevLicense -ErrorAction Stop
    $devOn = ($val -eq 1)
} catch {
    $devOn = $false
}

if (-not $devOn) {
    Write-Warning "Developer Mode is OFF - registration will fail."
    Write-Warning "Open Settings -> System -> For developers -> enable Developer Mode, then re-run."
    Write-Warning "(Alternatively: sign the manifest into a .msix and Add-AppxPackage that.)"
    throw "Developer Mode required. Enable it in Settings and re-run."
}

# ---- 3. Stage manifest + assets in a temp folder ---------------------------

$packageRoot = Join-Path $PSScriptRoot ".."
$manifestSrc = Join-Path $packageRoot "Package\AppxManifest.xml"
if (-not (Test-Path $manifestSrc)) { throw "Manifest not found: $manifestSrc" }

$staging = Join-Path $env:TEMP "WiFiShare-SparsePkg"
if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
New-Item -ItemType Directory -Force -Path $staging | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $staging "Assets") | Out-Null

Copy-Item $manifestSrc (Join-Path $staging "AppxManifest.xml")

# ---- 4. Generate placeholder icons (blue squares) --------------------------

Add-Type -AssemblyName System.Drawing

function New-SolidPng($path, $size, $color = [System.Drawing.Color]::FromArgb(255, 25, 118, 210)) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.Clear($color)
        # "WS" centred
        $font = New-Object System.Drawing.Font "Segoe UI", ([Math]::Floor($size * 0.45)), ([System.Drawing.FontStyle]::Bold)
        $brush = [System.Drawing.Brushes]::White
        $sf = New-Object System.Drawing.StringFormat
        $sf.Alignment = [System.Drawing.StringAlignment]::Center
        $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
        $rect = New-Object System.Drawing.RectangleF 0, 0, $size, $size
        $g.DrawString("WS", $font, $brush, $rect, $sf)
        $font.Dispose()
        $sf.Dispose()
    } finally { $g.Dispose() }
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

New-SolidPng (Join-Path $staging "Assets\Square44x44Logo.png")    44
New-SolidPng (Join-Path $staging "Assets\Square150x150Logo.png")  150
New-SolidPng (Join-Path $staging "Assets\StoreLogo.png")          50

# ---- 5. Register the sparse package ----------------------------------------

# Remove any previous registration (idempotent re-runs)
$existing = Get-AppxPackage -Name "WiFiShare.Tray" -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Removing previous registration…"
    Remove-AppxPackage -Package $existing.PackageFullName
}

$manifestPath = Join-Path $staging "AppxManifest.xml"
Write-Host "Registering sparse package…"
Add-AppxPackage -Register $manifestPath -ExternalLocation $installDir -ForceApplicationShutdown

Write-Host ""
Write-Host "[OK] Registered. WiFi Share should now appear in the Windows Share dialog (Win+H)."
Write-Host "     To unregister: Get-AppxPackage WiFiShare.Tray | Remove-AppxPackage"
