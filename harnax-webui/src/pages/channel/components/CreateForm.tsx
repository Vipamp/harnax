import { Form, Input, Select, Typography, Button, Switch } from 'antd';
import React, { useState, useEffect } from 'react';
import { useIntl } from '@umijs/max';
import { LinkOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';
import { DEFAULT_CHANNEL_MODE, allowedModes, requiresEncryptKey } from './channelModes';

const { TextArea } = Input;
const { Text } = Typography;

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
  const selectedMode = Form.useWatch('communicationMode', form);

  // Channel 类型选项
  const CHANNEL_TYPES = [
    { label: intl.formatMessage({ id: 'pages.channel.type.wecom', defaultMessage: 'WeCom' }), value: 'wecom' },
    { label: intl.formatMessage({ id: 'pages.channel.type.wechat', defaultMessage: 'WeChat' }), value: 'wechat' },
    { label: intl.formatMessage({ id: 'pages.channel.type.feishu', defaultMessage: 'Feishu' }), value: 'feishu' },
    { label: intl.formatMessage({ id: 'pages.channel.type.dingtalk', defaultMessage: 'DingTalk' }), value: 'dingtalk' },
    { label: intl.formatMessage({ id: 'pages.channel.type.http', defaultMessage: 'HTTP' }), value: 'http' },
  ];

  const MODE_LABELS: Record<string, string> = {
    webhook: intl.formatMessage({ id: 'pages.channel.form.communicationMode.webhook', defaultMessage: 'Webhook' }),
    websocket: intl.formatMessage({ id: 'pages.channel.form.communicationMode.websocket', defaultMessage: 'WebSocket' }),
    stream: intl.formatMessage({ id: 'pages.channel.form.communicationMode.stream', defaultMessage: 'Stream' }),
    long_polling: intl.formatMessage({ id: 'pages.channel.form.communicationMode.longPolling', defaultMessage: 'Long Polling' }),
  };

  // 飞书 webhook 靠 Encrypt Key 验签，缺了它渠道会直接拒收回调，所以这两个字段只在 webhook 下出现
  const renderFeishuCallbackFields = () => {
    if (!requiresEncryptKey(selectedType, selectedMode)) {
      return null;
    }
    return (
      <>
        <Form.Item
          label={intl.formatMessage({ id: 'pages.channel.form.label.encodingAesKey', defaultMessage: 'Encrypt Key' })}
          name="encodingAesKey"
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.encodingAesKey', defaultMessage: 'Feishu callback mode requires an Encrypt Key' }) }]}
          extra={intl.formatMessage({ id: 'pages.channel.form.extra.feishuEncryptKey', defaultMessage: 'Required: Feishu signs callbacks with it, and the channel refuses unsigned events.' })}
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.encodingAesKey', defaultMessage: 'Feishu event Encrypt Key' })} />
        </Form.Item>
        <Form.Item
          label={intl.formatMessage({ id: 'pages.channel.form.label.token', defaultMessage: 'Verification Token' })}
          name="token"
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.token', defaultMessage: 'Please enter the Feishu event Verification Token' }) }]}
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.token', defaultMessage: 'Feishu event Verification Token' })} />
        </Form.Item>
      </>
    );
  };

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

      // Extract type-specific config fields and serialize into configJson
      const { token, encodingAesKey, appId, appSecret, webhookUrl, enabled, ...restValues } = values;
      const config: Record<string, string> = {};
      if (token) config.token = token;
      if (encodingAesKey) config.encodingAesKey = encodingAesKey;
      if (appId) config.appId = appId;
      if (appSecret) config.appSecret = appSecret;
      if (webhookUrl) config.webhookUrl = webhookUrl;

      const submitData: API.ChannelCreateRequest = {
        ...restValues,
        enabled: enabled ? 1 : 0,
        configJson: Object.keys(config).length > 0 ? JSON.stringify(config) : undefined,
      };

      await onSubmit(submitData);
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
              label={intl.formatMessage({ id: 'pages.channel.form.label.wecomBotId', defaultMessage: 'Bot ID' })}
              name="appId"
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.wecomBotId', defaultMessage: 'Please enter WeCom bot ID' }) }]}
            >
              <Input placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.wecomBotId', defaultMessage: 'WeCom smart-robot BotID' })} />
            </Form.Item>
            <Form.Item
              label={intl.formatMessage({ id: 'pages.channel.form.label.wecomBotSecret', defaultMessage: 'Bot Secret' })}
              name="appSecret"
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.wecomBotSecret', defaultMessage: 'Please enter WeCom bot secret' }) }]}
            >
              <Input.Password placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.wecomBotSecret', defaultMessage: 'WeCom smart-robot Secret' })} />
            </Form.Item>
          </>
        );
      case 'wechat':
        return (
          <Form.Item label=" " colon={false}>
            <Text type="secondary">
              {intl.formatMessage({
                id: 'pages.channel.form.wechatHint',
                defaultMessage: 'No credentials needed. After creation, click the WeChat icon in the list to scan and log in.',
              })}
            </Text>
          </Form.Item>
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
            {renderFeishuCallbackFields()}
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
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="md"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.channel.modal.title.create', defaultMessage: 'Create Channel' }),
        subtitle: intl.formatMessage({
          id: 'pages.channel.modal.title.create.subtitle',
          defaultMessage: 'Configure channel connection settings',
        }),
        icon: <LinkOutlined />,
      }}
    >
      <Form 
        form={form} 
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
      >
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
            onChange={(value) => {
              setSelectedType(value);
              const recommended = DEFAULT_CHANNEL_MODE[value];
              if (recommended) {
                form.setFieldValue('communicationMode', recommended);
              }
            }}
          />
        </Form.Item>

        <Form.Item
          label={intl.formatMessage({ id: 'pages.channel.form.label.agent', defaultMessage: 'Associated Agent' })}
          name="agentId"
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.rule.required.agent', defaultMessage: 'Please select associated agent' }) }]}
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

        {selectedType !== 'wechat' && (
          <Form.Item
            label={intl.formatMessage({ id: 'pages.channel.form.label.communicationMode', defaultMessage: 'Communication Mode' })}
            name="communicationMode"
            rules={[{ required: true, message: intl.formatMessage({ id: 'pages.channel.form.placeholder.communicationMode', defaultMessage: 'Select communication mode' }) }]}
          >
            <Select
              placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.communicationMode', defaultMessage: 'Select communication mode' })}
              // Only the modes this type can actually run: a mode with no transport becomes a
              // channel that never receives anything, and nothing on the list page says why.
              options={allowedModes(selectedType).map((mode) => ({ label: MODE_LABELS[mode], value: mode }))}
            />
          </Form.Item>
        )}

        <Form.Item
          label={intl.formatMessage({ id: 'pages.channel.form.label.enabled', defaultMessage: 'Auto Listen' })}
          name="enabled"
          valuePropName="checked"
          initialValue={true}
        >
          <Switch />
        </Form.Item>

        {renderTypeSpecificFields()}

        <Form.Item
          label={intl.formatMessage({ id: 'pages.channel.form.label.description', defaultMessage: 'Description' })}
          name="description"
        >
          <TextArea rows={3} placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.description', defaultMessage: 'Please enter channel description' })} />
        </Form.Item>

        {/* 按钮区域 */}
        <Form.Item wrapperCol={{ span: 24 }}>
          <div style={{ 
            display: 'flex', 
            justifyContent: 'flex-end', 
            gap: '12px',
            marginTop: '24px',
            paddingTop: '20px',
            borderTop: '1px solid var(--vip-border)'
          }}>
            <Button 
              onClick={() => form.resetFields()}
            >
              {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
            </Button>
            <Button 
              type="primary" 
              onClick={handleSubmit}
              loading={loading}
            >
              {intl.formatMessage({ id: 'pages.common.submit', defaultMessage: 'Submit' })}
            </Button>
          </div>
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default CreateForm;
