[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Fail([string]$Message) {
    throw "DELETE-01 failed: $Message"
}

function FullPath([string]$RelativePath) {
    return Join-Path $repo ($RelativePath -replace '/', [IO.Path]::DirectorySeparatorChar)
}

function Assert-Exists([string]$RelativePath) {
    if (-not (Test-Path -LiteralPath (FullPath $RelativePath))) {
        Fail "required retained path is missing: $RelativePath"
    }
}

function Assert-Absent([string]$RelativePath) {
    if (Test-Path -LiteralPath (FullPath $RelativePath)) {
        Fail "frozen delete path still exists: $RelativePath"
    }
}

function Assert-Contains([string]$RelativePath, [string]$Text) {
    Assert-Exists $RelativePath
    $content = Get-Content -LiteralPath (FullPath $RelativePath) -Raw
    if (-not $content.Contains($Text)) {
        Fail "retained marker '$Text' is missing from $RelativePath"
    }
}

function Assert-NotContains([string]$RelativePath, [string]$Text) {
    Assert-Exists $RelativePath
    $content = Get-Content -LiteralPath (FullPath $RelativePath) -Raw
    if ($content.Contains($Text)) {
        Fail "retired marker '$Text' remains in $RelativePath"
    }
}

function Assert-NotMatches([string[]]$RelativePaths, [string]$Pattern, [string]$Label) {
    foreach ($relativePath in $RelativePaths) {
        Assert-Exists $relativePath
        $content = Get-Content -LiteralPath (FullPath $relativePath) -Raw
        if ($content -cmatch $Pattern) {
            Fail "$Label remains in $relativePath"
        }
    }
}

function Assert-NoGitDiff([string[]]$RelativePaths, [string]$Label) {
    $arguments = @('-C', $repo, 'diff', '--quiet', 'HEAD', '--') + $RelativePaths
    & git @arguments
    if ($LASTEXITCODE -ne 0) {
        Fail "$Label has an out-of-scope diff"
    }
}

function Get-HeadText([string]$RelativePath) {
    $value = & git -C $repo show "HEAD:$RelativePath" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Fail "cannot read HEAD copy of $RelativePath"
    }
    return ($value -join "`n")
}

function Get-JavaMethodBlock([string]$Content, [string]$MethodName) {
    $lines = $Content -split "`r?`n"
    $signature = -1
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match "\b$([regex]::Escape($MethodName))\s*\(") {
            $signature = $index
            break
        }
    }
    if ($signature -lt 0) {
        Fail "Java method '$MethodName' is missing"
    }

    $start = $signature
    while ($start -gt 0 -and $lines[$start - 1].TrimStart().StartsWith('@')) {
        $start--
    }

    $depth = 0
    $opened = $false
    for ($index = $signature; $index -lt $lines.Count; $index++) {
        $opens = ([regex]::Matches($lines[$index], '\{')).Count
        $closes = ([regex]::Matches($lines[$index], '\}')).Count
        if ($opens -gt 0) {
            $opened = $true
        }
        if ($opened) {
            $depth += $opens - $closes
            if ($depth -eq 0) {
                return ($lines[$start..$index] -join "`n").Trim()
            }
        }
    }
    Fail "Java method '$MethodName' has no complete body"
}

function Assert-RetainedControllerMethods(
        [string]$RelativePath,
        [string[]]$DeletedMethods) {
    $head = Get-HeadText $RelativePath
    $current = Get-Content -LiteralPath (FullPath $RelativePath) -Raw
    $matches = [regex]::Matches(
        $head,
        '(?m)^\s*public\s+[\w<>,.?\[\] ]+\s+(\w+)\s*\(')
    $methodNames = $matches | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
    foreach ($methodName in $methodNames) {
        if ($DeletedMethods -contains $methodName) {
            continue
        }
        $before = Get-JavaMethodBlock $head $methodName
        $after = Get-JavaMethodBlock $current $methodName
        if ($before -cne $after) {
            Fail "retained controller method changed: $RelativePath::$methodName"
        }
    }
}

$productionDeletes = @(
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2NaturalLanguageTurnService.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2TurnPlanner.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2TurnPlanningException.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2PlannerCapabilityCatalog.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2IntakePlanningProviderAdapter.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2TurnIntakeTransactions.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2TurnHistoryQueryService.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2TurnHistoryResponse.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveCyclePort.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveExecutionCoordinator.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveExecutionResult.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveExecutionService.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveExecutionStore.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveFinalSynthesisService.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveRuntimeCycleFactory.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveTurnQueryService.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveTurnResponse.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveTurnSnapshot.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2ModelReflectionProvider.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2ReplanRequestMaterializer.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/reflection/ReflectionAction.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/reflection/ReflectionContext.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/reflection/ReflectionOutcome.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/reflection/ReflectionParseException.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/reflection/ReflectionProvider.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/reflection/ReflectionReplacementStep.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/reflection/ReflectionStepResult.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/reflection/StrictReflectionDecisionParser.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/loop/AutonomousNaturalLanguageStepTurnAdapter.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/loop/NaturalLanguageStepKernelFactory.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/context/V2ExecutionContextSource.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/compatibility/project/V2ProjectAnalysisService.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/compatibility/project/V2ProjectAnalysisRequest.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/compatibility/project/V2ProjectAnalysisResponse.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/compatibility/project/V2ProjectCandidateService.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/compatibility/project/V2ProjectCandidateRequest.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/compatibility/project/V2ProjectCandidateResponse.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/compatibility/project/V2ProjectCandidateRepairRequest.java',
    'yanban-api/src/main/java/com/yanban/api/project/CandidateValidationRepairService.java',
    'yanban-api/src/main/java/com/yanban/api/project/CandidateValidationRepairRepository.java',
    'yanban-api/src/main/java/com/yanban/api/project/CandidateValidationRepair.java'
)

$testDeletes = @(
    'yanban-api/src/test/java/com/yanban/api/agent/v2/intake/AgentControllerV2NaturalLanguageEndpointTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/intake/V2NaturalLanguageTurnServiceTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/intake/V2TurnHistoryQueryServiceTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/intake/V2TurnIntakeTransactionsH2Test.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/intake/V2TurnIntakeTransactionsTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/intake/V2TurnPlannerTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/adaptive/reflection/StrictReflectionDecisionParserTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveExecutionCoordinatorTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveExecutionServiceTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveFinalSynthesisServiceTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveTurnQueryServiceTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/adaptive/V2ModelReflectionProviderTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/adaptive/V2ReplanRequestMaterializerTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/loop/AutonomousNaturalLanguageStepTurnAdapterTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/loop/NaturalLanguageStepKernelFactoryTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/context/V2ExecutionContextSourceTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/compatibility/project/V2ProjectAnalysisServiceH2VerticalTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/compatibility/project/V2ProjectAnalysisServiceTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/compatibility/project/V2ProjectCandidateServiceTest.java',
    'yanban-api/src/test/java/com/yanban/api/project/ProjectControllerV2AnalysisTest.java',
    'yanban-api/src/test/java/com/yanban/api/project/ProjectControllerV2CandidateTest.java'
)

if ($productionDeletes.Count -ne 41 -or $testDeletes.Count -ne 21) {
    Fail 'the frozen delete arrays are not exactly 41 production + 21 test files'
}
foreach ($path in $productionDeletes + $testDeletes) {
    Assert-Absent $path
}

$oldSymbols = @(
    'V2NaturalLanguageTurnService', 'V2TurnPlanner',
    'V2TurnPlanningException', 'V2PlannerCapabilityCatalog',
    'V2IntakePlanningProviderAdapter', 'V2TurnIntakeTransactions',
    'V2TurnHistoryQueryService', 'V2TurnHistoryResponse',
    'V2AdaptiveCyclePort', 'V2AdaptiveExecutionCoordinator',
    'V2AdaptiveExecutionResult', 'V2AdaptiveExecutionService',
    'V2AdaptiveExecutionStore', 'V2AdaptiveFinalSynthesisService',
    'V2AdaptiveRuntimeCycleFactory', 'V2AdaptiveTurnQueryService',
    'V2AdaptiveTurnResponse', 'V2AdaptiveTurnSnapshot',
    'V2ModelReflectionProvider', 'V2ReplanRequestMaterializer',
    'ReflectionAction', 'ReflectionContext', 'ReflectionOutcome',
    'ReflectionParseException', 'ReflectionProvider',
    'ReflectionReplacementStep', 'ReflectionStepResult',
    'StrictReflectionDecisionParser',
    'AutonomousNaturalLanguageStepTurnAdapter',
    'NaturalLanguageStepKernelFactory', 'V2ExecutionContextSource',
    'V2ProjectAnalysisService', 'V2ProjectAnalysisRequest',
    'V2ProjectAnalysisResponse', 'V2ProjectCandidateService',
    'V2ProjectCandidateRequest', 'V2ProjectCandidateResponse',
    'V2ProjectCandidateRepairRequest', 'CandidateValidationRepairService',
    'CandidateValidationRepairRepository', 'CandidateValidationRepair',
    'repairAfterFailure', 'bindRepair'
)
$javaFiles = @(
    Get-ChildItem -LiteralPath (FullPath 'yanban-api/src/main/java') -Recurse -File -Filter '*.java'
    Get-ChildItem -LiteralPath (FullPath 'yanban-api/src/test/java') -Recurse -File -Filter '*.java'
)
foreach ($symbol in $oldSymbols) {
    $match = Select-String -LiteralPath $javaFiles.FullName -SimpleMatch $symbol | Select-Object -First 1
    if ($null -ne $match) {
        Fail "old production symbol '$symbol' remains at $($match.Path):$($match.LineNumber)"
    }
}

$agentController = 'yanban-api/src/main/java/com/yanban/api/agent/AgentController.java'
$projectController = 'yanban-api/src/main/java/com/yanban/api/project/ProjectController.java'
foreach ($oldEndpoint in @('/v2/turns', 'read-analysis-turns', 'candidate-turns')) {
    Assert-NotContains $agentController $oldEndpoint
    Assert-NotContains $projectController $oldEndpoint
}
Assert-RetainedControllerMethods $agentController @(
    'listV2NaturalLanguageTurns',
    'getV2NaturalLanguageTurn',
    'sendV2NaturalLanguageTurn'
)
Assert-RetainedControllerMethods $projectController @(
    'startV2ProjectAnalysis',
    'readV2ProjectAnalysis',
    'startV2ProjectCandidate',
    'readV2ProjectCandidate'
)

foreach ($path in @(
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2NaturalLanguageTurnRequest.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2NaturalLanguageTurnResponse.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2SessionDeletionService.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2TurnIntakeEntity.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2TurnIntakeJpaRepository.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveTurnDeletionService.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveTurnEntity.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveTurnRepository.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/intake/V2SessionDeletionServiceTest.java'
)) {
    Assert-Exists $path
}

Assert-Contains $agentController '@GetMapping("/{sessionId}/messages")'
Assert-Contains $agentController '@PostMapping("/{sessionId}/messages")'
Assert-Contains $agentController '@GetMapping("/v2/capabilities")'
Assert-Contains $agentController '@PostMapping("/{sessionId}/v2/literature-turns")'
Assert-Contains $agentController '@GetMapping("/{sessionId}/v2/literature-turns/{clientRequestId}")'
Assert-Contains $agentController '@PostMapping("/{sessionId}/v2/literature-turns/{clientRequestId}/cancel")'

$frontendApi = 'frontend/src/api/agent.ts'
$frontendPage = 'frontend/src/views/ProjectPreviewPage.vue'
$frontendUtility = 'frontend/src/utils/v2NaturalLanguageTurn.ts'
$frontendMocks = @(
    'frontend/src/mocks/fixtures.ts',
    'frontend/src/mocks/httpAdapter.ts'
)
$frontendOwnedPaths = @(
    $frontendApi,
    $frontendUtility,
    'frontend/src/utils/__tests__/v2NaturalLanguageTurn.test.ts',
    $frontendPage,
    'frontend/src/views/__tests__/ProjectPreviewPageV2Conversation.test.ts',
    $frontendMocks[0],
    $frontendMocks[1],
    'frontend/tests/v2ProjectModeSwitch.test.ts',
    'frontend/tests/uiMock.test.ts'
)
foreach ($marker in @(
    'startV2NaturalLanguageTurn',
    'getV2NaturalLanguageTurn',
    'listV2NaturalLanguageTurns',
    'clientRequestId'
)) {
    Assert-Contains $frontendApi $marker
}
foreach ($marker in @(
    'newV2NaturalLanguageClientRequestId',
    'normalizeV2NaturalLanguageRequest',
    'V2NaturalLanguageRequestIdentity',
    'isCurrentV2NaturalLanguageRequest'
)) {
    Assert-Contains $frontendUtility $marker
}
foreach ($marker in @(
    'V2_NATURAL_LANGUAGE_STORAGE_KEY',
    'naturalLanguageStorageKey(projectId: number, sessionId: number)',
    'isCurrentV2NaturalLanguageResponse',
    'Project Agent'
)) {
    Assert-Contains $frontendPage $marker
}

Assert-NotMatches $frontendOwnedPaths `
    '(WAITING_CONFIRMATION|agentAutomaticValidation|confirmationValidation)' `
    'old adaptive status/validation projection'
Assert-NotMatches @($frontendPage, $frontendUtility) `
    '(?m)\bresume\s*:|startThenPollV2NaturalLanguageTurn|pollV2NaturalLanguageTurn' `
    'old poll/resume POST flow'
Assert-NotMatches @($frontendPage, $frontendUtility) `
    'v2-direct-answer-required' `
    'browser DIRECT synthesis'
Assert-NotMatches (@($frontendPage) + $frontendMocks) `
    '(?m)\broute\s*:\s*[''"]PERSISTENT_PLAN_EXECUTE[''"]' `
    'hard-coded PERSISTENT projection'
Assert-NotMatches $frontendMocks `
    '/agent/sessions/6401/v2/turns' `
    'old V2 turn mock endpoint'

$sharedCuts = @(
    'yanban-api/src/main/java/com/yanban/api/agent/AgentController.java',
    'yanban-api/src/main/java/com/yanban/api/project/ProjectController.java',
    'yanban-api/src/main/java/com/yanban/api/project/CandidateSandboxValidationDispatcher.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/compatibility/project/ProjectCandidateDeliveryTransactions.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/compatibility/project/ProjectCandidateDeliveryEntity.java',
    'yanban-api/src/test/java/com/yanban/api/agent/AgentControllerV2AvailabilityTest.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/compatibility/literature/AgentControllerV2LiteratureEndpointTest.java',
    'yanban-api/src/test/java/com/yanban/api/project/ProjectControllerV2AvailabilityTest.java'
)
$expectedTrackedDiff = @(
    $productionDeletes
    $testDeletes
    $sharedCuts
    $frontendOwnedPaths
) | Sort-Object -Unique
$actualTrackedDiff = @(& git -C $repo -c core.quotepath=false diff --name-only HEAD --)
if ($LASTEXITCODE -ne 0) {
    Fail 'cannot enumerate the tracked worktree diff'
}
$actualTrackedDiff = $actualTrackedDiff | Sort-Object -Unique
$missingTrackedDiff = @($expectedTrackedDiff | Where-Object { $actualTrackedDiff -cnotcontains $_ })
$unexpectedTrackedDiff = @($actualTrackedDiff | Where-Object { $expectedTrackedDiff -cnotcontains $_ })
if ($missingTrackedDiff.Count -gt 0 -or $unexpectedTrackedDiff.Count -gt 0) {
    Fail "tracked diff differs from the frozen 41+21+8+9 path set; missing=[$($missingTrackedDiff -join ', ')]; unexpected=[$($unexpectedTrackedDiff -join ', ')]"
}

$migrationPath = 'yanban-api/src/main/resources/db/migration'
foreach ($version in 1..69) {
    $found = @(Get-ChildItem -LiteralPath (FullPath $migrationPath) -File -Filter "V${version}__*.sql")
    if ($found.Count -lt 1) {
        Fail "retained migration V$version is missing"
    }
}

$protectedPaths = @(
    'yanban-api/src/main/java/com/yanban/api/agent/AgentService.java',
    'yanban-api/src/test/java/com/yanban/api/agent/AgentControllerIntegrationTest.java',
    'frontend/src/views/ChatPage.vue',
    'yanban-api/src/main/resources/db/migration',
    'yanban-api/src/test/resources/db/migration-h2',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2NaturalLanguageTurnRequest.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2NaturalLanguageTurnResponse.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2SessionDeletionService.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2TurnIntakeEntity.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2TurnIntakeJpaRepository.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveTurnDeletionService.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveTurnEntity.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveTurnRepository.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2/intake/V2SessionDeletionServiceTest.java',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/compatibility/literature'
)
$literatureAndLoopFiles = @(
    Get-ChildItem -LiteralPath (FullPath 'yanban-api/src/main/java/com/yanban/api/agent/v2/effect') -File |
        Where-Object { $_.Name -like '*Literature*' } |
        ForEach-Object { $_.FullName.Substring($repo.Length + 1).Replace('\', '/') }
    Get-ChildItem -LiteralPath (FullPath 'yanban-api/src/main/java/com/yanban/api/agent/v2/loop') -File |
        Where-Object {
            $_.Name -eq 'AuthenticatedPersistentPlanAgentLoopComposer.java' -or
            $_.Name -like 'PersistentPlanAgentLoop*.java'
        } |
        ForEach-Object { $_.FullName.Substring($repo.Length + 1).Replace('\', '/') }
)
Assert-NoGitDiff ($protectedPaths + $literatureAndLoopFiles) 'retained Session/Workspace Chat/Literature/migration scope'

$ownedDiffPaths = @(
    $sharedCuts,
    'yanban-api/src/main/java/com/yanban/api/agent/v2/intake',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/context',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/loop',
    'yanban-api/src/main/java/com/yanban/api/agent/v2/compatibility/project',
    'yanban-api/src/main/java/com/yanban/api/project/CandidateValidationRepair.java',
    'yanban-api/src/main/java/com/yanban/api/project/CandidateValidationRepairRepository.java',
    'yanban-api/src/main/java/com/yanban/api/project/CandidateValidationRepairService.java',
    'yanban-api/src/test/java/com/yanban/api/agent/v2',
    $frontendOwnedPaths
)
$diffArguments = @('-C', $repo, 'diff', '--check', '--') + $ownedDiffPaths
$diffCheck = & git @diffArguments 2>&1
if ($LASTEXITCODE -ne 0) {
    Fail "owned tracked diff has whitespace errors:`n$($diffCheck -join "`n")"
}

$currentOwnedFiles = @(
    $ownedDiffPaths | ForEach-Object {
        $path = FullPath $_
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            Get-Item -LiteralPath $path
        } elseif (Test-Path -LiteralPath $path -PathType Container) {
            Get-ChildItem -LiteralPath $path -Recurse -File
        }
    }
    Get-Item -LiteralPath (FullPath 'scripts/verification/verify-agent-v2-chain-delete.ps1')
) | Sort-Object FullName -Unique
foreach ($file in $currentOwnedFiles) {
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $file.FullName) {
        $lineNumber++
        if ($line -match '[ \t]+$') {
            Fail "trailing whitespace at $($file.FullName):$lineNumber"
        }
    }
}

Assert-Contains 'yanban-api/src/test/java/com/yanban/api/project/ProjectControllerV2AvailabilityTest.java' 'disabledV2DoesNotGateCandidateApply'
Assert-Contains 'yanban-api/src/test/java/com/yanban/api/agent/v2/compatibility/literature/AgentControllerV2LiteratureEndpointTest.java' 'explicitEndpointDelegatesOnlyToV2Capability'

Write-Host 'DELETE-01 passed: 41 production files and 21 tests are absent; seven legacy handlers and repair callbacks are gone; retained boundaries are unchanged.'
