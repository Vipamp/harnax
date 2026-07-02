import React, { useState, useEffect, useRef } from 'react';
import { useIntl } from '@umijs/max';
import { Row, Col, Card, Button, message, Modal, Empty, Tag, Input, Select, Table, Tooltip } from 'antd';
import { ScheduleOutlined, SearchOutlined, ReloadOutlined, ThunderboltOutlined, PlayCircleOutlined, PauseCircleOutlined, CaretRightOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import type { ColumnsType } from 'antd/es/table';
import {
  getAgentTaskPage,
  deleteAgentTask,
  startAgentTask,
  pauseAgentTask,
  runAgentTaskOnce,
  getAgentTaskLogs,
} from '@/services/ant-design-pro/agentTask';
import TaskForm from './components/TaskForm';
import LogDetailModal from './components/LogDetailModal';

const AgentTaskManagement: React.FC = () => {
  const intl = useIntl();
  const [tasks, setTasks] = useState<API.AgentTaskItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  const [selectedTask, setSelectedTask] = useState<API.AgentTaskItem | null>(null);
  const [formVisible, setFormVisible] = useState(false);
  const [editingTask, setEditingTask] = useState<API.AgentTaskItem | null>(null);

  const [logs, setLogs] = useState<API.AgentTaskLogItem[]>([]);
  const [logsLoading, setLogsLoading] = useState(false);
  const [logsTotal, setLogsTotal] = useState(0);
  const [logsPageNum, setLogsPageNum] = useState(1);

  const [logDetailVisible, setLogDetailVisible] = useState(false);
  const [selectedLog, setSelectedLog] = useState<API.AgentTaskLogItem | null>(null);

  const [filters, setFilters] = useState({
    name: '',
    taskStatus: undefined as number | undefined,
  });

  const loadTasks = async () => {
    setLoading(true);
    try {
      const response = await getAgentTaskPage({
        pageNum,
        pageSize,
        name: filters.name || undefined,
        taskStatus: filters.taskStatus,
      });
      if (response.code === 200 && response.data) {
        setTasks(response.data.records || []);
        setTotal(response.data.total || 0);
      } else {
        message.error(response.message || 'Failed to load tasks');
      }
    } catch (error) {
      message.error('Failed to load tasks');
    } finally {
      setLoading(false);
    }
  };

  const loadLogs = async (taskId: number) => {
    setLogsLoading(true);
    try {
      const response = await getAgentTaskLogs(taskId, {
        pageNum: logsPageNum,
        pageSize: 10,
      });
      if (response.code === 200 && response.data) {
        setLogs(response.data.records || []);
        setLogsTotal(response.data.total || 0);
      } else {
        message.error(response.message || 'Failed to load logs');
      }
    } catch (error) {
      message.error('Failed to load logs');
    } finally {
      setLogsLoading(false);
    }
  };

  const runOnceTimerRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    loadTasks();
  }, [pageNum, pageSize, filters]);

  useEffect(() => {
    if (selectedTask) {
      setLogsPageNum(1);
      loadLogs(selectedTask.id);
    }
    return () => {
      if (runOnceTimerRef.current) {
        clearTimeout(runOnceTimerRef.current);
      }
    };
  }, [selectedTask]);

  useEffect(() => {
    if (selectedTask && logsPageNum > 1) {
      loadLogs(selectedTask.id);
    }
  }, [logsPageNum]);

  const handleStart = async (id: number) => {
    try {
      const response = await startAgentTask(id);
      if (response.code === 200) {
        message.success('Task started');
        loadTasks();
      } else {
        message.error(response.message || 'Failed to start task');
      }
    } catch (error) {
      message.error('Failed to start task');
    }
  };

  const handlePause = async (id: number) => {
    try {
      const response = await pauseAgentTask(id);
      if (response.code === 200) {
        message.success('Task paused');
        loadTasks();
      } else {
        message.error(response.message || 'Failed to pause task');
      }
    } catch (error) {
      message.error('Failed to pause task');
    }
  };

  const handleRunOnce = async (id: number) => {
    try {
      const response = await runAgentTaskOnce(id);
      if (response.code === 200) {
        message.success('Task triggered');
        if (selectedTask?.id === id) {
          if (runOnceTimerRef.current) {
            clearTimeout(runOnceTimerRef.current);
          }
          runOnceTimerRef.current = setTimeout(() => loadLogs(id), 2000);
        }
      } else {
        message.error(response.message || 'Failed to trigger task');
      }
    } catch (error) {
      message.error('Failed to trigger task');
    }
  };

  const handleDelete = (id: number) => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.common.deleteConfirm', defaultMessage: 'Are you sure to delete?' }),
      onOk: async () => {
        try {
          const response = await deleteAgentTask(id);
          if (response.code === 200) {
            message.success('Task deleted');
            if (selectedTask?.id === id) {
              setSelectedTask(null);
              setLogs([]);
              setLogsTotal(0);
              setLogsPageNum(1);
            }
            loadTasks();
          } else {
            message.error(response.message || 'Failed to delete task');
          }
        } catch (error) {
          message.error('Failed to delete task');
        }
      },
    });
  };

  const taskColumns: ColumnsType<API.AgentTaskItem> = [
    {
      title: intl.formatMessage({ id: 'pages.agentTask.name', defaultMessage: 'Task Name' }),
      dataIndex: 'name',
      key: 'name',
      width: 180,
      render: (text: string, record: API.AgentTaskItem) => (
        <a onClick={() => setSelectedTask(record)} style={{ fontWeight: 500 }}>
          {text}
        </a>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.agentTask.agent', defaultMessage: 'Agent' }),
      dataIndex: 'agentName',
      key: 'agentName',
      width: 120,
    },
    {
      title: intl.formatMessage({ id: 'pages.agentTask.cron', defaultMessage: 'Cron' }),
      dataIndex: 'cronExpression',
      key: 'cronExpression',
      width: 140,
      render: (text: string) => <code style={{ fontSize: 12 }}>{text}</code>,
    },
    {
      title: intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' }),
      dataIndex: 'taskStatus',
      key: 'taskStatus',
      width: 80,
      render: (status: number) => (
        <Tag color={status === 1 ? 'green' : 'default'}>
          {status === 1
            ? intl.formatMessage({ id: 'pages.common.running', defaultMessage: 'Running' })
            : intl.formatMessage({ id: 'pages.common.paused', defaultMessage: 'Paused' })}
        </Tag>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.actions', defaultMessage: 'Actions' }),
      key: 'actions',
      width: 200,
      render: (_: any, record: API.AgentTaskItem) => (
        <span>
          {record.taskStatus === 1 ? (
            <Tooltip title={intl.formatMessage({ id: 'pages.common.pause', defaultMessage: 'Pause' })}>
              <Button type="link" size="small" icon={<PauseCircleOutlined />} onClick={() => handlePause(record.id)} />
            </Tooltip>
          ) : (
            <Tooltip title={intl.formatMessage({ id: 'pages.common.start', defaultMessage: 'Start' })}>
              <Button type="link" size="small" icon={<PlayCircleOutlined />} onClick={() => handleStart(record.id)} />
            </Tooltip>
          )}
          <Tooltip title={intl.formatMessage({ id: 'pages.agentTask.runOnce', defaultMessage: 'Run Once' })}>
            <Button type="link" size="small" icon={<CaretRightOutlined />} onClick={() => handleRunOnce(record.id)} />
          </Tooltip>
          <Tooltip title={intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' })}>
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => { setEditingTask(record); setFormVisible(true); }} />
          </Tooltip>
          <Tooltip title={intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(record.id)} />
          </Tooltip>
        </span>
      ),
    },
  ];

  const logColumns: ColumnsType<API.AgentTaskLogItem> = [
    {
      title: intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' }),
      dataIndex: 'status',
      key: 'status',
      width: 80,
      render: (status: number) => (
        <Tag color={status === 1 ? 'green' : status === 2 ? 'orange' : 'red'}>
          {status === 1
            ? intl.formatMessage({ id: 'pages.common.success', defaultMessage: 'Success' })
            : status === 2
            ? intl.formatMessage({ id: 'pages.common.timeout', defaultMessage: 'Timeout' })
            : intl.formatMessage({ id: 'pages.common.failed', defaultMessage: 'Failed' })}
        </Tag>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.agentTask.prompt', defaultMessage: 'Prompt' }),
      dataIndex: 'prompt',
      key: 'prompt',
      ellipsis: true,
      render: (text: string, record: API.AgentTaskLogItem) => (
        <a onClick={() => { setSelectedLog(record); setLogDetailVisible(true); }}>
          {text?.substring(0, 60)}{text && text.length > 60 ? '...' : ''}
        </a>
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

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '18px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <ScheduleOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.agentTask.title', defaultMessage: 'Agent Task Management' })}
          </span>
        ),
      }}
    >
      <div style={{ padding: '0', minHeight: 'calc(100vh - 140px)' }}>
        <Row gutter={16}>
          {/* Left: Task list */}
          <Col span={14}>
            <Card
              title={
                <span style={{ fontSize: '14px', fontWeight: 600 }}>
                  <ScheduleOutlined style={{ marginRight: 8, color: '#4f6ef7' }} />
                  {intl.formatMessage({ id: 'pages.agentTask.taskList', defaultMessage: 'Task List' })}
                </span>
              }
              extra={
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                  <Input
                    placeholder={intl.formatMessage({ id: 'pages.agentTask.searchPlaceholder', defaultMessage: 'Search task name' })}
                    prefix={<SearchOutlined style={{ color: '#8c8c9a', fontSize: '12px' }} />}
                    value={filters.name}
                    onChange={(e) => setFilters({ ...filters, name: e.target.value })}
                    allowClear
                    style={{ width: 200, borderRadius: '6px', height: '28px', fontSize: '12px' }}
                  />
                  <Select
                    placeholder={intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })}
                    style={{ width: 120, height: '28px', fontSize: '12px' }}
                    value={filters.taskStatus}
                    onChange={(value) => setFilters({ ...filters, taskStatus: value })}
                    allowClear
                    options={[
                      { label: intl.formatMessage({ id: 'pages.common.running', defaultMessage: 'Running' }), value: 1 },
                      { label: intl.formatMessage({ id: 'pages.common.paused', defaultMessage: 'Paused' }), value: 0 },
                    ]}
                  />
                  <Button icon={<ReloadOutlined />} onClick={() => { setFilters({ name: '', taskStatus: undefined }); setPageNum(1); }} style={{ borderRadius: '6px', height: '28px', padding: '0 12px', fontSize: '12px' }}>
                    {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
                  </Button>
                  <Button type="primary" icon={<ScheduleOutlined />} onClick={() => { setEditingTask(null); setFormVisible(true); }}>
                    {intl.formatMessage({ id: 'pages.common.create', defaultMessage: 'Create' })}
                  </Button>
                </div>
              }
              styles={{
                body: { padding: '12px' },
              }}
            >
              <Table
                columns={taskColumns}
                dataSource={tasks}
                loading={loading}
                rowKey="id"
                size="small"
                rowClassName={(record) => record.id === selectedTask?.id ? 'ant-table-row-selected' : ''}
                pagination={{
                  current: pageNum,
                  pageSize,
                  total,
                  showSizeChanger: true,
                  onChange: (page, size) => { setPageNum(page); setPageSize(size); },
                }}
              />
            </Card>
          </Col>

          {/* Right: Execution logs */}
          <Col span={10}>
            <Card
              title={
                selectedTask ? (
                  <span style={{ fontSize: '14px', fontWeight: 600 }}>
                    <ThunderboltOutlined style={{ marginRight: 8, color: '#4f6ef7' }} />
                    {intl.formatMessage({ id: 'pages.agentTask.logs', defaultMessage: 'Execution Logs' })}
                    <Tag color="blue" style={{ marginLeft: 8 }}>{selectedTask.name}</Tag>
                  </span>
                ) : (
                  <span style={{ fontSize: '14px', fontWeight: 600 }}>
                    <ThunderboltOutlined style={{ marginRight: 8, color: '#4f6ef7' }} />
                    {intl.formatMessage({ id: 'pages.agentTask.logs', defaultMessage: 'Execution Logs' })}
                  </span>
                )
              }
              styles={{
                body: { padding: '12px' },
              }}
            >
              {selectedTask ? (
                <Table
                  columns={logColumns}
                  dataSource={logs}
                  loading={logsLoading}
                  rowKey="id"
                  size="small"
                  pagination={{
                    current: logsPageNum,
                    pageSize: 10,
                    total: logsTotal,
                    onChange: (page) => setLogsPageNum(page),
                  }}
                />
              ) : (
                <Empty description={intl.formatMessage({ id: 'pages.agentTask.selectTask', defaultMessage: 'Please select a task' })} />
              )}
            </Card>
          </Col>
        </Row>

        {/* Task form modal */}
        <TaskForm
          visible={formVisible}
          values={editingTask}
          onCancel={() => { setFormVisible(false); setEditingTask(null); }}
          onSuccess={() => { setFormVisible(false); setEditingTask(null); loadTasks(); }}
        />

        {/* Log detail modal */}
        <LogDetailModal
          visible={logDetailVisible}
          log={selectedLog}
          onCancel={() => { setLogDetailVisible(false); setSelectedLog(null); }}
        />
      </div>
    </PageContainer>
  );
};

export default AgentTaskManagement;
