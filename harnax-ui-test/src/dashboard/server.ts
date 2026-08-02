/**
 * Local dashboard for harnax-ui-test.
 *
 * Zero-dependency Node HTTP server:
 *   - GET  /                      dashboard page (public/index.html)
 *   - POST /api/run               trigger `playwright test` (one run at a time)
 *   - GET  /api/status            current run state + live log tail
 *   - GET  /api/runs              history list (artifacts/runs/*.json)
 *   - GET  /api/runs/:id          one run's full result
 *   - GET  /artifacts/*           screenshots & traces (static)
 *
 * Start: npm run dashboard  →  http://localhost:4600
 */
import 'dotenv/config';
import { spawn, type ChildProcess } from 'node:child_process';
import { createServer, type ServerResponse } from 'node:http';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync, createReadStream, statSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';

const PORT = Number(process.env.DASHBOARD_PORT ?? 4600);
const ROOT = process.cwd();
const RUNS_DIR = join(ROOT, 'artifacts', 'runs');
mkdirSync(RUNS_DIR, { recursive: true });

// ── run state ──
interface RunState {
  id: string;
  status: 'idle' | 'running' | 'passed' | 'failed' | 'error';
  startedAt: string | null;
  finishedAt: string | null;
  log: string[];
}

let current: RunState = { id: '', status: 'idle', startedAt: null, finishedAt: null, log: [] };
let child: ChildProcess | null = null;

function startRun(grep?: string): { ok: boolean; message: string } {
  if (current.status === 'running') return { ok: false, message: 'a run is already in progress' };

  const id = new Date().toISOString().replace(/[:.]/g, '-');
  current = { id, status: 'running', startedAt: new Date().toISOString(), finishedAt: null, log: [] };

  const args = ['playwright', 'test'];
  if (grep) args.push('--grep', grep);

  child = spawn('npx', args, { cwd: ROOT, env: process.env, shell: process.platform === 'win32' });
  const append = (chunk: Buffer) => {
    for (const line of chunk.toString().split('\n')) {
      if (line.trim()) current.log.push(line);
    }
    if (current.log.length > 800) current.log = current.log.slice(-500);
  };
  child.stdout?.on('data', append);
  child.stderr?.on('data', append);

  child.on('close', (code) => {
    current.finishedAt = new Date().toISOString();
    current.status = code === 0 ? 'passed' : 'failed';
    child = null;
    persistRun();
  });
  child.on('error', (err) => {
    current.finishedAt = new Date().toISOString();
    current.status = 'error';
    current.log.push(`spawn error: ${err.message}`);
    child = null;
    persistRun();
  });

  return { ok: true, message: `run ${id} started` };
}

/** After a run, merge playwright's json report with our run metadata and archive it. */
function persistRun(): void {
  let report: unknown = null;
  const reportPath = join(ROOT, 'artifacts', 'last-run.json');
  if (existsSync(reportPath)) {
    try {
      report = JSON.parse(readFileSync(reportPath, 'utf-8'));
    } catch {
      report = null;
    }
  }
  const record = {
    id: current.id,
    status: current.status,
    startedAt: current.startedAt,
    finishedAt: current.finishedAt,
    log: current.log.slice(-200),
    report,
  };
  writeFileSync(join(RUNS_DIR, `${current.id}.json`), JSON.stringify(record));
}

function listRuns(): { id: string; status: string; startedAt: string | null; finishedAt: string | null; summary: { total: number; passed: number; failed: number; skipped: number } }[] {
  return readdirSync(RUNS_DIR)
    .filter((f) => f.endsWith('.json'))
    .sort()
    .reverse()
    .slice(0, 50)
    .map((f) => {
      try {
        const r = JSON.parse(readFileSync(join(RUNS_DIR, f), 'utf-8'));
        return {
          id: r.id,
          status: r.status,
          startedAt: r.startedAt,
          finishedAt: r.finishedAt,
          summary: summarize(r.report),
        };
      } catch {
        return { id: f.replace('.json', ''), status: 'error', startedAt: null, finishedAt: null, summary: { total: 0, passed: 0, failed: 0, skipped: 0 } };
      }
    });
}

interface PwStats { expected?: number; unexpected?: number; skipped?: number; flaky?: number }
function summarize(report: unknown): { total: number; passed: number; failed: number; skipped: number } {
  const stats = (report as { stats?: PwStats } | null)?.stats;
  if (!stats) return { total: 0, passed: 0, failed: 0, skipped: 0 };
  const passed = stats.expected ?? 0;
  const failed = stats.unexpected ?? 0;
  const skipped = stats.skipped ?? 0;
  return { total: passed + failed + skipped + (stats.flaky ?? 0), passed, failed, skipped };
}

// ── http plumbing ──
const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webm': 'video/webm',
  '.zip': 'application/zip',
};

function sendJson(res: ServerResponse, code: number, body: unknown): void {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

function sendFile(res: ServerResponse, path: string): void {
  if (!existsSync(path) || !statSync(path).isFile()) {
    res.writeHead(404).end('not found');
    return;
  }
  res.writeHead(200, { 'Content-Type': MIME[extname(path)] ?? 'application/octet-stream' });
  createReadStream(path).pipe(res);
}

const server = createServer((req, res) => {
  const url = new URL(req.url ?? '/', `http://localhost:${PORT}`);
  const path = url.pathname;

  if (path === '/' || path === '/index.html') {
    return sendFile(res, join(ROOT, 'src', 'dashboard', 'public', 'index.html'));
  }
  if (path === '/api/run' && req.method === 'POST') {
    const grep = url.searchParams.get('grep') ?? undefined;
    return sendJson(res, 200, startRun(grep));
  }
  if (path === '/api/status') {
    return sendJson(res, 200, { ...current, log: current.log.slice(-100) });
  }
  if (path === '/api/runs') {
    return sendJson(res, 200, listRuns());
  }
  if (path.startsWith('/api/runs/')) {
    const id = path.slice('/api/runs/'.length).replace(/[^\w-]/g, '');
    const file = join(RUNS_DIR, `${id}.json`);
    if (!existsSync(file)) return sendJson(res, 404, { error: 'run not found' });
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return createReadStream(file).pipe(res);
  }
  if (path.startsWith('/artifacts/')) {
    // static artifacts (screenshots, traces); prevent path escape
    const rel = normalize(decodeURIComponent(path)).replace(/^(\.\.[/\\])+/, '');
    const abs = join(ROOT, rel);
    if (!abs.startsWith(join(ROOT, 'artifacts'))) {
      res.writeHead(403).end('forbidden');
      return;
    }
    return sendFile(res, abs);
  }
  res.writeHead(404).end('not found');
});

server.listen(PORT, () => {
  console.log(`harnax-ui-test dashboard: http://localhost:${PORT}`);
});
