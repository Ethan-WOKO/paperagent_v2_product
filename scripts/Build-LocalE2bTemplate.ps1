[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$configPath = Join-Path $repoRoot '.env.sandbox.local'
$buildScript = Join-Path $repoRoot 'deploy\sandbox\e2b\build_template.py'

if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Missing local E2B configuration."
}

$settings = @{}
Get-Content -LiteralPath $configPath | ForEach-Object {
    if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $settings[$Matches[1]] = $Matches[2]
    }
}

foreach ($required in 'E2B_API_KEY', 'YANBAN_E2B_PYTHON_EXECUTABLE', 'YANBAN_E2B_TEMPLATE') {
    if (-not $settings.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($settings[$required])) {
        throw "Missing required local E2B setting: $required"
    }
}

$env:E2B_API_KEY = $settings['E2B_API_KEY']
$env:YANBAN_E2B_TEMPLATE = $settings['YANBAN_E2B_TEMPLATE']
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
try {
    & $settings['YANBAN_E2B_PYTHON_EXECUTABLE'] $buildScript
    if ($LASTEXITCODE -ne 0) {
        throw 'E2B template build failed.'
    }
} finally {
    Remove-Item Env:E2B_API_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:YANBAN_E2B_TEMPLATE -ErrorAction SilentlyContinue
    Remove-Item Env:PYTHONUTF8 -ErrorAction SilentlyContinue
    Remove-Item Env:PYTHONIOENCODING -ErrorAction SilentlyContinue
}
