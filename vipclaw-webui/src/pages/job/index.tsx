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
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';

const { Text } = Typography;

const JobManagement: React.FC = () => {
  const actionRef = useRef<ActionType | null>(null);

  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<API.JobItem>();
  const [tableLoading, setTableLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.JobItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
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
  const loadData = async (page = pageNum, size = pageSize) => {
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
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
    } finally {
      setTableLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [pageNum, pageSize]);

  /** 搜索 */
  const handleSearch = () => {
    setPageNum(1);
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
          const response = await deleteJob(jobId);
          hide();
          if (response.code === 200) {
            messageApi.success(intl.formatMessage({ id: 'pages.message.deleteSuccess', defaultMessage: 'Deleted successfully' }));
            loadData();
          } else {
            const errorMsg = response.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed, please try again' });
            messageApi.error(errorMsg);
          }
        } catch (error: any) {
          hide();
          const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed, please try again' });
          messageApi.error(errorMsg);
        }
      },
    });
  };

  /** 启动任务 */
  const handleStart = async (jobId: number) => {
    try {
      await startJob(jobId);
      messageApi.success(intl.formatMessage({ id: 'pages.message.operationSuccess', defaultMessage: 'Operation successful' }));
      loadData();
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
    }
  };

  /** 暂停任务 */
  const handlePause = async (jobId: number) => {
    try {
      await pauseJob(jobId);
      messageApi.success(intl.formatMessage({ id: 'pages.message.operationSuccess', defaultMessage: 'Operation successful' }));
      loadData();
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
    }
  };

  /** 立即执行 */
  const handleRunOnce = async (jobId: number) => {
    try {
      await runJobOnce(jobId);
      messageApi.success(intl.formatMessage({ id: 'pages.message.operationSuccess', defaultMessage: 'Operation successful' }));
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
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
          <Tag color="default" style={{ fontFamily: 'monospace', cursor: 'help', backgroundColor: 'var(--vip-primary)', color: 'white' }}>
            <ClockCircleOutlined style={{ marginRight: 4, color: 'white' }} />
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
          <Tag color="default" style={{ backgroundColor: 'var(--vip-success)', color: 'white' }} icon={<PlayCircleOutlined style={{ color: 'white' }} />}>运行中</Tag>
        ) : (
          <Tag color="default" style={{ backgroundColor: 'var(--vip-warning)', color: 'white' }} icon={<PauseOutlined style={{ color: 'white' }} />}>暂停</Tag>
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
          <Tag color="default" style={{ backgroundColor: 'var(--vip-success)', color: 'white' }}>允许</Tag>
        ) : (
          <Tag color="default" style={{ backgroundColor: 'var(--vip-warning)', color: 'white' }}>禁止</Tag>
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
      render: (_, record) => record.isPublic === 1 ? <Tag color="default" style={{ backgroundColor: 'var(--vip-primary)', color: 'white' }}>公开</Tag> : <Tag>私有</Tag>,
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
                icon={<CaretRightOutlined style={{ fontSize: 16, color: 'var(--vip-success)' }} />}
                onClick={() => handleStart(record.id!)}
              />
            </Tooltip>
          ) : (
            <Tooltip title="暂停">
              <Button
                type="text"
                size="small"
                icon={<PauseOutlined style={{ fontSize: 16, color: 'var(--vip-warning)' }} />}
                onClick={() => handlePause(record.id!)}
              />
            </Tooltip>
          )}
          <Tooltip title="立即执行">
            <Button
              type="text"
              size="small"
              icon={<PlayCircleOutlined style={{ fontSize: 16, color: 'var(--vip-info)' }} />}
              onClick={() => handleRunOnce(record.id!)}
            />
          </Tooltip>
          <Tooltip title="编辑">
            <Button
              type="text"
              size="small"
              icon={<EditOutlined style={{ fontSize: 16, color: 'var(--vip-primary)' }} />}
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
              icon={<DeleteOutlined style={{ fontSize: 16, color: 'var(--vip-danger)' }} />}
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
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <ScheduleOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.job.title', defaultMessage: 'Scheduled Tasks' })}
          </span>
        ),
      }}
    >
      {contextHolder}

      {/* 搜索和工具栏 */}
      <SearchFilterBar
        onSearch={handleSearch}
        onReset={() => {
          setKeyword('');
          setJobStatus(undefined);
          setPageNum(1);
          loadData(1);
        }}
        searchText={intl.formatMessage({ id: 'pages.common.search', defaultMessage: 'Search' })}
        resetText={intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
        extra={
          <ActionButton
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateModalVisible(true)}
          >
            {intl.formatMessage({ id: 'pages.job.create', defaultMessage: 'Create Task' })}
          </ActionButton>
        }
      >
        <SearchInput
          value={keyword}
          onChange={setKeyword}
          onSearch={handleSearch}
          placeholder={intl.formatMessage({ id: 'pages.job.search.placeholder', defaultMessage: 'Search task name' })}
          width="auto"
        />
        <FilterSelect
          value={jobStatus}
          onChange={setJobStatus}
          placeholder={intl.formatMessage({ id: 'pages.job.filter.status', defaultMessage: 'Status filter' })}
          width="auto"
          options={[
            { label: intl.formatMessage({ id: 'pages.job.status.running', defaultMessage: 'Running' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.job.status.paused', defaultMessage: 'Paused' }), value: 0 },
          ]}
        />
      </SearchFilterBar>

      <ProTable<API.JobItem>
        headerTitle={undefined}
        rowKey="id"
        loading={tableLoading}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (page, size) => {
            setPageNum(page);
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
            messageApi.success(intl.formatMessage({ id: 'pages.message.createSuccess', defaultMessage: 'Created successfully' }));
            setCreateModalVisible(false);
            loadData();
          } catch (error) {
            messageApi.error(intl.formatMessage({ id: 'pages.message.createFailed', defaultMessage: 'Create failed, please try again' }));
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
              messageApi.success(intl.formatMessage({ id: 'pages.message.updateSuccess', defaultMessage: 'Updated successfully' }));
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
