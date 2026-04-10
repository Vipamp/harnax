import { Modal, Form, Input, Select, message } from 'antd';
import React, { useState, useEffect } from 'react';

const { TextArea } = Input;

// Channel 类型选项
const CHANNEL_TYPES = [
  { label: '企业微信', value: 'wecom' },
  { label: '飞书', value: 'feishu' },
  { label: '钉钉', value: 'dingtalk' },
  { label: 'HTTP接口', value: 'http' },
];

interface UpdateFormProps {
  visible: boolean;
  values: API.ChannelItem;
  agents: API.AgentItem[];
  onCancel: () => void;
  onSubmit: (values: API.ChannelUpdateRequest) => Promise<void>;
}

const UpdateForm: React.FC<UpdateFormProps> = ({ visible, values, agents, onCancel, onSubmit }) => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [selectedType, setSelectedType] = useState<string>('');

  // 初始化表单数据
  useEffect(() => {
    if (visible && values) {
      form.setFieldsValue({
        name: values.name,
        type: values.type,
        agentId: values.agentId,
        webhookUrl: values.webhookUrl,
        token: values.token,
        encodingAesKey: values.encodingAesKey,
        appId: values.appId,
        appSecret: values.appSecret,
        description: values.description,
      });
      setSelectedType(values.type);
    }
  }, [visible, values]);

  // 重置表单
  useEffect(() => {
    if (!visible) {
      form.resetFields();
      setSelectedType('');
    }
  }, [visible]);

  // 提交表单
  const handleSubmit = async () => {
    try {
      const formValues = await form.validateFields();
      setLoading(true);
      await onSubmit(formValues);
      form.resetFields();
      setSelectedType('');
    } catch (error) {
      console.error('表单验证失败', error);
    } finally {
      setLoading(false);
    }
  };

  // 根据类型显示不同的配置字段
  const renderTypeSpecificFields = () => {
    switch (selectedType) {
      case 'wecom':
        return (
          <>
            <Form.Item
              label="Token"
              name="token"
            >
              <Input.Password placeholder="企业微信验证Token" />
            </Form.Item>
            <Form.Item
              label="EncodingAESKey"
              name="encodingAesKey"
            >
              <Input.Password placeholder="企业微信消息加密密钥" />
            </Form.Item>
          </>
        );
      case 'feishu':
        return (
          <>
            <Form.Item
              label="App ID"
              name="appId"
            >
              <Input placeholder="飞书应用 App ID" />
            </Form.Item>
            <Form.Item
              label="App Secret"
              name="appSecret"
            >
              <Input.Password placeholder="飞书应用 App Secret" />
            </Form.Item>
          </>
        );
      case 'dingtalk':
        return (
          <>
            <Form.Item
              label="App Key"
              name="appId"
            >
              <Input placeholder="钉钉应用 App Key" />
            </Form.Item>
            <Form.Item
              label="App Secret"
              name="appSecret"
            >
              <Input.Password placeholder="钉钉应用 App Secret" />
            </Form.Item>
          </>
        );
      case 'http':
        return (
          <Form.Item
            label="Webhook URL"
            name="webhookUrl"
          >
            <Input placeholder="HTTP 回调地址（可选）" />
          </Form.Item>
        );
      default:
        return null;
    }
  };

  return (
    <Modal
      title="编辑 Channel"
      open={visible}
      onCancel={onCancel}
      onOk={handleSubmit}
      confirmLoading={loading}
      width={600}
      destroyOnClose
    >
      <Form form={form} layout="vertical" style={{ marginTop: 24 }}>
        <Form.Item
          label="通道名称"
          name="name"
          rules={[{ required: true, message: '请输入通道名称' }]}
        >
          <Input placeholder="请输入通道名称" />
        </Form.Item>

        <Form.Item
          label="通道类型"
          name="type"
          rules={[{ required: true, message: '请选择通道类型' }]}
        >
          <Select
            placeholder="请选择通道类型"
            options={CHANNEL_TYPES}
            onChange={(value) => setSelectedType(value)}
          />
        </Form.Item>

        <Form.Item
          label="关联智能体"
          name="agentId"
          rules={[{ required: true, message: '请选择关联的智能体' }]}
        >
          <Select
            placeholder="请选择关联的智能体"
            showSearch
            optionFilterProp="label"
            options={agents.map(agent => ({
              label: agent.name,
              value: agent.id,
            }))}
          />
        </Form.Item>

        {renderTypeSpecificFields()}

        <Form.Item
          label="描述"
          name="description"
        >
          <TextArea rows={3} placeholder="请输入通道描述" />
        </Form.Item>
      </Form>

      {/* 显示回调 URL */}
      {values?.callbackUrl && (
        <div style={{ marginTop: 16, padding: 12, background: '#f5f5f5', borderRadius: 8 }}>
          <div style={{ marginBottom: 4, fontWeight: 500 }}>回调 URL</div>
          <div style={{ color: '#666', wordBreak: 'break-all' }}>{values.callbackUrl}</div>
        </div>
      )}
    </Modal>
  );
};

export default UpdateForm;
