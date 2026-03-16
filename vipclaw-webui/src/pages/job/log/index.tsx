import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useSearchParams } from '@umijs/max';
import { Button, Card, DatePicker, Input, message, Modal, Select, Space, Tag, Typography } from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import { cleanJobLogs, getJobLogPage } from '@/services/ant-design-pro/job';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  DeleteOutlined,
  HistoryOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';

const { Text } = Typography;
const { RangePicker } = DatePicker;

const JobLog: React.FC = () => {
  const actionRef = useRef<ActionType | null>(null);
  const [searchParams] = useSearchParams();

  const [tableLoading, setTableLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.JobLogItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [current, setCurrent] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const [jobName, setJobName] = useState<string>(searchParams.get('jobName') || '');
  const [jobId, setJobId] = useState<number | undefined>(
    searchParams.get('jobId') ? Number(searchParams.get('jobId')) : undefined,
  );
  const [status, setStatus] = useState<number | undefined>(undefined);
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null] | null>(null);

  const [messageApi, contextHolder] = message.useMessage();

  /** 加载数据 */
  const loadData = async (page = current, size = pageSize) => {
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

  /** 清理日志 */
  const handleCleanLogs = async () => {
    Modal.confirm({
      title: '清理日志',
      content: '请选择要清理多少天前的日志',
      okText: '确定',
      cancelText: '取消',
      onOk: async () => {
        try {
          await cleanJobLogs(30);
          messageApi.success('日志清理成功');
          loadData();
        } catch (error) {
          messageApi.error('清理失败，请重试');
        }
      },
    });
  };

  /** 查看异常详情 */
  const handleViewException = (record: API.JobLogItem) => {
    Modal.info({
      title: '异常详情',
      width: 800,
      content: (
        <div style={{ maxHeight: 400, overflow: 'auto' }}>
          <pre style={{ background: '#f5f5f5', padding: 16, borderRadius: 4 }}>
            {record.exceptionInfo}
          </pre>
        </div>
      ),
      onOk() {},
    });
  };

  const columns: ProColumns<API.JobLogItem>[] = [
    {
      title: '日志ID',
      dataIndex: 'id',
      valueType: 'text',
      width: 80,
    },
    {
      title: '任务ID',
      dataIndex: 'jobId',
      valueType: 'text',
      width: 80,
    },
    {
      title: '任务名称',
      dataIndex: 'jobName',
      valueType: 'text',
      ellipsis: true,
    },
    {
      title: '任务组名',
      dataIndex: 'jobGroup',
      valueType: 'text',
      width: 120,
    },
    {
      title: '调用目标',
      dataIndex: 'invokeTarget',
      valueType: 'text',
      ellipsis: true,
    },
    {
      title: '执行状态',
      dataIndex: 'status',
      filters: true,
      onFilter: true,
      valueEnum: {
        0: { text: '失败', status: 'Error' },
        1: { text: '成功', status: 'Success' },
      },
      render: (_, record) => {
        return record.status === 1 ? (
          <Tag color="success" icon={<CheckCircleOutlined />}>
            成功
          </Tag>
        ) : (
          <Tag color="error" icon={<CloseCircleOutlined />}>
            失败
          </Tag>
        );
      },
      width: 100,
    },
    {
      title: '执行信息',
      dataIndex: 'jobMessage',
      valueType: 'text',
      ellipsis: true,
    },
    {
      title: '开始时间',
      dataIndex: 'startTime',
      valueType: 'dateTime',
      width: 180,
    },
    {
      title: '结束时间',
      dataIndex: 'endTime',
      valueType: 'dateTime',
      width: 180,
    },
    {
      title: '耗时(ms)',
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
      title: '操作',
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
              查看异常
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
          <span style={{ fontSize: '20px', fontWeight: 600, color: '#1a1a2e' }}>
            <HistoryOutlined style={{ marginRight: 10, color: '#4f6ef7' }} />
            任务执行日志
          </span>
        ),
      }}
    >
      {contextHolder}

      {/* 搜索和工具栏 */}
      <Card
        style={{ marginBottom: 24, borderRadius: '12px', boxShadow: '0 2px 12px rgba(0,0,0,0.04)' }}
        styles={{ body: { padding: '16px 20px' } }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <Input
            placeholder="搜索任务名称"
            prefix={<SearchOutlined />}
            value={jobName}
            onChange={(e) => setJobName(e.target.value)}
            onPressEnter={handleSearch}
            style={{ width: 200, borderRadius: '8px' }}
            allowClear
          />
          <Select
            placeholder="执行状态"
            value={status}
            onChange={(val) => setStatus(val)}
            style={{ width: 140, borderRadius: '8px' }}
            allowClear
            options={[
              { label: '成功', value: 1 },
              { label: '失败', value: 0 },
            ]}
          />
          <RangePicker
            showTime
            value={dateRange}
            onChange={(dates) => setDateRange(dates)}
            style={{ borderRadius: '8px' }}
            placeholder={['开始时间', '结束时间']}
          />
          <Button type="primary" onClick={handleSearch} style={{ borderRadius: '8px' }}>
            查询
          </Button>
          <Button
            onClick={() => {
              setJobName('');
              setJobId(undefined);
              setStatus(undefined);
              setDateRange(null);
              setCurrent(1);
              loadData(1);
            }}
            style={{ borderRadius: '8px' }}
          >
            重置
          </Button>
          <div style={{ flex: 1 }} />
          <Button
            danger
            icon={<DeleteOutlined />}
            onClick={handleCleanLogs}
            style={{ borderRadius: '8px' }}
          >
            清理日志
          </Button>
        </div>
      </Card>

      <ProTable<API.JobLogItem>
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
    </PageContainer>
  );
};

export default JobLog;
