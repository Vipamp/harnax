import React, { useEffect, useState } from 'react';
import { Form, Input, Select, Switch, Upload, message, Button } from 'antd';
import { createSkillSource, updateSkillSource, uploadSkillSourceZip } from '@/services/ant-design-pro/skillSource';
import { getCurrentUserInfo, isPublicSwitchDisabled } from '@/utils/permissionUtil';
import { useIntl } from '@umijs/max';
import { ThunderboltOutlined, UploadOutlined } from '@ant-design/icons';
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
  const [sourceType, setSourceType] = useState<string>('GIT');
  const [zipFile, setZipFile] = useState<File | null>(null);
  const { username, isAdmin } = getCurrentUserInfo();
  const isCreate = !values;

  useEffect(() => {
    if (visible) {
      if (values) {
        const type = values.sourceType || 'GIT';
        setSourceType(type);
        form.setFieldsValue({
          ...values,
          sourceType: type,
          branch: values.branch || 'main',
          isPublic: values.isPublic === 1,
          packageName: values.sourceConfig?.packageName,
          registry: values.sourceConfig?.registry,
        });
      } else {
        form.resetFields();
        form.setFieldsValue({
          sourceType: 'GIT',
          status: 1,
          branch: 'main',
          isPublic: false,
        });
        setSourceType('GIT');
        setZipFile(null);
      }
    }
  }, [visible, values, form]);

  const handleSubmit = async () => {
    try {
      const formValues = await form.validateFields();
      setLoading(true);

      if (sourceType === 'ZIP' && isCreate) {
        if (!zipFile) {
          message.error('Please select a ZIP file');
          setLoading(false);
          return;
        }
        const response = await uploadSkillSourceZip(zipFile, formValues.name);
        if (response.code === 200) {
          message.success('Upload and install successful');
          onSuccess();
        } else {
          message.error(response.message || 'Upload failed');
        }
      } else {
        let sourceConfig: Record<string, any> = {};
        if (sourceType === 'GIT') {
          sourceConfig = { url: formValues.url || '', branch: formValues.branch || 'main' };
        } else if (sourceType === 'NPM') {
          sourceConfig = { packageName: formValues.packageName, registry: formValues.registry || '' };
        }

        const data = {
          name: formValues.name,
          sourceType,
          sourceConfig,
          version: formValues.version || '',
          description: formValues.description || '',
          isPublic: formValues.isPublic ? 1 : 0,
          url: formValues.url || '',
          branch: formValues.branch || '',
        };

        if (values) {
          const response = await updateSkillSource(values.id, {
            name: data.name,
            sourceConfig: data.sourceConfig,
            version: data.version,
            description: data.description,
            url: data.url,
            branch: data.branch,
          });
          if (response.code === 200) {
            message.success('Update successful');
            onSuccess();
          } else {
            message.error(response.message || 'Update failed');
          }
        } else {
          const response = await createSkillSource(data);
          if (response.code === 200) {
            message.success('Create successful');
            onSuccess();
          } else {
            message.error(response.message || 'Create failed');
          }
        }
      }
    } catch (error: any) {
      const errorMsg = error?.message || error?.info?.errorMessage || 'Operation failed';
      message.error(errorMsg);
    } finally {
      setLoading(false);
    }
  };

  const handleReset = () => {
    if (values) {
      const type = values.sourceType || 'GIT';
      setSourceType(type);
      form.setFieldsValue({
        ...values,
        sourceType: type,
        branch: values.branch || 'main',
        isPublic: values.isPublic === 1,
        packageName: values.sourceConfig?.packageName,
        registry: values.sourceConfig?.registry,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({
        sourceType: 'GIT',
        status: 1,
        branch: 'main',
        isPublic: false,
      });
      setSourceType('GIT');
      setZipFile(null);
    }
  };

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="md"
      titleConfig={{
        mainTitle: isCreate
          ? intl.formatMessage({ id: 'pages.skill.repository.create', defaultMessage: 'Create Skill Source' })
          : intl.formatMessage({ id: 'pages.skill.repository.edit', defaultMessage: 'Edit Skill Source' }),
        subtitle: isCreate
          ? intl.formatMessage({ id: 'pages.skill.repository.create.subtitle', defaultMessage: 'Configure skill source connection and parameters' })
          : intl.formatMessage({ id: 'pages.skill.repository.edit.subtitle', defaultMessage: 'Modify source configuration, changes take effect immediately' }),
        icon: <ThunderboltOutlined />,
        iconGradient: 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)',
        iconShadowColor: 'rgba(79, 110, 247, 0.25)',
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
          label={intl.formatMessage({ id: 'pages.skill.repository.name', defaultMessage: 'Source Name' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.skill.repository.nameRequired', defaultMessage: 'Please enter source name' }) }]}
        >
          <Input
            placeholder={intl.formatMessage({ id: 'pages.skill.repository.name.placeholder', defaultMessage: 'Please enter source name' })}
          />
        </Form.Item>

        <Form.Item
          name="sourceType"
          label={intl.formatMessage({ id: 'pages.skill.source.type', defaultMessage: 'Source Type' })}
          rules={[{ required: true }]}
        >
          <Select
            onChange={(value: string) => setSourceType(value)}
            disabled={!isCreate}
          >
            <Select.Option value="GIT">Git Repository</Select.Option>
            <Select.Option value="NPM">NPM Package</Select.Option>
            <Select.Option value="ZIP">ZIP Upload</Select.Option>
          </Select>
        </Form.Item>

        {sourceType === 'GIT' && (
          <>
            <Form.Item
              name="url"
              label={intl.formatMessage({ id: 'pages.skill.repository.url', defaultMessage: 'Repository URL' })}
              rules={[{ required: true, message: 'Please enter Git URL' }]}
            >
              <Input
                placeholder="https://github.com/example/skills"
              />
            </Form.Item>
            <Form.Item
              name="branch"
              label={intl.formatMessage({ id: 'pages.skill.repository.branch', defaultMessage: 'Branch Name' })}
              initialValue="main"
            >
              <Input
                placeholder="main"
              />
            </Form.Item>
          </>
        )}

        {sourceType === 'NPM' && (
          <>
            <Form.Item
              name="packageName"
              label={intl.formatMessage({ id: 'pages.skill.npm.package', defaultMessage: 'Package Name' })}
              rules={[{ required: true, message: 'Please enter npm package name' }]}
            >
              <Input
                placeholder="@harnax/skill-pack"
              />
            </Form.Item>
            <Form.Item
              name="registry"
              label={intl.formatMessage({ id: 'pages.skill.npm.registry', defaultMessage: 'Registry' })}
            >
              <Input
                placeholder="https://registry.npmmirror.com"
              />
            </Form.Item>
          </>
        )}

        {sourceType === 'ZIP' && isCreate && (
          <Form.Item
            label={intl.formatMessage({ id: 'pages.skill.zip.file', defaultMessage: 'ZIP File' })}
            required
          >
            <Upload
              accept=".zip"
              maxCount={1}
              beforeUpload={(file) => {
                setZipFile(file);
                return false;
              }}
              onRemove={() => setZipFile(null)}
            >
              <Button icon={<UploadOutlined />}>
                {intl.formatMessage({ id: 'pages.skill.zip.select', defaultMessage: 'Select ZIP File' })}
              </Button>
            </Upload>
          </Form.Item>
        )}

        <Form.Item
          name="version"
          label={intl.formatMessage({ id: 'pages.skill.version', defaultMessage: 'Version' })}
        >
          <Input
            placeholder="1.0.0"
          />
        </Form.Item>

        <Form.Item
          name="description"
          label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
        >
          <TextArea
            rows={3}
            placeholder={intl.formatMessage({ id: 'pages.skill.repository.description.placeholder', defaultMessage: 'Please enter source description' })}
          />
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
              : intl.formatMessage({ id: 'pages.skill.repository.publicHint', defaultMessage: 'Other users can view this source after making it public' })
          }
        >
          <Switch
            checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
            unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
            disabled={isPublicSwitchDisabled(isAdmin, username, values?.creator, values?.isPublic, isCreate)}
          />
        </Form.Item>

        <Form.Item wrapperCol={{ span: 24 }} style={{ marginBottom: 0 }}>
          <div style={{
            display: 'flex',
            justifyContent: 'flex-end',
            gap: '10px',
            marginTop: '12px',
            paddingTop: '10px',
            paddingLeft: '168px',
            borderTop: '1px solid var(--vip-border)'
          }}>
            <Button
              onClick={handleReset}
              style={{
                fontWeight: 500,
                padding: '4px 20px',
              }}
            >
              {intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
            </Button>
            <Button
              type="primary"
              onClick={handleSubmit}
              loading={loading}
              style={{
                fontWeight: 500,
                padding: '4px 20px',
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
