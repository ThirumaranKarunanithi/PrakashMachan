/** Minimal RFC-4180-ish CSV parsing/serialising. No dependencies. */

export interface CsvTable {
  header: string[];
  /** Data rows. rows[i] corresponds to source line i+2 (1-based, after header). */
  rows: string[][];
}

export function parseCsv(text: string): CsvTable {
  const src = text.replace(/\r\n?/g, '\n');
  const rows: string[][] = [];
  let field = '';
  let row: string[] = [];
  let inQuotes = false;
  for (let i = 0; i < src.length; i++) {
    const c = src[i];
    if (inQuotes) {
      if (c === '"') {
        if (src[i + 1] === '"') { field += '"'; i++; }
        else inQuotes = false;
      } else field += c;
    } else if (c === '"') inQuotes = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n') {
      row.push(field); field = '';
      if (row.length > 1 || row[0] !== '') rows.push(row);
      row = [];
    } else field += c;
  }
  if (field !== '' || row.length > 0) { row.push(field); rows.push(row); }
  const header = rows.shift() ?? [];
  return { header, rows };
}

export function serializeCsv(header: string[], rows: (string | number | null | undefined)[][]): string {
  const esc = (v: string | number | null | undefined): string => {
    const s = String(v ?? '');
    return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  };
  return [header.join(',')]
    .concat(rows.map((r) => r.map(esc).join(',')))
    .join('\n') + '\n';
}
