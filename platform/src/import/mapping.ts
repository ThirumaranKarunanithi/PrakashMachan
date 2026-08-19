/** DAT-004: reusable column-mapping profiles, saved per client/system. */

export type StandardGlField =
  | 'voucherId' | 'voucherType' | 'txnDate' | 'createdAt'
  | 'accountCode' | 'accountName' | 'debit' | 'credit'
  | 'narration' | 'source' | 'userId' | 'reversalOf';

export type DateFormat = 'YYYY-MM-DD' | 'DD-MM-YYYY' | 'DD/MM/YYYY';

export interface MappingProfile {
  /** e.g. "client-a-gl" — saved and reused per client/system (DAT-004) */
  name: string;
  sourceType: 'csv' | 'tally-xml' | 'xlsx';
  /** standard field -> source column header */
  fieldMap: Partial<Record<StandardGlField, string>>;
  dateFormat: DateFormat;
  /** amounts in source are rupees with optional thousands separators */
  description?: string;
}

export const REQUIRED_GL_FIELDS: StandardGlField[] = [
  'voucherId', 'voucherType', 'txnDate', 'accountCode', 'accountName', 'narration',
];

/** Validate a profile against an actual file header. Returns human-readable problems. */
export function checkProfileAgainstHeader(profile: MappingProfile, header: string[]): string[] {
  const problems: string[] = [];
  const cols = new Set(header);
  for (const field of REQUIRED_GL_FIELDS) {
    if (!profile.fieldMap[field]) problems.push(`Profile does not map required field "${field}".`);
  }
  if (!profile.fieldMap.debit && !profile.fieldMap.credit) {
    problems.push('Profile maps neither "debit" nor "credit".');
  }
  for (const [field, col] of Object.entries(profile.fieldMap)) {
    if (col && !cols.has(col)) problems.push(`Mapped column "${col}" (for ${field}) not found in file header.`);
  }
  return problems;
}

/** Parse a source date string per the profile's format into ISO YYYY-MM-DD, or null if invalid. */
export function parseDate(value: string, format: DateFormat): string | null {
  const v = value.trim();
  let y: number, m: number, d: number;
  let match: RegExpMatchArray | null;
  switch (format) {
    case 'YYYY-MM-DD':
      match = v.match(/^(\d{4})-(\d{2})-(\d{2})$/);
      if (!match) return null;
      [y, m, d] = [Number(match[1]), Number(match[2]), Number(match[3])];
      break;
    case 'DD-MM-YYYY':
      match = v.match(/^(\d{2})-(\d{2})-(\d{4})$/);
      if (!match) return null;
      [d, m, y] = [Number(match[1]), Number(match[2]), Number(match[3])];
      break;
    case 'DD/MM/YYYY':
      match = v.match(/^(\d{2})\/(\d{2})\/(\d{4})$/);
      if (!match) return null;
      [d, m, y] = [Number(match[1]), Number(match[2]), Number(match[3])];
      break;
  }
  // real-calendar check
  const dt = new Date(Date.UTC(y, m - 1, d));
  if (dt.getUTCFullYear() !== y || dt.getUTCMonth() !== m - 1 || dt.getUTCDate() !== d) return null;
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

/** Parse "YYYY-MM-DD HH:mm" (optionally with seconds/T separator) into canonical "YYYY-MM-DD HH:mm". */
export function parseTimestamp(value: string): string | null {
  const m = value.trim().match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2})(?::\d{2})?$/);
  if (!m) return null;
  const date = parseDate(m[1]!, 'YYYY-MM-DD');
  if (!date) return null;
  const hh = Number(m[2]), mm = Number(m[3]);
  if (hh > 23 || mm > 59) return null;
  return `${date} ${m[2]}:${m[3]}`;
}

/**
 * Parse a rupee amount string into integer paise.
 * Accepts optional Indian/Western thousands separators and up to 2 decimals.
 * Returns null for blank, NaN for garbage (caller records a quality issue).
 */
export function parseAmountPaise(value: string): number | null {
  const v = value.trim();
  if (v === '') return null;
  const cleaned = v.replace(/,/g, '');
  if (!/^-?\d+(\.\d{1,2})?$/.test(cleaned)) return NaN;
  const [whole, frac = ''] = cleaned.split('.');
  const sign = whole!.startsWith('-') ? -1 : 1;
  const wholeAbs = whole!.replace('-', '');
  return sign * (Number(wholeAbs) * 100 + Number((frac + '00').slice(0, 2)));
}
