<#
.SYNOPSIS
    Remove the sparse-package registration that exposes WiFi Share in the
    Windows Share dialog. Leaves the WiFiShareTray.exe itself untouched.
#>
$pkg = Get-AppxPackage -Name "WiFiShare.Tray" -ErrorAction SilentlyContinue
if ($pkg) {
    Remove-AppxPackage -Package $pkg.PackageFullName
    Write-Host "[OK] Unregistered."
} else {
    Write-Host "Not currently registered."
}
