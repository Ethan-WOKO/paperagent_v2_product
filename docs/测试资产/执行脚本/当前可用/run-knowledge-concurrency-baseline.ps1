param(
    [string]$BaseUrl = "http://localhost:8080",
    [ValidateRange(1, 50)]
    [int]$Concurrency = 6,
    [ValidateRange(1, 200)]
    [int]$Documents = 12,
    [ValidateRange(1, 1024)]
    [int]$DocumentSizeKb = 8,
    [ValidateRange(10, 1800)]
    [int]$TimeoutSec = 300,
    [string]$OutputDir = "docs/测试资产/运行结果",
    [string]$InviteCode,
    [string]$Username,
    [switch]$Cleanup
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [System.Text.UTF8Encoding]::new()

if (-not $InviteCode -and (Test-Path ".env")) {
    $inviteLine = Get-Content -LiteralPath ".env" |
        Where-Object { $_ -match '^INVITE_CODES=' } |
        Select-Object -First 1
    if ($inviteLine) {
        $configuredCodes = (($inviteLine -split '=', 2)[1]).Trim().Trim([char]34)
        $InviteCode = ($configuredCodes -split ',')[0].Trim()
    }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [string]$Token = $null,
        [int]$RequestTimeoutSec = 30
    )
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $parameters = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $headers
        TimeoutSec = $RequestTimeoutSec
        SkipHttpErrorCheck = $true
        StatusCodeVariable = "statusCode"
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 10 -Compress
    }
    $response = Invoke-RestMethod @parameters
    [pscustomobject]@{ Status = [int]$statusCode; Body = $response }
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if ($Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling($sorted.Count * $Percentile) - 1
    $index = [Math]::Max(0, [Math]::Min($index, $sorted.Count - 1))
    [Math]::Round([double]$sorted[$index], 2)
}

$health = Invoke-Api -Method GET -Path "/actuator/health" -RequestTimeoutSec 10
if ($health.Status -ne 200 -or $health.Body.status -ne "UP") {
    throw "Backend is not healthy at $BaseUrl"
}

$startedAt = Get-Date
$runId = $startedAt.ToString("yyyyMMdd-HHmmss")
$gitCommit = (git rev-parse HEAD).Trim()
$worktreeChanges = @(git status --short --untracked-files=all | Where-Object { $_ -notmatch '^\?\? \.runtime/' })
$username = if ($Username) { $Username } else { "kb_load_$($startedAt.ToString('yyyyMMddHHmmss'))" }
$password = "password123"
$auth = if ($Username) {
    Invoke-Api -Method POST -Path "/api/v1/auth/login" -Body @{
        username = $username
        password = $password
    }
} else {
    Invoke-Api -Method POST -Path "/api/v1/auth/register" -Body @{
        username = $username
        password = $password
        inviteCode = $InviteCode
    }
}
if ($auth.Status -notin @(200, 201) -or -not $auth.Body.accessToken) {
    throw "Unable to authenticate load-test user (HTTP $($auth.Status)); pass -Username to reuse an existing kb_load account"
}
$token = $auth.Body.accessToken
$markerPrefix = "KBLOAD-$runId"

Write-Host "Knowledge concurrency baseline runId=$runId commit=$gitCommit"
Write-Host "concurrency=$Concurrency documents=$Documents documentSizeKb=$DocumentSizeKb"

$uploadJob = {
    param($BaseUrl, $Token, $Index, $MarkerPrefix, $DocumentSizeKb)
    Add-Type -AssemblyName System.Net.Http
    $marker = "$MarkerPrefix-DOC-$Index"
    $uploadId = "$MarkerPrefix-UPLOAD-$Index"
    $filename = "kb-load-$Index.md"
    $sentence = "$marker proves concurrent knowledge ingestion remains searchable and isolated. "
    $targetCharacters = $DocumentSizeKb * 1024
    $content = ($sentence * [Math]::Ceiling($targetCharacters / $sentence.Length)).Substring(0, $targetCharacters)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($content)
    $chunkSize = 4 * 1024
    $totalChunks = [Math]::Ceiling($bytes.Length / $chunkSize)
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(120)
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)

    function Send-Chunk {
        param([int]$ChunkNumber)
        $offset = $ChunkNumber * $chunkSize
        $length = [Math]::Min($chunkSize, $bytes.Length - $offset)
        $chunkBytes = [byte[]]::new($length)
        [Array]::Copy($bytes, $offset, $chunkBytes, 0, $length)
        $multipart = [System.Net.Http.MultipartFormDataContent]::new()
        $file = [System.Net.Http.ByteArrayContent]::new($chunkBytes)
        $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("text/markdown; charset=utf-8")
        $multipart.Add($file, "file", "chunk-$ChunkNumber")
        $multipart.Add([System.Net.Http.StringContent]::new($uploadId), "uploadId")
        $multipart.Add([System.Net.Http.StringContent]::new($filename), "filename")
        $multipart.Add([System.Net.Http.StringContent]::new([string]$ChunkNumber), "chunkNumber")
        $multipart.Add([System.Net.Http.StringContent]::new([string]$totalChunks), "totalChunks")
        try {
            $response = $client.PostAsync("$BaseUrl/api/v1/upload/chunk", $multipart).Result
            $raw = $response.Content.ReadAsStringAsync().Result
            [pscustomobject]@{ Ok = $response.IsSuccessStatusCode; Status = [int]$response.StatusCode; Raw = $raw }
        } finally {
            $multipart.Dispose()
        }
    }

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $chunkResponses = for ($chunkNumber = 0; $chunkNumber -lt $totalChunks; $chunkNumber++) {
            Send-Chunk -ChunkNumber $chunkNumber
        }
        $failedChunk = @($chunkResponses | Where-Object { -not $_.Ok } | Select-Object -First 1)
        if ($failedChunk.Count -gt 0) {
            throw "chunk upload failed (HTTP $($failedChunk[0].Status)): $($failedChunk[0].Raw)"
        }
        $chunkReplay = Send-Chunk -ChunkNumber 0
        if (-not $chunkReplay.Ok) {
            throw "chunk replay failed (HTTP $($chunkReplay.Status)): $($chunkReplay.Raw)"
        }

        $mergeJson = @{
            uploadId = $uploadId
            filename = $filename
            totalChunks = $totalChunks
            isPublic = $false
            mimeType = "text/markdown"
        } | ConvertTo-Json -Compress
        $mergeContent = [System.Net.Http.StringContent]::new($mergeJson, [System.Text.Encoding]::UTF8, "application/json")
        $response = $client.PostAsync("$BaseUrl/api/v1/upload/merge", $mergeContent).Result
        $raw = $response.Content.ReadAsStringAsync().Result
        $mergeContent.Dispose()
        $body = $null
        try { $body = $raw | ConvertFrom-Json } catch {}
        if (-not $response.IsSuccessStatusCode -or -not $body.id) {
            throw "merge failed (HTTP $([int]$response.StatusCode)): $raw"
        }
        $replayContent = [System.Net.Http.StringContent]::new($mergeJson, [System.Text.Encoding]::UTF8, "application/json")
        $replayResponse = $client.PostAsync("$BaseUrl/api/v1/upload/merge", $replayContent).Result
        $replayRaw = $replayResponse.Content.ReadAsStringAsync().Result
        $replayContent.Dispose()
        $replayBody = $null
        try { $replayBody = $replayRaw | ConvertFrom-Json } catch {}
        $idempotentReplay = $replayResponse.IsSuccessStatusCode -and $replayBody.id -eq $body.id
        $watch.Stop()
        [pscustomobject]@{
            index = $Index
            marker = $marker
            accepted = $response.IsSuccessStatusCode -and $idempotentReplay
            httpStatus = [int]$response.StatusCode
            documentId = $body.id
            initialStatus = $body.status
            chunkCount = $totalChunks
            idempotentReplay = $idempotentReplay
            uploadDurationMs = [Math]::Round($watch.Elapsed.TotalMilliseconds, 2)
            acceptedAt = (Get-Date).ToString("o")
            error = if ($idempotentReplay) { $null } else { "merge replay did not return document $($body.id): $replayRaw" }
        }
    } catch {
        $watch.Stop()
        [pscustomobject]@{
            index = $Index
            marker = $marker
            accepted = $false
            httpStatus = 0
            documentId = $null
            initialStatus = $null
            chunkCount = $totalChunks
            idempotentReplay = $false
            uploadDurationMs = [Math]::Round($watch.Elapsed.TotalMilliseconds, 2)
            acceptedAt = (Get-Date).ToString("o")
            error = $_.Exception.Message
        }
    } finally {
        $client.Dispose()
    }
}

$pending = [System.Collections.Generic.List[object]]::new()
$uploads = [System.Collections.Generic.List[object]]::new()
for ($index = 1; $index -le $Documents; $index++) {
    while ($pending.Count -ge $Concurrency) {
        $done = Wait-Job -Job $pending -Any
        $uploads.Add((Receive-Job -Job $done)) | Out-Null
        Remove-Job -Job $done
        $pending.Remove($done) | Out-Null
    }
    $pending.Add((Start-Job -ScriptBlock $uploadJob -ArgumentList $BaseUrl, $token, $index, $markerPrefix, $DocumentSizeKb)) | Out-Null
}
while ($pending.Count -gt 0) {
    $done = Wait-Job -Job $pending -Any
    $uploads.Add((Receive-Job -Job $done)) | Out-Null
    Remove-Job -Job $done
    $pending.Remove($done) | Out-Null
}

$accepted = @($uploads | Where-Object accepted)
$acceptedIds = @($accepted | ForEach-Object { [long]$_.documentId })
$terminal = @{}
$observedStatuses = [System.Collections.Generic.HashSet[string]]::new()
$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ($terminal.Count -lt $acceptedIds.Count -and (Get-Date) -lt $deadline) {
    $list = Invoke-Api -Method GET -Path "/api/v1/kb/documents" -Token $token -RequestTimeoutSec 15
    if ($list.Status -ne 200) { throw "Document polling failed (HTTP $($list.Status))" }
    foreach ($document in @($list.Body | Where-Object { $acceptedIds -contains [long]$_.id })) {
        [void]$observedStatuses.Add([string]$document.status)
        if ($document.status -in @("READY", "FAILED")) {
            $terminal[[long]$document.id] = [pscustomobject]@{
                status = [string]$document.status
                error = $document.errorMessage
                terminalAt = Get-Date
            }
        }
    }
    if ($terminal.Count -lt $acceptedIds.Count) { Start-Sleep -Milliseconds 500 }
}

$finishedAt = Get-Date
$documentResults = foreach ($upload in @($uploads | Sort-Object index)) {
    $outcome = if ($upload.documentId) { $terminal[[long]$upload.documentId] } else { $null }
    $acceptedAt = [datetimeoffset]::Parse($upload.acceptedAt)
    [pscustomobject]@{
        index = $upload.index
        documentId = $upload.documentId
        marker = $upload.marker
        httpStatus = $upload.httpStatus
        accepted = $upload.accepted
        initialStatus = $upload.initialStatus
        chunkCount = $upload.chunkCount
        idempotentReplay = $upload.idempotentReplay
        finalStatus = if ($outcome) { $outcome.status } elseif ($upload.accepted) { "TIMEOUT" } else { "REJECTED" }
        uploadDurationMs = $upload.uploadDurationMs
        processingDurationMs = if ($outcome) { [Math]::Round(($outcome.terminalAt - $acceptedAt.LocalDateTime).TotalMilliseconds, 2) } else { $null }
        error = if ($outcome -and $outcome.error) { $outcome.error } else { $upload.error }
    }
}

$ready = @($documentResults | Where-Object finalStatus -eq "READY")
$failed = @($documentResults | Where-Object finalStatus -eq "FAILED")
$timedOut = @($documentResults | Where-Object finalStatus -eq "TIMEOUT")
$rejected = @($documentResults | Where-Object finalStatus -eq "REJECTED")
$uploadDurations = @($documentResults | ForEach-Object { [double]$_.uploadDurationMs })
$processingDurations = @($ready | ForEach-Object { [double]$_.processingDurationMs })
$durationSeconds = [Math]::Max(0.001, ($finishedAt - $startedAt).TotalSeconds)
$searchVerified = $false
if ($ready.Count -gt 0) {
    $probe = $ready[0]
    $search = Invoke-Api -Method POST -Path "/api/v1/search" -Token $token -RequestTimeoutSec 60 -Body @{
        query = $probe.marker
        topK = 5
    }
    $searchVerified = $search.Status -eq 200 -and (($search.Body | ConvertTo-Json -Depth 10 -Compress) -match [regex]::Escape($probe.marker))
}

$summary = [ordered]@{
    runId = $runId
    gitCommit = $gitCommit
    workingTreeDirty = $worktreeChanges.Count -gt 0
    startedAt = $startedAt.ToString("o")
    finishedAt = $finishedAt.ToString("o")
    baseUrl = $BaseUrl
    concurrency = $Concurrency
    documents = $Documents
    documentSizeKb = $DocumentSizeKb
    timeoutSec = $TimeoutSec
    accepted = $accepted.Count
    rejected = $rejected.Count
    ready = $ready.Count
    failed = $failed.Count
    timedOut = $timedOut.Count
    idempotentReplayVerified = @($documentResults | Where-Object idempotentReplay).Count
    searchVerified = $searchVerified
    observedStatuses = @($observedStatuses | Sort-Object)
    durationSec = [Math]::Round($durationSeconds, 2)
    throughputDocumentsPerSec = [Math]::Round($ready.Count / $durationSeconds, 3)
    uploadP50Ms = Get-Percentile -Values $uploadDurations -Percentile 0.50
    uploadP95Ms = Get-Percentile -Values $uploadDurations -Percentile 0.95
    processingP50Ms = Get-Percentile -Values $processingDurations -Percentile 0.50
    processingP95Ms = Get-Percentile -Values $processingDurations -Percentile 0.95
    documentsDetail = @($documentResults)
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$jsonPath = Join-Path $OutputDir "knowledge-concurrency-$runId.json"
$mdPath = Join-Path $OutputDir "knowledge-concurrency-$runId.md"
$summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$markdown = @(
    "# Knowledge Concurrency Baseline $runId",
    "",
    "- Git commit: ``$gitCommit``",
    "- Working tree dirty: $($summary.workingTreeDirty)",
    "- Load: $Documents documents x ${DocumentSizeKb} KiB, concurrency $Concurrency",
    "- Result: accepted=$($accepted.Count), ready=$($ready.Count), failed=$($failed.Count), rejected=$($rejected.Count), timedOut=$($timedOut.Count)",
    "- Duration: $($summary.durationSec) s; throughput: $($summary.throughputDocumentsPerSec) documents/s",
    "- Upload P50/P95: $($summary.uploadP50Ms)/$($summary.uploadP95Ms) ms",
    "- Processing P50/P95: $($summary.processingP50Ms)/$($summary.processingP95Ms) ms",
    "- Search verification: $searchVerified",
    "- Observed statuses: $($summary.observedStatuses -join ', ')",
    "",
    "- Idempotent replay verified: $($summary.idempotentReplayVerified)/$Documents",
    "",
    "| # | Document | Chunks | Idempotent | HTTP | Initial | Final | Upload ms | Processing ms | Error |",
    "|---:|---:|---:|---:|---:|---|---|---:|---:|---|"
)
foreach ($result in $documentResults) {
    $safeError = ([string]$result.error).Replace("|", "/").Replace("`r", " ").Replace("`n", " ")
    $markdown += "| $($result.index) | $($result.documentId) | $($result.chunkCount) | $($result.idempotentReplay) | $($result.httpStatus) | $($result.initialStatus) | $($result.finalStatus) | $($result.uploadDurationMs) | $($result.processingDurationMs) | $safeError |"
}
$markdown | Set-Content -LiteralPath $mdPath -Encoding UTF8

if ($Cleanup) {
    foreach ($documentId in $acceptedIds) {
        $delete = Invoke-Api -Method DELETE -Path "/api/v1/kb/documents/$documentId" -Token $token -RequestTimeoutSec 30
        if ($delete.Status -ne 204) { Write-Warning "Cleanup failed for document $documentId (HTTP $($delete.Status))" }
    }
}

Write-Host "RESULT accepted=$($accepted.Count) ready=$($ready.Count) failed=$($failed.Count) rejected=$($rejected.Count) timedOut=$($timedOut.Count)"
Write-Host "P95 upload=$($summary.uploadP95Ms)ms processing=$($summary.processingP95Ms)ms throughput=$($summary.throughputDocumentsPerSec)docs/s search=$searchVerified"
Write-Host "JSON $jsonPath"
Write-Host "MD   $mdPath"

if ($rejected.Count -gt 0 -or $failed.Count -gt 0 -or $timedOut.Count -gt 0 -or -not $searchVerified) { exit 1 }
