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
. (Join-Path $ModDir 'deps.ps1')
$deps = Get-ModJars $StarsectorDir
foreach ($name in 'Nexerelin', 'NexusUI', 'LunaLib', 'Console Commands') {
    if (-not $deps.Contains($name)) { throw "Missing compile dependency: $name (no jar under $StarsectorDir\mods)" }
    $cp += $deps[$name]
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
    # Two enabled folders with the same mod id break the launcher: a mod manager may have installed a release
    # copy in its own versioned folder.
    $others = Get-ChildItem (Join-Path $StarsectorDir 'mods') -Directory | Where-Object {
        $info = Join-Path $_.FullName 'mod_info.json'
        $_.FullName -ne $dest -and (Test-Path $info) -and ((Get-Content $info -Raw) -match '"id"\s*:\s*"bank_of_starsector"')
    }
    if ($others) {
        throw "Another copy of bank_of_starsector is installed at: $(($others | ForEach-Object FullName) -join ', '). Remove it (or rename its mod_info.json) before installing, or build with -NoInstall."
    }
    New-Item -ItemType Directory -Force $dest | Out-Null
    Copy-Item (Join-Path $ModDir 'mod_info.json') $dest -Force
    Copy-Item (Join-Path $ModDir 'jars') $dest -Recurse -Force
    Copy-Item (Join-Path $ModDir 'data') $dest -Recurse -Force
    Copy-Item (Join-Path $ModDir 'graphics') $dest -Recurse -Force
    foreach ($f in 'README.md', 'LICENSE', 'changelog.txt', 'bank_of_starsector.version') { if (Test-Path (Join-Path $ModDir $f)) { Copy-Item (Join-Path $ModDir $f) $dest -Force } }
    Write-Host "Installed to $dest"
}
