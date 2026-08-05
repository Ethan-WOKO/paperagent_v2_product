[CmdletBinding()]
param(
    [switch]$StartBackend
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$configPath = Join-Path $repoRoot '.env.sandbox.local'
$brokerPort = 8091
$runtimeRoot = Join-Path $env:LOCALAPPDATA 'Yanban\E2bSandboxBroker'
$providerRuntimeRoot = Join-Path $runtimeRoot 'provider-python'
$providerPython = Join-Path $providerRuntimeRoot 'Scripts\python.exe'
$brokerJar = Join-Path $repoRoot 'yanban-sandbox-broker\target\yanban-sandbox-broker-0.1.0-SNAPSHOT.jar'
$providerHelper = Join-Path $repoRoot 'deploy\sandbox\e2b\e2b_provider.py'
$providerRequirements = Join-Path $repoRoot 'deploy\sandbox\e2b\requirements.txt'
$stdoutLog = Join-Path $runtimeRoot 'broker.out.log'
$stderrLog = Join-Path $runtimeRoot 'broker.err.log'
$backendStdoutLog = Join-Path $runtimeRoot 'backend.out.log'
$backendStderrLog = Join-Path $runtimeRoot 'backend.err.log'

if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Missing $configPath. Copy deploy/sandbox/e2b/sandbox-broker.local.env.example and fill the local secrets first."
}

$settings = @{}
Get-Content -LiteralPath $configPath | ForEach-Object {
    if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}

$required = @(
    'YANBAN_SANDBOX_BROKER_ENABLED',
    'YANBAN_SANDBOX_WORKSPACE_ROOT',
    'YANBAN_SANDBOX_BROKER_TOKEN',
    'YANBAN_SANDBOX_DB_URL',
    'YANBAN_SANDBOX_DB_USER',
    'YANBAN_SANDBOX_DB_PASSWORD',
    'E2B_API_KEY',
    'YANBAN_E2B_TEMPLATE'
)
foreach ($name in $required) {
    if ([string]::IsNullOrWhiteSpace($settings[$name])) { throw "Missing required setting: $name" }
}
if ($settings['YANBAN_SANDBOX_BROKER_ENABLED'] -ne 'true') {
    throw 'The local E2B Broker config must be enabled.'
}
if (-not (Test-Path -LiteralPath $providerHelper -PathType Leaf)) {
    throw 'The repository-owned E2B provider helper is missing.'
}
if (-not (Test-Path -LiteralPath $providerRequirements -PathType Leaf)) {
    throw 'The pinned E2B provider requirements are missing.'
}
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $providerPython -PathType Leaf)) {
    $basePython = Get-Command python -CommandType Application -All -ErrorAction Stop |
        Select-Object -ExpandProperty Source -First 1
    $savedErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $basePython -m venv $providerRuntimeRoot
    $venvExitCode = $LASTEXITCODE
    $ErrorActionPreference = $savedErrorAction
    if ($venvExitCode -ne 0 -or
        -not (Test-Path -LiteralPath $providerPython -PathType Leaf)) {
        throw 'Creating the stable E2B provider Python environment failed.'
    }
}
$savedErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
& $providerPython -c 'import e2b' 2>$null
$importExitCode = $LASTEXITCODE
$ErrorActionPreference = $savedErrorAction
if ($importExitCode -ne 0) {
    Write-Output 'Installing the pinned E2B provider SDK into the stable local runtime.'
    $ErrorActionPreference = 'Continue'
    & $providerPython -m pip install --disable-pip-version-check -r $providerRequirements
    $installExitCode = $LASTEXITCODE
    $ErrorActionPreference = $savedErrorAction
    if ($installExitCode -ne 0) {
        throw 'Installing the pinned E2B provider SDK failed.'
    }
    $ErrorActionPreference = 'SilentlyContinue'
    & $providerPython -c 'import e2b' 2>$null
    $importExitCode = $LASTEXITCODE
    $ErrorActionPreference = $savedErrorAction
    if ($importExitCode -ne 0) {
        throw 'The stable Python environment still cannot import the E2B provider SDK.'
    }
}
$settings['YANBAN_E2B_PYTHON_EXECUTABLE'] = $providerPython
$settings['YANBAN_E2B_HELPER'] = $providerHelper

$listener = Get-NetTCPConnection -LocalPort $brokerPort -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    throw "Port $brokerPort is already in use by PID $($listener.OwningProcess). Stop the existing Broker first."
}

New-Item -ItemType Directory -Path $settings['YANBAN_SANDBOX_WORKSPACE_ROOT'] -Force | Out-Null

$brokerSources = Get-ChildItem -Path @(
    (Join-Path $repoRoot 'yanban-sandbox-broker\src'),
    (Join-Path $repoRoot 'yanban-sandbox-broker\pom.xml'),
    (Join-Path $repoRoot 'yanban-sandbox-contract\src'),
    (Join-Path $repoRoot 'yanban-sandbox-contract\pom.xml')
) -Recurse -File
$jarIsStale = -not (Test-Path -LiteralPath $brokerJar -PathType Leaf) -or
    ($brokerSources | Where-Object { $_.LastWriteTime -gt (Get-Item -LiteralPath $brokerJar).LastWriteTime } | Select-Object -First 1)
if ($jarIsStale) {
    & mvn -o -pl yanban-sandbox-broker -am package -DskipTests
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $brokerJar -PathType Leaf)) {
        throw 'Broker jar build failed.'
    }
}

foreach ($entry in $settings.GetEnumerator()) {
    [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
}
$env:YANBAN_SANDBOX_BROKER_PORT = "$brokerPort"

$broker = Start-Process -FilePath java -ArgumentList @('-jar', $brokerJar) -WorkingDirectory $repoRoot `
    -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog
$backend = $null
try {
    $headers = @{ Authorization = "Bearer $($settings['YANBAN_SANDBOX_BROKER_TOKEN'])" }
    $health = $null
    $healthDeadline = [DateTimeOffset]::UtcNow.AddSeconds(90)
    while ([DateTimeOffset]::UtcNow -lt $healthDeadline) {
        if ($broker.HasExited) { break }
        try {
            # Provider health includes a real helper probe and can take several seconds on Windows.
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$brokerPort/internal/v1/health" -Headers $headers -TimeoutSec 10
            if ($health.status -eq 'UP' -and $health.provider -eq 'e2b') { break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $health -or $health.status -ne 'UP' -or $health.provider -ne 'e2b') {
        $detail = if (Test-Path -LiteralPath $stderrLog) {
            (Get-Content -LiteralPath $stderrLog -Tail 12) -join [Environment]::NewLine
        } else { 'No Broker stderr was captured.' }
        throw "E2B Broker did not become healthy.$([Environment]::NewLine)$detail"
    }
    Write-Output 'E2B Broker is healthy. Start the backend in IDEA and the frontend with pnpm dev as usual.'
    Write-Output "BROKER_PID=$($broker.Id)"
    Write-Output "BROKER_URL=http://127.0.0.1:$brokerPort"
    Write-Output "BROKER_LOG=$stdoutLog"
    if ($StartBackend) {
        if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
            throw 'Port 8080 is already listening. Stop the existing backend first.'
        }
        $env:YANBAN_SANDBOX_ENABLED = 'true'
        $env:YANBAN_SANDBOX_REQUIRED_AT_STARTUP = 'true'
        $env:YANBAN_SANDBOX_PROVIDER = 'e2b'
        $env:YANBAN_SANDBOX_BROKER_URL = "http://127.0.0.1:$brokerPort"
        $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
        $backend = Start-Process -FilePath $maven `
            -ArgumentList @('-pl', 'yanban-api', 'spring-boot:run', '-Dspring-boot.run.profiles=dev') `
            -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $backendStdoutLog `
            -RedirectStandardError $backendStderrLog
        $backendHealth = $null
        $backendDeadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
        while ([DateTimeOffset]::UtcNow -lt $backendDeadline) {
            if ($backend.HasExited) { break }
            try {
                $backendHealth = Invoke-RestMethod `
                    -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 2
                if ($backendHealth.status -eq 'UP') { break }
            } catch { }
            Start-Sleep -Milliseconds 500
        }
        if (-not $backendHealth -or $backendHealth.status -ne 'UP') {
            if ($backend -and -not $backend.HasExited) {
                Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
            }
            throw 'Backend did not become healthy; inspect the local E2B backend logs.'
        }
        Write-Output 'Backend is healthy with the E2B Broker configuration.'
        Write-Output "BACKEND_LAUNCHER_PID=$($backend.Id)"
        Write-Output 'BACKEND_URL=http://127.0.0.1:8080'
        Write-Output "BACKEND_LOG=$backendStdoutLog"
    }
} catch {
    if ($backend -and -not $backend.HasExited) {
        Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
    }
    if ($broker -and -not $broker.HasExited) {
        Stop-Process -Id $broker.Id -Force -ErrorAction SilentlyContinue
    }
    throw
}
