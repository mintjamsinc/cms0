#!/usr/bin/env node
// Build driver: one type-check, then one rollup process per target.
//
// rollup.config.js is a list of independent targets (the shell and one
// bundle per app). Running them all in a single rollup process does not
// scale: each target's TypeScript plugin builds a full program over src/ and
// the type definitions it pulls in, and a finished target's program is not
// released until the process exits, so the heap grows by roughly a gigabyte
// per target and a full build eventually dies of "heap out of memory" no
// matter how large --max-old-space-size is set. A target on its own fits in
// well under 2 GB, so this script gives each one its own process and runs a
// few of them side by side.
//
// Type checking is done once here, over the whole of src/, with tsc. The
// rollup targets only transpile (see tsPlugin in rollup.config.js), so a
// full build checks the sources exactly once instead of once per target.
//
// Usage:
//   node scripts/build.mjs [--prod] [--jobs N] [--no-check] [target ...]
//
//   --prod       production build (BUILD=production); development otherwise
//   --jobs N     targets built at the same time (default: BUILD_JOBS or 3)
//   --no-check   skip the tsc pass (the rollup targets do not type-check)
//   target ...   the targets to build, as named in rollup.config.js; all
//                targets when none is given
//
// Every target of one invocation shares the same BUILD_VERSION cache-busting
// stamp, as a single-process build did.
import { spawn } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const isWindows = process.platform === 'win32';

// ---- arguments ----

const args = process.argv.slice(2);
let production = false;
let check = true;
let jobs = Number(process.env.BUILD_JOBS) || 3;
const targets = [];
for (let i = 0; i < args.length; i++) {
	const a = args[i];
	if (a === '--prod' || a === '--production') production = true;
	else if (a === '--dev' || a === '--development') production = false;
	else if (a === '--no-check') check = false;
	else if (a === '--jobs' || a === '-j') jobs = Number(args[++i]);
	else if (a.startsWith('--jobs=')) jobs = Number(a.slice('--jobs='.length));
	else if (a.startsWith('-')) fail(`Unknown option: ${a}`);
	else targets.push(a);
}
if (!Number.isInteger(jobs) || jobs < 1) fail(`--jobs must be a positive integer, got ${jobs}`);

const buildVersion = Date.now().toString(36);
const mode = production ? 'production' : 'development';
// Set before the config is imported: the config makes and logs a stamp of
// its own when none is given.
process.env.BUILD_VERSION = buildVersion;

// The target names come from rollup.config.js itself, so a target added
// there is built here without a second list to keep in sync.
const { TARGET_NAMES } = await import(pathToFileURL(resolve(root, 'rollup.config.js')).href);
const unknown = targets.filter(t => !TARGET_NAMES.includes(t));
if (unknown.length) fail(`Unknown target(s): ${unknown.join(', ')}\nKnown targets: ${TARGET_NAMES.join(', ')}`);
const selected = targets.length ? TARGET_NAMES.filter(t => targets.includes(t)) : TARGET_NAMES.slice();

const env = { ...process.env, BUILD: mode, BUILD_VERSION: buildVersion };
delete env.TARGETS;

console.log(`[build] ${mode}, ${selected.length} target(s), ${Math.min(jobs, selected.length)} at a time, BUILD_VERSION=${buildVersion}`);

// ---- type check ----

if (check) {
	const started = Date.now();
	const code = await run('tsc', ['--noEmit', '-p', 'tsconfig.json'], { prefix: 'tsc' });
	if (code !== 0) fail(`[build] type check failed (${elapsed(started)})`, code);
	console.log(`[build] type check passed (${elapsed(started)})`);
}

// ---- targets ----

const failures = [];
const queue = selected.slice();
const startedAll = Date.now();
await Promise.all(Array.from({ length: Math.min(jobs, queue.length) }, worker));

if (failures.length) {
	fail(`[build] FAILED: ${failures.join(', ')} (${elapsed(startedAll)})`);
}
console.log(`[build] done: ${selected.length} target(s) in ${elapsed(startedAll)}`);

async function worker() {
	while (queue.length) {
		const target = queue.shift();
		const started = Date.now();
		const code = await run('rollup', ['-c'], { prefix: target, env: { ...env, TARGETS: target } });
		if (code !== 0) {
			failures.push(target);
			console.error(`[build] ${target}: exit ${code} (${elapsed(started)})`);
		}
	}
}

// ---- helpers ----

// Run a node_modules binary, prefixing every output line with the target so
// the interleaved output of parallel builds stays readable.
function run(bin, binArgs, { prefix, env: childEnv = env }) {
	return new Promise((resolveRun) => {
		const cmd = resolve(root, 'node_modules', '.bin', isWindows ? `${bin}.cmd` : bin);
		const child = spawn(cmd, binArgs, { cwd: root, env: childEnv, stdio: ['ignore', 'pipe', 'pipe'], shell: isWindows });
		const forward = (stream, out) => {
			let rest = '';
			stream.on('data', (chunk) => {
				rest += chunk.toString();
				const lines = rest.split(/\r?\n/);
				rest = lines.pop();
				for (const line of lines) if (line.trim()) out(`[${prefix}] ${line}`);
			});
			stream.on('end', () => { if (rest.trim()) out(`[${prefix}] ${rest}`); });
		};
		forward(child.stdout, (l) => console.log(l));
		forward(child.stderr, (l) => console.error(l));
		child.on('error', (e) => { console.error(`[${prefix}] ${e.message}`); resolveRun(1); });
		child.on('close', (code) => resolveRun(code ?? 1));
	});
}

function elapsed(since) {
	return `${((Date.now() - since) / 1000).toFixed(1)}s`;
}

function fail(message, code = 1) {
	console.error(message);
	process.exit(code);
}
