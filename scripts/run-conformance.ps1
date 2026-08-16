[CmdletBinding()]
param(
  [string[]]$Scenario = @(
    'tools-list',
    'tools-call-simple-text',
    'tools-call-image',
    'tools-call-audio',
    'tools-call-embedded-resource',
    'tools-call-mixed-content',
    'tools-call-error',
    'input-required-result-basic-elicitation',
    'input-required-result-basic-sampling',
    'input-required-result-basic-list-roots',
    'input-required-result-request-state',
    'input-required-result-multiple-input-requests',
    'input-required-result-multi-round',
    'input-required-result-capability-check',
    'dns-rebinding-protection'
  ),
  [int]$Port = 3001,
  [switch]$Requirements
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repository = Split-Path -Parent $PSScriptRoot
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logDirectory = Join-Path $repository 'logs'
$resultDirectory = Join-Path $logDirectory "conformance-results-$timestamp"
$conformanceLog = Join-Path $logDirectory "conformance-$timestamp.log"
$serverLog = Join-Path $logDirectory "conformance-server-$timestamp.log"
$serverErrorLog = Join-Path $logDirectory "conformance-server-error-$timestamp.log"
$classpathFile = Join-Path $repository 'target\conformance-classpath.txt'
$server = $null
$failed = $false

New-Item -ItemType Directory -Force -Path $logDirectory, $resultDirectory | Out-Null
Push-Location $repository
try {
  & mvn test-compile dependency:build-classpath '-DincludeScope=test' `
    '-Dmdep.outputFile=target/conformance-classpath.txt'
  if ($LASTEXITCODE -ne 0) {
    throw "Unable to compile the fixture launcher (Maven exit $LASTEXITCODE)"
  }

  $dependencies = (Get-Content -Raw $classpathFile).Trim()
  $separator = [IO.Path]::PathSeparator
  $classpath = (Join-Path $repository 'target\test-classes') + $separator +
      (Join-Path $repository 'target\classes') + $separator + $dependencies
  $java = (Get-Command java -ErrorAction Stop).Source
  $server = Start-Process -FilePath $java -ArgumentList @(
      "-Dmcp.port=$Port", '-cp', $classpath, 'dev.mars.mcp.ConformanceMain'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $serverLog `
      -RedirectStandardError $serverErrorLog

  $ready = $false
  for ($attempt = 0; $attempt -lt 120 -and -not $ready; $attempt++) {
    if ($server.HasExited) {
      throw "Conformance fixture exited early with code $($server.ExitCode)"
    }
    try {
      $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 1 `
          -Uri "http://127.0.0.1:$Port/health/ready"
      $ready = $response.StatusCode -eq 200
    } catch {
      Start-Sleep -Milliseconds 250
    }
  }
  if (-not $ready) {
    throw "Conformance fixture did not become ready on port $Port"
  }

  $baseArguments = @(
    '-y', '@modelcontextprotocol/conformance@0.2.0-alpha.11', 'server',
    '--url', "http://127.0.0.1:$Port/mcp"
  )
  if ($Requirements) {
    $arguments = $baseArguments + @(
      '--requirements', '2026-07-28', '-o', $resultDirectory
    )
    & npx @arguments 2>&1 | Tee-Object -FilePath $conformanceLog -Append
    $failed = $LASTEXITCODE -ne 0
  } else {
    foreach ($name in $Scenario) {
      $scenarioResult = Join-Path $resultDirectory $name
      $arguments = $baseArguments + @(
        '--scenario', $name, '--spec-version', '2026-07-28', '-o', $scenarioResult
      )
      & npx @arguments 2>&1 | Tee-Object -FilePath $conformanceLog -Append
      if ($LASTEXITCODE -ne 0) { $failed = $true }
    }
  }
} finally {
  if ($null -ne $server -and -not $server.HasExited) {
    Stop-Process -Id $server.Id -Force
    $server.WaitForExit()
  }
  Pop-Location
}

Write-Host "Conformance log: $conformanceLog"
Write-Host "Server log:      $serverLog"
Write-Host "Results:         $resultDirectory"
if ($failed) { throw 'One or more conformance scenarios failed' }
