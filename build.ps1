param([string]$Maven = 'mvn')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try { & $Maven clean verify; if ($LASTEXITCODE -ne 0) { throw 'Сборка не прошла.' } } finally { Pop-Location }
