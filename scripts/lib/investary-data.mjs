// Shared helpers for scripts/backup-data.mjs and scripts/restore-data.mjs (no dependencies).
//
// A backup archive holds everything needed to move Investary to another device:
//   database.dump   pg_dump custom format of the whole database (schema, Flyway history, data)
//   documents/      library PDFs  (investary.storage.root)
//   emails/         original EMLs (investary.email.storage.root)
//   manifest.json   counts and the dump checksum
// The archive contains personal data (emails) and third-party research. It must never be committed:
// the scripts write it only outside the repository or into a git-ignored folder (default data/backups), and the
// harness fails on tracked backups or anything tracked under data/.
//
// Local data lives in <repo>/data/ (documents, emails, tmp, backups), matching the dev profile defaults in
// backend/src/main/resources/application-dev.yml. The database stays in the Docker volume.

import { createHash } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import {
  cpSync, createReadStream, createWriteStream, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, renameSync, rmSync,
  statSync, writeFileSync,
} from 'node:fs';
import { createConnection } from 'node:net';
import { tmpdir } from 'node:os';
import { basename, dirname, join, relative, resolve, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const DATA_DIR = join(ROOT, 'data');
export const DEFAULT_BACKUP_DIR = join(DATA_DIR, 'backups');
// The dev backend runs in backend/, so relative storage paths in the environment are relative to that folder.
const BACKEND_DIR = join(ROOT, 'backend');
const FORMAT_VERSION = 1;

/** Parses `--name value` / `--flag` arguments; positional arguments go to `_`. */
export function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (!token.startsWith('--')) {
      args._.push(token);
    } else if (i + 1 < argv.length && !argv[i + 1].startsWith('--')) {
      args[token.slice(2)] = argv[++i];
    } else {
      args[token.slice(2)] = true;
    }
  }
  return args;
}

/** Reads KEY=value lines from infrastructure/.env (missing file → empty). Values are never printed. */
function readDotEnv() {
  const file = join(ROOT, 'infrastructure/.env');
  const values = {};
  if (!existsSync(file)) {
    return values;
  }
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/.exec(line);
    if (match && !line.trimStart().startsWith('#')) {
      values[match[1]] = match[2].replace(/^(['"])(.*)\1$/, '$2');
    }
  }
  return values;
}

/** Connection and storage settings: command-line options, then the environment, then infrastructure/.env, then defaults. */
export function settings(args = {}) {
  const env = { ...readDotEnv(), ...process.env };
  return {
    container: args.container ?? 'investary-postgres',
    dbUser: args['db-user'] ?? env.POSTGRES_USER ?? 'investary',
    dbName: args['db-name'] ?? env.POSTGRES_DB ?? 'investary',
    documentsRoot: env.INVESTARY_STORAGE_ROOT ? resolve(BACKEND_DIR, env.INVESTARY_STORAGE_ROOT) : join(DATA_DIR, 'documents'),
    emailsRoot: env.INVESTARY_EMAIL_STORAGE_ROOT ? resolve(BACKEND_DIR, env.INVESTARY_EMAIL_STORAGE_ROOT) : join(DATA_DIR, 'emails'),
    backendPort: Number(args['backend-port'] ?? 8080),
  };
}

export function fail(message) {
  console.error(`Error: ${message}`);
  process.exit(1);
}

/** True when `path` is inside the repository and git would track files written there (not ignored). */
export function trackableInRepository(path) {
  const target = resolve(path);
  const rel = relative(ROOT, target);
  if (rel.startsWith('..') || isAbsolute(rel)) {
    return false;
  }
  if (rel === '') {
    return true;
  }
  try {
    // Exit 0 = ignored. Probe a neutral file name inside the folder, so folder rules ("/data/") apply but the
    // file-name rules for backups (investary-backup-*, *.dump) do not make every folder look ignored.
    execFileSync('git', ['check-ignore', '-q', join(rel, 'folder-ignore-probe.txt').replace(/\\/g, '/')], { cwd: ROOT, stdio: 'ignore' });
    return false;
  } catch {
    return true;
  }
}

/** Resolves to true when something accepts TCP connections on localhost:port. */
export function portInUse(port) {
  return new Promise((done) => {
    const socket = createConnection({ host: '127.0.0.1', port });
    socket.setTimeout(1000);
    socket.once('connect', () => { socket.destroy(); done(true); });
    socket.once('timeout', () => { socket.destroy(); done(false); });
    socket.once('error', () => done(false));
  });
}

export function requireContainer(cfg) {
  try {
    const running = execFileSync('docker', ['inspect', '-f', '{{.State.Running}}', cfg.container], { encoding: 'utf8' }).trim();
    if (running !== 'true') {
      fail(`container "${cfg.container}" is not running. Start it with: docker compose -f infrastructure/docker-compose.yml up -d --wait`);
    }
  } catch {
    fail(`container "${cfg.container}" was not found (is Docker running?). Start it with: docker compose -f infrastructure/docker-compose.yml up -d --wait`);
  }
}

/** Runs one SQL statement through psql in the container and returns the trimmed text output. */
export function sql(cfg, statement) {
  return execFileSync('docker', ['exec', cfg.container, 'psql', '-U', cfg.dbUser, '-d', cfg.dbName, '-v', 'ON_ERROR_STOP=1', '-tAc', statement],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

/** Runs `docker <args>`, streaming stdin from / stdout to files when given; rejects with stderr on a non-zero exit. */
function docker(args, { stdinFile, stdoutFile } = {}) {
  return new Promise((done, reject) => {
    const child = spawn('docker', args, { stdio: [stdinFile ? 'pipe' : 'ignore', stdoutFile ? 'pipe' : 'ignore', 'pipe'] });
    let stderr = '';
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    // The pipe ends the file stream by itself; wait for it to be flushed and closed as well as for the process.
    let written = Promise.resolve();
    if (stdoutFile) {
      const output = createWriteStream(stdoutFile);
      written = new Promise((closed, broken) => { output.once('close', closed); output.once('error', broken); });
      child.stdout.pipe(output);
    }
    if (stdinFile) {
      createReadStream(stdinFile).pipe(child.stdin);
    }
    child.once('error', reject);
    child.once('close', (code) => {
      written.then(() => (code === 0
        ? done(stderr)
        : reject(new Error(`docker ${args.slice(0, 3).join(' ')} exited with ${code}\n${stderr.trim()}`))), reject);
    });
  });
}

/** The system tar. On Windows prefer the bundled bsdtar, which accepts drive-letter paths (Git Bash's GNU tar does not). */
function tarCommand() {
  if (process.platform === 'win32') {
    const bsdtar = join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'tar.exe');
    if (existsSync(bsdtar)) {
      return bsdtar;
    }
  }
  return 'tar';
}

export function sha256File(path) {
  const hash = createHash('sha256');
  hash.update(readFileSync(path));
  return hash.digest('hex');
}

function countFiles(dir) {
  if (!existsSync(dir)) {
    return 0;
  }
  let count = 0;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    count += entry.isDirectory() ? countFiles(join(dir, entry.name)) : 1;
  }
  return count;
}

function counts(cfg) {
  const number = (statement) => Number(sql(cfg, statement) || 0);
  const tableExists = (name) => sql(cfg, `select to_regclass('public.${name}') is not null`) === 't';
  return {
    flywayVersion: tableExists('flyway_schema_history')
      ? sql(cfg, "select coalesce(max(version::int), 0) from flyway_schema_history where success and version is not null")
      : null,
    documents: tableExists('document') ? number('select count(*) from document') : 0,
    emails: tableExists('source_messages') ? number('select count(*) from source_messages') : 0,
  };
}

/** Local time as yyyyMMdd-HHmmss, for file names the user recognises. */
function timestamp() {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
}

export function formatSize(bytes) {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`;
}

/**
 * Writes `<outDir>/<prefix>-<timestamp>.tar.gz` with the database dump, both file stores and a manifest.
 * Returns the archive path and the manifest.
 */
export async function backup(cfg, outDir, prefix = 'investary-backup') {
  const target = resolve(outDir);
  if (trackableInRepository(target)) {
    fail(`refusing to write a backup to ${target}: git would track it and it contains personal data. `
      + 'Use the default data/backups, another git-ignored folder, or a folder outside the repository.');
  }
  mkdirSync(target, { recursive: true });
  const staging = mkdtempSync(join(tmpdir(), 'investary-backup-'));
  try {
    const dump = join(staging, 'database.dump');
    await docker(['exec', cfg.container, 'pg_dump', '-U', cfg.dbUser, '-d', cfg.dbName, '-Fc', '--no-owner', '--no-privileges'], { stdoutFile: dump });
    for (const [name, source] of [['documents', cfg.documentsRoot], ['emails', cfg.emailsRoot]]) {
      if (existsSync(source)) {
        cpSync(source, join(staging, name), { recursive: true });
      } else {
        mkdirSync(join(staging, name));
      }
    }
    const manifest = {
      format: FORMAT_VERSION,
      createdAt: new Date().toISOString(),
      database: { ...counts(cfg), dumpSha256: sha256File(dump), dumpBytes: statSync(dump).size },
      files: { documents: countFiles(join(staging, 'documents')), emails: countFiles(join(staging, 'emails')) },
    };
    writeFileSync(join(staging, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');

    const archive = join(target, `${prefix}-${timestamp()}.tar.gz`);
    execFileSync(tarCommand(), ['-czf', archive, '-C', staging, '.'], { stdio: 'inherit' });
    return { archive, manifest };
  } finally {
    rmSync(staging, { recursive: true, force: true });
  }
}

/** Extracts an archive into a new temporary folder and verifies its manifest. The caller removes the folder. */
export function extract(archive) {
  if (!existsSync(archive)) {
    fail(`archive not found: ${archive}`);
  }
  const dir = mkdtempSync(join(tmpdir(), 'investary-restore-'));
  execFileSync(tarCommand(), ['-xzf', resolve(archive), '-C', dir], { stdio: 'inherit' });
  const manifestFile = join(dir, 'manifest.json');
  if (!existsSync(manifestFile) || !existsSync(join(dir, 'database.dump'))) {
    rmSync(dir, { recursive: true, force: true });
    fail(`${basename(archive)} is not an Investary backup (manifest.json or database.dump missing)`);
  }
  const manifest = JSON.parse(readFileSync(manifestFile, 'utf8'));
  if (manifest.format !== FORMAT_VERSION) {
    rmSync(dir, { recursive: true, force: true });
    fail(`unsupported backup format ${manifest.format}`);
  }
  if (sha256File(join(dir, 'database.dump')) !== manifest.database.dumpSha256) {
    rmSync(dir, { recursive: true, force: true });
    fail('database.dump checksum does not match the manifest; the archive is damaged');
  }
  return { dir, manifest };
}

/** Replaces the database with the dump (drops and recreates every object in it). */
export async function restoreDatabase(cfg, dumpFile) {
  await docker(['exec', '-i', cfg.container, 'pg_restore', '-U', cfg.dbUser, '-d', cfg.dbName,
    '--clean', '--if-exists', '--no-owner', '--no-privileges', '--single-transaction', '--exit-on-error'], { stdinFile: dumpFile });
}

/** Replaces the contents of `root` with `source`; the old folder is moved aside and only deleted after the copy succeeded. */
export function replaceFolder(source, root) {
  const old = `${root}.pre-restore-${timestamp()}`;
  const hadOld = existsSync(root);
  if (hadOld) {
    renameSync(root, old);
  }
  try {
    mkdirSync(dirname(root), { recursive: true });
    cpSync(source, root, { recursive: true });
  } catch (error) {
    rmSync(root, { recursive: true, force: true });
    if (hadOld) {
      renameSync(old, root);
    }
    throw error;
  }
  if (hadOld) {
    rmSync(old, { recursive: true, force: true });
  }
}

export { counts, countFiles, timestamp };
