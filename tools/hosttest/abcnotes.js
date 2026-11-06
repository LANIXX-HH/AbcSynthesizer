// prints notes per voice as abcjs understands them: start(beats) duration pitch
const ABCJS = require('abcjs');
const fs = require('fs');
const abc = fs.readFileSync(process.argv[2], 'utf8');
const tune = ABCJS.parseOnly(abc)[0];
const warn = tune.warnings || [];
const audio = tune.setUpAudio({});
let out = [];
audio.tracks.forEach((tr, i) => {
  const notes = tr.filter(e => e.cmd === 'note').map(e => [Math.round(e.start * 4 * 1000) / 1000, Math.round(e.duration * 4 * 1000) / 1000, e.pitch]);
  out.push(notes);
});
console.log(JSON.stringify({warnings: warn.length, tracks: out}));
