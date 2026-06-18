import React from 'react';
import { Modal, Typography, Button, Alert, Space } from 'antd';
import { useIntl } from '@umijs/max';
import { KeyOutlined, CopyOutlined, WarningOutlined } from '@ant-design/icons';

const { Text, Paragraph } = Typography;

interface RawKeyModalProps {
  visible: boolean;
  rawKey: string;
  keyName: string;
  onClose: () => void;
}

const RawKeyModal: React.FC<RawKeyModalProps> = ({ visible, rawKey, keyName, onClose }) => {
  const intl = useIntl();

  const handleCopy = () => {
    navigator.clipboard.writeText(rawKey);
  };

  return (
    <Modal
      open={visible}
      onCancel={onClose}
      footer={null}
      closable={true}
      maskClosable={false}
      centered
      width={560}
      styles={{
        header: {
          background: 'transparent',
          borderBottom: '1px solid var(--vip-border)',
          padding: '16px 24px',
        },
        body: { padding: '20px 24px' },
      }}
      title={
        <Space>
          <KeyOutlined style={{ color: 'var(--vip-primary)', fontSize: 18 }} />
          <Text strong style={{ fontSize: 16 }}>
            {intl.formatMessage({ id: 'pages.apiKey.rawKey.title', defaultMessage: 'API Key 创建成功' })}
          </Text>
        </Space>
      }
    >
      <Alert
        type="warning"
        showIcon
        icon={<WarningOutlined />}
        message={intl.formatMessage({
          id: 'pages.apiKey.rawKey.warning',
          defaultMessage: '请立即复制并妥善保存此 Key，关闭后将无法再次查看。',
        })}
        style={{ marginBottom: 16 }}
      />

      <div style={{ marginBottom: 8 }}>
        <Text type="secondary">{intl.formatMessage({ id: 'pages.apiKey.rawKey.name', defaultMessage: '名称' })}</Text>
      </div>
      <Paragraph strong style={{ marginBottom: 16 }}>{keyName}</Paragraph>

      <div style={{ marginBottom: 8 }}>
        <Text type="secondary">{intl.formatMessage({ id: 'pages.apiKey.rawKey.key', defaultMessage: 'API Key' })}</Text>
      </div>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '10px 14px',
          background: 'var(--vip-bg-component, #f5f5f5)',
          border: '1px solid var(--vip-border, #e8e8e8)',
          borderRadius: 8,
          marginBottom: 16,
        }}
      >
        <Paragraph
          copyable={{
            text: rawKey,
            icon: [
              <CopyOutlined key="copy" style={{ color: 'var(--vip-text-tertiary)', fontSize: 14 }} />,
              <CopyOutlined key="copied" style={{ color: 'var(--vip-primary)', fontSize: 14 }} />,
            ],
          }}
          style={{
            flex: 1,
            margin: 0,
            fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
            fontSize: 13,
            wordBreak: 'break-all',
          }}
        >
          {rawKey}
        </Paragraph>
      </div>

      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button type="primary" icon={<CopyOutlined />} onClick={handleCopy}>
          {intl.formatMessage({ id: 'pages.apiKey.rawKey.copy', defaultMessage: '复制 Key' })}
        </Button>
      </div>
    </Modal>
  );
};

export default RawKeyModal;
