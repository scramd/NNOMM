// Run from repository root. The mock is trusted project source, never runtime code.
const fs = require('node:fs');
const vm = require('node:vm');
const excluded = ['joinHelper', 'joinMods', 'pingYellow', 'pingRed', 'srvRefresh', 'clearFav', 'tempKeep'];
const source = fs.readFileSync('design/mock/src/data.js', 'utf8');
const extract = 'JSON.stringify({mods: MODS, installed: INST, profiles: PROFILES, notes: NOTES, packImport: PACK_IMPORT, packUpdate: PACK_UPDATE, settings: SETTINGS.filter(s => !' + JSON.stringify(excluded) + '.includes(s.key)), categories: CATS, keys: KEYS})';

const data = JSON.parse(vm.runInNewContext(source + '\n' + extract));
// Safety wording reflects ownership; the original mock predates adopted loaders.
Object.assign(data.settings.find(s => s.key === 'dzAll'), {
  label: 'Remove managed mods and NNOMM-installed loader files',
  help: 'Removes managed mods and only loader files NNOMM installed. An adopted loader and your own files stay. NNOMM keeps your profiles and recovery copies.'
});
fs.writeFileSync('core/src/main/resources/nnomm/demo.json', JSON.stringify(data));
fs.writeFileSync('core/src/main/resources/nnomm/settings.json', JSON.stringify({settings:data.settings,categories:data.categories,keys:data.keys}, null, 2) + '\n');
