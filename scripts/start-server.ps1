# Start the NABI server on this PC with Docker Desktop (Windows PowerShell).
#   powershell -ExecutionPolicy Bypass -File scripts\start-server.ps1
# Creates .env with random secrets on first run, starts PostgreSQL + LLM server (mock) + backend,
# applies migrations, seeds the intervention catalog and waits until http://127.0.0.1:8000 answers.
# The Android emulator reaches it at http://10.0.2.2:8000 (the app's fixed debug address).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker is not installed or not on PATH. Install Docker Desktop, start it, then run this again."
}
docker info *> $null
if ($LASTEXITCODE -ne 0) { Write-Error "Docker Desktop is not running. Start it and wait until it says 'Engine running'." }

if (-not (Test-Path .env)) {
    function New-Secret([int]$bytes) {
        $b = New-Object byte[] $bytes
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
        return ([Convert]::ToBase64String($b) -replace '[+/=]', '')
    }
    (Get-Content .env.example -Raw) `
        -replace 'replace_with_strong_password', (New-Secret 24) `
        -replace 'replace_with_long_random_secret', (New-Secret 48) `
        -replace 'replace_with_long_random_internal_token', (New-Secret 40) |
        Set-Content .env -NoNewline -Encoding ascii
    Write-Host "Created .env with random secrets."
}

Write-Host "Starting database and LLM server..."
docker compose up -d --build db llm-server
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Applying database migrations..."
docker compose run --rm backend alembic upgrade head
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Seeding the intervention catalog..."
docker compose run --rm backend python -m app.seed.seed_interventions
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Starting backend..."
docker compose up -d --build backend
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

for ($i = 0; $i -lt 30; $i++) {
    try {
        $h = Invoke-RestMethod -Uri "http://127.0.0.1:8000/api/v1/health" -TimeoutSec 3
        Write-Host ("Server is up: status={0}, database={1}, llm={2}" -f $h.status, $h.database, $h.llm)
        Write-Host "Emulator app address: http://10.0.2.2:8000   |   Stop: docker compose down"
        exit 0
    } catch { Start-Sleep -Seconds 2 }
}
Write-Error "Backend did not answer on http://127.0.0.1:8000. Check: docker compose logs backend"
