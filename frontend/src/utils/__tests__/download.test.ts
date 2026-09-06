import { describe, expect, it } from 'vitest';
import { parseDownloadFilename } from '../download';

/** 订单导出.xlsx 的 UTF-8 百分号编码 */
const CN_ENCODED = '%E8%AE%A2%E5%8D%95%E5%AF%BC%E5%87%BA.xlsx';

describe('parseDownloadFilename', () => {
  it('filename*=UTF-8 百分号编码 → decodeURIComponent 解码', () => {
    expect(parseDownloadFilename(`attachment; filename*=UTF-8''${CN_ENCODED}`)).toBe('订单导出.xlsx');
  });

  it('纯 ASCII 普通 filename="..." → 原样', () => {
    expect(parseDownloadFilename('attachment; filename="orders.xlsx"')).toBe('orders.xlsx');
  });

  it('无该头 / 传 null / 无法解析 → null', () => {
    expect(parseDownloadFilename(null)).toBeNull();
    expect(parseDownloadFilename(undefined)).toBeNull();
    expect(parseDownloadFilename('')).toBeNull();
    expect(parseDownloadFilename('text/plain')).toBeNull();
  });

  it('star 段畸形百分号（解码抛错）→ 回退普通 filename，不吞掉可读名', () => {
    const malformed = `attachment; filename*=UTF-8''${CN_ENCODED}%zz.xlsx; filename="fallback.xlsx"`;
    expect(parseDownloadFilename(malformed)).toBe('fallback.xlsx');
  });
});
