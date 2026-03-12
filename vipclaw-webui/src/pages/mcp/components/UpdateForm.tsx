import React, { useState, useEffect } from 'react';
import { Modal, Button, message } from 'antd';
import {
  ProForm,
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { ThunderboltOutlined } from '@ant-design/icons';

export interface UpdateFormProps {
  visible: boolean;
  values: API.McpServerItem;
  onCancel: () => void;
  onSubmit: (values: API.McpServerUpdateRequest) => Promise<void>;
  onConnectivityTest?: (id: number) => Promise<boolean>;
}

const MCP_TYPE_OPTIONS = [
  { label: 'STDIO（本地进程）', value: 'stdio' },
  { label: 'SSE（Server-Sent Events）', value: 'sse' },
  { label: 'Streamable HTTP', value: 'streamablehttp' },
];

const UpdateForm: React.FC<UpdateFormProps> = ({ visible, values, onCancel, onSubmit, onConnectivityTest }) => {
  const intl = useIntl();
  const [mcpType, setMcpType] = useState<string>(values.type);
  const [testing, setTesting] = useState(false);

  // 当外部 values 变化时更新 mcpType
  useEffect(() => {
    if (values?.type) {
      setMcpType(values.type);
    }
  }, [values]);

  /** 连通性测试 */
  const handleConnectivityTest = async () => {
    if (!onConnectivityTest || !values?.id) {
      message.error('无法进行连通性测试');
      return;
    }
    setTesting(true);
    try {
      const result = await onConnectivityTest(values.id);
      if (result) {
        message.success(`${values.name} 连通性测试通过`);
      } else {
        message.error(`${values.name} 连通性测试失败`);
      }
    } catch (error) {
      message.error(`${values.name} 连通性测试失败`);
    } finally {
      setTesting(false);
    }
  };

  return (
    <Modal
      destroyOnClose
      title={
        <span style={{ fontSize: '16px', fontWeight: 600, color: '#1a1a2e' }}>
          编辑 MCP 服务
        </span>
      }
      width={640}
      open={visible}
      footer={null}
      onCancel={onCancel}
      styles={{
        body: { padding: '24px 28px', background: '#fafbff' },
        header: {
          background: 'linear-gradient(135deg, #f7f8ff 0%, #eef1fe 100%)',
          borderBottom: '1px solid #e8ecfb',
          padding: '18px 24px',
        },
      }}
    >
      <ProForm<API.McpServerUpdateRequest>
        onFinish={onSubmit}
        submitter={{
          searchConfig: {
            submitText: '保存',
            resetText: '重置',
          },
          render: (props, dom) => [
            <Button
              key="test"
              icon={<ThunderboltOutlined />}
              loading={testing}
              onClick={handleConnectivityTest}
              style={{ marginRight: 8 }}
            >
              连通测试
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
          label="MCP 名称"
          placeholder="请输入 MCP 名称"
          rules={[
            { required: true, message: '请输入 MCP 名称' },
            { max: 100, message: '名称不能超过 100 个字符' },
          ]}
          fieldProps={{ size: 'large' }}
        />

        <ProFormTextArea
          name="description"
          label="描述"
          placeholder="请输入 MCP 服务的描述信息（可选）"
          fieldProps={{ rows: 3 }}
        />

        <ProFormSelect
          name="type"
          label="类型"
          options={MCP_TYPE_OPTIONS}
          rules={[{ required: true, message: '请选择 MCP 类型' }]}
          fieldProps={{
            size: 'large',
            onChange: (val: string) => setMcpType(val),
          }}
        />

        {mcpType === 'stdio' && (
          <ProFormText
            name="command"
            label="执行命令"
            placeholder="如：npx -y @modelcontextprotocol/server-filesystem /tmp"
            rules={[{ required: true, message: 'stdio 类型必须填写执行命令' }]}
            fieldProps={{ size: 'large' }}
            extra="stdio 类型：填写启动 MCP 进程的命令行，支持参数"
          />
        )}

        {(mcpType === 'sse' || mcpType === 'streamablehttp') && (
          <ProFormText
            name="url"
            label="服务地址"
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
          label="状态"
          options={[
            { label: '启用', value: 1 },
            { label: '禁用', value: 0 },
          ]}
          fieldProps={{ size: 'large' }}
        />
      </ProForm>
    </Modal>
  );
};

export default UpdateForm;
