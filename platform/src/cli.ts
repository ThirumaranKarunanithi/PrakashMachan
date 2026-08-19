/**
 * CLI: run the full import pipeline on a GL + TB file pair.
 *
 *   node dist/cli.js import-gl --gl <file.csv> --tb <file.csv> --mapping <profile.json> --out <dir>
 *
 * Writes to <out>/: manifest.json, quality-report.csv, validation.json, normalized-gl.json
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, basename } from 'node:path';
import { parseCsv } from './core/csv.js';
import { manifestEntry } from './core/checksum.js';
import type { MappingProfile } from './import/mapping.js';
import { checkProfileAgainstHeader } from './import/mapping.js';
import { normalizeGl, normalizeTb } from './import/normalize.js';
import { buildQualityReport, findDuplicateIdentities, findUnmappedAccounts, qualityReportCsv } from './import/quality.js';
import { validateLedger } from './import/validate.js';
import { inr } from './import/types.js';

function arg(name: string): string {
  const i = process.argv.indexOf('--' + name);
  if (i === -1 || !process.argv[i + 1]) {
    console.error(`Missing --${name}. Usage: import-gl --gl <file> --tb <file> --mapping <profile.json> --out <dir>`);
    process.exit(2);
  }
  return process.argv[i + 1]!;
}

if (process.argv[2] !== 'import-gl') {
  console.error('Unknown command. Supported: import-gl');
  process.exit(2);
}

const glPath = arg('gl');
const tbPath = arg('tb');
const mappingPath = arg('mapping');
const outDir = arg('out');
mkdirSync(outDir, { recursive: true });

const profile: MappingProfile = JSON.parse(readFileSync(mappingPath, 'utf-8'));
const glTable = parseCsv(readFileSync(glPath, 'utf-8'));
const tbTable = parseCsv(readFileSync(tbPath, 'utf-8'));

// 0. mapping profile check (DAT-004)
const profileProblems = checkProfileAgainstHeader(profile, glTable.header);
if (profileProblems.length > 0) {
  console.error('Mapping profile problems:\n  ' + profileProblems.join('\n  '));
  process.exit(1);
}

// 1. normalise with lineage (DAT-005)
const gl = normalizeGl(glTable, profile, basename(glPath));
const tb = normalizeTb(tbTable, basename(tbPath));

// 2. data-quality report (DAT-003)
const issues = [
  ...gl.issues,
  ...tb.issues,
  ...findDuplicateIdentities(gl.rows),
  ...findUnmappedAccounts(gl.rows, tb.rows),
];
const report = buildQualityReport(basename(glPath), gl.totalRows, gl.rows.length, issues);
writeFileSync(join(outDir, 'quality-report.csv'), qualityReportCsv(report), 'utf-8');

// 3. validation (DAT-002)
const validation = validateLedger(gl.rows, tb.rows);
writeFileSync(join(outDir, 'validation.json'), JSON.stringify(validation, null, 2), 'utf-8');

// 4. source manifest (DAT-001)
const manifest = {
  profile: profile.name,
  files: [manifestEntry(glPath, gl.totalRows), manifestEntry(tbPath, tbTable.rows.length)],
};
writeFileSync(join(outDir, 'manifest.json'), JSON.stringify(manifest, null, 2), 'utf-8');

// 5. normalised population (input to the rule engine)
writeFileSync(join(outDir, 'normalized-gl.json'), JSON.stringify(gl.rows), 'utf-8');

// summary
console.log('=== IMPORT SUMMARY ===');
console.log(`Profile: ${profile.name}`);
console.log(`GL: ${basename(glPath)} — ${gl.totalRows} rows, ${gl.rows.length} normalised, sha256 ${manifest.files[0]!.sha256.slice(0, 12)}…`);
console.log(`Quality issues: ${report.issues.length}${report.issues.length ? ' — ' + Object.entries(report.summary).map(([t, n]) => `${t}:${n}`).join(', ') : ''}`);
console.log(`Totals: Dr ${inr(validation.totalDebit)} / Cr ${inr(validation.totalCredit)} — ${validation.balanced ? 'BALANCED' : 'UNBALANCED'}`);
console.log(`Voucher imbalances: ${validation.voucherImbalances.length}`);
console.log(`Trial balance: ${validation.tbAgrees ? 'AGREES' : validation.tbDifferences.length + ' account difference(s)'}`);
if (!validation.tbAgrees) {
  for (const d of validation.tbDifferences.slice(0, 5)) {
    console.log(`  ${d.accountCode} ${d.accountName}: difference ${inr(d.difference)}`);
  }
}
console.log(`Outputs written to ${outDir}/`);
// Per DAT-002/003: unexplained differences are SHOWN before analysis starts —
// exit non-zero so pipelines stop when the population is not usable.
process.exit(validation.balanced && validation.tbAgrees ? 0 : 1);
