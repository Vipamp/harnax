import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { Button, Card, Input, message, Modal, Select, Space, Tag, Tooltip, Typography } from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import {
  createJob,
  deleteJob,
  getJobPage,
  pauseJob,
  runJobOnce,
  startJob,
  updateJob,
} from '@/services/ant-design-pro/job';
import JobForm from './components/JobForm';
import {
  CaretRightOutlined,
  ClockCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  HistoryOutlined,
  PauseOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ScheduleOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';

const { Text } = Typography;

const JobManagement: React.FC = () => {
  const actionRef = useRef<ActionType | null>(null);

  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<API.JobItem>();
  const [tableLoading, setTableLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.JobItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [current, setCurrent] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const [keyword, setKeyword] = useState<string>('');
  const [jobStatus, setJobStatus] = useState<number | undefined>(undefined);

  const intl = useIntl();
  const [messageApi, contextHolder] = message.useMessage();

  /**
   * 获取下次执行时间的提示文本
   */
  const getNextExecutionHint = (cronExpr: string): string => {
    if (!cronExpr) return '暂无表达式';
    
    try {
      const nextTime = getNextExecutionTime(cronExpr);
      if (nextTime) {
        return `预计下次执行：${formatDateTime(nextTime)}`;
      }
      return '无法计算下次执行时间';
    } catch (error) {
      return '表达式格式错误';
    }
  };

  /**
   * 获取下一次执行时间
   */
  const getNextExecutionTime = (cronExpr: string): Date | null => {
    const parts = cronExpr.trim().split(/\s+/);
    if (parts.length < 6) return null;

    const [secondExpr, minuteExpr, hourExpr, dayExpr, monthExpr, weekExpr] = parts;
    let candidate = new Date();
    candidate.setMilliseconds(0);

    // 最多尝试 4 年
    for (let yearOffset = 0; yearOffset < 4; yearOffset++) {
      const year = candidate.getFullYear() + yearOffset;
      const months = parseField(monthExpr, 1, 12);

      for (let mIdx = 0; mIdx < months.length; mIdx++) {
        const month = months[mIdx];
        if (yearOffset === 0 && month < candidate.getMonth() + 1) continue;

        const daysInMonth = new Date(year, month, 0).getDate();
        let days: number[];

        if (weekExpr !== '?' && weekExpr !== '*') {
          days = getDaysByWeek(year, month, weekExpr, daysInMonth);
        } else {
          days = parseField(dayExpr, 1, daysInMonth);
        }

        for (let dIdx = 0; dIdx < days.length; dIdx++) {
          const day = days[dIdx];
          if (yearOffset === 0 && month === candidate.getMonth() + 1 && day < candidate.getDate()) continue;

          const hours = parseField(hourExpr, 0, 23);
          for (let hIdx = 0; hIdx < hours.length; hIdx++) {
            const hour = hours[hIdx];
            if (yearOffset === 0 && month === candidate.getMonth() + 1 && day === candidate.getDate() && hour < candidate.getHours()) continue;

            const minutes = parseField(minuteExpr, 0, 59);
            for (let minIdx = 0; minIdx < minutes.length; minIdx++) {
              const minute = minutes[minIdx];
              if (yearOffset === 0 && month === candidate.getMonth() + 1 && day === candidate.getDate() && hour === candidate.getHours() && minute < candidate.getMinutes()) continue;

              const seconds = parseField(secondExpr, 0, 59);
              for (let sIdx = 0; sIdx < seconds.length; sIdx++) {
                const second = seconds[sIdx];
                if (yearOffset === 0 && month === candidate.getMonth() + 1 && day === candidate.getDate() && hour === candidate.getHours() && minute === candidate.getMinutes() && second <= candidate.getSeconds()) continue;

                const result = new Date(year, month - 1, day, hour, minute, second);
                if (result > new Date()) {
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
    const weekDays = parseField(weekExpr, 1, 7);

    for (let day = 1; day <= daysInMonth; day++) {
      const date = new Date(year, month - 1, day);
      const weekDay = date.getDay() === 0 ? 1 : date.getDay() + 1;
      if (weekDays.includes(weekDay)) {
        days.push(day);
      }
    }

    return days;
  };

  /**
   * 解析 Cron 字段
   */
  const parseField = (expr: string, min: number, max: number): number[] => {
    const result: number[] = [];

    if (expr === 'L' || expr === '?') return [min];
    if (expr === '*') {
      for (let i = min; i <= max; i++) result.push(i);
      return result;
    }

    if (expr.includes(',')) {
      const parts = expr.split(',');
      for (let idx = 0; idx < parts.length; idx++) {
        result.push(...parseField(parts[idx].trim(), min, max));
      }
      return [...new Set(result)].sort((a, b) => a - b);
    }

    if (expr.includes('-')) {
      const [start, end] = expr.split('-').map(Number);
      for (let i = Math.max(min, start); i <= Math.min(max, end); i++) {
        result.push(i);
      }
      return result;
    }

    if (expr.includes('/')) {
      const [range, step] = expr.split('/');
      const stepNum = Number(step);
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

    const num = Number(expr);
    if (!isNaN(num)) {
      return [num];
    }

    const weekMap: { [key: string]: number } = {
      'SUN': 1, 'MON': 2, 'TUE': 3, 'WED': 4, 'THU': 5, 'FRI': 6, 'SAT': 7,
    };
    if (weekMap[expr.toUpperCase()]) {
      return [weekMap[expr.toUpperCase()]];
    }

    return [min];
  };

  /**
   * 格式化日期时间
   */
  const formatDateTime = (date: Date): string => {
    const pad = (n: number) => n.toString().padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
  };

  /** 加载数据 */
  const loadData = async (page = current, size = pageSize) => {
    setTableLoading(true);
    try {
      const res = await getJobPage({
        pageNum: page,
        pageSize: size,
        keyword: keyword || undefined,
        jobStatus: jobStatus,
      });
      setData(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch (error) {
      messageApi.error('获取数据失败');
    } finally {
      setTableLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [current, pageSize]);

  /** 搜索 */
  const handleSearch = () => {
    setCurrent(1);
    loadData(1);
  };

  /** 删除节点 */
  const handleRemove = async (jobId: number) => {
    Modal.confirm({
      title: '确认删除该定时任务吗？',
      content: '此操作不可恢复，请谨慎操作',
      okText: '确定',
      cancelText: '取消',
      onOk: async () => {
        const hide = message.loading('正在删除');
        if (!jobId) return;
        try {
          await deleteJob(jobId);
          hide();
          messageApi.success('删除成功');
          loadData();
        } catch (error) {
          hide();
          messageApi.error('删除失败，请重试');
        }
      },
    });
  };

  /** 启动任务 */
  const handleStart = async (jobId: number) => {
    try {
      await startJob(jobId);
      messageApi.success('启动成功');
      loadData();
    } catch (error) {
      messageApi.error('启动失败，请重试');
    }
  };

  /** 暂停任务 */
  const handlePause = async (jobId: number) => {
    try {
      await pauseJob(jobId);
      messageApi.success('暂停成功');
      loadData();
    } catch (error) {
      messageApi.error('暂停失败，请重试');
    }
  };

  /** 立即执行 */
  const handleRunOnce = async (jobId: number) => {
    try {
      await runJobOnce(jobId);
      messageApi.success('任务已触发执行');
    } catch (error) {
      messageApi.error('执行失败，请重试');
    }
  };

  const columns: ProColumns<API.JobItem>[] = [
    {
      title: '任务 ID',
      dataIndex: 'id',
      valueType: 'text',
      hideInForm: true,
      hideInSearch: true,
      width: 60,
      align: 'center',
    },
    {
      title: '任务名称',
      dataIndex: 'jobName',
      valueType: 'text',
      hideInSearch: true,
      ellipsis: true,
      width: 150,
      align: 'center',
    },
    {
      title: '任务组名',
      dataIndex: 'jobGroup',
      valueType: 'text',
      hideInSearch: true,
      width: 120,
      align: 'center',
    },
    {
      title: 'Cron 表达式',
      dataIndex: 'cronExpression',
      valueType: 'text',
      hideInSearch: true,
      width: 180,
      align: 'center',
      render: (_, record) => (
        <Tooltip title={getNextExecutionHint(record.cronExpression || '')}>
          <Tag color="blue" style={{ fontFamily: 'monospace', cursor: 'help' }}>
            <ClockCircleOutlined style={{ marginRight: 4 }} />
            {record.cronExpression}
          </Tag>
        </Tooltip>
      ),
    },
    {
      title: '执行类',
      dataIndex: 'jobClass',
      valueType: 'text',
      hideInSearch: true,
      ellipsis: true,
      width: 280,
      align: 'center',
    },
    {
      title: '状态',
      dataIndex: 'jobStatus',
      filters: true,
      onFilter: true,
      valueEnum: {
        0: { text: '暂停', status: 'Default' },
        1: { text: '运行中', status: 'Success' },
      },
      render: (_, record) => {
        return record.jobStatus === 1 ? (
          <Tag color="success" icon={<PlayCircleOutlined />}>运行中</Tag>
        ) : (
          <Tag color="default" icon={<PauseOutlined />}>暂停</Tag>
        );
      },
      width: 80,
      align: 'center',
    },
    {
      title: '并发',
      dataIndex: 'concurrent',
      hideInSearch: true,
      render: (_, record) => {
        return record.concurrent === 1 ? (
          <Tag color="processing">允许</Tag>
        ) : (
          <Tag color="warning">禁止</Tag>
        );
      },
      width: 80,
      align: 'center',
    },
    {
      title: '描述',
      dataIndex: 'description',
      valueType: 'text',
      hideInSearch: true,
      ellipsis: true,
      width: 180,
      align: 'center',
    },
    {
      title: '是否公开',
      dataIndex: 'isPublic',
      valueType: 'text',
      hideInSearch: true,
      width: 100,
      align: 'center',
      render: (_, record) => record.isPublic === 1 ? <Tag color="blue">公开</Tag> : <Tag>私有</Tag>,
    },
    {
      title: '创建人',
      dataIndex: 'creator',
      valueType: 'text',
      hideInSearch: true,
      width: 120,
      align: 'center',
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      valueType: 'dateTime',
      hideInForm: true,
      hideInSearch: true,
      sorter: true,
      defaultSortOrder: 'descend',
      width: 150,
      align: 'center',
    },
    {
      title: '操作',
      valueType: 'option',
      key: 'option',
      width: 200,
      fixed: 'right',
      align: 'center',
      render: (text, record) => (
        <Space size={8}>
          {record.jobStatus === 0 ? (
            <Tooltip title="启动">
              <Button
                type="text"
                size="small"
                icon={<CaretRightOutlined style={{ fontSize: 16, color: '#52c41a' }} />}
                onClick={() => handleStart(record.id!)}
              />
            </Tooltip>
          ) : (
            <Tooltip title="暂停">
              <Button
                type="text"
                size="small"
                icon={<PauseOutlined style={{ fontSize: 16, color: '#faad14' }} />}
                onClick={() => handlePause(record.id!)}
              />
            </Tooltip>
          )}
          <Tooltip title="立即执行">
            <Button
              type="text"
              size="small"
              icon={<PlayCircleOutlined style={{ fontSize: 16, color: '#1890ff' }} />}
              onClick={() => handleRunOnce(record.id!)}
            />
          </Tooltip>
          <Tooltip title="编辑">
            <Button
              type="text"
              size="small"
              icon={<EditOutlined style={{ fontSize: 16, color: '#4f6ef7' }} />}
              onClick={() => {
                setCurrentRow(record);
                setUpdateModalVisible(true);
              }}
            />
          </Tooltip>
          <Tooltip title="删除">
            <Button
              type="text"
              size="small"
              icon={<DeleteOutlined style={{ fontSize: 16, color: '#ff4d4f' }} />}
              onClick={() => handleRemove(record.id!)}
            />
          </Tooltip>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: '#1a1a2e' }}>
            <ScheduleOutlined style={{ marginRight: 10, color: '#4f6ef7' }} />
            定时任务
          </span>
        ),
      }}
    >
      {contextHolder}

      {/* 搜索和工具栏 */}
      <Card
        style={{ 
          marginBottom: 24, 
          borderRadius: '16px', 
          boxShadow: '0 2px 12px rgba(0,0,0,0.04)',
          border: '1px solid #f0f0f8',
        }}
        styles={{ body: { padding: '20px 24px' } }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flex: 1, minWidth: 300 }}>
            <Input
              placeholder="搜索任务名称"
              prefix={<SearchOutlined style={{ color: '#8c8c9a' }} />}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onPressEnter={handleSearch}
              style={{ width: 260, borderRadius: '10px', height: '40px' }}
              allowClear
            />
            <Select
              placeholder="状态筛选"
              value={jobStatus}
              onChange={(val) => setJobStatus(val)}
              style={{ width: 140 }}
              allowClear
              options={[
                { label: '运行中', value: 1 },
                { label: '暂停', value: 0 },
              ]}
            />
            <Button 
              type="primary" 
              onClick={handleSearch} 
              style={{ borderRadius: '10px', height: '40px', padding: '0 20px' }}
            >
              查询
            </Button>
            <Button
              onClick={() => {
                setKeyword('');
                setJobStatus(undefined);
                setCurrent(1);
                loadData(1);
              }}
              style={{ borderRadius: '10px', height: '40px', padding: '0 20px' }}
            >
              重置
            </Button>
          </div>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateModalVisible(true)}
            style={{ 
              borderRadius: '10px', 
              height: '44px',
              padding: '0 24px',
              fontWeight: 600,
              fontSize: '14px',
              boxShadow: '0 4px 16px rgba(79, 110, 247, 0.3)',
            }}
          >
            新建任务
          </Button>
        </div>
      </Card>

      <ProTable<API.JobItem>
        headerTitle={undefined}
        rowKey="id"
        loading={tableLoading}
        pagination={{
          current,
          pageSize,
          total,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (page, size) => {
            setCurrent(page);
            if (size) setPageSize(size);
          },
        }}
        dataSource={data}
        search={false}
        toolBarRender={false}
        columns={columns}
      />

      {/* 新建任务弹窗 */}
      <JobForm
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (values: API.SysJobCreateRequest) => {
          try {
            await createJob(values);
            messageApi.success('创建成功');
            setCreateModalVisible(false);
            loadData();
          } catch (error) {
            messageApi.error('创建失败，请重试');
          }
        }}
        visible={createModalVisible}
      />

      {/* 更新任务弹窗 */}
      {currentRow && (
        <JobForm
          onSubmit={async (values) => {
            try {
              await updateJob(currentRow.id || 0, values as API.SysJobUpdateRequest);
              messageApi.success('更新成功');
              setUpdateModalVisible(false);
              setCurrentRow(undefined);
              loadData();
            } catch (error) {
              messageApi.error('更新失败，请重试');
            }
          }}
          onCancel={() => {
            setUpdateModalVisible(false);
            setCurrentRow(undefined);
          }}
          visible={updateModalVisible}
          values={currentRow}
        />
      )}
    </PageContainer>
  );
};

export default JobManagement;
