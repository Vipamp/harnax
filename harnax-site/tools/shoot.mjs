// Render forensics: serve the site over loopback HTTP, drive headless Chrome,
// assert the parsed DOM and write one desktop plus one mobile capture per page into .preview/.
// Run: node harnax-site/tools/shoot.mjs [index.html ...]
import { createServer } from 'node:http';
import { readFileSync, existsSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { PAGES, SECTION_IDS } from './check.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = join(ROOT, '.preview');
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.png': 'image/png',
};

// Served in-tree so the iframe is same-origin: it measures whether a page overflows
// the viewport width, which a screenshot of a 12000px canvas cannot show us.
const PROBE = `<!doctype html><meta charset="utf-8"><body><div id="out">pending</div>
<iframe id="f" style="border:0;height:1200px"></iframe>
<script>
var q = new URLSearchParams(location.search);
var w = Number(q.get('w')) || 1440;
var f = document.getElementById('f');
f.style.width = w + 'px';
f.src = q.get('t');
f.onload = function () {
  var d = f.contentDocument, bad = [];
  var over = function (el) { return el.getBoundingClientRect().right > w + 1; };
  d.querySelectorAll('table,svg,.tbl-wrap,.cards,.facts,.articles,.tiers,.hero-grid,.split').forEach(function (el) {
    var ox = getComputedStyle(el).overflowX;
    if (el.scrollWidth > el.clientWidth + 1 && ox !== 'auto' && ox !== 'scroll') {
      bad.push((el.getAttribute('class') || el.tagName) + ':' + el.scrollWidth + '/' + el.clientWidth);
    }
  });
  if (d.documentElement.scrollWidth > w + 1) {
    var wide = [].slice.call(d.querySelectorAll('body *')).filter(function (el) {
      return el.getBoundingClientRect().width > 0 && over(el) && ![].slice.call(el.children).some(over);
    });
    bad.push('doc:' + d.documentElement.scrollWidth + ' [' + wide.slice(0, 4).map(function (el) {
      return el.tagName + '.' + (el.getAttribute('class') || '') + '@' + Math.round(el.getBoundingClientRect().right);
    }).join(' ') + ']');
  }
  // Diagram text is clipped by its own viewBox, so a label that is too long for the
  // drawing simply vanishes; measure it in user units instead of pixels. Vertical
  // extents are not comparable — getBBox reports the font box, not the ink.
  d.querySelectorAll('svg.dgm').forEach(function (svg, i) {
    var vb = svg.viewBox.baseVal, off = [];
    var rects = [].slice.call(svg.querySelectorAll('rect')).map(function (r) {
      return { x: +r.getAttribute('x'), y: +r.getAttribute('y'), w: +r.getAttribute('width'), h: +r.getAttribute('height') };
    });
    var lines = [];
    svg.querySelectorAll('text').forEach(function (t) {
      var b = t.getBBox(), right = b.x + b.width;
      if (right > vb.width - 4 || b.x < -0.5) off.push(t.textContent.slice(0, 14) + '@' + Math.round(right));
      var box = rects.filter(function (r) {
        return b.x >= r.x && b.x < r.x + r.w && b.y + b.height / 2 >= r.y && b.y <= r.y + r.h;
      }).sort(function (p, q) { return p.w * p.h - q.w * q.h; })[0];
      if (box && right > box.x + box.w - 4) {
        off.push('out:' + t.textContent.slice(0, 12) + '@' + Math.round(right) + '/' + Math.round(box.x + box.w - 4));
      }
      lines.push({ n: t.textContent.slice(0, 10), x: b.x, r: right, y: b.y + b.height / 2 });
    });
    for (var a = 0; a < lines.length; a++) {
      for (var c = a + 1; c < lines.length; c++) {
        if (Math.abs(lines[a].y - lines[c].y) < 4 && lines[a].x < lines[c].r - 6 && lines[c].x < lines[a].r - 6) {
          off.push('hit:' + lines[a].n + '/' + lines[c].n);
        }
      }
    }
    if (off.length) bad.push('dgm' + i + ':' + off.join('|'));
  });
  document.getElementById('out').textContent = 'OVERFLOW:' + (bad.length ? bad.join(',') : 'none');
};
</script></body></html>`;

const server = createServer((req, res) => {
  const path = decodeURIComponent(req.url.split('?')[0].split('#')[0]);
  if (path === '/__probe') { res.writeHead(200, { 'content-type': TYPES['.html'] }).end(PROBE); return; }
  const file = join(ROOT, path === '/' ? 'index.html' : path.replace(/^\/+/, ''));
  if (!file.startsWith(ROOT) || !existsSync(file)) { res.writeHead(404).end('not found'); return; }
  res.writeHead(200, { 'content-type': TYPES[extname(file)] || 'application/octet-stream' });
  res.end(readFileSync(file));
});

// Chrome writes the artifact and then keeps running, so wait for the artifact and
// kill the group we spawned — never the user's own Chrome processes.
function chrome(url, extra, expect) {
  const profile = join(tmpdir(), `harnax-site-shot-${process.pid}-${Date.now()}`);
  const shotArg = extra.find((a) => a.startsWith('--screenshot='));
  const shot = shotArg ? shotArg.split('=')[1] : '';
  // A capture left over from a previous run satisfies existsSync at once, which would make
  // us return before Chrome had written this one and then judge a stale image fresh.
  if (shot) rmSync(shot, { force: true });
  return new Promise((resolve) => {
    const child = spawn(CHROME, [
      '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
      `--user-data-dir=${profile}`, '--virtual-time-budget=4000', ...extra, url,
    ], { detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    let err = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { err += d; });
    const done = () => {
      clearInterval(poll);
      setTimeout(() => {
        try { process.kill(-child.pid, 'SIGKILL'); } catch { /* already gone */ }
        rmSync(profile, { recursive: true, force: true });
        resolve({ stdout: out, stderr: err });
      }, 300);
    };
    let waited = 0;
    const poll = setInterval(() => {
      waited += 250;
      const ready = shot ? existsSync(shot) : expect ? out.includes(expect) : out.length > 400;
      if (ready || waited > 30000) done();
    }, 250);
  });
}

const only = process.argv.slice(2).filter((a) => !a.startsWith('-'));
const targets = only.length ? only : [...PAGES, ...PAGES.map((p) => `en/${p}`)];
mkdirSync(OUT, { recursive: true });
const fails = [];
let count = 0;

await new Promise((res) => server.listen(8123, '127.0.0.1', res));

for (const page of targets) {
  if (!existsSync(join(ROOT, page))) { console.log(`skip ${page}: not written yet`); continue; }
  const key = page.replace(/^en\//, '');
  const slug = page.replace(/^en\//, 'en-').replace('.html', '');
  const ids = SECTION_IDS[key] || [];

  const dom = await chrome(`http://127.0.0.1:8123/${page}`, ['--dump-dom']);
  if (!dom.stdout.includes('<section')) fails.push(`${page}: dump-dom produced no <section>`);
  for (const bad of ['TBD', 'TODO', 'Lorem', '{{']) {
    if (dom.stdout.includes(bad)) fails.push(`${page}: placeholder ${bad} in DOM`);
  }
  for (const id of ids) if (!dom.stdout.includes(`id="${id}"`)) fails.push(`${page}: missing #${id}`);
  if (!/<html[^>]*class="js/.test(dom.stdout)) fails.push(`${page}: site.js did not run (no html.js)`);

  for (const w of [1440, 390]) {
    const probe = await chrome(`http://127.0.0.1:8123/__probe?t=/${page}&w=${w}`, ['--dump-dom'], 'OVERFLOW:');
    const got = (probe.stdout.match(/OVERFLOW:[^<&]*/) || ['OVERFLOW:never-measured'])[0];
    console.log(`  ${page} @${w}px -> ${got}`);
    if (!got.endsWith(':none')) fails.push(`${page} @${w}px ${got}`);
  }

  // One tall capture per page: --screenshot ignores the URL fragment, so per-band
  // shots were all the top of the page. The bands themselves are asserted on the DOM above.
  const desktop = join(OUT, `${slug}-desktop.png`);
  await chrome(`http://127.0.0.1:8123/${page}`, ['--window-size=1440,12000', `--screenshot=${desktop}`]);
  if (!existsSync(desktop)) fails.push(`${slug}: no desktop screenshot written`);
  count++;
  const mobile = join(OUT, `${slug}-mobile.png`);
  await chrome(`http://127.0.0.1:8123/${page}`, ['--window-size=390,16000', `--screenshot=${mobile}`]);
  if (!existsSync(mobile)) fails.push(`${slug}: no mobile screenshot written`);
  count++;
  console.log(`${page}: 2 shots, dom ${dom.stdout.length} bytes`);
}

server.close();
for (const f of fails) console.log('  - ' + f);
if (fails.length) { console.log(`render forensics: FAIL (${count} shots)`); process.exit(1); }
writeFileSync(join(OUT, 'index.txt'), `${count} shots\n`);
console.log(`render forensics: PASS — ${count} shots -> ${OUT}`);
