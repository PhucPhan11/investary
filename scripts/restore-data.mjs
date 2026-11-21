#!/usr/bin/env node
// Restores an archive made by scripts/backup-data.mjs: REPLACES the database and both file stores.
//
//   node scripts/restore-data.mjs <archive.tar.gz>          shows what would be replaced, changes nothing
//   node scripts/restore-data.mjs <archive.tar.gz> --yes    restores
//
// Before restoring, the current data is backed up to ~/investary-backups (or --safety-out <folder>) as
// investary-pre-restore-*.tar.gz. The backend must be stopped; PostgreSQL must be running. When the code is newer
// than the backup, Flyway migrates the restored database forward on the next backend start.
// Options for unusual setups: --container <name> --db-user <user> --db-name <db>.

import {
  DEFAULT_BACKUP_DIR, backup, counts, countFiles, extract, fail, parseArgs, portInUse, replaceFolder, requireContainer,
  restoreDatabase, settings,
} from './lib/investary-data.mjs';
import { rmSync } from 'node:fs';
import { join } from 'node:path';

const args = parseArgs(process.argv.slice(2));
const archive = args._[0];
if (!archive || args.help) {
  console.log('Usage: node scripts/restore-data.mjs <archive.tar.gz> [--yes] [--safety-out <folder>] [--container <name>]');
  process.exit(archive ? 0 : 1);
}
const cfg = settings(args);
requireContainer(cfg);
if (await portInUse(cfg.backendPort)) {
  fail(`the backend is running on port ${cfg.backendPort}; stop it before restoring.`);
}

const { dir, manifest } = extract(archive);
try {
  await restore(dir, manifest);
} catch (error) {
  console.error(`Error: ${error.message}`);
  process.exitCode = 1;
} finally {
  rmSync(dir, { recursive: true, force: true });
}

async function restore(dir, manifest) {
  const current = counts(cfg);
  console.log(`Backup from ${manifest.createdAt}: ${manifest.database.emails} emails, ${manifest.database.documents} documents, `
    + `schema V${manifest.database.flywayVersion}, ${manifest.files.documents + manifest.files.emails} files.`);
  console.log(`This replaces database "${cfg.dbName}" in container "${cfg.container}" (now ${current.emails} emails, ${current.documents} documents)`);
  console.log(`and the folders:\n  ${cfg.documentsRoot}\n  ${cfg.emailsRoot}`);
  if (!args.yes) {
    console.log('Nothing changed. Run again with --yes to restore.');
    return;
  }

  const safety = await backup(cfg, args['safety-out'] ?? DEFAULT_BACKUP_DIR, 'investary-pre-restore');
  console.log(`Current data saved first: ${safety.archive}`);

  await restoreDatabase(cfg, join(dir, 'database.dump'));
  replaceFolder(join(dir, 'documents'), cfg.documentsRoot);
  replaceFolder(join(dir, 'emails'), cfg.emailsRoot);

  const restored = counts(cfg);
  const files = { documents: countFiles(cfg.documentsRoot), emails: countFiles(cfg.emailsRoot) };
  console.log(`Restored: ${restored.emails} emails, ${restored.documents} documents, schema V${restored.flywayVersion}, `
    + `${files.documents} library files, ${files.emails} email files.`);
  if (restored.emails !== manifest.database.emails || restored.documents !== manifest.database.documents
    || files.documents !== manifest.files.documents || files.emails !== manifest.files.emails) {
    throw new Error('restored counts differ from the backup manifest; check the output above.');
  }
  console.log('Start the backend as usual. If Gmail was connected, it only works with the same INVESTARY_GMAIL_TOKEN_KEY;');
  console.log('otherwise reconnect Gmail in the Automation Center.');
}
