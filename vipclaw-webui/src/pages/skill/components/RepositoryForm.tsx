import React, { useEffect, useState } from 'react';
import { Form, Input, Select, Switch, message, Button } from 'antd';
import { createSkillRepository, updateSkillRepository } from '@/services/ant-design-pro/skillRepository';
import { getCurrentUserInfo, isPublicSwitchDisabled } from '@/utils/permissionUtil';
import { useIntl } from '@umijs/max';
import { FolderOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';
import StatusSwitch from '@/components/StatusSwitch';

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
        form.setFieldsValue({
          ...values,
          branch: values.branch || 'main',
          isPublic: values.isPublic === 1,
        });
      } else {
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

  const handleReset = () => {
    if (values) {
      form.setFieldsValue({
        ...values,
        branch: values.branch || 'main',
        isPublic: values.isPublic === 1,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ 
        status: 1,
        branch: 'main',
        isPublic: false,
      });
    }
  };

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="md"
      titleConfig={{
        mainTitle: isCreate
          ? intl.formatMessage({ id: 'pages.skill.repository.create', defaultMessage: 'Create Repository' })
          : intl.formatMessage({ id: 'pages.skill.repository.edit', defaultMessage: 'Edit Repository' }),
        subtitle: isCreate
          ? intl.formatMessage({ id: 'pages.skill.repository.create.subtitle', defaultMessage: 'Configure skill repository connection and parameters' })
          : intl.formatMessage({ id: 'pages.skill.repository.edit.subtitle', defaultMessage: 'Modify repository configuration, changes take effect immediately' }),
        icon: <FolderOutlined />,
        iconGradient: isCreate
          ? 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)'
          : 'linear-gradient(135deg, var(--vip-warning) 0%, var(--vip-warning-light) 100%)',
        iconShadowColor: isCreate
          ? 'rgba(79, 110, 247, 0.25)'
          : 'rgba(250, 173, 20, 0.25)',
      }}
    >
      <Form 
        form={form} 
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
      >
        <Form.Item
          name="name"
          label={intl.formatMessage({ id: 'pages.skill.repository.name', defaultMessage: 'Repository Name' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.skill.repository.nameRequired', defaultMessage: 'Please enter repository name' }) }]}
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.skill.repository.name.placeholder', defaultMessage: 'Please enter repository name' })} />
        </Form.Item>
        <Form.Item 
          name="url" 
          label={intl.formatMessage({ id: 'pages.skill.repository.url', defaultMessage: 'Repository URL' })}
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.skill.repository.url.placeholder', defaultMessage: 'Please enter repository URL' })} />
        </Form.Item>
        <Form.Item 
          name="branch" 
          label={intl.formatMessage({ id: 'pages.skill.repository.branch', defaultMessage: 'Branch Name' })}
          initialValue="main"
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.skill.repository.branch.placeholder', defaultMessage: 'Please enter branch name, e.g.: main' })} />
        </Form.Item>
        <Form.Item 
          name="description" 
          label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
        >
          <TextArea rows={3} placeholder={intl.formatMessage({ id: 'pages.skill.repository.description.placeholder', defaultMessage: 'Please enter repository description' })} />
        </Form.Item>
        <Form.Item 
          label={intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })}
          name="status"
          initialValue={1}
          valuePropName="checked"
          getValueFromEvent={(checked) => (checked ? 1 : 0)}
          getValueProps={(value) => ({ checked: value === 1 })}
        >
          <StatusSwitch
            status={form.getFieldValue('status') ?? 1}
            onChange={(newStatus) => form.setFieldsValue({ status: newStatus })}
          />
        </Form.Item>

        <Form.Item
          name="isPublic"
          label={intl.formatMessage({ id: 'pages.common.isPublic', defaultMessage: 'Public' })}
          valuePropName="checked"
          initialValue={false}
          extra={
            isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, isCreate) && !isCreate
              ? intl.formatMessage({ id: 'pages.skill.repository.noPermission', defaultMessage: 'You do not have permission to modify this setting' })
              : intl.formatMessage({ id: 'pages.skill.repository.publicHint', defaultMessage: 'Other users can view this repository after making it public' })
          }
        >
          <Switch
            checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
            disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, isCreate)}
          />
        </Form.Item>

        {/* 按钮区域 */}
        <Form.Item wrapperCol={{ offset: 6, span: 18 }}>
          <div style={{ 
            display: 'flex', 
            justifyContent: 'flex-end', 
            gap: '12px',
            marginTop: '24px',
            paddingTop: '20px',
            borderTop: '1px solid var(--vip-border)'
          }}>
            <Button 
              onClick={handleReset}
              style={{
                fontSize: '13px',
                fontWeight: 500,
                height: '36px',
                padding: '6px 24px',
                borderRadius: '6px',
              }}
            >
              {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
            </Button>
            <Button 
              type="primary" 
              onClick={handleSubmit}
              loading={loading}
              style={{
                fontSize: '13px',
                fontWeight: 500,
                height: '36px',
                padding: '6px 24px',
                borderRadius: '6px',
              }}
            >
              {intl.formatMessage({ id: 'pages.common.submit', defaultMessage: 'Submit' })}
            </Button>
          </div>
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default RepositoryForm;
