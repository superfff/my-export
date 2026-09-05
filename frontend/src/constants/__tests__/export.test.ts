import { describe, expect, it } from 'vitest';
import { ascSortedIds, classifyExport409, exportProgressPercent, EXPORT_409_MESSAGES } from '../export';

describe('classifyExport409', () => {
  it('后端 duplicate 原文判别为 duplicate', () => {
    expect(classifyExport409(EXPORT_409_MESSAGES.duplicate)).toBe('duplicate');
    expect(classifyExport409('已有相同导出任务')).toBe('duplicate');
  });

  it('后端 conflict 原文判别为 conflict', () => {
    expect(classifyExport409(EXPORT_409_MESSAGES.conflict)).toBe('conflict');
    expect(classifyExport409('幂等值冲突')).toBe('conflict');
  });

  it('其它未知文案一律视为 conflict（避免误导为已创建）', () => {
    expect(classifyExport409('')).toBe('conflict');
    expect(classifyExport409('后端未来新增的 409 文案')).toBe('conflict');
  });
});

describe('ascSortedIds', () => {
  it('乱序输入 → 升序', () => {
    expect(ascSortedIds(new Set([3, 1, 2]))).toEqual([1, 2, 3]);
    expect(ascSortedIds([5, 2, 9, 1])).toEqual([1, 2, 5, 9]);
  });

  it('已升序输入幂等', () => {
    expect(ascSortedIds(new Set([1, 2, 3]))).toEqual([1, 2, 3]);
  });

  it('含重复值保留并按升序（不改原输入）', () => {
    const input = new Set([2, 1]);
    const out = ascSortedIds(input);
    expect(out).toEqual([1, 2]);
    expect(input).toEqual(new Set([2, 1])); // 不改原 Set
    expect(ascSortedIds([2, 1, 1, 3])).toEqual([1, 1, 2, 3]);
  });

  it('空集 → 空数组', () => {
    expect(ascSortedIds([])).toEqual([]);
    expect(ascSortedIds(new Set<number>())).toEqual([]);
  });
});

describe('exportProgressPercent', () => {
  it('RUNNING 且 processedRows==expectedTotal → 封顶 99（不触 100）', () => {
    expect(exportProgressPercent({ status: 'RUNNING', processedRows: 2500, expectedTotal: 2500 })).toBe(99);
  });

  it('SUCCESS 同值 → 100', () => {
    expect(exportProgressPercent({ status: 'SUCCESS', processedRows: 2500, expectedTotal: 2500 })).toBe(100);
  });

  it('expectedTotal<=0 → null', () => {
    expect(exportProgressPercent({ status: 'RUNNING', processedRows: 0, expectedTotal: 0 })).toBeNull();
    expect(exportProgressPercent({ status: 'RUNNING', processedRows: 3, expectedTotal: -1 })).toBeNull();
  });

  it('中间值四舍五入正确', () => {
    expect(exportProgressPercent({ status: 'RUNNING', processedRows: 500, expectedTotal: 1000 })).toBe(50);
    expect(exportProgressPercent({ status: 'RUNNING', processedRows: 1, expectedTotal: 3 })).toBe(33);
  });

  it('processedRows=0 → 0', () => {
    expect(exportProgressPercent({ status: 'RUNNING', processedRows: 0, expectedTotal: 100 })).toBe(0);
  });
});
