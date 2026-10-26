param([switch]$Offline, [switch]$NoProxy, [string]$LinphoneAar, [string]$XposedApi, [string]$GradleExecutable)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $argsList = @('--console=plain', 'assembleDebug', 'testDebugUnitTest', 'lintDebug')
    if ($NoProxy) { $argsList += @('-Dhttp.proxyHost=', '-Dhttps.proxyHost=', '-Dhttp.nonProxyHosts=*') }
    if ($Offline) { $argsList += '--offline' }
    if (!$LinphoneAar -and (Test-Path -LiteralPath '.tools\linphone-complete.aar')) { $LinphoneAar = (Resolve-Path -LiteralPath '.tools\linphone-complete.aar').Path }
    if ($LinphoneAar) { $argsList += "-PlinphoneAar=$((Resolve-Path -LiteralPath $LinphoneAar).Path)" }
    if (!$XposedApi -and (Test-Path -LiteralPath '.tools\xposed-api-82.jar')) { $XposedApi = (Resolve-Path -LiteralPath '.tools\xposed-api-82.jar').Path }
    if ($XposedApi) { $argsList += "-PxposedApi=$((Resolve-Path -LiteralPath $XposedApi).Path)" }
    if ($GradleExecutable) { & (Resolve-Path -LiteralPath $GradleExecutable).Path @argsList }
    elseif ($env:OS -eq 'Windows_NT') { & .\gradlew.bat @argsList }
    else { & ./gradlew @argsList }
    if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed' }
    & "$PSScriptRoot\package-module.ps1"
} finally { Pop-Location }
