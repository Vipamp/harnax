import { Modal, Form, Input, Button, message, Select, Switch } from 'antd';
import React, { useState, useEffect } from 'react';
// @ts-ignore
import { getAgentPage } from '@/services/ant-design-pro/agent';
import { createSession } from '@/services/ant-design-pro/session';
// @ts-ignore
import { useModel } from '@umijs/max';

const { TextArea } = Input;

interface SettingsModalProps {
  visible: boolean;
  onCancel: () => void;
  onSuccess: (session: API.SessionItem) => void;
}

const SettingsModal: React.FC<SettingsModalProps> = ({ visible, onCancel, onSuccess }) => {
  const [form] = Form.useForm();
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;
  const [agents, setAgents] = useState<API.AgentItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [isPublic, setIsPublic] = useState(false);

  // 初始化表单数据
  useEffect(() => {
    if (visible) {
      loadAgents();
      form.resetFields();
      setIsPublic(false);
    }
  }, [visible, currentUser]);

  const loadAgents = async () => {
    setLoading(true);
    try {
      const res = await getAgentPage({ pageNum: 1, pageSize: 100, status: 1 });
      const enabledAgents = (res.data?.records || []).filter((item: any) => item.status === 1);
      setAgents(enabledAgents);
    } catch (error) {
      console.error('加载智能体列表失败', error);
    } finally {
      setLoading(false);
    }
  };

  const handleCreateSubmit = async () => {
    try {
      const formValues = await form.validateFields(['title', 'agentId', 'sessionDescription']);
      const createData: API.SessionCreateRequest = {
        title: formValues.title,
        sessionDescription: formValues.sessionDescription,
        agentId: formValues.agentId,
      };
      setLoading(true);
      const res = await createSession(createData);
      if (res.code === 200) {
        message.success('创建成功');
        form.resetFields();
        onSuccess(res.data || (createData as unknown as API.SessionItem));
      } else {
        message.error(res.message || '创建失败');
      }
    } catch (error) {
      console.error('创建失败', error);
    } finally {
      setLoading(false);
    }
  };

  const handleClose = () => {
    form.resetFields();
    onCancel();
  };

  return (
    <Modal
      title="创建会话"
      open={visible}
      onCancel={handleClose}
      onOk={handleCreateSubmit}
      confirmLoading={loading}
      okText="创建"
      cancelText="取消"
      width={600}
      destroyOnClose
    >
      <Form form={form} layout="vertical" style={{ marginTop: 24 }}>
        <Form.Item
          label="会话名称"
          name="title"
          rules={[{ required: true, message: '请输入会话名称' }]}
        >
          <Input placeholder="请输入会话名称" />
        </Form.Item>

        <Form.Item
          label="会话描述"
          name="sessionDescription"
        >
          <TextArea
            rows={3}
            placeholder="请输入会话描述（可选）"
            showCount
            maxLength={500}
          />
        </Form.Item>

        <Form.Item
          label="选择智能体"
          name="agentId"
          rules={[{ required: true, message: '请选择智能体' }]}
        >
          <Select
            placeholder="请选择智能体"
            loading={loading}
            showSearch
            optionFilterProp="label"
            options={agents.map(agent => ({
              label: `${agent.name}${agent.description ? ` - ${agent.description}` : ''}`,
              value: agent.id,
            }))}
          />
        </Form.Item>

        {/* 是否公开 */}
        <Form.Item
          label="是否公开"
          extra="公开后其他用户也可以查看此会话"
        >
          <Switch
            checked={isPublic}
            onChange={setIsPublic}
            checkedChildren="公开"
            unCheckedChildren="私有"
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default SettingsModal;
