# Builds one self-contained HTML file from src/, and optional screenshots with headless Chrome.
param([switch]$Shots)
$root = $PSScriptRoot
$src = Join-Path $root 'src'
$font = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $root 'archivo-latin.woff2')))
$css = [IO.File]::ReadAllText((Join-Path $src 'styles.css'))
$js = (@('data.js', 'core.js', 'views.js', 'app.js') | ForEach-Object { [IO.File]::ReadAllText((Join-Path $src $_)) }) -join "`n"
$html = [IO.File]::ReadAllText((Join-Path $src 'template.html'))
$html = $html.Replace('__FONT__', $font).Replace('__CSS__', $css).Replace('__JS__', $js)
$out = Join-Path $root 'nnomm-mock.html'
[IO.File]::WriteAllText($out, $html, (New-Object Text.UTF8Encoding($false)))
"built $out ($([Math]::Round((Get-Item $out).Length / 1KB)) KB)"
# The served copy fills the browser window (site/index.html, behind the Local Router route "nnomm").
$site = Join-Path $root 'site'; New-Item -ItemType Directory -Force $site | Out-Null
[IO.File]::WriteAllText((Join-Path $site 'index.html'), $html.Replace('<script>', '<script>window.NN_FILL = true;'), (New-Object Text.UTF8Encoding($false)))
if ($Shots) {
    $chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
    $dir = Join-Path $root 'shots'
    New-Item -ItemType Directory -Force $dir | Out-Null
    $url = 'file:///' + ($out -replace '\\', '/')
    $list = [ordered]@{
        '01-browse'           = @('page=browse', 1360, 860)
        '02-browse-search'    = @('page=browse&q=hud&panel=mod:fuel-planner', 1360, 860)
        '03-browse-list-tags' = @('page=browse&view=list&tags=Map,Visual', 1360, 860)
        '04-library'          = @('page=library', 1360, 860)
        '05-library-bulk'     = @('page=library&sel=minimap,delivery,spectator&panel=mod:harrier-kai&tab=needs', 1360, 860)
        '06-library-updates'  = @('page=library&lf=upd&dl=minimap&panel=mod:minimap&tab=new', 1360, 860)
        '07-servers'          = @('page=servers&panel=server:s2', 1360, 860)
        '08-settings-game'    = @('page=settings&cat=game', 1360, 860)
        '09-settings-servers' = @('page=settings&cat=servers', 1360, 860)
        '10-settings-profile' = @('page=settings&scope=profile&pid=hardcore', 1360, 860)
        '11-settings-search'  = @('page=settings&sq=update', 1360, 860)
        '12-palette'          = @('page=library&overlay=palette&pq=map', 1360, 860)
        '13-import'           = @('page=library&overlay=import', 1360, 860)
        '14-pack-update'      = @('page=library&prof=friday&overlay=packupdate', 1360, 860)
        '15-troubleshoot'     = @('page=library&trouble=1', 1360, 860)
        '16-first-start'      = @('mock=first', 1360, 860)
        '17-drop'             = @('page=library&overlay=drop', 1360, 860)
        '18-light-library'    = @('page=library&theme=light&panel=mod:spectator', 1360, 860)
        '19-light-browse'     = @('page=browse&theme=light', 1360, 860)
        '20-narrow-browse'    = @('page=browse', 760, 820)
        '21-narrow-library'   = @('page=library&panel=mod:minimap', 760, 820)
        '22-empty-library'    = @('page=library&mock=empty', 1360, 860)
        '23-offline'          = @('page=browse&mock=offline', 1360, 860)
        '24-new-profile'      = @('page=library&overlay=newprofile', 1360, 860)
        '25-share'            = @('page=library&overlay=share', 1360, 860)
        '26-settings-danger'  = @('page=settings&cat=danger', 1360, 860)
        '27-settings-mods'    = @('page=settings&cat=mods', 1360, 860)
        '28-import-add'       = @('page=library&overlay=import&mode=add', 1360, 860)
        '29-server-unknown'   = @('page=servers&panel=server:s4', 1360, 860)
        '30-local-mod'        = @('page=library&lf=local&panel=mod:local-testhud', 1360, 860)
        '31-friday'           = @('page=library&prof=friday&panel=mod:harrier-kai', 1360, 860)
        '32-settings-registry' = @('page=settings&cat=browse', 1360, 860)
        '33-servers-list'     = @('page=servers', 1360, 860)
        '34-servers-join-new' = @('page=servers&panel=server:s9&join=new', 1360, 860)
        '35-servers-narrow'   = @('page=servers', 760, 820)
        '36-browse-tags'      = @('page=browse&tags=HUD,Map', 1360, 860)
    }
    foreach ($k in $list.Keys) {
        $v = $list[$k]
        $png = Join-Path $dir "$k.png"
        $p = Start-Process -FilePath $chrome -ArgumentList @('--headless=new', '--disable-gpu', '--hide-scrollbars', "--window-size=$($v[1]),$($v[2])", '--virtual-time-budget=1500', "--screenshot=`"$png`"", "`"$url#fill=1&$($v[0])`"") -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $dir 'chrome.err')
        if (-not $p.WaitForExit(40000)) { $p.Kill() }
    }
    "shots: $((Get-ChildItem $dir -Filter *.png).Count)"
}
