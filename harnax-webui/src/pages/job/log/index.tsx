import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer } from '@ant-design/pro-components';
import { useIntl, useSearchParams } from '@umijs/max';
import { Button, Card, DatePicker, Input, message, Select, Tag, Typography } from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import { getJobLogPage } from '@/services/ant-design-pro/job';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  HistoryOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import SearchFilterBar, { SearchInput, FilterSelect, FilterDatePicker } from '@/components/SearchFilterBar';
import StyledProTable from '@/components/StyledProTable';

const { Text } = Typography;
const { RangePicker } = DatePicker;

const JobLog: React.FC = () => {
  const intl = useIntl();
  const actionRef = useRef<ActionType | null>(null);
  const [searchParams] = useSearchParams();

  const [tableLoading, setTableLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.JobLogItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const [jobName, setJobName] = useState<string>(searchParams.get('jobName') || '');
  const [jobId, setJobId] = useState<number | undefined>(
    searchParams.get('jobId') ? Number(searchParams.get('jobId')) : undefined,
  );
  const [status, setStatus] = useState<number | undefined>(undefined);
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null] | null>(null);
  
  // 防抖定时器引用
  const searchTimerRef = useRef<NodeJS.Timeout | null>(null);
  
  // 使用 ref 保存最新的筛选参数，避免闭包问题
  const filtersRef = useRef({
    jobName: searchParams.get('jobName') || '',
    jobId: searchParams.get('jobId') ? Number(searchParams.get('jobId')) : undefined as number | undefined,
    status: undefined as number | undefined,
    dateRange: null as [dayjs.Dayjs | null, dayjs.Dayjs | null] | null,
  });

  const [messageApi, contextHolder] = message.useMessage();

  /** 加载数据（使用 ref 中的最新筛选参数，避免闭包问题） */
  const loadDataWithFilters = async (page = 1, size = pageSize) => {
    const { jobName: jn, jobId: jid, status: st, dateRange: dr } = filtersRef.current;
    
    setTableLoading(true);
    try {
      const res = await getJobLogPage({
        pageNum: page,
        pageSize: size,
        jobId: jid,
        jobName: jn || undefined,
        status: st,
        startTime: dr?.[0]?.format('YYYY-MM-DD HH:mm:ss'),
        endTime: dr?.[1]?.format('YYYY-MM-DD HH:mm:ss'),
      });
      setData(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.job.log.fetchFailed', defaultMessage: 'Failed to fetch data' }));
    } finally {
      setTableLoading(false);
    }
  };

  useEffect(() => {
    loadDataWithFilters(pageNum, pageSize);
  }, [pageNum, pageSize]);
  
  // 组件卸载时清理定时器
  useEffect(() => {
    return () => {
      if (searchTimerRef.current) {
        clearTimeout(searchTimerRef.current);
      }
    };
  }, []);

  /** 关键词变化（带防抖） */
  const handleJobNameChange = (value: string) => {
    setJobName(value);
    filtersRef.current.jobName = value;
    
    if (searchTimerRef.current) {
      clearTimeout(searchTimerRef.current);
    }
    
    searchTimerRef.current = setTimeout(() => {
      setPageNum(1);
      loadDataWithFilters(1);
    }, 500);
  };
  
  /** 状态筛选改变 */
  const handleStatusChange = (value: number | undefined) => {
    setStatus(value);
    filtersRef.current.status = value;
    setPageNum(1);
    loadDataWithFilters(1);
  };
  
  /** 日期范围改变 */
  const handleDateRangeChange = (dates: [dayjs.Dayjs | null, dayjs.Dayjs | null] | null, dateStrings: [string, string]) => {
    setDateRange(dates);
    filtersRef.current.dateRange = dates;
    setPageNum(1);
    loadDataWithFilters(1);
  };
  
  /** 重置筛选 */
  const handleReset = () => {
    setJobName('');
    setJobId(undefined);
    setStatus(undefined);
    setDateRange(null);
    filtersRef.current = {
      jobName: '',
      jobId: undefined,
      status: undefined,
      dateRange: null,
    };
    setPageNum(1);
    loadDataWithFilters(1);
  };

  const columns: ProColumns<API.JobLogItem>[] = [
    {
      title: intl.formatMessage({ id: 'pages.common.id', defaultMessage: 'ID' }),
      dataIndex: 'id',
      valueType: 'text',
      width: 80,
    },
    {
      title: intl.formatMessage({ id: 'pages.job.log.column.jobId', defaultMessage: 'Task ID' }),
      dataIndex: 'jobId',
      valueType: 'text',
      width: 80,
    },
    {
      title: intl.formatMessage({ id: 'pages.job.log.column.jobName', defaultMessage: 'Task Name' }),
      dataIndex: 'jobName',
      valueType: 'text',
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.job.log.column.jobGroup', defaultMessage: 'Task Group' }),
      dataIndex: 'jobGroup',
      valueType: 'text',
      width: 120,
    },
    {
      title: intl.formatMessage({ id: 'pages.job.log.column.invokeTarget', defaultMessage: 'Invoke Target' }),
      dataIndex: 'invokeTarget',
      valueType: 'text',
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.job.log.column.status', defaultMessage: 'Execution Status' }),
      dataIndex: 'status',
      filters: true,
      onFilter: true,
      valueEnum: {
        0: { text: intl.formatMessage({ id: 'pages.job.log.status.failed', defaultMessage: 'Failed' }), status: 'Error' },
        1: { text: intl.formatMessage({ id: 'pages.job.log.status.success', defaultMessage: 'Success' }), status: 'Success' },
      },
      render: (_, record) => {
        return record.status === 1 ? (
          <Tag color="success" icon={<CheckCircleOutlined />}>
            {intl.formatMessage({ id: 'pages.job.log.status.success', defaultMessage: 'Success' })}
          </Tag>
        ) : (
          <Tag color="error" icon={<CloseCircleOutlined />}>
            {intl.formatMessage({ id: 'pages.job.log.status.failed', defaultMessage: 'Failed' })}
          </Tag>
        );
      },
      width: 100,
    },
    {
      title: intl.formatMessage({ id: 'pages.job.log.column.message', defaultMessage: 'Execution Message' }),
      dataIndex: 'jobMessage',
      valueType: 'text',
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.job.log.column.startTime', defaultMessage: 'Start Time' }),
      dataIndex: 'startTime',
      valueType: 'dateTime',
      width: 180,
    },
    {
      title: intl.formatMessage({ id: 'pages.job.log.column.endTime', defaultMessage: 'End Time' }),
      dataIndex: 'endTime',
      valueType: 'dateTime',
      width: 180,
    },
    {
      title: intl.formatMessage({ id: 'pages.job.log.column.duration', defaultMessage: 'Duration(ms)' }),
      dataIndex: 'duration',
      valueType: 'text',
      render: (_, record) => {
        const duration = record.duration;
        if (duration === undefined || duration === null) return '-';
        return (
          <Tag color={duration > 5000 ? 'orange' : 'green'}>
            {duration}ms
          </Tag>
        );
      },
      width: 100,
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '18px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <HistoryOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.job.log.title', defaultMessage: 'Task Execution Log' })}
          </span>
        ),
      }}
    >
      {contextHolder}

      {/* 搜索和工具栏 */}
      <SearchFilterBar
        onSearch={() => {}}
        onReset={handleReset}
        showSearchButton={false}
        searchText={intl.formatMessage({ id: 'pages.common.search', defaultMessage: 'Search' })}
        resetText={intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
      >
        <SearchInput
          value={jobName}
          onChange={handleJobNameChange}
          placeholder={intl.formatMessage({ id: 'pages.job.log.search.placeholder', defaultMessage: 'Search task name' })}
          width="auto"
        />
        <FilterSelect
          value={status}
          onChange={handleStatusChange}
          placeholder={intl.formatMessage({ id: 'pages.job.log.filter.status', defaultMessage: 'Execution status' })}
          width="auto"
          options={[
            { label: intl.formatMessage({ id: 'pages.job.log.status.success', defaultMessage: 'Success' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.job.log.status.failed', defaultMessage: 'Failed' }), value: 0 },
          ]}
        />
        <FilterDatePicker
          value={dateRange}
          onChange={handleDateRangeChange}
          placeholder={[intl.formatMessage({ id: 'pages.job.log.filter.startTime', defaultMessage: 'Start time' }), intl.formatMessage({ id: 'pages.job.log.filter.endTime', defaultMessage: 'End time' })]}
          showTime
          width="auto"
        />
      </SearchFilterBar>

      <StyledProTable<API.JobLogItem>
        headerTitle={undefined}
        rowKey="id"
        loading={tableLoading}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (t) => intl.formatMessage(
            { id: 'pages.common.pagination.total', defaultMessage: 'Total {total} items' },
            { total: t }
          ),
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
    </PageContainer>
  );
};

export default JobLog;
