import React, { useState } from 'react';
import { Button, message, Switch } from 'antd';
import {
  ProForm,
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { ThunderboltOutlined, PlusOutlined } from '@ant-design/icons';
import { getCurrentUserInfo } from '@/utils/permissionUtil';
import { isPersonal } from '@/utils/edition';
import { FormModal } from '@/components/FormModal';

export interface CreateFormProps {
  visible: boolean;
  onCancel: () => void;
  onSubmit: (values: API.McpServerCreateRequest) => Promise<void>;
  onConnectivityTest?: (values: API.McpServerCreateRequest) => Promise<boolean>;
}

const CreateForm: React.FC<CreateFormProps> = ({ visible, onCancel, onSubmit, onConnectivityTest }) => {
  const intl = useIntl();
  const [mcpType, setMcpType] = useState<string>(isPersonal() ? 'stdio' : 'sse');
  const [form] = ProForm.useForm();
  const [testing, setTesting] = useState(false);
  const { isAdmin } = getCurrentUserInfo();
  const [isPublic, setIsPublic] = useState(false);
  const [status, setStatus] = useState<number>(1);

  // MCP 类型选项 - 根据版本动态生成
  const mcpTypeOptions = [
    { label: intl.formatMessage({ id: 'pages.mcp.type.stdio', defaultMessage: 'STDIO' }), value: 'stdio' },
    { label: intl.formatMessage({ id: 'pages.mcp.type.sse', defaultMessage: 'SSE' }), value: 'sse' },
    { label: intl.formatMessage({ id: 'pages.mcp.type.streamablehttp', defaultMessage: 'Streamable HTTP' }), value: 'streamablehttp' },
  ].filter(opt => isPersonal() || opt.value !== 'stdio');

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
      size="md"
      titleConfig={{
        mainTitle: intl.formatMessage({ id: 'pages.mcp.create', defaultMessage: 'Create MCP Service' }),
        subtitle: intl.formatMessage({ id: 'pages.mcp.create.subtitle', defaultMessage: 'Configure MCP service connection and parameters' }),
        icon: <PlusOutlined />,
      }}
    >
      <ProForm<API.McpServerCreateRequest>
        form={form}
        onFinish={(values) => onSubmit({ ...values, isPublic: isPublic ? 1 : 0, status })}
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
        submitter={{
          render: (_, dom) => (
            <div style={{ 
              display: 'flex', 
              justifyContent: 'flex-end', 
              gap: '10px',
              marginTop: '12px',
              paddingTop: '10px',
              borderTop: '1px solid var(--vip-border)'
            }}>
              <Button
                key="test"
                icon={<ThunderboltOutlined />}
                loading={testing}
                onClick={handleConnectivityTest}
                style={{
                  fontSize: '12px',
                  fontWeight: 500,
                  height: '32px',
                  padding: '4px 20px',
                  borderRadius: '6px'
                }}
              >
                {intl.formatMessage({ id: 'pages.mcp.connectivityTest', defaultMessage: 'Connectivity Test' })}
              </Button>
              {dom.map((item: any) => 
                React.cloneElement(item, {
                  style: {
                    fontSize: '12px',
                    fontWeight: 500,
                    height: '32px',
                    padding: '4px 20px',
                    borderRadius: '6px',
                    ...(item.props.style || {})
                  }
                })
              )}
            </div>
          ),
          searchConfig: {
            submitText: intl.formatMessage({ id: 'pages.mcp.submit', defaultMessage: 'Submit' }),
            resetText: intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' }),
          },
        }}
      >
        <ProFormText
          name="name"
          label={intl.formatMessage({ id: 'pages.mcp.name', defaultMessage: 'MCP Name' })}
          placeholder={intl.formatMessage({ id: 'pages.mcp.namePlaceholder', defaultMessage: 'Enter MCP name, e.g.: filesystem-server' })}
          rules={[
            { required: true, message: '请输入 MCP 名称' },
            { max: 100, message: '名称不能超过 100 个字符' },
          ]}
        />

        <ProFormTextArea
          name="description"
          label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
          placeholder={intl.formatMessage({ id: 'pages.mcp.descriptionPlaceholder', defaultMessage: 'Enter MCP service description (optional)' })}
          fieldProps={{ rows: 3 }}
        />

        <ProFormSelect
          name="type"
          label={intl.formatMessage({ id: 'pages.mcp.type', defaultMessage: 'Type' })}
          options={mcpTypeOptions}
          initialValue={isPersonal() ? 'stdio' : 'sse'}
          rules={[{ required: true, message: '请选择 MCP 类型' }]}
          fieldProps={{
            defaultValue: isPersonal() ? 'stdio' : 'sse',
            onChange: (val: string) => setMcpType(val),
          }}
        />

        {mcpType === 'stdio' && (
          <ProFormText
            name="command"
            label={intl.formatMessage({ id: 'pages.mcp.command', defaultMessage: 'Command' })}
            placeholder={intl.formatMessage({ id: 'pages.mcp.commandPlaceholder', defaultMessage: 'e.g.: npx -y @modelcontextprotocol/server-filesystem /tmp' })}
            rules={[{ required: true, message: 'stdio 类型必须填写执行命令' }]}
            extra="stdio 类型：填写启动 MCP 进程的命令行，支持参数"
          />
        )}

        {(mcpType === 'sse' || mcpType === 'streamablehttp') && (
          <ProFormText
            name="url"
            label={intl.formatMessage({ id: 'pages.mcp.url', defaultMessage: 'Service URL' })}
            placeholder={mcpType === 'sse' ? 'http://localhost:3000/sse' : 'http://localhost:3000/mcp'}
            rules={[
              { required: true, message: `${mcpType === 'sse' ? 'SSE' : 'Streamable HTTP'} 类型必须填写服务地址` },
              { type: 'url', message: '请输入正确的 URL 格式' },
            ]}
            extra={mcpType === 'sse' ? 'SSE 类型：填写 SSE 事件流端点地址' : 'Streamable HTTP 类型：填写 HTTP 端点地址'}
          />
        )}

        <ProForm.Item
          label={intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })}
        >
          <Switch
            checked={status === 1}
            onChange={(checked) => setStatus(checked ? 1 : 0)}
            checkedChildren={intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}
            style={{
              backgroundColor: status === 1 ? '#4f6ef7' : '#d9d9d9',
            }}
          />
        </ProForm.Item>

        <ProForm.Item
          label={intl.formatMessage({ id: 'pages.common.isPublic', defaultMessage: 'Public' })}
          extra={intl.formatMessage({ id: 'pages.mcp.publicHint', defaultMessage: 'Other users can view this MCP service after making it public' })}
        >
          <Switch
            checked={isPublic}
            onChange={setIsPublic}
            checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
          />
        </ProForm.Item>
      </ProForm>
    </FormModal>
  );
};

export default CreateForm;
