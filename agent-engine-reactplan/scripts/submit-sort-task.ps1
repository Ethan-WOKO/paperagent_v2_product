param(
    [Parameter(Mandatory = $true)]
    [long]$TurnId,
    [string]$ApiOrigin = "http://127.0.0.1:8080",
    [string]$Instruction = "读取 Sort.java，在沙箱中用 yanban-runner java Sort.java 编译或运行，并根据正式回执解释结果。",
    [int]$PollSeconds = 2,
    [int]$MaximumPolls = 180
)

$ErrorActionPreference = "Stop"
$accessToken = $env:PAPERAGENT_ACCESS_TOKEN
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    throw "Set PAPERAGENT_ACCESS_TOKEN to the signed-in product access token first."
}
if ($PollSeconds -lt 1 -or $MaximumPolls -lt 1) {
    throw "PollSeconds and MaximumPolls must be positive."
}

$headers = @{ Authorization = "Bearer $accessToken" }
$base = "$($ApiOrigin.TrimEnd('/'))/api/v1/react-agent/turns/$TurnId/tasks"
$request = @{
    instruction = $Instruction
    provider = "deepseek"
    model = "deepseek-chat"
} | ConvertTo-Json

$accepted = Invoke-RestMethod -Method Post -Uri $base -Headers $headers `
    -ContentType "application/json" -Body $request
$taskId = if ($accepted.taskId) { $accepted.taskId } else { $accepted.task.taskId }
if ([string]::IsNullOrWhiteSpace($taskId)) {
    throw "The product accepted the request but returned no taskId."
}
Write-Host "Accepted task: $taskId"

$view = $null
for ($attempt = 1; $attempt -le $MaximumPolls; $attempt++) {
    $view = Invoke-RestMethod -Uri "$base/$taskId" -Headers $headers
    Write-Host "[$attempt/$MaximumPolls] state=$($view.state) sequence=$($view.lastSequence)"
    if ($view.state -in @("succeeded", "failed", "cancelled", "waiting_user")) { break }
    Start-Sleep -Seconds $PollSeconds
}

if ($null -eq $view -or $view.state -notin @("succeeded", "failed", "cancelled", "waiting_user")) {
    throw "Task did not reach an observable stopping state before the polling limit."
}

Write-Host "Final task view:"
$view | ConvertTo-Json -Depth 8
Write-Host "Event replay (the two-second curl timeout is intentional):"
& curl.exe --silent --show-error --max-time 2 -N `
    -H "Authorization: Bearer $accessToken" `
    -H "Last-Event-ID: 0" `
    "$base/$taskId/events"
if ($LASTEXITCODE -notin @(0, 28)) {
    throw "curl event replay failed with exit code $LASTEXITCODE"
}
