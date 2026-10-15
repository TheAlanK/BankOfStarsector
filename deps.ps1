# Bank of Starsector - other mods' jars the bridge classes compile and link against.
# Mod managers install mods into versioned folders (Nexerelin-0.12.2d) and keep older copies around
# without a mod_info.json, so the jars are looked up by name: a folder with mod_info.json wins, then the
# newest jar. Dot-sourced by build.ps1 and test.ps1.
function Get-ModJars([string]$StarsectorDir) {
    $mods = Join-Path $StarsectorDir 'mods'
    $wanted = [ordered]@{
        'Nexerelin'        = 'jars\ExerelinCore.jar'
        'NexusUI'          = 'jars\NexusUI.jar'
        'LunaLib'          = 'jars\LunaLib.jar'
        'Console Commands' = 'jars\lw_Console.jar'
    }
    $found = [ordered]@{}
    foreach ($name in $wanted.Keys) {
        $hits = Get-ChildItem $mods -Directory | ForEach-Object {
            $jar = Join-Path $_.FullName $wanted[$name]
            if (Test-Path $jar) {
                [pscustomobject]@{
                    Jar    = $jar
                    Active = [int](Test-Path (Join-Path $_.FullName 'mod_info.json'))
                    Time   = (Get-Item $jar).LastWriteTime
                }
            }
        }
        $best = $hits | Sort-Object Active, Time -Descending | Select-Object -First 1
        if ($best) { $found[$name] = $best.Jar }
    }
    $found
}
