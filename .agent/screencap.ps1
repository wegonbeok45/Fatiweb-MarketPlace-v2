param(
  [Parameter(Mandatory=$true)][string]$Name,
  [string]$Serial = "emulator-5554",
  [int]$Quality = 80,
  [int]$MaxWidth = 720
)

$ErrorActionPreference = "Stop"
$env:PATH = "C:\Users\ta\AppData\Local\Android\Sdk\platform-tools;$env:PATH"

$dir = Join-Path $PSScriptRoot "screens"
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$tmpPng = Join-Path $env:TEMP "screencap_$([guid]::NewGuid().ToString('N')).png"
$outJpg = Join-Path $dir ("{0}.jpg" -f $Name)

# Pull screencap as raw PNG bytes (binary-safe)
$bytes = & adb -s $Serial exec-out screencap -p | ForEach-Object { $_ }
# The above won't be binary-safe in PowerShell pipelines, so use cmd redirect instead:
& cmd /c "adb -s $Serial exec-out screencap -p > `"$tmpPng`""

if (-not (Test-Path $tmpPng) -or (Get-Item $tmpPng).Length -lt 1024) {
  throw "Screencap failed (file missing or too small): $tmpPng"
}

Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile($tmpPng)
try {
  $w = $src.Width; $h = $src.Height
  if ($w -gt $MaxWidth) {
    $scale = $MaxWidth / [double]$w
    $nw = $MaxWidth; $nh = [int][Math]::Round($h * $scale)
    $dst = New-Object System.Drawing.Bitmap($nw, $nh)
    $g = [System.Drawing.Graphics]::FromImage($dst)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($src, 0, 0, $nw, $nh)
    $g.Dispose()
  } else {
    $dst = New-Object System.Drawing.Bitmap($src)
  }

  $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq "image/jpeg" }
  $params = New-Object System.Drawing.Imaging.EncoderParameters(1)
  $params.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]$Quality)
  $dst.Save($outJpg, $codec, $params)
  $dst.Dispose()
} finally {
  $src.Dispose()
  Remove-Item $tmpPng -ErrorAction SilentlyContinue
}

$sizeKb = [math]::Round((Get-Item $outJpg).Length/1KB, 1)
Write-Output "OK $outJpg ($sizeKb KB)"
