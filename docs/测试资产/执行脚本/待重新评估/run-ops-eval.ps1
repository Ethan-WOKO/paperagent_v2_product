param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ComposeFile = "docs/当前有效/部署运维/docker-compose.yml",
    [int]$TimeoutSec = 30,
    [string]$OutputDir = "docs/测试资产/运行结果"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [System.Text.UTF8Encoding]::new()

function Load-DotEnv {
    param([string]$Path = ".env")
    if (-not (Test-Path $Path)) { return }
    Get-Content $Path | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim().Trim('"'), "Process")
        }
    }
}

function Short-Text {
    param($Value, [int]$Max = 260)
    if ($null -eq $Value) { return "" }
    $text = if ($Value -is [string]) { $Value } else { $Value | ConvertTo-Json -Depth 20 -Compress }
    $text = $text -replace '\s+', ' '
    if ($text.Length -le $Max) { return $text }
    return $text.Substring(0, $Max) + "..."
}

function Add-Result {
    param([string]$Id, [string]$Status, [int]$DurationMs = 0, [string]$Note = "", $Excerpt = "", [string]$Error = "")
    $script:Results.Add([pscustomobject]@{
        id = $Id
        status = $Status
        pass = ($Status -eq "PASS")
        durationMs = $DurationMs
        note = $Note
        excerpt = Short-Text $Excerpt
        error = $Error
    }) | Out-Null
    Write-Host ("[{0}] {1} durationMs={2} {3}" -f $Status, $Id, $DurationMs, $Note)
}

function Invoke-JsonGet {
    param([string]$Uri, [int]$Timeout = $TimeoutSec)
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($Timeout)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $client.GetAsync($Uri).Result
        $sw.Stop()
        $raw = $response.Content.ReadAsStringAsync().Result
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Ok = $response.IsSuccessStatusCode; Raw = $raw; DurationMs = [int]$sw.ElapsedMilliseconds; Error = $null }
    } catch {
        $sw.Stop()
        return [pscustomobject]@{ Status = 0; Ok = $false; Raw = ""; DurationMs = [int]$sw.ElapsedMilliseconds; Error = $_.Exception.Message }
    } finally {
        $client.Dispose()
    }
}

function Invoke-Process {
    param([string]$FilePath, [string[]]$Arguments)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $FilePath @Arguments 2>&1
        $code = $LASTEXITCODE
        $sw.Stop()
        return [pscustomobject]@{ ExitCode = $code; Output = ($output -join [Environment]::NewLine); DurationMs = [int]$sw.ElapsedMilliseconds; Error = $null }
    } catch {
        $sw.Stop()
        return [pscustomobject]@{ ExitCode = -1; Output = ""; DurationMs = [int]$sw.ElapsedMilliseconds; Error = $_.Exception.Message }
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

Load-DotEnv
$runStarted = Get-Date
$runId = $runStarted.ToString("yyyyMMddHHmmss")
$script:Results = [System.Collections.Generic.List[object]]::new()
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$artifactDir = Join-Path $OutputDir "ops-$runId"
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

Write-Host "Yanban ops eval runId=$runId composeFile=$ComposeFile"

$health = Invoke-JsonGet -Uri "$BaseUrl/actuator/health" -Timeout 10
Add-Result -Id "OPS-HEALTH-01" -Status ($(if ($health.Status -eq 200 -and $health.Raw -match "UP") { "PASS" } else { "FAIL" })) -DurationMs $health.DurationMs -Note "backend actuator health" -Excerpt $health.Raw -Error $health.Error

$dockerVersion = Invoke-Process -FilePath "docker" -Arguments @("--version")
Add-Result -Id "OPS-DOCKER-01" -Status ($(if ($dockerVersion.ExitCode -eq 0) { "PASS" } else { "FAIL" })) -DurationMs $dockerVersion.DurationMs -Note "docker command available" -Excerpt $dockerVersion.Output -Error $dockerVersion.Error

$composePs = Invoke-Process -FilePath "docker" -Arguments @("compose", "-f", $ComposeFile, "ps")
$composeExpected = @("yanban-mysql", "yanban-redis", "yanban-elasticsearch", "yanban-kafka", "yanban-minio")
$composePass = $composePs.ExitCode -eq 0
foreach ($name in $composeExpected) {
    if ($composePs.Output -notmatch [regex]::Escape($name)) {
        $composePass = $false
    }
}
Add-Result -Id "OPS-COMPOSE-01" -Status ($(if ($composePass) { "PASS" } else { "FAIL" })) -DurationMs $composePs.DurationMs -Note "docker compose services visible" -Excerpt $composePs.Output -Error $composePs.Error

$mysqlRoot = if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { "yanban_root_password" }
$mysqlDb = if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { "yanban_agent" }
$dump = Invoke-Process -FilePath "docker" -Arguments @("exec", "-e", "MYSQL_PWD=$mysqlRoot", "yanban-mysql", "mysqldump", "-uroot", "--single-transaction", "--no-data", $mysqlDb)
$dumpPath = Join-Path $artifactDir "mysql-schema.sql"
if ($dump.ExitCode -eq 0) {
    Set-Content -Path $dumpPath -Value $dump.Output -Encoding UTF8
}
$dumpPass = $dump.ExitCode -eq 0 -and (Test-Path $dumpPath) -and ((Get-Item $dumpPath).Length -gt 100)
Add-Result -Id "OPS-MYSQL-01" -Status ($(if ($dumpPass) { "PASS" } else { "FAIL" })) -DurationMs $dump.DurationMs -Note "non-destructive schema dump generated" -Excerpt $dumpPath -Error $dump.Error

$es = Invoke-JsonGet -Uri "http://localhost:9200/_cluster/health" -Timeout 15
Add-Result -Id "OPS-ES-01" -Status ($(if ($es.Status -eq 200 -and $es.Raw -match "status") { "PASS" } else { "FAIL" })) -DurationMs $es.DurationMs -Note "elasticsearch cluster health reachable" -Excerpt $es.Raw -Error $es.Error

$minio = Invoke-Process -FilePath "docker" -Arguments @("exec", "yanban-minio", "mc", "ready", "local")
Add-Result -Id "OPS-MINIO-01" -Status ($(if ($minio.ExitCode -eq 0) { "PASS" } else { "FAIL" })) -DurationMs $minio.DurationMs -Note "minio readiness check" -Excerpt $minio.Output -Error $minio.Error

$kafka = Invoke-Process -FilePath "docker" -Arguments @("exec", "yanban-kafka", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "localhost:9092", "--list")
Add-Result -Id "OPS-KAFKA-01" -Status ($(if ($kafka.ExitCode -eq 0) { "PASS" } else { "FAIL" })) -DurationMs $kafka.DurationMs -Note "kafka topic listing works" -Excerpt $kafka.Output -Error $kafka.Error

$serialized = $Results | ConvertTo-Json -Depth 20 -Compress
$secrets = @($env:DEEPSEEK_API_KEY, $env:GLM_API_KEY, $env:DASHSCOPE_API_KEY, $env:JWT_SECRET, $env:MYSQL_PASSWORD, $env:MYSQL_ROOT_PASSWORD) | Where-Object { $_ -and $_.Length -ge 8 }
$leaks = @()
foreach ($secret in $secrets) {
    if ($serialized.Contains($secret)) {
        $leaks += "secret-value-present"
    }
}
Add-Result -Id "OPS-SECRETS-01" -Status ($(if ($leaks.Count -eq 0) { "PASS" } else { "FAIL" })) -DurationMs 0 -Note "report does not contain configured secret values" -Error ($leaks -join ",")

$runEnded = Get-Date
$passCount = @($Results | Where-Object { $_.status -eq "PASS" }).Count
$failCount = @($Results | Where-Object { $_.status -eq "FAIL" }).Count
$summary = [pscustomobject]@{
    runId = $runId
    startedAt = $runStarted.ToString("o")
    endedAt = $runEnded.ToString("o")
    durationSec = [int]($runEnded - $runStarted).TotalSeconds
    artifactDir = $artifactDir
    passCount = $passCount
    failCount = $failCount
    total = $Results.Count
    results = $Results
}

$jsonPath = Join-Path $OutputDir "ops-$runId.json"
$mdPath = Join-Path $OutputDir "ops-$runId.md"
$summary | ConvertTo-Json -Depth 30 | Set-Content -Path $jsonPath -Encoding UTF8

$md = @()
$md += "# Ops Eval $runId"
$md += ""
$md += "- Result: $passCount pass / $failCount fail / $($Results.Count) total"
$md += "- Artifact dir: $artifactDir"
$md += ""
$md += "| ID | Status | Duration ms | Note | Error |"
$md += "|---|---:|---:|---|---|"
foreach ($result in $Results) {
    $md += "| $($result.id) | $($result.status) | $($result.durationMs) | $($result.note -replace '\|','/') | $($result.error -replace '\|','/') |"
}
$md | Set-Content -Path $mdPath -Encoding UTF8

Write-Host ""
Write-Host "RESULT pass=$passCount fail=$failCount total=$($Results.Count)"
Write-Host "JSON $jsonPath"
Write-Host "MD   $mdPath"

if ($failCount -gt 0) { exit 1 }
