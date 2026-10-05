import { useIntl, useParams, history } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  Form,
  Input,
  Modal,
  Radio,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  AuditOutlined,
  FileProtectOutlined,
  StopOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import React, { useCallback, useEffect, useState } from 'react';
import dayjs from 'dayjs';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import BackButton from '@/components/BackButton';
import {
  approveSkillDraft,
  getSkillDraft,
  rejectSkillDraft,
} from '@/services/ant-design-pro/skillDraft';

const STATUS_COLORS: Record<string, string> = {
  PENDING: 'orange',
  APPROVED: 'green',
  REJECTED: 'red',
};

const MAX_REJECT_REASON = 512;

/** SkillInstaller.MAX_SKILL_NAME_LENGTH: the rename has to fit the column the promoted row grows into. */
const MAX_SKILL_NAME = 100;

/**
 * One draft, as the reviewer reads it, plus the two decisions (design section 6.2 / 7).
 *
 * The approval sends back the digest this screen was built from rather than the body: an agent can still
 * patch a PENDING draft, and "approve what I am looking at" only means something if the server can check it.
 * Refusals come back on a 200 envelope as an `outcome` with the fields the next attempt needs, so the screen
 * branches on that and never re-fetches to find out what it just refused.
 */
const SkillDraftDetail: React.FC = () => {
  const intl = useIntl();
  const params = useParams<{ id: string }>();
  const draftId = Number(params.id);
  const [draft, setDraft] = useState<API.SkillDraftDetail | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [acting, setActing] = useState<boolean>(false);
  const [rejectOpen, setRejectOpen] = useState<boolean>(false);
  const [conflict, setConflict] = useState<{ name: string; skillId?: number } | null>(null);
  const [rejectForm] = Form.useForm<{ reason: string }>();
  const [conflictForm] = Form.useForm<{ resolution: 'replace' | 'rename'; newName?: string }>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const response = await getSkillDraft(draftId, { skipErrorHandler: true });
      // A refusal arrives on HTTP 200 with the code in the envelope, so umi's errorThrower never fires here;
      // without this check a draft belonging to another workspace would render as an empty screen.
      if (response.code !== 200) {
        throw new Error(response.message || '');
      }
      setDraft(response.data ?? null);
    } catch (error: any) {
      setDraft(null);
      message.error(
        error?.message ||
          error?.info?.errorMessage ||
          intl.formatMessage({ id: 'pages.skill.draft.detail.loadFailed', defaultMessage: 'Failed to load this draft' }),
      );
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftId]);

  useEffect(() => {
    if (draftId > 0) {
      load();
    }
  }, [draftId, load]);

  const showPromoted = (decision: API.SkillDraftDecision) => {
    const name = decision.promotedName || draft?.name || '';
    const skillPath = decision.skillId ? `/context/skill/detail/${decision.skillId}` : '/context/skill';
    if (decision.skillStatus === 1) {
      Modal.success({
        title: intl.formatMessage({ id: 'pages.skill.draft.promoted.title', defaultMessage: 'Promoted' }),
        content: intl.formatMessage(
          { id: 'pages.skill.draft.promoted.enabled', defaultMessage: '{name} is a skill now and enabled.' },
          { name },
        ),
        okText: intl.formatMessage({ id: 'pages.skill.draft.promoted.openSkill', defaultMessage: 'Open skill' }),
        onOk: () => history.push(skillPath),
      });
      return;
    }
    // Promotion is not enablement: the scan hit has to be said, and the entry to the second action given.
    Modal.warning({
      title: intl.formatMessage({ id: 'pages.skill.draft.promoted.title', defaultMessage: 'Promoted' }),
      content: intl.formatMessage(
        {
          id: 'pages.skill.draft.promoted.disabled',
          defaultMessage: '{name} was stored disabled: the content scan has {count} hit(s). Enable it yourself once you have read them.',
        },
        { name, count: decision.findings?.length ?? 0 },
      ),
      okText: intl.formatMessage({ id: 'pages.skill.draft.promoted.openSkill', defaultMessage: 'Open skill' }),
      onOk: () => history.push(skillPath),
    });
  };

  const showAlreadyReviewed = (decision: API.SkillDraftDecision) => {
    Modal.info({
      title: intl.formatMessage({ id: 'pages.skill.draft.refused.reviewedTitle', defaultMessage: 'Already decided' }),
      content: intl.formatMessage(
        {
          id: 'pages.skill.draft.refused.reviewed',
          defaultMessage: '{reviewer} closed this draft on {at}. {reason}',
        },
        {
          reviewer: decision.reviewedBy || '-',
          at: decision.reviewedAt ? dayjs(decision.reviewedAt).format('YYYY-MM-DD HH:mm') : '-',
          reason: decision.rejectReason
            ? `${intl.formatMessage({ id: 'pages.skill.draft.rejectReason', defaultMessage: 'Reason' })}: ${decision.rejectReason}`
            : '',
        },
      ),
    });
  };

  const handleDecision = (decision: API.SkillDraftDecision, askedName: string = '') => {
    switch (decision.outcome) {
      case 'PROMOTED':
        showPromoted(decision);
        break;
      case 'REJECTED':
        message.success(intl.formatMessage({ id: 'pages.skill.draft.reject.done', defaultMessage: 'Draft rejected' }));
        break;
      case 'DRAFT_CHANGED':
        // The content moved under the reviewer; forcing a reload beats a silent retry on the old digest.
        Modal.warning({
          title: intl.formatMessage({ id: 'pages.skill.draft.refused.changedTitle', defaultMessage: 'Draft changed' }),
          content: (
            <Space direction="vertical" size={8} style={{ display: 'flex' }}>
              <span>
                {intl.formatMessage({
                  id: 'pages.skill.draft.refused.changed',
                  defaultMessage: 'The agent patched this draft after you opened it. Read the new content, then approve it again.',
                })}
              </span>
              {decision.currentDigest ? (
                <Typography.Text code copyable={{ text: decision.currentDigest }}>
                  {`${decision.currentDigest.slice(0, 16)}…`}
                </Typography.Text>
              ) : null}
            </Space>
          ),
        });
        break;
      case 'ALREADY_REVIEWED':
        showAlreadyReviewed(decision);
        break;
      case 'NAME_TAKEN':
        // The backend reports the name that collided, so the dialog has to show what this attempt asked for.
        setConflict({ name: askedName || draft?.name || '', skillId: decision.skillId });
        return;
      default:
        message.error(
          decision.reason ||
            intl.formatMessage({ id: 'pages.skill.draft.decisionFailed', defaultMessage: 'The decision was refused' }),
        );
    }
    load();
  };

  const sendApprove = async (body: API.SkillDraftApproveRequest) => {
    const askedName = body.conflictResolution === 'rename' ? body.newName || '' : draft?.name || '';
    setActing(true);
    try {
      const response = await approveSkillDraft(draftId, body, { skipErrorHandler: true });
      if (response.data) {
        handleDecision(response.data, askedName);
      } else if (response.code === 409) {
        // The one refusal that is not an outcome: somebody published this name between the conflict probe
        // and the write, so the whole approval rolled back. The draft is still pending, which is why the
        // page reloads it — "decision refused" would read as a closed review.
        message.warning(
          intl.formatMessage({
            id: 'pages.skill.draft.conflict.race',
            defaultMessage:
              'That name was taken while this approval ran, so nothing was written. The draft is still pending — reload it and decide again.',
          }),
        );
        load();
      } else {
        message.error(response.message || intl.formatMessage({ id: 'pages.skill.draft.decisionFailed', defaultMessage: 'The decision was refused' }));
      }
    } catch (error: any) {
      message.error(error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.skill.draft.decisionFailed', defaultMessage: 'The decision was refused' }));
    } finally {
      setActing(false);
    }
  };

  const approve = () => {
    if (!draft?.contentDigest) {
      message.error(
        intl.formatMessage({
          id: 'pages.skill.draft.digestMissing',
          defaultMessage: 'This draft carries no content digest; reload the page before approving',
        }),
      );
      load();
      return;
    }
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.skill.draft.approve.confirmTitle', defaultMessage: 'Promote this draft?' }),
      content: intl.formatMessage({
        id: 'pages.skill.draft.approve.confirmBody',
        defaultMessage: 'Promotion writes the skill table for this tenant. A content scan hit stores it disabled rather than refusing it.',
      }),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'OK' }),
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
      onOk: () => sendApprove({ expectedDigest: draft.contentDigest }),
    });
  };

  const reject = async () => {
    const values = await rejectForm.validateFields();
    setActing(true);
    try {
      const response = await rejectSkillDraft(draftId, { reason: values.reason.trim() }, { skipErrorHandler: true });
      if (response.data) {
        setRejectOpen(false);
        rejectForm.resetFields();
        handleDecision(response.data);
      } else {
        message.error(response.message || intl.formatMessage({ id: 'pages.skill.draft.decisionFailed', defaultMessage: 'The decision was refused' }));
      }
    } catch (error: any) {
      message.error(error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.skill.draft.decisionFailed', defaultMessage: 'The decision was refused' }));
    } finally {
      setActing(false);
    }
  };

  const resolveConflict = async () => {
    const values = await conflictForm.validateFields();
    const resolution = values.resolution;
    setConflict(null);
    await sendApprove({
      expectedDigest: draft?.contentDigest || '',
      conflictResolution: resolution,
      newName: resolution === 'rename' ? values.newName?.trim() : undefined,
    });
  };

  const tabItems = () => {
    if (!draft) return [];
    const items: any[] = [
      {
        key: 'skillmd',
        label: intl.formatMessage({ id: 'pages.skill.draft.tab.content', defaultMessage: 'Body' }),
        children: (
          <div style={{ padding: 16, maxHeight: 'calc(100vh - 420px)', overflow: 'auto' }}>
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{draft.skillmd || ''}</ReactMarkdown>
          </div>
        ),
      },
      {
        key: 'resources',
        label: intl.formatMessage({ id: 'pages.skill.draft.tab.resources', defaultMessage: 'Files' }),
        children:
          Object.keys(draft.resources || {}).length === 0 ? (
            <Empty description={intl.formatMessage({ id: 'pages.skill.draft.resources.empty', defaultMessage: 'No support files' })} style={{ paddingTop: 40 }} />
          ) : (
            <div style={{ padding: 16, maxHeight: 'calc(100vh - 420px)', overflow: 'auto' }}>
              {Object.entries(draft.resources || {}).map(([path, content]) => (
                <div key={path} style={{ marginBottom: 16 }}>
                  <Typography.Text code>{path}</Typography.Text>
                  <pre
                    style={{
                      marginTop: 8,
                      padding: 12,
                      background: 'var(--vip-bg-layout, #f6f8fa)',
                      border: '1px solid var(--vip-border)',
                      borderRadius: 8,
                      whiteSpace: 'pre-wrap',
                      fontSize: 12,
                    }}
                  >
                    {content}
                  </pre>
                </div>
              ))}
            </div>
          ),
      },
      {
        key: 'scripts',
        label: intl.formatMessage({ id: 'pages.skill.draft.tab.scripts', defaultMessage: 'Scripts' }),
        children: <ScriptsTab scripts={draft.scripts || []} />,
      },
      {
        key: 'scans',
        label: intl.formatMessage({ id: 'pages.skill.draft.tab.scans', defaultMessage: 'Content scan' }),
        children: <ScansTab draft={draft} />,
      },
      {
        key: 'source',
        label: intl.formatMessage({ id: 'pages.skill.draft.tab.source', defaultMessage: 'Origin' }),
        children: <SourceTab draft={draft} />,
      },
    ];
    return items;
  };

  return (
    <PageContainer
      header={{
        title: (
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <BackButton onClick={() => history.push('/context/skill-drafts')} />
            <div style={{ width: 1, height: 24, background: 'var(--vip-border)' }} />
            <span style={{ fontSize: 18, fontWeight: 600, color: 'var(--vip-text-primary)', margin: 0 }}>
              <FileProtectOutlined style={{ marginRight: 10, color: '#531dab' }} />
              {intl.formatMessage({ id: 'pages.skill.draft.detail.title', defaultMessage: 'Draft for review' })}
              {draft && (
                <Tag color={STATUS_COLORS[draft.status] || 'default'} style={{ marginLeft: 10 }}>
                  {intl.formatMessage({ id: `pages.skill.draft.status.${draft.status}`, defaultMessage: draft.status })}
                </Tag>
              )}
            </span>
          </div>
        ),
        extra:
          draft?.status === 'PENDING'
            ? [
                <Button key="reject" danger icon={<StopOutlined />} disabled={acting} onClick={() => setRejectOpen(true)}>
                  {intl.formatMessage({ id: 'pages.skill.draft.reject', defaultMessage: 'Reject' })}
                </Button>,
                <Button key="approve" type="primary" icon={<AuditOutlined />} loading={acting} onClick={approve}>
                  {intl.formatMessage({ id: 'pages.skill.draft.approve', defaultMessage: 'Approve' })}
                </Button>,
              ]
            : undefined,
      }}
    >
      <Spin spinning={loading}>
        {!draft && !loading ? (
          <Empty description={intl.formatMessage({ id: 'pages.skill.draft.detail.notFound', defaultMessage: 'Draft not found' })} />
        ) : (
          draft && (
            <>
              <Card
                style={{ borderRadius: 16, border: '1px solid var(--vip-border)', marginBottom: 16 }}
                styles={{ body: { padding: 16 } }}
              >
                <Descriptions column={2} size="small">
                  <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.draft.name', defaultMessage: 'Proposed skill' })}>
                    {draft.name}
                  </Descriptions.Item>
                  <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.draft.proposedAt', defaultMessage: 'Proposed' })}>
                    {draft.createTime ? dayjs(draft.createTime).format('YYYY-MM-DD HH:mm') : '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.draft.description', defaultMessage: 'Description' })}>
                    {draft.description || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.draft.patchedAt', defaultMessage: 'Last patch' })}>
                    {draft.updateTime ? dayjs(draft.updateTime).format('YYYY-MM-DD HH:mm') : '-'}
                  </Descriptions.Item>
                </Descriptions>
                {draft.status === 'PENDING' ? (
                  <Alert
                    type="info"
                    showIcon
                    message={intl.formatMessage(
                      {
                        id: 'pages.skill.draft.digestHint',
                        defaultMessage: 'Approving sends this digest ({digest}); if the agent patches the draft first, the approval is refused and the page reloads.',
                      },
                      { digest: `${draft.contentDigest.slice(0, 12)}…` },
                    )}
                  />
                ) : (
                  <Alert
                    type={draft.status === 'APPROVED' ? 'success' : 'warning'}
                    showIcon
                    message={intl.formatMessage(
                      { id: 'pages.skill.draft.decidedHint', defaultMessage: '{reviewer} decided this on {at}. The page is read-only now.' },
                      {
                        reviewer: draft.reviewedBy || '-',
                        at: draft.reviewedAt ? dayjs(draft.reviewedAt).format('YYYY-MM-DD HH:mm') : '-',
                      },
                    )}
                    description={draft.status === 'REJECTED' ? draft.rejectReason : undefined}
                  />
                )}
              </Card>

              <Card
                style={{
                  borderRadius: 16,
                  border: '1px solid var(--vip-border)',
                  boxShadow: 'var(--vip-shadow-sm)',
                  overflow: 'hidden',
                }}
                styles={{ body: { padding: 0 } }}
              >
                <Tabs defaultActiveKey="skillmd" items={tabItems()} size="large" style={{ padding: '0 24px' }} />
              </Card>
            </>
          )
        )}
      </Spin>

      <Modal
        open={rejectOpen}
        title={intl.formatMessage({ id: 'pages.skill.draft.reject.title', defaultMessage: 'Reject this draft' })}
        okText={intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'OK' })}
        cancelText={intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' })}
        confirmLoading={acting}
        onCancel={() => {
          setRejectOpen(false);
          rejectForm.resetFields();
        }}
        onOk={reject}
      >
        <Form form={rejectForm} layout="vertical">
          <Form.Item
            name="reason"
            label={intl.formatMessage({ id: 'pages.skill.draft.rejectReason', defaultMessage: 'Reason' })}
            rules={[
              {
                required: true,
                message: intl.formatMessage({ id: 'pages.skill.draft.reject.reasonRequired', defaultMessage: 'A rejection needs a reason' }),
              },
              {
                validator: (_rule, value) =>
                  (value || '').trim().length > MAX_REJECT_REASON
                    ? Promise.reject(
                        new Error(
                          intl.formatMessage(
                            { id: 'pages.skill.draft.reject.tooLong', defaultMessage: 'Keep the reason under {count} characters' },
                            { count: MAX_REJECT_REASON },
                          ),
                        ),
                      )
                    : Promise.resolve(),
              },
            ]}
            extra={intl.formatMessage({
              id: 'pages.skill.draft.reject.reasonHint',
              defaultMessage: 'This is what the agent is told if it proposes the same skill again.',
            })}
          >
            <Input.TextArea rows={4} maxLength={MAX_REJECT_REASON + 1} showCount />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={!!conflict}
        title={intl.formatMessage({ id: 'pages.skill.draft.conflict.title', defaultMessage: 'That name is taken' })}
        okText={intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'OK' })}
        cancelText={intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' })}
        confirmLoading={acting}
        onCancel={() => {
          setConflict(null);
          conflictForm.resetFields();
          load();
        }}
        onOk={resolveConflict}
      >
        <Typography.Paragraph>
          {intl.formatMessage(
            {
              id: 'pages.skill.draft.conflict.hint',
              defaultMessage: 'A skill already holds the name {name} in this tenant. Choose one: overwrite it, or promote under a new name. Nothing is overwritten silently.',
            },
            { name: conflict?.name || draft?.name || '' },
          )}
        </Typography.Paragraph>
        {conflict?.skillId ? (
          <Typography.Paragraph style={{ marginBottom: 16 }}>
            <a onClick={() => history.push(`/context/skill/detail/${conflict.skillId}`)}>
              {intl.formatMessage({
                id: 'pages.skill.draft.conflict.openExisting',
                defaultMessage: 'View the skill that holds the name',
              })}
            </a>
          </Typography.Paragraph>
        ) : null}
        <Form form={conflictForm} layout="vertical" initialValues={{ resolution: 'rename' }}>
          <Form.Item name="resolution" label={intl.formatMessage({ id: 'pages.skill.draft.conflict.choose', defaultMessage: 'Resolution' })}>
            <Radio.Group>
              <Space direction="vertical">
                <Radio value="rename">
                  {intl.formatMessage({ id: 'pages.skill.draft.conflict.rename', defaultMessage: 'Promote under a new name' })}
                </Radio>
                <Radio value="replace">
                  {intl.formatMessage({ id: 'pages.skill.draft.conflict.replace', defaultMessage: 'Replace the existing skill' })}
                </Radio>
              </Space>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(prev, next) => prev.resolution !== next.resolution}
          >
            {({ getFieldValue }) =>
              getFieldValue('resolution') === 'rename' ? (
                <Form.Item
                  name="newName"
                  label={intl.formatMessage({ id: 'pages.skill.draft.conflict.newName', defaultMessage: 'New name' })}
                  rules={[
                    {
                      required: true,
                      whitespace: true,
                      message: intl.formatMessage({ id: 'pages.skill.draft.conflict.newNameRequired', defaultMessage: 'Type the name to promote under' }),
                    },
                    {
                      max: MAX_SKILL_NAME,
                      message: intl.formatMessage(
                        { id: 'pages.skill.draft.conflict.newNameTooLong', defaultMessage: 'Keep the name under {count} characters' },
                        { count: MAX_SKILL_NAME },
                      ),
                    },
                  ]}
                >
                  <Input placeholder={draft?.name} maxLength={MAX_SKILL_NAME} />
                </Form.Item>
              ) : null
            }
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

const ScriptsTab: React.FC<{ scripts: API.SkillDraftScript[] }> = ({ scripts }) => {
  const intl = useIntl();
  if (scripts.length === 0) {
    return <Empty description={intl.formatMessage({ id: 'pages.skill.draft.scripts.empty', defaultMessage: 'No script files' })} style={{ paddingTop: 40 }} />;
  }
  const columns: ColumnsType<API.SkillDraftScript> = [
    {
      title: intl.formatMessage({ id: 'pages.skill.draft.scripts.path', defaultMessage: 'Path' }),
      dataIndex: 'relPath',
      key: 'relPath',
      render: (path: string) => <Typography.Text code>{path}</Typography.Text>,
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.draft.scripts.lines', defaultMessage: 'Lines' }),
      dataIndex: 'totalLines',
      key: 'totalLines',
      width: 90,
    },
    {
      title: 'sha256',
      dataIndex: 'sha256',
      key: 'sha256',
      width: 220,
      render: (hash: string) => <Typography.Text copyable={{ text: hash }}>{`${(hash || '').slice(0, 16)}…`}</Typography.Text>,
    },
  ];
  return (
    <div style={{ padding: 16, maxHeight: 'calc(100vh - 420px)', overflow: 'auto' }}>
      <Table<API.SkillDraftScript>
        rowKey="relPath"
        columns={columns}
        dataSource={scripts}
        size="small"
        pagination={false}
        expandable={{
          expandedRowRender: (record) => <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 12 }}>{record.headPreview}</pre>,
        }}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 8, fontSize: 12 }}>
        {intl.formatMessage({
          id: 'pages.skill.draft.scripts.hint',
          defaultMessage: 'Line counts and hashes are computed over the bytes stored with the draft, not reported by the sandbox.',
        })}
      </Typography.Paragraph>
    </div>
  );
};

const ScansTab: React.FC<{ draft: API.SkillDraftDetail }> = ({ draft }) => {
  const intl = useIntl();
  const local = draft.localFindings || [];
  const upstream = draft.scanFindings || [];
  return (
    <div style={{ padding: 16, maxHeight: 'calc(100vh - 420px)', overflow: 'auto' }}>
      <Card
        size="small"
        title={
          <Space>
            <ThunderboltOutlined style={{ color: local.length > 0 ? '#fa541c' : '#52c41a' }} />
            {intl.formatMessage({ id: 'pages.skill.draft.scans.localTitle', defaultMessage: 'harnax content scan' })}
            <Tag color={local.length > 0 ? 'red' : 'green'}>{local.length}</Tag>
          </Space>
        }
        extra={
          <Tag color="blue">{intl.formatMessage({ id: 'pages.skill.draft.scans.decides', defaultMessage: 'decides enablement' })}</Tag>
        }
        style={{ marginBottom: 16 }}
      >
        {local.length === 0 ? (
          <Typography.Text type="secondary">
            {intl.formatMessage({ id: 'pages.skill.draft.scans.noLocal', defaultMessage: 'No hit. An approval stores the skill enabled.' })}
          </Typography.Text>
        ) : (
          <ul style={{ margin: 0, paddingLeft: 20 }}>
            {local.map((finding) => (
              <li key={finding} style={{ marginBottom: 4 }}>
                <Typography.Text>{finding}</Typography.Text>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card
        size="small"
        title={
          <Space>
            {intl.formatMessage({ id: 'pages.skill.draft.scans.upstreamTitle', defaultMessage: 'Sandbox report' })}
            {draft.scanVerdict && <Tag>{draft.scanVerdict}</Tag>}
            <Tag color={upstream.length > 0 ? 'orange' : 'default'}>{upstream.length}</Tag>
          </Space>
        }
      >
        {upstream.length === 0 ? (
          <Typography.Text type="secondary">
            {intl.formatMessage({ id: 'pages.skill.draft.scans.noUpstream', defaultMessage: 'The proposal arrived without findings.' })}
          </Typography.Text>
        ) : (
          <ul style={{ margin: 0, paddingLeft: 20 }}>
            {upstream.map((finding) => (
              <li key={finding} style={{ marginBottom: 4 }}>
                <Typography.Text>{finding}</Typography.Text>
              </li>
            ))}
          </ul>
        )}
        <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
          {intl.formatMessage({
            id: 'pages.skill.draft.scans.upstreamHint',
            defaultMessage: 'What the sandbox said when the draft was proposed. Display only — it never stores a skill disabled on its own.',
          })}
        </Typography.Paragraph>
      </Card>
    </div>
  );
};

const SourceTab: React.FC<{ draft: API.SkillDraftDetail }> = ({ draft }) => {
  const intl = useIntl();
  const history0 = draft.history || [];
  const columns: ColumnsType<API.SkillDraftHistoryItem> = [
    {
      title: intl.formatMessage({ id: 'pages.skill.draft.history.action', defaultMessage: 'Action' }),
      dataIndex: 'action',
      key: 'action',
      width: 120,
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.draft.history.actor', defaultMessage: 'Who' }),
      dataIndex: 'actor',
      key: 'actor',
      width: 140,
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.draft.history.at', defaultMessage: 'When' }),
      dataIndex: 'createTime',
      key: 'createTime',
      width: 170,
      render: (time?: string) => (time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.draft.history.detail', defaultMessage: 'Note' }),
      dataIndex: 'detail',
      key: 'detail',
      ellipsis: true,
      render: (detail?: string) => detail || '-',
    },
  ];
  return (
    <div style={{ padding: 16, maxHeight: 'calc(100vh - 420px)', overflow: 'auto' }}>
      <Descriptions column={2} size="small" style={{ marginBottom: 16 }}>
        <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.draft.source.session', defaultMessage: 'Session' })}>
          <Typography.Text code copyable={{ text: draft.sourceSessionId || '' }}>
            {draft.sourceSessionId || '-'}
          </Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label={intl.formatMessage({ id: 'pages.skill.draft.source.agent', defaultMessage: 'Agent' })}>
          {draft.agentId ? (
            <Typography.Text code>{draft.agentId}</Typography.Text>
          ) : (
            <Typography.Text type="secondary">
              {intl.formatMessage({
                id: 'pages.skill.draft.source.noAgent',
                defaultMessage: 'The run that proposed it recorded no agent',
              })}
            </Typography.Text>
          )}
        </Descriptions.Item>
      </Descriptions>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
        {intl.formatMessage({
          id: 'pages.skill.draft.source.hint',
          defaultMessage: 'The origin is what the runtime recorded when the draft was proposed. It is context, not a condition: decide on the content.',
        })}
      </Typography.Paragraph>
      <Table<API.SkillDraftHistoryItem>
        rowKey={(record) => `${record.action}-${record.createTime}`}
        columns={columns}
        dataSource={history0}
        size="small"
        pagination={false}
        locale={{ emptyText: <Empty description={intl.formatMessage({ id: 'pages.skill.draft.history.empty', defaultMessage: 'No trail recorded' })} /> }}
      />
    </div>
  );
};

export default SkillDraftDetail;
