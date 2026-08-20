param(
    [string]$ElasticsearchEndpoint = "http://localhost:9200",
    [ValidateSet(50, 100, 300)]
    [int]$MaxQueries = 300,
    [switch]$IncludeModelRerank,
    [switch]$TuneRrf,
    [switch]$EvaluateRerankIntents,
    [switch]$EvaluateQueryRewrite,
    [switch]$EvaluateOptimizedPipeline,
    [switch]$EvaluateFrozenFinal,
    [switch]$EvaluateRerankWindows,
    [switch]$EvaluateChunking
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..")
$previousLocation = Get-Location

try {
    Invoke-RestMethod -Uri $ElasticsearchEndpoint -TimeoutSec 5 | Out-Null
    Set-Location $repoRoot

    $datasetRoot = Join-Path $repoRoot "yanban-knowledge\target\rag-eval\datasets"
    $datasetZip = Join-Path $datasetRoot "scifact.zip"
    $datasetPath = Join-Path $datasetRoot "scifact"
    New-Item -ItemType Directory -Force -Path $datasetRoot | Out-Null
    if (-not (Test-Path $datasetZip)) {
        Invoke-WebRequest `
            -Uri "https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets/scifact.zip" `
            -OutFile $datasetZip
    }
    $actualMd5 = (Get-FileHash -LiteralPath $datasetZip -Algorithm MD5).Hash.ToLowerInvariant()
    if ($actualMd5 -ne "5f7d1de60b170fc8027bb7898e2efca1") {
        throw "SciFact archive checksum mismatch"
    }
    if (-not (Test-Path (Join-Path $datasetPath "corpus.jsonl"))) {
        Expand-Archive -LiteralPath $datasetZip -DestinationPath $datasetRoot -Force
    }

    if (-not $env:DASHSCOPE_API_KEY) {
        $envFile = Join-Path $repoRoot ".env"
        if (Test-Path $envFile) {
            $keyLine = Get-Content $envFile | Where-Object { $_ -match '^DASHSCOPE_API_KEY=' } | Select-Object -First 1
            if ($keyLine) {
                $env:DASHSCOPE_API_KEY = ($keyLine -split '=', 2)[1].Trim()
            }
        }
    }
    if (-not $env:DASHSCOPE_API_KEY) {
        throw "DASHSCOPE_API_KEY is required for the SciFact KNN baseline"
    }
    if (($EvaluateQueryRewrite -or $EvaluateOptimizedPipeline) -and -not $env:DEEPSEEK_API_KEY) {
        $envFile = Join-Path $repoRoot ".env"
        if (Test-Path $envFile) {
            $keyLine = Get-Content $envFile | Where-Object { $_ -match '^DEEPSEEK_API_KEY=' } | Select-Object -First 1
            if ($keyLine) {
                $env:DEEPSEEK_API_KEY = ($keyLine -split '=', 2)[1].Trim()
            }
        }
    }
    if (($EvaluateQueryRewrite -or $EvaluateOptimizedPipeline) -and -not $env:DEEPSEEK_API_KEY) {
        throw "DEEPSEEK_API_KEY is required for query rewrite evaluation"
    }

    & mvn -pl yanban-knowledge -am `
        "-Dtest=SciFactRetrievalBaselineE2eTest" `
        "-Dyanban.scifact-eval=true" `
        "-Dyanban.scifact-model-rerank=$($IncludeModelRerank.IsPresent.ToString().ToLowerInvariant())" `
        "-Dyanban.scifact-rrf-tuning=$($TuneRrf.IsPresent.ToString().ToLowerInvariant())" `
        "-Dyanban.scifact-rerank-intent-eval=$($EvaluateRerankIntents.IsPresent.ToString().ToLowerInvariant())" `
        "-Dyanban.scifact-query-rewrite-eval=$($EvaluateQueryRewrite.IsPresent.ToString().ToLowerInvariant())" `
        "-Dyanban.scifact-optimized-pipeline-eval=$($EvaluateOptimizedPipeline.IsPresent.ToString().ToLowerInvariant())" `
        "-Dyanban.scifact-frozen-final-eval=$($EvaluateFrozenFinal.IsPresent.ToString().ToLowerInvariant())" `
        "-Dyanban.scifact-rerank-window-eval=$($EvaluateRerankWindows.IsPresent.ToString().ToLowerInvariant())" `
        "-Dyanban.scifact-chunking-eval=$($EvaluateChunking.IsPresent.ToString().ToLowerInvariant())" `
        "-Dyanban.scifact-max-queries=$MaxQueries" `
        "-Dyanban.real-es-endpoint=$ElasticsearchEndpoint" `
        "-Dsurefire.failIfNoSpecifiedTests=false" test
    if ($LASTEXITCODE -ne 0) {
        throw "SciFact retrieval baseline failed with exit code $LASTEXITCODE"
    }

    Write-Host "SciFact retrieval baseline completed."
    if ($TuneRrf) {
        Write-Host "RRF tuning: yanban-knowledge\target\rag-eval\scifact\scifact-rrf-tuning-600.md"
        Write-Host "RRF validation: yanban-knowledge\target\rag-eval\scifact\scifact-rrf-validation-209.md"
        Write-Host "RRF selection: yanban-knowledge\target\rag-eval\scifact\scifact-rrf-selection.json"
        return
    }
    if ($EvaluateRerankIntents) {
        Write-Host "Rerank intents: yanban-knowledge\target\rag-eval\scifact\scifact-rerank-intents-50.md"
        Write-Host "Rerank intent usage: yanban-knowledge\target\rag-eval\scifact\scifact-rerank-intents-50-usage.json"
        return
    }
    if ($EvaluateQueryRewrite) {
        Write-Host "Query rewrite: yanban-knowledge\target\rag-eval\scifact\scifact-query-rewrite-50.md"
        Write-Host "Query rewrite usage: yanban-knowledge\target\rag-eval\scifact\scifact-query-rewrite-50-usage.json"
        return
    }
    if ($EvaluateOptimizedPipeline) {
        Write-Host "Optimized pipeline: yanban-knowledge\target\rag-eval\scifact\scifact-optimized-pipeline-50.md"
        Write-Host "Optimized pipeline usage: yanban-knowledge\target\rag-eval\scifact\scifact-optimized-pipeline-50-usage.json"
        return
    }
    if ($EvaluateFrozenFinal) {
        Write-Host "Frozen final: yanban-knowledge\target\rag-eval\scifact\scifact-frozen-final-300.md"
        Write-Host "Frozen final usage: yanban-knowledge\target\rag-eval\scifact\scifact-frozen-final-300-usage.json"
        return
    }
    if ($EvaluateRerankWindows) {
        Write-Host "Rerank windows: yanban-knowledge\target\rag-eval\scifact\scifact-rerank-window-109.md"
        Write-Host "Rerank window usage: yanban-knowledge\target\rag-eval\scifact\scifact-rerank-window-109-usage.json"
        return
    }
    if ($EvaluateChunking) {
        Write-Host "Chunking comparison: yanban-knowledge\target\rag-eval\scifact\scifact-chunking-$MaxQueries.md"
        Write-Host "Chunking usage: yanban-knowledge\target\rag-eval\scifact\scifact-chunking-$MaxQueries-usage.json"
        return
    }
    foreach ($tier in @(50, 100, 300) | Where-Object { $_ -le $MaxQueries }) {
        Write-Host "Markdown: yanban-knowledge\target\rag-eval\scifact\scifact-$tier.md"
        Write-Host "JSON: yanban-knowledge\target\rag-eval\scifact\scifact-$tier.json"
        if ($IncludeModelRerank) {
            Write-Host "Rerank usage: yanban-knowledge\target\rag-eval\scifact\scifact-$tier-model-rerank-usage.json"
        }
    }
} finally {
    Set-Location $previousLocation
}
