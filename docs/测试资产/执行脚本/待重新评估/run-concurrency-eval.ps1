param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Concurrency = 5,
    [int]$Requests = 10,
    [int]$TimeoutSec = 120,
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

function Invoke-JsonApi {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null, [int]$Timeout = $TimeoutSec)
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($Timeout)
    if ($Token) {
        $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
    }
    $methodObject = switch ($Method.ToUpperInvariant()) {
        "GET" { [System.Net.Http.HttpMethod]::Get }
        "POST" { [System.Net.Http.HttpMethod]::Post }
        "PUT" { [System.Net.Http.HttpMethod]::Put }
        default { [System.Net.Http.HttpMethod]::new($Method.ToUpperInvariant()) }
    }
    $request = [System.Net.Http.HttpRequestMessage]::new($methodObject, "$BaseUrl$Path")
    if ($null -ne $Body) {
        $request.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 20), [System.Text.Encoding]::UTF8, "application/json")
    }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $client.SendAsync($request).Result
        $sw.Stop()
        $raw = $response.Content.ReadAsStringAsync().Result
        $parsed = $null
        if ($raw) {
            try { $parsed = $raw | ConvertFrom-Json } catch { $parsed = $raw }
        }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Ok = $response.IsSuccessStatusCode; Body = $parsed; Raw = $raw; DurationMs = [int]$sw.ElapsedMilliseconds; Error = $null }
    } catch {
        $sw.Stop()
        return [pscustomobject]@{ Status = 0; Ok = $false; Body = $null; Raw = ""; DurationMs = [int]$sw.ElapsedMilliseconds; Error = $_.Exception.Message }
    } finally {
        if ($request) { $request.Dispose() }
        $client.Dispose()
    }
}

function Add-Result {
    param([string]$Id, [string]$Status, [int]$DurationMs = 0, [string]$Note = "", [string]$Error = "")
    $script:Results.Add([pscustomobject]@{ id = $Id; status = $Status; pass = ($Status -eq "PASS"); durationMs = $DurationMs; note = $Note; error = $Error }) | Out-Null
    Write-Host ("[{0}] {1} durationMs={2} {3}" -f $Status, $Id, $DurationMs, $Note)
}

function Get-Token {
    param([string]$Username, [string]$Password = "password123")
    $register = Invoke-JsonApi -Method "POST" -Path "/api/v1/auth/register" -Body @{ username = $Username; password = $Password } -Timeout 30
    if ($register.Status -eq 201 -and $register.Body.accessToken) { return $register.Body.accessToken }
    $login = Invoke-JsonApi -Method "POST" -Path "/api/v1/auth/login" -Body @{ username = $Username; password = $Password } -Timeout 30
    if ($login.Status -eq 200 -and $login.Body.accessToken) { return $login.Body.accessToken }
    throw "Cannot register/login $Username"
}

function Set-Settings {
    param([string]$Token)
    return Invoke-JsonApi -Method "PUT" -Path "/api/v1/settings" -Token $Token -Body @{
        defaultProvider = "deepseek"
        deepseekApiKey = $env:DEEPSEEK_API_KEY
        glmApiKey = $env:GLM_API_KEY
        deepseekModel = "deepseek-chat"
        glmModel = "glm-4.5-air"
        deepseekTemperature = 0.2
        maxSteps = 4
        ragDefaultEnabled = $true
        filesystemRoots = @()
        disabledSkills = @()
    } -Timeout 30
}

function Upload-Text {
    param([string]$Token, [string]$Filename, [string]$Content)
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    $fileContent = [System.Net.Http.ByteArrayContent]::new([System.Text.Encoding]::UTF8.GetBytes($Content))
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("text/markdown; charset=utf-8")
    $multipart.Add($fileContent, "file", $Filename)
    $multipart.Add([System.Net.Http.StringContent]::new("false"), "isPublic")
    try {
        $response = $client.PostAsync("$BaseUrl/api/v1/kb/documents/simple-upload", $multipart).Result
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Raw = $response.Content.ReadAsStringAsync().Result }
    } finally {
        $multipart.Dispose()
        $client.Dispose()
    }
}

function Percentile95 {
    param([int[]]$Values)
    if ($Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling($sorted.Count * 0.95) - 1
    $index = [Math]::Max(0, [Math]::Min($index, $sorted.Count - 1))
    return [int]$sorted[$index]
}

Load-DotEnv
$runStarted = Get-Date
$runId = $runStarted.ToString("yyyyMMddHHmmss")
$script:Results = [System.Collections.Generic.List[object]]::new()
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

Write-Host "Yanban concurrency eval runId=$runId concurrency=$Concurrency requests=$Requests"

$health = Invoke-JsonApi -Method "GET" -Path "/actuator/health" -Timeout 10
Add-Result -Id "ENV-HEALTH-01" -Status ($(if ($health.Status -eq 200 -and $health.Raw -match "UP") { "PASS" } else { "FAIL" })) -DurationMs $health.DurationMs -Note "backend health" -Error $health.Error

if (-not $env:DEEPSEEK_API_KEY) {
    Add-Result -Id "SETUP-DEEPSEEK" -Status "SKIP" -Note "DEEPSEEK_API_KEY missing; concurrency chat eval skipped"
} else {
    $alice = Get-Token -Username "concurrency_alice_$runId"
    $bob = Get-Token -Username "concurrency_bob_$runId"
    $settingsA = Set-Settings -Token $alice
    $settingsB = Set-Settings -Token $bob
    Add-Result -Id "SETUP-SETTINGS" -Status ($(if ($settingsA.Status -eq 200 -and $settingsB.Status -eq 200) { "PASS" } else { "FAIL" })) -DurationMs ($settingsA.DurationMs + $settingsB.DurationMs) -Note "settings configured"

    $privateKey = "concurrency_private_$runId"
    $upload = Upload-Text -Token $alice -Filename "concurrency-$runId.md" -Content "${privateKey}: Alice private concurrent KB fact."
    Add-Result -Id "SETUP-KB" -Status ($(if ($upload.Status -eq 201) { "PASS" } else { "FAIL" })) -Note "private KB uploaded"

    $ownerSession = Invoke-JsonApi -Method "POST" -Path "/api/v1/agent/sessions" -Token $alice -Body @{ title = "Owner isolation $runId"; modelProvider = "deepseek"; model = "deepseek-chat"; maxSteps = 4; ragDisabled = $true } -Timeout 30
    $bobProbe = Invoke-JsonApi -Method "GET" -Path "/api/v1/agent/sessions/$($ownerSession.Body.id)/messages" -Token $bob -Timeout 20
    Add-Result -Id "P0-AUTH-02" -Status ($(if ($bobProbe.Status -eq 404 -or $bobProbe.Status -eq 403) { "PASS" } else { "FAIL" })) -DurationMs $bobProbe.DurationMs -Note "bob cannot read alice session"

    $jobScript = {
        param($BaseUrl, $Token, $Index, $PrivateKey, $TimeoutSec)
        Add-Type -AssemblyName System.Net.Http
        function Invoke-LocalJson {
            param([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null, [int]$Timeout = 120)
            $client = [System.Net.Http.HttpClient]::new()
            $client.Timeout = [TimeSpan]::FromSeconds($Timeout)
            if ($Token) { $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token) }
            $methodObject = switch ($Method) { "GET" { [System.Net.Http.HttpMethod]::Get } "POST" { [System.Net.Http.HttpMethod]::Post } default { [System.Net.Http.HttpMethod]::Post } }
            $request = [System.Net.Http.HttpRequestMessage]::new($methodObject, "$BaseUrl$Path")
            if ($null -ne $Body) { $request.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 20), [System.Text.Encoding]::UTF8, "application/json") }
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                $response = $client.SendAsync($request).Result
                $raw = $response.Content.ReadAsStringAsync().Result
                $bodyObject = $null
                if ($raw) { try { $bodyObject = $raw | ConvertFrom-Json } catch { $bodyObject = $raw } }
                $sw.Stop()
                return [pscustomobject]@{ Status = [int]$response.StatusCode; Raw = $raw; Body = $bodyObject; DurationMs = [int]$sw.ElapsedMilliseconds; Error = $null }
            } catch {
                $sw.Stop()
                return [pscustomobject]@{ Status = 0; Raw = ""; Body = $null; DurationMs = [int]$sw.ElapsedMilliseconds; Error = $_.Exception.Message }
            } finally {
                if ($request) { $request.Dispose() }
                $client.Dispose()
            }
        }
        $session = Invoke-LocalJson -Method "POST" -Path "/api/v1/agent/sessions" -Token $Token -Timeout 30 -Body @{ title = "Concurrent $Index"; modelProvider = "deepseek"; model = "deepseek-chat"; maxSteps = 4; ragDisabled = $true }
        $chat = Invoke-LocalJson -Method "POST" -Path "/api/v1/agent/sessions/$($session.Body.id)/messages" -Token $Token -Timeout $TimeoutSec -Body @{ content = "Reply with OK-$Index in one short sentence."; ragDisabled = $true }
        $search = Invoke-LocalJson -Method "POST" -Path "/api/v1/search" -Token $Token -Timeout 45 -Body @{ query = $PrivateKey; topK = 5 }
        $searchHit = $search.Raw -match [regex]::Escape($PrivateKey)
        $has5xx = ($session.Status -ge 500) -or ($chat.Status -ge 500) -or ($search.Status -ge 500) -or ($session.Status -eq 0) -or ($chat.Status -eq 0) -or ($search.Status -eq 0)
        [pscustomobject]@{
            index = $Index
            pass = (-not $has5xx) -and ($session.Status -eq 201) -and ($chat.Status -eq 200) -and ($search.Status -eq 200) -and $searchHit
            sessionStatus = $session.Status
            chatStatus = $chat.Status
            searchStatus = $search.Status
            durationMs = $session.DurationMs + $chat.DurationMs + $search.DurationMs
            error = (($session.Error, $chat.Error, $search.Error) -join " ")
        }
    }

    $pending = New-Object System.Collections.Generic.List[object]
    $allJobResults = New-Object System.Collections.Generic.List[object]
    for ($i = 1; $i -le $Requests; $i++) {
        while ($pending.Count -ge $Concurrency) {
            $done = Wait-Job -Job $pending -Any
            $allJobResults.Add((Receive-Job -Job $done)) | Out-Null
            Remove-Job -Job $done
            $pending.Remove($done) | Out-Null
        }
        $pending.Add((Start-Job -ScriptBlock $jobScript -ArgumentList $BaseUrl, $alice, $i, $privateKey, $TimeoutSec)) | Out-Null
    }
    while ($pending.Count -gt 0) {
        $done = Wait-Job -Job $pending -Any
        $allJobResults.Add((Receive-Job -Job $done)) | Out-Null
        Remove-Job -Job $done
        $pending.Remove($done) | Out-Null
    }

    $failedJobs = @($allJobResults | Where-Object { -not $_.pass })
    $p95 = Percentile95 -Values @($allJobResults | ForEach-Object { [int]$_.durationMs })
    Add-Result -Id "P1-CONCURRENCY-01" -Status ($(if ($failedJobs.Count -eq 0 -and $p95 -lt 60000) { "PASS" } else { "FAIL" })) -DurationMs $p95 -Note "completed=$($allJobResults.Count), failed=$($failedJobs.Count), p95Ms=$p95" -Error (($failedJobs | ConvertTo-Json -Depth 10 -Compress))
}

$runEnded = Get-Date
$passCount = @($Results | Where-Object { $_.status -eq "PASS" }).Count
$failCount = @($Results | Where-Object { $_.status -eq "FAIL" }).Count
$skipCount = @($Results | Where-Object { $_.status -eq "SKIP" }).Count
$summary = [pscustomobject]@{
    runId = $runId
    startedAt = $runStarted.ToString("o")
    endedAt = $runEnded.ToString("o")
    durationSec = [int]($runEnded - $runStarted).TotalSeconds
    concurrency = $Concurrency
    requests = $Requests
    passCount = $passCount
    failCount = $failCount
    skipCount = $skipCount
    total = $Results.Count
    results = $Results
}

$jsonPath = Join-Path $OutputDir "concurrency-$runId.json"
$mdPath = Join-Path $OutputDir "concurrency-$runId.md"
$summary | ConvertTo-Json -Depth 30 | Set-Content -Path $jsonPath -Encoding UTF8

$md = @()
$md += "# Concurrency Eval $runId"
$md += ""
$md += "- Result: $passCount pass / $failCount fail / $skipCount skip / $($Results.Count) total"
$md += "- Concurrency: $Concurrency"
$md += "- Requests: $Requests"
$md += ""
$md += "| ID | Status | Duration ms | Note | Error |"
$md += "|---|---:|---:|---|---|"
foreach ($result in $Results) {
    $md += "| $($result.id) | $($result.status) | $($result.durationMs) | $($result.note) | $($result.error -replace '\|','/') |"
}
$md | Set-Content -Path $mdPath -Encoding UTF8

Write-Host ""
Write-Host "RESULT pass=$passCount fail=$failCount skip=$skipCount total=$($Results.Count)"
Write-Host "JSON $jsonPath"
Write-Host "MD   $mdPath"

if ($failCount -gt 0) { exit 1 }
