import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ExportModal from '../index';

const columnOptions = [
  { label: '订单号', value: 'orderNo' },
  { label: '客户名称', value: 'customerName' },
  { label: '订单金额', value: 'amount' },
];

describe('ExportModal', () => {
  it('弹窗打开时显示标题', () => {
    render(
      <ExportModal
        open={true}
        onClose={() => {}}
        title="导出已选（3 条）"
        columnOptions={columnOptions}
        onConfirm={() => {}}
      />,
    );
    expect(screen.getByText('导出已选（3 条）')).toBeInTheDocument();
  });

  it('显示文件名输入框和字段勾选', () => {
    render(
      <ExportModal
        open={true}
        onClose={() => {}}
        title="导出"
        columnOptions={columnOptions}
        onConfirm={() => {}}
      />,
    );
    expect(screen.getByPlaceholderText('请输入导出文件名')).toBeInTheDocument();
    expect(screen.getByText('订单号')).toBeInTheDocument();
    expect(screen.getByText('客户名称')).toBeInTheDocument();
    expect(screen.getByText('订单金额')).toBeInTheDocument();
  });

  it('未填文件名时提交显示校验提示', async () => {
    render(
      <ExportModal
        open={true}
        onClose={() => {}}
        title="导出"
        columnOptions={columnOptions}
        onConfirm={() => {}}
      />,
    );
    await userEvent.click(screen.getByText('确认导出'));
    await waitFor(() => {
      expect(screen.getByText('请输入导出文件名')).toBeInTheDocument();
    });
  });

  it('未勾选字段时提交显示校验提示', async () => {
    render(
      <ExportModal
        open={true}
        onClose={() => {}}
        title="导出"
        columnOptions={columnOptions}
        onConfirm={() => {}}
      />,
    );
    // 填入文件名但不勾选字段
    await userEvent.type(screen.getByPlaceholderText('请输入导出文件名'), 'test');
    await userEvent.click(screen.getByText('确认导出'));
    await waitFor(() => {
      expect(screen.getByText('请至少勾选一个导出字段')).toBeInTheDocument();
    });
  });

  it('填写完整后提交调用 onConfirm', async () => {
    const onConfirm = vi.fn();
    render(
      <ExportModal
        open={true}
        onClose={() => {}}
        title="导出"
        columnOptions={columnOptions}
        onConfirm={onConfirm}
      />,
    );
    await userEvent.type(screen.getByPlaceholderText('请输入导出文件名'), 'orders');
    // 勾选"订单号"字段
    const checkbox = screen.getByRole('checkbox', { name: '订单号' });
    await userEvent.click(checkbox);
    await userEvent.click(screen.getByText('确认导出'));

    await waitFor(() => {
      expect(onConfirm).toHaveBeenCalledWith('orders', ['orderNo']);
    });
  });

  it('点击取消调用 onClose', async () => {
    const onClose = vi.fn();
    render(
      <ExportModal
        open={true}
        onClose={onClose}
        title="导出"
        columnOptions={columnOptions}
        onConfirm={() => {}}
      />,
    );
    await userEvent.click(screen.getByText(/取.*消/));
    expect(onClose).toHaveBeenCalled();
  });
});
