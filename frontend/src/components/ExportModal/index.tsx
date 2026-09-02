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
  /** 确认回调，返回文件名和勾选字段 */
  onConfirm: (filename: string, fields: string[]) => void;
}

export default function ExportModal({ open, onClose, title, columnOptions, onConfirm }: ExportModalProps) {
  const [form] = Form.useForm<{ filename: string; fields: string[] }>();

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      onConfirm(values.filename, values.fields);
      form.resetFields();
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
