// Fake data for the NNOMM mock. Nothing here is read from the game, the registry or Steam.
const GAME_VERSION = '0.34.6';

// m(id, name, author, desc, tags, downloads, updatedDaysAgo, addedDaysAgo, version, sizeKb, extra)
const MODS = [];
function m(id, name, author, desc, tags, dl, upd, added, v, size, x = {}) {
  MODS.push(Object.assign({ id, name, author, desc, tags, dl, upd, added, v, size, type: 'plugin', side: 'client', game: GAME_VERSION, deps: [], conflicts: [], img: false, pre: null }, x));
}
m('config-manager', 'BepInEx Configuration Manager', 'BepInEx team', 'Press F1 in the game to change the settings of your mods without editing files.', ['QoL', 'Utility'], 1277950, 41, 610, '18.4.1', 310, { img: 'cog' });
m('blueprinter', 'Blueprinter', 'Nikkorap', 'Loads aircraft, weapons and vehicles that other people made. Most add-on aircraft need it.', ['Framework', 'Aircraft'], 116910, 9, 402, '2.1.1', 2140, { img: 'frame' });
m('unity-editor', 'Runtime Unity Editor', 'ManlyMarco', 'Inspect and change game objects while the game runs. For mod makers.', ['Dev tools'], 145870, 120, 590, '5.4.0', 1890);
m('minimap', 'Nuclear Minimap', 'dikka', 'Shows the map on a cockpit screen, with heading tape, zoom keys and north-up or heading-up.', ['HUD', 'Map'], 8420, 3, 34, '0.5.2', 96, { img: 'map', prev: '0.5.1' });
m('delivery', 'Nuclear Delivery', 'dikka', 'Counts your missiles and bombs in flight and shows hits and misses on the target camera.', ['HUD', 'Weapons'], 5210, 12, 40, '0.1.0', 54);
m('spectator', 'Nuclear Spectator', 'dikka', 'Watch other pilots from their cockpit, with their HUD, a target camera and smooth camera moves.', ['Camera', 'Multiplayer'], 6130, 6, 12, '0.1.12', 88, { img: 'eye' });
m('map-trails', 'Nuclear Map Trails', 'dikka', 'Draws fading trails behind aircraft on the map.', ['Map', 'Visual'], 3980, 20, 21, '0.1.1', 31);
m('map-markings', 'Nuclear Map Markings', 'dikka', 'Runways, approach lines, range rings and predicted paths on the map.', ['Map', 'HUD'], 4410, 11, 15, '0.1.6', 47);
m('satellite-map', 'Nuclear Satellite Map', 'dikka', 'Satellite pictures under the map grid. Large download: the map images are 127 MB.', ['Map', 'Visual'], 2950, 13, 20, '0.2.0', 130400, { img: 'globe' });
m('night-vision', 'Nuclear Night Vision', 'dikka', 'Night vision fades in and out instead of switching at once.', ['Visual', 'Immersion'], 1890, 10, 10, '0.1.0', 22);
m('sounds', 'Nuclear Sounds', 'dikka', 'Extra cockpit warning sounds and a quieter missile warning.', ['Sound'], 2260, 11, 30, '0.1.5', 4100);
m('harrier-kai', 'F-27 Harrier Kai', 'Sable', 'A rank 2 vertical take-off strike jet with a new cockpit.', ['Aircraft'], 52700, 2, 96, '1.4.0', 18300, { type: 'addon', deps: ['blueprinter'], img: 'jet', prev: '1.3.2' });
m('vortex', 'Su-41 Vortex', 'Okapi', 'A fast rank 4 interceptor for the PALA side with long-range missiles.', ['Aircraft'], 88460, 15, 210, '2.0.3', 24100, { type: 'addon', deps: ['blueprinter'], img: 'jet2' });
m('warthog', 'A-9 Warthog II', 'Sable', 'A slow, tough ground-attack aircraft with a 30 mm gun.', ['Aircraft'], 61690, 44, 300, '1.1.0', 16900, { type: 'addon', deps: ['blueprinter'], game: '0.34.2' });
m('coastal-strike', 'Coastal Strike Pack', 'Okapi', 'Six anti-ship missiles and two glide bombs for add-on aircraft.', ['Weapons'], 30120, 5, 150, '1.2.0', 7200, { type: 'addon', deps: ['blueprinter'], pre: '1.3.0-beta2' });
m('tower-voice', 'Tower Voice Pack', 'Halcyon', 'New recorded voices for the tower and for ground crews.', ['Sound', 'Voice pack', 'Immersion'], 12480, 60, 240, '1.0.2', 38200);
m('better-flares', 'Better Flares', 'tern', 'Brighter flares with smoke that stays in the air a little longer.', ['Visual'], 21900, 33, 320, '1.3.1', 640);
m('cockpit-shake', 'Cockpit Shake', 'Voss', 'The cockpit shakes with speed, G-load and gun fire.', ['Immersion', 'Camera'], 17650, 70, 280, '0.9.4', 58, { game: '0.34.2' });
m('ffb-tuner', 'FFB Tuner', 'pilot_ray', 'Change force-feedback strength for each aircraft, with a test page.', ['Controls'], 4020, 25, 130, '1.0.0', 120);
m('radio-chatter', 'Radio Chatter', 'Halcyon', 'Background radio calls from other flights during missions.', ['Sound', 'Immersion'], 9870, 18, 170, '2.2.0', 21500);
m('german', 'Deutsche Lokalisierung', 'Voss', 'German texts for menus, the HUD and the encyclopedia.', ['Localization'], 3310, 8, 200, '0.34.6', 410);
m('free-cam', 'Free Camera Plus', 'tern', 'More camera modes for screenshots: orbit, fly-by and a fixed tower camera.', ['Camera'], 15230, 90, 400, '1.6.0', 77, { conflicts: ['spectator'] });
m('server-tools', 'Server Tools', 'Mako', 'Admin commands, vote kicks and mission rotation for dedicated servers.', ['Server', 'Multiplayer'], 2740, 4, 260, '3.1.0', 190, { side: 'server' });
m('loadout-presets', 'Loadout Presets', 'pilot_ray', 'Save a weapon loadout and use it again with one click.', ['QoL', 'Weapons'], 26400, 7, 190, '1.5.0', 66, { prev: '1.4.2' });
m('target-colors', 'Target Box Colors', 'tern', 'Choose the colours of target boxes for friends, enemies and unknown contacts.', ['HUD'], 11020, 52, 230, '1.0.3', 28);
m('autopilot', 'Autopilot Hold', 'Okapi', 'Holds altitude, heading or speed until you touch the stick.', ['Controls', 'QoL'], 33800, 28, 350, '2.1.0', 71);
m('carrier-ops', 'Carrier Ops', 'Mako', 'Adds catapult launches and arrested landings on carriers.', ['Gameplay'], 19400, 16, 110, '0.8.0', 5300, { deps: ['blueprinter'] });
m('desert-dawn', 'Desert Dawn Terrain', 'Halcyon', 'A dry desert look for the Heartland map, with new ground textures.', ['Terrain', 'Visual'], 7600, 75, 180, '1.0.0', 211000, { game: '0.34.2' });
m('sonic-boom', 'Sonic Boom', 'Voss', 'You hear a boom when a supersonic aircraft passes.', ['Sound'], 14100, 100, 330, '1.1.2', 900);
m('hud-scale', 'HUD Scale', 'pilot_ray', 'Make the HUD larger or smaller, for wide screens and 4K.', ['HUD', 'QoL'], 22750, 36, 310, '1.2.0', 19);
m('mission-timer', 'Mission Timer', 'tern', 'A small clock that shows how long the mission has run.', ['HUD'], 2980, 130, 340, '1.0.0', 11);
m('wingman', 'Wingman Commands', 'Mako', 'Give orders to AI wingmen: attack my target, cover me, return to base.', ['Gameplay'], 28300, 1, 75, '0.6.0', 240, { img: 'wing' });
m('trackir', 'Head Tracking Recenter', 'pilot_ray', 'One key recenters your head tracker and a second key pauses it.', ['Controls'], 5120, 48, 140, '1.0.1', 14);
m('vr-hands', 'VR Hands', 'Okapi', 'Shows your hands in VR and lets you press cockpit buttons.', ['VR'], 6900, 22, 95, '0.4.2', 3400);
m('tracers', 'Tracer Colors', 'tern', 'Different tracer colours for each side.', ['Visual'], 8050, 110, 290, '1.0.0', 16);
m('kill-feed', 'Kill Feed Filter', 'Mako', 'Hide or shorten kill messages on busy servers.', ['HUD', 'Multiplayer'], 4390, 14, 60, '1.1.0', 23);
m('fuel-planner', 'Fuel Planner', 'Halcyon', 'Shows how far you can fly with your fuel and when you must turn back.', ['Utility', 'HUD'], 10260, 19, 85, '1.3.0', 44);
m('g-meter', 'G-Meter', 'Voss', 'A G-load number next to the speed tape.', ['HUD'], 6710, 85, 270, '1.0.4', 9);
m('smoke-trails', 'Smoke Trails', 'tern', 'Coloured display smoke for formation flights.', ['Visual', 'Multiplayer'], 9320, 30, 125, '1.2.1', 320);
m('awacs', 'AWACS Callouts', 'Halcyon', 'A voice tells you bearing and range of new threats.', ['Sound', 'Gameplay'], 13800, 5, 50, '0.7.1', 12600, { pre: '0.8.0-rc1' });

const MOD = Object.fromEntries(MODS.map(x => [x.id, x]));

const NOTES = {
  'minimap': [['0.5.2', '3 days ago', ['The HUD no longer stays on screen after you are shot down.', 'The cockpit map keeps its place when a map icon is removed.']], ['0.5.1', '12 days ago', ['Fixed the map jumping to the bottom-left corner.']], ['0.5.0', '3 weeks ago', ['Zoom keys now use one binding system for keyboard, gamepad and HOTAS.']]],
  'harrier-kai': [['1.4.0', '2 days ago', ['New cockpit screens.', 'Hover is more stable below 40 knots.', 'Needs Blueprinter 2.1.1.']], ['1.3.2', '5 weeks ago', ['Fixed the left pylon weight.']]],
  'loadout-presets': [['1.5.0', '7 days ago', ['Presets can be shared as a short code.', 'Works with add-on weapons.']], ['1.4.2', '2 months ago', ['Fixed presets for aircraft with internal bays.']]],
};
function notesFor(id) {
  const x = MOD[id];
  return NOTES[id] || [[x.v, ago(x.upd), []], [prevVersion(x.v), ago(x.upd + 40), []]];
}
function prevVersion(v) { const p = v.split('.').map(n => parseInt(n, 10) || 0); for (let i = p.length - 1; i >= 0; i--) { if (p[i] > 0) { p[i]--; return p.join('.'); } p[i] = 9; } return '0.' + v; }
function ago(d) { if (d <= 0) return 'today'; if (d === 1) return 'yesterday'; if (d < 14) return d + ' days ago'; if (d < 60) return Math.round(d / 7) + ' weeks ago'; if (d < 365) return Math.round(d / 30) + ' months ago'; return Math.round(d / 365) + ' years ago'; }
function size(kb) { return kb >= 1024 ? (kb / 1024).toFixed(kb >= 10240 ? 0 : 1) + ' MB' : kb + ' KB'; }
function num(n) { return n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e4 ? Math.round(n / 1e3) + 'k' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'k' : String(n); }

// Installed mods: version on disk, where it came from, and per-profile on/off.
const INST = {};
function inst(id, on, x = {}) { INST[id] = Object.assign({ v: MOD[id] ? (MOD[id].prev || MOD[id].v) : x.v, source: 'registry', on: { default: on, hardcore: false, friday: false }, problem: null }, x); }
inst('config-manager', true); inst('blueprinter', true); inst('minimap', true); inst('delivery', true); inst('spectator', true);
inst('map-trails', true); inst('map-markings', true); inst('satellite-map', false); inst('night-vision', true); inst('sounds', true);
inst('harrier-kai', true); inst('vortex', false); inst('warthog', true); inst('loadout-presets', true); inst('autopilot', true);
inst('cockpit-shake', true); inst('free-cam', true); inst('hud-scale', false); inst('better-flares', true); inst('tower-voice', false);
inst('fuel-planner', false); inst('tracers', false, { problem: 'missing' }); inst('g-meter', false);
// two mods that are not in the registry: added from a file
MODS.push({ id: 'local-testhud', name: 'TestHud', author: 'TestHud.dll', file: 'TestHud.dll', desc: 'You added this mod from a file. It is not in the registry, so NNOMM cannot look for updates.', tags: [], dl: 0, upd: 2, added: 2, v: '0.0.3', size: 18, type: 'plugin', side: 'client', game: GAME_VERSION, deps: [], conflicts: [], img: false, local: true });
MODS.push({ id: 'local-skin', name: 'Squadron Skins', author: 'squadron-skins.zip', file: 'squadron-skins.zip', desc: 'You added this mod from a file. It is not in the registry, so NNOMM cannot look for updates.', tags: [], dl: 0, upd: 26, added: 26, v: '1.0', size: 9400, type: 'addon', side: 'client', game: GAME_VERSION, deps: [], conflicts: [], img: false, local: true });
MOD['local-testhud'] = MODS[MODS.length - 2]; MOD['local-skin'] = MODS[MODS.length - 1];
inst('local-testhud', true, { source: 'local' }); inst('local-skin', false, { source: 'local' });
// profile contents
['config-manager', 'blueprinter', 'minimap', 'delivery', 'map-markings', 'cockpit-shake', 'tower-voice', 'autopilot'].forEach(id => INST[id].on.hardcore = true);
const FRIDAY = ['config-manager', 'blueprinter', 'harrier-kai', 'vortex', 'warthog', 'loadout-presets', 'better-flares', 'spectator'];
FRIDAY.forEach(id => INST[id].on.friday = true);

const PROFILES = [
  { id: 'default', name: 'Default' },
  { id: 'hardcore', name: 'Hardcore sim', ownSettings: true },
  { id: 'friday', name: 'Friday squad', pack: { by: 'Mako', v: '1.3', next: '1.4', mods: FRIDAY, followed: true } },
];

const PACK_IMPORT = {
  name: 'Pacific Wing training pack', by: 'Halcyon', v: '2.0', file: 'pacific-wing-2.0.nommpack',
  items: [
    ['same', 'config-manager'], ['same', 'blueprinter'], ['same', 'spectator'], ['same', 'autopilot'], ['same', 'better-flares'],
    ['ver', 'harrier-kai', '1.4.0'], ['ver', 'loadout-presets', '1.5.0'], ['ver', 'map-markings', '0.1.5'],
    ['add', 'wingman'], ['add', 'awacs'], ['add', 'radio-chatter'], ['add', 'carrier-ops'], ['add', 'smoke-trails'],
    ['local', 'PacificWingSkins.dll', 220],
  ],
};
const PACK_UPDATE = { items: [['add', 'coastal-strike'], ['add', 'wingman'], ['rem', 'better-flares'], ['chg', 'harrier-kai', '1.3.2', '1.4.0'], ['chg', 'loadout-presets', '1.4.2', '1.5.0'], ['same', 'config-manager'], ['same', 'blueprinter'], ['same', 'vortex'], ['same', 'warthog'], ['same', 'spectator']] };

// Servers. Fields follow what a dedicated server really publishes: name, short mission name, map, players, game version,
// PvP type, round start, a "modded" flag, a password flag, a Steam relay location (not always), and a description.
// A mod list exists only when the server runs the manager's server plugin. Player names do not exist in this game.
const SERVERS = [
  { id: 's1', name: 'scramd.com | Escalation 24/7 | new pilots welcome', mission: 'Escalation', map: 'Heartland', type: 'PvE', p: 21, max: 32, ping: 24, loc: 'Frankfurt', round: 42, modded: false, mods: [], fav: true, recent: 1, desc: 'Escalation around the clock. New pilots welcome. Rules are on scramd.com.' },
  { id: 's2', name: 'Hangar 18 | modded aircraft | free flight', mission: 'Free flight', map: 'Ignus', type: 'Mixed', p: 14, max: 24, ping: 96, loc: 'New York', round: 187, modded: true, mods: ['blueprinter', 'harrier-kai', 'vortex', 'warthog', 'coastal-strike', 'loadout-presets', 'carrier-ops', 'wingman', 'better-flares'], fav: true, desc: 'Add-on aircraft only. The list changes every Sunday.' },
  { id: 's3', name: 'Nordic Sim Squadron [EU] realistic procedures', mission: 'Terminal Control', map: 'Heartland', type: 'PvE', p: 30, max: 32, ping: 38, loc: 'Stockholm', round: 12, modded: true, mods: ['config-manager', 'minimap', 'spectator', 'autopilot', 'cockpit-shake'], recent: 2, desc: 'Realistic procedures. Voice chat needed.' },
  { id: 's4', name: 'Pacific Wing training', mission: 'Training range', map: 'Ignus', type: 'PvE', p: 3, max: 16, ping: 212, loc: 'Sydney', round: 65, modded: true, mods: null, lock: true, desc: '' },
  { id: 's5', name: 'Blueprint test range for add-on makers', mission: 'Sandbox', map: 'Heartland', type: 'Mixed', p: 0, max: 16, ping: 41, loc: 'Amsterdam', round: null, modded: true, mods: ['blueprinter', 'harrier-kai', 'vortex', 'warthog', 'coastal-strike', 'carrier-ops', 'desert-dawn', 'wingman', 'awacs', 'radio-chatter', 'smoke-trails', 'vr-hands'], desc: 'Test server for add-on makers.' },
  { id: 's6', name: 'Terminal Control PvE | no team kills', mission: 'Terminal Control', map: 'Heartland', type: 'PvE', p: 32, max: 32, ping: 118, loc: 'Chicago', round: 95, modded: false, mods: [], desc: 'Players against the AI. No team kills.' },
  { id: 's7', name: 'Low Orbit friends', mission: 'Escalation', map: 'Ignus', type: 'PvE', p: 6, max: 12, ping: 164, loc: null, round: 230, modded: true, mods: ['harrier-kai@1.4.0', 'loadout-presets@1.5.0', 'blueprinter'], desc: 'Small group, friendly.' },
  { id: 's8', name: 'Kuril Front PvP | two teams | no AI aircraft', mission: 'Escalation', map: 'Heartland', type: 'PvP', p: 18, max: 40, ping: 248, loc: 'Tokyo', round: 51, modded: true, mods: null, desc: 'Two teams, no AI aircraft.' },
  { id: 's9', name: 'Weekend carrier ops', mission: 'Carrier strike', map: 'Ignus', type: 'PvE', p: 9, max: 20, ping: 52, loc: 'Frankfurt', round: 8, modded: true, mods: ['blueprinter', 'carrier-ops', 'harrier-kai'], desc: 'Carrier launches and landings.' },
  { id: 's10', name: 'Rookie range | slow missions for new pilots', mission: 'Training range', map: 'Heartland', type: 'PvE', p: 11, max: 24, ping: 61, loc: 'London', round: 140, modded: false, mods: [], desc: 'Slow missions for new pilots.' },
  { id: 's11', name: 'Night shift', mission: 'Escalation', map: 'Ignus', type: 'PvP', p: 2, max: 32, ping: 133, loc: 'Dallas', round: 20, modded: true, mods: ['night-vision'], ver: '0.34.5', desc: 'Night missions only.' },
];
SERVERS.forEach(s => { s.ver = s.ver || GAME_VERSION; });
// Ways to join a server that lists its mods. The first one is the default.
const JOIN_MODES = [
  ['tempplus', "Temporary profile: the current profile plus the server's mods"],
  ['temp', "Temporary profile: only the server's mods"],
  ['new', "New profile: only the server's mods"],
  ['add', "Add the server's mods to the current profile"],
];

// Settings. ov: can be different per profile. Values are mock values only.
const SETTINGS = [
  { cat: 'game', key: 'gamePath', label: 'Game folder', help: 'Nuclear Option 0.34.6 found here.', type: 'path', val: 'D:\\SteamLibrary\\steamapps\\common\\Nuclear Option', actions: ['Find again', 'Choose folder'] },
  { cat: 'game', key: 'launchArgs', label: 'Start options', help: 'Extra options for the game, for example -screen-fullscreen 0.', type: 'text', val: '', placeholder: 'none', ov: true },
  { cat: 'game', key: 'onLaunch', label: 'When the game starts', type: 'select', val: 'Keep NNOMM open', options: ['Keep NNOMM open', 'Minimise NNOMM', 'Close NNOMM'], ov: true },
  { cat: 'game', key: 'loader', label: 'Mod loader', help: 'BepInEx 5.4.23.4, installed by NNOMM. Mods cannot run without it. NNOMM installs it when you turn on your first mod, and tells you.', type: 'actions', actions: ['Repair'] },
  { cat: 'game', key: 'console', label: 'Show the mod log window while the game runs', help: 'A text window with messages from mods. Useful when a mod fails.', type: 'toggle', val: false, ov: true },

  { cat: 'mods', key: 'checkUpdates', label: 'Read the registry for new mods and updates', type: 'select', val: 'When NNOMM starts', options: ['When NNOMM starts', 'Every hour while NNOMM is open', 'Only when I ask'] },
  { cat: 'mods', key: 'onUpdate', label: 'When a mod has an update', type: 'select', val: 'Tell me', options: ['Tell me', 'Update by itself'], ov: true },
  { cat: 'mods', key: 'prerelease', label: 'Offer test versions of mods', help: 'Authors mark some versions as pre-release. They can be unstable.', type: 'toggle', val: false },
  { cat: 'mods', key: 'keepOld', label: 'Keep the version before an update', help: 'You can go back to it from the mod page.', type: 'select', val: '1 version', options: ['None', '1 version', '3 versions'] },
  { cat: 'mods', key: 'autoDeps', label: 'Install mods that a mod needs', help: 'For example Blueprinter for add-on aircraft.', type: 'toggle', val: true },
  { cat: 'mods', key: 'warnOld', label: 'Mark mods made for an older game version', help: 'A mark is not proof that the mod is broken.', type: 'toggle', val: true },

  { cat: 'profiles', key: 'showProfiles', label: 'Show profiles in the sidebar', help: 'With this off NNOMM uses the Default profile and you have one mod list.', type: 'toggle', val: true },
  { cat: 'profiles', key: 'ownSettings', label: 'New profiles keep their own mod settings', help: 'Mod settings are the values you change with F1 in the game. With this off all profiles share them.', type: 'toggle', val: false },
  { cat: 'profiles', key: 'newFrom', label: 'A new profile starts', type: 'select', val: 'With all mods off', options: ['With all mods off', 'As a copy of the current profile'] },
  { cat: 'profiles', key: 'tempKeep', label: 'Remove temporary server profiles', help: 'NNOMM makes a temporary profile when you join a server that needs mods. If NNOMM is closed at that time, it removes the profile at its next start.', type: 'select', val: 'When the game closes', options: ['When the game closes', 'After 7 days'] },
  { cat: 'profiles', key: 'ownSettingsP', label: 'This profile keeps its own mod settings', type: 'toggle', val: false, ov: true, profileOnly: true },

  { cat: 'browse', key: 'sources', label: 'Registries', help: 'NNOMM reads the mods you can install from these addresses.', type: 'sources' },
  { cat: 'browse', key: 'hideServer', label: 'Hide mods that are only for servers', type: 'toggle', val: true },

  { cat: 'servers', key: 'showServers', label: 'Show Servers in the sidebar', help: 'Turn this off if you do not use the server list.', type: 'toggle', val: true },
  { cat: 'servers', key: 'joinHelper', label: 'Join helper', help: 'The game cannot join a server from outside by itself. NNOMM installs the small helper mod NOSMR the first time you join from here, and keeps it up to date.', type: 'info' },
  { cat: 'servers', key: 'joinMods', label: 'How to join a server that needs mods', help: 'You can still pick another way for one join, with the arrow beside the Join button.', type: 'select', val: JOIN_MODES[0][1], options: JOIN_MODES.map(m => m[1]) },
  { cat: 'servers', key: 'pingYellow', label: 'Ping is yellow from', type: 'select', val: '100 ms', options: ['Never', '80 ms', '100 ms', '150 ms'] },
  { cat: 'servers', key: 'pingRed', label: 'Ping is red from', type: 'select', val: '200 ms', options: ['Never', '150 ms', '200 ms', '300 ms'] },
  { cat: 'servers', key: 'srvRefresh', label: 'Refresh the list', type: 'select', val: 'Every 30 seconds', options: ['Every 15 seconds', 'Every 30 seconds', 'Only when I ask'] },
  { cat: 'servers', key: 'clearFav', label: 'Favourites and recent servers', help: '2 favourites, 2 recent.', type: 'actions', actions: ['Clear recent', 'Clear favourites'] },

  { cat: 'storage', key: 'store', label: 'Where NNOMM keeps mod files', help: 'Each version of a mod is stored once here, also when several profiles use it. 438 MB used.', type: 'path', val: 'C:\\Users\\you\\AppData\\Roaming\\NNOMM\\mods', actions: ['Move', 'Open'] },
  { cat: 'storage', key: 'cache', label: 'Downloaded files that are no longer in use', help: '212 MB. Old versions that no profile uses.', type: 'actions', actions: ['Clear'] },

  { cat: 'look', key: 'theme', label: 'Theme', type: 'select', val: 'Dark', options: ['Same as Windows', 'Dark', 'Light'] },
  { cat: 'look', key: 'density', label: 'Row height in lists', type: 'select', val: 'Compact', options: ['Compact', 'Comfortable'] },
  { cat: 'look', key: 'textSize', label: 'Text size', type: 'select', val: '100%', options: ['90%', '100%', '110%', '125%'] },
  { cat: 'look', key: 'motion', label: 'Animations', type: 'select', val: 'Same as Windows', options: ['Same as Windows', 'On', 'Off'] },
  { cat: 'look', key: 'tileSkipNuclear', label: 'Leave out "Nuclear" in mod letters', help: 'Mods without a picture show two letters from their name. Many names start with Nuclear. With this on, NuclearMapTrails shows MT, not NM. NuclearMinimap keeps NM.', type: 'toggle', val: false },
  { cat: 'look', key: 'startPage', label: 'NNOMM opens on', type: 'select', val: 'The page I used last', options: ['The page I used last', 'Browse', 'Library'] },

  { cat: 'keys', key: 'keys', type: 'keys' },

  { cat: 'app', key: 'appUpdate', label: 'Update NNOMM', help: 'Version 0.1.0. You have the newest version.', type: 'select', val: 'Tell me', options: ['By itself', 'Tell me', 'Never'], actions: ['Look now'] },
  { cat: 'app', key: 'channel', label: 'Get test versions of NNOMM', type: 'toggle', val: false },
  { cat: 'app', key: 'logs', label: 'Problem report', help: 'A text file with your mod list, versions and the last game log. NNOMM shows it to you before you send it to anyone.', type: 'actions', actions: ['Copy report', 'Open log folder'] },
  { cat: 'app', key: 'credits', label: 'About', help: 'NNOMM is a fork of NOMM by Combat787 (MIT licence). The mod list is NOMNOM by KopterBuzz.', type: 'actions', actions: ['Licences'] },

  { cat: 'danger', key: 'dzSettings', label: 'Reset all NNOMM settings', help: 'Mods and profiles stay.', type: 'actions', actions: ['Reset settings'], danger: true },
  { cat: 'danger', key: 'dzAll', label: 'Remove all mods and the mod loader', help: 'The game goes back to its plain state. NNOMM keeps your profiles, so you can install everything again.', type: 'actions', actions: ['Remove everything'], danger: true },
];
const CATS = [['game', 'Game'], ['mods', 'Mods and updates'], ['profiles', 'Profiles'], ['browse', 'Registry'], ['servers', 'Servers'], ['storage', 'Storage'], ['look', 'Appearance'], ['keys', 'Keyboard'], ['app', 'About and updates'], ['danger', 'Danger zone']];
const KEYS = [['Search or run a command', 'Ctrl K'], ['Start the game', 'Ctrl Enter'], ['Find on this page', '/'], ['Move in a list', 'Up, Down'], ['Turn the mod on or off', 'Space'], ['Open details', 'Enter'], ['Select a range', 'Shift click'], ['Select all', 'Ctrl A'], ['Remove the selected mods', 'Delete'], ['Undo', 'Ctrl Z'], ['Close a panel or window', 'Esc'], ['Confirm a window', 'Enter'], ['Browse, Library, Servers', 'Ctrl 1, 2, 3'], ['Settings', 'Ctrl ,']];
