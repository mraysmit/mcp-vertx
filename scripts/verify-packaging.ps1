[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repository = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $repository 'logs'
$firstLog = Join-Path $logDirectory 'packaging-clean-package.log'
$secondLog = Join-Path $logDirectory 'packaging-repeat-package.log'
$artifact = Join-Path $repository 'target\mcp-vertx-0.3.0-SNAPSHOT.jar'

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

function Invoke-MavenPackage([string[]]$Arguments, [string]$LogFile) {
  & mvn --batch-mode --no-transfer-progress @Arguments 2>&1 |
      Tee-Object -FilePath $LogFile
  if ($LASTEXITCODE -ne 0) {
    throw "Maven packaging failed with exit code $LASTEXITCODE"
  }
}

Push-Location $repository
try {
  Invoke-MavenPackage @('clean', 'package', '-DskipTests') $firstLog
  Invoke-MavenPackage @('package', '-DskipTests') $secondLog

  $repeatOutput = Get-Content -Raw -LiteralPath $secondLog
  $applicationOverlap = '(?m)^\[WARNING\] (?:mcp-vertx-[^,\r\n]+, [^,\r\n]+' +
      '|[^,\r\n]+, mcp-vertx-[^,\r\n]+) define \d+ overlapping class'
  if ($repeatOutput -match $applicationOverlap) {
    throw 'Repeated packaging shaded the existing application JAR into itself'
  }

  $entries = & jar tf $artifact
  if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the shaded JAR' }
  if ($entries -match '(^|/)module-info\.class$') {
    throw 'Executable classpath JAR must not contain dependency module descriptors'
  }

  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $archive = [IO.Compression.ZipFile]::OpenRead($artifact)
  try {
    $manifestEntry = $archive.GetEntry('META-INF/MANIFEST.MF')
    if ($null -eq $manifestEntry) { throw 'Shaded JAR does not contain a manifest' }
    $reader = [IO.StreamReader]::new($manifestEntry.Open())
    try { $manifestContent = $reader.ReadToEnd() } finally { $reader.Dispose() }
  } finally {
    $archive.Dispose()
  }
  if ($manifestContent -notmatch '(?m)^Main-Class: dev\.mars\.mcp\.Main\s*$') {
    throw 'Shaded JAR manifest does not declare dev.mars.mcp.Main'
  }

  Write-Host 'Packaging idempotency and executable-JAR structure PASS'
  Write-Host "Clean package log:  $firstLog"
  Write-Host "Repeat package log: $secondLog"
} finally { Pop-Location }
