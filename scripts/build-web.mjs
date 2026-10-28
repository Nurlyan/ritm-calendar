// Copies the static site into www/ for Capacitor. The site itself stays in the repo root for GitHub Pages.
import {cpSync,mkdirSync,rmSync,readdirSync,statSync} from 'node:fs';
import {join,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
const root=join(dirname(fileURLToPath(import.meta.url)),'..'),out=join(root,'www');
const skip=new Set(['node_modules','android','www','dist','scripts','.git','.github','package.json','package-lock.json','capacitor.config.json']);
const keep=name=>!skip.has(name)&&!name.startsWith('.')&&!/\.(sql|md|mjs)$/.test(name);
rmSync(out,{recursive:true,force:true});mkdirSync(out);
for(const name of readdirSync(root).filter(keep)){cpSync(join(root,name),join(out,name),{recursive:statSync(join(root,name)).isDirectory()})}
console.log('www:',readdirSync(out).join(', '));
