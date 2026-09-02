import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import PageTable from '../index';
import { createInitialSelectionState } from '../index';
import { SelectionMode } from '../../../types/order';
import type { SelectionState } from '../../../types/order';

/** 测试用数据行 */
interface Row {
  id: number;
  name: string;
}

const rows: Row[] = [
  { id: 1, name: 'A' },
  { id: 2, name: 'B' },
  { id: 3, name: 'C' },
];

const columns = [
  { title: '名称', dataIndex: 'name', width: 100 },
];

/** 包裹组件，管理 selectionState */
function TableWrapper({ filteredTotal = 3 }: { filteredTotal?: number }) {
  const [selectionState, setSelectionState] = useState<SelectionState>(
    createInitialSelectionState,
  );

  return (
    <PageTable<Row>
      rowKey="id"
      columns={columns}
      dataSource={rows}
      total={filteredTotal}
      page={1}
      pageSize={20}
      onPageChange={() => {}}
      selectionState={selectionState}
      onSelectionChange={setSelectionState}
      filteredTotal={filteredTotal}
    />
  );
}

describe('PageTable 选择模型', () => {
  it('初始状态无勾选提示', () => {
    render(<TableWrapper />);
    expect(screen.queryByText(/已勾选|排除/)).not.toBeInTheDocument();
  });

  it('手动勾选单行后显示勾选提示', async () => {
    render(<TableWrapper />);
    const checkboxes = screen.getAllByRole('checkbox');
    await userEvent.click(checkboxes[1]); // 第一个是表头，第二个是第一行
    // 文本被多个元素拆分，用 container + text content 匹配
    const notice = screen.getByText((content) => content.includes('已勾选'));
    expect(notice.textContent).toContain('1');
  });

  it('手动勾选多行后显示正确数量', async () => {
    render(<TableWrapper />);
    const checkboxes = screen.getAllByRole('checkbox');
    await userEvent.click(checkboxes[1]);
    await userEvent.click(checkboxes[2]);
    const notice = screen.getByText((content) => content.includes('已勾选'));
    expect(notice.textContent).toContain('2');
  });

  it('切换到全选模式后显示全部筛选结果提示', async () => {
    render(<TableWrapper filteredTotal={10} />);
    // antd Table 会渲染一个隐藏的 measure row，导致"手动"匹配多个元素，取第一个
    const modeSwitches = screen.getAllByText(/手动/);
    await userEvent.click(modeSwitches[0]);
    const allOption = screen.getByText('全选数据');
    await userEvent.click(allOption);
    const notice = screen.getByText((content) => content.includes('全部筛选结果'));
    expect(notice.textContent).toContain('0');
  });

  it('全选模式下反选后显示排除数', async () => {
    render(<TableWrapper filteredTotal={10} />);
    const modeSwitches = screen.getAllByText(/手动/);
    await userEvent.click(modeSwitches[0]);
    await userEvent.click(screen.getByText('全选数据'));

    // 反选第一行
    const checkboxes = screen.getAllByRole('checkbox');
    await userEvent.click(checkboxes[1]);

    const notice = screen.getByText((content) => content.includes('排除'));
    expect(notice.textContent).toContain('1');
  });

  it('SelectionMode 枚举值正确', () => {
    expect(SelectionMode.MANUAL).toBe('MANUAL');
    expect(SelectionMode.ALL).toBe('ALL');
  });

  it('createInitialSelectionState 返回正确初始值', () => {
    const state = createInitialSelectionState();
    expect(state.mode).toBe(SelectionMode.MANUAL);
    expect(state.selectedIds.size).toBe(0);
    expect(state.excludedIds.size).toBe(0);
  });
});
