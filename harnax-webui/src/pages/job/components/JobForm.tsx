import React, { useEffect, useState } from 'react';
import { useIntl } from '@umijs/max';
import { Modal, Form, Input, Select, Radio, Button, Space, Alert, Typography, Card, Tag, Tooltip, Switch } from 'antd';
import { QuestionCircleOutlined, ClockCircleOutlined, EyeOutlined, FieldTimeOutlined } from '@ant-design/icons';
import { getCurrentUserInfo, isPublicSwitchDisabled } from '@/utils/permissionUtil';
import { FormModal } from '@/components/FormModal';

const { TextArea } = Input;
const { Text } = Typography;

interface JobFormProps {
  visible: boolean;
  onCancel: () => void;
  onSubmit: (values: any) => Promise<void>;
  values?: API.JobItem;
}

const JobForm: React.FC<JobFormProps> = ({ visible, onCancel, onSubmit, values }) => {
  const intl = useIntl();
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
      for (let i = 0; i < 5; i++) {
        const nextDate = getNextExecution(parts, currentDate);
        if (nextDate) {
          executions.push(formatDateTime(nextDate));
          currentDate = new Date(nextDate.getTime() + 1000);
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

  const getNextExecution = (parts: string[], afterDate: Date): Date | null => {
    const [secondExpr, minuteExpr, hourExpr, dayExpr, monthExpr, weekExpr] = parts;
    let candidate = new Date(afterDate);
    candidate.setMilliseconds(0);
    for (let yearOffset = 0; yearOffset < 4; yearOffset++) {
      const year = candidate.getFullYear() + yearOffset;
      const months = parseField(monthExpr, 1, 12, '月');
      for (let mIdx = 0; mIdx < months.length; mIdx++) {
        const month = months[mIdx];
        if (yearOffset === 0 && month < candidate.getMonth() + 1) continue;
        const daysInMonth = new Date(year, month, 0).getDate();
        let days: number[];
        if (weekExpr !== '?' && weekExpr !== '*') {
          days = getDaysByWeek(year, month, weekExpr, daysInMonth);
        } else {
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

  const getDaysByWeek = (year: number, month: number, weekExpr: string, daysInMonth: number): number[] => {
    const days: number[] = [];
    const weekDays = parseField(weekExpr, 1, 7, '周');
    for (let day = 1; day <= daysInMonth; day++) {
      const date = new Date(year, month - 1, day);
      const weekDay = date.getDay() === 0 ? 1 : date.getDay() + 1;
      if (weekDays.includes(weekDay)) {
        days.push(day);
      }
    }
    return days;
  };

  const parseField = (expr: string, min: number, max: number, fieldName: string): number[] => {
    const result: number[] = [];
    if (expr === 'L') return [max];
    if (expr === '?') return [min];
    if (expr === '*') {
      for (let i = min; i <= max; i++) result.push(i);
      return result;
    }
    if (expr.includes(',')) {
      const parts = expr.split(',');
      for (let idx = 0; idx < parts.length; idx++) {
        result.push(...parseField(parts[idx].trim(), min, max, fieldName));
      }
      return [...new Set(result)].sort((a, b) => a - b);
    }
    if (expr.includes('-')) {
      const [start, end] = expr.split('-').map(Number);
      if (isNaN(start) || isNaN(end)) throw new Error(`${fieldName} 范围格式错误: ${expr}`);
      for (let i = Math.max(min, start); i <= Math.min(max, end); i++) result.push(i);
      return result;
    }
    if (expr.includes('/')) {
      const [range, step] = expr.split('/');
      const stepNum = Number(step);
      if (isNaN(stepNum) || stepNum <= 0) throw new Error(`${fieldName} 步长格式错误: ${expr}`);
      let start = min, end = max;
      if (range !== '*') {
        if (range.includes('-')) {
          const [s, e] = range.split('-').map(Number);
          start = Math.max(min, s); end = Math.min(max, e);
        } else {
          start = Number(range);
        }
      }
      for (let i = start; i <= end; i += stepNum) result.push(i);
      return result;
    }
    const num = Number(expr);
    if (isNaN(num)) {
      const weekMap: { [key: string]: number } = {
        'SUN': 1, 'MON': 2, 'TUE': 3, 'WED': 4, 'THU': 5, 'FRI': 6, 'SAT': 7,
        '周日': 1, '周一': 2, '周二': 3, '周三': 4, '周四': 5, '周五': 6, '周六': 7,
      };
      if (weekMap[expr.toUpperCase()]) return [weekMap[expr.toUpperCase()]];
      throw new Error(`${fieldName} 格式错误: ${expr}`);
    }
    if (num >= min && num <= max) return [num];
    throw new Error(`${fieldName} 值超出范围 [${min}-${max}]: ${expr}`);
  };

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

  const cronExamples = [
    { label: intl.formatMessage({ id: 'pages.job.cronExamples.every5Seconds', defaultMessage: '每5秒执行' }), value: '0/5 * * * * ?' },
    { label: intl.formatMessage({ id: 'pages.job.cronExamples.everyMinute', defaultMessage: '每分钟执行' }), value: '0 * * * * ?' },
    { label: intl.formatMessage({ id: 'pages.job.cronExamples.everyHour', defaultMessage: '每小时执行' }), value: '0 0 * * * ?' },
    { label: intl.formatMessage({ id: 'pages.job.cronExamples.everyDayAtMidnight', defaultMessage: '每天0点执行' }), value: '0 0 0 * * ?' },
    { label: intl.formatMessage({ id: 'pages.job.cronExamples.everyDayAt8AM', defaultMessage: '每天8点执行' }), value: '0 0 8 * * ?' },
    { label: intl.formatMessage({ id: 'pages.job.cronExamples.everyMondayAtMidnight', defaultMessage: '每周一0点执行' }), value: '0 0 0 ? * MON' },
    { label: intl.formatMessage({ id: 'pages.job.cronExamples.everyMonthFirstDay', defaultMessage: '每月1日0点执行' }), value: '0 0 0 1 * ?' },
  ];

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="lg"
      titleConfig={{
        mainTitle: isUpdate
          ? intl.formatMessage({ id: 'pages.job.edit', defaultMessage: '编辑定时任务' })
          : intl.formatMessage({ id: 'pages.job.create', defaultMessage: '新建定时任务' }),
        subtitle: isUpdate
          ? intl.formatMessage({ id: 'pages.job.edit.subtitle', defaultMessage: '修改任务配置，保存后即时生效' })
          : intl.formatMessage({ id: 'pages.job.create.subtitle', defaultMessage: '配置定时任务的基本信息和执行规则' }),
        icon: <FieldTimeOutlined />,
        iconGradient: 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)',
        iconShadowColor: 'rgba(79, 110, 247, 0.25)',
      }}
    >
      {/* 预览执行时间弹窗（内嵌的，保持原生 Modal） */}
      <Modal
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '4px 0' }}>
            <div style={{
              width: '32px', height: '32px', borderRadius: '8px',
              background: 'linear-gradient(135deg, var(--vip-success) 0%, #95de64 100%)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              boxShadow: '0 4px 12px rgba(82, 196, 26, 0.25)'
            }}>
              <ClockCircleOutlined style={{ fontSize: '16px', color: '#fff' }} />
            </div>
            <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--vip-text-primary)', lineHeight: '1.2' }}>
              {intl.formatMessage({ id: 'pages.job.previewExecution', defaultMessage: '预览执行时间' })}
            </div>
          </div>
        }
        open={previewModalVisible}
        onCancel={() => setPreviewModalVisible(false)}
        footer={[
          <Button key="close" onClick={() => setPreviewModalVisible(false)}
            style={{ fontSize: '12px', fontWeight: 500, height: '32px', padding: '4px 20px', borderRadius: '6px' }}>
            {intl.formatMessage({ id: 'pages.common.close', defaultMessage: 'Close' })}
          </Button>,
        ]}
        centered width={600}
        styles={{ body: { padding: '24px 28px' } }}
      >
        {nextExecutions.length > 0 ? (
          <Space direction="vertical" size={12} style={{ width: '100%', padding: '16px 0' }}>
            {nextExecutions.map((time, index) => (
              <div key={index} style={{
                display: 'flex', alignItems: 'center', padding: '12px 16px',
                background: index === 0 ? 'rgba(82, 196, 26, 0.08)' : 'rgba(79, 110, 247, 0.06)',
                border: `1px solid ${index === 0 ? 'rgba(82, 196, 26, 0.3)' : 'rgba(79, 110, 247, 0.2)'}`,
                borderRadius: '8px',
              }}>
                <ClockCircleOutlined style={{ marginRight: 12, fontSize: '14px', color: index === 0 ? 'var(--vip-success)' : 'var(--vip-primary)' }} />
                <div>
                  <Tag color={index === 0 ? 'green' : 'blue'} style={{ marginRight: 8 }}>
                    {index === 0
                      ? intl.formatMessage({ id: 'pages.job.nextExecution', defaultMessage: '下次执行' })
                      : intl.formatMessage({ id: 'pages.job.executionTimes', defaultMessage: '第 {count} 次' }, { count: index + 1 })
                    }
                  </Tag>
                  <Text strong style={{ fontSize: '13px' }}>{time}</Text>
                </div>
              </div>
            ))}
          </Space>
        ) : (
          <Alert
            message={intl.formatMessage({ id: 'pages.job.noPredictionData', defaultMessage: '暂无预测数据' })}
            description={intl.formatMessage({ id: 'pages.job.cronParseError', defaultMessage: 'Cron 表达式解析失败或格式错误' })}
            type="warning" showIcon
          />
        )}
      </Modal>

      <Form
        form={form}
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
        initialValues={{ jobGroup: 'DEFAULT', concurrent: 1 }}
      >
        <Form.Item name="jobName" label={intl.formatMessage({ id: 'pages.job.jobName', defaultMessage: '任务名称' })}
          rules={[
            { required: true, message: intl.formatMessage({ id: 'pages.job.jobName.required', defaultMessage: '请输入任务名称' }) },
            { max: 100, message: intl.formatMessage({ id: 'pages.job.jobName.max', defaultMessage: '任务名称长度不能超过100' }) },
          ]}>
          <Input placeholder={intl.formatMessage({ id: 'pages.job.jobName.placeholder', defaultMessage: '请输入任务名称' })} />
        </Form.Item>

        <Form.Item name="jobGroup" label={intl.formatMessage({ id: 'pages.job.jobGroup', defaultMessage: '任务组名' })}
          rules={[
            { required: true, message: intl.formatMessage({ id: 'pages.job.jobGroup.required', defaultMessage: '请输入任务组名' }) },
            { max: 100, message: intl.formatMessage({ id: 'pages.job.jobGroup.max', defaultMessage: '任务组名长度不能超过100' }) },
          ]}>
          <Input placeholder={intl.formatMessage({ id: 'pages.job.jobGroup.placeholder', defaultMessage: '请输入任务组名，默认：DEFAULT' })} />
        </Form.Item>

        <Form.Item name="jobClass" label={intl.formatMessage({ id: 'pages.job.jobClass', defaultMessage: '执行类' })}
          rules={[
            { required: true, message: intl.formatMessage({ id: 'pages.job.jobClass.required', defaultMessage: '请输入执行类全路径' }) },
            { max: 255, message: intl.formatMessage({ id: 'pages.job.jobClass.max', defaultMessage: '执行类长度不能超过 255' }) },
          ]}
          tooltip={{ title: intl.formatMessage({ id: 'pages.job.jobClass.tooltip', defaultMessage: '必须是继承 BaseJob 的类全路径，例如：job.com.agnetix.harnax.admin.SampleJob' }), icon: <QuestionCircleOutlined /> }}>
          <Input placeholder={intl.formatMessage({ id: 'pages.job.jobClass.placeholder', defaultMessage: '例如：job.com.agnetix.harnax.admin.SampleJob' })} />
        </Form.Item>
        
        <Form.Item label={intl.formatMessage({ id: 'pages.job.cronExamples', defaultMessage: '常用表达式' })}>
          <Select placeholder={intl.formatMessage({ id: 'pages.job.cronExamples.placeholder', defaultMessage: '选择常用表达式' })}
            options={cronExamples}
            onChange={(value) => {
              if (value) { form.setFieldsValue({ cronExpression: value }); predictNextExecutions(value); }
              else { form.setFieldsValue({ cronExpression: '' }); setNextExecutions([]); setCronError(''); }
            }}
            style={{ width: '100%' }} allowClear />
        </Form.Item>
        
        <Form.Item name="cronExpression" label={intl.formatMessage({ id: 'pages.job.cronExpression', defaultMessage: 'Cron 表达式' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.job.cronExpression.required', defaultMessage: '请输入 Cron 表达式' }) }]}
          tooltip={{ title: intl.formatMessage({ id: 'pages.job.cronExpression.tooltip', defaultMessage: '格式：秒 分 时 日 月 周 年（可选）。示例：0 0 12 * * ? 表示每天中午12点执行' }), icon: <QuestionCircleOutlined /> }}>
          <Space.Compact style={{ width: '100%' }}>
            <Input placeholder={intl.formatMessage({ id: 'pages.job.cronExpression.placeholder', defaultMessage: '例如：0/5 * * * * ?' })}
              onChange={(e) => predictNextExecutions(e.target.value)} />
            <Button type="primary" icon={<EyeOutlined />}
              onClick={() => {
                const cronValue = form.getFieldValue('cronExpression');
                if (cronValue && !cronError && nextExecutions.length > 0) { setPreviewModalVisible(true); }
                else if (!cronValue) { setCronError('请先输入 Cron 表达式'); }
                else { predictNextExecutions(cronValue); setTimeout(() => { const currentError = form.getFieldError('cronExpression'); if (!currentError || currentError.length === 0) { setPreviewModalVisible(true); } }, 100); }
              }}
              disabled={!form.getFieldValue('cronExpression') || !!cronError}>
              {intl.formatMessage({ id: 'pages.job.previewExecution', defaultMessage: '预览执行时间' })}
            </Button>
          </Space.Compact>
        </Form.Item>

        {cronError && (
          <Alert message={intl.formatMessage({ id: 'pages.job.cronError.title', defaultMessage: '表达式解析错误' })}
            description={cronError} type="error" showIcon style={{ marginBottom: 16 }} />
        )}

        <Form.Item name="concurrent" label={intl.formatMessage({ id: 'pages.job.concurrent', defaultMessage: '并发执行' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.job.concurrent.required', defaultMessage: '请选择是否允许并发' }) }]}>
          <Radio.Group>
            <Radio value={1}>{intl.formatMessage({ id: 'pages.job.concurrent.allow', defaultMessage: '允许' })}</Radio>
            <Radio value={0}>{intl.formatMessage({ id: 'pages.job.concurrent.deny', defaultMessage: '禁止' })}</Radio>
          </Radio.Group>
        </Form.Item>

        <Form.Item name="description" label={intl.formatMessage({ id: 'pages.job.description', defaultMessage: '任务描述' })}
          rules={[{ max: 500, message: intl.formatMessage({ id: 'pages.job.description.max', defaultMessage: '任务描述长度不能超过500' }) }]}>
          <TextArea rows={3} placeholder={intl.formatMessage({ id: 'pages.job.description.placeholder', defaultMessage: '请输入任务描述（可选）' })} />
        </Form.Item>

        <Form.Item label={intl.formatMessage({ id: 'pages.job.isPublic', defaultMessage: '是否公开' })}>
          <Switch checked={isPublic} onChange={setIsPublic}
            checkedChildren={intl.formatMessage({ id: 'pages.job.isPublic.public', defaultMessage: '公开' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.job.isPublic.private', defaultMessage: '私有' })}
            disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, !isUpdate)} />
        </Form.Item>

        <Form.Item wrapperCol={{ span: 24 }}>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px', paddingTop: '10px', borderTop: '1px solid var(--vip-border)' }}>
            <Button onClick={() => form.resetFields()} style={{ fontSize: '12px', fontWeight: 500, height: '32px', padding: '4px 20px', borderRadius: '6px' }}>
              {intl.formatMessage({ id: 'pages.job.reset', defaultMessage: 'Reset' })}
            </Button>
            <Button type="primary" onClick={handleSubmit} style={{ fontSize: '12px', fontWeight: 500, height: '32px', padding: '4px 20px', borderRadius: '6px' }}>
              {intl.formatMessage({ id: 'pages.job.submit', defaultMessage: 'Submit' })}
            </Button>
          </div>
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default JobForm;
