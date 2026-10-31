# .Neo-Client build script (ASCII only: safe for Windows PowerShell 5.1)
# Steps: compile -> test -> jar -> update sha1/size in .Neo-Client.json -> release files
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Find-Tool([string]$name) {
  if ($env:JAVA_HOME) {
    $p = Join-Path $env:JAVA_HOME "bin\$name.exe"
    if (Test-Path $p) { return $p }
  }
  $c = Get-Command $name -ErrorAction SilentlyContinue
  if ($c) { return $c.Source }
  throw "$name not found. Install JDK 21 or later and set JAVA_HOME or PATH."
}
# Native commands may write to stderr (for example "Picked up JAVA_TOOL_OPTIONS").
# Windows PowerShell 5.1 turns that into an error under 'Stop', so relax it only around the call.
function Invoke-Native([string]$exe, [string[]]$arguments) {
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $text = (& $exe @arguments 2>&1 | ForEach-Object { "$_" } | Out-String)
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $prev
  }
  return @{ Code = $code; Text = $text }
}
function Run([string]$exe, [string[]]$arguments) {
  $r = Invoke-Native $exe $arguments
  if ($r.Code -ne 0) { Write-Host $r.Text; throw "failed: $exe $($arguments -join ' ') (exit $($r.Code))" }
}

$javac = Find-Tool 'javac'
$jar   = Find-Tool 'jar'
$java  = Find-Tool 'java'

# JDK version check (21 or later)
$verText = ((Invoke-Native $javac @('-version')).Text -split "`n" | Where-Object { $_ -match 'javac' } | Select-Object -First 1).Trim()
if (-not ($verText -match 'javac\s+(\d+)')) { throw "cannot read javac version: $verText" }
if ([int]$Matches[1] -lt 21) { throw "JDK 21 or later is required (found: $verText)" }
Write-Host "using $verText"

$out    = Join-Path $root 'build\classes'
$tout   = Join-Path $root 'build\tester'
$tmpdir = Join-Path $root 'build\testroot'
if (Test-Path (Join-Path $root 'build')) { Remove-Item (Join-Path $root 'build') -Recurse -Force }
New-Item -ItemType Directory -Force $out, $tout, $tmpdir | Out-Null

# 1) compile
$srcList = Join-Path $root 'build\sources.txt'
Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object { '"' + ($_.FullName -replace '\\','/') + '"' } | Set-Content $srcList -Encoding ASCII
Run $javac @('--release','21','-encoding','UTF-8','-d',$out,"@$srcList")
Write-Host 'compile: OK'

# 2) test (a failure stops the build before the jar is made)
if (-not $SkipTests) {
  $tList = Join-Path $root 'build\tester-sources.txt'
  Get-ChildItem (Join-Path $root 'tester') -Recurse -Filter *.java | ForEach-Object { '"' + ($_.FullName -replace '\\','/') + '"' } | Set-Content $tList -Encoding ASCII
  Run $javac @('-encoding','UTF-8','-cp',$out,'-d',$tout,"@$tList")
  $cp = "$out;$tout"
  foreach ($cls in @('neo.client.setup.ModSetupTester','neo.client.loader.NeoClientTester')) {
    $r = Invoke-Native $java @("-Dtest.root=$tmpdir", '-cp', $cp, $cls)
    $text = $r.Text
    if ($r.Code -ne 0 -or $text -notmatch 'ALL PASS') {
      Write-Host $text
      throw "test failed: $cls"
    }
    $passLine = ($text -split "`n" | Where-Object { $_ -match 'ALL PASS' } | Select-Object -Last 1).Trim()
    Write-Host "test: $cls -> $passLine"
  }
} else {
  Write-Host 'test: skipped'
}

# 3) jar (fixed timestamp so the same source gives the same jar)
$jarPath = Join-Path $root 'neoclient.jar'
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
Run $jar @('--create','--file',$jarPath,'--date=2026-01-01T00:00:00Z','-C',$out,'neo')
$sha1 = (Get-FileHash $jarPath -Algorithm SHA1).Hash.ToLower()
$size = (Get-Item $jarPath).Length
Write-Host "jar: neoclient.jar sha1=$sha1 size=$size"

# 4) write sha1/size into the JSON and the installer (no BOM, line endings kept)
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Update-File([string]$path, [string]$pattern, [string]$replacement) {
  $text = [System.IO.File]::ReadAllText($path, $utf8)
  $re = New-Object System.Text.RegularExpressions.Regex($pattern, 'Singleline')
  if (-not $re.IsMatch($text)) { throw "pattern not found in ${path}" }
  [System.IO.File]::WriteAllText($path, $re.Replace($text, $replacement, 1), $utf8)
}
Update-File (Join-Path $root '.Neo-Client.json') '("artifact"\s*:\s*\{.*?"sha1"\s*:\s*")[0-9a-fA-F]{40}(".*?"size"\s*:\s*)\d+' ('${1}' + $sha1 + '${2}' + $size)
Write-Host 'updated: .Neo-Client.json'

# 5) release files in admin\release: Neo-Client.zip (the .Neo-Client folder + installers + README) and Neo-Client.json (for self-update)
$rel = Join-Path $root 'admin\release'
New-Item -ItemType Directory -Force $rel | Out-Null
Copy-Item (Join-Path $root '.Neo-Client.json') (Join-Path $rel 'Neo-Client.json') -Force
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zipPath = Join-Path $rel 'Neo-Client.zip'
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
$zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')
try {
  # entry names use '/' (Compress-Archive in PowerShell 5.1 writes '\', which breaks on macOS / Linux)
  foreach ($e in @(@('.Neo-Client.json', '.Neo-Client/.Neo-Client.json'), @('install.cmd', 'install.cmd'), @('install.sh', 'install.sh'), @('README.md', 'README.md'))) {
    $entry = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $root $e[0]), $e[1])
    if ($e[1] -like '*.sh') { $entry.ExternalAttributes = [int](0x81ED -shl 16) }
  }
} finally {
  $zip.Dispose()
}
Write-Host 'release: admin\release\Neo-Client.zip, admin\release\Neo-Client.json'

Remove-Item (Join-Path $root 'build') -Recurse -Force
Write-Host ''
Write-Host "DONE  neoclient.jar  sha1=$sha1  size=$size"
Write-Host 'Next: upload neoclient.jar first, then admin\release\Neo-Client.json and Neo-Client.zip, to the same Release.'
