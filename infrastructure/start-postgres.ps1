# Starts PostgreSQL and waits until it is healthy. Used by the "Postgres (docker compose)" run
# configuration, which is a before-launch task of the backend configurations.
$ErrorActionPreference = 'Stop'
docker compose -f "$PSScriptRoot/docker-compose.yml" up -d --wait
exit $LASTEXITCODE
