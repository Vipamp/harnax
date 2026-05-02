import { Modal, Form, Input, Select, message } from 'antd';
import React, { useState, useEffect } from 'react';
import { useIntl } from '@umijs/max';

const { TextArea } = Input;

interface CreateFormProps {
  visible: boolean;
  agents: API.AgentItem[];
  onCancel: () => void;
  onSubmit: (values: API.ChannelCreateRequest) => Promise<void>;
}

const CreateForm: React.FC<CreateFormProps> = ({ visible, agents, onCancel, onSubmit }) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [selectedType, setSelectedType] = useState<string>('');

  // Channel 类型选项
  const CHANNEL_TYPES = [
    { label: intl.formatMessage({ id: 'pages.channel.type.wecom', defaultMessage: 'WeCom' }), value: 'wecom' },
    { label: intl.formatMessage({ id: 'pages.channel.type.feishu', defaultMessage: 'Feishu' }), value: 'feishu' },
    { label: intl.formatMessage({ id: 'pages.channel.type.dingtalk', defaultMessage: 'DingTalk' }), value: 'dingtalk' },
    { label: intl.formatMessage({ id: 'pages.channel.type.http', defaultMessage: 'HTTP' }), value: 'http' },
  ];

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
      const values = await form.validateFields();
      setLoading(true);
      await onSubmit(values);
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
              label={intl.formatMessage({ id: 'pages.channel.form.label.token', defaultMessage: 'Token' })}
              name="token"
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.token', defaultMessage: 'Please enter verification token' }) }]}
            >
              <Input.Password placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.token', defaultMessage: 'WeCom verification token' })} />
            </Form.Item>
            <Form.Item
              label={intl.formatMessage({ id: 'pages.channel.form.label.encodingAesKey', defaultMessage: 'EncodingAESKey' })}
              name="encodingAesKey"
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.encodingAesKey', defaultMessage: 'Please enter encryption key' }) }]}
            >
              <Input.Password placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.encodingAesKey', defaultMessage: 'WeCom message encryption key' })} />
            </Form.Item>
          </>
        );
      case 'feishu':
        return (
          <>
            <Form.Item
              label={intl.formatMessage({ id: 'pages.channel.form.label.appId', defaultMessage: 'App ID' })}
              name="appId"
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.appId', defaultMessage: 'Please enter Feishu app ID' }) }]}
            >
              <Input placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.appId', defaultMessage: 'Feishu app App ID' })} />
            </Form.Item>
            <Form.Item
              label={intl.formatMessage({ id: 'pages.channel.form.label.appSecret', defaultMessage: 'App Secret' })}
              name="appSecret"
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.appSecret', defaultMessage: 'Please enter Feishu app secret' }) }]}
            >
              <Input.Password placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.appSecret', defaultMessage: 'Feishu app App Secret' })} />
            </Form.Item>
          </>
        );
      case 'dingtalk':
        return (
          <>
            <Form.Item
              label={intl.formatMessage({ id: 'pages.channel.form.label.appKey', defaultMessage: 'App Key' })}
              name="appId"
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.appKey', defaultMessage: 'Please enter DingTalk app key' }) }]}
            >
              <Input placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.appKey', defaultMessage: 'DingTalk app App Key' })} />
            </Form.Item>
            <Form.Item
              label={intl.formatMessage({ id: 'pages.channel.form.label.appSecret', defaultMessage: 'App Secret' })}
              name="appSecret"
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.appSecret', defaultMessage: 'Please enter DingTalk app secret' }) }]}
            >
              <Input.Password placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.appSecret', defaultMessage: 'DingTalk app App Secret' })} />
            </Form.Item>
          </>
        );
      case 'http':
        return (
          <Form.Item
            label={intl.formatMessage({ id: 'pages.channel.form.label.webhookUrl', defaultMessage: 'Webhook URL' })}
            name="webhookUrl"
          >
            <Input placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.webhookUrl', defaultMessage: 'HTTP callback URL (optional)' })} />
          </Form.Item>
        );
      default:
        return null;
    }
  };

  return (
    <Modal
      title={intl.formatMessage({ id: 'pages.channel.modal.title.create', defaultMessage: 'Create Channel' })}
      open={visible}
      onCancel={onCancel}
      onOk={handleSubmit}
      confirmLoading={loading}
      width={600}
      destroyOnClose
    >
      <Form form={form} layout="vertical" style={{ marginTop: 24 }}>
        <Form.Item
          label={intl.formatMessage({ id: 'pages.channel.form.label.name', defaultMessage: 'Channel Name' })}
          name="name"
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.name', defaultMessage: 'Please enter channel name' }) }]}
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.name', defaultMessage: 'Please enter channel name, e.g. WeCom Customer Service' })} />
        </Form.Item>

        <Form.Item
          label={intl.formatMessage({ id: 'pages.channel.form.label.type', defaultMessage: 'Channel Type' })}
          name="type"
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.type', defaultMessage: 'Please select channel type' }) }]}
        >
          <Select
            placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.type', defaultMessage: 'Please select channel type' })}
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
            placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.agent', defaultMessage: 'Please select associated agent' })}
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
          label={intl.formatMessage({ id: 'pages.channel.form.label.description', defaultMessage: 'Description' })}
          name="description"
        >
          <TextArea rows={3} placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.description', defaultMessage: 'Please enter channel description' })} />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default CreateForm;
