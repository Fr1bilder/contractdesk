param([switch]$Demo, [string]$Data)
$ErrorActionPreference = 'Stop'
$jar = Join-Path $PSScriptRoot 'target\contractdesk-1.0.0.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Сначала выполните .\build.ps1' }
$appArgs = @('-jar', $jar)
if ($Demo) { $appArgs += '--demo' }
if ($Data) { $appArgs += @('--data', $Data) }
& java @appArgs
if ($LASTEXITCODE -ne 0) { throw 'Приложение завершилось с ошибкой.' }
