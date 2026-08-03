param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$TimeoutSec = 120,
    [string[]]$Providers = @("deepseek"),
    [string]$DeepSeekModel = "deepseek-v4-flash",
    [string]$GlmModel = "glm-4.5-air",
    [string]$EvalInviteCode = $env:EVAL_INVITE_CODE,
    [ValidateSet("markdown", "mixed")]
    [string]$FixtureMode = "markdown",
    [switch]$RunPlanExecution,
    [string]$OutputDir = "docs/测试资产/运行结果",
    [switch]$IncludeGlm
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [System.Text.UTF8Encoding]::new()
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Load-DotEnv {
    param([string]$Path = ".env")
    if (-not (Test-Path $Path)) {
        return
    }
    Get-Content $Path | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
            $name = $matches[1].Trim()
            $value = $matches[2].Trim().Trim('"')
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

function Short-Text {
    param($Value, [int]$Max = 300)
    if ($null -eq $Value) {
        return ""
    }
    if ($Value -is [string]) {
        $text = $Value
    } else {
        $text = ($Value | ConvertTo-Json -Depth 30 -Compress)
    }
    $text = $text -replace '\s+', ' '
    if ($text.Length -le $Max) {
        return $text
    }
    return $text.Substring(0, $Max) + "..."
}

function To-BodyText {
    param($Value)
    if ($null -eq $Value) {
        return ""
    }
    if ($Value -is [string]) {
        return $Value
    }
    return ($Value | ConvertTo-Json -Depth 30 -Compress)
}

function Invoke-JsonApi {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [string]$Token = $null,
        [int]$Timeout = $TimeoutSec
    )
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
        "PATCH" { [System.Net.Http.HttpMethod]::Patch }
        "DELETE" { [System.Net.Http.HttpMethod]::Delete }
        default { [System.Net.Http.HttpMethod]::new($Method.ToUpperInvariant()) }
    }
    $request = [System.Net.Http.HttpRequestMessage]::new($methodObject, "$BaseUrl$Path")
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 40
        $request.Content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
    }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $client.SendAsync($request).Result
        $sw.Stop()
        $raw = $response.Content.ReadAsStringAsync().Result
        $parsed = $null
        if ($raw) {
            try {
                $parsed = $raw | ConvertFrom-Json
            } catch {
                $parsed = $raw
            }
        }
        return [pscustomobject]@{
            Ok = $response.IsSuccessStatusCode
            Status = [int]$response.StatusCode
            Body = $parsed
            Raw = $raw
            DurationMs = [int]$sw.ElapsedMilliseconds
            Error = $null
        }
    } catch {
        $sw.Stop()
        return [pscustomobject]@{
            Ok = $false
            Status = 0
            Body = $null
            Raw = ""
            DurationMs = [int]$sw.ElapsedMilliseconds
            Error = $_.Exception.Message
        }
    } finally {
        if ($request) {
            $request.Dispose()
        }
        $client.Dispose()
    }
}

function Add-Result {
    param(
        [string]$Id,
        [string]$Provider = "global",
        [ValidateSet("PASS", "FAIL", "SKIP")]
        [string]$Status,
        [int]$Score = 0,
        [int]$DurationMs = 0,
        [string]$Note = "",
        $Excerpt = "",
        [string]$Error = ""
    )
    $script:Results.Add([pscustomobject]@{
        id = $Id
        provider = $Provider
        status = $Status
        pass = ($Status -eq "PASS")
        score = $Score
        durationMs = $DurationMs
        note = $Note
        excerpt = (Short-Text $Excerpt)
        error = $Error
    }) | Out-Null
    Write-Host ("[{0}] {1} provider={2} score={3} durationMs={4} {5}" -f $Status, $Id, $Provider, $Score, $DurationMs, $Note)
}

function Add-BooleanResult {
    param(
        [string]$Id,
        [string]$Provider = "global",
        [bool]$Pass,
        [int]$PassScore = 5,
        [int]$FailScore = 0,
        [int]$DurationMs = 0,
        [string]$Note = "",
        $Excerpt = "",
        [string]$Error = ""
    )
    $status = if ($Pass) { "PASS" } else { "FAIL" }
    $score = if ($Pass) { $PassScore } else { $FailScore }
    Add-Result -Id $Id -Provider $Provider -Status $status -Score $score -DurationMs $DurationMs -Note $Note -Excerpt $Excerpt -Error $Error
}

function Contains-Any {
    param([string]$Text, [string[]]$Needles)
    foreach ($needle in $Needles) {
        if ($Text -match [regex]::Escape($needle)) {
            return $true
        }
    }
    return $false
}

function Get-Token {
    param([string]$Username, [string]$Password = "password123")
    $registerBody = @{ username = $Username; password = $Password }
    if ($script:ResolvedEvalInviteCode) {
        $registerBody.inviteCode = $script:ResolvedEvalInviteCode
    }
    $register = Invoke-JsonApi -Method "POST" -Path "/api/v1/auth/register" -Body $registerBody -Timeout 30
    if ($register.Status -eq 201 -and $register.Body.accessToken) {
        return $register.Body.accessToken
    }
    $login = Invoke-JsonApi -Method "POST" -Path "/api/v1/auth/login" -Body @{ username = $Username; password = $Password } -Timeout 30
    if ($login.Status -eq 200 -and $login.Body.accessToken) {
        return $login.Body.accessToken
    }
    throw "Cannot register/login $Username. register=$($register.Status) registerBody=$(Short-Text $register.Raw 180) login=$($login.Status) loginBody=$(Short-Text $login.Raw 180)"
}

function New-Session {
    param(
        [string]$Token,
        [string]$Title,
        [bool]$RagDisabled = $false,
        [string]$Provider = "deepseek",
        [string]$Model = $script:DeepSeekModel,
        [int]$MaxSteps = 6
    )
    $response = Invoke-JsonApi -Method "POST" -Path "/api/v1/agent/sessions" -Token $Token -Body @{
        title = $Title
        modelProvider = $Provider
        model = $Model
        maxSteps = $MaxSteps
        ragDisabled = $RagDisabled
    } -Timeout 30
    if ($response.Status -ne 201) {
        throw "Create session failed: $($response.Status) $(Short-Text $response.Raw)"
    }
    return [long]$response.Body.id
}

function Send-Message {
    param(
        [string]$Token,
        [long]$SessionId,
        [string]$Content,
        [bool]$RagDisabled = $false,
        [int]$Timeout = $TimeoutSec
    )
    return Invoke-JsonApi -Method "POST" -Path "/api/v1/agent/sessions/$SessionId/messages" -Token $Token -Body @{
        content = $Content
        ragDisabled = $RagDisabled
    } -Timeout $Timeout
}

function Get-ContentType {
    param([string]$Path)
    switch ([System.IO.Path]::GetExtension($Path).ToLowerInvariant()) {
        ".pdf" { return "application/pdf" }
        ".docx" { return "application/vnd.openxmlformats-officedocument.wordprocessingml.document" }
        ".md" { return "text/markdown; charset=utf-8" }
        ".txt" { return "text/plain; charset=utf-8" }
        default { return "application/octet-stream" }
    }
}

function Upload-File {
    param(
        [string]$Token,
        [string]$Path,
        [bool]$IsPublic = $false
    )
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $fileContent = [System.Net.Http.ByteArrayContent]::new($bytes)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse((Get-ContentType -Path $Path))
    $multipart.Add($fileContent, "file", [System.IO.Path]::GetFileName($Path))
    $multipart.Add([System.Net.Http.StringContent]::new($IsPublic.ToString().ToLowerInvariant()), "isPublic")
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $client.PostAsync("$BaseUrl/api/v1/kb/documents/simple-upload", $multipart).Result
        $sw.Stop()
        $raw = $response.Content.ReadAsStringAsync().Result
        $parsed = $null
        if ($raw) {
            try {
                $parsed = $raw | ConvertFrom-Json
            } catch {
                $parsed = $raw
            }
        }
        return [pscustomobject]@{
            Ok = $response.IsSuccessStatusCode
            Status = [int]$response.StatusCode
            Body = $parsed
            Raw = $raw
            DurationMs = [int]$sw.ElapsedMilliseconds
            Error = $null
        }
    } catch {
        $sw.Stop()
        return [pscustomobject]@{
            Ok = $false
            Status = 0
            Body = $null
            Raw = ""
            DurationMs = [int]$sw.ElapsedMilliseconds
            Error = $_.Exception.Message
        }
    } finally {
        $multipart.Dispose()
        $client.Dispose()
    }
}

function Escape-Xml {
    param([string]$Text)
    return [System.Security.SecurityElement]::Escape($Text)
}

function Escape-PdfText {
    param([string]$Text)
    return $Text.Replace("\", "\\").Replace("(", "\(").Replace(")", "\)")
}

function New-MinimalPdf {
    param([string]$Path, [string[]]$Lines)
    $escapedLines = @()
    foreach ($line in $Lines) {
        $escapedLines += "(" + (Escape-PdfText -Text $line) + ") Tj"
        $escapedLines += "0 -16 Td"
    }
    $stream = "BT`n/F1 11 Tf`n72 740 Td`n" + ($escapedLines -join "`n") + "`nET`n"
    $streamLength = [System.Text.Encoding]::ASCII.GetByteCount($stream)
    $objects = @(
        "1 0 obj`n<< /Type /Catalog /Pages 2 0 R >>`nendobj`n",
        "2 0 obj`n<< /Type /Pages /Kids [3 0 R] /Count 1 >>`nendobj`n",
        "3 0 obj`n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>`nendobj`n",
        "4 0 obj`n<< /Length $streamLength >>`nstream`n$stream" + "endstream`nendobj`n",
        "5 0 obj`n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>`nendobj`n"
    )
    $enc = [System.Text.Encoding]::ASCII
    $pdf = "%PDF-1.4`n"
    $offsets = @()
    foreach ($object in $objects) {
        $offsets += $enc.GetByteCount($pdf)
        $pdf += $object
    }
    $xrefOffset = $enc.GetByteCount($pdf)
    $xref = "xref`n0 6`n0000000000 65535 f `n"
    foreach ($offset in $offsets) {
        $xref += ("{0:0000000000} 00000 n `n" -f $offset)
    }
    $pdf += $xref
    $pdf += "trailer`n<< /Root 1 0 R /Size 6 >>`nstartxref`n$xrefOffset`n%%EOF`n"
    [System.IO.File]::WriteAllBytes($Path, $enc.GetBytes($pdf))
}

function Add-ZipText {
    param($Zip, [string]$EntryName, [string]$Text)
    $entry = $Zip.CreateEntry($EntryName)
    $stream = $entry.Open()
    try {
        $writer = [System.IO.StreamWriter]::new($stream, [System.Text.UTF8Encoding]::new($false))
        try {
            $writer.Write($Text)
        } finally {
            $writer.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function New-MinimalDocx {
    param([string]$Path, [string[]]$Lines)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path $Path) {
        Remove-Item -LiteralPath $Path -Force
    }
    $zip = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        Add-ZipText -Zip $zip -EntryName "[Content_Types].xml" -Text '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>'
        Add-ZipText -Zip $zip -EntryName "_rels/.rels" -Text '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>'
        $paragraphs = ""
        foreach ($line in $Lines) {
            $paragraphs += '<w:p><w:r><w:t>' + (Escape-Xml -Text $line) + '</w:t></w:r></w:p>'
        }
        $docXml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>' + $paragraphs + '<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>'
        Add-ZipText -Zip $zip -EntryName "word/document.xml" -Text $docXml
    } finally {
        $zip.Dispose()
    }
}

function New-KbFixtures {
    param(
        [string]$Provider,
        [string]$RunId,
        [string]$Mode,
        [string]$FixtureRoot,
        [hashtable]$Keys
    )
    $dir = Join-Path $FixtureRoot "$Provider-$RunId"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $doc1Lines = @(
        "$($Keys.mentorKey): Zhang Mingyuan.",
        "$($Keys.phoneKey): not documented.",
        "$($Keys.meetingKey): Wednesday 14:00.",
        "$($Keys.privateKey): belongs only to Alice.",
        "$($Keys.locationKey): Room 402.",
        "$($Keys.deadlineKey): Friday 18:00."
    )
    $doc2Lines = @(
        "$($Keys.modelKey): DeepSeek.",
        "$($Keys.maxStepsKey): 5 to 100.",
        "$($Keys.ingestKey): Tika extraction.",
        "$($Keys.chunkingKey): semantic chunks."
    )
    $doc3Lines = @(
        "$($Keys.paperKey): OpenAlex literature recommendation export.",
        "$($Keys.fileTypeKey): PDF, DOCX, and Markdown.",
        "$($Keys.evalMetricKey): citation precision and answer accuracy.",
        "$($Keys.rerankPolicyKey): phrase match, term coverage, and vector score.",
        "$($Keys.architectureKey): retriever, reranker, generator, and citation renderer."
    )

    if ($Mode -eq "mixed") {
        $notes = Join-Path $dir "yanban-lab-notes-$Provider-$RunId.pdf"
        $research = Join-Path $dir "research-plan-$Provider-$RunId.docx"
        $paper = Join-Path $dir "paper-checklist-$Provider-$RunId.md"
        New-MinimalPdf -Path $notes -Lines $doc1Lines
        New-MinimalDocx -Path $research -Lines $doc2Lines
        Set-Content -Path $paper -Value ($doc3Lines -join [Environment]::NewLine) -Encoding UTF8
    } else {
        $notes = Join-Path $dir "yanban-lab-notes-$Provider-$RunId.md"
        $research = Join-Path $dir "research-plan-$Provider-$RunId.md"
        $paper = Join-Path $dir "paper-checklist-$Provider-$RunId.md"
        Set-Content -Path $notes -Value ($doc1Lines -join [Environment]::NewLine) -Encoding UTF8
        Set-Content -Path $research -Value ($doc2Lines -join [Environment]::NewLine) -Encoding UTF8
        Set-Content -Path $paper -Value ($doc3Lines -join [Environment]::NewLine) -Encoding UTF8
    }

    return @($notes, $research, $paper)
}

function Get-ProviderConfig {
    param([string]$Provider)
    switch ($Provider) {
        "deepseek" {
            return [pscustomobject]@{
                Name = "deepseek"
                Model = $script:DeepSeekModel
                ApiKey = $env:DEEPSEEK_API_KEY
                KeyName = "DEEPSEEK_API_KEY"
            }
        }
        "glm" {
            return [pscustomobject]@{
                Name = "glm"
                Model = $script:GlmModel
                ApiKey = $env:GLM_API_KEY
                KeyName = "GLM_API_KEY"
            }
        }
        default {
            throw "Unsupported provider: $Provider"
        }
    }
}

function Set-UserSettings {
    param(
        [string]$Token,
        [string]$Provider,
        [string]$ProviderKey,
        [string]$DeepSeekModelName = $script:DeepSeekModel,
        [string]$GlmModelName = $script:GlmModel
    )
    $settingsBody = @{
        defaultProvider = $Provider
        deepseekApiKey = $env:DEEPSEEK_API_KEY
        glmApiKey = $env:GLM_API_KEY
        deepseekModel = $DeepSeekModelName
        glmModel = $GlmModelName
        deepseekTemperature = 0.2
        maxSteps = 6
        ragDefaultEnabled = $true
        filesystemRoots = @()
        disabledSkills = @()
    }
    return Invoke-JsonApi -Method "PUT" -Path "/api/v1/settings" -Token $Token -Body $settingsBody -Timeout 30
}

function Test-PlanExecutionResult {
    param($Plan, $Events)
    if ($null -eq $Plan -or $Plan.status -ne "COMPLETED") {
        return $false
    }
    foreach ($step in @($Plan.steps)) {
        if (@("COMPLETED", "DEGRADED", "SUPERSEDED") -notcontains [string]$step.status) {
            return $false
        }
    }
    $eventTypes = @($Events | ForEach-Object { $_.eventType })
    foreach ($required in @("plan_queued", "plan_started", "step_started", "step_completed", "plan_completed")) {
        if ($eventTypes -notcontains $required) {
            return $false
        }
    }
    return $true
}

function Test-RagQaSet {
    param(
        [string]$Token,
        [string]$Provider,
        [hashtable]$Keys
    )
    $facts = @(
        [pscustomobject]@{ id = "mentor"; key = $Keys.mentorKey; expected = "Zhang Mingyuan"; expectedFile = "yanban-lab-notes" },
        [pscustomobject]@{ id = "phone"; key = $Keys.phoneKey; expected = "not documented"; expectedFile = "yanban-lab-notes" },
        [pscustomobject]@{ id = "meeting"; key = $Keys.meetingKey; expected = "Wednesday 14:00"; expectedFile = "yanban-lab-notes" },
        [pscustomobject]@{ id = "private"; key = $Keys.privateKey; expected = "belongs only to Alice"; expectedFile = "yanban-lab-notes" },
        [pscustomobject]@{ id = "location"; key = $Keys.locationKey; expected = "Room 402"; expectedFile = "yanban-lab-notes" },
        [pscustomobject]@{ id = "deadline"; key = $Keys.deadlineKey; expected = "Friday 18:00"; expectedFile = "yanban-lab-notes" },
        [pscustomobject]@{ id = "model"; key = $Keys.modelKey; expected = "DeepSeek"; expectedFile = "research-plan" },
        [pscustomobject]@{ id = "max_steps"; key = $Keys.maxStepsKey; expected = "5 to 100"; expectedFile = "research-plan" },
        [pscustomobject]@{ id = "ingestion"; key = $Keys.ingestKey; expected = "Tika extraction"; expectedFile = "research-plan" },
        [pscustomobject]@{ id = "chunking"; key = $Keys.chunkingKey; expected = "semantic chunks"; expectedFile = "research-plan" },
        [pscustomobject]@{ id = "paper"; key = $Keys.paperKey; expected = "OpenAlex"; expectedFile = "paper-checklist" },
        [pscustomobject]@{ id = "file_types"; key = $Keys.fileTypeKey; expected = "PDF, DOCX, and Markdown"; expectedFile = "paper-checklist" },
        [pscustomobject]@{ id = "metric"; key = $Keys.evalMetricKey; expected = "citation precision"; expectedFile = "paper-checklist" },
        [pscustomobject]@{ id = "architecture"; key = $Keys.architectureKey; expected = "retriever, reranker, generator"; expectedFile = "paper-checklist" },
        [pscustomobject]@{ id = "rerank"; key = $Keys.rerankPolicyKey; expected = "phrase match, term coverage, and vector score"; expectedFile = "paper-checklist" }
    )
    $queryTemplates = @(
        "{0}",
        "Find the exact answer for {0}.",
        "Which citation supports {0}? Return the cited fact.",
        "Verify {0} from the uploaded knowledge base."
    )
    $cases = [System.Collections.Generic.List[object]]::new()
    foreach ($fact in $facts) {
        for ($i = 0; $i -lt $queryTemplates.Count; $i++) {
            $cases.Add([pscustomobject]@{
                id = "$($fact.id)_v$($i + 1)"
                query = [string]::Format($queryTemplates[$i], $fact.key)
                expected = $fact.expected
                expectedFile = $fact.expectedFile
            }) | Out-Null
        }
    }
    $details = [System.Collections.Generic.List[object]]::new()
    $answerCorrect = 0
    $citationCorrect = 0
    $citationTotal = 0
    $rerankPresent = 0
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    foreach ($case in $cases) {
        $search = Invoke-JsonApi -Method "POST" -Path "/api/v1/search" -Token $Token -Body @{ query = $case.query; topK = 5 } -Timeout 45
        $items = @($search.Body)
        $bodyText = To-BodyText $search.Body
        $first = if ($items.Count -gt 0) { $items[0] } else { $null }
        $expectedPattern = [regex]::Escape([string]$case.expected)
        $filePattern = [regex]::Escape([string]$case.expectedFile)
        $answerOk = $search.Status -eq 200 -and $bodyText -match $expectedPattern
        $citationOk = $false
        if ($first -ne $null -and [string]$first.citationId) {
            $citationTotal++
            $citationOk = ([string]$first.chunkText) -match $expectedPattern -and ([string]$first.filename) -match $filePattern
            if ($first.PSObject.Properties.Name -contains "rerankScore" -and $null -ne $first.rerankScore) {
                $rerankPresent++
            }
        }
        if ($answerOk) {
            $answerCorrect++
        }
        if ($citationOk) {
            $citationCorrect++
        }
        $details.Add([pscustomobject]@{
            id = $case.id
            status = $search.Status
            answerOk = $answerOk
            citationOk = $citationOk
            firstCitationId = if ($first -eq $null) { "" } else { [string]$first.citationId }
            firstFilename = if ($first -eq $null) { "" } else { [string]$first.filename }
            firstScoreBand = if ($first -eq $null) { "" } else { [string]$first.scoreBand }
            firstRerankScore = if ($first -eq $null) { $null } else { $first.rerankScore }
        }) | Out-Null
    }
    $sw.Stop()
    $metrics = [pscustomobject]@{
        provider = $Provider
        caseCount = $cases.Count
        answerAccuracy = [Math]::Round($answerCorrect / [double]$cases.Count, 4)
        citationPrecision = if ($citationTotal -eq 0) { 0 } else { [Math]::Round($citationCorrect / [double]$citationTotal, 4) }
        rerankCoverage = [Math]::Round($rerankPresent / [double]$cases.Count, 4)
        durationMs = [int]$sw.ElapsedMilliseconds
        details = $details
    }
    $script:RagQaMetrics.Add($metrics) | Out-Null
    return $metrics
}

function Wait-PlanTerminal {
    param(
        [string]$Token,
        [long]$PlanId,
        [int]$Timeout = 300,
        [int]$PollMs = 1500
    )
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $last = $null
    while ($sw.Elapsed.TotalSeconds -lt $Timeout) {
        $last = Invoke-JsonApi -Method "GET" -Path "/api/v1/agent/plans/$PlanId" -Token $Token -Timeout 30
        if ($last.Status -eq 200 -and @("COMPLETED", "FAILED", "CANCELLED") -contains [string]$last.Body.status) {
            $sw.Stop()
            return [pscustomobject]@{
                Response = $last
                DurationMs = [int]$sw.ElapsedMilliseconds
                TimedOut = $false
            }
        }
        Start-Sleep -Milliseconds $PollMs
    }
    $sw.Stop()
    return [pscustomobject]@{
        Response = $last
        DurationMs = [int]$sw.ElapsedMilliseconds
        TimedOut = $true
    }
}

function Run-ProviderEval {
    param(
        [string]$Provider,
        [string]$RunId,
        [string]$FixtureMode,
        [string]$FixtureRoot
    )
    $config = Get-ProviderConfig -Provider $Provider
    if (-not $config.ApiKey) {
        Add-Result -Id "SETUP-PROVIDER" -Provider $Provider -Status "SKIP" -Score 0 -DurationMs 0 -Note "$($config.KeyName) missing; provider eval skipped"
        return
    }

    $providerRun = "$Provider-$RunId"
    $aliceUser = "eval_${Provider}_alice_$RunId"
    $bobUser = "eval_${Provider}_bob_$RunId"
    $aliceToken = Get-Token -Username $aliceUser
    $bobToken = Get-Token -Username $bobUser
    Add-Result -Id "SETUP-USERS" -Provider $Provider -Status "PASS" -Score 5 -DurationMs 0 -Note "registered temporary eval users"

    foreach ($token in @($aliceToken, $bobToken)) {
        $settings = Set-UserSettings -Token $token -Provider $Provider -ProviderKey $config.ApiKey -DeepSeekModelName $script:DeepSeekModel -GlmModelName $script:GlmModel
        Add-BooleanResult -Id "SETUP-SETTINGS" -Provider $Provider -Pass ($settings.Status -eq 200) -DurationMs $settings.DurationMs -Note "settings configured" -Excerpt $settings.Raw -Error $settings.Error
    }

    $keys = @{
        mentorKey = "mentor_lookup_$providerRun"
        phoneKey = "mentor_phone_lookup_$providerRun"
        meetingKey = "weekly_meeting_lookup_$providerRun"
        privateKey = "private_lookup_$providerRun"
        locationKey = "lab_location_lookup_$providerRun"
        deadlineKey = "weekly_deadline_lookup_$providerRun"
        modelKey = "default_model_lookup_$providerRun"
        maxStepsKey = "max_steps_lookup_$providerRun"
        ingestKey = "ingestion_pipeline_lookup_$providerRun"
        chunkingKey = "chunking_policy_lookup_$providerRun"
        paperKey = "paper_final_step_lookup_$providerRun"
        fileTypeKey = "file_type_lookup_$providerRun"
        evalMetricKey = "rag_quality_metric_lookup_$providerRun"
        architectureKey = "rag_architecture_lookup_$providerRun"
        rerankPolicyKey = "rerank_policy_lookup_$providerRun"
    }

    $aliceSession = New-Session -Token $aliceToken -Title "Eval RAG $providerRun" -RagDisabled $false -Provider $Provider -Model $config.Model
    $aliceNoRagSession = New-Session -Token $aliceToken -Title "Eval No RAG $providerRun" -RagDisabled $true -Provider $Provider -Model $config.Model
    $bobProbe = Invoke-JsonApi -Method "GET" -Path "/api/v1/agent/sessions/$aliceSession/messages" -Token $bobToken -Timeout 20
    Add-BooleanResult -Id "P0-AUTH-02" -Provider $Provider -Pass ($bobProbe.Status -eq 404 -or $bobProbe.Status -eq 403) -DurationMs $bobProbe.DurationMs -Note "bob cannot read alice session" -Excerpt $bobProbe.Raw

    $fixtureFiles = New-KbFixtures -Provider $Provider -RunId $RunId -Mode $FixtureMode -FixtureRoot $FixtureRoot -Keys $keys
    $uploadSw = [System.Diagnostics.Stopwatch]::StartNew()
    $uploads = @()
    foreach ($file in $fixtureFiles) {
        $uploads += Upload-File -Token $aliceToken -Path $file -IsPublic $false
    }
    $uploadSw.Stop()
    $uploadPass = ($uploads | Where-Object { $_.Status -eq 201 -and $_.Raw -match "READY" }).Count -eq 3
    Add-BooleanResult -Id "P1-KB-01" -Provider $Provider -Pass $uploadPass -DurationMs ([int]$uploadSw.ElapsedMilliseconds) -Note "uploaded 3 private KB docs mode=$FixtureMode" -Excerpt ($uploads | ForEach-Object { $_.Raw })

    $aliceSearch = Invoke-JsonApi -Method "POST" -Path "/api/v1/search" -Token $aliceToken -Body @{ query = $keys.privateKey; topK = 5 } -Timeout 45
    $aliceSearchText = To-BodyText $aliceSearch.Body
    Add-BooleanResult -Id "P0-KB-02" -Provider $Provider -Pass ($aliceSearch.Status -eq 200 -and $aliceSearchText -match $keys.privateKey) -DurationMs $aliceSearch.DurationMs -Note "alice can search her private KB" -Excerpt $aliceSearch.Body

    $bobSearch = Invoke-JsonApi -Method "POST" -Path "/api/v1/search" -Token $bobToken -Body @{ query = $keys.privateKey; topK = 5 } -Timeout 45
    $bobSearchText = To-BodyText $bobSearch.Body
    Add-BooleanResult -Id "P0-KB-01" -Provider $Provider -Pass ($bobSearch.Status -eq 200 -and -not ($bobSearchText -match $keys.privateKey)) -DurationMs $bobSearch.DurationMs -Note "bob cannot search alice private KB" -Excerpt $bobSearch.Body

    $chatSession = New-Session -Token $aliceToken -Title "Eval Chat $providerRun" -RagDisabled $true -Provider $Provider -Model $config.Model
    $chat = Send-Message -Token $aliceToken -SessionId $chatSession -Content "In one sentence, introduce what you can help with." -RagDisabled $true -Timeout 120
    $chatContent = [string]$chat.Body.assistantContent
    Add-BooleanResult -Id "P0-CHAT-01" -Provider $Provider -Pass ($chat.Status -eq 200 -and $chatContent.Length -gt 10) -DurationMs $chat.DurationMs -Note "basic chat response" -Excerpt $chatContent -Error $chat.Error

    $explain = Send-Message -Token $aliceToken -SessionId $chatSession -Content "Explain Retrieval-Augmented Generation for non-technical colleagues in English, under 120 words." -RagDisabled $true -Timeout 120
    $explainContent = [string]$explain.Body.assistantContent
    $explainPass = $explain.Status -eq 200 -and $explainContent.Length -gt 20 -and (Contains-Any -Text $explainContent -Needles @("retrieval", "generation", "knowledge", "RAG"))
    Add-BooleanResult -Id "P1-CHAT-01" -Provider $Provider -Pass $explainPass -PassScore 4 -FailScore 2 -DurationMs $explain.DurationMs -Note "plain RAG explanation quality smoke test" -Excerpt $explainContent -Error $explain.Error

    $common = Send-Message -Token $aliceToken -SessionId $chatSession -Content "List three common benefits of watermelon. Answer directly without browsing unless it is truly needed." -RagDisabled $true -Timeout 120
    $commonContent = [string]$common.Body.assistantContent
    $commonMessages = @($common.Body.messages)
    $commonLooksDirect = -not ($commonContent -match '(?i)\b(search|searched|browse|browsed|web|source|sources|according to search)\b')
    $commonPass = $common.Status -eq 200 -and $commonContent.Length -gt 20 -and $commonMessages.Count -eq 2 -and $common.Body.steps -eq 1 -and $commonLooksDirect
    Add-BooleanResult -Id "P0-CHAT-03" -Provider $Provider -Pass $commonPass -DurationMs $common.DurationMs -Note "common knowledge should answer in one assistant message without tool-search preamble" -Excerpt $commonContent -Error $common.Error

    $identitySession = New-Session -Token $aliceToken -Title "Eval Identity $providerRun" -RagDisabled $true -Provider $Provider -Model $config.Model
    $identity = Send-Message -Token $aliceToken -SessionId $identitySession -Content "What model are you?" -RagDisabled $true -Timeout 60
    $identityContent = [string]$identity.Body.assistantContent
    $identityLeakFree = -not ($identityContent -match '(?i)provider=|model=|backend|runtime|prompt|guard|internal|harness')
    $identityPass = $identity.Status -eq 200 -and $identity.Body.steps -eq 0 -and $identityContent -match [regex]::Escape($config.Model) -and $identityLeakFree
    Add-BooleanResult -Id "P0-IDENTITY-01" -Provider $Provider -Pass $identityPass -DurationMs $identity.DurationMs -Note "identity question should be handled without model call or internal debug wording" -Excerpt $identityContent -Error $identity.Error

    $rag1 = Send-Message -Token $aliceToken -SessionId $aliceSession -Content $keys.mentorKey -RagDisabled $false -Timeout 120
    $rag1Content = [string]$rag1.Body.assistantContent
    Add-BooleanResult -Id "P0-RAG-01" -Provider $Provider -Pass ($rag1.Status -eq 200 -and $rag1Content -match "Zhang Mingyuan") -DurationMs $rag1.DurationMs -Note "RAG answers mentor from KB" -Excerpt $rag1Content -Error $rag1.Error

    $rag2 = Send-Message -Token $aliceToken -SessionId $aliceSession -Content $keys.phoneKey -RagDisabled $false -Timeout 120
    $rag2Content = [string]$rag2.Body.assistantContent
    $noPhone = -not ($rag2Content -match '\b\d{3}[- ]?\d{3}[- ]?\d{4}\b|\b1[3-9]\d{9}\b')
    Add-BooleanResult -Id "P0-RAG-02" -Provider $Provider -Pass ($rag2.Status -eq 200 -and $noPhone -and $rag2Content.Length -gt 5) -DurationMs $rag2.DurationMs -Note "RAG should not invent phone number" -Excerpt $rag2Content -Error $rag2.Error

    $rag3 = Send-Message -Token $aliceToken -SessionId $aliceSession -Content $keys.meetingKey -RagDisabled $false -Timeout 120
    $rag3Content = [string]$rag3.Body.assistantContent
    Add-BooleanResult -Id "P1-RAG-01" -Provider $Provider -Pass ($rag3.Status -eq 200 -and $rag3Content -match "14:00") -DurationMs $rag3.DurationMs -Note "RAG answers weekly meeting time" -Excerpt $rag3Content -Error $rag3.Error

    $rag4 = Send-Message -Token $aliceToken -SessionId $aliceSession -Content $keys.paperKey -RagDisabled $false -Timeout 120
    $rag4Content = [string]$rag4.Body.assistantContent
    Add-BooleanResult -Id "P1-RAG-05" -Provider $Provider -Pass ($rag4.Status -eq 200 -and $rag4Content -match "OpenAlex") -DurationMs $rag4.DurationMs -Note "RAG answers paper workflow final step" -Excerpt $rag4Content -Error $rag4.Error

    if ($FixtureMode -eq "mixed") {
        $typeSearch = Invoke-JsonApi -Method "POST" -Path "/api/v1/search" -Token $aliceToken -Body @{ query = $keys.fileTypeKey; topK = 5 } -Timeout 45
        $typeSearchText = To-BodyText $typeSearch.Body
        $typePass = $typeSearch.Status -eq 200 -and $typeSearchText -match "PDF" -and $typeSearchText -match "DOCX" -and $typeSearchText -match "Markdown"
        Add-BooleanResult -Id "P1-KB-02" -Provider $Provider -Pass $typePass -DurationMs $typeSearch.DurationMs -Note "mixed fixtures are indexed and searchable" -Excerpt $typeSearch.Body -Error $typeSearch.Error
    }

    $ragQa = Test-RagQaSet -Token $aliceToken -Provider $Provider -Keys $keys
    $ragQaPass = $ragQa.answerAccuracy -ge 0.90 -and $ragQa.citationPrecision -ge 0.85 -and $ragQa.rerankCoverage -ge 0.80
    Add-BooleanResult -Id "P1-RAG-QA-SET" -Provider $Provider -Pass $ragQaPass -PassScore 5 -FailScore 2 -DurationMs $ragQa.durationMs -Note "standard RAG QA cases=$($ragQa.caseCount) answerAccuracy=$($ragQa.answerAccuracy) citationPrecision=$($ragQa.citationPrecision) rerankCoverage=$($ragQa.rerankCoverage)" -Excerpt $ragQa

    $noRag = Send-Message -Token $aliceToken -SessionId $aliceNoRagSession -Content $keys.mentorKey -RagDisabled $true -Timeout 120
    $noRagContent = [string]$noRag.Body.assistantContent
    Add-BooleanResult -Id "P1-RAG-06" -Provider $Provider -Pass ($noRag.Status -eq 200 -and -not ($noRagContent -match "Zhang Mingyuan")) -PassScore 4 -FailScore 1 -DurationMs $noRag.DurationMs -Note "RAG disabled should not use private KB fact" -Excerpt $noRagContent -Error $noRag.Error

    $toolSession = New-Session -Token $aliceToken -Title "Eval Tools $providerRun" -RagDisabled $true -Provider $Provider -Model $config.Model -MaxSteps 4
    $tool = Send-Message -Token $aliceToken -SessionId $toolSession -Content "Search the web for common RAG evaluation metrics, then summarize 4 metrics. Use external search if available." -RagDisabled $true -Timeout 240
    $toolContent = [string]$tool.Body.assistantContent
    $toolPass = $tool.Status -eq 200 -and $toolContent.Length -gt 40 -and (Contains-Any -Text $toolContent -Needles @("faithfulness", "context", "recall", "precision", "answer"))
    Add-BooleanResult -Id "P1-TOOLS-01" -Provider $Provider -Pass $toolPass -PassScore 4 -FailScore 2 -DurationMs $tool.DurationMs -Note "web-search/tool loop smoke test" -Excerpt $toolContent -Error $tool.Error

    $toolFollow = Send-Message -Token $aliceToken -SessionId $toolSession -Content "Based on the previous answer, give one implementation risk in one sentence." -RagDisabled $true -Timeout 120
    $toolFollowContent = [string]$toolFollow.Body.assistantContent
    Add-BooleanResult -Id "P0-TOOLS-02" -Provider $Provider -Pass ($toolFollow.Status -eq 200 -and $toolFollowContent.Length -gt 10) -DurationMs $toolFollow.DurationMs -Note "follow-up after tool-call history should not 500" -Excerpt $toolFollowContent -Error $toolFollow.Error

    $planSession = New-Session -Token $aliceToken -Title "Eval Plan $providerRun" -RagDisabled $true -Provider $Provider -Model $config.Model -MaxSteps 4
    $plan = Invoke-JsonApi -Method "POST" -Path "/api/v1/agent/sessions/$planSession/plans" -Token $aliceToken -Body @{
        content = "Create a short executable checklist for learning RAG basics, common architectures, and evaluation methods."
        ragDisabled = $true
        autoExecute = $false
    } -Timeout 180
    $planText = To-BodyText $plan.Body
    $planStepCount = 0
    if ($plan.Body -and $plan.Body.steps) {
        $planStepCount = @($plan.Body.steps).Count
    }
    $planPass = $plan.Status -eq 201 -and $planStepCount -ge 1 -and $planText -match "RAG"
    Add-BooleanResult -Id "P0-PLAN-01" -Provider $Provider -Pass $planPass -PassScore 4 -FailScore 1 -DurationMs $plan.DurationMs -Note "plan creation should finish and produce steps=$planStepCount" -Excerpt $plan.Body -Error $plan.Error

    if ($RunPlanExecution -and $planPass) {
        $planId = [long]$plan.Body.id
        $async = Invoke-JsonApi -Method "POST" -Path "/api/v1/agent/plans/$planId/execute-async" -Token $aliceToken -Timeout 30
        $asyncFast = $async.Status -eq 200 -and $async.DurationMs -lt 2000 -and @("RUNNING", "COMPLETED") -contains [string]$async.Body.status
        $execute = Wait-PlanTerminal -Token $aliceToken -PlanId $planId -Timeout 300
        $events = Invoke-JsonApi -Method "GET" -Path "/api/v1/agent/plans/$planId/events" -Token $aliceToken -Timeout 60
        $planResponse = $execute.Response
        $executePass = $asyncFast -and -not $execute.TimedOut -and $planResponse.Status -eq 200 -and $events.Status -eq 200 -and (Test-PlanExecutionResult -Plan $planResponse.Body -Events @($events.Body))
        Add-BooleanResult -Id "P1-PLAN-02" -Provider $Provider -Pass $executePass -PassScore 4 -FailScore 0 -DurationMs ($async.DurationMs + $execute.DurationMs + $events.DurationMs) -Note "async plan execution terminal=$($planResponse.Body.status) asyncMs=$($async.DurationMs)" -Excerpt @{ async = $async.Body; plan = $planResponse.Body; events = $events.Body } -Error ($async.Error + $planResponse.Error + $events.Error)
    } elseif ($RunPlanExecution) {
        Add-Result -Id "P1-PLAN-02" -Provider $Provider -Status "SKIP" -Score 0 -DurationMs 0 -Note "plan execution skipped because plan creation failed"
    }

    $dashboard = Invoke-JsonApi -Method "GET" -Path "/api/v1/observability/dashboard?windowMinutes=1440" -Token $aliceToken -Timeout 30
    $dashboardPass = $dashboard.Status -eq 200 -and $dashboard.Body.planStatusCounts -and $dashboard.Body.alerts
    Add-BooleanResult -Id "P1-OBS-01" -Provider $Provider -Pass $dashboardPass -PassScore 4 -FailScore 1 -DurationMs $dashboard.DurationMs -Note "observability dashboard endpoint" -Excerpt $dashboard.Body -Error $dashboard.Error

    $alerts = Invoke-JsonApi -Method "GET" -Path "/api/v1/observability/alerts?windowMinutes=1440" -Token $aliceToken -Timeout 30
    $alertsPass = $alerts.Status -eq 200 -and @("OK", "WARN", "CRITICAL") -contains [string]$alerts.Body.status -and @($alerts.Body.alerts).Count -ge 4
    Add-BooleanResult -Id "P1-OBS-02" -Provider $Provider -Pass $alertsPass -PassScore 4 -FailScore 1 -DurationMs $alerts.DurationMs -Note "observability alert rules endpoint status=$($alerts.Body.status)" -Excerpt $alerts.Body -Error $alerts.Error
}

function Normalize-Providers {
    param([string[]]$InputProviders, [bool]$AddGlm)
    $values = New-Object System.Collections.Generic.List[string]
    foreach ($item in $InputProviders) {
        foreach ($piece in ([string]$item).Split(",")) {
            $normalized = $piece.Trim().ToLowerInvariant()
            if ($normalized -and -not $values.Contains($normalized)) {
                $values.Add($normalized) | Out-Null
            }
        }
    }
    if ($AddGlm -and -not $values.Contains("glm")) {
        $values.Add("glm") | Out-Null
    }
    if ($values.Count -eq 0) {
        $values.Add("deepseek") | Out-Null
    }
    return $values.ToArray()
}

Load-DotEnv
$script:ResolvedEvalInviteCode = $null
if ($EvalInviteCode) {
    $script:ResolvedEvalInviteCode = $EvalInviteCode.Trim()
} elseif ($env:EVAL_INVITE_CODE) {
    $script:ResolvedEvalInviteCode = $env:EVAL_INVITE_CODE.Trim()
} elseif ($env:INVITE_CODES) {
    $script:ResolvedEvalInviteCode = @($env:INVITE_CODES.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ })[0]
}
$runStarted = Get-Date
$runId = $runStarted.ToString("yyyyMMddHHmmss")
$providerList = Normalize-Providers -InputProviders $Providers -AddGlm ([bool]$IncludeGlm)
$script:Results = [System.Collections.Generic.List[object]]::new()
$script:RagQaMetrics = [System.Collections.Generic.List[object]]::new()

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$fixtureRoot = Join-Path $OutputDir "fixtures-$runId"
New-Item -ItemType Directory -Force -Path $fixtureRoot | Out-Null

Write-Host "Yanban local eval runId=$runId baseUrl=$BaseUrl providers=$($providerList -join ',') fixtureMode=$FixtureMode runPlanExecution=$RunPlanExecution"

$health = Invoke-JsonApi -Method "GET" -Path "/actuator/health" -Timeout 10
Add-BooleanResult -Id "ENV-HEALTH-01" -Provider "global" -Pass ($health.Status -eq 200 -and $health.Raw -match "UP") -DurationMs $health.DurationMs -Note "actuator health" -Excerpt $health.Raw -Error $health.Error

$unauth = Invoke-JsonApi -Method "GET" -Path "/api/v1/agent/sessions" -Timeout 10
Add-BooleanResult -Id "P0-AUTH-01" -Provider "global" -Pass ($unauth.Status -eq 401) -DurationMs $unauth.DurationMs -Note "unauthorized request should be rejected" -Excerpt $unauth.Raw -Error $unauth.Error

foreach ($provider in $providerList) {
    try {
        Run-ProviderEval -Provider $provider -RunId $runId -FixtureMode $FixtureMode -FixtureRoot $fixtureRoot
    } catch {
        Add-Result -Id "PROVIDER-RUN" -Provider $provider -Status "FAIL" -Score 0 -DurationMs 0 -Note "provider eval crashed" -Error $_.Exception.Message -Excerpt $_.ScriptStackTrace
    }
}

$runEnded = Get-Date
$passCount = @($Results | Where-Object { $_.status -eq "PASS" }).Count
$failCount = @($Results | Where-Object { $_.status -eq "FAIL" }).Count
$skipCount = @($Results | Where-Object { $_.status -eq "SKIP" }).Count
$slowRequests = @($Results |
    Sort-Object -Property durationMs -Descending |
    Select-Object -First 10 id, provider, status, durationMs, note)
$providerStats = @($Results |
    Group-Object -Property provider |
    ForEach-Object {
        $items = @($_.Group)
        $providerFailCount = @($items | Where-Object { $_.status -eq "FAIL" }).Count
        [pscustomobject]@{
            provider = $_.Name
            total = $items.Count
            passCount = @($items | Where-Object { $_.status -eq "PASS" }).Count
            failCount = $providerFailCount
            skipCount = @($items | Where-Object { $_.status -eq "SKIP" }).Count
            errorRate = if ($items.Count -eq 0) { 0 } else { [Math]::Round($providerFailCount / $items.Count, 4) }
        }
    })
$summary = [pscustomobject]@{
    runId = $runId
    startedAt = $runStarted.ToString("o")
    endedAt = $runEnded.ToString("o")
    durationSec = [int]($runEnded - $runStarted).TotalSeconds
    baseUrl = $BaseUrl
    providers = $providerList
    fixtureMode = $FixtureMode
    runPlanExecution = [bool]$RunPlanExecution
    passCount = $passCount
    failCount = $failCount
    skipCount = $skipCount
    total = $Results.Count
    slowRequests = $slowRequests
    providerStats = $providerStats
    ragQaMetrics = $RagQaMetrics
    results = $Results
}

$providerSlug = ($providerList -join "-")
$jsonPath = Join-Path $OutputDir "run-$runId-$providerSlug.json"
$mdPath = Join-Path $OutputDir "run-$runId-$providerSlug.md"
$summary | ConvertTo-Json -Depth 40 | Set-Content -Path $jsonPath -Encoding UTF8

$mdLines = [System.Collections.Generic.List[string]]::new()
$mdLines.Add("# Local Eval Run $runId") | Out-Null
$mdLines.Add("") | Out-Null
$mdLines.Add("- Started: $($summary.startedAt)") | Out-Null
$mdLines.Add("- Duration: $($summary.durationSec)s") | Out-Null
$mdLines.Add("- Providers: $($providerList -join ', ')") | Out-Null
$mdLines.Add("- Fixture mode: $FixtureMode") | Out-Null
$mdLines.Add("- Plan execution: $([bool]$RunPlanExecution)") | Out-Null
$mdLines.Add("- Result: $passCount pass / $failCount fail / $skipCount skip / $($Results.Count) total") | Out-Null
$mdLines.Add("") | Out-Null
$mdLines.Add("## Provider Stats") | Out-Null
$mdLines.Add("") | Out-Null
$mdLines.Add("| Provider | Total | Pass | Fail | Skip | Error Rate |") | Out-Null
$mdLines.Add("|---|---:|---:|---:|---:|---:|") | Out-Null
foreach ($stat in $providerStats) {
    $mdLines.Add("| $($stat.provider) | $($stat.total) | $($stat.passCount) | $($stat.failCount) | $($stat.skipCount) | $($stat.errorRate) |") | Out-Null
}
$mdLines.Add("") | Out-Null
$mdLines.Add("## Slow Requests Top 10") | Out-Null
$mdLines.Add("") | Out-Null
$mdLines.Add("| ID | Provider | Status | Duration ms | Note |") | Out-Null
$mdLines.Add("|---|---|---:|---:|---|") | Out-Null
foreach ($slow in $slowRequests) {
    $note = ([string]$slow.note) -replace '\|', '/'
    $mdLines.Add("| $($slow.id) | $($slow.provider) | $($slow.status) | $($slow.durationMs) | $note |") | Out-Null
}
$mdLines.Add("") | Out-Null
$mdLines.Add("## RAG QA Metrics") | Out-Null
$mdLines.Add("") | Out-Null
$mdLines.Add("| Provider | Cases | Answer Accuracy | Citation Precision | Rerank Coverage | Duration ms |") | Out-Null
$mdLines.Add("|---|---:|---:|---:|---:|---:|") | Out-Null
foreach ($metric in $RagQaMetrics) {
    $mdLines.Add("| $($metric.provider) | $($metric.caseCount) | $($metric.answerAccuracy) | $($metric.citationPrecision) | $($metric.rerankCoverage) | $($metric.durationMs) |") | Out-Null
}
$mdLines.Add("") | Out-Null
$mdLines.Add("## Results") | Out-Null
$mdLines.Add("") | Out-Null
$mdLines.Add("| ID | Provider | Status | Score | Duration ms | Note | Error |") | Out-Null
$mdLines.Add("|---|---|---:|---:|---:|---|---|") | Out-Null
foreach ($result in $Results) {
    $note = ([string]$result.note) -replace '\|', '/'
    $errorText = ([string]$result.error) -replace '\|', '/'
    $mdLines.Add("| $($result.id) | $($result.provider) | $($result.status) | $($result.score) | $($result.durationMs) | $note | $(Short-Text $errorText 120) |") | Out-Null
}
$mdLines.Add("") | Out-Null
$mdLines.Add("## Excerpts") | Out-Null
foreach ($result in $Results) {
    $mdLines.Add("") | Out-Null
    $mdLines.Add("### $($result.id) [$($result.provider)]") | Out-Null
    $mdLines.Add("") | Out-Null
    $mdLines.Add('```text') | Out-Null
    $mdLines.Add([string]$result.excerpt) | Out-Null
    $mdLines.Add('```') | Out-Null
}
$mdLines | Set-Content -Path $mdPath -Encoding UTF8

Write-Host ""
Write-Host "RESULT pass=$passCount fail=$failCount skip=$skipCount total=$($Results.Count) durationSec=$($summary.durationSec)"
Write-Host "JSON $jsonPath"
Write-Host "MD   $mdPath"

if ($failCount -gt 0) {
    exit 1
}
