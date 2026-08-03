param(
    [string]$OutputDir = "yanban-paper/target/literature-recommendation-eval"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [System.Text.UTF8Encoding]::new()

Write-Host "Running literature recommendation evaluation..."
mvn --% -pl yanban-paper -am -Dtest=LiteratureRecommendationEvaluationTest -Dsurefire.failIfNoSpecifiedTests=false test

$jsonPath = Join-Path $OutputDir "report.json"
$mdPath = Join-Path $OutputDir "report.md"

if (-not (Test-Path $jsonPath) -or -not (Test-Path $mdPath)) {
    throw "Evaluation finished but report files were not found under $OutputDir"
}

Write-Host ""
Write-Host "Literature recommendation evaluation report:"
Write-Host "JSON $jsonPath"
Write-Host "MD   $mdPath"
