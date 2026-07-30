import React, { useEffect, useState } from 'react';
import { Form, Input, Select } from 'antd';
import { useIntl } from '@umijs/max';
import { CodeOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';
import { getSkillPage } from '@/services/ant-design-pro/skill';
import { getSkillRepositoryList } from '@/services/ant-design-pro/agent';
import { BUILTIN_CLI_SKILL_REPO } from '@/constants/builtinRepository';

const { TextArea } = Input;

export interface CliFormProps {
  visible: boolean;
  /** undefined = create mode */
  values?: API.CliItem;
  onCancel: () => void;
  onSubmit: (values: API.CliCreateRequest | API.CliUpdateRequest) => Promise<void>;
}

const CliForm: React.FC<CliFormProps> = ({ visible, values, onCancel, onSubmit }) => {
  const intl = useIntl();
  const [form] = Form.useForm();
  const [skills, setSkills] = useState<API.SkillItem[]>([]);
  const isEdit = !!values?.id;

  useEffect(() => {
    if (visible) {
      loadSkills();
      if (values) {
        form.setFieldsValue({
          name: values.name,
          description: values.description,
          version: values.version,
          installScript: values.installScript,
          checkCommand: values.checkCommand,
          skillIds: (values.skillList || []).map((s) => s.skillId).filter((v): v is number => v != null),
        });
      } else {
        form.resetFields();
      }
    }
  }, [visible, values]);

  const loadSkills = async () => {
    try {
      const repoRes = await getSkillRepositoryList({ pageNum: 1, pageSize: 100 });
      const builtinRepo = (repoRes.data?.records || []).find((r: any) => r.name === BUILTIN_CLI_SKILL_REPO);
      if (!builtinRepo) {
        setSkills([]);
        return;
      }
      const res = await getSkillPage({ pageNum: 1, pageSize: 200, status: 1, repositoryId: builtinRepo.id });
      setSkills(res.data?.records || []);
    } catch (error) {
      console.error('Failed to load skills', error);
    }
  };

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="md"
      titleConfig={{
        mainTitle: isEdit
          ? intl.formatMessage({ id: 'pages.cli.edit', defaultMessage: 'Edit CLI Tool' })
          : intl.formatMessage({ id: 'pages.cli.create', defaultMessage: 'Create CLI Tool' }),
        subtitle: intl.formatMessage({
          id: 'pages.cli.form.subtitle',
          defaultMessage: 'CLI tools are installed into the agent sandbox image; associated skills are loaded automatically at runtime',
        }),
        icon: <CodeOutlined />,
      }}
    >
      <Form
        form={form}
        layout="horizontal"
        labelCol={{ span: 5 }}
        wrapperCol={{ span: 19 }}
        style={{ marginTop: 12 }}
        onFinish={(formValues) => onSubmit(formValues)}
      >
        <Form.Item
          name="name"
          label={intl.formatMessage({ id: 'pages.cli.name', defaultMessage: 'Name' })}
          rules={[
            { required: true, message: intl.formatMessage({ id: 'pages.cli.nameRequired', defaultMessage: 'Please enter CLI name' }) },
            { max: 128, message: intl.formatMessage({ id: 'pages.cli.nameMax', defaultMessage: 'Name cannot exceed 128 characters' }) },
          ]}
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.cli.namePlaceholder', defaultMessage: 'e.g. kubectl' })} />
        </Form.Item>

        <Form.Item
          name="version"
          label={intl.formatMessage({ id: 'pages.cli.version', defaultMessage: 'Version' })}
        >
          <Input placeholder={intl.formatMessage({ id: 'pages.cli.versionPlaceholder', defaultMessage: 'e.g. 1.30.0' })} />
        </Form.Item>

        <Form.Item
          name="description"
          label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
        >
          <TextArea rows={2} placeholder={intl.formatMessage({ id: 'pages.cli.descriptionPlaceholder', defaultMessage: 'What this CLI tool does' })} />
        </Form.Item>

        <Form.Item
          name="installScript"
          label={intl.formatMessage({ id: 'pages.cli.installScript', defaultMessage: 'Install Script' })}
          rules={[{ required: true, message: intl.formatMessage({ id: 'pages.cli.installScriptRequired', defaultMessage: 'Please enter the install script' }) }]}
          extra={intl.formatMessage({
            id: 'pages.cli.installScriptHint',
            defaultMessage: 'Dockerfile RUN fragment executed when building the sandbox image',
          })}
        >
          <TextArea
            rows={4}
            style={{ fontFamily: 'monospace' }}
            placeholder={'curl -LO "https://dl.k8s.io/release/v1.30.0/bin/linux/amd64/kubectl" && install -m 0755 kubectl /usr/local/bin/kubectl && rm kubectl'}
          />
        </Form.Item>

        <Form.Item
          name="checkCommand"
          label={intl.formatMessage({ id: 'pages.cli.checkCommand', defaultMessage: 'Check Command' })}
          extra={intl.formatMessage({
            id: 'pages.cli.checkCommandHint',
            defaultMessage: 'Used to verify the installation after the image is built (optional)',
          })}
        >
          <Input style={{ fontFamily: 'monospace' }} placeholder="kubectl version --client" />
        </Form.Item>

        <Form.Item
          name="skillIds"
          label={intl.formatMessage({ id: 'pages.cli.skills', defaultMessage: 'Skills' })}
          extra={intl.formatMessage({
            id: 'pages.cli.skillsHint',
            defaultMessage: 'Skills teaching the agent how to use this CLI; auto-loaded for agents that select this CLI',
          })}
        >
          <Select
            mode="multiple"
            allowClear
            placeholder={intl.formatMessage({ id: 'pages.cli.skillsPlaceholder', defaultMessage: 'Select associated skills (optional)' })}
            optionFilterProp="label"
            options={skills.map((skill) => ({ label: skill.name, value: skill.id }))}
          />
        </Form.Item>
      </Form>
    </FormModal>
  );
};

export default CliForm;
