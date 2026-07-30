[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$configPath = Join-Path $repoRoot '.env.sandbox.local'
$brokerJar = Join-Path $repoRoot 'yanban-sandbox-broker\target\yanban-sandbox-broker-0.1.0-SNAPSHOT.jar'
$providerHelper = Join-Path $repoRoot 'deploy\sandbox\e2b\e2b_provider.py'
$listeners = Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue

if (-not $listeners) {
    Write-Output 'Local E2B Broker is already stopped.'
    exit 0
}
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'The local E2B config is missing; refusing to stop an unverified process.'
}

$settings = @{}
Get-Content -LiteralPath $configPath | ForEach-Object {
    if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
foreach ($name in @('E2B_API_KEY', 'YANBAN_E2B_PYTHON_EXECUTABLE')) {
    if ([string]::IsNullOrWhiteSpace($settings[$name])) { throw "Missing required setting: $name" }
}
if (-not (Test-Path -LiteralPath $providerHelper -PathType Leaf)) {
    throw 'The repository-owned E2B provider helper is missing.'
}
$env:E2B_API_KEY = $settings['E2B_API_KEY']
$active = & $settings['YANBAN_E2B_PYTHON_EXECUTABLE'] $providerHelper list | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Unable to verify managed E2B sandbox state; refusing to stop Broker.' }
if (@($active).Count -gt 0) {
    throw 'One or more managed E2B sandboxes are still active. Wait for cleanup or cancel through the application first.'
}

foreach ($listener in $listeners) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    $expected = $process -and $process.Name -eq 'java.exe' -and
        $process.CommandLine -like '*yanban-sandbox-broker-0.1.0-SNAPSHOT.jar*'
    if (-not $expected) {
        throw "Port 8091 belongs to an unverified process (PID $($listener.OwningProcess)); refusing to stop it."
    }
    Stop-Process -Id $listener.OwningProcess
    Wait-Process -Id $listener.OwningProcess -Timeout 20 -ErrorAction SilentlyContinue
}

if (Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue) {
    throw 'E2B Broker did not stop cleanly.'
}
Write-Output 'Local E2B Broker stopped. Docker infrastructure was not changed.'
