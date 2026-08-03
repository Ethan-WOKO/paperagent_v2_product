param(
    [string]$LiteratureOutputDir = "yanban-paper/target/literature-recommendation-eval",
    [string]$PolishOutputDir = "yanban-paper/target/paper-polish-baseline-eval"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [System.Text.UTF8Encoding]::new()

Write-Host "Running literature recommendation and paper polish baseline evaluations..."
mvn --% -pl yanban-paper -am -Dtest=LiteratureRecommendationEvaluationTest,PaperPolishBaselineEvaluationTest -Dsurefire.failIfNoSpecifiedTests=false test

$expectedFiles = @(
    (Join-Path $LiteratureOutputDir "report.json"),
    (Join-Path $LiteratureOutputDir "report.md"),
    (Join-Path $PolishOutputDir "report.json"),
    (Join-Path $PolishOutputDir "report.md")
)

foreach ($path in $expectedFiles) {
    if (-not (Test-Path $path)) {
        throw "Evaluation finished but report file was not found: $path"
    }
}

Write-Host ""
Write-Host "Baseline evaluation reports:"
Write-Host "Literature JSON $LiteratureOutputDir/report.json"
Write-Host "Literature MD   $LiteratureOutputDir/report.md"
Write-Host "Polish JSON     $PolishOutputDir/report.json"
Write-Host "Polish MD       $PolishOutputDir/report.md"
