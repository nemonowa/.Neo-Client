# .Neo-Client installer (ASCII only: safe for Windows PowerShell 5.1)
$ErrorActionPreference = 'Stop'
$mc   = Join-Path $env:APPDATA '.minecraft'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$expectSha1 = '72bec4192ab52aef957b934e6030c241f5049e4b'
$expectSize = 22740

if (-not (Test-Path $mc)) { throw ".minecraft not found: $mc" }
if (Get-Process -Name 'MinecraftLauncher' -ErrorAction SilentlyContinue) {
  throw 'Minecraft Launcher is running. Close it completely and run again.'
}
$src = Join-Path $here 'neoclient.jar'
$h = (Get-FileHash $src -Algorithm SHA1).Hash.ToLower()
if ($h -ne $expectSha1 -or (Get-Item $src).Length -ne $expectSize) { throw "neoclient.jar is corrupted (sha1=$h)" }

$vdir = Join-Path $mc 'versions\.Neo-Client'
$ldir = Join-Path $mc 'libraries\neo'
New-Item -ItemType Directory -Force $vdir, $ldir | Out-Null
Copy-Item (Join-Path $here '.Neo-Client.json') (Join-Path $vdir '.Neo-Client.json') -Force
Copy-Item $src (Join-Path $ldir 'neoclient.jar') -Force

# move old layouts aside (not deleted)
foreach ($old in @('libraries\com\neo\neoclient','libraries\com\neo\neo','libraries\com\example\neoclient')) {
  $p = Join-Path $mc $old
  if (Test-Path $p) {
    $dest = $p + '.old'
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    Rename-Item $p $dest
    Write-Host "moved aside: $p"
  }
}

$jsonOk = Test-Path (Join-Path $vdir '.Neo-Client.json')
$jarOk  = (Get-FileHash (Join-Path $ldir 'neoclient.jar') -Algorithm SHA1).Hash.ToLower() -eq $expectSha1
if ($jsonOk -and $jarOk) {
  Write-Host "OK: $vdir\.Neo-Client.json"
  Write-Host "OK: $ldir\neoclient.jar"
} else {
  throw 'verification failed'
}
