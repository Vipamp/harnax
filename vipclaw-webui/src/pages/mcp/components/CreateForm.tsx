import React, { useState } from 'react';
import { Modal, Button, message, Switch } from 'antd';
import {
  ProForm,
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { ThunderboltOutlined } from '@ant-design/icons';
import { getCurrentUserInfo } from '@/utils/permissionUtil';
import { isPersonal } from '@/utils/edition';

export interface CreateFormProps {
  visible: boolean;
  onCancel: () => void;
  onSubmit: (values: API.McpServerCreateRequest) => Promise<void>;
  onConnectivityTest?: (values: API.McpServerCreateRequest) => Promise<boolean>;
}

// MCP 类型选项 - 根据版本动态生成
const getMcpTypeOptions = () => {
  const allOptions = [
    { label: 'STDIO（本地进程）', value: 'stdio' },
    { label: 'SSE（Server-Sent Events）', value: 'sse' },
    { label: 'Streamable HTTP', value: 'streamablehttp' },
  ];
  
  // 个人版支持所有模式,企业版和公网版不支持 stdio
  if (isPersonal()) {
    return allOptions;
  } else {
    return allOptions.filter(opt => opt.value !== 'stdio');
  }
};

const CreateForm: React.FC<CreateFormProps> = ({ visible, onCancel, onSubmit, onConnectivityTest }) => {
  const intl = useIntl();
  // 个人版默认 stdio,企业版和公网版默认 sse
  const [mcpType, setMcpType] = useState<string>(isPersonal() ? 'stdio' : 'sse');
  const [form] = ProForm.useForm();
  const [testing, setTesting] = useState(false);
  const { isAdmin } = getCurrentUserInfo();
  const [isPublic, setIsPublic] = useState(false);

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
    <Modal
      destroyOnClose
      title={
        <span style={{ fontSize: '16px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
          {intl.formatMessage({ id: 'pages.mcp.create', defaultMessage: 'Create MCP Service' })}
        </span>
      }
      width={640}
      open={visible}
      footer={null}
      onCancel={onCancel}
      styles={{
        body: { padding: '24px 28px', background: 'var(--vip-bg-layout)' },
        header: {
          background: 'var(--vip-primary-light)',
          borderBottom: '1px solid var(--vip-border)',
          padding: '18px 24px',
        },
      }}
    >
      <ProForm<API.McpServerCreateRequest>
        form={form}
        onFinish={(values) => onSubmit({ ...values, isPublic: isPublic ? 1 : 0 })}
        submitter={{
          searchConfig: {
            submitText: intl.formatMessage({ id: 'pages.common.create', defaultMessage: 'Create' }),
            resetText: intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' }),
          },
          render: (props, dom) => [
            <Button
              key="test"
              icon={<ThunderboltOutlined />}
              loading={testing}
              onClick={handleConnectivityTest}
              style={{ marginRight: 8 }}
            >
              {intl.formatMessage({ id: 'pages.mcp.connectivityTest', defaultMessage: 'Connectivity Test' })}
            </Button>,
            ...dom,
          ],
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
          fieldProps={{ size: 'large' }}
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
          options={getMcpTypeOptions()}
          initialValue={isPersonal() ? 'stdio' : 'sse'}
          rules={[{ required: true, message: '请选择 MCP 类型' }]}
          fieldProps={{
            size: 'large',
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
            fieldProps={{ size: 'large' }}
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
            fieldProps={{ size: 'large' }}
            extra={mcpType === 'sse' ? 'SSE 类型：填写 SSE 事件流端点地址' : 'Streamable HTTP 类型：填写 HTTP 端点地址'}
          />
        )}

        <ProFormSelect
          name="status"
          label={intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })}
          options={[
            { label: intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }), value: 1 },
            { label: intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' }), value: 0 },
          ]}
          initialValue={1}
          fieldProps={{ size: 'large', defaultValue: 1 }}
        />

        <ProForm.Item
          label={intl.formatMessage({ id: 'pages.common.isPublic', defaultMessage: 'Public' })}
          extra={intl.formatMessage({ id: 'pages.mcp.publicHint', defaultMessage: 'Other users can view this MCP service after making it public' })}
        >
          <Switch
            checked={isPublic}
            onChange={setIsPublic}
            checkedChildren="公开"
            unCheckedChildren="私有"
          />
        </ProForm.Item>
      </ProForm>
    </Modal>
  );
};

export default CreateForm;
