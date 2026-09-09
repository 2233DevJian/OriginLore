param([string]$MinecraftJar, [string]$Stats = 'build/gametest/item_stats_1_21_1.txt')
$ErrorActionPreference = 'Stop'
if (-not $MinecraftJar) {
    $MinecraftJar = Get-ChildItem -LiteralPath '.gradle/loom-cache/minecraftMaven/net/minecraft' -Recurse -Filter 'minecraft-common-*.jar' |
        Where-Object { $_.Name -notlike '*-sources.jar' } | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $MinecraftJar) { throw 'Pass -MinecraftJar with the Minecraft 1.21.1 common jar.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sources = @{}
function Add-Source([string]$Item, [string]$Source) {
    if (-not $Item -or -not $Source) { return }
    if (-not $sources.ContainsKey($Item)) { $sources[$Item] = [System.Collections.Generic.HashSet[string]]::new() }
    [void]$sources[$Item].Add($Source)
}
function Read-Loot($Node, [string]$Source) {
    if ($Node -is [System.Collections.IDictionary]) {
        if ($Node.type -eq 'minecraft:item') { Add-Source $Node.name $Source }
        foreach ($value in $Node.Values) { Read-Loot $value $Source }
    } elseif ($Node -is [array]) {
        foreach ($value in $Node) { Read-Loot $value $Source }
    }
}
$lootKinds = @{ block='BLOCK_DROP'; chest='CHEST_LOOT'; entity='ENTITY_DROP'; fishing='FISHING'; archaeology='ARCHAEOLOGY'; barter='BARTER'; gift='GIFT'; vault='VAULT' }
$recipeKinds = @{ crafting_shaped='CRAFTING'; crafting_shapeless='CRAFTING'; smelting='SMELTING'; smoking='SMELTING'; blasting='SMELTING'; campfire_cooking='SMELTING'; smithing_transform='SMITHING'; stonecutting='CUTTING' }
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $MinecraftJar))
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -notmatch '^data/minecraft/(recipe|loot_table)/.+\.json$') { continue }
        $kind = $Matches[1]
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { $document = $reader.ReadToEnd() | ConvertFrom-Json -AsHashtable } finally { $reader.Dispose() }
        $type = ($document.type -replace '^minecraft:', '')
        if ($kind -eq 'recipe') {
            $result = $document.result
            $item = if ($result -is [string]) { $result } elseif ($result.id) { $result.id } else { $result.item }
            Add-Source $item $recipeKinds[$type]
        } else {
            $source = $lootKinds[$type]
            if ($entry.FullName -match '/chests/trial_chambers/reward') { $source = 'VAULT' }
            if (-not $source -and $entry.FullName -match '/gameplay/fishing') { $source = 'FISHING' }
            if (-not $source -and $entry.FullName -match '/chests/') { $source = 'CHEST_LOOT' }
            Read-Loot $document $source
        }
    }
} finally { $archive.Dispose() }
$statsByItem = [ordered]@{}
foreach ($line in Get-Content -LiteralPath $Stats) {
    if ($line -notmatch '^(minecraft:[a-z0-9_]+)\s+(.+)$') { continue }
    $item = $Matches[1]
    $body = $Matches[2]
    $record = [ordered]@{}
    foreach ($match in [regex]::Matches(($body -split ' effect=')[0], '(\w+)=([\d.]+|true|false)')) {
        $record[$match.Groups[1].Value] = if ($match.Groups[2].Value -match '^(true|false)$') { [bool]::Parse($match.Groups[2].Value) } else { [Math]::Round([double]$match.Groups[2].Value, 4) }
    }
    $effects = @()
    foreach ($match in [regex]::Matches($body, 'effect=(\S+) amplifier=(\d+) duration=(\d+) probability=([\d.]+)')) {
        $effects += [ordered]@{ id=$match.Groups[1].Value; amplifier=[int]$match.Groups[2].Value; duration=[int]$match.Groups[3].Value; probability=[double]$match.Groups[4].Value }
    }
    if ($effects.Count) { $record.effects = $effects }
    $statsByItem[$item] = $record
}
$sourceTable = [ordered]@{}
foreach ($item in ($sources.Keys | Sort-Object)) { $sourceTable[$item] = @($sources[$item] | Sort-Object) }
$result = [ordered]@{ minecraft='1.21.1'; stats=$statsByItem; sources=$sourceTable }
$json = ($result | ConvertTo-Json -Depth 20) -replace "`r`n", "`n"
[System.IO.File]::WriteAllText((Join-Path (Get-Location) 'tools/presets/_vanilla_reference.json'), $json + "`n", [System.Text.UTF8Encoding]::new($false))
Write-Output "Wrote $($statsByItem.Count) item stat records and $($sourceTable.Count) acquisition source records."
