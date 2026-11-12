// Run from repository root. The mock is trusted project source, never runtime code.
const fs = require('node:fs');
const vm = require('node:vm');
const excluded = ['joinHelper', 'joinMods', 'pingYellow', 'pingRed', 'srvRefresh', 'clearFav', 'tempKeep'];
const source = fs.readFileSync('design/mock/src/data.js', 'utf8');
const extract = 'JSON.stringify({mods: MODS, installed: INST, profiles: PROFILES, notes: NOTES, packImport: PACK_IMPORT, packUpdate: PACK_UPDATE, settings: SETTINGS.filter(s => !' + JSON.stringify(excluded) + '.includes(s.key)), categories: CATS, keys: KEYS})';

const data = JSON.parse(vm.runInNewContext(source + '\n' + extract));
// Safety wording reflects ownership; the original mock predates adopted loaders.
Object.assign(data.settings.find(s => s.key === 'dzAll'), {
  label: 'Remove all mods',
  actions: ['Remove all mods'],
  help: 'Mods that can be downloaded again leave every profile. Other mods stay in the Library, turned off, with their stored copies kept. Only loader files NNOMM installed are removed. An adopted loader, your own files, profiles and recovery copies stay.'
});
data.settings.find(s => s.key === 'logs').help = 'A report with your mod list, versions and the last game log. NNOMM shows it to you before you send it to anyone.';
fs.writeFileSync('core/src/main/resources/nnomm/demo.json', JSON.stringify(data));
fs.writeFileSync('core/src/main/resources/nnomm/settings.json', JSON.stringify({settings:data.settings,categories:data.categories,keys:data.keys}, null, 2) + '\n');
