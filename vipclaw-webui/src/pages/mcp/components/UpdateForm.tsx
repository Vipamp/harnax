import React, { useState, useEffect } from 'react';
import { Modal, Button, message, Switch } from 'antd';
import {
  ProForm,
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { ThunderboltOutlined } from '@ant-design/icons';
import { getCurrentUserInfo, isPublicSwitchDisabled } from '@/utils/permissionUtil';
import { isPersonal } from '@/utils/edition';

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
  const [testing, setTesting] = useState(false);
  const [isPublic, setIsPublic] = useState(values.isPublic === 1);
  const { username, isAdmin } = getCurrentUserInfo();

  // 当外部 values 变化时更新 mcpType 和 isPublic
  useEffect(() => {
    if (values?.type) {
      setMcpType(values.type);
    }
    if (values?.isPublic !== undefined) {
      setIsPublic(values.isPublic === 1);
    }
  }, [values]);

  // MCP 类型选项 - 根据版本动态生成
  const mcpTypeOptions = [
    { label: intl.formatMessage({ id: 'pages.mcp.type.stdio', defaultMessage: 'STDIO' }), value: 'stdio' },
    { label: intl.formatMessage({ id: 'pages.mcp.type.sse', defaultMessage: 'SSE' }), value: 'sse' },
    { label: intl.formatMessage({ id: 'pages.mcp.type.streamablehttp', defaultMessage: 'Streamable HTTP' }), value: 'streamablehttp' },
  ].filter(opt => isPersonal() || opt.value !== 'stdio');

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
    <Modal
      destroyOnClose
      title={
        <span style={{ fontSize: '16px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
          {intl.formatMessage({ id: 'pages.mcp.edit', defaultMessage: 'Edit MCP Service' })}
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
      <ProForm<API.McpServerUpdateRequest>
        onFinish={(formValues) => onSubmit({ ...formValues, isPublic: isPublic ? 1 : 0 })}
        submitter={{
          searchConfig: {
            submitText: intl.formatMessage({ id: 'pages.common.save', defaultMessage: 'Save' }),
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
        initialValues={{
          id: values.id,
          name: values.name,
          description: values.description,
          type: values.type,
          command: values.command,
          url: values.url,
          status: values.status,
        }}
      >
        <ProFormText
          name="name"
          label={intl.formatMessage({ id: 'pages.mcp.name', defaultMessage: 'MCP Name' })}
          placeholder="请输入 MCP 名称"
          rules={[
            { required: true, message: '请输入 MCP 名称' },
            { max: 100, message: '名称不能超过 100 个字符' },
          ]}
          fieldProps={{ size: 'large' }}
        />

        <ProFormTextArea
          name="description"
          label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
          placeholder="请输入 MCP 服务的描述信息（可选）"
          fieldProps={{ rows: 3 }}
        />

        <ProFormSelect
          name="type"
          label={intl.formatMessage({ id: 'pages.mcp.type', defaultMessage: 'Type' })}
          options={mcpTypeOptions}
          rules={[{ required: true, message: '请选择 MCP 类型' }]}
          fieldProps={{
            size: 'large',
            onChange: (val: string) => setMcpType(val),
          }}
        />

        {mcpType === 'stdio' && (
          <ProFormText
            name="command"
            label={intl.formatMessage({ id: 'pages.mcp.command', defaultMessage: 'Command' })}
            placeholder="如：npx -y @modelcontextprotocol/server-filesystem /tmp"
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
          fieldProps={{ size: 'large' }}
        />

        <ProForm.Item
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
        </ProForm.Item>
      </ProForm>
    </Modal>
  );
};

export default UpdateForm;
