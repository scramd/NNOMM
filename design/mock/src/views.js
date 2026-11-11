// Screens. Every function returns HTML for one region.
const tog = (on, act, attrs = '', label = '') => `<button class="tog" role="switch" aria-checked="${!!on}" aria-label="${esc(label)}" data-act="${act}" ${attrs}></button>`;
const opts = (list, cur) => list.map(o => { const [v, t] = Array.isArray(o) ? o : [o, o]; return `<option value="${esc(v)}" ${v === cur ? 'selected' : ''}>${esc(t)}</option>`; }).join('');
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
const multi = () => VAL.showProfiles && PROFILES.length > 1;

function vSide() {
  const upd = updates().length, p = profile();
  const nav = (page, ic, label, extra = '') => `<button class="nav ${S.page === page ? 'cur' : ''}" data-act="nav" data-page="${page}" title="${label}">${I(ic)}<span class="lbl-t">${label}</span>${extra}</button>`;
  let h = nav('browse', 'browse', 'Browse') + nav('library', 'library', 'Library', upd ? `<span class="count" title="${plural(upd, 'update', 'updates')}">${upd}</span>` : '');
  if (VAL.showServers) h += nav('servers', 'server', 'Servers');
  if (VAL.showProfiles) {
    h += `<h3><span>Profiles</span><button class="iconbtn sm" data-act="newprofile" title="New profile">${I('plus')}</button></h3><div class="prof-list">`;
    PROFILES.forEach(q => {
      const tail = q.temp ? I('clock') : q.pack && q.pack.next ? '<span class="upd">update</span>' : onCount(q.id);
      h += `<button class="nav prof ${q.id === S.profile ? 'act' : ''}" data-act="profile" data-id="${q.id}" data-ctx="profile" title="${esc(q.name)}${q.id === S.profile ? ' (in use)' : ''}. Right-click for more."><span class="dot"></span><span class="ini">${esc(q.name.slice(0, 1).toUpperCase())}</span><span class="lbl-t ell">${esc(q.name)}</span><span class="tail">${tail}</span></button>`;
    });
    h += '</div>';
  }
  h += '<div class="spacer"></div>';
  const left = S.downloads.filter(d => d.pct < 100);
  if (S.downloads.length) {
    const avg = left.length ? Math.round(left.reduce((a, d) => a + d.pct, 0) / left.length) : 100;
    h += `<button class="dlrow" data-act="dlpop" title="Downloads"><span class="row">${I('download')}<span class="t">${left.length ? plural(left.length, 'download', 'downloads') : 'Downloads finished'}</span></span>${left.length ? `<span class="bar"><i data-dltotal style="width:${avg}%"></i></span>` : ''}</button>`;
  }
  h += `<div class="playbox">`;
  if (VAL.showProfiles) h += `<button class="who" data-act="profmenu" title="Change profile"><span class="ell">${esc(p.name)}</span>${I('chevd')}</button>`;
  h += `<div class="sub">${p.temp ? 'Temporary, for a server. ' : ''}${onCount()} of ${installedIds().length} mods on</div>`;
  h += S.running ? `<div class="running" title="Nuclear Option is running"><span>Game is running</span></div>`
    : `<div class="go"><button class="btn primary" data-act="play" title="Start Nuclear Option (Ctrl Enter)">${I('play')}<span class="t">Play</span></button><button class="btn primary" data-act="playmenu" title="More ways to start">${I('chevd')}</button></div>`;
  h += `</div>` + nav('settings', 'gear', 'Settings');
  return h;
}

function modAction(x, sz = '') {
  const d = dlOf(x.id), off = S.mock === 'offline';
  if (d) return `<span class="dlpill"><span class="bar"><i data-dlbar="${x.id}" style="width:${d.pct}%"></i></span><span data-dlpct="${x.id}">${d.pct}%</span></span>`;
  if (!isInst(x.id)) return `<button class="btn ${sz}" data-act="install" data-id="${x.id}" ${off ? 'disabled title="Needs a connection"' : ''}>Install</button>`;
  if (INST[x.id].problem === 'missing') return `<button class="btn line ${sz}" data-act="repair" data-id="${x.id}" title="The file is missing. Download it again.">Repair</button>`;
  if (hasUpdate(x.id)) return `<button class="btn upd ${sz}" data-act="update" data-id="${x.id}" title="${verOf(x.id)} to ${x.v}" ${off ? 'disabled' : ''}>Update</button>`;
  if (!isOn(x.id)) return `<button class="btn line ${sz}" data-act="turnon" data-id="${x.id}" title="Installed. Off in ${esc(profile().name)}.">Turn on</button>`;
  return `<span class="st-on" title="Installed and on in ${esc(profile().name)}">${I('check')}On</span>`;
}
const tagBtn = t => `<button class="tag" data-act="tag" data-tag="${esc(t)}" title="Show mods with this tag">${esc(t)}</button>`;
function card(x, mt = {}) {
  const cur = S.panel && S.panel.id === x.id ? 'cur' : '';
  return `<div class="card ${cur}" data-act="openmod" data-id="${x.id}" tabindex="0">
    <div class="top">${icon(x, 'm')}<div class="grow"><div class="name">${mt.nameIdx ? hl(x.name, mt.nameIdx) : esc(x.name)}</div><div class="by ell">${esc(x.author)}</div></div></div>
    <div class="desc">${esc(x.desc)}</div>${mt.why ? `<div class="why ell">${mt.why}</div>` : ''}
    <div class="tags">${x.tags.slice(0, 3).map(tagBtn).join('')}${x.type === 'addon' ? `<span class="tag" title="Content for another mod. Needs ${esc(x.deps.map(d => MOD[d].name).join(', '))}.">Add-on</span>` : ''}</div>
    <div class="foot"><span class="meta"><span title="Downloads">${I('download')}${num(x.dl)}</span><span title="Newest version">${ago(x.upd)}</span></span>${modAction(x, 'sm')}</div></div>`;
}
function mrow(x, mt = {}) {
  const cur = S.panel && S.panel.id === x.id ? 'cur' : '';
  return `<div class="mrow ${cur}" data-act="openmod" data-id="${x.id}" tabindex="0">${icon(x)}
    <div class="grow"><div class="name ell">${mt.nameIdx ? hl(x.name, mt.nameIdx) : esc(x.name)}</div><div class="faint ell" style="font-size:12px">${esc(x.author)}</div></div>
    <div class="muted ell">${mt.why || esc(x.desc)}</div>
    <div class="muted narrow nw" title="Downloads">${num(x.dl)}</div><div class="muted narrow nw c-upd">${ago(x.upd)}</div><div class="end">${modAction(x, 'sm')}</div></div>`;
}
const showMods = (res, view) => view === 'grid' ? `<div class="grid">${res.map(r => card(r.x, r.m)).join('')}</div>` : `<div class="rows">${res.map(r => mrow(r.x, r.m)).join('')}</div>`;

function vBrowse() {
  const b = S.browse, res = browseList(), filtered = browseFiltered(), searching = !!b.q.trim();
  const pool = MODS.filter(x => !x.local && (VAL.hideServer ? x.side !== 'server' : true)), total = pool.length;
  let h = `<div class="ph"><h1>Browse</h1><span class="sub">${total} mods</span><span class="grow"></span>
    <div class="field" style="flex:1 1 150px;max-width:250px">${I('search')}<input id="bq" data-in="browse.q" placeholder="Search mods" title="Looks in names, tags, authors and descriptions" value="${esc(b.q)}"></div>
    <select class="sel" data-ch="browse.sort" title="${searching ? 'A search shows the best match first' : 'Sort'}" ${searching ? 'disabled' : ''}>${opts(searching ? [['x', 'Best match']] : [['downloads', 'Most downloaded'], ['updated', 'Recently updated'], ['newest', 'Newest'], ['name', 'Name']], searching ? 'x' : b.sort)}</select>
    <div class="seg"><button class="${b.view === 'grid' ? 'cur' : ''}" data-act="bset" data-k="view" data-v="grid" title="Grid">${I('grid')}</button><button class="${b.view === 'list' ? 'cur' : ''}" data-act="bset" data-k="view" data-v="list" title="List">${I('list')}</button></div>
    <button class="iconbtn only-narrow ${b.facets ? 'cur' : ''}" data-act="facets" title="Filters">${I('filter')}</button></div>`;
  if (S.mock === 'offline') h += `<div class="note warn">${I('alert')}<span>No connection to the registry. This is the list from yesterday, 21:40. You can still use your installed mods and start the game.</span><button class="btn sm" data-act="mock" data-v="normal">${I('refresh')}Try again</button></div>`;
  const cntH = f => browseList('have').filter(r => f(r.x)).length, cntT = f => browseList('type').filter(r => f(r.x)).length, tc = tagCounts();
  const fo = (k, v, label, n) => `<button class="fopt ${b[k] === v ? 'cur' : ''}" data-act="bset" data-k="${k}" data-v="${v}"><span class="radio" style="margin:0;${b[k] === v ? 'border:4.5px solid var(--strong)' : ''}"></span>${label}<span class="n">${n}</span></button>`;
  const clr = k => `<button class="linkbtn" style="font-size:11.5px;font-weight:400" data-act="bclear" data-k="${k}" title="Show all again">Clear</button>`;
  const tags = b.allTags ? ALL_TAGS : ALL_TAGS.slice(0, 9);
  h += `<div class="br"><div class="facets ${b.facets ? 'open' : ''}" data-scroll="facets">
    <div><h3>Show${b.have !== 'all' ? clr('have') : ''}</h3>${fo('have', 'new', 'Not installed', cntH(x => !isInst(x.id)))}${fo('have', 'inst', 'Installed', cntH(x => isInst(x.id)))}${fo('have', 'upd', 'With an update', cntH(x => hasUpdate(x.id)))}</div>
    <div><h3>Kind${b.type !== 'all' ? clr('type') : ''}</h3>${fo('type', 'plugin', 'Mods', cntT(x => x.type === 'plugin'))}${fo('type', 'addon', 'Add-ons', cntT(x => x.type === 'addon'))}</div>
    <div><h3>Tags<span class="row" style="gap:7px">${b.tags.length ? clr('tags') : ''}${b.tags.length > 1 ? `<span class="seg" title="A mod must have any, or all, of the tags you select"><button class="${b.tagMode === 'any' ? 'cur' : ''}" data-act="bset" data-k="tagMode" data-v="any" style="height:20px;padding:0 6px;font-size:11.5px">Any</button><button class="${b.tagMode === 'all' ? 'cur' : ''}" data-act="bset" data-k="tagMode" data-v="all" style="height:20px;padding:0 6px;font-size:11.5px">All</button></span>` : ''}</span></h3>
      ${tags.map(([t]) => `<button class="fopt ${b.tags.includes(t) ? 'cur' : ''}" data-act="btag" data-tag="${esc(t)}"><span class="ck ${b.tags.includes(t) ? 'cur' : ''}">${I('check')}</span>${esc(t)}<span class="n">${tc[t]}</span></button>`).join('')}
      <button class="linkbtn" data-act="bset" data-k="allTags" data-v="toggle" style="font-size:12px;margin-top:4px">${b.allTags ? 'Fewer tags' : `All ${ALL_TAGS.length} tags`}</button></div>
    <div><h3>Game version${b.fits ? clr('fits') : ''}</h3><button class="fopt ${b.fits ? 'cur' : ''}" data-act="bset" data-k="fits" data-v="toggle"><span class="ck ${b.fits ? 'cur' : ''}">${I('check')}</span>Made for ${GAME_VERSION}</button></div>
  </div><div class="results" data-scroll="browse-results">`;
  if (S.mock === 'loading') return h + `<div class="sec"><h2>Reading the registry</h2><div class="grid">${'<div class="skel"></div>'.repeat(9)}</div></div></div></div>`;
  if (filtered) {
    const chips = [];
    if (b.have !== 'all') chips.push([{ new: 'Not installed', inst: 'Installed', upd: 'With an update' }[b.have], 'have', 'all']);
    if (b.type !== 'all') chips.push([b.type === 'plugin' ? 'Mods' : 'Add-ons', 'type', 'all']);
    b.tags.forEach(t => chips.push([t, 'tag', t]));
    if (b.fits) chips.push(['Made for ' + GAME_VERSION, 'fits', 'toggle']);
    h += `<div class="active-filters"><b style="color:var(--text)">${plural(res.length, 'mod', 'mods')}</b>${searching ? `<span>for "${esc(b.q.trim())}"</span>` : ''}
      ${chips.map(([t, k, v]) => `<button class="chip cur" data-act="${k === 'tag' ? 'btag' : 'bset'}" data-k="${k}" data-v="${v}" data-tag="${esc(v)}" title="Remove this filter">${esc(t)}${I('x')}</button>`).join('')}
      ${b.tags.length > 1 ? `<span class="faint">${b.tagMode === 'any' ? 'any of these tags' : 'all of these tags'}</span>` : ''}<button class="linkbtn" data-act="clearfilters">Clear all</button></div>`;
    h += res.length ? showMods(res, b.view) : `<div class="empty"><h2>No mod matches</h2><p>Try fewer letters or remove a filter. The search also looks in descriptions, tags and author names.</p><button class="btn" data-act="clearfilters">Clear search and filters</button></div>`;
  } else if (b.sort !== 'downloads') {
    h += `<div class="sec"><h2>All mods<small>${{ updated: 'last updated first', newest: 'newest first', name: 'by name' }[b.sort]}</small></h2>${showMods(res, b.view)}</div>`;
  } else {
    const fresh = pool.filter(x => x.upd <= 7).sort((a, c) => a.upd - c.upd);
    const neu = pool.filter(x => x.added <= 60).sort((a, c) => a.added - c.added);
    const forYou = pool.filter(x => !isInst(x.id) && x.deps.length && x.deps.every(isInst));
    const base = [...new Set(forYou.flatMap(x => x.deps))].map(d => MOD[d].name).join(' and ');
    const sec = (t, sm, l) => l.length ? `<div class="sec"><h2>${t}<small>${sm}</small></h2><div class="strip">${l.map(x => card(x)).join('')}</div></div>` : '';
    h += sec('Updated this week', plural(fresh.length, 'mod', 'mods'), fresh) + sec('New', 'first release in the last 2 months', neu) + sec('For mods you have', 'they need ' + base + ', which you have', forYou);
    h += `<div class="sec"><h2>All mods<small>${{ downloads: 'most downloaded first', updated: 'last updated first', newest: 'newest first', name: 'by name' }[b.sort]}</small></h2>${showMods(res, b.view)}</div>`;
  }
  return h + '</div></div>';
}

function vLibrary() {
  const c = libCounts(), ids = libList(), l = S.lib, p = profile(), ups = updates();
  const chip = (k, t, n) => `<button class="chip ${l.filter === k ? 'cur' : ''}" data-act="libfilter" data-v="${k}">${t}<span class="n">${n}</span></button>`;
  let h = `<div class="ph"><h1>Library</h1>${VAL.showProfiles ? `<button class="btn ghost" data-act="profmenu2" title="Actions for this profile">${esc(p.name)}${I('chevd')}</button>` : ''}<span class="grow"></span>
    ${ups.length ? `<button class="btn upd" data-act="updateall" title="${multi() ? 'Updates the versions that ' + esc(p.name) + ' uses' : ''}">${I('up')}Update all (${ups.length})</button>` : ''}
    <button class="btn" data-act="addfile">${I('plus')}Add from a file</button>
    <button class="iconbtn" data-act="libmore" title="More">${I('more')}</button></div>`;
  if (!installedIds().length) return h + `<div class="empty">${I('library')}<h2>No mods yet</h2><p>Install a mod from Browse, or drop a mod file or a modpack on this window.</p><div class="row"><button class="btn strong" data-act="gobrowse">Browse the most downloaded mods</button><button class="btn" data-act="import">Import a modpack</button></div></div>`;
  h += `<div class="tools">${chip('all', 'All', c.all)}${chip('on', 'On', c.on)}${chip('off', 'Off', c.off)}${chip('upd', 'Updates', c.upd)}${chip('local', 'Local files', c.local)}${chip('prob', 'Problems', c.prob)}<span class="grow"></span>
    <div class="field" style="width:200px">${I('search')}<input id="lq" data-in="lib.q" placeholder="Filter by name" value="${esc(l.q)}"></div></div>`;
  if (S.trouble && (S.trouble.phase === 'test' || S.trouble.phase === 'base')) h += `<div class="note warn">${I('split')}<span>You are looking for a broken mod. ${S.trouble.off.length} mods are off for the test. NNOMM turns them on again at the end.</span><button class="btn sm" data-act="trouble">Continue</button></div>`;
  if (S.running) h += `<div class="note">${I('info')}<span>Nuclear Option is running. Changes apply the next time you start the game.</span></div>`;
  if (p.pack && p.pack.next) h += `<div class="note">${I('box')}<span>${esc(p.name)} ${p.pack.next} by ${esc(p.pack.by)} is out. You have ${p.pack.v}.</span><button class="btn sm upd" data-act="packupdate">See what changes</button></div>`;
  if (p.temp) h += `<div class="note">${I('clock')}<span>Temporary profile for the server ${esc(p.name)}. NNOMM removes it ${VAL.tempKeep === 'After 7 days' ? 'after 7 days' : 'when the game closes'}.</span><button class="btn sm" data-act="keeptemp">Keep this profile</button></div>`;
  const sh = (k, t) => `<button class="${l.sort === k ? 'cur' : ''}" data-act="libsort" data-v="${k}">${t}${l.sort === k ? I(l.dir > 0 ? 'chevu' : 'chevd') : ''}</button>`;
  const allSel = ids.length && ids.every(id => l.sel.has(id));
  h += `<div class="scroll ${l.sel.size ? 'has-bulk' : ''}" data-scroll="lib"><div class="tbl" role="grid"><div class="tr head lib-cols">
    <button class="ck ${allSel ? 'cur' : ''}" data-act="libselall" title="Select all">${I('check')}</button>${sh('on', 'On')}<span></span>${sh('name', 'Name')}<span title="Sort: mods with an update first">${sh('ver', 'Version')}</span><span class="c-src">${sh('source', 'Source')}</span><span class="c-size r">${sh('size', 'Size')}</span><span title="Release date of the version you have">${sh('upd', 'Released')}</span><span></span></div>`;
  ids.forEach(id => {
    const x = MOD[id], on = isOn(id), sel = l.sel.has(id), d = dlOf(id), v = verOf(id);
    const badges = problems(id).map(pr => pr.kind ? `<button class="badge ${pr.kind}" data-act="problem" data-id="${id}" data-key="${pr.key}" title="${esc(pr.text)}. Click for ways to fix it.">${I('alert')}<span class="ell">${esc(pr.text)}</span></button>` : `<span class="badge" title="The author made this version for game ${x.game}. It may still work.">${esc(pr.text)}</span>`).join('');
    const ver = d ? `<span class="dlpill" style="height:auto"><span class="bar"><i data-dlbar="${id}" style="width:${d.pct}%"></i></span><span data-dlpct="${id}">${d.pct}%</span></span>`
      : hasUpdate(id) ? `<button class="verup" data-act="update" data-id="${id}" title="Update to ${x.v}${multi() ? ' in ' + esc(p.name) : ''}">${v}${I('chevr')}${x.v}</button>`
        : packManaged(id) ? `<span title="The modpack sets this version. It changes when the pack updates.">${esc(v)}</span>` : esc(v);
    h += `<div class="tr body lib-cols ${sel ? 'picked' : ''} ${l.focus === id ? 'foc' : ''} ${on ? '' : 'off'}" data-act="librow" data-id="${id}" role="row">
      <button class="ck ${sel ? 'cur' : ''}" data-act="libsel" data-id="${id}" aria-label="Select ${esc(x.name)}">${I('check')}</button>
      ${tog(on, 'toggle', `data-id="${id}"`, (on ? 'Turn off ' : 'Turn on ') + x.name)}${icon(x)}
      <div class="nm"><b>${esc(x.name)}</b>${badges}</div><div class="narrow nw">${ver}</div><div class="c-src muted nw">${sourceOf(id)}</div><div class="c-size r muted narrow nw">${size(x.size)}</div><div class="muted narrow nw">${ago(updOf(id))}</div>
      <button class="iconbtn sm" data-act="rowmenu" data-id="${id}" title="More">${I('more')}</button></div>`;
  });
  if (!ids.length) h += `<div class="empty"><h2>No mod in this view</h2><button class="btn" data-act="libclear">Show all mods</button></div>`;
  h += `</div><div class="footline"><span>Mod loader BepInEx 5.4.23.4</span><span>Game ${GAME_VERSION}</span><span>${size(installedIds().reduce((a, id) => a + MOD[id].size, 0))} of mods</span></div></div>`;
  if (l.sel.size) {
    const s = [...l.sel];
    h += `<div class="bulk"><b>${s.length} selected</b><button class="btn sm" data-act="bulk" data-v="on">Turn on</button><button class="btn sm" data-act="bulk" data-v="off">Turn off</button>${s.some(hasUpdate) ? `<button class="btn sm upd" data-act="bulk" data-v="update">Update</button>` : ''}<button class="btn sm danger" data-act="bulk" data-v="remove" title="${multi() ? 'Removes the mods from this computer, for all profiles' : 'Removes the mods from this computer'}">Uninstall</button><button class="iconbtn sm" data-act="bulk" data-v="clear" title="Clear the selection">${I('x')}</button></div>`;
  }
  return h;
}

const dur = m => m < 60 ? m + ' min' : Math.floor(m / 60) + ' h' + (m % 60 ? ' ' + (m % 60) + ' min' : '');
const pingLimit = k => parseInt(VAL[k], 10) || Infinity;
const pingStyle = ms => ms >= pingLimit('pingRed') ? 'color:var(--danger)' : ms >= pingLimit('pingYellow') ? 'color:var(--warn)' : '';
// The Mods cell: first line is what the server uses, second line is what that means for you.
function srvModsCell(s) {
  const st = srvState(s);
  const n = st.vanilla ? '<span class="muted">No mods</span>' : st.unknown ? 'Modded' : plural(st.n, 'mod', 'mods');
  if (st.other) return [n, `<span style="color:var(--warn)">other game version</span>`];
  if (st.vanilla) return [n, ''];
  if (st.unknown) return [n, 'list not published'];
  if (st.ready) return [`<span class="st-on" style="height:auto">${I('check')}${n}</span>`, st.n === 1 ? 'you have it on' : 'you have them on'];
  return [n, [st.miss && `${st.miss} to install`, st.ver && `${st.ver} in another version`, st.off && `${st.off} to turn on`].filter(Boolean).join(', ')];
}
function vServers() {
  const v = S.srv, list = srvList(), y = pingLimit('pingYellow');
  const chip = (k, t, tip) => `<button class="chip ${v.chips.has(k) ? 'cur' : ''}" data-act="srvchip" data-v="${k}" title="${tip}">${t}</button>`;
  const sh = (k, t) => `<button class="${v.sort === k ? 'cur' : ''}" data-act="srvsort" data-v="${k}">${t}${v.sort === k ? I(v.dir > 0 ? 'chevu' : 'chevd') : ''}</button>`;
  let h = `<div class="ph"><h1>Servers</h1><span class="sub">${SERVERS.length} dedicated servers, ${SERVERS.reduce((a, s) => a + s.p, 0)} players</span><span class="grow"></span>
    <div class="field" style="width:220px">${I('search')}<input id="sq" data-in="srv.q" placeholder="Server, mission or map" value="${esc(v.q)}"></div><button class="iconbtn" data-act="noop" title="Refresh now">${I('refresh')}</button></div>
    <div class="tools">${chip('fits', 'Ready to join', 'Your game version, and no mods or only mods that you have on')}<span class="chipgap"></span>${chip('vanilla', 'No mods', 'Servers that say they are not modded')}${chip('mods', 'Modded', 'Servers that say they are modded')}<span class="chipgap"></span>${chip('pve', 'PvE', 'Players against the AI')}${chip('pvp', 'PvP', 'Players against players')}${chip('mixed', 'Mixed', 'Both in one mission')}<span class="chipgap"></span>${chip('notfull', 'Not full', '')}${chip('notempty', 'Not empty', '')}${y === Infinity ? '' : chip('ping', `Ping under ${y}`, 'The limit is the yellow ping value in Settings, Servers')}</div>
    <div class="scroll" data-scroll="srv"><div class="tbl"><div class="tr head srv-cols"><span></span>${sh('name', 'Server')}<span class="c-mis">${sh('mission', 'Mission')}</span><span class="c-type">${sh('round', 'Mode and round')}</span>${sh('p', 'Players')}${sh('ping', 'Ping')}${sh('mods', 'Mods')}</div>`;
  const row = s => {
    const [m1, m2] = srvModsCell(s), full = s.p >= s.max;
    return `<div class="tr body srv-cols ${S.panel && S.panel.id === s.id ? 'picked' : ''}" data-act="opensrv" data-id="${s.id}" tabindex="0">
    <button class="star ${s.fav ? 'cur' : ''}" data-act="fav" data-id="${s.id}" title="${s.fav ? 'Remove from favourites' : 'Add to favourites'}">${I('star')}</button>
    <div class="two"><div class="nm"><b>${esc(s.name)}</b>${s.lock ? `<span class="faint" title="Needs a password">${I('lock')}</span>` : ''}</div><div class="l2 ell"><span class="d-desc">${esc(s.desc || '')}</span><span class="d-mis">${s.type}, ${esc(s.mission)} on ${esc(s.map)}</span></div></div>
    <div class="two c-mis"><div class="ell">${esc(s.mission)}</div><div class="l2 ell">${esc(s.map)}</div></div>
    <div class="two c-type"><div>${s.type}</div><div class="l2 nw">${s.round == null ? 'no round running' : 'round ' + dur(s.round)}</div></div>
    <div class="two"><div class="narrow nw ${s.p ? '' : 'faint'}">${s.p} / ${s.max}${full ? ' <span class="faint">full</span>' : ''}</div><div class="bar fill"><i style="width:${Math.round(s.p / s.max * 100)}%"></i></div></div>
    <div class="two"><div class="narrow nw" style="${pingStyle(s.ping)}">${s.ping} ms</div><div class="l2 ell" title="Steam relay location of the server. It is near the server, not its exact place.">${esc(s.loc || '')}</div></div>
    <div class="two"><div class="nw">${m1}</div><div class="l2 ell">${m2}</div></div></div>`;
  };
  const fav = list.filter(s => s.fav), rec = list.filter(s => !s.fav && s.recent), rest = list.filter(s => !s.fav && !s.recent);
  if (fav.length) h += `<div class="grp">Favourites</div>` + fav.map(row).join('');
  if (rec.length) h += `<div class="grp">Joined lately</div>` + rec.map(row).join('');
  if (rest.length) h += `<div class="grp">All servers</div>` + rest.map(row).join('');
  if (!list.length) h += `<div class="empty"><h2>No server matches</h2><button class="btn" data-act="srvclear">Clear search and filters</button></div>`;
  h += `</div><div class="footline"><span>Dedicated servers only. NNOMM does not sign in to Steam, so Steam shows you as playing only while the game runs.</span><button class="linkbtn" data-act="gosetting" data-cat="servers">Server settings</button></div></div>`;
  return h;
}

function srow(s, q) {
  const prof = S.set.scope === 'profile', o = S.set.ov[setPid()] || {}, own = s.key in o;
  const val = prof ? (own ? o[s.key] : VAL[s.key]) : VAL[s.key];
  const mark = t => { if (!q || !t) return esc(t || ''); const at = t.toLowerCase().indexOf(q); return at < 0 ? esc(t) : hl(t, Array.from({ length: q.length }, (_, i) => at + i)); };
  const acts = (s.actions || []).map(a => `<button class="btn ${s.danger ? 'danger' : ''}" data-act="setact" data-key="${s.key}" data-v="${esc(a)}">${esc(a)}</button>`).join('');
  let ctl = '';
  // In the view of one profile, a setting that still uses the general value cannot be edited until the profile gets its own value.
  const dis = prof && !own && !s.profileOnly ? 'disabled' : '';
  if (s.type === 'toggle') ctl = tog(val, 'setval', `data-key="${s.key}" ${dis}`, s.label);
  else if (s.type === 'select') ctl = `<select class="sel" data-ch="set:${s.key}" ${dis}>${opts(s.options, val)}</select>${acts}`;
  else if (s.type === 'text' || s.type === 'path') ctl = `<div class="field"><input id="set-${s.key}" data-in="set:${s.key}" value="${esc(val)}" placeholder="${esc(s.placeholder || '')}" ${dis}></div>${acts}`;
  else if (s.type === 'actions') ctl = acts;
  if (s.type === 'sources') return `<div class="srow" style="flex-wrap:wrap"><div class="txt"><b><span>${mark(s.label)}</span></b><p>${mark(s.help)}</p>
    <div class="srcs"><div class="src">${tog(true, 'noop', '', 'NOMNOM')}<div class="grow"><b style="font-weight:600">NOMNOM</b><div class="faint ell" style="font-size:12px">kopterbuzz.github.io/NOMNOM/manifest/manifest.json</div></div><span class="muted nw">${MODS.length - 2} mods, read 12 minutes ago</span><button class="btn sm" data-act="noop">Read now</button></div>
    <div class="row"><div class="field grow"><input id="set-newsrc" placeholder="Address of another registry"></div><button class="btn" data-act="noop">Add</button></div></div></div></div>`;
  if (s.type === 'keys') return `<div class="keys">${KEYS.map(([a, k]) => `<span>${a}</span><span><span class="kbd">${k}</span></span>`).join('')}</div>`;
  const optHit = q && !(s.label + ' ' + (s.help || '')).toLowerCase().includes(q) && (s.options || []).find(x => x.toLowerCase().includes(q));
  let extra = '', cls = '';
  if (prof && !s.profileOnly) { extra = own ? `<button class="btn ghost sm" data-act="ovreset" data-key="${s.key}">Use the general setting</button>` : `<button class="btn sm" data-act="ovset" data-key="${s.key}">Change for this profile</button>`; cls = own ? '' : 'inherit'; }
  const owners = !prof && s.ov && VAL.showProfiles ? PROFILES.filter(q => s.key in (S.set.ov[q.id] || {})) : [];
  if (owners.length) extra = `<button class="linkbtn" style="font-size:12px" data-act="setscope" data-v="profile" data-pid="${owners[0].id}" title="Show the settings of this profile">${esc(owners.map(q => q.name).join(', '))} ${owners.length === 1 ? 'has its' : 'have their'} own value</button>`;
  const dot = prof && own && !s.profileOnly ? '<span class="moddot" title="This profile has its own value"></span>' : '';
  return `<div class="srow ${cls} ${s.type === 'path' ? 'wide' : ''}"><div class="txt"><b>${dot}<span>${mark(s.label)}</span></b>${s.help ? `<p>${mark(s.help)}</p>` : ''}${optHit ? `<p>Option: ${mark(optHit)}</p>` : ''}</div><div class="ctl">${ctl}${extra}</div></div>`;
}
function vSettings() {
  if (S.set.scope === 'profile' && !PROFILES.some(x => x.id === S.set.pid && !x.temp)) S.set.scope = 'all';
  const st = S.set, q = st.q.trim().toLowerCase(), p = profile(setPid()), prof = st.scope === 'profile' && VAL.showProfiles;
  const ownN = id => Object.keys(st.ov[id] || {}).filter(k => !SETTINGS.find(s => s.key === k).profileOnly).length;
  let h = `<div class="ph"><h1>Settings</h1>${VAL.showProfiles ? `<div class="seg"><button class="${!prof ? 'cur' : ''}" data-act="setscope" data-v="all">All profiles</button></div><select class="sel scope ${prof ? 'cur' : ''}" data-ch="setpid" title="Settings that can have their own value in one profile">${prof ? '' : '<option value="" selected>One profile</option>'}${PROFILES.filter(q => !q.temp).map(q => `<option value="${q.id}" ${prof && q.id === p.id ? 'selected' : ''}>${esc(q.name)}${ownN(q.id) ? ' (has own values)' : ''}</option>`).join('')}</select>` : ''}<span class="grow"></span>
    <div class="field" style="width:230px">${I('search')}<input id="setq" data-in="set.q" placeholder="Search settings" value="${esc(st.q)}"></div></div><div class="set">`;
  const catName = Object.fromEntries(CATS);
  const group = rows => { let out = '', last = ''; rows.forEach(s => { if (s.cat !== last) { out += `${last === 'danger' ? '</div>' : ''}<h2 style="margin-top:${last ? 18 : 4}px">${catName[s.cat]}</h2>${s.cat === 'danger' ? '<div class="dzbox">' : ''}`; last = s.cat; } out += srow(s, q); }); return out + (last === 'danger' ? '</div>' : ''); };
  const hit = s => (s.label + ' ' + (s.help || '') + ' ' + (s.options || []).join(' ')).toLowerCase().includes(q);
  if (prof) {
    const rows = SETTINGS.filter(s => s.ov && (!q || hit(s))), n = ownN(p.id);
    return h + `<div class="cats"><div class="pcard"><b>${esc(p.name)}</b><span class="muted">${n ? (n === 1 ? '1 setting has its own value here.' : n + ' settings have their own value here.') : 'It uses the general settings.'}</span>${n ? `<button class="btn sm" data-act="ovclear">Use the general settings</button>` : ''}</div></div><div class="sbody prof" data-scroll="set-prof"><p class="lead muted" style="margin:4px 0 16px;max-width:64ch">These settings can have their own value in ${esc(p.name)}. A dot marks each one that has. All other settings are the same for every profile.</p>${group(rows)}</div></div>`;
    return h + `<div class="sbody" data-scroll="set-prof" style="padding-left:18px"><p class="lead muted" style="margin-bottom:6px;max-width:64ch">These settings can have their own value in ${esc(p.name)}. A dot marks each one that has. All other settings are the same for every profile.</p>${group(rows)}</div></div>`;
  }
  h += `<div class="cats">${CATS.map(([k, t]) => `<button class="nav ${!q && st.cat === k ? 'cur' : ''} ${k === 'danger' ? 'dz' : ''}" data-act="setcat" data-v="${k}">${t}</button>`).join('')}</div><div class="sbody" data-scroll="set-${q ? 'q' : st.cat}">`;
  if (q) {
    const rows = SETTINGS.filter(s => !s.profileOnly && s.label && hit(s));
    h += rows.length ? `<p class="muted" style="margin-bottom:2px">${plural(rows.length, 'setting', 'settings')} for "${esc(st.q.trim())}"</p>` + group(rows) : `<div class="empty"><h2>No setting matches</h2><button class="btn" data-act="setclear">Clear the search</button></div>`;
  } else {
    const rows = SETTINGS.filter(s => s.cat === st.cat && !s.profileOnly);
    const lead = { servers: 'NNOMM lists dedicated servers only and never signs in to Steam. Steam shows you as playing Nuclear Option only while the game runs.', danger: 'NNOMM asks again before each of these.', profiles: 'A profile is a list of mods that are on, with the version of each. You can ignore profiles and use only Default.', keys: 'Click a shortcut to change it.' }[st.cat];
    h += group(rows).replace('</h2>', '</h2>' + (lead ? `<p class="lead">${lead}</p>` : ''));
  }
  return h + '</div></div>';
}

function vMain() { return { browse: vBrowse, library: vLibrary, servers: vServers, settings: vSettings }[S.page](); }

function pMod() {
  const x = MOD[S.panel.id], st = INST[x.id], tab = x.local ? 'about' : (S.panel.tab || 'about'), inst = isInst(x.id), d = dlOf(x.id), v = inst ? verOf(x.id) : x.v, p = profile();
  const conf = [...new Set(x.conflicts.concat(MODS.filter(o => o.conflicts.includes(x.id)).map(o => o.id)))];
  const usedBy = MODS.filter(o => o.deps.includes(x.id) && isInst(o.id));
  const needN = x.deps.length + conf.length;
  const row = id => `<button class="row linkbtn" style="text-decoration:none;color:var(--text)" data-act="openmod" data-id="${id}">${icon(MOD[id], 's')}${esc(MOD[id].name)}</button>`;
  const main = d ? modAction(x) : !inst ? `<button class="btn strong lg" data-act="install" data-id="${x.id}" ${S.mock === 'offline' ? 'disabled' : ''}>Install<span style="font-weight:400;opacity:.75">${size(x.size)}</span></button>`
    : st.problem === 'missing' ? modAction(x) : hasUpdate(x.id) ? `<button class="btn upd lg" data-act="update" data-id="${x.id}">Update to ${x.v}</button>` : '';
  let h = `<div class="pn-head"><div class="t">${icon(x, 'l')}<div class="grow"><h2>${esc(x.name)}</h2><div class="muted">${x.local ? 'Local file ' : ''}${esc(x.author)}</div></div><button class="iconbtn" data-act="closepanel" title="Close (Esc)">${I('x')}</button></div>
    <div class="row" style="flex-wrap:wrap">${main}${inst ? `<span class="row" style="gap:7px">${tog(isOn(x.id), 'toggle', `data-id="${x.id}"`, 'On in ' + p.name)}<span>${isOn(x.id) ? 'On' : 'Off'}${multi() ? ' in ' + esc(p.name) : ''}</span></span>` : ''}<span class="grow"></span>${inst ? `<button class="iconbtn" data-act="rowmenu" data-id="${x.id}" title="More">${I('more')}</button>` : ''}</div>
    ${problems(x.id).filter(pr => pr.kind).map(pr => `<button class="badge ${pr.kind}" style="height:26px;align-self:flex-start" data-act="problem" data-id="${x.id}" data-key="${pr.key}" title="Click for ways to fix it">${I('alert')}${esc(pr.text)}${I('chevd')}</button>`).join('')}`;
  if (x.local) {
    return h + `<div class="pn-meta"><div><span>Added</span>${ago(x.added)}</div><div><span>Size</span>${size(x.size)}</div><div><span>Version in the file</span>${esc(v)}</div></div></div>
      <div class="pn-body" data-scroll="pn-${x.id}"><p>${esc(x.desc)}</p>
      <div><h3>On this computer</h3>${multi() ? PROFILES.map(q => `<div class="kv"><span>${esc(q.name)}</span><span class="end">${tog(isOn(x.id, q.id), 'toggleIn', `data-id="${x.id}" data-p="${q.id}"`, 'On in ' + q.name)}</span></div>`).join('') : ''}
        <div class="kv"><button class="linkbtn row" data-act="noop" style="gap:6px">${I('file')}Replace with a newer file</button></div>
        <div class="kv"><button class="linkbtn row" data-act="noop" style="gap:6px">${I('folder')}Open the mod folder</button><span class="end"><button class="linkbtn" data-act="noop">Open its settings file</button></span></div>
        <div class="kv"><button class="linkbtn row" data-act="remove" data-id="${x.id}" style="gap:6px;color:var(--danger)">${I('trash')}Uninstall</button></div></div></div>`;
  }
  h += `<div class="pn-meta"><div><span>Downloads</span>${num(x.dl)}</div><div><span>Newest version</span>${x.v}, ${ago(x.upd)}</div><div><span>Size</span>${size(x.size)}</div>
      <div><span>${inst ? (multi() ? 'Version in ' + esc(p.name) : 'Your version') : 'First release'}</span>${inst ? v : ago(x.added)}</div><div><span>Made for game</span><span style="display:inline;color:${x.game === GAME_VERSION ? 'inherit' : 'var(--warn)'}">${x.game}</span></div><div><span>Kind</span>${x.type === 'addon' ? 'Add-on' : 'Mod'}${x.side === 'server' ? ', server only' : ''}</div></div></div>
    <div class="pn-tabs">${[['about', 'About'], ['new', "What's new"], ['needs', 'Needs' + (needN ? ' ' + needN : '')]].map(([k, t]) => `<button class="${tab === k ? 'cur' : ''}" data-act="tab" data-v="${k}">${t}</button>`).join('')}</div><div class="pn-body" data-scroll="pn-${x.id}-${tab}">`;
  if (tab === 'about') {
    h += `<p>${esc(x.desc)}</p>`;
    if (x.tags.length) h += `<div class="row" style="flex-wrap:wrap;gap:5px">${x.tags.map(tagBtn).join('')}</div>`;
    if (x.pre && VAL.prerelease) h += `<div class="note" style="margin:0">${I('info')}<span>Test version ${x.pre} is out. It can be unstable.</span><button class="btn sm" data-act="noop">Install ${x.pre}</button></div>`;
    h += `<div><button class="linkbtn row" data-act="noop" style="gap:5px">${I('ext')}Mod page on GitHub</button></div>`;
    if (inst) {
      h += `<div><h3>On this computer</h3>`;
      if (multi()) h += PROFILES.map(q => `<div class="kv"><span>${esc(q.name)}</span><span class="end">${isOn(x.id, q.id) ? `<span class="narrow">${verOf(x.id, q.id)}</span>` : ''}${tog(isOn(x.id, q.id), 'toggleIn', `data-id="${x.id}" data-p="${q.id}"`, 'On in ' + q.name)}</span></div>`).join('');
      h += `<div class="kv"><span>${multi() ? 'Version in ' + esc(p.name) : 'Version'}</span><span class="end">${packManaged(x.id) ? `${v}, set by the modpack` : `<select class="sel" data-ch="ver:${x.id}" style="height:24px" title="Go back to an older version, or forward again">${opts(notesFor(x.id).map(n => [n[0], n[0] + (n[0] === x.v ? ' (newest)' : '')]).concat(notesFor(x.id).some(n => n[0] === v) ? [] : [[v, v]]), v)}</select>`}</span></div>
        <div class="kv"><button class="linkbtn row" data-act="noop" style="gap:6px">${I('folder')}Open the mod folder</button><span class="end"><button class="linkbtn" data-act="noop">Open its settings file</button></span></div>
        <div class="kv"><button class="linkbtn row" data-act="remove" data-id="${x.id}" style="gap:6px;color:var(--danger)">${I('trash')}Uninstall${multi() ? ' (all profiles)' : ''}</button></div></div>`;
    }
  } else if (tab === 'new') {
    h += notesFor(x.id).map(([nv, when, lines]) => `<div class="ver"><div class="h"><b>${nv}</b><span class="faint">${when}</span>${inst && v === nv ? `<span class="badge ok">${multi() ? 'in ' + esc(p.name) : 'installed'}</span>` : ''}</div>${lines.length ? `<ul>${lines.map(l => `<li>${esc(l)}</li>`).join('')}</ul>` : '<p class="faint">The author gave no notes for this version.</p>'}</div>`).join('') + `<p class="faint">The notes come from the mod's release page.</p>`;
  } else {
    const depRow = id => { const o = MOD[id]; const s = !isInst(id) ? ['Not installed', `<button class="btn sm" data-act="install" data-id="${id}">Install</button>`] : !isOn(id) ? ['Off', `<button class="btn sm line" data-act="turnon" data-id="${id}">Turn on</button>`] : ['On', `<span class="st-on" style="height:auto">${I('check')}</span>`]; return `<div class="kv">${row(id)}<span class="end">${dlOf(id) ? modAction(o) : s[0] + ' ' + s[1]}</span></div>`; };
    h += `<div><h3>Needs these mods</h3>${x.deps.length ? x.deps.map(depRow).join('') : '<p>Nothing listed.</p>'}</div>`;
    if (conf.length) h += `<div><h3>Does not work together with</h3>${conf.map(id => `<div class="kv">${row(id)}<span class="end">${isOn(id) ? 'On' : isInst(id) ? 'Off' : 'Not installed'}</span></div>`).join('')}<p class="faint" style="margin-top:6px">The authors marked these mods as not compatible. Use one of them at a time.</p></div>`;
    if (usedBy.length) h += `<div><h3>Your mods that need this one</h3>${usedBy.map(o => `<div class="kv">${row(o.id)}</div>`).join('')}</div>`;
  }
  return h + '</div>';
}
// What a join does, for the chosen way to join.
function joinPlan(s) {
  const ms = srvMods(s), st = srvState(s), mode = joinMode();
  const only = mode === 'temp' || mode === 'new';
  const dls = ms.filter(q => q.state === 'missing' || q.state === 'version');
  const need = ms.filter(q => q.state !== 'have');
  return { ms, st, mode, only, dls, need, change: st.n > 0 && (only || need.length > 0) };
}
const shortName = s => s.name.split('|')[0].trim();
function pServer() {
  const s = SERVERS.find(q => q.id === S.panel.id), { ms, st, mode, dls, need, change } = joinPlan(s), full = s.p >= s.max, p = esc(profile().name);
  const bytes = size(dls.reduce((a, q) => a + MOD[q.id].size, 0)), cnt = k => ms.filter(q => q.state === k).length;
  const sum = [cnt('missing') && `${cnt('missing')} to install`, cnt('version') && `${cnt('version')} to change version`, cnt('off') && `${cnt('off')} to turn on`].filter(Boolean).join(', ') + (dls.length ? `, ${bytes}` : '');
  const blocked = full ? 'Server is full' : S.running ? 'The game is running' : '';
  const label = blocked || (change && dls.length ? `Get ${plural(dls.length, 'mod', 'mods')} and join` : st.other ? 'Join anyway' : 'Join');
  const n = plural(st.n, 'mod', 'mods');
  const gone = VAL.tempKeep === 'After 7 days' ? 'NNOMM removes the temporary profile after 7 days.' : 'NNOMM removes the temporary profile when the game closes.';
  const modeName = (JOIN_MODES.find(m => m[0] === mode) || [])[1].replace('the current profile', profile().name);
  const explain = full ? 'This server is full. Try again later.'
    : S.running ? 'Close Nuclear Option before you join a server from NNOMM.'
      : st.unknown ? `This server is modded but does not publish its mod list, so NNOMM cannot prepare the mods. The game starts with ${p}.`
        : st.vanilla ? `This server uses no mods. The game starts with ${p}.`
          : !change ? `Ready: ${p} has ${st.n === 1 ? 'this server\'s mod' : `all ${st.n} mods of this server`} on, in the right ${st.n === 1 ? 'version' : 'versions'}. The game starts with ${p}.`
            : `<b style="color:var(--text);font-weight:600">${esc(modeName)}.</b> ` + (mode === 'tempplus' ? `The mods get the server's versions. ${p} stays as it is. ${gone}`
              : mode === 'temp' ? `Your other mods are off while you play there. ${p} stays as it is. ${gone}`
                : mode === 'new' ? `NNOMM makes the profile "${esc(shortName(s))}" with this server's ${n}. It stays in your list, and you can add other mods to it. After the game NNOMM goes back to ${p}.`
                  : `NNOMM turns on this server's mods in ${p}, in the server's versions. ${p} stays changed after the game.`);
  return `<div class="pn-head"><div class="t"><div class="grow"><h2>${esc(s.name)}</h2><div class="muted">${esc(s.mission)} on ${esc(s.map)}</div></div><button class="star ${s.fav ? 'cur' : ''}" style="width:28px;height:28px" data-act="fav" data-id="${s.id}" title="Favourite">${I('star')}</button><button class="iconbtn" data-act="closepanel" title="Close (Esc)">${I('x')}</button></div>
    ${st.other ? `<div class="note warn" style="margin:0">${I('alert')}<span>This server runs game ${s.ver} and you have ${GAME_VERSION}. The game will probably refuse the join.</span></div>` : ''}
    ${s.lock ? `<div class="field">${I('lock')}<input id="srv-pw" type="password" data-in="srv.pw" value="${esc(S.srv.pw || '')}" placeholder="Server password" autocomplete="off"></div>` : ''}
    <div class="row" style="flex-wrap:wrap"><div class="split"><button class="btn primary lg" data-act="join" data-id="${s.id}" ${blocked ? 'disabled' : ''}>${I('play')}${label}</button>${st.n ? `<button class="btn primary lg" data-act="joinmenu" data-id="${s.id}" title="Choose how to join" ${blocked ? 'disabled' : ''}>${I('chevd')}</button>` : ''}</div>${!blocked && change && sum ? `<span class="muted">${sum}</span>` : ''}</div>
    <p class="muted">${explain}</p>
    <div class="pn-meta"><div><span>Players</span>${s.p} / ${s.max}</div><div><span>Ping</span><span style="display:inline;${pingStyle(s.ping)}">${s.ping} ms</span>${s.loc ? `<span style="display:inline;color:inherit" title="Steam relay location of the server. It is near the server, not its exact place.">, near ${esc(s.loc)}</span>` : ''}</div><div><span>Mode</span>${s.type}</div>
      <div><span>Round</span>${s.round == null ? 'none running' : 'running ' + dur(s.round)}</div><div><span>Game version</span><span style="display:inline;color:${st.other ? 'var(--warn)' : 'inherit'}">${s.ver}</span></div><div><span>Password</span>${s.lock ? 'Yes' : 'No'}</div></div></div>
    <div class="pn-body" data-scroll="pn-${s.id}">${s.desc ? `<p>${esc(s.desc)}</p>` : ''}
    <div><h3>Mods on this server${ms.length ? ' (' + ms.length + ')' : ''}</h3>${st.unknown ? '<p>The server says it is modded, but it does not publish the list.</p>' : ms.length ? ms.map(q => { const o = MOD[q.id]; const t = q.state === 'have' ? `<span class="st-on" style="height:auto">${I('check')}You have it on</span>` : q.state === 'missing' ? `${I('download')}To install, ${size(o.size)}` : q.state === 'off' ? `Off in ${p}` : `${I('download')}Server uses ${q.want}, ${p} has ${verOf(q.id)}`; return `<div class="kv"><button class="row linkbtn" style="text-decoration:none;color:var(--text)" data-act="openmod" data-id="${q.id}">${icon(o, 's')}${esc(o.name)}</button><span class="end">${t}</span></div>`; }).join('') : '<p>None.</p>'}</div>
    <div class="kv"><span class="faint">Address</span><span class="end">203.0.113.${20 + parseInt(s.id.slice(1), 10)}:7777<button class="iconbtn sm" data-act="copied" title="Copy">${I('copy')}</button></span></div>
    <p class="faint">To join from here, NNOMM uses the small helper mod NOSMR. It is turned on for this start.</p></div>`;
}
const vPanel = () => !S.panel ? '' : S.panel.kind === 'mod' ? pMod() : pServer();
