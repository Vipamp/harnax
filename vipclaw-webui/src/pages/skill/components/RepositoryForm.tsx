import React, { useEffect, useState } from 'react';
import { Modal, Form, Input, Select, message } from 'antd';
import { createSkillRepository, updateSkillRepository } from '@/services/ant-design-pro/skillRepository';

const { TextArea } = Input;

interface RepositoryFormProps {
  visible: boolean;
  values: API.SkillRepositoryItem | null;
  onCancel: () => void;
  onSuccess: () => void;
}

const RepositoryForm: React.FC<RepositoryFormProps> = ({ visible, values, onCancel, onSuccess }) => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (visible) {
      if (values) {
        // 编辑模式：设置已有值，如果 branch 为空则设置为 'main'
        form.setFieldsValue({
          ...values,
          branch: values.branch || 'main',
        });
      } else {
        // 新建模式：重置表单并设置默认值
        form.resetFields();
        form.setFieldsValue({ 
          status: 1,
          branch: 'main',
        });
      }
    }
  }, [visible, values, form]);

  const handleSubmit = async () => {
    try {
      const formValues = await form.validateFields();
      setLoading(true);
      if (values) {
        await updateSkillRepository(values.id, formValues);
        message.success('更新成功');
      } else {
        await createSkillRepository(formValues);
        message.success('创建成功');
      }
      onSuccess();
    } catch (error) {
      message.error('操作失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title={values ? '编辑仓库' : '新建仓库'}
      open={visible}
      onOk={handleSubmit}
      onCancel={onCancel}
      confirmLoading={loading}
      destroyOnClose
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="name"
          label="仓库名称"
          rules={[{ required: true, message: '请输入仓库名称' }]}
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
      </Form>
    </Modal>
  );
};

export default RepositoryForm;
