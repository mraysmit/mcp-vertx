# ─────────────────────────────────────────────────────────────────
# shutdown.ps1 — Kill all vertx5-mcp services by port
# ─────────────────────────────────────────────────────────────────
# Usage:
#   .\scripts\shutdown.ps1
# ─────────────────────────────────────────────────────────────────

$ports = @(8080, 8081, 8082, 3001)

foreach ($port in $ports) {
    $conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
    if ($conn) {
        $pids = $conn | Select-Object -ExpandProperty OwningProcess -Unique
        foreach ($procId in $pids) {
            $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            Write-Host "  Stopped PID $procId ($($proc.ProcessName)) on port $port" -ForegroundColor Yellow
        }
    } else {
        Write-Host "  Port $port — nothing running" -ForegroundColor DarkGray
    }
}

Write-Host "`nAll services stopped." -ForegroundColor Green
