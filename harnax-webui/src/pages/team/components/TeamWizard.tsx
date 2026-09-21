import { useIntl } from '@umijs/max';
import { Button, Form, Input, Select, Steps, Switch, message } from 'antd';
import React, { useEffect, useState } from 'react';
// @ts-ignore
import { getModelList, getSkillListByRepository } from '@/services/ant-design-pro/agent';
import { getSkillSourceOptions } from '@/services/ant-design-pro/skillSource';
import { TeamOutlined } from '@ant-design/icons';
import { FormModal } from '@/components/FormModal';
import { BUILTIN_CLI_SKILL_REPO } from '@/constants/builtinRepository';
import SkillConfigPanel from '@/pages/agent/components/SkillConfigPanel';
import type { SkillConfigState } from '@/pages/agent/components/SkillConfigPanel';
import { describeConfigIssue, findSkillIssue } from '@/pages/agent/components/configValidation';
import MembersField from './MembersField';

const { TextArea } = Input;
const { Step } = Steps;

/** 新建与编辑共用一个向导：两者只差初始值、提交调用和这一步的按钮文案。 */
export interface TeamWizardPayload {
  name: string;
  description: string;
  systemPrompt: string;
  modelId: number;
  /** 缺省表示不动主管的技能绑定，只有动过技能才整体替换 */
  skillIds?: number[];
  members: API.TeamMemberRequest[];
  isPublic: number;
}

export interface TeamWizardProps {
  visible: boolean;
  /** 传了即编辑态 */
  values?: API.TeamItem;
  agents: API.AgentItem[];
  onCancel: () => void;
  onSubmit: (payload: TeamWizardPayload) => Promise<void>;
}

/** 第一步的字段；点「完成」时若它们报错，需要跳回第一步才看得见红字 */
const BASIC_FIELDS = ['name', 'description', 'systemPrompt', 'modelId'];

type FormatMessage = (descriptor: { id: string; defaultMessage?: string }) => string;

/**
 * The picker only offers usable models, so a team whose lead model has since been disabled, deleted or
 * turned into a non-chat row has no option to render — the Select falls back to the raw id and the save
 * refusal points at nothing. Same shape as the member picker's unavailable row.
 */
function modelOptions(values: API.TeamItem | undefined, models: API.ModelItem[], t: FormatMessage) {
  const options = models.map((model) => ({
    label: `${model.modelName} - ${
      model.providerName || t({ id: 'pages.common.unknownProvider', defaultMessage: 'Unknown provider' })
    } ¥${model.price || 0}/M`,
    value: model.id as number,
    disabled: false,
  }));
  if (values?.modelId && !models.some((model) => model.id === values.modelId)) {
    options.unshift({
      label: `${values.modelName || `#${values.modelId}`} · ${t({
        id: 'pages.team.modelUnavailable',
        defaultMessage: 'model is disabled, deleted or no longer a chat model',
      })}`,
      value: values.modelId,
      disabled: true,
    });
  }
  return options;
}

const TeamWizard: React.FC<TeamWizardProps> = ({ visible, values, agents, onCancel, onSubmit }) => {
  const intl = useIntl();
  const isEdit = !!values;
  const [currentStep, setCurrentStep] = useState(0);
  const [form] = Form.useForm();
  const [repositories, setRepositories] = useState<API.SkillRepositoryItem[]>([]);
  const [skills, setSkills] = useState<API.SkillItem[]>([]);
  const [models, setModels] = useState<API.ModelItem[]>([]);
  const [skillConfigs, setSkillConfigs] = useState<SkillConfigState[]>([{}]);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!visible) {
      setCurrentStep(0);
      return;
    }
    form.resetFields();
    setSkillConfigs([{}]);
    if (values) {
      form.setFieldsValue({
        name: values.name,
        description: values.description,
        systemPrompt: values.systemPrompt,
        modelId: values.modelId,
        isPublic: values.isPublic === 1,
        members: (values.memberList || []).map((member) => ({
          agentId: member.agentId,
          delegationDescription: member.delegationDescription,
        })),
      });
      const bound = (values.skillList || []).map((item) => ({
        repositoryId: item.repositoryId,
        repositoryName: item.repositoryName,
        skillId: item.skillId,
        skillName: item.skillName,
        value: item.skillId,
        // 已失效的技能留在列表里并标出来：运行侧只是不装载它，删掉显示等于替用户隐瞒一条坏绑定
        label:
          item.skillAvailable === false
            ? `${item.skillName} · ${intl.formatMessage({
                id: 'pages.team.skillUnavailable',
                defaultMessage: 'This skill is disabled or deleted',
              })}`
            : item.skillName,
      }));
      if (bound.length > 0) {
        setSkillConfigs(bound);
        const repoIds = bound
          .map((item) => item.repositoryId)
          .filter((id): id is number => id !== undefined);
        Array.from(new Set(repoIds)).forEach((repositoryId) => {
          loadSkills(repositoryId);
        });
      }
    }
    loadRepositories();
    loadModels();
  }, [visible, values, form]);

  const loadRepositories = async () => {
    try {
      const res = await getSkillSourceOptions({ status: 1 });
      // 内置 CLI 仓库的技能只能由 CLI 关联引出，团队没有 CLI 这一段
      setRepositories((res.data?.records || []).filter((repo: any) => repo.name !== BUILTIN_CLI_SKILL_REPO));
    } catch (error) {
      console.error('加载技能仓库列表失败', error);
    }
  };

  const loadModels = async () => {
    try {
      const res = await getModelList({ pageNum: 1, pageSize: 100 });
      setModels(
        (res.data?.records || []).filter(
          (item: any) => item.status === 1 && item.modelType === 'chat',
        ),
      );
    } catch (error) {
      console.error('加载模型列表失败', error);
    }
  };

  const loadSkills = async (repositoryId: number) => {
    try {
      const res = await getSkillListByRepository(repositoryId, {
        pageNum: 1,
        pageSize: 100,
        status: 1,
      });
      setSkills(res.data?.records || []);
    } catch (error) {
      console.error('加载技能列表失败', error);
      setSkills([]);
    }
  };

  const showSkillIssue = (): boolean => {
    const issue = findSkillIssue(skillConfigs);
    if (!issue) return false;
    message.error(describeConfigIssue(issue, intl.formatMessage));
    return true;
  };

  const handleFinish = async () => {
    if (submitting) return;
    if (showSkillIssue()) {
      setCurrentStep(1);
      return;
    }
    let formValues: any;
    try {
      formValues = await form.validateFields();
    } catch (error: any) {
      const failed: any[] = error?.errorFields || [];
      if (failed.some((field) => BASIC_FIELDS.includes(String(field.name?.[0])))) {
        setCurrentStep(0);
      }
      return;
    }
    const selected = skillConfigs
      .map((config) => config.skillId)
      .filter((id): id is number => id !== undefined);
    if (!(formValues.members || []).length) {
      setCurrentStep(2);
      message.error(
        intl.formatMessage({ id: 'pages.team.membersExtra', defaultMessage: 'At least one member is required' }),
      );
      return;
    }
    // 技能集合没动就不发这个字段：后端的「整体替换」会拒收任何已经失效的绑定，
    // 一条坏技能就会让团队连改名都改不动。成员仍是整体替换——表单里删掉的行就是要消失。
    // 按集合比而不按行序：绑定本就不保序，挪动行顺序不该被当成一次改动。
    const skillKey = (ids: number[]) => [...ids].sort((a, b) => a - b).join(',');
    const skillsTouched =
      !isEdit || skillKey(selected) !== skillKey((values?.skillList || []).map((item) => item.skillId));
    const payload: TeamWizardPayload = {
      name: formValues.name,
      description: formValues.description,
      systemPrompt: formValues.systemPrompt,
      modelId: formValues.modelId,
      skillIds: skillsTouched ? selected : undefined,
      members: (formValues.members || []).map((member: any) => ({
        agentId: member.agentId,
        delegationDescription: member.delegationDescription,
      })),
      isPublic: formValues.isPublic ? 1 : 0,
    };
    setSubmitting(true);
    try {
      await onSubmit(payload);
    } finally {
      setSubmitting(false);
    }
  };

  const handleNext = async () => {
    if (currentStep === 2) {
      await handleFinish();
      return;
    }
    if (currentStep === 0) {
      try {
        await form.validateFields(BASIC_FIELDS);
      } catch {
        return;
      }
    } else if (showSkillIssue()) {
      return;
    }
    setCurrentStep(currentStep + 1);
  };

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      size="lg"
      titleConfig={{
        mainTitle: intl.formatMessage({
          id: isEdit ? 'pages.team.edit' : 'pages.team.create',
          defaultMessage: isEdit ? 'Edit Team' : 'New Team',
        }),
        subtitle: intl.formatMessage({
          id: isEdit ? 'pages.team.edit.subtitle' : 'pages.team.create.subtitle',
          defaultMessage: isEdit
            ? 'Running team sessions keep the previous members until refreshed'
            : 'Fill in the lead basics, then its skills, then the members it delegates to',
        }),
        icon: <TeamOutlined />,
      }}
    >
      <Steps
        current={currentStep}
        size="small"
        style={{ marginBottom: 16, paddingBottom: 16, borderBottom: '1px solid var(--vip-border)' }}
      >
        <Step title={intl.formatMessage({ id: 'pages.team.basicStep', defaultMessage: 'Basic Info' })} />
        <Step title={intl.formatMessage({ id: 'pages.team.skillStep', defaultMessage: 'Skills' })} />
        <Step title={intl.formatMessage({ id: 'pages.team.members', defaultMessage: 'Member Agents' })} />
      </Steps>

      <div style={{ maxHeight: 'calc(100vh - 320px)', overflowY: 'auto', paddingRight: 4 }}>
        {/* 三步共用一个 Form 实例：未显示的步骤保持挂载，切回来时红字与已填值都还在 */}
        <Form
          form={form}
          layout="horizontal"
          labelCol={{ span: 5 }}
          wrapperCol={{ span: 19 }}
          style={{ marginTop: 12 }}
          initialValues={{ members: [{}], isPublic: false }}
        >
          <div style={{ display: currentStep === 0 ? 'block' : 'none' }}>
            <Form.Item
              name="name"
              label={intl.formatMessage({ id: 'pages.team.name', defaultMessage: 'Team Name' })}
              extra={intl.formatMessage({
                id: 'pages.team.nameExtra',
                defaultMessage: 'The team name is what the lead is called in the conversation',
              })}
              rules={[
                {
                  required: true,
                  message: intl.formatMessage({
                    id: 'pages.team.nameRequired',
                    defaultMessage: 'Please enter the team name',
                  }),
                },
                {
                  max: 100,
                  message: intl.formatMessage({
                    id: 'pages.team.nameMax',
                    defaultMessage: 'Team name cannot exceed 100 characters',
                  }),
                },
              ]}
            >
              <Input
                placeholder={intl.formatMessage({
                  id: 'pages.team.namePlaceholder',
                  defaultMessage: 'e.g. Research report team',
                })}
              />
            </Form.Item>

            <Form.Item
              name="description"
              label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
              rules={[
                {
                  required: true,
                  message: intl.formatMessage({
                    id: 'pages.team.descriptionRequired',
                    defaultMessage: 'Please enter the team description',
                  }),
                },
              ]}
            >
              <TextArea
                rows={2}
                maxLength={500}
                placeholder={intl.formatMessage({
                  id: 'pages.team.descriptionPlaceholder',
                  defaultMessage: 'One line on what this team does',
                })}
              />
            </Form.Item>

            <Form.Item
              name="systemPrompt"
              label={intl.formatMessage({ id: 'pages.team.systemPrompt', defaultMessage: 'System Prompt' })}
              extra={intl.formatMessage({
                id: 'pages.team.systemPromptExtra',
                defaultMessage: 'This is the lead prompt itself — it no longer comes from an agent',
              })}
              rules={[
                {
                  required: true,
                  message: intl.formatMessage({
                    id: 'pages.team.systemPromptRequired',
                    defaultMessage: 'Please enter the system prompt',
                  }),
                },
              ]}
            >
              <TextArea
                rows={6}
                placeholder={intl.formatMessage({
                  id: 'pages.team.systemPromptPlaceholder',
                  defaultMessage:
                    'How the lead breaks the goal down, delegates and reports back. Markdown supported',
                })}
              />
            </Form.Item>

            <Form.Item
              name="modelId"
              label={intl.formatMessage({ id: 'pages.team.model', defaultMessage: 'Lead Model' })}
              rules={[
                {
                  required: true,
                  message: intl.formatMessage({
                    id: 'pages.team.modelRequired',
                    defaultMessage: 'Please select the model',
                  }),
                },
              ]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                placeholder={intl.formatMessage({
                  id: 'pages.team.modelPlaceholder',
                  defaultMessage: 'Select the model the lead runs on',
                })}
                options={modelOptions(values, models, intl.formatMessage)}
              />
            </Form.Item>

            <Form.Item
              name="isPublic"
              label={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
              valuePropName="checked"
              extra={intl.formatMessage({
                id: 'pages.team.publicExtra',
                defaultMessage: 'Public teams can be used by other users in this tenant',
              })}
            >
              <Switch
                checkedChildren={intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}
                unCheckedChildren={intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}
              />
            </Form.Item>
          </div>

          <div style={{ display: currentStep === 1 ? 'block' : 'none' }}>
            <div style={{ marginBottom: 16, color: 'var(--vip-text-secondary)', fontSize: 12 }}>
              {intl.formatMessage({
                id: 'pages.team.leadSkillHint',
                defaultMessage:
                  'The lead takes skills only — no tools, MCP or CLI. A skill with scripts still loads, but the lead has nothing to execute with, so only its text reaches it.',
              })}
            </div>
            <SkillConfigPanel
              skillConfigs={skillConfigs}
              setSkillConfigs={setSkillConfigs}
              repositories={repositories}
              skills={skills}
              onLoadSkills={loadSkills}
            />
          </div>

          <div style={{ display: currentStep === 2 ? 'block' : 'none' }}>
            <MembersField agents={agents} currentMembers={values?.memberList} />
          </div>
        </Form>
      </div>

      <div
        style={{
          display: 'flex',
          justifyContent: 'flex-end',
          gap: 10,
          marginTop: 12,
          paddingTop: 12,
          borderTop: '1px solid var(--vip-border)',
        }}
      >
        <Button disabled={currentStep === 0 || submitting} onClick={() => setCurrentStep(currentStep - 1)}>
          {intl.formatMessage({ id: 'pages.common.previous', defaultMessage: 'Previous' })}
        </Button>
        <Button type="primary" onClick={handleNext} loading={submitting} disabled={submitting}>
          {currentStep === 2
            ? intl.formatMessage({
                id: isEdit ? 'pages.common.save' : 'pages.common.create',
                defaultMessage: isEdit ? 'Save' : 'Create',
              })
            : intl.formatMessage({ id: 'pages.common.next', defaultMessage: 'Next' })}
        </Button>
      </div>
    </FormModal>
  );
};

export default TeamWizard;
