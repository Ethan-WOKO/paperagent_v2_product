param(
    [string]$ElasticsearchEndpoint = "http://localhost:9200"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..")
$previousLocation = Get-Location

try {
    Invoke-RestMethod -Uri $ElasticsearchEndpoint -TimeoutSec 5 | Out-Null
    Set-Location $repoRoot

    & mvn -pl yanban-knowledge -am `
        "-Dtest=RealElasticsearchHybridRagE2eTest" `
        "-Dyanban.real-es-e2e=true" `
        "-Dyanban.real-es-endpoint=$ElasticsearchEndpoint" `
        "-Dsurefire.failIfNoSpecifiedTests=false" test
    if ($LASTEXITCODE -ne 0) {
        throw "RAG retrieval baseline failed with exit code $LASTEXITCODE"
    }

    Write-Host "RAG retrieval baseline completed."
    Write-Host "Markdown: yanban-knowledge\target\rag-eval\real-es-retrieval-baseline.md"
    Write-Host "JSON: yanban-knowledge\target\rag-eval\real-es-retrieval-baseline.json"
} finally {
    Set-Location $previousLocation
}
