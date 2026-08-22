param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Username = "kb_load_20260822102002",
    [string]$Password = "password123",
    [ValidateRange(1, 10)] [int]$AgentSequentialRequests = 3,
    [ValidateRange(1, 12)] [int]$AgentConcurrency = 3,
    [ValidateRange(1, 12)] [int]$AgentConcurrentRequests = 3,
    [int[]]$RagConcurrencyLevels = @(1, 4, 8),
    [ValidateRange(1, 200)] [int]$RagRequestsPerLevel = 20,
    [string]$OutputDir = "docs/测试资产/运行结果"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [System.Text.UTF8Encoding]::new()

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if ($Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling($sorted.Count * $Percentile) - 1
    $index = [Math]::Max(0, [Math]::Min($index, $sorted.Count - 1))
    [Math]::Round([double]$sorted[$index], 2)
}

function Invoke-JsonApi {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null)
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $parameters = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $headers
        SkipHttpErrorCheck = $true
        StatusCodeVariable = "statusCode"
        TimeoutSec = 60
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 10 -Compress
    }
    $response = Invoke-RestMethod @parameters
    [pscustomobject]@{ Status = [int]$statusCode; Body = $response }
}

$agentWorker = {
    param($BaseUrl, $Token, $SessionId, $Index)
    Add-Type -AssemblyName System.Net.Http | Out-Null
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(120)
    $client.DefaultRequestHeaders.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $requestId = "request.perf$([guid]::NewGuid().ToString('N'))"
        $payload = @{
            clientRequestId = $requestId
            instruction = "读取 README.md，只用一句话说明项目名称。"
            provider = "deepseek"
            model = "deepseek-chat"
        } | ConvertTo-Json -Compress
        $content = [System.Net.Http.StringContent]::new(
            $payload, [Text.Encoding]::UTF8, "application/json")
        $response = $client.PostAsync(
            "$BaseUrl/api/v1/react-agent/sessions/$SessionId/tasks", $content).GetAwaiter().GetResult()
        $acceptedMs = $watch.Elapsed.TotalMilliseconds
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            return [pscustomobject]@{ index=$Index; success=$false; status=[int]$response.StatusCode;
                acceptedMs=[math]::Round($acceptedMs,2); firstEventMs=$null; firstVisibleMs=$null;
                totalMs=[math]::Round($watch.Elapsed.TotalMilliseconds,2); state="rejected";
                turnId=$null; taskId=$null; error=$body }
        }
        $task = $body | ConvertFrom-Json
        $streamRequest = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Get,
            "$BaseUrl/api/v1/react-agent/turns/$($task.turnId)/tasks/$($task.taskId)/events")
        $streamRequest.Headers.Accept.ParseAdd("text/event-stream")
        $streamRequest.Headers.Add("Last-Event-ID", "0")
        $streamResponse = $client.SendAsync(
            $streamRequest, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        $streamResponse.EnsureSuccessStatusCode()
        $reader = [IO.StreamReader]::new($streamResponse.Content.ReadAsStream())
        $firstEventMs = $null
        $firstVisibleMs = $null
        $terminalState = $null
        while (-not $reader.EndOfStream) {
            $line = $reader.ReadLine()
            if (-not $line.StartsWith("data: ")) { continue }
            $event = $line.Substring(6) | ConvertFrom-Json
            if ($null -eq $firstEventMs) { $firstEventMs = $watch.Elapsed.TotalMilliseconds }
            if ($null -eq $firstVisibleMs -and $event.type -in @("delivery", "question")) {
                $firstVisibleMs = $watch.Elapsed.TotalMilliseconds
            }
            if ($event.type -eq "status" -and $event.state -in @("succeeded", "failed", "cancelled")) {
                $terminalState = $event.state
                break
            }
        }
        [pscustomobject]@{
            index = $Index
            success = $terminalState -eq "succeeded"
            status = [int]$response.StatusCode
            acceptedMs = [math]::Round($acceptedMs, 2)
            firstEventMs = if ($null -eq $firstEventMs) { $null } else { [math]::Round($firstEventMs, 2) }
            firstVisibleMs = if ($null -eq $firstVisibleMs) { $null } else { [math]::Round($firstVisibleMs, 2) }
            totalMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 2)
            state = $terminalState
            turnId = $task.turnId
            taskId = $task.taskId
            error = $null
        }
    } catch {
        [pscustomobject]@{ index=$Index; success=$false; status=0; acceptedMs=$null;
            firstEventMs=$null; firstVisibleMs=$null; totalMs=[math]::Round($watch.Elapsed.TotalMilliseconds,2);
            state="exception"; turnId=$null; taskId=$null; error=$_.Exception.Message }
    } finally {
        $client.Dispose()
    }
}

$ragWorker = {
    param($BaseUrl, $Token, $Index)
    Add-Type -AssemblyName System.Net.Http | Out-Null
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(90)
    $client.DefaultRequestHeaders.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $payload = @{ query = "concurrent knowledge ingestion remains searchable and isolated"; topK = 5 } |
            ConvertTo-Json -Compress
        $content = [System.Net.Http.StringContent]::new(
            $payload, [Text.Encoding]::UTF8, "application/json")
        $response = $client.PostAsync("$BaseUrl/api/v1/search", $content).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $resultCount = if ($response.IsSuccessStatusCode) { @($body | ConvertFrom-Json).Count } else { 0 }
        [pscustomobject]@{ index=$Index; success=$response.IsSuccessStatusCode; status=[int]$response.StatusCode;
            latencyMs=[math]::Round($watch.Elapsed.TotalMilliseconds,2); resultCount=$resultCount; error=$null }
    } catch {
        [pscustomobject]@{ index=$Index; success=$false; status=0;
            latencyMs=[math]::Round($watch.Elapsed.TotalMilliseconds,2); resultCount=0; error=$_.Exception.Message }
    } finally {
        $client.Dispose()
    }
}

$health = Invoke-JsonApi -Method GET -Path "/actuator/health"
if ($health.Status -ne 200 -or $health.Body.status -ne "UP") { throw "Backend is not healthy" }
$auth = Invoke-JsonApi -Method POST -Path "/api/v1/auth/login" -Body @{ username=$Username; password=$Password }
if ($auth.Status -ne 200 -or -not $auth.Body.accessToken) { throw "Authentication failed" }
$token = $auth.Body.accessToken

$startedAt = Get-Date
$runId = $startedAt.ToString("yyyyMMdd-HHmmss")
$gitCommit = (git rev-parse HEAD).Trim()
$worktreeChanges = @(git status --short --untracked-files=all |
    Where-Object { $_ -notmatch '^\?\? \.runtime/' -and $_ -notmatch 'docs/测试资产/运行结果/' })

# Create a minimal isolated Project because the production ReAct entry requires a Project-scoped session.
$projectName = "agent-perf-$runId"
$curlArguments = @(
    "-sS", "-X", "POST", "$BaseUrl/api/v1/projects",
    "-H", "Authorization: Bearer $token",
    "-F", "name=$projectName", "-F", "includeRules=**",
    "-F", "files=@README.md;filename=README.md"
)
$upload = & curl.exe @curlArguments
$project = $upload | ConvertFrom-Json
if (-not $project.id) { throw "Project creation failed: $upload" }
$session = Invoke-JsonApi -Method POST -Path "/api/v1/projects/$($project.id)/agent/sessions" -Token $token -Body @{
    title="Agent performance baseline"; modelProvider="deepseek"; model="deepseek-chat"; maxSteps=6; ragDisabled=$true
}
if ($session.Status -ne 201 -and $session.Status -ne 200) { throw "Project session creation failed" }
$sessionId = $session.Body.id

Write-Host "Agent sequential baseline: requests=$AgentSequentialRequests"
$agentSequential = @()
for ($i = 1; $i -le $AgentSequentialRequests; $i++) {
    $agentSequential += & $agentWorker $BaseUrl $token $sessionId $i
}

Write-Host "Agent concurrent baseline: concurrency=$AgentConcurrency requests=$AgentConcurrentRequests"
$agentConcurrent = @()
for ($offset = 0; $offset -lt $AgentConcurrentRequests; $offset += $AgentConcurrency) {
    $count = [Math]::Min($AgentConcurrency, $AgentConcurrentRequests - $offset)
    $jobs = 1..$count | ForEach-Object {
        Start-ThreadJob -ScriptBlock $agentWorker -ArgumentList $BaseUrl, $token, $sessionId, ($offset + $_)
    }
    $agentConcurrent += @($jobs | Receive-Job -Wait -AutoRemoveJob)
}

$ragStages = @()
foreach ($level in $RagConcurrencyLevels) {
    Write-Host "RAG search baseline: concurrency=$level requests=$RagRequestsPerLevel"
    $stageWatch = [Diagnostics.Stopwatch]::StartNew()
    $items = @()
    for ($offset = 0; $offset -lt $RagRequestsPerLevel; $offset += $level) {
        $count = [Math]::Min($level, $RagRequestsPerLevel - $offset)
        $jobs = 1..$count | ForEach-Object {
            Start-ThreadJob -ScriptBlock $ragWorker -ArgumentList $BaseUrl, $token, ($offset + $_)
        }
        $items += @($jobs | Receive-Job -Wait -AutoRemoveJob)
    }
    $stageWatch.Stop()
    $latencies = @($items | Where-Object success | ForEach-Object { [double]$_.latencyMs })
    $ragStages += [pscustomobject]@{
        concurrency=$level; requests=$items.Count; succeeded=@($items | Where-Object success).Count;
        failed=@($items | Where-Object { -not $_.success }).Count;
        wallTimeSec=[math]::Round($stageWatch.Elapsed.TotalSeconds,3);
        qps=[math]::Round(@($items | Where-Object success).Count / $stageWatch.Elapsed.TotalSeconds,3);
        p50Ms=(Get-Percentile $latencies 0.50); p95Ms=(Get-Percentile $latencies 0.95);
        p99Ms=(Get-Percentile $latencies 0.99); items=$items
    }
}

function Summarize-Agent($items) {
    $valid = @($items | Where-Object { $null -ne $_.PSObject.Properties['success'] })
    $success = @($valid | Where-Object success)
    [pscustomobject]@{
        requests=$valid.Count; succeeded=$success.Count; failed=@($valid | Where-Object { -not $_.success }).Count
        acceptedP95Ms=(Get-Percentile @($success | ForEach-Object { [double]$_.acceptedMs }) 0.95)
        firstEventP95Ms=(Get-Percentile @($success | ForEach-Object { [double]$_.firstEventMs }) 0.95)
        firstVisibleP50Ms=(Get-Percentile @($success | ForEach-Object { [double]$_.firstVisibleMs }) 0.50)
        firstVisibleP95Ms=(Get-Percentile @($success | ForEach-Object { [double]$_.firstVisibleMs }) 0.95)
        totalP50Ms=(Get-Percentile @($success | ForEach-Object { [double]$_.totalMs }) 0.50)
        totalP95Ms=(Get-Percentile @($success | ForEach-Object { [double]$_.totalMs }) 0.95)
        items=$valid
    }
}

$traceItems = @($agentSequential + $agentConcurrent | Where-Object success | ForEach-Object {
    $trace = Invoke-JsonApi -Method GET -Path "/api/v1/react-agent/turns/$($_.turnId)/tasks/$($_.taskId)/trace" -Token $token
    if ($trace.Status -ne 200) { throw "Agent trace query failed for $($_.taskId)" }
    [pscustomobject]@{
        turnId=$_.turnId; taskId=$_.taskId; modelCalls=$trace.Body.summary.modelCalls;
        toolCalls=$trace.Body.summary.toolCalls; durationMs=$trace.Body.summary.totalDurationMillis;
        firstObservableMs=$trace.Body.summary.firstObservableMillis;
        promptTokens=$trace.Body.summary.promptTokens; completionTokens=$trace.Body.summary.completionTokens;
        failureCount=$trace.Body.summary.failureCount
    }
})
$traceSummary = [pscustomobject]@{
    tasks=$traceItems.Count
    modelCallsAverage=[math]::Round(($traceItems | Measure-Object modelCalls -Average).Average,2)
    modelCallsMin=($traceItems | Measure-Object modelCalls -Minimum).Minimum
    modelCallsMax=($traceItems | Measure-Object modelCalls -Maximum).Maximum
    totalTokensAverage=[math]::Round(($traceItems | ForEach-Object { $_.promptTokens + $_.completionTokens } |
        Measure-Object -Average).Average,0)
    durationAverageMs=[math]::Round(($traceItems | Measure-Object durationMs -Average).Average,0)
    items=$traceItems
}

$result = [ordered]@{
    runId=$runId; startedAt=$startedAt.ToString("o"); gitCommit=$gitCommit; worktreeChanges=$worktreeChanges
    username=$Username; projectId=$project.id; sessionId=$sessionId
    definition=[ordered]@{
        agentFirstEvent="first SSE data event (normally running status)"
        agentFirstVisible="first delivery or question event; current API has no token delta event"
        ragQps="successful completed / client wall-clock seconds"
    }
    agent=[ordered]@{
        sequential=Summarize-Agent $agentSequential
        concurrent=Summarize-Agent $agentConcurrent
        trace=$traceSummary
    }
    rag=$ragStages
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$jsonPath = Join-Path $OutputDir "runtime-performance-$runId.json"
$mdPath = Join-Path $OutputDir "runtime-performance-$runId.md"
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding utf8
$seq=$result.agent.sequential; $con=$result.agent.concurrent
$rows = $ragStages | ForEach-Object { "| $($_.concurrency) | $($_.succeeded)/$($_.requests) | $($_.qps) | $($_.p50Ms) | $($_.p95Ms) | $($_.p99Ms) |" }
@"
# Runtime performance baseline $runId

- Commit: `$gitCommit`
- Project/session: `$($project.id)` / `$sessionId`
- Agent sequential: success `$($seq.succeeded)/$($seq.requests)`, first-visible P50/P95 `$($seq.firstVisibleP50Ms)/$($seq.firstVisibleP95Ms) ms`, total P50/P95 `$($seq.totalP50Ms)/$($seq.totalP95Ms) ms`
- Agent concurrent: success `$($con.succeeded)/$($con.requests)`, first-visible P50/P95 `$($con.firstVisibleP50Ms)/$($con.firstVisibleP95Ms) ms`, total P50/P95 `$($con.totalP50Ms)/$($con.totalP95Ms) ms`
- Agent trace: model calls avg/min/max `$($traceSummary.modelCallsAverage)/$($traceSummary.modelCallsMin)/$($traceSummary.modelCallsMax)`, average tokens `$($traceSummary.totalTokensAverage)`, average task duration `$($traceSummary.durationAverageMs) ms`

| RAG concurrency | Success | QPS | P50 ms | P95 ms | P99 ms |
|---:|---:|---:|---:|---:|---:|
$($rows -join "`n")
"@ | Set-Content -LiteralPath $mdPath -Encoding utf8

Write-Host "Result: $jsonPath"
Write-Host "Agent sequential first-visible P95=$($seq.firstVisibleP95Ms)ms total P95=$($seq.totalP95Ms)ms"
Write-Host "Agent concurrent first-visible P95=$($con.firstVisibleP95Ms)ms total P95=$($con.totalP95Ms)ms"
$ragStages | Select-Object concurrency,requests,succeeded,failed,qps,p50Ms,p95Ms,p99Ms | Format-Table
