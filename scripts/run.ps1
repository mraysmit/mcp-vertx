# ─────────────────────────────────────────────────────────────────
# run.ps1 — Load .env and start the agent application
# ─────────────────────────────────────────────────────────────────
# Usage:
#   .\scripts\run.ps1            # build + run
#   .\scripts\run.ps1 -SkipBuild # run without rebuilding
# ─────────────────────────────────────────────────────────────────

param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

# ── UTF-8 console encoding (prevents ? for Unicode chars) ────────
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
$OutputEncoding           = [System.Text.Encoding]::UTF8
chcp 65001 | Out-Null

# ── Load .env file ───────────────────────────────────────────────
$envFile = Join-Path $projectRoot ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line -match '^([^=]+)=(.*)$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), "Process")
            Write-Host "  Loaded: $($Matches[1].Trim())" -ForegroundColor DarkGray
        }
    }
    Write-Host "Environment loaded from .env" -ForegroundColor Green
} else {
    Write-Warning ".env file not found at $envFile — OPENAI_API_KEY may not be set"
}

# ── Verify required env vars ────────────────────────────────────
if (-not $env:OPENAI_API_KEY) {
    Write-Error "OPENAI_API_KEY is not set. Add it to $envFile or set it manually."
    exit 1
}
Write-Host "OPENAI_API_KEY is set (length: $($env:OPENAI_API_KEY.Length))" -ForegroundColor Green

# ── Kill any previous instances on our ports ─────────────────────
$ports = @(8080, 8081, 8082, 3001)
foreach ($port in $ports) {
    $conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
    if ($conn) {
        $conn | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
            Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
        }
        Write-Host "  Freed port $port" -ForegroundColor Yellow
    }
}

# ── Build (unless -SkipBuild) ────────────────────────────────────
Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        Write-Host "`nBuilding project..." -ForegroundColor Cyan
        mvn install -DskipTests
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Build failed"
            exit 1
        }
    }

    # ── Run the application ──────────────────────────────────────
    Write-Host "`nStarting agent application..." -ForegroundColor Cyan
    Write-Host "  API:        http://localhost:8080"
    Write-Host "  Pipeline UI: http://localhost:8081"
    Write-Host "  Workflow UI: http://localhost:8082/workflow"
    Write-Host "  MCP Server:  http://localhost:3001"
    Write-Host ""
    mvn exec:java "-Dexec.mainClass=dev.mars.agent.Main" "-Dexec.jvmArgs=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8" -pl agent-app
} finally {
    Pop-Location
}
