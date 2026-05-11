<#
.SYNOPSIS
    Generate a multi-resolution Windows .ico file for WiFiShareTray.exe.
    Run once (or on brand changes) — the .ico is checked in.
#>
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$outPath = Join-Path $PSScriptRoot "..\icon.ico"
$sizes = @(16, 24, 32, 48, 64, 128, 256)

function New-IconBitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g   = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode      = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode  = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.TextRenderingHint  = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear([System.Drawing.Color]::Transparent)

    # Rounded blue background
    $bg = [System.Drawing.Color]::FromArgb(255, 25, 118, 210)
    $brush = New-Object System.Drawing.SolidBrush $bg
    $radius = [int]($size * 0.18)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $rect = New-Object System.Drawing.Rectangle 0, 0, $size, $size
    $d = $radius * 2
    $path.AddArc($rect.X, $rect.Y, $d, $d, 180, 90)
    $path.AddArc($rect.Right - $d, $rect.Y, $d, $d, 270, 90)
    $path.AddArc($rect.Right - $d, $rect.Bottom - $d, $d, $d, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    $g.FillPath($brush, $path)
    $brush.Dispose()
    $path.Dispose()

    # WiFi arcs (white)
    $stroke = [Math]::Max(2, [int]($size * 0.07))
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), $stroke
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap   = [System.Drawing.Drawing2D.LineCap]::Round

    # Outer arc (~70% wide, centred)
    $arcW = [int]($size * 0.70)
    $arcH = [int]($size * 0.70)
    $arcX = [int](($size - $arcW) / 2)
    $arcY = [int]($size * 0.18)
    $g.DrawArc($pen, $arcX, $arcY, $arcW, $arcH, 200, 140)

    # Inner arc
    $arcW2 = [int]($size * 0.42)
    $arcH2 = [int]($size * 0.42)
    $arcX2 = [int](($size - $arcW2) / 2)
    $arcY2 = [int]($size * 0.30)
    $g.DrawArc($pen, $arcX2, $arcY2, $arcW2, $arcH2, 200, 140)
    $pen.Dispose()

    # Up arrow (sharing indicator) bottom-centre
    $arrowW = [Math]::Max(6, [int]($size * 0.28))
    $arrowH = [Math]::Max(6, [int]($size * 0.34))
    $cx = $size / 2.0
    $top = $size * 0.52
    $points = @(
        [System.Drawing.PointF]::new($cx, $top),
        [System.Drawing.PointF]::new($cx - $arrowW / 2, $top + $arrowH * 0.45),
        [System.Drawing.PointF]::new($cx - $arrowW / 6, $top + $arrowH * 0.45),
        [System.Drawing.PointF]::new($cx - $arrowW / 6, $top + $arrowH),
        [System.Drawing.PointF]::new($cx + $arrowW / 6, $top + $arrowH),
        [System.Drawing.PointF]::new($cx + $arrowW / 6, $top + $arrowH * 0.45),
        [System.Drawing.PointF]::new($cx + $arrowW / 2, $top + $arrowH * 0.45)
    )
    $g.FillPolygon([System.Drawing.Brushes]::White, $points)

    $g.Dispose()
    return $bmp
}

# Build multi-image ICO file manually so each resolution is its own image.
function Write-Ico($path, $bitmaps) {
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter $ms

    # ICONDIR header
    $bw.Write([uint16]0)             # reserved
    $bw.Write([uint16]1)             # type: 1 = icon
    $bw.Write([uint16]$bitmaps.Count)

    # Pre-encode each bitmap to PNG bytes
    $encoded = @()
    foreach ($bmp in $bitmaps) {
        $pngMs = New-Object System.IO.MemoryStream
        $bmp.Save($pngMs, [System.Drawing.Imaging.ImageFormat]::Png)
        $encoded += ,$pngMs.ToArray()
    }

    $offset = 6 + ($bitmaps.Count * 16)
    foreach ($i in 0..($bitmaps.Count - 1)) {
        $bmp = $bitmaps[$i]
        $dataLen = $encoded[$i].Length
        $w = $bmp.Width; $h = $bmp.Height
        $bw.Write([byte]($(if ($w -ge 256) { 0 } else { $w })))   # width 0 means 256
        $bw.Write([byte]($(if ($h -ge 256) { 0 } else { $h })))
        $bw.Write([byte]0)                # color count (0 for >256)
        $bw.Write([byte]0)                # reserved
        $bw.Write([uint16]1)              # planes
        $bw.Write([uint16]32)             # bit depth
        $bw.Write([uint32]$dataLen)       # image data length
        $bw.Write([uint32]$offset)        # image data offset
        $offset += $dataLen
    }

    foreach ($bytes in $encoded) { $bw.Write($bytes) }
    $bw.Flush()

    [System.IO.File]::WriteAllBytes($path, $ms.ToArray())
    $ms.Dispose()
}

$bitmaps = $sizes | ForEach-Object { New-IconBitmap $_ }
Write-Ico $outPath $bitmaps
foreach ($b in $bitmaps) { $b.Dispose() }

Write-Host "Wrote $outPath ($([Math]::Round((Get-Item $outPath).Length / 1KB, 1)) KB)"
