// Overlays, actions and event wiring.
const dlgWrap = (inner, cls = '') => `<div class="scrim" data-act="closeoverlay"><div class="dlg ${cls}" data-act="stop" role="dialog">${inner}</div></div>`;
const SIGN = { add: '+', rem: '-', chg: '', same: '=', newer: '=' };
function diffRow([k, id, a, b]) {
  const x = MOD[id];
  const right = k === 'add' ? size(x.size) : k === 'rem' ? 'turned off, not deleted' : k === 'chg' ? `${a} to ${b}` : k === 'newer' ? `pack asks for ${b}, you keep ${a}` : 'you have it';
  return `<div class="d ${k === 'newer' ? 'same' : k}"><span class="sg">${k === 'chg' ? I('up') : SIGN[k]}</span><span class="nmx ell">${esc(x.name)}</span><span class="faint narrow nw">${right}</span></div>`;
}
const cmpVer = (a, b) => { const x = a.split('.').map(n => parseInt(n, 10) || 0), y = b.split('.').map(n => parseInt(n, 10) || 0); for (let i = 0; i < Math.max(x.length, y.length); i++) { if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) - (y[i] || 0); } return 0; };
// What a pack row means depends on where the pack goes: a new profile takes the pack's versions as they are;
// adding to an existing profile can update a mod, or conflict with a newer version that the profile has.
function importRows(o) {
  const p = profile();
  return PACK_IMPORT.items.filter(i => i[0] !== 'local').map(([k, id, pv]) => {
    const have = isInst(id) ? verOf(id, p.id) : null;
    const kind = k !== 'ver' ? k : o.mode === 'new' ? 'other' : cmpVer(pv, have) > 0 ? 'up' : 'conflict';
    return { kind, id, pv, have };
  });
}
function vImport() {
  const o = S.overlay, P = PACK_IMPORT, p = profile(), rows = importRows(o), local = P.items.filter(i => i[0] === 'local');
  const n = k => rows.filter(r => r.kind === k).length;
  const addKb = rows.filter(r => r.kind === 'add').reduce((a, r) => a + MOD[r.id].size, 0);
  const order = { conflict: 0, add: 1, up: 2, other: 3, same: 4 };
  const line = r => {
    const x = MOD[r.id], keep = o.keep[r.id] !== 'pack';
    const right = r.kind === 'add' ? size(x.size) : r.kind === 'same' ? 'you have it' : r.kind === 'up' ? `${r.have} to ${r.pv}`
      : r.kind === 'other' ? `pack uses ${r.pv}, ${esc(p.name)} keeps ${r.have}`
        : `<span class="seg"><button class="${keep ? 'cur' : ''}" data-act="okeep" data-id="${r.id}" data-v="mine" style="height:22px">Keep ${r.have}</button><button class="${keep ? '' : 'cur'}" data-act="okeep" data-id="${r.id}" data-v="pack" style="height:22px">Use ${r.pv} from the pack</button></span>`;
    const cls = { add: 'add', same: 'same', up: 'chg', other: 'same', conflict: 'rem' }[r.kind];
    return `<div class="d ${cls}"><span class="sg">${r.kind === 'up' ? I('up') : r.kind === 'conflict' ? I('alert') : r.kind === 'add' ? '+' : '='}</span><span class="nmx ell" style="text-decoration:none;color:var(--text)">${esc(x.name)}${r.kind === 'conflict' ? ` <span class="faint">${esc(p.name)} has a newer version than the pack</span>` : ''}</span><span class="faint narrow nw">${right}</span></div>`;
  };
  const dl = n('add') + n('up') + n('other') + rows.filter(r => r.kind === 'conflict' && o.keep[r.id] === 'pack').length;
  return dlgWrap(`<div class="dlg-h"><h2>Import ${esc(P.name)} ${P.v}</h2><p class="muted">Modpack by ${esc(P.by)}, from ${esc(P.file)}</p></div><div class="dlg-b">
    <div class="sum"><div><b>${rows.length}</b>mods in the pack</div><div><b>${n('same')}</b>you have</div><div><b>${n('add')}</b>new, ${size(addKb)}</div>${o.mode === 'new' ? `<div><b>${n('other')}</b>in another version</div>` : `<div><b>${n('up')}</b>get an update</div><div><b>${n('conflict')}</b>version ${n('conflict') === 1 ? 'conflict' : 'conflicts'}</div>`}</div>
    <div class="diff">${rows.slice().sort((a, b) => order[a.kind] - order[b.kind]).map(line).join('')}</div>
    ${local.map(([, name, kb]) => `<div class="note warn" style="margin:0;align-items:flex-start">${I('alert')}<span>This pack also contains a file that is not from the registry: ${esc(name)} (${size(kb)}). A mod file can run any code on your computer. Include it only if you trust ${esc(P.by)}.</span><span class="row" style="margin-left:auto;gap:7px;white-space:nowrap">${tog(o.local, 'oset', 'data-k="local" data-v="toggle"', 'Include the file')}Include</span></div>`).join('')}
    ${VAL.showProfiles ? `<button class="opt ${o.mode === 'new' ? 'cur' : ''}" data-act="oset" data-k="mode" data-v="new"><span class="radio"></span><span><b>Make a new profile "${esc(P.name)}"</b>It gets the pack's mods in the pack's versions. ${esc(p.name)} stays as it is.</span></button>` : ''}
    <button class="opt ${o.mode === 'add' ? 'cur' : ''}" data-act="oset" data-k="mode" data-v="add"><span class="radio"></span><span><b>Add the mods to ${esc(p.name)}</b>The pack's mods are turned on in ${esc(p.name)}. Your other mods stay as they are.</span></button>
    </div><div class="dlg-f"><button class="btn ghost" data-act="closeoverlay">Cancel</button><button class="btn strong" data-act="doimport">Import, ${plural(dl, 'download', 'downloads')}</button></div>`, 'wide');
}
function vPackUpdate() {
  const p = profile(S.overlay.pid), it = PACK_UPDATE.items, n = k => it.filter(i => i[0] === k).length;
  return dlgWrap(`<div class="dlg-h"><h2>${esc(p.name)} ${p.pack.v} to ${p.pack.next}</h2><p class="muted">Modpack by ${esc(p.pack.by)}</p></div><div class="dlg-b">
    <div class="sum"><div><b>${n('add')}</b>added</div><div><b>${n('rem')}</b>removed</div><div><b>${n('chg')}</b>updated</div><div><b>${n('same')}</b>not changed</div></div>
    <div class="diff">${it.map(diffRow).join('')}</div><p>Only this profile changes. Mods that you added to it yourself stay on.</p>
    </div><div class="dlg-f"><button class="btn ghost left" data-act="stopfollow">Stop following this pack</button><button class="btn ghost" data-act="closeoverlay">Not now</button><button class="btn strong" data-act="dopackupdate">Update the profile</button></div>`, 'wide');
}
function vNewProfile() {
  const o = S.overlay, op = (v, t, d) => `<button class="opt ${o.from === v ? 'cur' : ''}" data-act="oset" data-k="from" data-v="${v}"><span class="radio"></span><span><b>${t}</b>${d}</span></button>`;
  return dlgWrap(`<div class="dlg-h"><h2>New profile</h2></div><div class="dlg-b">
    ${o.from === 'pack' ? '' : `<label class="lbl">Name<div class="field"><input id="np-name" data-in="overlay.name" data-autofocus placeholder="For example: Squad night" value="${esc(o.name)}"></div></label>`}
    ${op('off', 'Start with all mods off', 'Your installed mods are in the list. You turn on the ones you want.')}${op('copy', `Copy ${esc(profile().name)}`, 'The same mods are on. After that the two profiles change separately.')}${op('pack', 'Start from a modpack file', 'NNOMM shows what the pack contains before it installs anything.')}
    ${o.from === 'pack' ? '<p>The profile gets the name of the pack. You can rename it later.</p>' : `<div class="row">${tog(o.own, 'oset', 'data-k="own" data-v="toggle"', 'Own mod settings')}<span>This profile keeps its own mod settings (the values you change with F1 in the game)</span></div>`}
    </div><div class="dlg-f"><button class="btn ghost" data-act="closeoverlay">Cancel</button><button class="btn strong" data-act="docreate"${o.from !== 'pack' && !o.name.trim() ? ' disabled' : ''}>${o.from === 'pack' ? 'Choose the modpack file' : 'Create profile'}</button></div>`);
}
function vShare() {
  const o = S.overlay, p = profile(o.pid), on = installedIds().filter(id => isOn(id, o.pid)), local = on.filter(id => INST[id].source === 'local');
  return dlgWrap(`<div class="dlg-h"><h2>Share ${esc(p.name)} as a modpack</h2></div><div class="dlg-b">
    <p>The file lists the ${on.length} mods that are on in this profile, with their versions. Other players import it and get the same set.</p>
    <div class="row"><label class="lbl grow">Pack name<div class="field"><input id="sh-name" data-in="overlay.name" value="${esc(o.name)}"></div></label><label class="lbl" style="width:110px">Version<div class="field"><input id="sh-v" data-in="overlay.v" value="${esc(o.v)}"></div></label></div>
    ${local.length ? `<div class="note" style="margin:0">${I('file')}<span>${local.map(id => esc(MOD[id].name)).join(' and ')} ${local.length === 1 ? 'is a local file' : 'are local files'}. With this on, NNOMM puts ${local.length === 1 ? 'it' : 'them'} inside the pack file (${size(local.reduce((a, id) => a + MOD[id].size, 0))}). Players get a warning before they import it.</span><span class="row" style="margin-left:auto;gap:7px;white-space:nowrap">${tog(o.local, 'oset', 'data-k="local" data-v="toggle"', 'Include local files')}Include</span></div>` : ''}
    ${on.some(hasProblem) ? `<div class="note warn" style="margin:0">${I('alert')}<span>${plural(on.filter(hasProblem).length, 'mod has', 'mods have')} a problem in this profile. Players who import the pack get the same problem.</span><button class="btn sm" data-act="shareprob">Show</button></div>` : ''}
    <div class="row">${tog(o.cfg, 'oset', 'data-k="cfg" data-v="toggle"', 'Include mod settings')}<span>Include my mod settings, so the pack arrives configured</span></div>
    <label class="lbl">Address for updates (optional)<div class="field"><input id="sh-url" data-in="overlay.url" placeholder="https://..." value="${esc(o.url || '')}"></div><span class="faint" style="font-size:12px">If you put the pack file online, enter its address. Players then get a notice when you save a newer version there. Without an address, players import each new file by hand.</span></label>
    </div><div class="dlg-f"><button class="btn ghost left" data-act="copied">Copy the mod list as text</button><button class="btn ghost" data-act="closeoverlay">Cancel</button><button class="btn strong" data-act="doshare"${o.name.trim() && o.v.trim() ? '' : ' disabled'}>Save the modpack file</button></div>`);
}
function vRename() {
  const o = S.overlay;
  return dlgWrap(`<div class="dlg-h"><h2>Rename profile</h2></div><div class="dlg-b"><div class="field"><input id="rn-name" data-in="overlay.name" data-autofocus value="${esc(o.name)}"></div></div>
    <div class="dlg-f"><button class="btn ghost" data-act="closeoverlay">Cancel</button><button class="btn strong" data-act="dorename"${o.name.trim() ? '' : ' disabled'}>Rename</button></div>`);
}
function vConfirm() {
  const o = S.overlay;
  return dlgWrap(`<div class="dlg-h"><h2>${esc(o.title)}</h2></div><div class="dlg-b"><p>${esc(o.text)}</p></div><div class="dlg-f"><button class="btn ghost" data-act="closeoverlay">Cancel</button><button class="btn danger" data-act="doconfirm">${esc(o.yes)}</button></div>`);
}
function vTrouble() {
  const t = S.trouble || { phase: 'intro' }, on = installedIds().filter(id => isOn(id));
  let b = '', f = '';
  if (t.phase === 'intro') {
    const rounds = Math.max(1, Math.ceil(Math.log2(Math.max(2, on.length))));
    b = `<p>Use this when the game crashes or behaves wrongly and you do not know which mod is the cause.</p><p>NNOMM turns off half of your ${on.length} mods. You start the game and say if the problem is still there. After about ${rounds} rounds one mod is left.</p><p>A mod stays on while another mod in the test needs it. At the end, or when you stop, NNOMM puts ${esc(profile().name)} back exactly as it is now.</p><p class="faint">If the problem needs two mods together, this test cannot find it.</p>`;
    f = `<button class="btn ghost" data-act="closeoverlay">Cancel</button><button class="btn strong" data-act="tstart">Start</button>`;
  } else if (t.phase === 'base') {
    b = `<p>First check: all ${t.saved.length} mods are off now. Start the game and do what causes the problem.</p><p>If the problem is still there, a mod is not the cause, and the test stops.</p>`;
    f = `<button class="btn ghost left" data-act="tstop">Stop and turn my mods back on</button><button class="btn" data-act="play">${I('play')}Play</button><button class="btn" data-act="tanswer" data-v="gone">The problem is gone</button><button class="btn strong" data-act="tanswer" data-v="still">Still there</button>`;
  } else if (t.phase === 'nomod') {
    b = `<p>The problem is there with all mods off, so a mod is not the cause. Your mods are on again, as before.</p><p>Next steps: "Verify integrity of game files" in Steam, or Repair for the mod loader in Settings, Game.</p>`;
    f = `<button class="btn strong" data-act="closeoverlay">Close</button>`;
  } else if (t.phase === 'test') {
    b = `<div class="sum"><div><b>${t.round} of ${t.rounds}</b>round</div><div><b>${t.on.length}</b>mods on</div><div><b>${t.off.length}</b>mods off</div><div><b>${t.suspects.length}</b>still possible</div></div>
      <p>Start the game and do what causes the problem. Then come back and answer.</p>
      <div class="diff" style="max-height:150px">${t.on.map(id => `<div class="d add"><span class="sg">on</span><span class="nmx ell">${esc(MOD[id].name)}</span><span></span></div>`).join('')}${t.off.map(id => `<div class="d same"><span class="sg">off</span><span class="ell">${esc(MOD[id].name)}</span><span></span></div>`).join('')}</div>`;
    f = `<button class="btn ghost left" data-act="tstop">Stop and turn my mods back on</button><button class="btn" data-act="play">${I('play')}Play</button><button class="btn" data-act="tanswer" data-v="gone">The problem is gone</button><button class="btn strong" data-act="tanswer" data-v="still">Still there</button>`;
  } else {
    const x = MOD[t.found];
    b = `<div class="row" style="gap:12px">${icon(x, 'l')}<div><b style="color:var(--text);font-size:15px">${esc(x.name)}</b><div>${esc(x.author)}, version ${INST[x.id].v}, made for game ${x.game}</div></div></div>
      <p>In the test, the problem followed this mod: it was there with the mod on and gone with it off. Your other mods are on again.</p>`;
    f = `<button class="btn ghost left" data-act="openmod" data-id="${x.id}">Open the mod page</button><button class="btn" data-act="tend" data-v="keep">Keep it on</button><button class="btn danger" data-act="tend" data-v="remove">Remove it</button><button class="btn strong" data-act="tend" data-v="off">Leave it off</button>`;
  }
  return dlgWrap(`<div class="dlg-h"><h2>${t.phase === 'found' ? 'Found the mod' : t.phase === 'nomod' ? 'A mod is not the cause' : 'Find the mod that breaks the game'}</h2></div><div class="dlg-b">${b}</div><div class="dlg-f">${f}</div>`);
}
function vDrop() {
  return `<div class="drop" data-act="closeoverlay"><div class="in">${I('download')}<h2>Drop to install</h2><p class="muted">Mod files (.dll, .zip) go into your Library. If the file is a mod from the registry, NNOMM recognises it and can update it later. A modpack (.nommpack) opens a preview first.</p>
    <div class="row" style="margin-top:10px"><button class="btn" data-act="dropfile">Mock: a mod file was dropped</button><button class="btn" data-act="import">Mock: a modpack was dropped</button></div></div></div>`;
}
function vFirst() {
  return `<div class="first"><div class="in"><h1>NNOMM found your game</h1><p class="muted">Check these three things. You can change all of them later in Settings.</p>
    <div class="found">
      <div class="kv">${I('folder')}<div class="grow"><b>Nuclear Option ${GAME_VERSION}</b><div class="faint ell">D:\\SteamLibrary\\steamapps\\common\\Nuclear Option</div></div><span class="end"><button class="btn sm" data-act="noop">Choose another folder</button></span></div>
      <div class="kv">${I('library')}<div class="grow"><b>${Object.keys(INST).length} mods are already in the game folder</b><div class="faint">They go into your Library as they are: ${Object.keys(INST).filter(id => INST[id].on.default).length} on, ${Object.keys(INST).filter(id => !INST[id].on.default).length} off.</div></div></div>
      <div class="kv">${I('check')}<div class="grow"><b>The mod loader BepInEx 5.4.23.4 is installed</b><div class="faint">NNOMM does not change it.</div></div></div>
    </div>
    <p class="muted">NNOMM does not sign in to Steam. It changes nothing in the game folder until you install, uninstall or switch a mod.</p>
    <div class="row"><button class="btn strong lg" data-act="mock" data-v="normal" data-page="library">Open my Library</button><button class="btn ghost lg" data-act="mock" data-v="normal" data-page="browse">Look for mods first</button></div></div></div>`;
}
function palItems() {
  const q = S.pal.q.trim(), out = [];
  const add = (g, label, hint, fn, ic, idx, alt, altHint) => out.push({ g, label, hint, fn, ic, idx, alt, altHint });
  const open = id => () => { S.panel = { kind: 'mod', id, tab: 'about' }; if (S.page !== 'browse' && S.page !== 'library') S.page = isInst(id) ? 'library' : 'browse'; if (S.page === 'library' && !isInst(id)) S.page = 'browse'; };
  const go = (p, t, ic) => () => { S.page = p; S.panel = null; };
  const actions = [
    ['Start the game', 'Ctrl Enter', () => ACT.play(), 'play'], ['Look for mod updates', '', () => toast(`${updates().length} mods have an update`, 'Show', () => { S.page = 'library'; S.lib.filter = 'upd'; }), 'refresh'],
    ['Add a mod from a file', '', () => ACT.addfile(), 'plus'], ['Import a modpack', '', () => ACT.import(), 'box'], ['Share this profile as a modpack', '', () => ACT.share(), 'share'],
    ['Find the mod that breaks the game', '', () => ACT.trouble(), 'split'], ['New profile', '', () => ACT.newprofile(), 'plus'], ['Open the game folder', '', () => ACT.noop(), 'folder'],
  ];
  if (!VAL.showProfiles) actions.splice(actions.findIndex(a => a[0] === 'New profile'), 1);
  const pages = [['browse', 'Browse', 'browse'], ['library', 'Library', 'library'], ['servers', 'Servers', 'server'], ['settings', 'Settings', 'gear']].filter(p => p[0] !== 'servers' || VAL.showServers);
  if (!q) {
    actions.slice(0, 6).forEach(a => add('Actions', a[0], a[1], a[2], a[3]));
    pages.forEach(p => add('Go to', p[1], '', go(p[0]), p[2]));
    if (VAL.showProfiles) PROFILES.filter(p => p.id !== S.profile).forEach(p => add('Profiles', 'Use ' + p.name, '', () => ACT.profile({ dataset: { id: p.id } })));
    return out;
  }
  const rank = (list, text) => list.map(o => ({ o, f: fuzzy(q, text(o)) })).filter(r => r.f).sort((a, b) => b.f.score - a.f.score);
  rank(installedIds().map(id => MOD[id]), o => o.name).slice(0, 4).forEach(({ o, f }) => add('Your mods', o.name, isOn(o.id) ? 'On' : 'Off', open(o.id), null, f.idx, () => { INST[o.id].on[S.profile] = !isOn(o.id); toast(`${o.name} is ${isOn(o.id) ? 'on' : 'off'}${multi() ? ' in ' + profile().name : ''}`); }, isOn(o.id) ? 'turn it off' : 'turn it on'));
  rank(MODS.filter(o => !isInst(o.id) && !o.local), o => o.name).slice(0, 4).forEach(({ o, f }) => add('Mods in the registry', o.name, 'Not installed', open(o.id), null, f.idx, () => { install(o.id); }, 'install it'));
  if (VAL.showProfiles) rank(PROFILES.filter(p => p.id !== S.profile), o => o.name).forEach(({ o, f }) => add('Profiles', o.name, 'Use this profile', () => ACT.profile({ dataset: { id: o.id } }), null, f.idx));
  rank(actions, o => o[0]).slice(0, 4).forEach(({ o, f }) => add('Actions', o[0], o[1], o[2], o[3], f.idx));
  rank(pages, o => o[1]).forEach(({ o, f }) => add('Go to', o[1], '', go(o[0]), o[2], f.idx));
  if (VAL.showServers) rank(SERVERS, o => o.name).slice(0, 3).forEach(({ o, f }) => add('Servers', o.name, `${o.p} / ${o.max}`, () => { S.page = 'servers'; S.panel = { kind: 'server', id: o.id }; }, 'server', f.idx));
  rank(SETTINGS.filter(s => s.label && !s.profileOnly), o => o.label).slice(0, 4).forEach(({ o, f }) => add('Settings', o.label, Object.fromEntries(CATS)[o.cat], () => { S.page = 'settings'; S.panel = null; S.set.scope = 'all'; S.set.q = ''; S.set.cat = o.cat; }, 'gear', f.idx));
  return out;
}
function vPalette() {
  const items = palItems(); S.pal.items = items; S.pal.idx = Math.max(0, Math.min(S.pal.idx, items.length - 1));
  let last = '', list = '';
  items.forEach((it, i) => {
    if (it.g !== last) { list += `<div class="g">${it.g}</div>`; last = it.g; }
    const m = MODS.find(x => x.name === it.label);
    list += `<button class="it ${i === S.pal.idx ? 'cur' : ''}" data-act="palrun" data-i="${i}">${m ? icon(m, 's') : it.ic ? I(it.ic) : '<span style="width:16px"></span>'}<span class="ell">${hl(it.label, it.idx)}</span><span class="hint">${it.hint}</span></button>`;
  });
  return `<div class="scrim" data-act="closeoverlay"><div class="pal" data-act="stop">
    <div class="in">${I('search')}<input id="palq" data-in="pal.q" data-autofocus placeholder="Mod, profile, server, setting or action" value="${esc(S.pal.q)}"></div>
    <div class="list">${list || '<div class="empty" style="margin:26px auto"><p>Nothing matches. Try a mod name or a word such as "update".</p></div>'}</div>
    <div class="foot"><span><span class="kbd">Up</span> <span class="kbd">Down</span> move</span><span><span class="kbd">Enter</span> ${items[S.pal.idx] && items[S.pal.idx].alt ? 'open it' : 'do it'}</span>${items[S.pal.idx] && items[S.pal.idx].alt ? `<span><span class="kbd">Ctrl Enter</span> ${items[S.pal.idx].altHint}</span>` : ''}<span><span class="kbd">Esc</span> close</span></div></div></div>`;
}
function vDlPop() {
  const rows = S.downloads.slice().reverse().map(d => { const x = MOD[d.id], done = d.pct >= 100; return `<div class="q"><div class="row">${icon(x, 's')}<span class="grow ell">${esc(x.name)}</span><span class="faint nw" ${done ? '' : `data-dlpct="${d.id}"`}>${done ? (d.kind === 'update' ? 'Updated' : 'Installed') : d.pct + '%'}</span>${done ? '' : `<button class="iconbtn sm" data-act="dlcancel" data-id="${d.id}" title="Cancel">${I('x')}</button>`}</div>${done ? '' : `<span class="bar"><i data-dlbar="${d.id}" style="width:${d.pct}%"></i></span>`}</div>`; }).join('');
  return `<div class="menuscrim" data-act="closemenu"></div><div class="dlpop" data-act="stop"><div class="row"><b>Downloads</b><span class="grow"></span><button class="linkbtn" data-act="dlclear">Clear finished</button></div>${rows || '<span class="muted">No downloads.</span>'}</div>`;
}
function vMenu() {
  const m = S.menu, pos = (m.up ? `bottom:${m.bottom}px;` : `top:${m.top}px;`) + (m.right ? `right:${m.rightPx}px;` : `left:${m.left}px;`);
  return `<div class="menuscrim" data-act="closemenu"></div><div class="menu" style="${pos}">${m.items.map((it, i) => it === '-' ? '<hr>' : it.head ? `<div class="mh">${esc(it.head)}</div>` : `<button class="${it.dz ? 'dz' : ''}" data-act="menuitem" data-i="${i}">${it.ic ? I(it.ic) : ''}${esc(it.label)}${it.hint ? `<span class="faint" style="margin-left:auto;padding-left:14px">${it.hint}</span>` : ''}</button>`).join('')}</div>`;
}
function vLayer() {
  let h = S.mock === 'first' ? vFirst() : '';
  const o = S.overlay;
  if (o) h += { palette: vPalette, import: vImport, packupdate: vPackUpdate, newprofile: vNewProfile, share: vShare, trouble: vTrouble, confirm: vConfirm, drop: vDrop, rename: vRename }[o.kind]();
  if (S.dlpop) h += vDlPop();
  if (S.menu) h += vMenu();
  if (S.toasts.length) h += `<div class="toasts">${S.toasts.map(t => `<div class="toast"><span>${esc(t.text)}</span>${t.action ? `<button data-act="toast" data-id="${t.id}">${esc(t.action)}</button>` : ''}</div>`).join('')}</div>`;
  return h;
}
function vMock() {
  const b = (act, v, t, cur) => `<button class="${cur ? 'cur' : ''}" data-act="${act}" data-v="${v}">${t}</button>`;
  return `<span class="lab">Mock controls, not part of the app</span>${b('theme', 'dark', 'Dark', S.theme === 'dark')}${b('theme', 'light', 'Light', S.theme === 'light')}<span class="gap"></span>
    ${b('mock', 'normal', 'Normal', S.mock === 'normal')}${b('mock', 'first', 'First start', S.mock === 'first')}${b('mock', 'loading', 'Mod list loading', S.mock === 'loading')}${b('mock', 'offline', 'No connection', S.mock === 'offline')}${b('mock', 'empty', 'No mods yet', S.mock === 'empty')}<span class="gap"></span>
    ${b('running', '', 'Game is running', S.running)}${b('mockdrop', '', 'Drag a file in')}${b('import', '', 'Import a modpack')}${b('palette', '', 'Command search')}`;
}

function openMenu(el, items) {
  const r = el.getBoundingClientRect(), n = $('#nn').getBoundingClientRect();
  S.menu = { items, left: Math.round(r.left - n.left), top: Math.round(r.bottom - n.top + 4), rightPx: Math.round(n.right - r.right), bottom: Math.round(n.bottom - r.top + 4), up: r.bottom - n.top > n.height - 40 - items.length * 30, right: r.left - n.left > n.width - 240 };
}
function profileItems(id) {
  const p = profile(id), it = [];
  if (id !== S.profile) it.push({ label: 'Use this profile', ic: 'check', fn: () => ACT.profile({ dataset: { id } }) });
  it.push({ label: 'Rename', fn: () => { S.overlay = { kind: 'rename', id, name: p.name }; } }, { label: 'Make a copy', ic: 'copy', fn: () => { const c = { id: 'p' + Date.now(), name: uniqueName(p.name + ' copy') }; PROFILES.push(c); installedIds().forEach(m => { INST[m].on[c.id] = isOn(m, id); if (INST[m].ver && INST[m].ver[id]) setVer(m, INST[m].ver[id], c.id); }); S.set.ov[c.id] = Object.assign({}, S.set.ov[id]); S.profile = c.id; S.page = 'library'; toast(`Made ${c.name}`); } },
    { label: 'Share as a modpack', ic: 'share', fn: () => ACT.share(id) }, { label: 'Settings for this profile', ic: 'gear', fn: () => { S.page = 'settings'; S.panel = null; S.set.scope = 'profile'; S.set.pid = id; S.set.q = ''; } });
  if (p.pack && p.pack.next) it.push({ label: `See what changes in ${p.pack.next}`, ic: 'box', fn: () => ACT.packupdate(id) });
  if (p.pack && p.pack.followed) it.push({ label: 'Stop following this pack', fn: () => ACT.stopfollow({ dataset: { id } }) });
  if (id !== 'default') it.push('-', { label: 'Delete this profile', ic: 'trash', dz: true, fn: () => { const i = PROFILES.indexOf(p); PROFILES.splice(i, 1); if (S.profile === id) S.profile = 'default'; toast(`Deleted the profile ${p.name}. Its mods stay installed.`, 'Undo', () => { PROFILES.splice(i, 0, p); }); } });
  return it;
}
function modItems(id) {
  const x = MOD[id], on = isOn(id), it = [{ label: 'Open details', hint: 'Enter', fn: () => { S.panel = { kind: 'mod', id, tab: 'about' }; } }, { label: on ? 'Turn off' : 'Turn on', hint: 'Space', fn: () => { INST[id].on[S.profile] = !on; } }];
  if (hasUpdate(id)) it.push({ label: `Update to ${x.v}`, ic: 'up', fn: () => download(id, 'update') }, { label: "See what's new", fn: () => { S.panel = { kind: 'mod', id, tab: 'new' }; } });
  it.push('-', { label: 'Open the mod folder', ic: 'folder', fn: ACT.noop }, { label: 'Open its settings file', ic: 'file', fn: ACT.noop }, '-', { label: multi() ? 'Uninstall (all profiles)' : 'Uninstall', ic: 'trash', hint: 'Delete', dz: true, fn: () => removeMods([id]) });
  return it;
}

const ACT = {
  stop() {}, noop() { toast('This does nothing in the mock.', null, null, 2500); }, copied() { toast('Copied', null, null, 2500); },
  nav(el) { S.page = el.dataset.page; S.panel = null; },
  gobrowse() { S.page = 'browse'; S.browse.sort = 'downloads'; },
  gosetting(el) { S.page = 'settings'; S.panel = null; S.set.scope = 'all'; S.set.q = ''; S.set.cat = el.dataset.cat; },
  profile(el) {
    if (S.trouble) { toast('Finish or stop the broken-mod test before you change the profile.', 'Show the test', () => { S.overlay = { kind: 'trouble' }; }); return; }
    S.profile = el.dataset.id; S.page = 'library'; S.lib.sel.clear(); S.panel = null;
  },
  repair(el) { const id = el.dataset.id; INST[id].problem = null; toast(`Downloaded ${MOD[id].name} again`); },
  okeep(el) { S.overlay.keep[el.dataset.id] = el.dataset.v; },
  shareprob() { S.overlay = null; S.page = 'library'; S.lib.filter = 'prob'; },
  newprofile() { S.overlay = { kind: 'newprofile', name: '', from: { 'With all mods off': 'off' }[VAL.newFrom] || 'copy', own: VAL.ownSettings }; },
  profmenu(el) { openMenu(el, [{ head: 'Play with' }, ...PROFILES.map(p => ({ label: p.name, ic: p.id === S.profile ? 'check' : 'minus', hint: onCount(p.id) + ' mods', fn: () => ACT.profile({ dataset: { id: p.id } }) })), '-', { label: 'New profile', ic: 'plus', fn: ACT.newprofile }]); },
  profmenu2(el) { openMenu(el, profileItems(S.profile)); },
  play() { if (S.running) return; S.running = true; toast(`Starting Nuclear Option with ${onCount()} mods`, null, null, 4000); },
  playmenu(el) { openMenu(el, [{ label: 'Play without mods', ic: 'play', fn: () => { S.running = true; toast('Starting Nuclear Option without mods. Your profile stays as it is.', null, null, 4000); } }, '-', { label: 'Open the game folder', ic: 'folder', fn: ACT.noop }, { label: 'Open the mod log', ic: 'file', fn: ACT.noop }]); },
  openmod(el) { S.panel = { kind: 'mod', id: el.dataset.id, tab: 'about' }; if (S.overlay && S.overlay.kind === 'trouble') S.overlay = null; },
  opensrv(el) { if (!S.panel || S.panel.id !== el.dataset.id) { S.srv.mode = null; S.srv.pw = ''; } S.panel = { kind: 'server', id: el.dataset.id }; },
  closepanel() { S.panel = null; },
  tab(el) { S.panel.tab = el.dataset.v; },
  install(el) { install(el.dataset.id); },
  update(el) { download(el.dataset.id, 'update'); },
  updateall() { updates().forEach(id => download(id, 'update')); },
  turnon(el) { INST[el.dataset.id].on[S.profile] = true; },
  toggle(el) { const id = el.dataset.id; INST[id].on[S.profile] = !isOn(id); S.lib.focus = id; },
  toggleIn(el) { const id = el.dataset.id, p = el.dataset.p; INST[id].on[p] = !isOn(id, p); },
  remove(el) { removeMods([el.dataset.id]); },
  tag(el) { const t = el.dataset.tag, b = S.browse; b.hideTags = b.hideTags.filter(x => x !== t); if (!b.tags.includes(t)) b.tags.push(t); S.page = 'browse'; },
  btag(el) { const t = el.dataset.tag, a = S.browse.tags, i = a.indexOf(t); i >= 0 ? a.splice(i, 1) : a.push(t); },
  // Hide a tag: its mods leave the list, and the tag moves to the "Hide mods with" group.
  bhide(el, e) {
    const t = el.dataset.tag, b = S.browse, shown = ALL_TAGS.map(r => r[0]).filter(x => !b.hideTags.includes(x)), i = shown.indexOf(t);
    b.tags = b.tags.filter(x => x !== t); if (!b.hideTags.includes(t)) b.hideTags.push(t); b.hidePick = false;
    if (e && e.detail === 0) { const nx = shown[i + 1] || shown[i - 1]; S._focus = el.closest('.hpick') || !nx ? 'fh-add' : 'ft-' + nx; }
  },
  bunhide(el, e) {
    const t = el.dataset.tag, b = S.browse, i = b.hideTags.indexOf(t); b.hideTags.splice(i, 1);
    if (e && e.detail === 0) S._focus = b.hideTags.length ? 'fh-' + b.hideTags[Math.min(i, b.hideTags.length - 1)] : 'ft-' + t;
  },
  bhidepick() { S.browse.hidePick = !S.browse.hidePick; S._focus = 'fh-add'; },
  bset(el) { const { k, v } = el.dataset, b = S.browse; b[k] = v === 'toggle' ? !b[k] : (k === 'have' || k === 'type') && b[k] === v ? 'all' : v; },
  bclear(el) { const k = el.dataset.k, b = S.browse; if (k === 'tags') b.tags = []; else if (k === 'hide') { b.hideTags = []; b.hidePick = false; } else if (k === 'fits') b.fits = false; else b[k] = 'all'; },
  clearfilters() { Object.assign(S.browse, { q: '', tags: [], hideTags: [], hidePick: false, type: 'all', have: 'all', fits: false }); },
  facets() { S.browse.facets = !S.browse.facets; },
  libfilter(el) { S.lib.filter = el.dataset.v; S.lib.sel.clear(); S.lib.focus = null; },
  libclear() { S.lib.filter = 'all'; S.lib.q = ''; S.lib.sel.clear(); S.lib.focus = null; },
  libsort(el) { const l = S.lib, k = el.dataset.v; if (l.sort === k) l.dir = -l.dir; else { l.sort = k; l.dir = 1; } },
  libsel(el, e) {
    const id = el.dataset.id, l = S.lib, ids = libList();
    if (e && e.shiftKey && l.anchor && ids.includes(l.anchor)) { const a = ids.indexOf(l.anchor), b = ids.indexOf(id); ids.slice(Math.min(a, b), Math.max(a, b) + 1).forEach(x => l.sel.add(x)); }
    else { l.sel.has(id) ? l.sel.delete(id) : l.sel.add(id); l.anchor = id; }
    l.focus = id;
  },
  libselall() { const l = S.lib, ids = libList(); ids.every(id => l.sel.has(id)) ? l.sel.clear() : ids.forEach(id => l.sel.add(id)); },
  librow(el, e) { const id = el.dataset.id; if (e && (e.shiftKey || e.ctrlKey || e.metaKey)) return ACT.libsel(el, e); S.lib.focus = id; S.lib.anchor = id; S.panel = { kind: 'mod', id, tab: 'about' }; },
  bulk(el) {
    const ids = [...S.lib.sel], v = el.dataset.v;
    if (v === 'on' || v === 'off') setOn(ids, v === 'on');
    if (v === 'update') ids.filter(hasUpdate).forEach(id => download(id, 'update'));
    if (v === 'remove') removeMods(ids);
    if (v === 'clear') S.lib.sel.clear();
  },
  rowmenu(el) { openMenu(el, modItems(el.dataset.id)); },
  problem(el) {
    const id = el.dataset.id, x = MOD[id], pr = problems(id).find(p => p.key === el.dataset.key), it = [{ head: pr.text }];
    if (pr.key === 'dep') it.push(isInst(pr.dep) ? { label: `Turn on ${MOD[pr.dep].name}`, fn: () => { INST[pr.dep].on[S.profile] = true; } } : { label: `Install ${MOD[pr.dep].name}`, ic: 'download', fn: () => install(pr.dep) }, { label: `Turn off ${x.name}`, fn: () => { INST[id].on[S.profile] = false; } });
    if (pr.key === 'conflict') it.push({ label: `Turn off ${MOD[pr.other].name}`, fn: () => { INST[pr.other].on[S.profile] = false; } }, { label: `Turn off ${x.name}`, fn: () => { INST[id].on[S.profile] = false; } }, { label: 'See why they conflict', fn: () => { S.panel = { kind: 'mod', id, tab: 'needs' }; } });
    if (pr.key === 'missing') it.push({ label: 'Download it again', ic: 'download', fn: () => { INST[id].problem = null; toast(`Repaired ${x.name}`); } }, { label: 'Remove it from the list', ic: 'trash', dz: true, fn: () => removeMods([id]) });
    openMenu(el, it);
  },
  addfile() { toast('A file window opens here. You can also drop a file on NNOMM.', 'Show the drop', () => { S.overlay = { kind: 'drop' }; }); },
  libmore(el) { openMenu(el, [{ label: 'Import a modpack', ic: 'box', fn: ACT.import }, { label: 'Share this profile as a modpack', ic: 'share', fn: ACT.share }, '-', { label: 'Look for updates', ic: 'refresh', fn: () => toast(`${updates().length} mods have an update`) }, { label: 'Find the mod that breaks the game', ic: 'split', fn: ACT.trouble }, '-', { label: 'Open the mods folder', ic: 'folder', fn: ACT.noop }]); },
  import() { S.overlay = { kind: 'import', mode: VAL.showProfiles ? 'new' : 'add', keep: {}, local: false }; },
  share(pid) { pid = typeof pid === 'string' ? pid : S.profile; S.overlay = { kind: 'share', pid, name: profile(pid).name, v: '1.0', cfg: false, local: false, url: '' }; },
  packupdate(pid) { S.overlay = { kind: 'packupdate', pid: typeof pid === 'string' ? pid : S.profile }; },
  keeptemp() { profile().temp = false; toast(`${profile().name} is now a normal profile`); },
  trouble() { S.overlay = { kind: 'trouble' }; },
  srvchip(el) { const c = S.srv.chips, v = el.dataset.v; c.has(v) ? c.delete(v) : c.add(v); },
  srvsort(el) { const s = S.srv, k = el.dataset.v; if (s.sort === k) s.dir = -s.dir; else { s.sort = k; s.dir = k === 'p' || k === 'mods' ? -1 : 1; } },
  srvclear() { S.srv.q = ''; S.srv.chips.clear(); },
  fav(el) { const s = SERVERS.find(q => q.id === el.dataset.id); s.fav = !s.fav; },
  join(el) {
    const s = SERVERS.find(q => q.id === el.dataset.id), { ms, mode, only, change } = joinPlan(s);
    if (S.trouble) { toast('Finish or stop the broken-mod test before you join a server.'); return; }
    if (S.running) { toast('Close Nuclear Option before you join a server from NNOMM.'); return; }
    if (S.pendingJoin) { toast(`NNOMM is still getting the mods for ${S.pendingJoin.name}.`); return; }
    if (s.lock && !S.srv.pw) { toast('Enter the server password first.', null, null, 3500); S._focus = 'srv-pw'; return; }
    s.recent = 1;
    if (!change) { S.running = true; toast(`Joining ${s.name}`, null, null, 4000); return; }
    const from = S.profile, last = () => S.downloads[S.downloads.length - 1];
    if (mode !== 'add') {
      // One profile per server and kind: a second join uses it again and brings it up to date.
      const temp = mode !== 'new';
      let t = PROFILES.find(q => q.server === s.id && !!q.temp === temp);
      if (!t) { t = { id: (temp ? 'tmp' : 'p') + Date.now(), name: shortName(s), temp, server: s.id }; PROFILES.push(t); }
      S.joinSession = { profile: t.id, from };
      if (!temp && !VAL.showProfiles) VAL.showProfiles = true;
      installedIds().forEach(id => { INST[id].on[t.id] = only ? false : isOn(id, from); if (INST[id].ver && INST[id].ver[from]) setVer(id, INST[id].ver[from], t.id); });
      S.set.ov[t.id] = Object.assign({}, S.set.ov[from]); S.profile = t.id;
    }
    ms.forEach(q => {
      if (q.state === 'missing') { install(q.id, true); if (q.want) last().ver = q.want; }
      else { INST[q.id].on[S.profile] = true; if (q.state === 'version') { download(q.id, 'update'); last().quiet = true; last().ver = q.want; } }
    });
    S.srv.mode = null;
    if (!S.downloads.some(d => d.pct < 100)) { S.running = true; toast(`Joining ${s.name} with the server's mods`, null, null, 4000); return; }
    S.pendingJoin = { name: s.name, profile: S.profile }; S.toasts = [];
    toast(`Getting the mods for ${s.name}. The game starts when they are ready.`);
  },
  joinmenu(el) {
    const cur = joinMode(), p = profile().name;
    openMenu(el, [{ head: 'How to join this server' }, ...JOIN_MODES.map(([k, t]) => ({ label: t.replace('the current profile', p), ic: k === cur ? 'check' : 'none', fn: () => { S.srv.mode = k; } })),
      '-', { label: 'Use my choice for every server', ic: 'gear', fn: () => { VAL.joinMods = JOIN_MODES.find(m => m[0] === joinMode())[1]; S.srv.mode = null; toast('This is now the first choice for every server. You can change it in Settings, Servers.'); } }]);
  },
  setcat(el) { S.set.cat = el.dataset.v; S.set.q = ''; },
  setscope(el) { S.set.scope = el.dataset.v; if (el.dataset.pid) S.set.pid = el.dataset.pid; if (S.set.scope === 'profile' && !PROFILES.some(p => p.id === S.set.pid)) S.set.pid = S.profile; },
  setclear() { S.set.q = ''; },
  setval(el) { const k = el.dataset.key, o = S.set.ov[setPid()] || {}; setSetting(k, !(S.set.scope === 'profile' && k in o ? o[k] : S.set.scope === 'profile' && SETTINGS.find(s => s.key === k).profileOnly ? false : VAL[k])); },
  setact(el) {
    const s = SETTINGS.find(q => q.key === el.dataset.key);
    if (s.danger) S.overlay = { kind: 'confirm', title: s.label + '?', text: (s.help || '') + ' You cannot undo this.', yes: el.dataset.v };
    else ACT.noop();
  },
  ovset(el) { const id = setPid(); (S.set.ov[id] = S.set.ov[id] || {})[el.dataset.key] = VAL[el.dataset.key]; },
  ovclear() { const o = S.set.ov[setPid()] || {}; Object.keys(o).forEach(k => { if (!SETTINGS.find(s => s.key === k).profileOnly) delete o[k]; }); },
  ovreset(el) { delete S.set.ov[setPid()][el.dataset.key]; },
  closeoverlay() { if (S.trouble && S.trouble.phase === 'nomod') S.trouble = null; if (S.trouble && S.trouble.phase === 'found') { const id = S.trouble.found; S.trouble = null; toast(`${MOD[id].name} stays off`); } S.overlay = null; },
  oset(el) { const { k, v } = el.dataset; S.overlay[k] = v === 'toggle' ? !S.overlay[k] : v; },
  doimport() {
    const P = PACK_IMPORT, o = S.overlay, rows = importRows(o), last = () => S.downloads[S.downloads.length - 1];
    if (o.mode === 'new') { const p = { id: 'p' + Date.now(), name: uniqueName(P.name), pack: { by: P.by, v: P.v, mods: rows.map(r => r.id) } }; PROFILES.push(p); S.profile = p.id; }
    let n = 0;
    rows.forEach(r => {
      if (r.kind === 'add') { install(r.id, true); n++; return; }
      INST[r.id].on[S.profile] = true;
      if (r.kind === 'up' || r.kind === 'other' || (r.kind === 'conflict' && o.keep[r.id] === 'pack')) { download(r.id, 'update'); last().quiet = true; last().ver = r.pv; n++; }
    });
    S.overlay = null; S.page = 'library'; S.panel = null; S.toasts = []; S.lib.filter = 'all';
    toast(`Importing ${P.name}: ${plural(n, 'download', 'downloads')}`);
  },
  stopfollow(el) { const p = profile(el.dataset.id || (S.overlay && S.overlay.pid) || S.profile); Object.assign(p.pack, { next: null, mods: [], followed: false }); S.overlay = null; toast('Stopped following this pack. You can now update and change its mods yourself.'); },
  dopackupdate() {
    const p = profile(S.overlay.pid);
    PACK_UPDATE.items.forEach(([k, id, a, b]) => {
      if (k === 'add') { if (isInst(id)) INST[id].on[p.id] = true; else { install(id, true); S.downloads[S.downloads.length - 1].profile = p.id; } if (!p.pack.mods.includes(id)) p.pack.mods.push(id); }
      if (k === 'rem') { INST[id].on[p.id] = false; p.pack.mods = p.pack.mods.filter(q => q !== id); }
      if (k === 'chg') { download(id, 'update'); const d = S.downloads[S.downloads.length - 1]; d.quiet = true; d.ver = b; d.profile = p.id; }
    });
    p.pack.v = p.pack.next; p.pack.next = null; S.overlay = null; S.toasts = []; toast(`${p.name} is now ${p.pack.v}`);
  },
  docreate() {
    const o = S.overlay;
    if (o.from === 'pack') { ACT.import(); return; }
    if (!o.name.trim()) return;
    const from = S.profile, p = { id: 'p' + Date.now(), name: uniqueName(o.name) }; PROFILES.push(p);
    installedIds().forEach(id => { INST[id].on[p.id] = o.from === 'copy' ? isOn(id) : false; if (o.from === 'copy' && INST[id].ver && INST[id].ver[from]) setVer(id, INST[id].ver[from], p.id); });
    S.set.ov[p.id] = Object.assign(o.from === 'copy' ? Object.assign({}, S.set.ov[from]) : {}, { ownSettingsP: o.own });
    S.profile = p.id; S.page = 'library'; S.panel = null; S.overlay = null; S.lib.filter = 'all';
    toast(`Made the profile ${p.name}`);
  },
  doshare() { const o = S.overlay; if (!o.name.trim() || !o.v.trim()) return; S.overlay = null; toast(`Saved ${o.name.replace(/\s+/g, '-')}-${o.v}.nommpack`, 'Show the file', () => { }); },
  dorename() { const o = S.overlay; if (!o.name.trim()) return; profile(o.id).name = uniqueName(o.name, o.id); S.overlay = null; },
  doconfirm() { S.overlay = null; ACT.noop(); },
  dropfile() { S.overlay = null; S.page = 'library'; S.lib.filter = 'local'; toast('Added TestHud.dll to your Library and turned it on', 'Undo', () => { }); },
  mockdrop() { S.overlay = { kind: 'drop' }; },
  tstart() {
    const on = installedIds().filter(id => isOn(id));
    S.trouble = { saved: on.slice(), suspects: on.slice(), culprit: on.includes('cockpit-shake') ? 'cockpit-shake' : on[on.length - 1], round: 0, rounds: Math.max(1, Math.ceil(Math.log2(Math.max(2, on.length)))), phase: 'base', on: [], off: on.slice(), profile: S.profile };
    on.forEach(id => { INST[id].on[S.profile] = false; });
  },
  tanswer(el) {
    const t = S.trouble; S.running = false;
    if (t.phase === 'base') {
      if (el.dataset.v === 'still') { t.saved.forEach(id => { if (INST[id]) INST[id].on[S.profile] = true; }); t.phase = 'nomod'; }
      else { t.phase = 'test'; troubleRound(); }
      return;
    }
    t.suspects = el.dataset.v === 'still' ? t.suspects.filter(id => t.on.includes(id)) : t.suspects.filter(id => t.off.includes(id));
    if (t.suspects.length <= 1) { t.found = t.suspects[0] || t.culprit; t.phase = 'found'; t.saved.forEach(id => { if (INST[id]) INST[id].on[S.profile] = id !== t.found; }); }
    else troubleRound();
  },
  tstop() { const t = S.trouble; t.saved.forEach(id => { if (INST[id]) INST[id].on[S.profile] = true; }); S.trouble = null; S.overlay = null; toast('Your mods are on again, as before'); },
  tend(el) {
    const t = S.trouble, id = t.found, v = el.dataset.v; S.trouble = null; S.overlay = null;
    if (v === 'keep') INST[id].on[S.profile] = true;
    if (v === 'remove') removeMods([id]); else toast(v === 'off' ? `${MOD[id].name} stays off` : `${MOD[id].name} is on again`);
  },
  palette() { S.overlay = { kind: 'palette' }; S.pal.q = ''; S.pal.idx = 0; },
  palrun(el) { const it = S.pal.items[+el.dataset.i]; S.overlay = null; if (it) it.fn(); },
  menuitem(el, e, menu) { const it = menu && menu.items[+el.dataset.i]; if (it && it.fn) it.fn(); },
  closemenu() { },
  toast(el) { const i = S.toasts.findIndex(t => t.id === +el.dataset.id); if (i >= 0) { const t = S.toasts[i]; S.toasts.splice(i, 1); if (t.fn) t.fn(); } },
  dlpop() { S.dlpop = !S._dlpopWas; },
  dlclear() { S.downloads = S.downloads.filter(d => d.pct < 100); S.dlpop = true; },
  dlcancel(el) {
    S.downloads = S.downloads.filter(d => d.id !== el.dataset.id); S.dlpop = true;
    if (S.pendingJoin) { toast(`Join cancelled. ${S.pendingJoin.name} needs ${MOD[el.dataset.id].name}.`); S.pendingJoin = null; endSession(true); S.dlpop = false; }
  },
  theme(el) { S.theme = el.dataset.v; VAL.theme = S.theme === 'light' ? 'Light' : 'Dark'; },
  mock(el) { S.mock = el.dataset.v; if (el.dataset.page) S.page = el.dataset.page; if (S.mock === 'loading') setTimeout(() => { if (S.mock === 'loading') { S.mock = 'normal'; render(); } }, 2600); if (S.mock === 'loading' || S.mock === 'offline') { S.page = 'browse'; S.panel = null; } if (S.mock === 'empty') { S.page = 'library'; S.panel = null; } },
  running() {
    S.running = !S.running;
    if (S.running) return;
    const r = endSession(false);
    if (!r) return;
    if (r.removed) toast(`The game closed. Removed the temporary profile for ${r.p.name}. You are back in ${profile().name}.`, 'Keep it', () => { r.p.temp = false; PROFILES.push(r.p); });
    else toast(`The game closed. You are back in ${profile().name}.`);
  },
};
// A server join that made or used a profile ends here: on game exit, or when the join is cancelled.
// It removes that temporary profile (not whichever profile is open) and goes back to the profile that was in use before.
function endSession(cancelled) {
  const j = S.joinSession; S.joinSession = null;
  if (!j) return null;
  const p = PROFILES.find(q => q.id === j.profile);
  if (!p) return null;
  const back = PROFILES.some(q => q.id === j.from) ? j.from : 'default';
  const removed = !!p.temp && (cancelled || VAL.tempKeep === 'When the game closes');
  if (removed) PROFILES.splice(PROFILES.indexOf(p), 1);
  if (S.profile === j.profile) S.profile = back;
  return { p, removed };
}
function troubleRound() {
  const t = S.trouble; t.round++;
  const half = Math.ceil(t.suspects.length / 2), keep = new Set(t.suspects.slice(0, half));
  // A mod stays on while a mod in the tested half needs it.
  for (let grown = true; grown;) { grown = false; [...keep].forEach(id => MOD[id].deps.forEach(d => { if (t.saved.includes(d) && !keep.has(d)) { keep.add(d); grown = true; } })); }
  t.on = t.saved.filter(id => keep.has(id));
  t.off = t.saved.filter(id => !t.on.includes(id));
  // The mock decides the answer path only from what the user clicks; on/off states follow the round.
  t.saved.forEach(id => { if (INST[id]) INST[id].on[S.profile] = t.on.includes(id); });
}
function setSetting(key, val) {
  const def = SETTINGS.find(s => s.key === key) || {};
  const pid = setPid();
  if (S.set.scope === 'profile' && ((S.set.ov[pid] && key in S.set.ov[pid]) || def.profileOnly)) { (S.set.ov[pid] = S.set.ov[pid] || {})[key] = val; return; }
  if (S.set.scope === 'profile' && def.ov) return;
  if (key === 'joinMods') S.srv.mode = null;
  VAL[key] = val;
  if (key === 'showProfiles' && !val) { S.profile = 'default'; S.set.scope = 'all'; S.trouble = null; }
  if (key === 'theme') S.theme = val === 'Light' ? 'light' : 'dark';
  if (key === 'view') S.browse.view = val === 'List' ? 'list' : 'grid';
  if (key === 'showServers' && !val && S.page === 'servers') S.page = 'library';
}
function setPath(path, val) {
  if (path === 'setpid') { if (val) { S.set.scope = 'profile'; S.set.pid = val; } return; }
  if (path.startsWith('set:')) return setSetting(path.slice(4), val);
  if (path.startsWith('ver:')) {
    const id = path.slice(4); if (val === verOf(id)) return;
    download(id, 'update'); const d = S.downloads[S.downloads.length - 1]; d.quiet = true; d.ver = val;
    toast(`Changing ${MOD[id].name} to ${val}${multi() ? ' in ' + profile().name : ''}`); return;
  }
  const [a, b] = path.split('.'); S[a][b] = val;
  if (path === 'pal.q') S.pal.idx = 0;
}

function render() {
  const nn = $('#nn'), ae = document.activeElement, fid = ae && ae.id, ss = ae && ae.selectionStart;
  const scrolls = {}; document.querySelectorAll('[data-scroll]').forEach(e => scrolls[e.dataset.scroll] = e.scrollTop);
  nn.dataset.theme = S.theme; nn.dataset.density = VAL.density === 'Comfortable' ? 'comfortable' : 'compact';
  nn.style.fontSize = (13 * parseInt(VAL.textSize, 10) / 100) + 'px';
  $('#side').innerHTML = vSide(); $('#main').innerHTML = vMain(); $('#panel').innerHTML = vPanel(); $('#layer').innerHTML = vLayer(); $('#mockbar').innerHTML = vMock();
  document.querySelectorAll('[data-scroll]').forEach(e => { if (scrolls[e.dataset.scroll]) e.scrollTop = scrolls[e.dataset.scroll]; });
  const auto = $('#layer [data-autofocus]');
  const f = fid && document.getElementById(fid);
  if (auto && (!f || !f.closest('#layer'))) { auto.focus(); try { auto.setSelectionRange(auto.value.length, auto.value.length); } catch (_) { } }
  else if (f) { f.focus(); try { if (ss != null) f.setSelectionRange(ss, ss); } catch (_) { } }
  if (S._focus) { const e = document.getElementById(S._focus); if (e) e.focus(); S._focus = null; }
  const foc = $('.tr.foc'); if (foc && S._kbd) { foc.scrollIntoView({ block: 'nearest' }); S._kbd = false; }
  const cur = $('.pal .it.cur'); if (cur) cur.scrollIntoView({ block: 'nearest' });
}

document.addEventListener('click', e => {
  const el = e.target.closest('[data-act]');
  if (!el || !el.closest('.stage')) return;
  const fn = ACT[el.dataset.act]; if (!fn) return;
  const menu = S.menu; S.menu = null; S._dlpopWas = S.dlpop; S.dlpop = false;
  if (el.dataset.act === 'stop' && (menu || S._dlpopWas)) { S.menu = menu; S.dlpop = S._dlpopWas && !!el.closest('.dlpop'); if (el.closest('.dlpop')) S.menu = null; }
  fn(el, e, menu);
  render();
});
document.addEventListener('input', e => { const el = e.target.closest('[data-in]'); if (!el) return; setPath(el.dataset.in, el.value); render(); });
document.addEventListener('change', e => { const el = e.target.closest('[data-ch]'); if (!el || el.dataset.ch === 'noop') return; setPath(el.dataset.ch, el.value); render(); });
document.addEventListener('contextmenu', e => { const el = e.target.closest('[data-ctx="profile"]'); if (!el) return; e.preventDefault(); openMenu(el, profileItems(el.dataset.id)); render(); });
document.addEventListener('keydown', e => {
  const typing = /^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName), ctrl = e.ctrlKey || e.metaKey, k = e.key;
  let hit = true;
  if (k === 'Enter' && !ctrl && e.target.matches && e.target.matches('[tabindex][data-act]')) { e.preventDefault(); e.target.click(); return; }
  if (S.overlay && S.overlay.kind === 'palette' && ['ArrowDown', 'ArrowUp', 'Enter'].includes(k)) {
    const n = (S.pal.items || []).length;
    if (k === 'Enter') { const it = S.pal.items[S.pal.idx]; S.overlay = null; if (it) (ctrl && it.alt ? it.alt : it.fn)(); }
    else if (n) S.pal.idx = (S.pal.idx + (k === 'ArrowDown' ? 1 : n - 1)) % n;
  }
  else if (ctrl && k.toLowerCase() === 'k') S.overlay && S.overlay.kind === 'palette' ? S.overlay = null : ACT.palette();
  else if (k === 'Escape') { if (S.menu || S.dlpop) { S.menu = null; S.dlpop = false; } else if (S.overlay) S.overlay = null; else if (typing && e.target.value) { setPath(e.target.dataset.in, ''); } else if (S.panel) S.panel = null; else if (S.lib.sel.size) S.lib.sel.clear(); else hit = false; }
  else if (k === 'Enter' && !ctrl && !e.shiftKey && !e.altKey && S.overlay && ['import', 'newprofile', 'share', 'rename', 'packupdate'].includes(S.overlay.kind) && !(e.target.closest && e.target.closest('button'))) {
    // Enter confirms a dialog, also from a text field. Never in the danger zone.
    e.preventDefault(); const b = $('.dlg-f .btn.strong'); if (b && !b.disabled) b.click(); return;
  }
  else if (ctrl && k === 'Enter') { if (!S.overlay) ACT.play(); }
  else if (ctrl && ['1', '2', '3'].includes(k)) { const p = ['browse', 'library', 'servers'][+k - 1]; if (p !== 'servers' || VAL.showServers) { S.page = p; S.panel = null; } }
  else if (ctrl && k === ',') { S.page = 'settings'; S.panel = null; }
  else if (ctrl && k.toLowerCase() === 'z' && !typing && S.toasts.some(t => t.action === 'Undo')) { const t = S.toasts.filter(q => q.action === 'Undo').pop(); S.toasts.splice(S.toasts.indexOf(t), 1); t.fn(); }
  else if (k === '-' && !typing && e.target.matches && e.target.matches('.facets [data-act="btag"]')) ACT.bhide(e.target, { detail: 0 });
  else if (k === '/' && !typing && !S.overlay) { const inp = $('#main .field input'); if (inp) { e.preventDefault(); inp.focus(); } return; }
  else if (S.page === 'library' && !S.overlay && !typing && !S.menu) {
    const l = S.lib, ids = libList(), onCtl = e.target.closest && e.target.closest('button, a, select, [tabindex]');
    if (!ids.includes(l.focus)) l.focus = null;
    [...l.sel].forEach(id => { if (!ids.includes(id)) l.sel.delete(id); });
    const i = ids.indexOf(l.focus);
    if (onCtl && (k === ' ' || k === 'Enter')) return;
    if (k === 'ArrowDown' || k === 'ArrowUp') {
      const j = i < 0 ? 0 : Math.max(0, Math.min(ids.length - 1, i + (k === 'ArrowDown' ? 1 : -1)));
      if (e.shiftKey) { if (i >= 0) l.sel.add(ids[i]); l.sel.add(ids[j]); }
      l.focus = ids[j]; S._kbd = true; if (S.panel && S.panel.kind === 'mod') S.panel = { kind: 'mod', id: ids[j], tab: S.panel.tab };
    }
    else if (k === ' ' && (l.focus || l.sel.size)) { const t = l.sel.size ? [...l.sel] : [l.focus]; const on = !t.every(id => isOn(id)); t.length > 1 ? setOn(t, on) : INST[t[0]].on[S.profile] = on; }
    else if (k === 'Enter' && l.focus) S.panel = { kind: 'mod', id: l.focus, tab: 'about' };
    else if (k === 'Delete' && (l.focus || l.sel.size)) { const t = l.sel.size ? [...l.sel] : [l.focus]; const j = ids[Math.min(ids.length - 1, i + 1)]; removeMods(t); l.focus = t.includes(j) ? null : j; }
    else if (ctrl && k.toLowerCase() === 'a') ids.forEach(id => l.sel.add(id));
    else hit = false;
  }
  else hit = false;
  if (hit) { e.preventDefault(); render(); }
});
let dragN = 0;
window.addEventListener('dragenter', e => { e.preventDefault(); if (dragN++ === 0 && !S.overlay) { S.overlay = { kind: 'drop' }; render(); } });
window.addEventListener('dragover', e => e.preventDefault());
window.addEventListener('dragleave', () => { if (--dragN <= 0) { dragN = 0; if (S.overlay && S.overlay.kind === 'drop') { S.overlay = null; render(); } } });
window.addEventListener('drop', e => { e.preventDefault(); dragN = 0; const f = e.dataTransfer && e.dataTransfer.files[0]; if (f && /\.nommpack$/i.test(f.name)) ACT.import(); else ACT.dropfile(); render(); });
window.addEventListener('error', e => { const d = document.createElement('div'); d.className = 'mockerr'; d.textContent = 'Mock error: ' + e.message + ' (' + (e.filename || '').split('/').pop() + ':' + e.lineno + ')'; document.querySelector('.stage').prepend(d); });

(function init() {
  const h = new URLSearchParams(location.hash.slice(1));
  const g = k => h.get(k);
  if (g('theme')) ACT.theme({ dataset: { v: g('theme') } });
  if (g('mock')) S.mock = g('mock');
  if (g('prof')) S.profile = g('prof');
  if (g('page')) S.page = g('page');
  if (g('q')) S.browse.q = g('q');
  if (g('view')) S.browse.view = g('view');
  if (g('tags')) S.browse.tags = g('tags').split(',');
  if (g('hide')) S.browse.hideTags = g('hide').split(',');
  if (g('lf')) S.lib.filter = g('lf');
  if (g('sel')) g('sel').split(',').forEach(id => S.lib.sel.add(id));
  if (g('cat')) S.set.cat = g('cat');
  if (g('scope')) { S.set.scope = g('scope'); S.set.pid = g('pid') || S.profile; }
  if (g('join')) S.srv.mode = g('join');
  if (g('sq')) S.set.q = g('sq');
  if (g('panel')) { const [kind, id] = g('panel').split(':'); S.panel = { kind, id, tab: g('tab') || 'about' }; }
  if (g('overlay')) S.overlay = { kind: g('overlay'), mode: g('mode') || 'new', keep: {}, local: false, name: g('overlay') === 'share' ? 'Default' : '', from: 'off', own: false, v: '1.0', cfg: false };
  if (g('pq')) S.pal.q = g('pq');
  if (g('running')) S.running = true;
  if (g('dl')) g('dl').split(',').forEach(id => { download(id, isInst(id) ? 'update' : 'install'); });
  if (g('trouble')) { ACT.tstart(); S.overlay = { kind: 'trouble' }; }
  let top = false; try { top = window.self === window.top; } catch (_) { }
  if (top && (g('fill') || window.NN_FILL)) {
    document.documentElement.classList.add('standalone');
    const fit = () => { $('#nn').style.height = Math.max(520, window.innerHeight - $('#mockbar').offsetHeight - 44) + 'px'; };
    render(); fit(); window.addEventListener('resize', fit);
  } else render();
})();
