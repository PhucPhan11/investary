# Stops what "Investary (all) - dev up" started: the backend (java on 8080), the frontend dev server
# (node on 4200) and PostgreSQL. The container and its data volume are kept. Used by the
# "Investary (all) - dev down" run configuration.
$ErrorActionPreference = 'Stop'

function Stop-Listener([int] $Port, [string] $ExpectedName, [string] $Label) {
    $pids = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    if (-not $pids) {
        Write-Output "$Label (port $Port): not running"
        return
    }
    foreach ($id in $pids) {
        $process = Get-Process -Id $id -ErrorAction SilentlyContinue
        if (-not $process) { continue }
        if ($process.ProcessName -ne $ExpectedName) {
            Write-Warning "$Label (port $Port): held by $($process.ProcessName) (PID $id), not $ExpectedName; left running"
            continue
        }
        Stop-Process -Id $id -Force
        Write-Output "$Label (port $Port): stopped $ExpectedName (PID $id)"
    }
}

Stop-Listener -Port 8080 -ExpectedName 'java' -Label 'Backend'
Stop-Listener -Port 4200 -ExpectedName 'node' -Label 'Frontend'

docker compose -f "$PSScriptRoot/docker-compose.yml" stop
exit $LASTEXITCODE
