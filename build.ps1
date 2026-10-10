# Bank of Starsector - build script (PowerShell). Compiles for Java 8 bytecode,
# packs the jar and installs into the game's mods folder.
param(
    [string]$StarsectorDir = 'E:\Games\Starsector',
    [string]$Jdk = $env:JAVA_HOME,
    [switch]$NoInstall
)
$ErrorActionPreference = 'Stop'
$ModDir = $PSScriptRoot
$Src = Join-Path $ModDir 'src'
$Out = Join-Path $ModDir 'build\classes'
$JarDir = Join-Path $ModDir 'jars'
$javac = if ($Jdk) { Join-Path $Jdk 'bin\javac.exe' } else { 'javac' }
$jar = if ($Jdk) { Join-Path $Jdk 'bin\jar.exe' } else { 'jar' }

$core = Join-Path $StarsectorDir 'starsector-core'
$cp = @(Get-ChildItem $core -Filter *.jar | ForEach-Object FullName)
# Compile-time only: the bridge classes reference these, but are loaded at runtime only when the mods are enabled.
foreach ($opt in @('mods\Nexerelin\jars\ExerelinCore.jar', 'mods\NexusUI\jars\NexusUI.jar', 'mods\LunaLib-2.0.5\jars\LunaLib.jar')) {
    $p = Join-Path $StarsectorDir $opt
    if (Test-Path $p) { $cp += $p } else { throw "Missing compile dependency: $p" }
}

Remove-Item -Recurse -Force $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Out, $JarDir | Out-Null
$sources = Get-ChildItem $Src -Recurse -Filter *.java | ForEach-Object FullName
# javac writes notes to stderr; judge success by exit code only.
$ErrorActionPreference = 'Continue'
& $javac --release 8 -encoding UTF-8 -Xlint:-options -cp ($cp -join ';') -d $Out $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
& $jar cf (Join-Path $JarDir 'BankOfStarsector.jar') -C $Out .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
Write-Host "Built jars\BankOfStarsector.jar ($($sources.Count) sources)"

if (-not $NoInstall) {
    $dest = Join-Path $StarsectorDir 'mods\BankOfStarsector'
    New-Item -ItemType Directory -Force $dest | Out-Null
    Copy-Item (Join-Path $ModDir 'mod_info.json') $dest -Force
    Copy-Item (Join-Path $ModDir 'jars') $dest -Recurse -Force
    Copy-Item (Join-Path $ModDir 'data') $dest -Recurse -Force
    Copy-Item (Join-Path $ModDir 'graphics') $dest -Recurse -Force
    foreach ($f in 'README.md', 'LICENSE', 'changelog.txt', 'bank_of_starsector.version') { if (Test-Path (Join-Path $ModDir $f)) { Copy-Item (Join-Path $ModDir $f) $dest -Force } }
    Write-Host "Installed to $dest"
}
