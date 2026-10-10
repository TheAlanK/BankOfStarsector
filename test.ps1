# Bank of Starsector - test runner.
#   1. builds the jar (no install)
#   2. SandboxCheck: replays Starsector's script class-loader policy over every referenced class
#   3. LinkCheck:    loads + links every class against the game's jars on the game's own JRE
#   4. BankSim:      runs the loan lifecycle month by month against a stubbed sector
#   5. -Smoke:       launches the real game with -DlaunchDirect and scans starsector.log for errors
param(
    [string]$StarsectorDir = 'E:\Games\Starsector',
    [string]$Jdk = $env:JAVA_HOME,
    [switch]$Smoke,
    [int]$SmokeTimeoutSec = 600
)
$ErrorActionPreference = 'Stop'
$ModDir = $PSScriptRoot
$core = Join-Path $StarsectorDir 'starsector-core'
$gameJava = Join-Path $StarsectorDir 'jre\bin\java.exe'
$javac = if ($Jdk) { Join-Path $Jdk 'bin\javac.exe' } else { 'javac' }
$jar = Join-Path $ModDir 'jars\BankOfStarsector.jar'
$out = Join-Path $ModDir 'build\tests'
$failed = 0

& (Join-Path $ModDir 'build.ps1') -StarsectorDir $StarsectorDir -Jdk $Jdk -NoInstall

$ErrorActionPreference = 'Continue'
$gameJars = @(Get-ChildItem $core -Filter *.jar | ForEach-Object FullName)
$optional = @('mods\Nexerelin\jars\ExerelinCore.jar', 'mods\NexusUI\jars\NexusUI.jar', 'mods\LunaLib-2.0.5\jars\LunaLib.jar') |
    ForEach-Object { Join-Path $StarsectorDir $_ } | Where-Object { Test-Path $_ }
$cp = (@($gameJars) + $jar) -join ';'

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null
& $javac -nowarn --release 17 -cp $cp -d $out (Get-ChildItem (Join-Path $ModDir 'tests') -Filter *.java | ForEach-Object FullName) 2>&1 | ForEach-Object { "$_" }
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }

function Run($name, [scriptblock]$cmd, [string]$okPattern) {
    Write-Host "`n=== $name"
    $lines = & $cmd 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -notmatch 'log4j:WARN' }
    $lines | Where-Object { $_ -cmatch 'PASS|FAIL|BLOCKED|SANDBOX|STRINGS|keys used|linked|ALL SCEN|^\[|Exception' } | ForEach-Object { Write-Host $_ }
    if (-not ($lines -match $okPattern) -or ($lines -cmatch '^\s*FAIL |BLOCKED in')) { $script:failed++ ; Write-Host "!!! $name FAILED" -ForegroundColor Red }
}

Run 'Sandbox policy' { & $gameJava -cp $out SandboxCheck $jar } 'SANDBOX OK'
Run 'Link against game jars' { & $gameJava -cp $out LinkCheck $jar $core @optional } 'failures 0'
Run 'Translation tables' { & $gameJava -cp "$out;$cp" StringsCheck $ModDir } 'STRINGS OK'
Run 'Loan lifecycle simulation' { & $gameJava "-Dbos.mod=$ModDir" -cp "$out;$cp" BankSim } 'ALL SCENARIOS PASSED'

if ($Smoke) {
    Write-Host "`n=== In-game smoke test (-DlaunchDirect)"
    $log = Join-Path $core 'starsector.log'
    $vm = (Get-Content (Join-Path $StarsectorDir 'vmparams') -Raw).Trim()
    $gameArgs = $vm.Substring($vm.IndexOf(' ') + 1) -replace ' com\.fs\.starfarer\.StarfarerLauncher$', ''
    $start = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }
    $p = Start-Process -FilePath $gameJava -ArgumentList "-DlaunchDirect=true -DstartRes=1280x720 -DstartFS=false -DstartSound=false $gameArgs com.fs.starfarer.StarfarerLauncher" `
        -WorkingDirectory $core -PassThru -WindowStyle Minimized
    $deadline = (Get-Date).AddSeconds($SmokeTimeoutSec)
    $text = ''
    $lastLen = -1
    $quietSince = Get-Date
    while ((Get-Date) -lt $deadline -and -not $p.HasExited) {
        Start-Sleep -Seconds 5
        $fs = [IO.File]::Open($log, 'Open', 'Read', 'ReadWrite')
        try {
            if ($fs.Length -lt $start) { $start = 0 }
            $fs.Seek($start, 'Begin') | Out-Null
            $text = (New-Object IO.StreamReader($fs)).ReadToEnd()
        } finally { $fs.Close() }
        # No main-menu marker exists in the log: loaded = our plugin ran and the log went quiet.
        if ($text.Length -ne $lastLen) { $lastLen = $text.Length; $quietSince = Get-Date }
        elseif ($text -match 'Bank of Starsector v' -and ((Get-Date) - $quietSince).TotalSeconds -ge 25) { break }
    }
    $killed = $false
    if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force; $killed = $true }
    $bos = $text -split "`n" | Where-Object { $_ -match 'bankofstarsector|bank_of_starsector|BankOfStarsector|bos_' }
    $bos | Select-Object -First 30 | ForEach-Object { Write-Host $_.Trim() }
    $foreign = $text -split "`n" | Where-Object { $_ -match '^\d+ +\[[^\]]+\] ERROR' -and $_ -notmatch 'com\.bankofstarsector' }
    if ($foreign) { Write-Host 'Errors from OTHER mods/game (not counted against this mod):' -ForegroundColor Yellow; $foreign | Select-Object -First 5 | ForEach-Object { Write-Host ($_.Trim().Substring(0, [Math]::Min(200, $_.Trim().Length))) -ForegroundColor Yellow } }
    if (-not $killed -and $p.ExitCode -ne 0) { Write-Host "!!! game exited early (code $($p.ExitCode))" -ForegroundColor Red; $failed++ }
    $errors = $text -split "`n" | Where-Object { $_ -match '(ERROR|Exception|Caused by)' -and $_ -match 'com\.bankofstarsector|BOS:|Bank of Starsector:' }
    if ($errors) { $errors | ForEach-Object { Write-Host $_.Trim() -ForegroundColor Red }; $failed++ }
    elseif (-not ($text -match 'Bank of Starsector v')) { Write-Host '!!! mod plugin never loaded' -ForegroundColor Red; $failed++ }
    else { Write-Host 'SMOKE OK: mod data and plugin loaded without errors' }
}

Write-Host ""
if ($failed -eq 0) { Write-Host 'ALL TESTS PASSED' -ForegroundColor Green } else { Write-Host "$failed TEST GROUP(S) FAILED" -ForegroundColor Red; exit 1 }
