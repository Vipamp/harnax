import React, { useEffect, useState } from 'react';
import { Modal, Form, Input, Select, Switch, Typography, message } from 'antd';
import { createSkillRepository, updateSkillRepository } from '@/services/ant-design-pro/skillRepository';
import { getCurrentUserInfo, isPublicSwitchDisabled } from '@/utils/permissionUtil';
import { useIntl } from '@umijs/max';

const { Text } = Typography;

const { TextArea } = Input;

interface RepositoryFormProps {
  visible: boolean;
  values: API.SkillRepositoryItem | null;
  onCancel: () => void;
  onSuccess: () => void;
}

const RepositoryForm: React.FC<RepositoryFormProps> = ({ visible, values, onCancel, onSuccess }) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const { username, isAdmin } = getCurrentUserInfo();
  const isCreate = !values;

  useEffect(() => {
    if (visible) {
      if (values) {
        // 编辑模式：设置已有值，如果 branch 为空则设置为 'main'
        form.setFieldsValue({
          ...values,
          branch: values.branch || 'main',
          isPublic: values.isPublic === 1,
        });
      } else {
        // 新建模式：重置表单并设置默认值
        form.resetFields();
        form.setFieldsValue({ 
          status: 1,
          branch: 'main',
          isPublic: false,
        });
      }
    }
  }, [visible, values, form]);

  const handleSubmit = async () => {
    try {
      const formValues = await form.validateFields();
      setLoading(true);
      const data = {
        ...formValues,
        isPublic: formValues.isPublic ? 1 : 0,
      };
      if (values) {
        const response = await updateSkillRepository(values.id, data);
        if (response.code === 200) {
          message.success('更新成功');
          onSuccess();
        } else {
          message.error(response.message || '更新失败');
        }
      } else {
        const response = await createSkillRepository(data);
        if (response.code === 200) {
          message.success('创建成功');
          onSuccess();
        } else {
          message.error(response.message || '创建失败');
        }
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || '操作失败';
      message.error(errorMsg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title={values ? intl.formatMessage({ id: 'pages.skill.repository.edit', defaultMessage: 'Edit Repository' }) : intl.formatMessage({ id: 'pages.skill.repository.create', defaultMessage: 'Create Repository' })}
      open={visible}
      onOk={handleSubmit}
      onCancel={onCancel}
      confirmLoading={loading}
      destroyOnClose
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="name"
          label={intl.formatMessage({ id: 'pages.skill.repository.name', defaultMessage: 'Repository Name' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.skill.repository.nameRequired', defaultMessage: 'Please enter repository name' }) }]}
        >
          <Input placeholder="请输入仓库名称" />
        </Form.Item>
        <Form.Item name="url" label="仓库地址">
          <Input placeholder="请输入仓库地址" />
        </Form.Item>
        <Form.Item name="branch" label="分支名称" initialValue="main">
          <Input placeholder="请输入分支名称，例如：main" />
        </Form.Item>
        <Form.Item name="description" label="仓库描述">
          <TextArea rows={3} placeholder="请输入仓库描述" />
        </Form.Item>
        <Form.Item name="status" label="状态" initialValue={1}>
          <Select
            options={[
              { label: '启用', value: 1 },
              { label: '禁用', value: 0 },
            ]}
          />
        </Form.Item>

        <Form.Item
          name="isPublic"
          label="是否公开"
          valuePropName="checked"
          initialValue={false}
          extra={
            isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, isCreate) && !isCreate
              ? '您没有权限修改此设置'
              : '公开后其他用户也可以查看此仓库'
          }
        >
          <Switch
            checkedChildren="公开"
            unCheckedChildren="私有"
            disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, isCreate)}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default RepositoryForm;
