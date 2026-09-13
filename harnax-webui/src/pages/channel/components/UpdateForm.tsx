import { Form, Input, Select, Typography, Tooltip, Button, Switch } from 'antd';
import React, { useState, useEffect } from 'react';
import { useIntl } from '@umijs/max';
import { LinkOutlined, CopyOutlined, KeyOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';
import { allowedModes, modeFor, requiresEncryptKey } from './channelModes';

const { TextArea } = Input;
const { Text } = Typography;

/** The `configJson` keys this form owns; everything else in the blob is passed through untouched. */
const MANAGED_CONFIG_KEYS = ['token', 'encodingAesKey', 'appId', 'appSecret', 'webhookUrl'] as const;

interface UpdateFormProps {
  visible: boolean;
  values: API.ChannelItem;
  agents: API.AgentItem[];
  onCancel: () => void;
  onSubmit: (values: API.ChannelUpdateRequest) => Promise<void>;
}

const UpdateForm: React.FC<UpdateFormProps> = ({ visible, values, agents, onCancel, onSubmit }) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [selectedType, setSelectedType] = useState<string>('');
  const [storedConfig, setStoredConfig] = useState<Record<string, any>>({});
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

  // 初始化表单数据
  useEffect(() => {
    if (visible && values) {
      // Parse configJson to populate individual config fields
      let config: Record<string, any> = {};
      try {
        config = values.configJson ? JSON.parse(values.configJson) : {};
      } catch {
        config = {};
      }
      setStoredConfig(config);
      form.setFieldsValue({
        name: values.name,
        type: values.type,
        agentId: values.agentId,
        // A channel created before the modes were restricted can hold a mode its type cannot run;
        // show the runnable one instead of the stored dead one.
        communicationMode: modeFor(values.type, values.communicationMode),
        enabled: values.enabled === 1,
        token: config.token,
        encodingAesKey: config.encodingAesKey,
        appId: config.appId,
        appSecret: config.appSecret,
        webhookUrl: config.webhookUrl,
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
      setStoredConfig({});
    }
  }, [visible]);

  // 提交表单
  const handleSubmit = async () => {
    try {
      const formValues = await form.validateFields();
      setLoading(true);

      // `validateFields()` only returns *mounted* fields, so reading the config out of it and
      // rewriting the whole blob is what used to erase a Feishu Encrypt Key or a WeChat bot token
      // whenever that field happened not to be rendered. Merge over the stored config and read the
      // managed keys from the store: an unrendered key keeps its loaded value, a cleared input
      // removes its key, and a key this form never manages survives untouched.
      const config: Record<string, any> = { ...storedConfig };
      MANAGED_CONFIG_KEYS.forEach((key) => {
        const value = form.getFieldValue(key);
        if (typeof value === 'string' && value.trim() === '') {
          delete config[key];
        } else if (value !== undefined && value !== null) {
          config[key] = value;
        }
      });

      // The config keys live inside `configJson`; `enabled` is a Switch on the form but a 0/1 here.
      const payload: Record<string, any> = { ...formValues };
      MANAGED_CONFIG_KEYS.forEach((key) => {
        delete payload[key];
      });
      payload.enabled = formValues.enabled ? 1 : 0;

      const submitData: API.ChannelUpdateRequest = {
        ...payload,
        // Always send the merged blob. Omitting it when every managed credential was cleared is how
        // "delete the secret" silently did nothing: the backend only writes `configJson` when the
        // request carries one, so the old value stayed live while the form showed it gone.
        configJson: JSON.stringify(config),
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
            >
              <Input placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.wecomBotId', defaultMessage: 'WeCom smart-robot BotID' })} />
            </Form.Item>
            <Form.Item
              label={intl.formatMessage({ id: 'pages.channel.form.label.wecomBotSecret', defaultMessage: 'Bot Secret' })}
              name="appSecret"
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
            >
              <Input placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.appId', defaultMessage: 'Feishu app App ID' })} />
            </Form.Item>
            <Form.Item
              label={intl.formatMessage({ id: 'pages.channel.form.label.appSecret', defaultMessage: 'App Secret' })}
              name="appSecret"
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
            >
              <Input placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.appKey', defaultMessage: 'DingTalk app App Key' })} />
            </Form.Item>
            <Form.Item
              label={intl.formatMessage({ id: 'pages.channel.form.label.appSecret', defaultMessage: 'App Secret' })}
              name="appSecret"
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
      size="lg"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.channel.modal.title.update', defaultMessage: 'Edit Channel' }),
        subtitle: intl.formatMessage({
          id: 'pages.channel.modal.title.update.subtitle',
          defaultMessage: 'Modify channel configuration, changes take effect immediately',
        }),
        icon: <LinkOutlined />,
        iconGradient: 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)',
        iconShadowColor: 'rgba(79, 110, 247, 0.25)',
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
          <Input placeholder={intl.formatMessage({ id: 'pages.channel.form.placeholder.name', defaultMessage: 'Please enter channel name' })} />
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
              // Switching type can leave the stored mode unrunnable; fall back to that type's
              // recommended mode rather than persisting a channel that receives nothing.
              const current = form.getFieldValue('communicationMode');
              form.setFieldValue('communicationMode', modeFor(value, current));
              // And drop the old platform's credentials from the form: `appId` / `appSecret` are the
              // same field names across types, so a feishu→dingtalk edit would otherwise write the
              // Feishu pair into the DingTalk channel and keep its Encrypt Key alongside. Non-managed
              // keys (the WeChat login tokens) are untouched by this.
              MANAGED_CONFIG_KEYS.forEach((key) => {
                form.setFieldValue(key, '');
              });
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

        {/* Session ID - 只读显示 */}
        {values?.sessionId && (
          <Form.Item
            label={
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <KeyOutlined style={{ color: 'var(--vip-primary)', fontSize: 13 }} />
                {intl.formatMessage({ id: 'pages.channel.form.label.sessionId', defaultMessage: 'Session ID' })}
              </span>
            }
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '10px 14px',
                background: 'var(--vip-bg-component)',
                border: '1px solid var(--vip-border)',
                borderRadius: 8,
                transition: 'border-color 0.2s, box-shadow 0.2s',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.borderColor = 'var(--vip-primary)';
                e.currentTarget.style.boxShadow = '0 0 0 2px rgba(99, 102, 241, 0.08)';
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.borderColor = 'var(--vip-border)';
                e.currentTarget.style.boxShadow = 'none';
              }}
            >
              <Text
                copyable={{
                  text: values.sessionId,
                  icon: [
                    <CopyOutlined key="copy" style={{ color: 'var(--vip-text-tertiary)', fontSize: 14 }} />,
                    <CopyOutlined key="copied" style={{ color: 'var(--vip-primary)', fontSize: 14 }} />,
                  ],
                }}
                style={{
                  flex: 1,
                  color: 'var(--vip-text-secondary)',
                  fontSize: 13,
                  fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
                  wordBreak: 'break-all',
                  lineHeight: 1.6,
                }}
              >
                {values.sessionId}
              </Text>
              <Tooltip title={intl.formatMessage({ id: 'pages.channel.tooltip.immutable', defaultMessage: 'Immutable, auto-generated at creation' })}>
                <span
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 4,
                    padding: '2px 8px',
                    borderRadius: 4,
                    background: 'rgba(99, 102, 241, 0.08)',
                    color: 'var(--vip-primary)',
                    fontSize: 11,
                    fontWeight: 500,
                    whiteSpace: 'nowrap',
                    flexShrink: 0,
                  }}
                >
                  {intl.formatMessage({ id: 'pages.channel.label.immutable', defaultMessage: 'IMMUTABLE' })}
                </span>
              </Tooltip>
            </div>
          </Form.Item>
        )}

        {/* 回调 URL - 使用 Form.Item 标签在外显示 */}
        {values?.callbackUrl && (
          <Form.Item
            label={
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <LinkOutlined style={{ color: 'var(--vip-primary)', fontSize: 13 }} />
                {intl.formatMessage({ id: 'pages.channel.form.label.callbackUrl', defaultMessage: 'Callback URL' })}
              </span>
            }
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '10px 14px',
                background: 'var(--vip-bg-component)',
                border: '1px solid var(--vip-border)',
                borderRadius: 8,
                transition: 'border-color 0.2s, box-shadow 0.2s',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.borderColor = 'var(--vip-primary)';
                e.currentTarget.style.boxShadow = '0 0 0 2px rgba(99, 102, 241, 0.08)';
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.borderColor = 'var(--vip-border)';
                e.currentTarget.style.boxShadow = 'none';
              }}
            >
              <Text
                copyable={{
                  text: values.callbackUrl,
                  icon: [
                    <CopyOutlined key="copy" style={{ color: 'var(--vip-text-tertiary)', fontSize: 14 }} />,
                    <CopyOutlined key="copied" style={{ color: 'var(--vip-primary)', fontSize: 14 }} />,
                  ],
                }}
                style={{
                  flex: 1,
                  color: 'var(--vip-text-secondary)',
                  fontSize: 13,
                  fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
                  wordBreak: 'break-all',
                  lineHeight: 1.6,
                }}
              >
                {values.callbackUrl}
              </Text>
              <Tooltip title={intl.formatMessage({ id: 'pages.channel.tooltip.autoGenerated', defaultMessage: 'Auto-generated' })}>
                <span
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 4,
                    padding: '2px 8px',
                    borderRadius: 4,
                    background: 'rgba(99, 102, 241, 0.08)',
                    color: 'var(--vip-primary)',
                    fontSize: 11,
                    fontWeight: 500,
                    whiteSpace: 'nowrap',
                    flexShrink: 0,
                  }}
                >
                  {intl.formatMessage({ id: 'pages.channel.label.auto', defaultMessage: 'AUTO' })}
                </span>
              </Tooltip>
            </div>
          </Form.Item>
        )}

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

export default UpdateForm;
