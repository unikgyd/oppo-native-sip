param([string]$Apk = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk")
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$dist = Join-Path $project 'dist'
$stage = Join-Path $dist ('.module-stage-' + [Guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Force -Path "$stage\system\app\MySIP" | Out-Null
    Copy-Item -LiteralPath $apkPath -Destination "$stage\system\app\MySIP\MySIP.apk"
    foreach ($name in @('module.prop', 'customize.sh', 'service.sh', 'uninstall.sh')) {
        Copy-Item -LiteralPath (Join-Path "$project\magisk" $name) -Destination $stage -Force
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    # Preinstalled system applications resolve native libraries next to the APK.
    # /data/app extraction performed for ordinary installs cannot be assumed here.
    $archive = [IO.Compression.ZipFile]::OpenRead($apkPath)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match '^lib/(arm64-v8a|armeabi-v7a|x86_64)/([A-Za-z0-9_.+-]+\.so)$') {
                $abiDir = @{ 'arm64-v8a'='arm64'; 'armeabi-v7a'='arm'; 'x86_64'='x86_64' }[$Matches[1]]
                $libDir = Join-Path $stage "system\app\MySIP\lib\$abiDir"
                New-Item -ItemType Directory -Force -Path $libDir | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $libDir $Matches[2]), $true)
            }
        }
    } finally { $archive.Dispose() }
    # Android shell scripts require LF even when the project is checked out on Windows.
    Get-ChildItem -LiteralPath $stage -Filter '*.sh' | ForEach-Object {
        [IO.File]::WriteAllText($_.FullName, [IO.File]::ReadAllText($_.FullName).Replace("`r`n", "`n"), [Text.UTF8Encoding]::new($false))
    }
    $moduleVersion = (Get-Content -LiteralPath "$project\magisk\module.prop" | Where-Object { $_ -match '^version=' }) -replace '^version=', ''
    if ($moduleVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid module version' }
    $zip = Join-Path $dist "MySIP-system-$moduleVersion.zip"
    $temporaryZip = $stage + '.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory($stage, $temporaryZip)
    Move-Item -LiteralPath $temporaryZip -Destination $zip -Force
    Copy-Item -LiteralPath $apkPath -Destination (Join-Path $dist "MySIP-$moduleVersion.apk") -Force
    Get-FileHash -Algorithm SHA256 -LiteralPath $zip, (Join-Path $dist "MySIP-$moduleVersion.apk") | Format-Table
} finally {
    # This directory was created for this invocation, never an existing checkout.
    $resolvedStage = [IO.Path]::GetFullPath($stage)
    $resolvedDist = [IO.Path]::GetFullPath($dist) + [IO.Path]::DirectorySeparatorChar
    if (!$resolvedStage.StartsWith($resolvedDist, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $resolvedStage -Leaf) -notmatch '^\.module-stage-[a-f0-9]{32}$') {
        throw 'Refusing to remove a staging directory outside dist'
    }
    if (Test-Path -LiteralPath $resolvedStage) {
        if ((Get-Item -LiteralPath $resolvedStage -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Unexpected staging link' }
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
    if (Test-Path -LiteralPath ($resolvedStage + '.zip')) { Remove-Item -LiteralPath ($resolvedStage + '.zip') -Force }
}
