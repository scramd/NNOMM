// State, helpers and mock logic.
const $ = (s, r = document) => r.querySelector(s);
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const ICONS = {
  search: '<circle cx="7" cy="7" r="4.5"/><path d="M10.5 10.5 14 14"/>',
  browse: '<circle cx="8" cy="8" r="6"/><path d="m10.6 5.4-1.5 3.7-3.7 1.5 1.5-3.7z"/>',
  library: '<path d="M8 2 2 5l6 3 6-3-6-3z"/><path d="m2 8 6 3 6-3"/><path d="m2 11 6 3 6-3"/>',
  server: '<rect x="2" y="2.5" width="12" height="4.5" rx="1"/><rect x="2" y="9" width="12" height="4.5" rx="1"/><path d="M4.6 4.75h.01M4.6 11.25h.01"/>',
  gear: '<circle cx="8" cy="8" r="2.2"/><path d="M8 1.6v2M8 12.4v2M1.6 8h2M12.4 8h2M3.5 3.5l1.4 1.4M11.1 11.1l1.4 1.4M3.5 12.5l1.4-1.4M11.1 4.9l1.4-1.4" stroke-width="2"/>',
  play: '<path d="M5 3.2v9.6l7.5-4.8z" fill="currentColor"/>',
  download: '<path d="M8 2.5v8M4.5 7.5 8 11l3.5-3.5M3 13.5h10"/>',
  check: '<path d="m3.5 8.5 3 3 6-7"/>',
  x: '<path d="m4 4 8 8M12 4l-8 8"/>',
  chevd: '<path d="m4 6 4 4 4-4"/>', chevu: '<path d="m4 10 4-4 4 4"/>', chevr: '<path d="m6 4 4 4-4 4"/>',
  plus: '<path d="M8 3v10M3 8h10"/>', minus: '<path d="M3 8h10"/>',
  more: '<circle cx="3.5" cy="8" r="1" fill="currentColor" stroke="none"/><circle cx="8" cy="8" r="1" fill="currentColor" stroke="none"/><circle cx="12.5" cy="8" r="1" fill="currentColor" stroke="none"/>',
  star: '<path d="m8 2 1.8 3.8 4.2.5-3.1 2.9.8 4.1L8 11.3l-3.7 2 .8-4.1L2 6.3l4.2-.5z"/>',
  lock: '<rect x="3.5" y="7" width="9" height="6.5" rx="1"/><path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2"/>',
  clock: '<circle cx="8" cy="8" r="6"/><path d="M8 4.5V8l2.5 1.5"/>',
  alert: '<path d="M8 2 1.5 13.5h13z"/><path d="M8 6.5v3.5M8 12h.01"/>',
  folder: '<path d="M2 4.5h4l1.5 1.5H14v7H2z"/>',
  share: '<path d="M8 10V2.5M5 5l3-3 3 3M3 9v4.5h10V9"/>',
  box: '<path d="M8 1.5 2 4.5v7l6 3 6-3v-7z"/><path d="m2 4.5 6 3 6-3M8 7.5v7"/>',
  grid: '<rect x="2.5" y="2.5" width="4.5" height="4.5" rx="1"/><rect x="9" y="2.5" width="4.5" height="4.5" rx="1"/><rect x="2.5" y="9" width="4.5" height="4.5" rx="1"/><rect x="9" y="9" width="4.5" height="4.5" rx="1"/>',
  list: '<path d="M2.5 4h11M2.5 8h11M2.5 12h11"/>',
  filter: '<path d="M2 4h12M4.5 8h7M6.5 12h3"/>',
  undo: '<path d="M3 6.5h7a3.5 3.5 0 0 1 0 7H6M3 6.5l3-3M3 6.5l3 3"/>',
  up: '<path d="M8 13V3M4 7l4-4 4 4"/>',
  ext: '<path d="M6.5 3.5H3V13h9.5V9.5M9 2.5h4.5V7M13.5 2.5l-6 6"/>',
  trash: '<path d="M2.5 4.5h11M6 4.5v-2h4v2M4 4.5l.7 9h6.6l.7-9"/>',
  refresh: '<path d="M13.5 8a5.5 5.5 0 1 1-1.6-3.9M13.5 2.5v3h-3"/>',
  split: '<path d="M8 2v12M2.5 5h3M2.5 8h3M2.5 11h3M10.5 5h3M10.5 8h3M10.5 11h3"/>',
  users: '<circle cx="6" cy="5.5" r="2.5"/><path d="M1.5 13.5c.5-2.5 2.2-4 4.5-4s4 1.5 4.5 4M10.5 3.2a2.5 2.5 0 0 1 0 4.6M12 9.8c1.4.6 2.2 1.9 2.5 3.7"/>',
  file: '<path d="M4 1.5h5l3 3v10H4z"/><path d="M9 1.5v3h3"/>',
  info: '<circle cx="8" cy="8" r="6"/><path d="M8 7.5V11M8 5h.01"/>',
  square: '<rect x="4" y="4" width="8" height="8"/>',
  none: '',
  copy: '<rect x="5.5" y="5.5" width="8" height="8" rx="1"/><path d="M3 10.5h-.5v-8h8V3"/>',
};
const I = (n, c = '') => `<svg class="i ${c}" viewBox="0 0 16 16" aria-hidden="true">${ICONS[n] || ''}</svg>`;

const S = {
  theme: 'dark', page: 'browse', profile: 'default', mock: 'normal', running: false,
  browse: { q: '', sort: 'downloads', view: 'grid', tags: [], tagMode: 'any', type: 'all', have: 'all', fits: false, facets: false, allTags: false },
  lib: { filter: 'all', q: '', sort: 'name', dir: 1, sel: new Set(), anchor: null, focus: null },
  srv: { q: '', chips: new Set(), sort: 'p', dir: -1 },
  set: { cat: 'game', q: '', scope: 'all', ov: { hardcore: { ownSettingsP: true, console: true }, friday: {}, default: {} } },
  panel: null, overlay: null, menu: null, dlpop: false,
  downloads: [], toasts: [], pal: { q: '', idx: 0 }, trouble: null, tid: 0,
};
const VAL = Object.fromEntries(SETTINGS.filter(s => 'val' in s).map(s => [s.key, s.val]));
const setting = k => { const o = S.set.ov[S.profile]; return o && k in o ? o[k] : VAL[k]; };

const profile = (id = S.profile) => PROFILES.find(p => p.id === id);
// The profile whose own settings are shown, and the way to join a server that is selected now.
const setPid = () => S.set.scope === 'profile' && PROFILES.some(p => p.id === S.set.pid) ? S.set.pid : S.profile;
const joinMode = () => S.srv.mode || (JOIN_MODES.find(m => m[1] === VAL.joinMods) || JOIN_MODES[0])[0];
const isInst = id => S.mock !== 'empty' && !!INST[id];
const isOn = (id, p = S.profile) => !!(isInst(id) && INST[id].on[p]);
// Each profile uses its own version of a mod. The store keeps every version that a profile uses.
const verOf = (id, p = S.profile) => INST[id] ? ((INST[id].ver && INST[id].ver[p]) || INST[id].v) : null;
const setVer = (id, v, p = S.profile) => { (INST[id].ver = INST[id].ver || {})[p] = v; };
const packManaged = (id, p = S.profile) => { const q = profile(p); return !!(q && q.pack && q.pack.mods && q.pack.mods.includes(id)); };
const hasUpdate = id => isInst(id) && !MOD[id].local && !packManaged(id) && verOf(id) !== MOD[id].v;
const updOf = id => MOD[id].upd + (isInst(id) && verOf(id) !== MOD[id].v ? 38 : 0);
const usedIn = id => PROFILES.filter(p => isOn(id, p.id)).map(p => p.name);
const dlOf = id => S.downloads.find(d => d.id === id && d.pct < 100);
const installedIds = () => S.mock === 'empty' ? [] : Object.keys(INST);
const onCount = (p = S.profile) => installedIds().filter(id => isOn(id, p)).length;
const updates = () => installedIds().filter(hasUpdate);

// Problems that the Library shows as badges on the row.
function problems(id) {
  const x = MOD[id], st = INST[id], out = [];
  if (!st) return out;
  if (st.problem === 'missing') out.push({ kind: 'danger', key: 'missing', text: 'File missing' });
  if (isOn(id)) {
    x.deps.forEach(d => { if (!isOn(d)) out.push({ kind: 'warn', key: 'dep', dep: d, text: 'Needs ' + MOD[d].name }); });
    x.conflicts.concat(MODS.filter(o => o.conflicts.includes(id)).map(o => o.id)).forEach(c => { if (isOn(c)) out.push({ kind: 'warn', key: 'conflict', other: c, text: 'Conflicts with ' + MOD[c].name }); });
  }
  if (VAL.warnOld && x.game !== GAME_VERSION) out.push({ kind: '', key: 'old', text: 'Made for ' + x.game });
  return out;
}
const hasProblem = id => problems(id).some(p => p.kind);

// Icon tile: a small picture for the mods that have one, initials on a colour from the name for the rest.
const TILE = ['#5d6732', '#8a5a2b', '#3f5f5a', '#6b4f6e', '#7a6a2e', '#4d5d78', '#7b4636', '#566b3c'];
const PICS = {
  cog: '<circle cx="20" cy="20" r="7" fill="none" stroke="#e8e2c8" stroke-width="5" stroke-dasharray="3.2 2.3"/><circle cx="20" cy="20" r="3" fill="#e8e2c8"/>',
  frame: '<path d="M8 28 20 8l12 20z" fill="none" stroke="#e8e2c8" stroke-width="2.5"/><path d="M14 28h12" stroke="#e0552e" stroke-width="3"/>',
  map: '<path d="M6 12l9-3 10 3 9-3v19l-9 3-10-3-9 3z" fill="none" stroke="#e8e2c8" stroke-width="2.2"/><circle cx="24" cy="19" r="3" fill="#e0552e"/>',
  eye: '<path d="M5 20c4-7 9-10 15-10s11 3 15 10c-4 7-9 10-15 10S9 27 5 20z" fill="none" stroke="#e8e2c8" stroke-width="2.2"/><circle cx="20" cy="20" r="4.5" fill="#e8e2c8"/>',
  globe: '<circle cx="20" cy="20" r="12" fill="none" stroke="#e8e2c8" stroke-width="2.2"/><path d="M8 20h24M20 8c-6 7-6 17 0 24M20 8c6 7 6 17 0 24" fill="none" stroke="#e8e2c8" stroke-width="1.6"/>',
  jet: '<path d="M20 5l3 12 11 7v3l-11-3-1 7 4 3v2l-6-2-6 2v-2l4-3-1-7-11 3v-3l11-7z" fill="#e8e2c8"/>',
  jet2: '<path d="M20 4l4 16 10 10v2l-11-4-3 6-3-6-11 4v-2l10-10z" fill="#e8e2c8"/>',
  wing: '<path d="M6 26l14-12 14 12-14-5z" fill="#e8e2c8"/><path d="M20 14v-6" stroke="#e0552e" stroke-width="3"/>',
};
function hash(s) { let h = 0; for (const c of s) h = (h * 31 + c.charCodeAt(0)) >>> 0; return h; }
function icon(x, sz = '') {
  const bg = TILE[hash(x.id) % TILE.length];
  if (x.img) return `<span class="ico ${sz}" style="background:${bg}"><svg viewBox="0 0 40 40">${PICS[x.img]}</svg></span>`;
  return `<span class="ico ${sz}" style="background:${bg}">${esc(initials(x.name, VAL.tileSkipNuclear))}</span>`;
}
// A name with spaces uses its first two words. A name without spaces is split at snake_case,
// kebab-case and camelCase breaks first (NOTacMap: NO, Tac, Map). With skipNuclear a first word
// "Nuclear" is left out when two or more words follow it, so NuclearMinimap stays NM.
function nameWords(name) {
  return name.split(/[^\p{L}\p{N}]+/u).flatMap(p => p.split(/(?<=\p{Ll})(?=\p{Lu})|(?<=\p{Lu})(?=\p{Lu}\p{Ll})/u)).filter(Boolean);
}
function initials(name, skipNuclear) {
  const spaced = name.replace(/[^A-Za-z0-9 ]/g, '').split(' ').filter(Boolean);
  const split = spaced.length < 2 && !name.trim().includes(' ');
  let w = split ? nameWords(name) : spaced;
  if (skipNuclear && w.length > 2 && w[0].toLowerCase() === 'nuclear') w = w.slice(1);
  const first = (s, n) => [...s].slice(0, n).join('');
  return (w.length > 1 ? first(w[0], 1) + first(w[1], 1) : split && w.length ? first(w[0], 2) : name.slice(0, 2)).toUpperCase();
}

// Search: forgiving match on the name, plain match on tags, author and description. Returns why it matched.
function fuzzy(q, text) {
  const t = text.toLowerCase(); q = q.toLowerCase();
  const at = t.indexOf(q);
  if (at >= 0) return { score: 200 - at + (at === 0 || t[at - 1] === ' ' ? 30 : 0), idx: Array.from({ length: q.length }, (_, i) => at + i) };
  if (q.length < 3) return null;
  let i = 0, last = -2, score = 60; const idx = [];
  for (let j = 0; j < t.length && i < q.length; j++) {
    if (t[j] === q[i]) { idx.push(j); if (j === last + 1) score += 6; if (j === 0 || t[j - 1] === ' ') score += 8; last = j; i++; }
  }
  if (i < q.length) return null;
  const spread = idx[idx.length - 1] - idx[0] - q.length;
  return spread > Math.max(2, q.length - 1) ? null : { score: score - spread * 2, idx };
}
function hl(text, idx) {
  if (!idx || !idx.length) return esc(text);
  const set = new Set(idx); let out = '', open = false;
  for (let i = 0; i < text.length; i++) {
    if (set.has(i) && !open) { out += '<mark>'; open = true; }
    if (!set.has(i) && open) { out += '</mark>'; open = false; }
    out += esc(text[i]);
  }
  return out + (open ? '</mark>' : '');
}
function matchMod(x, q) {
  if (!q) return { score: 0 };
  const n = fuzzy(q, x.name);
  if (n) return { score: n.score + 100, nameIdx: n.idx };
  const ql = q.toLowerCase();
  const tag = x.tags.find(t => t.toLowerCase().includes(ql));
  if (tag) return { score: 90, why: 'Tag: ' + esc(tag) };
  if (x.author.toLowerCase().includes(ql)) return { score: 80, why: 'Author: ' + esc(x.author) };
  const at = x.desc.toLowerCase().indexOf(ql);
  if (at >= 0) {
    const a = Math.max(0, at - 18), b = Math.min(x.desc.length, at + q.length + 22);
    const cut = x.desc.slice(a, b);
    return { score: 60, why: 'In the description: ' + (a > 0 ? '…' : '') + hl(cut, Array.from({ length: q.length }, (_, i) => at - a + i)) + (b < x.desc.length ? '…' : '') };
  }
  return null;
}

const ALL_TAGS = (() => { const c = {}; MODS.forEach(x => x.tags.forEach(t => c[t] = (c[t] || 0) + 1)); return Object.entries(c).sort((a, b) => b[1] - a[1]); })();
// Tag counts follow the other filters, so a count is what you get when you click the tag.
function tagCounts() {
  const list = browseList('tags').map(r => r.x), b = S.browse, c = {};
  const base = b.tagMode === 'all' && b.tags.length ? list.filter(x => b.tags.every(t => x.tags.includes(t))) : list;
  ALL_TAGS.forEach(([t]) => c[t] = base.filter(x => x.tags.includes(t)).length);
  return c;
}
function browseList(skip) {
  const b = S.browse;
  let list = MODS.filter(x => !x.local).filter(x => VAL.hideServer ? x.side !== 'server' : true);
  if (b.type !== 'all' && skip !== 'type') list = list.filter(x => x.type === b.type);
  if (b.tags.length && skip !== 'tags') list = list.filter(x => b.tagMode === 'all' ? b.tags.every(t => x.tags.includes(t)) : b.tags.some(t => x.tags.includes(t)));
  if (skip !== 'have' && b.have === 'new') list = list.filter(x => !isInst(x.id));
  if (skip !== 'have' && b.have === 'inst') list = list.filter(x => isInst(x.id));
  if (skip !== 'have' && b.have === 'upd') list = list.filter(x => hasUpdate(x.id));
  if (b.fits) list = list.filter(x => x.game === GAME_VERSION);
  let res = list.map(x => ({ x, m: matchMod(x, b.q.trim()) })).filter(r => r.m);
  const by = { downloads: (a, c) => c.x.dl - a.x.dl, updated: (a, c) => a.x.upd - c.x.upd, newest: (a, c) => a.x.added - c.x.added, name: (a, c) => a.x.name.localeCompare(c.x.name) };
  res.sort(b.q.trim() ? (a, c) => c.m.score - a.m.score || by.downloads(a, c) : by[b.sort]);
  return res;
}
const browseFiltered = () => { const b = S.browse; return !!(b.q.trim() || b.tags.length || b.type !== 'all' || b.have !== 'all' || b.fits); };

function libList() {
  const l = S.lib;
  let ids = installedIds();
  const f = { on: id => isOn(id), off: id => !isOn(id), upd: hasUpdate, local: id => INST[id].source === 'local', prob: hasProblem }[l.filter];
  if (f) ids = ids.filter(f);
  if (l.q.trim()) ids = ids.filter(id => fuzzy(l.q.trim(), MOD[id].name));
  const key = { name: id => MOD[id].name.toLowerCase(), size: id => MOD[id].size, upd: id => updOf(id), source: id => sourceOf(id), on: id => isOn(id) ? 0 : 1, ver: id => hasUpdate(id) ? 0 : 1 }[l.sort];
  ids.sort((a, b) => (key(a) > key(b) ? 1 : key(a) < key(b) ? -1 : 0) * l.dir || MOD[a].name.localeCompare(MOD[b].name));
  return ids;
}
const sourceOf = id => INST[id].source === 'local' ? 'Local file' : packManaged(id) ? 'Modpack' : 'Registry';
const libCounts = () => { const ids = installedIds(); return { all: ids.length, on: ids.filter(id => isOn(id)).length, off: ids.filter(id => !isOn(id)).length, upd: ids.filter(hasUpdate).length, local: ids.filter(id => INST[id].source === 'local').length, prob: ids.filter(hasProblem).length }; };

// Server mods compared with what is installed.
// A server either publishes its mod list (maybe an empty one) or says nothing. Nothing is 'unknown', not 'no mods'.
function srvMods(s) {
  return (s.mods || []).map(e => { const [id, want] = e.split('@'); const state = !isInst(id) ? 'missing' : (want && verOf(id) !== want) ? 'version' : !isOn(id) ? 'off' : 'have'; return { id, want, state }; });
}
function srvState(s) {
  const ms = srvMods(s); const miss = ms.filter(x => x.state === 'missing').length, ver = ms.filter(x => x.state === 'version').length, off = ms.filter(x => x.state === 'off').length;
  const other = s.ver !== GAME_VERSION;
  return { other, unknown: s.modded && s.mods === null, n: ms.length, miss, ver, off, ready: !other && ms.length > 0 && !miss && !ver && !off, vanilla: !s.modded };
}
function srvList() {
  const v = S.srv; let list = SERVERS.slice();
  if (v.q.trim()) list = list.filter(s => fuzzy(v.q.trim(), s.name + ' ' + s.mission + ' ' + s.map));
  const c = v.chips, any = (...ks) => ks.some(k => c.has(k));
  // Chips in one group widen the list (PvE or PvP); chips of different groups narrow it.
  if (c.has('fits')) list = list.filter(s => { const st = srvState(s); return !st.other && (st.vanilla || st.ready); });
  if (any('vanilla', 'mods')) list = list.filter(s => (c.has('vanilla') && !s.modded) || (c.has('mods') && s.modded));
  if (any('pve', 'pvp', 'mixed')) list = list.filter(s => (c.has('pve') && s.type === 'PvE') || (c.has('pvp') && s.type === 'PvP') || (c.has('mixed') && s.type === 'Mixed'));
  if (c.has('notfull')) list = list.filter(s => s.p < s.max);
  if (c.has('notempty')) list = list.filter(s => s.p > 0);
  if (c.has('ping')) list = list.filter(s => s.ping < (parseInt(VAL.pingYellow, 10) || Infinity));
  const key = { name: s => s.name.toLowerCase(), p: s => s.p, ping: s => s.ping, mods: s => (s.mods || []).length + (s.modded ? 1 : 0), round: s => s.type + String(s.round == null ? 999999 : s.round).padStart(6, '0'), mission: s => s.mission + s.map }[v.sort];
  list.sort((a, b) => (key(a) > key(b) ? 1 : key(a) < key(b) ? -1 : 0) * v.dir);
  return list;
}

// Toasts and downloads
function toast(text, action, fn, ms = 7000) {
  const t = { id: ++S.tid, text, action, fn }; S.toasts.push(t); if (S.toasts.length > 3) S.toasts.shift();
  setTimeout(() => { const i = S.toasts.indexOf(t); if (i >= 0) { S.toasts.splice(i, 1); render(); } }, ms);
}
let ticking = null;
function download(id, kind) {
  if (dlOf(id)) return;
  S.downloads = S.downloads.filter(d => d.id !== id);
  S.downloads.push({ id, kind, pct: 0, speed: 6 + (hash(id) % 14), profile: S.profile });
  if (!ticking) ticking = setInterval(tick, 260);
}
function tick() {
  let done = false;
  const active = S.downloads.filter(d => d.pct < 100).slice(0, 3);
  active.forEach(d => {
    d.pct = Math.min(100, d.pct + d.speed);
    if (d.pct >= 100) { finish(d); done = true; }
    document.querySelectorAll(`[data-dlbar="${d.id}"]`).forEach(e => e.style.width = d.pct + '%');
    document.querySelectorAll(`[data-dlpct="${d.id}"]`).forEach(e => e.textContent = d.pct + '%');
  });
  const left = S.downloads.filter(d => d.pct < 100);
  const tot = $('[data-dltotal]'); if (tot && left.length) tot.style.width = Math.round(left.reduce((a, d) => a + d.pct, 0) / left.length) + '%';
  if (!left.length) {
    clearInterval(ticking); ticking = null;
    if (S.pendingJoin) { const j = S.pendingJoin; S.pendingJoin = null; if (PROFILES.some(p => p.id === j.profile)) S.profile = j.profile; S.running = true; S.toasts = []; toast(`Mods are ready. Joining ${j.name}`, null, null, 4000); done = true; }
  }
  if (done) render();
}
function finish(d) {
  const x = MOD[d.id];
  if (d.kind === 'update' && !INST[d.id]) return;
  if (d.kind === 'update') {
    setVer(d.id, d.ver || x.v, d.profile);
    if (!d.quiet) toast(`Updated ${x.name} to ${x.v}`, "What's new", () => { S.panel = { kind: 'mod', id: d.id, tab: 'new' }; });
  } else {
    const on = {}; PROFILES.forEach(p => on[p.id] = false); on[d.profile] = true;
    INST[d.id] = { v: d.ver || x.v, source: 'registry', on, problem: null };
    if (!d.quiet) toast(`Installed ${x.name} and turned it on in ${profile(d.profile).name}`, 'Undo', () => { delete INST[d.id]; (d.with || []).forEach(q => delete INST[q]); });
  }
}
function install(id, quiet) {
  const need = VAL.autoDeps ? MOD[id].deps.filter(d => !isInst(d)) : [];
  need.forEach(d => { download(d, 'install'); S.downloads[S.downloads.length - 1].quiet = true; });
  download(id, 'install'); const rec = S.downloads[S.downloads.length - 1]; rec.with = need; if (quiet) rec.quiet = true;
  if (need.length && !quiet) toast(`${MOD[id].name} needs ${need.map(d => MOD[d].name).join(' and ')}. Installing both.`);
  MOD[id].deps.filter(d => isInst(d) && !isOn(d)).forEach(d => { INST[d].on[S.profile] = true; });
}
function removeMods(ids) {
  const keep = ids.map(id => [id, INST[id]]);
  ids.forEach(id => { delete INST[id]; S.lib.sel.delete(id); });
  S.downloads = S.downloads.filter(d => d.pct >= 100 || !ids.includes(d.id));
  if (S.panel && ids.includes(S.panel.id)) S.panel = null;
  const where = ids.length === 1 && VAL.showProfiles && PROFILES.length > 1 ? keep[0][1] && PROFILES.filter(p => keep[0][1].on[p.id]).map(p => p.name) : [];
  toast(ids.length === 1 ? `Uninstalled ${MOD[ids[0]].name}${where.length ? '. It was on in ' + where.join(' and ') : ''}` : `Uninstalled ${ids.length} mods${VAL.showProfiles && PROFILES.length > 1 ? ' from all profiles' : ''}`, 'Undo', () => keep.forEach(([id, st]) => INST[id] = st), 8000);
}
function setOn(ids, on) {
  const p = S.profile, before = ids.map(id => [id, isOn(id)]);
  ids.forEach(id => { INST[id].on[p] = on; });
  if (ids.length > 1) toast(`${on ? 'Turned on' : 'Turned off'} ${ids.length} mods`, 'Undo', () => before.forEach(([id, v]) => { if (INST[id]) INST[id].on[p] = v; }));
}
