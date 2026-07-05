import React, { useState, useEffect } from 'react';
import { useIntl } from '@umijs/max';
import { Card, Button, message, Modal, Input, Select, Table, Tooltip } from 'antd';
import { ScheduleOutlined, SearchOutlined, ReloadOutlined, CaretRightOutlined, DeleteOutlined, EditOutlined, FileSearchOutlined, PlusOutlined } from '@ant-design/icons';
import StatusSwitch from '@/components/StatusSwitch';
import { PageContainer } from '@ant-design/pro-components';
import type { ColumnsType } from 'antd/es/table';
import {
  getAgentTaskPage,
  deleteAgentTask,
  toggleAgentTaskStatus,
} from '@/services/ant-design-pro/agentTask';
import TaskForm from './components/TaskForm';
import TaskLogModal from './components/TaskLogModal';

const AgentTaskManagement: React.FC = () => {
  const intl = useIntl();
  const [tasks, setTasks] = useState<API.AgentTaskItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  const [formVisible, setFormVisible] = useState(false);
  const [editingTask, setEditingTask] = useState<API.AgentTaskItem | null>(null);

  const [logModalVisible, setLogModalVisible] = useState(false);
  const [logTask, setLogTask] = useState<API.AgentTaskItem | null>(null);

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
        message.error(response.message || intl.formatMessage({ id: 'pages.agentTask.loadFailed', defaultMessage: 'Failed to load tasks' }));
      }
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.agentTask.loadFailed', defaultMessage: 'Failed to load tasks' }));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadTasks();
  }, [pageNum, pageSize, filters]);

  const handleToggleStatus = async (id: number, newStatus: number) => {
    try {
      const response = await toggleAgentTaskStatus(id, newStatus);
      if (response.code === 200) {
        message.success(intl.formatMessage({ id: 'pages.agentTask.statusUpdated', defaultMessage: 'Status updated' }));
        loadTasks();
      } else {
        message.error(response.message || intl.formatMessage({ id: 'pages.agentTask.toggleFailed', defaultMessage: 'Failed to toggle status' }));
      }
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.agentTask.toggleFailed', defaultMessage: 'Failed to toggle status' }));
    }
  };

  const handleRunOnce = async (id: number) => {
    try {
      const response = await toggleAgentTaskStatus(id, 1);
      if (response.code === 200) {
        message.success(intl.formatMessage({ id: 'pages.agentTask.triggered', defaultMessage: 'Task triggered' }));
      } else {
        message.error(response.message || intl.formatMessage({ id: 'pages.agentTask.triggerFailed', defaultMessage: 'Failed to trigger task' }));
      }
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.agentTask.triggerFailed', defaultMessage: 'Failed to trigger task' }));
    }
  };

  const handleDelete = (id: number) => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.common.deleteConfirm', defaultMessage: 'Are you sure to delete?' }),
      onOk: async () => {
        try {
          const response = await deleteAgentTask(id);
          if (response.code === 200) {
            message.success(intl.formatMessage({ id: 'pages.agentTask.deleted', defaultMessage: 'Task deleted' }));
            loadTasks();
          } else {
            message.error(response.message || intl.formatMessage({ id: 'pages.agentTask.deleteFailed', defaultMessage: 'Failed to delete task' }));
          }
        } catch (error) {
          message.error(intl.formatMessage({ id: 'pages.agentTask.deleteFailed', defaultMessage: 'Failed to delete task' }));
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
      render: (text: string) => (
        <span style={{ fontWeight: 500 }}>{text}</span>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.agentTask.agent', defaultMessage: 'Agent' }),
      dataIndex: 'agentName',
      key: 'agentName',
      width: 140,
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
      width: 100,
      render: (status: number, record: API.AgentTaskItem) => (
        <StatusSwitch
          status={status}
          onChange={(newStatus) => handleToggleStatus(record.id, newStatus)}
        />
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.actions', defaultMessage: 'Actions' }),
      key: 'actions',
      width: 200,
      render: (_: any, record: API.AgentTaskItem) => (
        <span>
          <Tooltip title={intl.formatMessage({ id: 'pages.agentTask.viewLogs', defaultMessage: 'Logs' })}>
            <Button type="link" size="small" icon={<FileSearchOutlined />} onClick={() => { setLogTask(record); setLogModalVisible(true); }} />
          </Tooltip>
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
                  { label: intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }), value: 1 },
                  { label: intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' }), value: 0 },
                ]}
              />
              <Button icon={<ReloadOutlined />} onClick={() => { setFilters({ name: '', taskStatus: undefined }); setPageNum(1); }} style={{ borderRadius: '6px', height: '28px', padding: '0 12px', fontSize: '12px' }}>
                {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
              </Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditingTask(null); setFormVisible(true); }} style={{ borderRadius: '6px', height: '28px', padding: '0 12px', fontSize: '12px' }}>
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
            pagination={{
              current: pageNum,
              pageSize,
              total,
              showSizeChanger: true,
              onChange: (page, size) => { setPageNum(page); setPageSize(size); },
            }}
          />
        </Card>

        {/* Task form modal */}
        <TaskForm
          visible={formVisible}
          values={editingTask}
          onCancel={() => { setFormVisible(false); setEditingTask(null); }}
          onSuccess={() => { setFormVisible(false); setEditingTask(null); loadTasks(); }}
        />

        {/* Task log modal */}
        <TaskLogModal
          visible={logModalVisible}
          task={logTask}
          onCancel={() => { setLogModalVisible(false); setLogTask(null); }}
        />
      </div>
    </PageContainer>
  );
};

export default AgentTaskManagement;
