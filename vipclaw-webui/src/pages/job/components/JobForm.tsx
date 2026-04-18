import React, { useEffect, useState } from 'react';
import { Modal, Form, Input, Select, Radio, Button, Space, Alert, Typography, Card, Tag, Tooltip, Switch } from 'antd';
import { QuestionCircleOutlined, ClockCircleOutlined, EyeOutlined } from '@ant-design/icons';
import { getCurrentUserInfo, isPublicSwitchDisabled } from '@/utils/permissionUtil';

const { TextArea } = Input;
const { Text } = Typography;

interface JobFormProps {
  visible: boolean;
  onCancel: () => void;
  onSubmit: (values: any) => Promise<void>;
  values?: API.JobItem;
}

const JobForm: React.FC<JobFormProps> = ({ visible, onCancel, onSubmit, values }) => {
  const [form] = Form.useForm();
  const isUpdate = !!values;
  const [nextExecutions, setNextExecutions] = useState<string[]>([]);
  const [cronError, setCronError] = useState<string>('');
  const [isPublic, setIsPublic] = useState(values?.isPublic === 1);
  const [previewModalVisible, setPreviewModalVisible] = useState<boolean>(false);
  const { username, isAdmin } = getCurrentUserInfo();

  useEffect(() => {
    if (visible && values) {
      form.setFieldsValue({
        jobName: values.jobName,
        jobGroup: values.jobGroup,
        jobClass: values.jobClass,
        cronExpression: values.cronExpression,
        concurrent: values.concurrent,
        description: values.description,
      });
      setIsPublic(values.isPublic === 1);
      // 预测下次执行时间
      if (values.cronExpression) {
        predictNextExecutions(values.cronExpression);
      }
    } else if (visible) {
      form.resetFields();
      const defaultValues = {
        jobGroup: 'DEFAULT',
        concurrent: 1,
      };
      form.setFieldsValue(defaultValues);
      setIsPublic(false);
      setNextExecutions([]);
      setCronError('');
    }
  }, [visible, values, form]);

  /**
   * 解析 Cron 表达式并预测下次执行时间
   * 支持标准 Quartz Cron 格式：秒 分 时 日 月 周 [年]
   */
  const predictNextExecutions = (cronExpr: string): string[] => {
    setCronError('');

    if (!cronExpr || !cronExpr.trim()) {
      setNextExecutions([]);
      return [];
    }

    try {
      const parts = cronExpr.trim().split(/\s+/);
      if (parts.length < 6 || parts.length > 7) {
        setCronError('Cron 表达式格式错误：需要 6-7 个字段');
        setNextExecutions([]);
        return [];
      }

      const executions: string[] = [];
      let currentDate = new Date();

      // 计算未来 5 次执行时间
      for (let i = 0; i < 5; i++) {
        const nextDate = getNextExecution(parts, currentDate);
        if (nextDate) {
          executions.push(formatDateTime(nextDate));
          currentDate = new Date(nextDate.getTime() + 1000); // 从下一次之后开始计算
        } else {
          break;
        }
      }

      setNextExecutions(executions);
      return executions;
    } catch (error) {
      setCronError('Cron 表达式解析失败：' + (error as Error).message);
      setNextExecutions([]);
      return [];
    }
  };

  /**
   * 获取下一次执行时间
   */
  const getNextExecution = (parts: string[], afterDate: Date): Date | null => {
    const [secondExpr, minuteExpr, hourExpr, dayExpr, monthExpr, weekExpr] = parts;

    let candidate = new Date(afterDate);
    candidate.setMilliseconds(0);

    // 最多尝试 4 年（处理每年执行的情况）
    for (let yearOffset = 0; yearOffset < 4; yearOffset++) {
      const year = candidate.getFullYear() + yearOffset;

      // 获取该年所有可能的月份
      const months = parseField(monthExpr, 1, 12, '月');

      for (let mIdx = 0; mIdx < months.length; mIdx++) {
        const month = months[mIdx];
        if (yearOffset === 0 && month < candidate.getMonth() + 1) continue;

        // 获取该月所有可能的日期
        const daysInMonth = new Date(year, month, 0).getDate();
        let days: number[];

        if (weekExpr !== '?' && weekExpr !== '*') {
          // 按周计算
          days = getDaysByWeek(year, month, weekExpr, daysInMonth);
        } else {
          // 按日计算
          days = parseField(dayExpr, 1, daysInMonth, '日');
        }

        for (let dIdx = 0; dIdx < days.length; dIdx++) {
          const day = days[dIdx];
          if (yearOffset === 0 && month === candidate.getMonth() + 1 && day <= candidate.getDate()) {
            if (yearOffset === 0 && month === candidate.getMonth() + 1 && day < candidate.getDate()) continue;
          }

          const hours = parseField(hourExpr, 0, 23, '时');
          for (let hIdx = 0; hIdx < hours.length; hIdx++) {
            const hour = hours[hIdx];
            if (yearOffset === 0 && month === candidate.getMonth() + 1 && day === candidate.getDate() && hour < candidate.getHours()) continue;

            const minutes = parseField(minuteExpr, 0, 59, '分');
            for (let minIdx = 0; minIdx < minutes.length; minIdx++) {
              const minute = minutes[minIdx];
              if (yearOffset === 0 && month === candidate.getMonth() + 1 && day === candidate.getDate() && hour === candidate.getHours() && minute < candidate.getMinutes()) continue;

              const seconds = parseField(secondExpr, 0, 59, '秒');
              for (let sIdx = 0; sIdx < seconds.length; sIdx++) {
                const second = seconds[sIdx];
                if (yearOffset === 0 && month === candidate.getMonth() + 1 && day === candidate.getDate() && hour === candidate.getHours() && minute === candidate.getMinutes() && second <= candidate.getSeconds()) continue;

                const result = new Date(year, month - 1, day, hour, minute, second);
                if (result > afterDate) {
                  return result;
                }
              }
            }
          }
        }
      }
    }

    return null;
  };

  /**
   * 根据周表达式获取日期
   */
  const getDaysByWeek = (year: number, month: number, weekExpr: string, daysInMonth: number): number[] => {
    const days: number[] = [];
    const weekDays = parseField(weekExpr, 1, 7, '周'); // 1=周日, 7=周六

    for (let day = 1; day <= daysInMonth; day++) {
      const date = new Date(year, month - 1, day);
      // getDay() 返回 0-6，0=周日
      const weekDay = date.getDay() === 0 ? 1 : date.getDay() + 1;
      if (weekDays.includes(weekDay)) {
        days.push(day);
      }
    }

    return days;
  };

  /**
   * 解析 Cron 字段
   * 支持：* / , -
   */
  const parseField = (expr: string, min: number, max: number, fieldName: string): number[] => {
    const result: number[] = [];

    // 处理 L（最后一天）
    if (expr === 'L') {
      return [max];
    }

    // 处理 ?（不指定）
    if (expr === '?') {
      return [min];
    }

    // 处理 *（每分钟/小时等）
    if (expr === '*') {
      for (let i = min; i <= max; i++) {
        result.push(i);
      }
      return result;
    }

    // 处理逗号分隔的多个值
    if (expr.includes(',')) {
      const parts = expr.split(',');
      for (let idx = 0; idx < parts.length; idx++) {
        result.push(...parseField(parts[idx].trim(), min, max, fieldName));
      }
      return [...new Set(result)].sort((a, b) => a - b);
    }

    // 处理范围（如 1-5）
    if (expr.includes('-')) {
      const [start, end] = expr.split('-').map(Number);
      if (isNaN(start) || isNaN(end)) {
        throw new Error(`${fieldName} 范围格式错误: ${expr}`);
      }
      for (let i = Math.max(min, start); i <= Math.min(max, end); i++) {
        result.push(i);
      }
      return result;
    }

    // 处理步长（如 */5 或 1-10/2）
    if (expr.includes('/')) {
      const [range, step] = expr.split('/');
      const stepNum = Number(step);
      if (isNaN(stepNum) || stepNum <= 0) {
        throw new Error(`${fieldName} 步长格式错误: ${expr}`);
      }

      let start = min;
      let end = max;

      if (range !== '*') {
        if (range.includes('-')) {
          const [s, e] = range.split('-').map(Number);
          start = Math.max(min, s);
          end = Math.min(max, e);
        } else {
          start = Number(range);
        }
      }

      for (let i = start; i <= end; i += stepNum) {
        result.push(i);
      }
      return result;
    }

    // 处理单个数字
    const num = Number(expr);
    if (isNaN(num)) {
      // 处理周的英文缩写
      const weekMap: { [key: string]: number } = {
        'SUN': 1, 'MON': 2, 'TUE': 3, 'WED': 4, 'THU': 5, 'FRI': 6, 'SAT': 7,
        '周日': 1, '周一': 2, '周二': 3, '周三': 4, '周四': 5, '周五': 6, '周六': 7,
      };
      if (weekMap[expr.toUpperCase()]) {
        return [weekMap[expr.toUpperCase()]];
      }
      throw new Error(`${fieldName} 格式错误: ${expr}`);
    }

    if (num >= min && num <= max) {
      return [num];
    }

    throw new Error(`${fieldName} 值超出范围 [${min}-${max}]: ${expr}`);
  };

  /**
   * 格式化日期时间
   */
  const formatDateTime = (date: Date): string => {
    const pad = (n: number) => n.toString().padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
  };

  const handleSubmit = async () => {
    try {
      const formData = await form.validateFields();
      await onSubmit({ ...formData, isPublic: isPublic ? 1 : 0 });
      form.resetFields();
    } catch (error) {
      console.error('表单验证失败:', error);
    }
  };

  // Cron 表达式示例
  const cronExamples = [
    { label: '每5秒执行', value: '0/5 * * * * ?' },
    { label: '每分钟执行', value: '0 * * * * ?' },
    { label: '每小时执行', value: '0 0 * * * ?' },
    { label: '每天0点执行', value: '0 0 0 * * ?' },
    { label: '每天8点执行', value: '0 0 8 * * ?' },
    { label: '每周一0点执行', value: '0 0 0 ? * MON' },
    { label: '每月1日0点执行', value: '0 0 0 1 * ?' },
  ];

  return (
    <Modal
      title={isUpdate ? '编辑定时任务' : '新建定时任务'}
      open={visible}
      onCancel={onCancel}
      width={600}
      footer={
        <Space>
          <Button onClick={onCancel}>取消</Button>
          <Button type="primary" onClick={handleSubmit}>
            确定
          </Button>
        </Space>
      }
    >
      {/* 预览执行时间弹窗 */}
      <Modal
        title={
          <Space>
            <ClockCircleOutlined style={{ color: '#52c41a' }} />
            <span>预计执行时间（未来 5 次）</span>
          </Space>
        }
        open={previewModalVisible}
        onCancel={() => setPreviewModalVisible(false)}
        footer={[
          <Button key="close" onClick={() => setPreviewModalVisible(false)}>
            关闭
          </Button>,
        ]}
      >
        {nextExecutions.length > 0 ? (
          <Space direction="vertical" size={12} style={{ width: '100%', padding: '16px 0' }}>
            {nextExecutions.map((time, index) => (
              <div
                key={index}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  padding: '12px 16px',
                  background: index === 0 ? '#f6ffed' : '#f0f5ff',
                  border: `1px solid ${index === 0 ? '#b7eb8f' : '#d6e4ff'}`,
                  borderRadius: '8px',
                }}
              >
                <ClockCircleOutlined
                  style={{
                    marginRight: 12,
                    fontSize: '16px',
                    color: index === 0 ? '#52c41a' : '#1890ff',
                  }}
                />
                <div>
                  <Tag color={index === 0 ? 'green' : 'blue'} style={{ marginRight: 8 }}>
                    {index === 0 ? '下次执行' : `第${index + 1}次`}
                  </Tag>
                  <Text strong style={{ fontSize: '15px' }}>{time}</Text>
                </div>
              </div>
            ))}
          </Space>
        ) : (
          <Alert
            message="暂无预测数据"
            description="Cron 表达式解析失败或格式错误"
            type="warning"
            showIcon
          />
        )}
      </Modal>

      <Form
        form={form}
        layout="vertical"
        initialValues={{
          jobGroup: 'DEFAULT',
          concurrent: 1,
        }}
      >
        <Form.Item
          name="jobName"
          label="任务名称"
          rules={[
            { required: true, message: '请输入任务名称' },
            { max: 100, message: '任务名称长度不能超过100' },
          ]}
        >
          <Input placeholder="请输入任务名称" />
        </Form.Item>

        <Form.Item
          name="jobGroup"
          label="任务组名"
          rules={[
            { required: true, message: '请输入任务组名' },
            { max: 100, message: '任务组名长度不能超过100' },
          ]}
        >
          <Input placeholder="请输入任务组名，默认：DEFAULT" />
        </Form.Item>

        <Form.Item
          name="jobClass"
          label="执行类"
          rules={[
            { required: true, message: '请输入执行类全路径' },
            { max: 255, message: '执行类长度不能超过 255' },
          ]}
          tooltip={{
            title: '必须是继承 BaseJob 的类全路径，例如：job.com.vipamp.vipclaw.admin.SampleJob',
            icon: <QuestionCircleOutlined />,
          }}
        >
          <Input placeholder="例如：job.com.vipamp.vipclaw.admin.SampleJob" />
        </Form.Item>
        
        <Form.Item label="常用表达式">
          <Select
            placeholder="选择常用表达式"
            options={cronExamples}
            onChange={(value) => {
              if (value) {
                form.setFieldsValue({ cronExpression: value });
                predictNextExecutions(value);
              } else {
                // 清空时重置
                form.setFieldsValue({ cronExpression: '' });
                setNextExecutions([]);
                setCronError('');
              }
            }}
            style={{ width: '100%' }}
            allowClear
          />
        </Form.Item>
        
        <Form.Item
          name="cronExpression"
          label="Cron 表达式"
          rules={[
            { required: true, message: '请输入 Cron 表达式' },
          ]}
          tooltip={{
            title: '格式：秒 分 时 日 月 周 年（可选）。示例：0 0 12 * * ? 表示每天中午12点执行',
            icon: <QuestionCircleOutlined />,
          }}
        >
          <Space.Compact style={{ width: '100%' }}>
            <Input
              placeholder="例如：0/5 * * * * ?"
              onChange={(e) => predictNextExecutions(e.target.value)}
            />
            <Button
              type="primary"
              icon={<EyeOutlined />}
              onClick={() => {
                const cronValue = form.getFieldValue('cronExpression');
                if (cronValue && !cronError && nextExecutions.length > 0) {
                  setPreviewModalVisible(true);
                } else if (!cronValue) {
                  setCronError('请先输入 Cron 表达式');
                } else if (cronError) {
                  // 已有错误，提示用户修正
                } else {
                  // 尝试重新计算
                  predictNextExecutions(cronValue);
                  setTimeout(() => {
                    const currentError = form.getFieldError('cronExpression');
                    if (!currentError || currentError.length === 0) {
                      setPreviewModalVisible(true);
                    }
                  }, 100);
                }
              }}
              disabled={!form.getFieldValue('cronExpression') || !!cronError}
            >
              预览执行时间
            </Button>
          </Space.Compact>
        </Form.Item>

        {cronError && (
          <Alert
            message="表达式解析错误"
            description={cronError}
            type="error"
            showIcon
            style={{ marginBottom: 16 }}
          />
        )}

        <Form.Item
          name="concurrent"
          label="并发执行"
          rules={[{ required: true, message: '请选择是否允许并发' }]}
        >
          <Radio.Group>
            <Radio value={1}>允许</Radio>
            <Radio value={0}>禁止</Radio>
          </Radio.Group>
        </Form.Item>

        <Form.Item
          name="description"
          label="任务描述"
          rules={[{ max: 500, message: '任务描述长度不能超过500' }]}
        >
          <TextArea rows={3} placeholder="请输入任务描述（可选）" />
        </Form.Item>

        {/* 是否公开 */}
        <Form.Item
          label="是否公开"
          extra={
            isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, !isUpdate) && isUpdate
              ? '您没有权限修改此设置（已公开的实体不能改为非公开）'
              : '公开后其他用户也可以查看此定时任务'
          }
        >
          <Switch
            checked={isPublic}
            onChange={setIsPublic}
            checkedChildren="公开"
            unCheckedChildren="私有"
            disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, !isUpdate)}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default JobForm;
