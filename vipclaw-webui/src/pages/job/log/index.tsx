import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl, useSearchParams } from '@umijs/max';
import { Button, Card, DatePicker, Input, message, Modal, Select, Space, Tag, Typography } from 'antd';
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

  const [messageApi, contextHolder] = message.useMessage();

  /** 加载数据 */
  const loadData = async (page = pageNum, size = pageSize) => {
    setTableLoading(true);
    try {
      const res = await getJobLogPage({
        pageNum: page,
        pageSize: size,
        jobId: jobId,
        jobName: jobName || undefined,
        status: status,
        startTime: dateRange?.[0]?.format('YYYY-MM-DD HH:mm:ss'),
        endTime: dateRange?.[1]?.format('YYYY-MM-DD HH:mm:ss'),
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
    loadData();
  }, [pageNum, pageSize]);

  /** 搜索 */
  const handleSearch = () => {
    setPageNum(1);
    loadData(1);
  };

  /** 查看异常详情 */
  const handleViewException = (record: API.JobLogItem) => {
    Modal.info({
      title: intl.formatMessage({ id: 'pages.job.log.exceptionDetail', defaultMessage: 'Exception Details' }),
      width: 800,
      content: (
        <div style={{ maxHeight: 400, overflow: 'auto' }}>
          <pre style={{ background: 'var(--vip-bg-layout)', padding: 16, borderRadius: 4 }}>
            {record.exceptionInfo}
          </pre>
        </div>
      ),
      onOk() {},
    });
  };

  const columns: ProColumns<API.JobLogItem>[] = [
    {
      title: intl.formatMessage({ id: 'pages.job.log.column.logId', defaultMessage: 'Log ID' }),
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
    {
      title: intl.formatMessage({ id: 'pages.common.operation', defaultMessage: 'Operation' }),
      valueType: 'option',
      key: 'option',
      width: 120,
      render: (text, record) => (
        <Space size={4}>
          {record.status === 0 && record.exceptionInfo && (
            <Button
              type="link"
              size="small"
              danger
              onClick={() => handleViewException(record)}
            >
              {intl.formatMessage({ id: 'pages.job.log.viewException', defaultMessage: 'View Exception' })}
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <HistoryOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.job.log.title', defaultMessage: 'Task Execution Log' })}
          </span>
        ),
      }}
    >
      {contextHolder}

      {/* 搜索和工具栏 */}
      <SearchFilterBar
        onSearch={handleSearch}
        onReset={() => {
          setJobName('');
          setJobId(undefined);
          setStatus(undefined);
          setDateRange(null);
          setPageNum(1);
          loadData(1);
        }}
        searchText={intl.formatMessage({ id: 'pages.common.search', defaultMessage: 'Search' })}
        resetText={intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
        showSearchButton={true}
        showResetButton={true}
      >
        <SearchInput
          value={jobName}
          onChange={setJobName}
          onSearch={handleSearch}
          placeholder={intl.formatMessage({ id: 'pages.job.log.search.placeholder', defaultMessage: 'Search task name' })}
          width="auto"
        />
        <FilterSelect
          value={status}
          onChange={setStatus}
          placeholder={intl.formatMessage({ id: 'pages.job.log.filter.status', defaultMessage: 'Execution status' })}
          width="auto"
          options={[
            { label: intl.formatMessage({ id: 'pages.job.log.status.success', defaultMessage: 'Success' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.job.log.status.failed', defaultMessage: 'Failed' }), value: 0 },
          ]}
        />
        <FilterDatePicker
          value={dateRange}
          onChange={setDateRange}
          placeholder={[intl.formatMessage({ id: 'pages.job.log.filter.startTime', defaultMessage: 'Start time' }), intl.formatMessage({ id: 'pages.job.log.filter.endTime', defaultMessage: 'End time' })]}
          showTime
          width="auto"
        />
      </SearchFilterBar>

      <ProTable<API.JobLogItem>
        headerTitle={undefined}
        rowKey="id"
        loading={tableLoading}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t) => intl.formatMessage({ id: 'pages.common.pagination.total', defaultMessage: 'Total {total} items' }, { total: t }),
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
