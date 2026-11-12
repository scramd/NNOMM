from pathlib import Path
from playwright.sync_api import sync_playwright
from PIL import Image

root = Path.cwd()
out = root / "composeApp/build/shots/compare"
out.mkdir(parents=True, exist_ok=True)
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    for theme in ["dark", "light"]:
        for page in ["library", "browse"]:
            for width, height, label in [(1360, 860, ""), (760, 820, "-narrow")]:
                tab = browser.new_page(
                    viewport={"width": width, "height": height}, device_scale_factor=1
                )
                tab.goto(
                    (root / "design/mock/nnomm-mock.html").as_uri()
                    + f"#fill=1&page={page}&theme={theme}"
                )
                tab.wait_for_timeout(400)
                tab.screenshot(path=str(out / f"mock-{page}{label}-{theme}.png"))
                tab.close()
        # Standalone specimen uses the mock stylesheet verbatim and the same data/region as Kotlin's demos.
        tab = browser.new_page(
            viewport={"width": 1328, "height": 792}, device_scale_factor=1
        )
        tab.goto(
            (root / "design/mock/nnomm-mock.html").as_uri()
            + f"#fill=1&page=library&theme={theme}"
        )
        table = '<div style="padding:18px;height:60px;display:flex;gap:12px;align-items:center"><h1 style="font-size:20px">Library</h1><span>500 rows, 0 selected</span></div>'
        table += '<div class="tr head lib-cols"><span class="ck"></span><span></span><span></span><button class="cur">Name ↑</button><span>Version</span><span>Source</span><span>Size</span><span>Released</span><span></span></div>'
        colors = [
            "#566b3c",
            "#5d6732",
            "#8a5a2b",
            "#3f5f5a",
            "#6b4f6e",
            "#7a6a2e",
            "#4d5d78",
            "#7b4636",
        ]
        for i in range(1, 21):
            on = i in [1, 2, 3, 5, 8]
            table += f'<div class="tr body lib-cols {"" if on else "off"}"><span class="ck"></span><span class="tog" aria-checked="{str(on).lower()}"></span><span class="ico" style="background:{colors[(i - 1) % 8]}">EM</span><div class="nm"><b>Example mod {i}</b></div><div>1.2.{i}</div><div>NOMNOM</div><div>2.4 MB</div><div>2 days ago</div><span>…</span></div>'
        tab.evaluate(
            '(html) => {document.body.innerHTML = `<div class="nn" data-theme="'
            + theme
            + '" style="display:block;width:1328px;height:792px;border:0;border-radius:0"><div style="height:38px"></div>${html}</div>`;document.body.style.padding="0"}',
            table,
        )
        tab.screenshot(path=str(out / f"mock-kit-table-{theme}.png"))
        # compare the exact header + rows region, excluding unrelated shell and title font
        a = Image.open(out / f"mock-kit-table-{theme}.png").crop((0, 98, 1328, 466))
        b = Image.open(root / f"composeApp/build/shots/kit-table-{theme}.png").crop(
            (0, 98, 1328, 466)
        )
        joined = Image.new("RGB", (2656, 368))
        joined.paste(a, (0, 0))
        joined.paste(b, (1328, 0))
        joined.save(out / f"table-{theme}.png")
        card = '<div style="padding:18px;display:block"><div style="height:30px"></div><div class="strip">'
        for i in range(1, 7):
            card += f'<div class="card"><div class="top"><span class="ico m" style="background:{colors[(i - 1) % 8]}">EM</span><div><div class="name">Example mod {i}</div><div class="by">by KopterBuzz</div></div></div><div class="desc">A small improvement for your next flight.</div><div class="foot"><span class="meta">12.4k</span><button class="btn sm">Install</button></div></div>'
        card += "</div></div>"
        tab.evaluate(
            '(html) => {document.body.innerHTML = `<div class="nn" data-theme="'
            + theme
            + '" style="display:block;width:1328px;height:792px;border:0;border-radius:0"><div style="height:38px"></div>${html}</div>`;document.body.style.padding="0"}',
            card,
        )
        tab.screenshot(path=str(out / f"mock-kit-cards-{theme}.png"))
        a = Image.open(out / f"mock-kit-cards-{theme}.png").crop((18, 86, 1270, 210))
        b = Image.open(root / f"composeApp/build/shots/kit-cards-{theme}.png").crop(
            (18, 86, 1270, 210)
        )
        joined = Image.new("RGB", (2504, 124))
        joined.paste(a, (0, 0))
        joined.paste(b, (1252, 0))
        joined.save(out / f"cards-{theme}.png")
        tab.close()
    browser.close()
