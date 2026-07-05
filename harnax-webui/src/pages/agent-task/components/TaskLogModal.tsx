import React, { useState, useEffect, useCallback } from 'react';
import { Table, Tag, Input, Select, DatePicker, Button, Space, message } from 'antd';
import { useIntl } from '@umijs/max';
import { FileSearchOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import { FormModal } from '@/components/FormModal';
import { getAgentTaskLogs } from '@/services/ant-design-pro/agentTask';

const { RangePicker } = DatePicker;

interface TaskLogModalProps {
  visible: boolean;
  task: API.AgentTaskItem | null;
  onCancel: () => void;
}

const TaskLogModal: React.FC<TaskLogModalProps> = ({ visible, task, onCancel }) => {
  const intl = useIntl();

  const [logs, setLogs] = useState<API.AgentTaskLogItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  const [timeRange, setTimeRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<number | undefined>(undefined);

  const loadLogs = useCallback(async () => {
    if (!task) return;
    setLoading(true);
    try {
      const response = await getAgentTaskLogs(task.id, {
        pageNum,
        pageSize,
        status: statusFilter,
        startTimeFrom: timeRange?.[0]?.format('YYYY-MM-DD HH:mm:ss'),
        startTimeTo: timeRange?.[1]?.format('YYYY-MM-DD HH:mm:ss'),
        keyword: keyword || undefined,
      });
      if (response.code === 200 && response.data) {
        setLogs(response.data.records || []);
        setTotal(response.data.total || 0);
      } else {
        message.error(response.message || intl.formatMessage({ id: 'pages.agentTask.loadLogsFailed', defaultMessage: 'Failed to load logs' }));
      }
    } catch {
      message.error(intl.formatMessage({ id: 'pages.agentTask.loadLogsFailed', defaultMessage: 'Failed to load logs' }));
    } finally {
      setLoading(false);
    }
  }, [task, pageNum, pageSize, statusFilter, timeRange, keyword, intl]);

  useEffect(() => {
    if (visible && task) {
      loadLogs();
    }
  }, [visible, task, pageNum, pageSize]);

  useEffect(() => {
    if (!visible) {
      setLogs([]);
      setTotal(0);
      setPageNum(1);
      setTimeRange(null);
      setKeyword('');
      setStatusFilter(undefined);
    }
  }, [visible]);

  const handleSearch = () => {
    setPageNum(1);
    loadLogs();
  };

  const handleReset = () => {
    setTimeRange(null);
    setKeyword('');
    setStatusFilter(undefined);
    setPageNum(1);
    // loadLogs will be called via useEffect after state updates
    setTimeout(() => loadLogs(), 0);
  };

  const statusMap: Record<number, { color: string; text: string }> = {
    0: { color: 'red', text: intl.formatMessage({ id: 'pages.common.failed', defaultMessage: 'Failed' }) },
    1: { color: 'green', text: intl.formatMessage({ id: 'pages.common.success', defaultMessage: 'Success' }) },
    2: { color: 'orange', text: intl.formatMessage({ id: 'pages.common.timeout', defaultMessage: 'Timeout' }) },
  };

  const columns: ColumnsType<API.AgentTaskLogItem> = [
    {
      title: intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' }),
      dataIndex: 'status',
      key: 'status',
      width: 80,
      render: (status: number) => {
        const info = statusMap[status] || statusMap[0];
        return <Tag color={info.color}>{info.text}</Tag>;
      },
    },
    {
      title: intl.formatMessage({ id: 'pages.agentTask.prompt', defaultMessage: 'Prompt' }),
      dataIndex: 'prompt',
      key: 'prompt',
      ellipsis: true,
      render: (text: string) => (
        <span title={text}>{text?.substring(0, 80)}{text && text.length > 80 ? '...' : ''}</span>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.agentTask.duration', defaultMessage: 'Duration' }),
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (ms: number) => ms ? `${(ms / 1000).toFixed(1)}s` : '-',
    },
    {
      title: intl.formatMessage({ id: 'pages.agentTask.startTime', defaultMessage: 'Start Time' }),
      dataIndex: 'startTime',
      key: 'startTime',
      width: 160,
    },
  ];

  if (!task) return null;

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="xl"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.agentTask.logModal.title', defaultMessage: 'Execution Logs' }),
        subtitle: `${task.name} - ${intl.formatMessage({ id: 'pages.agentTask.logModal.subtitle', defaultMessage: 'View task execution history' })}`,
        icon: <FileSearchOutlined />,
      }}
    >
      {/* Filter bar */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <RangePicker
          showTime={{ format: 'HH:mm:ss' }}
          value={timeRange}
          onChange={(dates) => setTimeRange(dates)}
          placeholder={[
            intl.formatMessage({ id: 'pages.agentTask.startTime', defaultMessage: 'Start Time' }),
            intl.formatMessage({ id: 'pages.agentTask.endTime', defaultMessage: 'End Time' }),
          ]}
          format="YYYY-MM-DD HH:mm:ss"
          style={{ fontSize: 12, borderRadius: 6, height: 28 }}
        />
        <Select
          value={statusFilter}
          onChange={(val) => setStatusFilter(val)}
          allowClear
          placeholder={intl.formatMessage({ id: 'pages.agentTask.logModal.status.all', defaultMessage: 'All Status' })}
          style={{ width: 120, height: 28, fontSize: 12 }}
          options={[
            { label: intl.formatMessage({ id: 'pages.common.success', defaultMessage: 'Success' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.common.failed', defaultMessage: 'Failed' }), value: 0 },
            { label: intl.formatMessage({ id: 'pages.common.timeout', defaultMessage: 'Timeout' }), value: 2 },
          ]}
        />
        <Input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          placeholder={intl.formatMessage({ id: 'pages.agentTask.logModal.keyword.placeholder', defaultMessage: 'Search prompt / response / error' })}
          prefix={<SearchOutlined style={{ color: '#8c8c9a', fontSize: 12 }} />}
          onPressEnter={handleSearch}
          style={{ width: 240, height: 28, fontSize: 12, borderRadius: 6 }}
        />
        <Space size={8}>
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch} style={{ height: 28, fontSize: 12, borderRadius: 6 }}>
            {intl.formatMessage({ id: 'pages.common.query', defaultMessage: 'Query' })}
          </Button>
          <Button icon={<ReloadOutlined />} onClick={handleReset} style={{ height: 28, fontSize: 12, borderRadius: 6 }}>
            {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
          </Button>
        </Space>
      </div>

      {/* Log table */}
      <Table
        columns={columns}
        dataSource={logs}
        loading={loading}
        rowKey="id"
        size="small"
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          onChange: (page, size) => { setPageNum(page); setPageSize(size); },
        }}
      />
    </FormModal>
  );
};

export default TaskLogModal;
