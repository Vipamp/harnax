import React, { useState } from 'react';
import { Button, message, Switch, Input, Form, Select } from 'antd';
import { useIntl } from '@umijs/max';
import { ThunderboltOutlined, ApiOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';
import ConfigEntriesEditor from './ConfigEntriesEditor';
import OAuthFields from './OAuthFields';
import ToolEnvEntriesEditor from '@/pages/tool/components/ToolEnvEntriesEditor';

export interface CreateFormProps {
  visible: boolean;
  onCancel: () => void;
  onSubmit: (values: API.McpServerCreateRequest) => Promise<void>;
  onConnectivityTest?: (values: API.McpServerCreateRequest) => Promise<boolean>;
}

const CreateForm: React.FC<CreateFormProps> = ({ visible, onCancel, onSubmit, onConnectivityTest }) => {
  const intl = useIntl();
  const [mcpType, setMcpType] = useState<string>('sse');
  const [authType, setAuthType] = useState<string>('NONE');
  const [form] = Form.useForm();
  const [testing, setTesting] = useState(false);
  const [isPublic, setIsPublic] = useState(false);
  const [status, setStatus] = useState<number>(1);

  // MCP 类型选项 - 后端与运行时均支持 stdio（McpHelper.buildMcpConfig），不再屏蔽
  const mcpTypeOptions = [
    { label: intl.formatMessage({ id: 'pages.mcp.type.stdio', defaultMessage: 'STDIO' }), value: 'stdio' },
    { label: intl.formatMessage({ id: 'pages.mcp.type.sse', defaultMessage: 'SSE' }), value: 'sse' },
    { label: intl.formatMessage({ id: 'pages.mcp.type.streamablehttp', defaultMessage: 'Streamable HTTP' }), value: 'streamablehttp' },
  ];

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

  /** 连通性测试 */
  const handleConnectivityTest = async () => {
    if (!onConnectivityTest) {
      message.info(intl.formatMessage({ id: 'pages.mcp.testAfterSave', defaultMessage: 'Please save the MCP service before testing connectivity' }));
      return;
    }
    try {
      const values = await form.validateFields();
      setTesting(true);
      const result = await onConnectivityTest(values);
      if (result) {
        message.success(intl.formatMessage({ id: 'pages.mcp.testSuccess', defaultMessage: 'Test Successful' }));
      } else {
        message.error(intl.formatMessage({ id: 'pages.mcp.testFailed', defaultMessage: 'Test Failed' }));
      }
    } catch (error) {
      // 表单验证失败
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
        mainTitle: intl.formatMessage({ id: 'pages.mcp.create', defaultMessage: 'Create MCP Service' }),
        subtitle: intl.formatMessage({ id: 'pages.mcp.create.subtitle', defaultMessage: 'Configure MCP service connection and parameters' }),
        icon: <ApiOutlined />,
      }}
    >
      <Form
        form={form}
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
        style={{ marginTop: 12 }}
        initialValues={{ type: 'sse' }}
        onFinish={(values) => {
          // Validate: required envs must have defaultValue
          const envParams: any[] = values.envParams || [];
          const invalidEnv = envParams.find((e: any) => e.required && !e.defaultValue?.trim());
          if (invalidEnv) {
            message.error(intl.formatMessage({
              id: 'pages.mcp.envRequiredError',
              defaultMessage: 'Required environment parameter "{name}" must have a default value',
            }, { name: invalidEnv.envParamName || '' }));
            return;
          }
          const { oauthConfig, ...rest } = values;
          onSubmit({
            ...rest,
            oauthConfig: values.authType === 'OAUTH2' ? oauthConfig : undefined,
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
            placeholder={intl.formatMessage({ id: 'pages.mcp.namePlaceholder', defaultMessage: 'Enter MCP name, e.g.: filesystem-server' })}
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
              // Form 默认 preserve：隐藏的字段仍带着旧值提交出去。切类型等于换一套连接参数，
              // 不把另一种的残留清掉，切回去时表单会显示成填过了。
              form.setFieldsValue(
                val === 'stdio'
                  ? { url: undefined, headers: undefined }
                  : { command: undefined, envParams: undefined },
              );
              if (val === 'stdio') {
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
            {/* 网络型只给 Headers：envParams 是 stdio 进程环境，运行时对网络型不会消费（McpHelper.buildMcpConfig） */}
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
              extra={intl.formatMessage({ id: mcpType === 'sse' ? 'pages.mcp.urlExtraSse' : 'pages.mcp.urlExtraHttp', defaultMessage: mcpType === 'sse' ? 'SSE type: enter SSE event stream endpoint' : 'Streamable HTTP type: enter HTTP endpoint' })}
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
          initialValue="NONE"
          extra={intl.formatMessage({ id: 'pages.mcp.oauth.authExtra', defaultMessage: 'OAuth only enables the two admin endpoints (discovery and client registration); the service itself still connects with the headers above until per-user token injection lands.' })}
        >
          <Select
            options={authTypeOptions}
            onChange={(val: string) => setAuthType(val)}
          />
        </Form.Item>

        {authType === 'OAUTH2' && <OAuthFields intl={intl} />}

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
          extra={intl.formatMessage({ id: 'pages.mcp.publicHint', defaultMessage: 'Other users can view this MCP service after making it public' })}
        >
          <Switch
            checked={isPublic}
            onChange={setIsPublic}
            checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
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
            onClick={() => {
              // resetFields 只管表单值；这几个驱动条件渲染的本地状态不一起退回去，重置后看到的
              // 表单就和提交出去的载荷不一致（OAuthFields 还挂着、type 却已退回 sse）
              form.resetFields();
              setMcpType('sse');
              setAuthType('NONE');
              setIsPublic(false);
              setStatus(1);
            }}
          >
            {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
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

export default CreateForm;
