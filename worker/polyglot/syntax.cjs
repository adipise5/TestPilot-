const fs = require('node:fs');
const babel = require('/opt/testpilot-js/node_modules/@babel/core');
for (const path of process.argv.slice(2)) {
  babel.parseSync(fs.readFileSync(path, 'utf8'), {
    filename: path, babelrc: false, configFile: false, sourceType: 'unambiguous',
  });
}
