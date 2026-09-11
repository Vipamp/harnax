import React, { useState, useEffect } from 'react';
import { Button, message, Switch, Input, Form, Select } from 'antd';
import { useIntl } from '@umijs/max';
import { ThunderboltOutlined, ApiOutlined } from '@ant-design/icons';
import { getCurrentUserInfo, isPublicSwitchDisabled } from '@/utils/permissionUtil';
import { FormModal } from '@/components/FormModal';
import ConfigEntriesEditor from './ConfigEntriesEditor';
import OAuthFields from './OAuthFields';
import ToolEnvEntriesEditor from '@/pages/tool/components/ToolEnvEntriesEditor';

export interface UpdateFormProps {
  visible: boolean;
  values: API.McpServerItem;
  onCancel: () => void;
  onSubmit: (values: API.McpServerUpdateRequest) => Promise<void>;
  onConnectivityTest?: (id: number) => Promise<boolean>;
}

const UpdateForm: React.FC<UpdateFormProps> = ({ visible, values, onCancel, onSubmit, onConnectivityTest }) => {
  const intl = useIntl();
  const [mcpType, setMcpType] = useState<string>(values.type);
  const [authType, setAuthType] = useState<string>(values.authType || 'NONE');
  const [form] = Form.useForm();
  const [testing, setTesting] = useState(false);
  const [isPublic, setIsPublic] = useState(values.isPublic === 1);
  const [status, setStatus] = useState<number>(values.status || 1);
  const { username, isAdmin } = getCurrentUserInfo();

  // 初始化表单数据
  useEffect(() => {
    if (visible && values?.id) {
      form.setFieldsValue({
        id: values.id,
        name: values.name,
        description: values.description,
        type: values.type,
        command: values.command,
        url: values.url,
        headers: values.headers || [],
        envParams: values.envParams || [],
        authType: values.authType || 'NONE',
        // 库里发现的 issuer 直接回填：留空提交本来就等于「不改它」，让管理员看到它比看到空值诚实。
        // 整份对象要带上后端的默认值：oauth_config 为空的行后端按 resourceIndicator=true 处理，
        // 这里给 {} 会连开关的 initialValue 一起抹成「关」，显示与实际配置相反。
        oauthConfig: { resourceIndicator: true, ...(values.oauthConfig || {}) },
      });
    }
  }, [visible, values]);

  // 当外部 values 变化时更新 mcpType、authType、isPublic 和 status
  useEffect(() => {
    if (values?.type) {
      setMcpType(values.type);
    }
    setAuthType(values?.authType || 'NONE');
    if (values?.isPublic !== undefined) {
      setIsPublic(values.isPublic === 1);
    }
    if (values?.status !== undefined) {
      setStatus(values.status);
    }
  }, [values]);

  // 运行时只认 McpAuthTypes.SUPPORTED 这三个；V25 里的 BASIC 写进来会被 resolveAuthType 拒掉，给了选项就是让人配一个存不下的值
  const authTypeOptions = [
    { label: intl.formatMessage({ id: 'pages.mcp.oauth.auth.none', defaultMessage: 'None (no upstream credential)' }), value: 'NONE' },
    { label: intl.formatMessage({ id: 'pages.mcp.oauth.auth.staticHeader', defaultMessage: 'Static header (shared service credential)' }), value: 'STATIC_HEADER' },
    {
      label: intl.formatMessage({ id: 'pages.mcp.oauth.auth.oauth2', defaultMessage: 'OAuth 2.1 (per user)' }),
      value: 'OAUTH2',
      disabled: mcpType === 'stdio',
    },
  ];

  const mcpTypeOptions = [
    { label: intl.formatMessage({ id: 'pages.mcp.type.stdio', defaultMessage: 'STDIO' }), value: 'stdio' },
    { label: intl.formatMessage({ id: 'pages.mcp.type.sse', defaultMessage: 'SSE' }), value: 'sse' },
    { label: intl.formatMessage({ id: 'pages.mcp.type.streamablehttp', defaultMessage: 'Streamable HTTP' }), value: 'streamablehttp' },
  ];

  /** 连通性测试 */
  const handleConnectivityTest = async () => {
    if (!onConnectivityTest || !values?.id) {
      message.error(intl.formatMessage({ id: 'pages.mcp.testFailed', defaultMessage: 'Connectivity test failed' }));
      return;
    }
    setTesting(true);
    try {
      const result = await onConnectivityTest(values.id);
      if (result) {
        message.success(intl.formatMessage({ id: 'pages.message.mcpTestSuccess', defaultMessage: 'MCP connectivity test passed, service connection is normal' }, { name: values.name }));
      } else {
        message.error(intl.formatMessage({ id: 'pages.message.mcpTestFailed', defaultMessage: 'MCP connectivity test failed, service unreachable' }, { name: values.name }));
      }
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.message.mcpTestFailed', defaultMessage: 'MCP connectivity test failed, service unreachable' }, { name: values.name }));
    } finally {
      setTesting(false);
    }
  };

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="lg"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.mcp.edit', defaultMessage: 'Edit MCP Service' }),
        subtitle: intl.formatMessage({ id: 'pages.mcp.edit.subtitle', defaultMessage: 'Modify MCP service configuration, changes take effect immediately' }),
        icon: <ApiOutlined />,
        iconGradient: 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)',
        iconShadowColor: 'rgba(79, 110, 247, 0.25)',
      }}
    >
      <Form
        form={form}
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
        style={{ marginTop: 12 }}
        onFinish={(formValues) => {
          // Validate: required envParams must have defaultValue
          const envParams: any[] = formValues.envParams || [];
          const invalidEnv = envParams.find((e: any) => e.required && !e.defaultValue?.trim());
          if (invalidEnv) {
            message.error(intl.formatMessage({
              id: 'pages.mcp.envRequiredError',
              defaultMessage: 'Required environment parameter "{name}" must have a default value',
            }, { name: invalidEnv.envParamName || '' }));
            return;
          }
          const { oauthConfig, ...rest } = formValues;
          onSubmit({
            ...rest,
            // 后端在 authType 离开 OAUTH2 时会清掉 oauth_config，这里就不必把残留值带回去
            oauthConfig: formValues.authType === 'OAUTH2' ? oauthConfig : undefined,
            isPublic: isPublic ? 1 : 0,
            status,
          });
        }}
      >
        <Form.Item
          name="name"
          label={intl.formatMessage({ id: 'pages.mcp.name', defaultMessage: 'MCP Name' })}
          rules={[
            { required: true, message: intl.formatMessage({ id: 'pages.mcp.nameRequired', defaultMessage: 'Please enter MCP name' }) },
            { max: 100, message: intl.formatMessage({ id: 'pages.mcp.nameMax', defaultMessage: 'Name cannot exceed 100 characters' }) },
          ]}
        >
          <Input 
            placeholder={intl.formatMessage({ id: 'pages.mcp.namePlaceholder', defaultMessage: 'Enter MCP name' })}
          />
        </Form.Item>

        <Form.Item
          name="description"
          label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
        >
          <Input.TextArea
            rows={3}
            placeholder={intl.formatMessage({ id: 'pages.mcp.descriptionPlaceholder', defaultMessage: 'Enter MCP service description (optional)' })}
            maxLength={500}
          />
        </Form.Item>

        <Form.Item
          name="type"
          label={intl.formatMessage({ id: 'pages.mcp.type', defaultMessage: 'Type' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.mcp.typeRequired', defaultMessage: 'Please select MCP type' }) }]}
        >
          <Select
            placeholder={intl.formatMessage({ id: 'pages.mcp.typePlaceholder', defaultMessage: 'Please select MCP type' })}
            onChange={(val: string) => {
              setMcpType(val);
              // 隐藏字段的旧值仍会被提交出去（Form 默认 preserve），而后端把「没带」当作「保留」，
              // 所以切类型时不主动清掉，另一种传输方式的连接参数就一直留着。
              form.setFieldsValue(
                val === 'stdio'
                  ? { url: undefined, headers: undefined }
                  : { command: undefined, envParams: undefined },
              );
              if (val === 'stdio') {
                // 后端 validateAuthType 拒绝 stdio + OAUTH2，切过去就得把认证方式一并收回 NONE
                setAuthType('NONE');
                form.setFieldsValue({ authType: 'NONE' });
              }
            }}
            options={mcpTypeOptions}
          />
        </Form.Item>

        {mcpType === 'stdio' && (
          <>
            <Form.Item
              name="command"
              label={intl.formatMessage({ id: 'pages.mcp.command', defaultMessage: 'Command' })}
              rules={[{ required: true, message: intl.formatMessage({ id: 'pages.mcp.commandRequired', defaultMessage: 'stdio type requires execution command' }) }]}
              extra={intl.formatMessage({ id: 'pages.mcp.commandExtra', defaultMessage: 'stdio type: enter command line to start MCP process, supports parameters' })}
            >
              <Input 
                placeholder={intl.formatMessage({ id: 'pages.mcp.commandPlaceholder', defaultMessage: 'e.g.: npx -y @modelcontextprotocol/server-filesystem /tmp' })}
              />
            </Form.Item>

            <Form.Item
              name="envParams"
              label={intl.formatMessage({ id: 'pages.mcp.envParams', defaultMessage: '环境参数' })}
              extra={intl.formatMessage({ id: 'pages.mcp.envParamsExtra', defaultMessage: 'STDIO 进程的环境参数配置' })}
            >
              <ToolEnvEntriesEditor />
            </Form.Item>
          </>
        )}

        {(mcpType === 'sse' || mcpType === 'streamablehttp') && (
          <>
            {/* 网络型只给 Headers：envParams 是 stdio 进程环境，运行时对网络型不会消费（McpHelper.buildMcpConfig）；
                存量记录里已配的 envParams 不在表单中时，后端保持原值不动 */}
            <Form.Item
              name="url"
              label={intl.formatMessage({ id: 'pages.mcp.url', defaultMessage: 'Service URL' })}
              rules={[
                { required: true, message: intl.formatMessage({ id: 'pages.mcp.urlRequired', defaultMessage: '{type} type requires service URL' }, { type: mcpType === 'sse' ? 'SSE' : 'Streamable HTTP' }) },
                {
                  pattern: /^https?:\/\/[\w\-]+(\.[\w\-]+)*(:\d+)?(\/.*)?$/,
                  message: intl.formatMessage({ id: 'pages.mcp.urlInvalid', defaultMessage: 'Please enter correct URL format' }),
                },
              ]}
              extra={
                <>
                  {intl.formatMessage({ id: mcpType === 'sse' ? 'pages.mcp.urlExtraSse' : 'pages.mcp.urlExtraHttp', defaultMessage: mcpType === 'sse' ? 'SSE type: enter SSE event stream endpoint' : 'Streamable HTTP type: enter HTTP endpoint' })}
                  {authType === 'OAUTH2' && (
                    <div>
                      {intl.formatMessage({ id: 'pages.mcp.oauth.urlChangeWarning', defaultMessage: 'Changing the URL clears every user grant issued for the old address (the token is bound to it), so users have to authorize again.' })}
                    </div>
                  )}
                </>
              }
            >
              <Input 
                placeholder={mcpType === 'sse' ? 'http://localhost:3000/sse' : 'http://localhost:3000/mcp'}
              />
            </Form.Item>

            <Form.Item
              name="headers"
              label={intl.formatMessage({ id: 'pages.mcp.headers', defaultMessage: 'Headers' })}
              extra={intl.formatMessage({ id: 'pages.mcp.headersExtra', defaultMessage: 'HTTP 请求头配置，如认证 Token' })}
            >
              <ConfigEntriesEditor
                placeholder={{ key: 'Authorization', value: 'Bearer sk-xxx' }}
              />
            </Form.Item>
          </>
        )}

        <Form.Item
          name="authType"
          label={intl.formatMessage({ id: 'pages.mcp.oauth.authType', defaultMessage: 'Auth Method' })}
          extra={intl.formatMessage({ id: 'pages.mcp.oauth.authExtra', defaultMessage: 'OAuth only enables the two admin endpoints (discovery and client registration); the service itself still connects with the headers above until per-user token injection lands.' })}
        >
          <Select
            options={authTypeOptions}
            onChange={(val: string) => setAuthType(val)}
          />
        </Form.Item>

        {authType === 'OAUTH2' && (
          <OAuthFields intl={intl} storedIssuer={values.oauthConfig?.authorizationServer} />
        )}

        <Form.Item
          label={intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })}
        >
          <Switch
            checked={status === 1}
            onChange={(checked) => setStatus(checked ? 1 : 0)}
            checkedChildren={intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}
          />
        </Form.Item>

        <Form.Item
          label={intl.formatMessage({ id: 'pages.common.isPublic', defaultMessage: 'Public' })}
          extra={
            isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, false)
              ? intl.formatMessage({ id: 'pages.mcp.noPermission', defaultMessage: 'You do not have permission to modify this setting (public entities cannot be changed to private)' })
              : intl.formatMessage({ id: 'pages.mcp.publicHint', defaultMessage: 'Other users can view this MCP service after making it public' })
          }
        >
          <Switch
            checked={isPublic}
            onChange={setIsPublic}
            checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
            disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, false)}
          />
        </Form.Item>

        {/* 按钮区域 */}
        <Form.Item>
          <Button
            icon={<ThunderboltOutlined />}
            loading={testing}
            onClick={handleConnectivityTest}
          >
            {intl.formatMessage({ id: 'pages.mcp.connectivityTest', defaultMessage: 'Connectivity Test' })}
          </Button>
          <Button 
            type="primary" 
            onClick={() => form.submit()}
          >
            {intl.formatMessage({ id: 'pages.mcp.submit', defaultMessage: 'Submit' })}
          </Button>
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default UpdateForm;
