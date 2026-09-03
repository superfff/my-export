import { useState } from 'react';
import { Modal, Form, Input, Checkbox } from 'antd';
import styles from './ExportModal.module.css';

interface ColumnOption {
  label: string;
  value: string;
}

export interface ExportModalProps {
  open: boolean;
  onClose: () => void;
  /** 弹窗标题，如 "导出已选（3 条）" */
  title: string;
  /** 表头字段选项 */
  columnOptions: ColumnOption[];
  /** 确认回调，支持异步提交；Promise resolve 表示提交成功（含"已有相同导出任务"） */
  onConfirm: (filename: string, fields: string[]) => Promise<void>;
}

export default function ExportModal({ open, onClose, title, columnOptions, onConfirm }: ExportModalProps) {
  const [form] = Form.useForm<{ filename: string; fields: string[] }>();
  const [confirmLoading, setConfirmLoading] = useState(false);

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      setConfirmLoading(true);
      try {
        await onConfirm(values.filename, values.fields);
        // 仅在提交成功后重置表单；失败时保留用户输入供重试
        form.resetFields();
      } finally {
        setConfirmLoading(false);
      }
    } catch {
      // 校验失败，antd Form 自动展示提示
    }
  };

  const handleCancel = () => {
    form.resetFields();
    onClose();
  };

  return (
    <Modal
      title={title}
      open={open}
      onOk={handleOk}
      onCancel={handleCancel}
      okText="确认导出"
      cancelText="取消"
      width={480}
      destroyOnHidden
      confirmLoading={confirmLoading}
    >
      <Form form={form} layout="vertical" className={styles.form}>
        <Form.Item
          name="filename"
          label="文件名"
          rules={[{ required: true, message: '请输入导出文件名' }]}
        >
          <Input placeholder="请输入导出文件名" />
        </Form.Item>
        <Form.Item
          name="fields"
          label="导出字段"
          rules={[{ required: true, message: '请至少勾选一个导出字段' }]}
        >
          <Checkbox.Group options={columnOptions} className={styles.fieldGroup} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
