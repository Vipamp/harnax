import React from 'react';
import { Modal, Descriptions, Tag, Typography } from 'antd';
import { useIntl } from '@umijs/max';

const { Paragraph } = Typography;

interface LogDetailModalProps {
  visible: boolean;
  log: API.AgentTaskLogItem | null;
  onCancel: () => void;
}

const LogDetailModal: React.FC<LogDetailModalProps> = ({ visible, log, onCancel }) => {
  const intl = useIntl();

  if (!log) return null;

  const statusMap: Record<number, { color: string; text: string }> = {
    0: { color: 'red', text: intl.formatMessage({ id: 'pages.common.failed', defaultMessage: 'Failed' }) },
    1: { color: 'green', text: intl.formatMessage({ id: 'pages.common.success', defaultMessage: 'Success' }) },
    2: { color: 'orange', text: intl.formatMessage({ id: 'pages.common.timeout', defaultMessage: 'Timeout' }) },
    3: { color: 'blue', text: intl.formatMessage({ id: 'pages.common.running', defaultMessage: 'Running' }) },
    4: { color: 'default', text: intl.formatMessage({ id: 'pages.common.stopped', defaultMessage: 'Stopped' }) },
  };

  const statusInfo = statusMap[log.status] || statusMap[0];

  return (
    <Modal
      title={intl.formatMessage({ id: 'pages.agentTask.logDetail', defaultMessage: 'Execution Log Detail' })}
      open={visible}
      onCancel={onCancel}
      footer={null}
      width={800}
    >
      <Descriptions bordered column={2} size="small">
        <Descriptions.Item label={intl.formatMessage({ id: 'pages.agentTask.taskName', defaultMessage: 'Task Name' })}>{log.taskName}</Descriptions.Item>
        <Descriptions.Item label={intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })}>
          <Tag color={statusInfo.color}>{statusInfo.text}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label={intl.formatMessage({ id: 'pages.agentTask.startTime', defaultMessage: 'Start Time' })}>{log.startTime || '-'}</Descriptions.Item>
        <Descriptions.Item label={intl.formatMessage({ id: 'pages.agentTask.endTime', defaultMessage: 'End Time' })}>{log.endTime || '-'}</Descriptions.Item>
        <Descriptions.Item label={intl.formatMessage({ id: 'pages.agentTask.duration', defaultMessage: 'Duration' })}>
          {log.durationMs ? `${(log.durationMs / 1000).toFixed(1)}s` : '-'}
        </Descriptions.Item>
        <Descriptions.Item label={intl.formatMessage({ id: 'pages.agentTask.sessionId', defaultMessage: 'Session ID' })}>{log.sessionId || '-'}</Descriptions.Item>
      </Descriptions>

      <div style={{ marginTop: 16 }}>
        <h4>{intl.formatMessage({ id: 'pages.agentTask.prompt', defaultMessage: 'Prompt' })}</h4>
        <Paragraph
          style={{
            background: '#f5f5f5',
            padding: '12px',
            whiteSpace: 'pre-wrap',
            fontSize: '13px',
            maxHeight: '200px',
            overflow: 'auto',
          }}
        >
          {log.prompt || '-'}
        </Paragraph>
      </div>

      <div style={{ marginTop: 16 }}>
        <h4>{intl.formatMessage({ id: 'pages.agentTask.response', defaultMessage: 'Agent Response' })}</h4>
        <Paragraph
          style={{
            background: '#f0f7ff',
            padding: '12px',
            whiteSpace: 'pre-wrap',
            fontSize: '13px',
            maxHeight: '400px',
            overflow: 'auto',
          }}
        >
          {log.response || '-'}
        </Paragraph>
      </div>

      {log.errorInfo && (
        <div style={{ marginTop: 16 }}>
          <h4 style={{ color: '#ff4d4f' }}>{intl.formatMessage({ id: 'pages.agentTask.error', defaultMessage: 'Error Info' })}</h4>
          <Paragraph
            style={{
              background: '#fff2f0',
              padding: '12px',
              whiteSpace: 'pre-wrap',
              fontSize: '13px',
              maxHeight: '200px',
              overflow: 'auto',
              color: '#ff4d4f',
            }}
          >
            {log.errorInfo}
          </Paragraph>
        </div>
      )}
    </Modal>
  );
};

export default LogDetailModal;
