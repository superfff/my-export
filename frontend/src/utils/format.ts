const pad = (n: number) => String(n).padStart(2, '0');

/** 时间戳或 ISO 字符串 -> YYYY-MM-DD */
export function formatDate(ts: string | number): string {
  const d = new Date(ts);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** 时间戳或 ISO 字符串 -> YYYY-MM-DD HH:mm:ss，空值返回 '-' */
export function formatDateTime(ts?: string | number | null): string {
  if (!ts) return '-';
  const d = new Date(ts);
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  );
}
