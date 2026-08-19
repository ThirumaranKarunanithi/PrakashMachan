/** DAT-001: preserve the original file identity so the analysed population is reproducible. */
import { createHash } from 'node:crypto';
import { statSync, readFileSync } from 'node:fs';
import { basename } from 'node:path';

export interface SourceManifestEntry {
  file: string;
  bytes: number;
  sha256: string;
  rows: number;
  importedAt: string; // ISO timestamp of the import run (not part of any checksum)
}

export function sha256Hex(data: string | Buffer): string {
  return createHash('sha256').update(data).digest('hex');
}

export function manifestEntry(filePath: string, rowCount: number, now: Date = new Date()): SourceManifestEntry {
  const buf = readFileSync(filePath);
  return {
    file: basename(filePath),
    bytes: statSync(filePath).size,
    sha256: sha256Hex(buf),
    rows: rowCount,
    importedAt: now.toISOString(),
  };
}
