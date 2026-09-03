import { Form, Input, Select, DatePicker, Button, Space } from 'antd';
import type { Dayjs } from 'dayjs';
import { ORDER_STATUS_OPTIONS } from '../../constants/order';
import type { OrderStatus, SortField, SortOrder } from '../../types/order';
import styles from './index.module.css';

const { RangePicker } = DatePicker;

/** 查询表单字段值（与后端 query 参数对应，时间范围先取 dayjs，交由页面转换） */
export interface OrderFormValues {
  orderNo?: string;
  customerName?: string;
  phone?: string;
  status?: OrderStatus;
  createdRange?: [Dayjs, Dayjs] | null;
  sortField?: SortField;
  sortOrder?: SortOrder;
}

/** 排序字段选项 */
const SORT_FIELD_OPTIONS = [
  { value: 'createdAt', label: '创建时间' },
  { value: 'amount', label: '订单金额' },
];

/** 排序方向选项 */
const SORT_ORDER_OPTIONS = [
  { value: 'asc', label: '升序' },
  { value: 'desc', label: '降序' },
];

interface OrderQueryFormProps {
  onSearch: (values: OrderFormValues) => void;
  onReset: () => void;
}

export default function OrderQueryForm({ onSearch, onReset }: OrderQueryFormProps) {
  const [form] = Form.useForm<OrderFormValues>();

  const handleSearch = () => {
    onSearch(form.getFieldsValue());
  };

  const handleReset = () => {
    form.resetFields();
    onReset();
  };

  return (
    <div className={styles.queryCard}>
      <Form form={form} layout="inline" onFinish={handleSearch}>
        <Form.Item name="orderNo" label="订单号">
          <Input placeholder="请输入订单号" allowClear style={{ width: 160 }} />
        </Form.Item>
        <Form.Item name="customerName" label="客户名称">
          <Input placeholder="请输入客户名称" allowClear style={{ width: 160 }} />
        </Form.Item>
        <Form.Item name="phone" label="客户手机号">
          <Input placeholder="请输入客户手机号" allowClear style={{ width: 160 }} />
        </Form.Item>
        <Form.Item name="status" label="订单状态">
          <Select placeholder="请选择状态" allowClear options={ORDER_STATUS_OPTIONS} style={{ width: 140 }} />
        </Form.Item>
        <Form.Item name="createdRange" label="创建时间">
          <RangePicker />
        </Form.Item>
        <Form.Item name="sortField" label="排序字段">
          <Select placeholder="默认" allowClear options={SORT_FIELD_OPTIONS} style={{ width: 120 }} />
        </Form.Item>
        <Form.Item name="sortOrder" label="排序方向">
          <Select placeholder="默认" allowClear options={SORT_ORDER_OPTIONS} style={{ width: 100 }} />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit">
              查询
            </Button>
            <Button onClick={handleReset}>重置</Button>
          </Space>
        </Form.Item>
      </Form>
    </div>
  );
}
