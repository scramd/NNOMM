// Run from repository root. The mock is trusted project source, never runtime code.
const fs = require('node:fs');
const vm = require('node:vm');
const excluded = ['joinHelper', 'joinMods', 'pingYellow', 'pingRed', 'srvRefresh', 'clearFav', 'tempKeep'];
const source = fs.readFileSync('design/mock/src/data.js', 'utf8');
const extract = 'JSON.stringify({mods: MODS, installed: INST, profiles: PROFILES, notes: NOTES, packImport: PACK_IMPORT, packUpdate: PACK_UPDATE, settings: SETTINGS.filter(s => !' + JSON.stringify(excluded) + '.includes(s.key)), categories: CATS, keys: KEYS})';
fs.writeFileSync('core/src/main/resources/nnomm/demo.json', vm.runInNewContext(source + '\n' + extract));
