[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$startedAt = Get-Date

# This list is intentionally narrow and entirely deterministic: each test uses a
# fake model/provider or local temporary files.  Do not replace it with `mvn test`;
# the full reactor remains a separate compatibility regression command.
$tests = @(
    'ToolRegistryPolicySafetyRegressionTest',
    'AgentTaskStateSafetyRegressionTest',
    'AgentContextBuilderTest',
    'AgentSessionServiceTest',
    'AgentControllerV2AvailabilityTest',
    'AgentControllerV2NaturalLanguageEndpointTest',
    'AgentControllerV2LiteratureEndpointTest',
    'V2TurnHistoryQueryServiceTest',
    'V2AdaptiveExecutionServiceTest',
    'V2AdaptiveFinalSynthesisServiceTest',
    'V2ProductToolCatalogTest',
    'V2SandboxEffectExecutionComposerTest',
    'ProjectServiceTest',
    'ProjectControllerIntegrationTest',
    'ProjectControllerV2AvailabilityTest',
    'CandidateChangeArtifactServiceTest',
    'PaperControllerIntegrationTest',
    'PaperTaskToolExecutorTest',
    'LiteratureSearchTaskToolExecutorTest',
    'LiteratureSearchTaskScannerTest'
)

Push-Location $repoRoot
try {
    # Offline mode is part of the gate contract: Maven must not download or call
    # an external service.  Missing cached dependencies are a deterministic gate
    # setup failure, not permission to fall back to the network.
    & mvn -o -pl yanban-api -am ("-Dtest=" + ($tests -join ',')) `
        '-Dsurefire.failIfNoSpecifiedTests=false' test
    $mavenExitCode = $LASTEXITCODE

    # This is intentionally a direct local Node invocation: it runs the
    # checked-in Markdown regression without invoking npm or the network.
    & node --test frontend/tests/markdownNormalization.test.mjs
    $frontendExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

$reportFiles = Get-ChildItem -Path $repoRoot -Recurse -Filter 'TEST-*.xml' |
    Where-Object { $_.FullName -match '[\\/]surefire-reports[\\/]' -and $_.LastWriteTime -ge $startedAt }

if ($reportFiles.Count -eq 0) {
    Write-Error 'MVP_RELEASE_GATE_RESULT tests=0 failures=0 errors=0 skipped=0 reason=no fresh Surefire XML reports'
    exit 2
}

$testsRun = 0
$failures = 0
$errors = 0
$skipped = @()
foreach ($reportFile in $reportFiles) {
    [xml]$report = Get-Content -Raw $reportFile.FullName
    foreach ($suite in $report.SelectNodes('//testsuite')) {
        $testsRun += [int]$suite.tests
        $failures += [int]$suite.failures
        $errors += [int]$suite.errors
        foreach ($case in $suite.SelectNodes('./testcase[skipped]')) {
            $reason = $case.skipped.message
            if (-not $reason -and $case.skipped.InnerText) { $reason = $case.skipped.InnerText.Trim() }
            if (-not $reason) { $reason = 'Surefire reported skipped without a message' }
            $skipped += [pscustomobject]@{ Class = $case.classname; Name = $case.name; Reason = $reason }
        }
    }
}

Write-Host ("MVP_RELEASE_GATE_RESULT tests={0} failures={1} errors={2} skipped={3}" -f $testsRun, $failures, $errors, $skipped.Count)
Write-Host ("MVP_RELEASE_GATE_FRONTEND_RESULT command=node --test frontend/tests/markdownNormalization.test.mjs exit={0}" -f $frontendExitCode)
foreach ($skip in $skipped) {
    Write-Host ("MVP_RELEASE_GATE_SKIP class={0} test={1} reason={2}" -f $skip.Class, $skip.Name, $skip.Reason)
}

if ($mavenExitCode -ne 0 -or $frontendExitCode -ne 0 -or $failures -ne 0 -or $errors -ne 0) {
    exit 1
}

# No release-gate safety test may be silently skipped.  In particular, Windows
# environments without SeCreateSymbolicLinkPrivilege are BLOCKED, not PASS, until
# this same command runs in privileged Windows CI or Linux CI.
if ($skipped.Count -ne 0) {
    exit 3
}
