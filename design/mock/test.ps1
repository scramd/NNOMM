# Builds a test copy of the mock with src/selftest.js added, runs it in headless Chrome and prints the result.
$root = $PSScriptRoot
$enc = New-Object Text.UTF8Encoding($false)
$html = [IO.File]::ReadAllText((Join-Path $root 'nnomm-mock.html'))
$test = [IO.File]::ReadAllText((Join-Path $root 'src\selftest.js'))
$i = $html.LastIndexOf('</script>')
$file = Join-Path $env:TEMP "nnomm-mock-test-$(Get-Random).html"
[IO.File]::WriteAllText($file, $html.Substring(0, $i) + "`n" + $test + "`n" + $html.Substring($i), $enc)
$out = "$file.dom.txt"
$p = Start-Process -FilePath 'C:\Program Files\Google\Chrome\Application\chrome.exe' -ArgumentList @('--headless=new', '--disable-gpu', '--virtual-time-budget=2000', '--dump-dom', ('"file:///' + ($file -replace '\\', '/') + '"')) -PassThru -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError "$file.err.txt"
if (-not $p.WaitForExit(90000)) { $p.Kill() }
Start-Sleep -Seconds 1
$dom = [string](Get-Content $out -Raw -ErrorAction SilentlyContinue)
$m = [regex]::Match($dom, '<pre id="selftest">([\s\S]*?)</pre>')
if ($m.Success) { [System.Net.WebUtility]::HtmlDecode($m.Groups[1].Value) } else { 'no selftest output' }
[regex]::Matches($dom, 'class="mockerr">([^<]*)') | ForEach-Object { 'MOCKERR: ' + [System.Net.WebUtility]::HtmlDecode($_.Groups[1].Value) }
Remove-Item $file, $out, "$file.err.txt" -ErrorAction SilentlyContinue
