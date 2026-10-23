param(
    [string]$ProductionJar = (Join-Path $PSScriptRoot '..\build\libs\end-platform-preserver-1.1.0+mc26.3.jar'),
    [string]$JavaDirectory = 'C:\Program Files\Java\jdk-26.0.1',
    [string]$MinecraftDirectory = (Join-Path $env:APPDATA '.minecraft'),
    [ValidateSet('client', 'server')]
    [string]$Environment = 'client',
    [string]$ServerBundlerJar = (Join-Path $PSScriptRoot '..\.tools\minecraft-26.3-server.jar'),
    [switch]$NegativeControl
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
$buildDirectory = Join-Path $PSScriptRoot 'build'
$classesDirectory = Join-Path $buildDirectory 'classes'
$runName = $(if ($Environment -eq 'server') { 'run-server' } else { 'run' })
if ($NegativeControl) { $runName += '-control' }
$runDirectory = Join-Path $PSScriptRoot $runName
$javac = Join-Path $JavaDirectory 'bin\javac.exe'
$java = Join-Path $JavaDirectory 'bin\java.exe'
$minecraftJar = Join-Path $MinecraftDirectory 'versions\26.3\26.3.jar'
$loaderJar = Join-Path $MinecraftDirectory 'libraries\net\fabricmc\fabric-loader\0.19.5\fabric-loader-0.19.5.jar'
$metadataPath = Join-Path $MinecraftDirectory 'versions\26.3\26.3.json'
$dependencyDirectory = Join-Path $projectDirectory '.tools\deps'

$requiredInputs = @($javac, $java, $loaderJar, $ProductionJar, $dependencyDirectory)
if ($Environment -eq 'client') { $requiredInputs += @($minecraftJar, $metadataPath) }
else { $requiredInputs += $ServerBundlerJar }
foreach ($requiredPath in $requiredInputs) {
    if (!(Test-Path -LiteralPath $requiredPath)) { throw "Required verification input is missing: $requiredPath" }
}
$ProductionJar = (Resolve-Path -LiteralPath $ProductionJar).Path
if ($Environment -eq 'client') {
    $metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
    $gameLibraries = @($metadata.libraries | ForEach-Object {
        if ($_.downloads.artifact.path) { Join-Path (Join-Path $MinecraftDirectory 'libraries') $_.downloads.artifact.path }
    } | Where-Object { Test-Path -LiteralPath $_ })
} else {
    # Use only the official server's game JAR and bundled libraries; no client game classes.
    $serverDirectory = Join-Path $buildDirectory 'official-server'
    New-Item -ItemType Directory -Path $serverDirectory -Force | Out-Null
    $gameLibraries = @()
    $serverArchive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ServerBundlerJar).Path)
    try {
        $serverEntries = @($serverArchive.Entries | Where-Object {
            $_.FullName.EndsWith('.jar') -and ($_.FullName.StartsWith('META-INF/versions/') -or $_.FullName.StartsWith('META-INF/libraries/'))
        })
        $versionEntries = @($serverEntries | Where-Object { $_.FullName.StartsWith('META-INF/versions/') })
        if ($versionEntries.Count -ne 1) { throw 'Expected exactly one game JAR in the official server bundler.' }
        foreach ($serverEntry in $serverEntries) {
            $relativeName = $serverEntry.FullName.Substring('META-INF/'.Length)
            $extractedPath = [IO.Path]::GetFullPath((Join-Path $serverDirectory $relativeName))
            if (!$extractedPath.StartsWith($serverDirectory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Unexpected server archive entry outside verification/build.'
            }
            New-Item -ItemType Directory -Path (Split-Path $extractedPath -Parent) -Force | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($serverEntry, $extractedPath, $true)
            if ($serverEntry.FullName.StartsWith('META-INF/versions/')) { $minecraftJar = $extractedPath }
            else { $gameLibraries += $extractedPath }
        }
    } finally {
        $serverArchive.Dispose()
    }
}
$loaderDependencies = @(Get-ChildItem -LiteralPath $dependencyDirectory -Filter '*.jar' | ForEach-Object { $_.FullName })
if ($loaderDependencies.Count -lt 6) { throw 'Expected the downloaded ASM and Sponge Mixin dependencies in .tools/deps.' }
$runtimeClasspath = (@($minecraftJar, $loaderJar) + $loaderDependencies + $gameLibraries) -join [IO.Path]::PathSeparator
New-Item -ItemType Directory -Path $classesDirectory, $runDirectory -Force | Out-Null

$compileArguments = @('--release', '25', '-encoding', 'UTF-8', '-cp',
    ($runtimeClasspath + [IO.Path]::PathSeparator + $ProductionJar), '-d', $classesDirectory)
$compileArguments += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java') -Filter '*.java' -Recurse |
    ForEach-Object { $_.FullName })
& $javac @compileArguments
if ($LASTEXITCODE -ne 0) { throw "Harness compilation failed with exit code $LASTEXITCODE" }
Copy-Item -Path (Join-Path $PSScriptRoot 'src\main\resources\*') -Destination $classesDirectory -Force

function Write-VerificationArchive([string]$SourceDirectory, [string]$DestinationJar) {
    # Write directly in the workspace; Java's JAR utility can fail moving a Windows sandbox temp file.
    $stream = [IO.File]::Create($DestinationJar)
    $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($sourceFile in Get-ChildItem -LiteralPath $SourceDirectory -File -Recurse) {
            $entryName = [IO.Path]::GetRelativePath($SourceDirectory, $sourceFile.FullName).Replace('\', '/')
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $sourceFile.FullName, $entryName) | Out-Null
        }
    } finally {
        $archive.Dispose()
        $stream.Dispose()
    }
}

$verificationJar = Join-Path $buildDirectory 'end-platform-preserver-verification.jar'
Write-VerificationArchive $classesDirectory $verificationJar

if ($NegativeControl) {
    # Keep the rule registration and production classes, but omit the platform mixin.
    $controlJar = Join-Path $buildDirectory 'negative-control.jar'
    $sourceArchive = [IO.Compression.ZipFile]::OpenRead($ProductionJar)
    $controlStream = [IO.File]::Create($controlJar)
    $controlArchive = [IO.Compression.ZipArchive]::new($controlStream, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($sourceEntry in $sourceArchive.Entries) {
            $targetEntry = $controlArchive.CreateEntry($sourceEntry.FullName)
            $inputStream = $sourceEntry.Open()
            $outputStream = $targetEntry.Open()
            try {
                if ($sourceEntry.FullName -eq 'end-platform-preserver.mixins.json') {
                    $reader = [IO.StreamReader]::new($inputStream)
                    $mixinConfig = $reader.ReadToEnd() | ConvertFrom-Json
                    $mixinConfig.mixins = @($mixinConfig.mixins | Where-Object { $_ -ne 'EndPlatformFeatureMixin' })
                    $writer = [IO.StreamWriter]::new($outputStream, [Text.UTF8Encoding]::new($false), 1024, $true)
                    try { $writer.Write(($mixinConfig | ConvertTo-Json -Depth 10)) } finally { $writer.Dispose() }
                } else {
                    $inputStream.CopyTo($outputStream)
                }
            } finally {
                $inputStream.Dispose()
                $outputStream.Dispose()
            }
        }
    } finally {
        $sourceArchive.Dispose()
        $controlArchive.Dispose()
    }
    $ProductionJar = $controlJar
}

Push-Location $runDirectory
try {
    $consoleLog = Join-Path $runDirectory 'verification-console.log'
    $knotClass = $(if ($Environment -eq 'server') { 'net.fabricmc.loader.impl.launch.knot.KnotServer' }
        else { 'net.fabricmc.loader.impl.launch.knot.KnotClient' })
    & $java '-Dfabric.gameVersion=26.3' "-Dfabric.addMods=$ProductionJar;$verificationJar" `
        '-Djava.awt.headless=true' -cp $runtimeClasspath $knotClass 2>&1 | Tee-Object -FilePath $consoleLog
    $verificationExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($NegativeControl) {
    if ($verificationExit -eq 0) { throw 'Negative control unexpectedly passed; assertions did not detect vanilla clearing.' }
    if (!(Select-String -LiteralPath $consoleLog -Pattern 'AssertionError: Feature touched protected space' -Quiet)) {
        throw "Negative control failed for an unexpected reason; inspect $consoleLog"
    }
    Write-Host "PASS: negative control rejected vanilla upper-space interaction (exit $verificationExit)."
} elseif ($verificationExit -ne 0) {
    throw "Platform verification failed with exit code $verificationExit"
}
