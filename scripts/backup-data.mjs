#!/usr/bin/env node
// Backs up Investary's data (database + library PDFs + original emails) into one archive, to move it to another
// device or keep a copy. The archive holds personal data and third-party research: keep it private and never
// commit it (the public repository is for code only).
//
//   node scripts/backup-data.mjs [--out <folder>]      default folder: ~/investary-backups
//
// Options for unusual setups: --container <name> --db-user <user> --db-name <db>. Storage roots come from
// INVESTARY_STORAGE_ROOT / INVESTARY_EMAIL_STORAGE_ROOT (default ~/.investary/documents and ~/.investary/emails).
// Restore with scripts/restore-data.mjs.

import { DEFAULT_BACKUP_DIR, backup, formatSize, parseArgs, portInUse, requireContainer, settings } from './lib/investary-data.mjs';
import { statSync } from 'node:fs';

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  console.log('Usage: node scripts/backup-data.mjs [--out <folder>] [--container <name>] [--db-user <user>] [--db-name <db>]');
  process.exit(0);
}
const cfg = settings(args);
requireContainer(cfg);
if (await portInUse(cfg.backendPort)) {
  console.warn(`Warning: the backend is running on port ${cfg.backendPort}. Background jobs may change data during the backup;`);
  console.warn('stop it first for a fully consistent copy.');
}

const { archive, manifest } = await backup(cfg, args.out ?? DEFAULT_BACKUP_DIR);
console.log(`Backup written: ${archive} (${formatSize(statSync(archive).size)})`);
console.log(`  database: ${manifest.database.emails} emails, ${manifest.database.documents} documents, schema V${manifest.database.flywayVersion}`);
console.log(`  files:    ${manifest.files.documents} library files, ${manifest.files.emails} email files`);
console.log('This archive contains your emails and research reports. Keep it private (OneDrive, Google Drive, USB);');
console.log('never commit it. On the other device: node scripts/restore-data.mjs <archive> --yes');
