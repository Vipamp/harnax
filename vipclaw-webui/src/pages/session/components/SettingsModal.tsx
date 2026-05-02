import { Modal, Form, Input, Button, message, Select, Switch } from 'antd';
import React, { useState, useEffect } from 'react';
// @ts-ignore
import { getAgentPage } from '@/services/ant-design-pro/agent';
import { createSession, checkSessionTitle } from '@/services/ant-design-pro/session';
// @ts-ignore
import { useModel, useIntl } from '@umijs/max';
import { debounce } from 'lodash';

const { TextArea } = Input;

interface SettingsModalProps {
  visible: boolean;
  onCancel: () => void;
  onSuccess: (session: API.SessionItem) => void;
}

const SettingsModal: React.FC<SettingsModalProps> = ({ visible, onCancel, onSuccess }) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;
  const [agents, setAgents] = useState<API.AgentItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [isPublic, setIsPublic] = useState(false);
  const [titleChecking, setTitleChecking] = useState(false);

  // 异步校验会话名称
  const validateSessionTitle = async (_: any, value: string) => {
    if (!value) {
      return Promise.reject('请输入会话名称');
    }

    setTitleChecking(true);
    try {
      const res = await checkSessionTitle(value);
      if (res.code === 200 && res.data) {
        return Promise.reject('会话名称已存在，请使用其他名称');
      }
      return Promise.resolve();
    } catch (error) {
      console.error('检查会话名称失败', error);
      return Promise.reject('检查会话名称失败');
    } finally {
      setTitleChecking(false);
    }
  };

  // 创建防抖的校验函数
  const debouncedValidateTitle = debounce(validateSessionTitle, 500);

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
        // 关闭弹窗
        onCancel();
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
      title={intl.formatMessage({ id: 'pages.session.createSession', defaultMessage: 'Create Session' })}
      open={visible}
      onCancel={handleClose}
      onOk={handleCreateSubmit}
      confirmLoading={loading}
      okText={intl.formatMessage({ id: 'pages.common.create', defaultMessage: 'Create' })}
      cancelText={intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' })}
      width={600}
      destroyOnClose
    >
      <Form form={form} layout="vertical" style={{ marginTop: 24 }}>
        <Form.Item
          label={intl.formatMessage({ id: 'pages.session.sessionName', defaultMessage: 'Session Name' })}
          name="title"
          rules={[
            { required: true, message: intl.formatMessage({ id: 'pages.session.titleRequired', defaultMessage: 'Please enter session name' }) },
            {
              validator: async (_, value) => {
                if (!value) {
                  return Promise.resolve();
                }
                const res = await checkSessionTitle(value);
                if (res.code === 200 && res.data) {
                  return Promise.reject(new Error(intl.formatMessage({ id: 'pages.session.titleExists', defaultMessage: 'Session name already exists, please use another name' })));
                }
                return Promise.resolve();
              },
            },
          ]}
          validateTrigger="onBlur"
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.session.namePlaceholder', defaultMessage: 'Please enter session name' })} />
        </Form.Item>

        <Form.Item
          label={intl.formatMessage({ id: 'pages.session.sessionDescription', defaultMessage: 'Session Description' })}
          name="sessionDescription"
        >
          <TextArea
            rows={3}
            placeholder={intl.formatMessage({ id: 'pages.session.descriptionPlaceholder', defaultMessage: 'Please enter session description (optional)' })}
            showCount
            maxLength={500}
          />
        </Form.Item>

        <Form.Item
          label={intl.formatMessage({ id: 'pages.session.selectAgent', defaultMessage: 'Select Agent' })}
          name="agentId"
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.session.agentRequired', defaultMessage: "Please select agent" }) }]}
        >
          <Select
            placeholder={intl.formatMessage({ id: 'pages.session.selectAgentPlaceholder', defaultMessage: 'Please select agent' })}
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
          label={intl.formatMessage({ id: 'pages.session.isPublic', defaultMessage: 'Is Public' })}
          extra={intl.formatMessage({ id: 'pages.session.publicHint', defaultMessage: 'After making public, other users can also view this session' })}
        >
          <Switch
            checked={isPublic}
            onChange={setIsPublic}
            checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default SettingsModal;
