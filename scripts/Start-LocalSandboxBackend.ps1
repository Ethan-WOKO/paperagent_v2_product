[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Start-LocalE2bBroker.ps1') -StartBackend
