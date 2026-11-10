param([int]$Port = 55432)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$state = Join-Path $root '.local'
$data = Join-Path $state 'postgres'
$envFile = Join-Path $state 'env.ps1'
New-Item -ItemType Directory -Path $state -Force | Out-Null

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with status $LASTEXITCODE." }
}

function New-ArenaSecret {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

if (-not (Test-Path -LiteralPath $envFile)) {
    $dbPassword = New-ArenaSecret
    $jwtSecret = New-ArenaSecret
    $judgeToken = New-ArenaSecret
    $adminPassword = (New-ArenaSecret).Substring(0, 20)
    @(
        ('$env:CODEARENA_DB_URL = ''jdbc:postgresql://127.0.0.1:{0}/codearena''' -f $Port)
        '$env:CODEARENA_DB_USER = ''codearena'''
        ('$env:CODEARENA_DB_PASSWORD = ''{0}''' -f $dbPassword)
        ('$env:CODEARENA_JWT_SECRET = ''{0}''' -f $jwtSecret)
        ('$env:CODEARENA_JUDGE_TOKEN = ''{0}''' -f $judgeToken)
        ('$env:CODEARENA_ADMIN_PASSWORD = ''{0}''' -f $adminPassword)
    ) | Set-Content -LiteralPath $envFile -Encoding utf8
}
. $envFile
$Port = ([Uri]$env:CODEARENA_DB_URL.Substring(5)).Port

if (-not (Test-Path -LiteralPath (Join-Path $data 'PG_VERSION'))) {
    $passwordFile = New-TemporaryFile
    try {
        Set-Content -LiteralPath $passwordFile.FullName -Value $env:CODEARENA_DB_PASSWORD -Encoding ascii
        Invoke-Checked 'initdb.exe' @('-D', $data, '-U', 'codearena', '-A', 'scram-sha-256',
            '--encoding=UTF8', '--locale=C', "--pwfile=$($passwordFile.FullName)")
    } finally {
        Remove-Item -LiteralPath $passwordFile.FullName -Force
    }
}

& pg_ctl.exe status -D $data *> $null
if ($LASTEXITCODE -ne 0) {
    Invoke-Checked 'pg_ctl.exe' @('start', '-D', $data, '-l', (Join-Path $state 'postgres.log'),
        '-o', "-h 127.0.0.1 -p $Port", '-w', '-t', '20')
}

$originalPassword = $env:PGPASSWORD
$env:PGPASSWORD = $env:CODEARENA_DB_PASSWORD
try {
    foreach ($database in @('codearena', 'codearena_test')) {
        $existing = Invoke-Checked 'psql.exe' @('-h', '127.0.0.1', '-p', "$Port", '-U', 'codearena',
            '-d', 'postgres', '-tA', '-c', "SELECT 1 FROM pg_database WHERE datname = '$database'")
        if ($existing -ne '1') {
            Invoke-Checked 'createdb.exe' @('-h', '127.0.0.1', '-p', "$Port", '-U', 'codearena', $database)
        }
    }
} finally {
    $env:PGPASSWORD = $originalPassword
}
Write-Output "CodeArena PostgreSQL is ready on 127.0.0.1:$Port. Load .local/env.ps1 in the API and judge terminals."
