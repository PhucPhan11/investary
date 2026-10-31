#!/usr/bin/env node
// Repository harness checks for Investary (no dependencies; Node is already a project prerequisite).
//
//   node scripts/check-harness.mjs                     run all checks
//   node scripts/check-harness.mjs --update-migrations record checksums of NEW Flyway migrations
//
// Checks:
//   1. sensitive files   git must not track real emails/PDFs or .env files
//   2. migrations        applied Flyway migrations are immutable (checksums in docs/harness/migration-checksums.json)
//   3. docs drift        paths and npm scripts named in agent docs exist; IntelliJ run configs point at real paths
//   4. failure memory    every docs/failures/*.md has "## Prevention" and "## Detection" sections

import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const MIGRATIONS_DIR = join(ROOT, 'backend/src/main/resources/db/migration');
const CHECKSUMS_FILE = join(ROOT, 'docs/harness/migration-checksums.json');
const FIXTURE_DIR = 'backend/src/test/resources/eml/';
const AGENT_DOCS = ['AGENTS.md', 'CLAUDE.md', 'backend/CLAUDE.md', 'frontend/CLAUDE.md'];
// Where short paths in agent docs may be rooted, besides the doc's own folder and the repo root.
const SOURCE_ROOTS = ['backend/src/main/java/com/investary', 'backend/src/test/java/com/investary', 'frontend/src/app'];

const problems = [];
const fail = (check, message) => problems.push(`[${check}] ${message}`);
const read = (path) => readFileSync(join(ROOT, path), 'utf8');

function checkSensitiveFiles() {
  const tracked = execFileSync('git', ['ls-files', '-z'], { cwd: ROOT, encoding: 'utf8' }).split('\0').filter(Boolean);
  for (const file of tracked) {
    const lower = file.toLowerCase();
    if (lower.endsWith('.eml') && !file.startsWith(FIXTURE_DIR)) {
      fail('sensitive-files', `${file}: real emails must not be committed (synthetic fixtures belong in ${FIXTURE_DIR})`);
    }
    if (lower.endsWith('.pdf')) {
      fail('sensitive-files', `${file}: PDFs must not be committed (tests generate them with TestPdfs)`);
    }
    if (/(^|\/)\.env$/.test(lower)) {
      fail('sensitive-files', `${file}: .env files hold secrets; commit .env.example instead`);
    }
  }
}

function sha256(path) {
  // Normalize line endings so the checksum does not depend on git's checkout settings.
  return createHash('sha256').update(readFileSync(path, 'utf8').replace(/\r\n/g, '\n')).digest('hex');
}

function checkMigrations(update) {
  const migrations = readdirSync(MIGRATIONS_DIR).filter((f) => /^V\d+__.+\.sql$/.test(f)).sort((a, b) => version(a) - version(b));
  const recorded = existsSync(CHECKSUMS_FILE) ? JSON.parse(readFileSync(CHECKSUMS_FILE, 'utf8')) : {};
  const versions = new Set();
  let added = 0;

  for (const name of migrations) {
    const v = version(name);
    if (versions.has(v)) {
      fail('migrations', `${name}: duplicate migration version V${v}`);
    }
    versions.add(v);
    const actual = sha256(join(MIGRATIONS_DIR, name));
    if (recorded[name] === undefined) {
      if (update) {
        recorded[name] = actual;
        added++;
      } else {
        fail('migrations', `${name}: not recorded; after reviewing it run "node scripts/check-harness.mjs --update-migrations"`);
      }
    } else if (recorded[name] !== actual) {
      fail('migrations', `${name}: content changed after it was recorded. Never edit an applied migration; add a new V<n>__*.sql instead`);
    }
  }
  for (const name of Object.keys(recorded)) {
    if (!migrations.includes(name)) {
      fail('migrations', `${name}: recorded migration was deleted or renamed`);
    }
  }
  if (update && added > 0) {
    mkdirSync(dirname(CHECKSUMS_FILE), { recursive: true });
    writeFileSync(CHECKSUMS_FILE, JSON.stringify(recorded, null, 2) + '\n');
    console.log(`Recorded ${added} new migration checksum(s) in ${relative(ROOT, CHECKSUMS_FILE)}`);
  }
}

function version(name) {
  return Number(/^V(\d+)__/.exec(name)[1]);
}

function checkDocsDrift() {
  const packageJson = JSON.parse(read('frontend/package.json'));
  const scripts = new Set(Object.keys(packageJson.scripts ?? {}));

  for (const doc of AGENT_DOCS) {
    if (!existsSync(join(ROOT, doc))) {
      fail('docs-drift', `${doc}: agent instruction file is missing`);
      continue;
    }
    const text = read(doc);
    const docDir = dirname(doc);

    for (const [, token] of text.matchAll(/`([^`\n]+)`/g)) {
      if (!isCheckablePath(token)) {
        continue;
      }
      const candidates = [docDir, '.', ...SOURCE_ROOTS].map((base) => join(ROOT, base, token));
      if (!candidates.some((path) => existsSync(path))) {
        fail('docs-drift', `${doc}: referenced path \`${token}\` does not exist`);
      }
    }
    for (const [, script] of text.matchAll(/npm run ([\w:-]+)/g)) {
      if (!scripts.has(script)) {
        fail('docs-drift', `${doc}: "npm run ${script}" is not a script in frontend/package.json`);
      }
    }
    for (const [, script] of text.matchAll(/npm (start|test)\b/g)) {
      if (!scripts.has(script)) {
        fail('docs-drift', `${doc}: "npm ${script}" is not a script in frontend/package.json`);
      }
    }
  }

  const runDir = join(ROOT, '.run');
  if (existsSync(runDir)) {
    for (const file of readdirSync(runDir).filter((f) => f.endsWith('.run.xml'))) {
      const xml = readFileSync(join(runDir, file), 'utf8');
      for (const [, path] of xml.matchAll(/\$PROJECT_DIR\$\/([^"]*)/g)) {
        if (!existsSync(join(ROOT, path))) {
          fail('docs-drift', `.run/${file}: path "${path}" does not exist`);
        }
      }
      for (const [, path] of xml.matchAll(/docker compose -f ([^\s"]+)/g)) {
        if (!existsSync(join(ROOT, path))) {
          fail('docs-drift', `.run/${file}: compose file "${path}" does not exist`);
        }
      }
    }
  }
}

/** A repo-relative file or folder path; commands, URLs, globs, placeholders and git-ignored env files are skipped. */
function isCheckablePath(token) {
  return (
    token.includes('/') &&
    !/[\s<>*…{}$~\\]/.test(token) &&
    !token.includes('://') &&
    !token.startsWith('/') &&
    !token.startsWith('./') &&
    !token.startsWith('-') &&
    !token.endsWith('.env')
  );
}

function checkFailureMemory() {
  const dir = join(ROOT, 'docs/failures');
  if (!existsSync(dir)) {
    return;
  }
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.md'))) {
    const text = readFileSync(join(dir, file), 'utf8');
    for (const section of ['## Prevention', '## Detection']) {
      if (!text.split(/\r?\n/).some((line) => line.trim() === section)) {
        fail('failure-memory', `docs/failures/${file}: missing "${section}" section`);
      }
    }
  }
}

const update = process.argv.includes('--update-migrations');
checkSensitiveFiles();
checkMigrations(update);
checkDocsDrift();
checkFailureMemory();

if (problems.length > 0) {
  console.error(`Harness check failed (${problems.length} problem${problems.length === 1 ? '' : 's'}):`);
  for (const problem of problems) {
    console.error(`  - ${problem}`);
  }
  process.exit(1);
}
console.log('Harness check passed: sensitive files, migrations, docs drift, failure memory.');
