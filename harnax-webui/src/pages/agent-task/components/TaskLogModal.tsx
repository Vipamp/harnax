import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Modal, Table, Tag, Input, Select, DatePicker, Button, Space, message } from 'antd';
import { useIntl } from '@umijs/max';
import { SearchOutlined, ReloadOutlined, StopOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import { getAgentTaskLogs, stopAgentTask } from '@/services/ant-design-pro/agentTask';
import LogDetailModal from './LogDetailModal';

const { RangePicker } = DatePicker;

interface TaskLogModalProps {
  visible: boolean;
  task: API.AgentTaskItem | null;
  onCancel: () => void;
  onRefresh?: () => void;
}

const TaskLogModal: React.FC<TaskLogModalProps> = ({ visible, task, onCancel, onRefresh }) => {
  const intl = useIntl();

  const [logs, setLogs] = useState<API.AgentTaskLogItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  const [timeRange, setTimeRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<number | undefined>(undefined);

  const [detailVisible, setDetailVisible] = useState(false);
  const [selectedLog, setSelectedLog] = useState<API.AgentTaskLogItem | null>(null);
  const [stopping, setStopping] = useState<number | null>(null);

  // Use refs for filter params so polling callback always reads latest values
  const filterRef = useRef({ statusFilter, timeRange, keyword });
  filterRef.current = { statusFilter, timeRange, keyword };
  const detailRef = useRef({ visible: detailVisible, logId: selectedLog?.id });
  detailRef.current = { visible: detailVisible, logId: selectedLog?.id };
  const logsRef = useRef(logs);
  logsRef.current = logs;

  const loadLogs = useCallback(async (silent = false) => {
    if (!task) return;
    if (!silent) setLoading(true);
    try {
      const { statusFilter: sf, timeRange: tr, keyword: kw } = filterRef.current;
      const response = await getAgentTaskLogs(task.id, {
        pageNum,
        pageSize,
        status: sf,
        startTimeFrom: tr?.[0]?.format('YYYY-MM-DD HH:mm:ss'),
        startTimeTo: tr?.[1]?.format('YYYY-MM-DD HH:mm:ss'),
        keyword: kw || undefined,
      });
      if (response.code === 200 && response.data) {
        const records = response.data.records || [];
        setLogs(records);
        setTotal(response.data.total || 0);
        // Update selectedLog if detail modal is open
        const dRef = detailRef.current;
        if (dRef.visible && dRef.logId) {
          const updated = records.find((r: API.AgentTaskLogItem) => r.id === dRef.logId);
          if (updated) setSelectedLog(updated);
        }
      } else if (!silent) {
        message.error(response.message || intl.formatMessage({ id: 'pages.agentTask.loadLogsFailed', defaultMessage: 'Failed to load logs' }));
      }
    } catch {
      if (!silent) message.error(intl.formatMessage({ id: 'pages.agentTask.loadLogsFailed', defaultMessage: 'Failed to load logs' }));
    } finally {
      if (!silent) setLoading(false);
    }
  }, [task, pageNum, pageSize, intl]);

  // Poll when modal is open: 3s if running logs exist, 5s otherwise
  useEffect(() => {
    if (!visible || !task) return;
    let timer: ReturnType<typeof setInterval>;
    let stopped = false;
    const scheduleNext = () => {
      if (stopped) return;
      // Read current logs state to decide interval: 3s if running logs exist, 5s otherwise
      const hasRunning = logsRef.current.some((l) => l.status === 3 || l.status === 4);
      const delay = hasRunning ? 3000 : 5000;
      timer = setTimeout(async () => {
        if (stopped) return;
        try {
          const { statusFilter: sf, timeRange: tr, keyword: kw } = filterRef.current;
          const response = await getAgentTaskLogs(task.id, {
            pageNum, pageSize, status: sf,
            startTimeFrom: tr?.[0]?.format('YYYY-MM-DD HH:mm:ss'),
            startTimeTo: tr?.[1]?.format('YYYY-MM-DD HH:mm:ss'),
            keyword: kw || undefined,
          });
          if (!stopped && response.code === 200 && response.data) {
            const records = response.data.records || [];
            setLogs(records);
            setTotal(response.data.total || 0);
            const dRef = detailRef.current;
            if (dRef.visible && dRef.logId) {
              const updated = records.find((r: API.AgentTaskLogItem) => r.id === dRef.logId);
              if (updated) setSelectedLog(updated);
            }
          }
        } catch { /* ignore polling errors */ }
        scheduleNext();
      }, delay);
    };
    scheduleNext();
    return () => { stopped = true; clearTimeout(timer); };
  }, [visible, task, pageNum, pageSize]);

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
      // Notify parent to refresh task list when closing
      onRefresh?.();
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

  const handleStopTask = (record: API.AgentTaskLogItem) => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.agentTask.stopConfirmTitle', defaultMessage: 'Stop Task Execution' }),
      content: intl.formatMessage({ id: 'pages.agentTask.stopConfirmContent', defaultMessage: 'Are you sure to stop the running task? This will interrupt the agent execution.' }),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
      okType: 'danger',
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
      onOk: async () => {
        setStopping(record.id);
        try {
          const response = await stopAgentTask(record.id);
          if (response.code === 200) {
            message.success(intl.formatMessage({ id: 'pages.agentTask.stopSuccess', defaultMessage: 'Task stopped successfully' }));
            setTimeout(() => loadLogs(true), 1000);
          } else {
            message.error(response.message || intl.formatMessage({ id: 'pages.agentTask.stopFailed', defaultMessage: 'Failed to stop task' }));
          }
        } catch {
          message.error(intl.formatMessage({ id: 'pages.agentTask.stopFailed', defaultMessage: 'Failed to stop task' }));
        } finally {
          setStopping(null);
        }
      },
    });
  };

  const statusMap: Record<number, { color: string; text: string }> = {
    0: { color: 'red', text: intl.formatMessage({ id: 'pages.common.failed', defaultMessage: 'Failed' }) },
    1: { color: 'green', text: intl.formatMessage({ id: 'pages.common.success', defaultMessage: 'Success' }) },
    2: { color: 'orange', text: intl.formatMessage({ id: 'pages.common.timeout', defaultMessage: 'Timeout' }) },
    3: { color: 'blue', text: intl.formatMessage({ id: 'pages.common.running', defaultMessage: 'Running' }) },
    4: { color: 'gold', text: intl.formatMessage({ id: 'pages.common.stopping', defaultMessage: 'Stopping' }) },
    5: { color: 'default', text: intl.formatMessage({ id: 'pages.common.stopped', defaultMessage: 'Stopped' }) },
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
      title: intl.formatMessage({ id: 'pages.agentTask.response', defaultMessage: 'Response' }),
      dataIndex: 'response',
      key: 'response',
      ellipsis: true,
      render: (text: string) => (
        <span title={text} style={{ color: text ? '#595959' : '#bfbfbf' }}>
          {text ? (text.substring(0, 60) + (text.length > 60 ? '...' : '')) : '-'}
        </span>
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
    {
      title: intl.formatMessage({ id: 'pages.common.action', defaultMessage: 'Action' }),
      key: 'action',
      width: 80,
      render: (_: unknown, record: API.AgentTaskLogItem) =>
        record.status === 3 ? (
          <Button
            type="link"
            danger
            size="small"
            icon={<StopOutlined />}
            loading={stopping === record.id}
            onClick={(e) => {
              e.stopPropagation();
              handleStopTask(record);
            }}
          >
            {intl.formatMessage({ id: 'pages.common.stop', defaultMessage: 'Stop' })}
          </Button>
        ) : null,
    },
  ];

  if (!task) return null;

  return (
    <Modal
      open={visible}
      onCancel={onCancel}
      width={1200}
      footer={null}
      centered
      maskClosable={false}
      destroyOnClose
      styles={{
        body: { padding: '20px 24px' },
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
        />
        <Select
          value={statusFilter}
          onChange={(val) => setStatusFilter(val)}
          allowClear
          placeholder={intl.formatMessage({ id: 'pages.agentTask.logModal.status.all', defaultMessage: 'All Status' })}
          style={{ width: 120 }}
          options={[
            { label: intl.formatMessage({ id: 'pages.common.success', defaultMessage: 'Success' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.common.failed', defaultMessage: 'Failed' }), value: 0 },
            { label: intl.formatMessage({ id: 'pages.common.timeout', defaultMessage: 'Timeout' }), value: 2 },
            { label: intl.formatMessage({ id: 'pages.common.running', defaultMessage: 'Running' }), value: 3 },
            { label: intl.formatMessage({ id: 'pages.common.stopping', defaultMessage: 'Stopping' }), value: 4 },
            { label: intl.formatMessage({ id: 'pages.common.stopped', defaultMessage: 'Stopped' }), value: 5 },
          ]}
        />
        <Input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          placeholder={intl.formatMessage({ id: 'pages.agentTask.logModal.keyword.placeholder', defaultMessage: 'Search prompt / response / error' })}
          prefix={<SearchOutlined style={{ color: '#8c8c9a', fontSize: 12 }} />}
          onPressEnter={handleSearch}
          style={{ width: 240 }}
        />
        <Space size={8}>
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
            {intl.formatMessage({ id: 'pages.common.query', defaultMessage: 'Query' })}
          </Button>
          <Button icon={<ReloadOutlined />} onClick={handleReset}>
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
        onRow={(record) => ({
          onClick: () => { setSelectedLog(record); setDetailVisible(true); },
          style: { cursor: 'pointer' },
        })}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          onChange: (page, size) => { setPageNum(page); setPageSize(size); },
        }}
      />

      {/* Log detail modal */}
      <LogDetailModal
        visible={detailVisible}
        log={selectedLog}
        onCancel={() => { setDetailVisible(false); setSelectedLog(null); }}
      />
    </Modal>
  );
};

export default TaskLogModal;
