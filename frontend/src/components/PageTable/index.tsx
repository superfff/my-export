import { useEffect, useRef, useState } from 'react';
import { Table, Pagination, Checkbox, Dropdown } from 'antd';
import type { TableColumnsType } from 'antd';
import { SelectionMode } from '../../types/order';
import type { SelectionState } from '../../types/order';
import styles from './PageTable.module.css';

interface PageTableProps<T> {
  rowKey: string;
  columns: TableColumnsType<T>;
  dataSource: T[];
  loading?: boolean;
  total: number;
  page: number;
  pageSize: number;
  onPageChange: (page: number, pageSize: number) => void;
  /** 勾选状态 */
  selectionState?: SelectionState;
  /** 勾选状态变更回调 */
  onSelectionChange?: (state: SelectionState) => void;
  /** 当前筛选条件下的数据总条数（全选模式下计算勾选数用） */
  filteredTotal?: number;
}

/** 创建初始选择状态 */
export function createInitialSelectionState(): SelectionState {
  return {
    mode: SelectionMode.MANUAL,
    selectedIds: new Set<number>(),
    excludedIds: new Set<number>(),
  };
}

/**
 * 可复用的表格 + 分页组件：
 * 表格区域占满剩余空间、内容超出时内部滚动（表头固定），底部分页器固定。
 * 支持双模式行选择：手动勾选 / 全选。
 */
export default function PageTable<T extends object>({
  rowKey,
  columns,
  dataSource,
  loading = false,
  total,
  page,
  pageSize,
  onPageChange,
  selectionState,
  onSelectionChange,
  filteredTotal = 0,
}: PageTableProps<T>) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [bodyHeight, setBodyHeight] = useState(0);

  // 测量表格可用高度，作为 antd Table 的 scroll.y
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return undefined;
    const observer = new ResizeObserver((entries) => {
      setBodyHeight(entries[0].contentRect.height);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // 无选择功能时，渲染基础表格
  if (!selectionState || !onSelectionChange) {
    return (
      <div className={styles.container}>
        <div ref={wrapRef} className={styles.tableWrap}>
          <Table<T>
            rowKey={rowKey}
            columns={columns}
            dataSource={dataSource}
            loading={loading}
            pagination={false}
            scroll={{ x: 'max-content', y: bodyHeight > 0 ? bodyHeight : undefined }}
          />
        </div>
        <div className={styles.pagination}>
          <Pagination
            current={page}
            pageSize={pageSize}
            total={total}
            showSizeChanger
            showTotal={(t) => `共 ${t} 条`}
            onChange={onPageChange}
            onShowSizeChange={(_, size) => onPageChange(1, size)}
          />
        </div>
      </div>
    );
  }

  // ---- 选择功能逻辑 ----
  const { mode, selectedIds, excludedIds } = selectionState;

  /** 从数据行中获取 id */
  const getRowId = (record: T): number => {
    return (record as Record<string, unknown>)[rowKey] as number;
  };

  /** 当前页所有行的 id 集合 */
  const currentPageIds = new Set(dataSource.map(getRowId));

  /** 当前页全选框的状态：全选/部分选/全不选 */
  const getPageCheckState = (): 'all' | 'partial' | 'none' => {
    if (currentPageIds.size === 0) return 'none';
    if (mode === SelectionMode.MANUAL) {
      let checkedCount = 0;
      for (const id of currentPageIds) {
        if (selectedIds.has(id)) checkedCount++;
      }
      if (checkedCount === 0) return 'none';
      if (checkedCount === currentPageIds.size) return 'all';
      return 'partial';
    }
    // ALL 模式：默认全选，排除的不勾
    let excludedCount = 0;
    for (const id of currentPageIds) {
      if (excludedIds.has(id)) excludedCount++;
    }
    if (excludedCount === currentPageIds.size) return 'none';
    if (excludedCount === 0) return 'all';
    return 'partial';
  };

  /** 当前页每一行的勾选状态 */
  const getRowSelected = (record: T): boolean => {
    const id = getRowId(record);
    if (mode === SelectionMode.MANUAL) {
      return selectedIds.has(id);
    }
    // ALL 模式：默认选中，排除的不选
    return !excludedIds.has(id);
  };

  /** 表头全选框点击 */
  const handleHeaderCheck = (checked: boolean) => {
    if (mode === SelectionMode.MANUAL) {
      const newSet = new Set(selectedIds);
      for (const id of currentPageIds) {
        if (checked) newSet.add(id);
        else newSet.delete(id);
      }
      onSelectionChange({ ...selectionState, selectedIds: newSet });
    } else {
      // ALL 模式：checked=false 表示当前页全反选，checked=true 表示当前页全恢复
      const newSet = new Set(excludedIds);
      for (const id of currentPageIds) {
        if (checked) newSet.delete(id);
        else newSet.add(id);
      }
      onSelectionChange({ ...selectionState, excludedIds: newSet });
    }
  };

  /** 单行勾选 */
  const handleRowSelect = (record: T, checked: boolean) => {
    const id = getRowId(record);
    if (mode === SelectionMode.MANUAL) {
      const newSet = new Set(selectedIds);
      if (checked) newSet.add(id);
      else newSet.delete(id);
      onSelectionChange({ ...selectionState, selectedIds: newSet });
    } else {
      // ALL 模式：checked=false → 加入排除，checked=true → 从排除中移除
      const newSet = new Set(excludedIds);
      if (checked) newSet.delete(id);
      else newSet.add(id);
      onSelectionChange({ ...selectionState, excludedIds: newSet });
    }
  };

  /** 切换选择模式 */
  const handleModeChange = (newMode: SelectionMode) => {
    if (newMode === mode) return;
    onSelectionChange({
      mode: newMode,
      selectedIds: new Set<number>(),
      excludedIds: new Set<number>(),
    });
  };

  const checkState = getPageCheckState();

  // 自定义表头：checkbox + 模式切换下拉
  const customHeaderCell = (
    <div className={styles.headerCell}>
      <Checkbox
        checked={checkState === 'all'}
        indeterminate={checkState === 'partial'}
        onChange={(e) => handleHeaderCheck(e.target.checked)}
      />
      <Dropdown
        menu={{
          items: [
            {
              key: SelectionMode.MANUAL,
              label: '手动勾选',
              onClick: () => handleModeChange(SelectionMode.MANUAL),
            },
            {
              key: SelectionMode.ALL,
              label: '全选数据',
              onClick: () => handleModeChange(SelectionMode.ALL),
            },
          ],
          selectedKeys: [mode],
        }}
        trigger={['click']}
      >
        <span className={styles.modeSwitch}>{mode === SelectionMode.MANUAL ? '手动' : '全选'} ▾</span>
      </Dropdown>
    </div>
  );

  // 在 columns 前插入选择列
  const selectionColumn: TableColumnsType<T>[number] = {
    title: customHeaderCell,
    width: 100,
    render: (_: unknown, record: T) => (
      <Checkbox
        checked={getRowSelected(record)}
        onChange={(e) => handleRowSelect(record, e.target.checked)}
      />
    ),
  };

  const mergedColumns = [selectionColumn, ...columns];

  // 计算当前勾选数
  const selectedCount =
    mode === SelectionMode.MANUAL
      ? selectedIds.size
      : Math.max(0, filteredTotal - excludedIds.size);

  return (
    <div className={styles.container}>
      {selectedCount > 0 && (
        <div className={styles.selectionNotice}>
          已勾选 <span className={styles.selectionCount}>{selectedCount}</span> 条
          {mode === SelectionMode.ALL && <span className={styles.modeTag}>（全选模式）</span>}
        </div>
      )}
      <div ref={wrapRef} className={styles.tableWrap}>
        <Table<T>
          rowKey={rowKey}
          columns={mergedColumns}
          dataSource={dataSource}
          loading={loading}
          pagination={false}
          scroll={{ x: 'max-content', y: bodyHeight > 0 ? bodyHeight : undefined }}
          rowClassName={() => styles.row}
        />
      </div>
      <div className={styles.pagination}>
        <Pagination
          current={page}
          pageSize={pageSize}
          total={total}
          showSizeChanger
          showTotal={(t) => `共 ${t} 条`}
          onChange={onPageChange}
          onShowSizeChange={(_, size) => onPageChange(1, size)}
        />
      </div>
    </div>
  );
}
