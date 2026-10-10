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
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { AuditOutlined, BookOutlined, StopOutlined } from '@ant-design/icons';
import React, { useCallback, useEffect, useState } from 'react';
import dayjs from 'dayjs';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import BackButton from '@/components/BackButton';
import {
  approveMemoryDraft,
  getMemoryDraft,
  rejectMemoryDraft,
} from '@/services/ant-design-pro/memoryDraft';

const STATUS_COLORS: Record<string, string> = {
  PENDING: 'orange',
  APPROVED: 'green',
  REJECTED: 'red',
};

/** memory_draft.reject_reason is varchar(512); a longer reason is refused by the server, not truncated. */
const MAX_REJECT_REASON = 512;

/**
 * One memory merge, as its owner reads it, plus the two decisions (memory design §11.4).
 *
 * The decision is a comparison, so both texts are here: what every later conversation of this agent is
 * already told, and what it would be told instead. The approval sends back the digest this screen was built
 * from rather than the text — a conversation rewrites its own candidate every merge window while this row is
 * open, so "approve what I am looking at" only means something if the server can check it. Refusals come
 * back on a 200 envelope as an `outcome` carrying what the next attempt needs, so the screen branches on
 * that instead of re-fetching to find out what it just refused.
 */
const MemoryDraftDetail: React.FC = () => {
  const intl = useIntl();
  const params = useParams<{ id: string }>();
  const draftId = Number(params.id);
  const [draft, setDraft] = useState<API.MemoryDraftDetail | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [acting, setActing] = useState<boolean>(false);
  const [rejectOpen, setRejectOpen] = useState<boolean>(false);
  const [rejectForm] = Form.useForm<{ reason: string }>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const response = await getMemoryDraft(draftId, { skipErrorHandler: true });
      // A refusal arrives on HTTP 200 with the code in the envelope, so umi's errorThrower never fires here;
      // without this check another account's candidate would render as an empty screen.
      if (response.code !== 200) {
        throw new Error(response.message || '');
      }
      setDraft(response.data ?? null);
    } catch (error: any) {
      setDraft(null);
      message.error(
        error?.message ||
          error?.info?.errorMessage ||
          intl.formatMessage({
            id: 'pages.memory.draft.detail.loadFailed',
            defaultMessage: 'Failed to load this memory merge',
          }),
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

  const showApproved = (decision: API.MemoryDraftDecision) => {
    const kept = decision.keptSources ?? 0;
    const dailyApplied = decision.dailyTargetsApplied ?? 0;
    Modal.success({
      title: intl.formatMessage({
        id: 'pages.memory.draft.approved.title',
        defaultMessage: 'Merged into the agent memory',
      }),
      content: (
        <Space direction="vertical" size={8} style={{ display: 'flex' }}>
          <span>
            {intl.formatMessage(
              {
                id: 'pages.memory.draft.approved.body',
                defaultMessage:
                  'The long-term layer is now version {version}, and every conversation this agent starts from here on is told that text. {cleared} conversation memory file(s) were cleared.{kept}',
              },
              {
                version: decision.longTermVersion ?? '-',
                cleared: decision.clearedSources ?? 0,
                kept: kept
                  ? intl.formatMessage(
                      {
                        id: 'pages.memory.draft.approved.kept',
                        defaultMessage:
                          ' {count} were left for the next candidate because their bytes moved after the merge read them.',
                      },
                      { count: kept },
                    )
                  : '',
              },
            )}
          </span>
          {/* One button now decides the day files too, so the receipt has to say how many were written. */}
          {dailyApplied > 0 ? (
            <span>
              {intl.formatMessage(
                {
                  id: 'pages.memory.draft.approved.daily',
                  defaultMessage: 'Memory for {count} date(s) was folded in alongside it.',
                },
                { count: dailyApplied },
              )}
            </span>
          ) : null}
        </Space>
      ),
      okText: intl.formatMessage({
        id: 'pages.memory.draft.approved.openMemory',
        defaultMessage: 'Open memory page',
      }),
      onOk: () => history.push('/optimization/memory'),
    });
  };

  const showAlreadyReviewed = (decision: API.MemoryDraftDecision) => {
    Modal.info({
      title: intl.formatMessage({
        id: 'pages.memory.draft.refused.reviewedTitle',
        defaultMessage: 'Already decided',
      }),
      content: intl.formatMessage(
        {
          id: 'pages.memory.draft.refused.reviewed',
          defaultMessage: '{reviewer} closed this merge on {at}. {reason}',
        },
        {
          reviewer: decision.reviewedBy || '-',
          at: decision.reviewedAt ? dayjs(decision.reviewedAt).format('YYYY-MM-DD HH:mm') : '-',
          reason: decision.rejectReason
            ? `${intl.formatMessage({
                id: 'pages.memory.draft.rejectReason',
                defaultMessage: 'Reason',
              })}: ${decision.rejectReason}`
            : '',
        },
      ),
    });
  };

  const handleDecision = (decision: API.MemoryDraftDecision) => {
    switch (decision.outcome) {
      case 'APPROVED':
        showApproved(decision);
        break;
      case 'REJECTED':
        message.success(
          intl.formatMessage({
            id: 'pages.memory.draft.reject.done',
            defaultMessage: 'Merge rejected, both memory layers are untouched',
          }),
        );
        break;
      case 'DRAFT_CHANGED':
        // The conversation merged again under the owner; forcing a re-read beats a silent retry on the old digest.
        Modal.warning({
          title: intl.formatMessage({
            id: 'pages.memory.draft.refused.changedTitle',
            defaultMessage: 'The merge has been rewritten',
          }),
          content: (
            <Space direction="vertical" size={8} style={{ display: 'flex' }}>
              <span>
                {intl.formatMessage({
                  id: 'pages.memory.draft.refused.changed',
                  defaultMessage:
                    'This conversation merged its memory again after you opened the candidate, so nothing was written. Read the new text, then approve it again.',
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
      case 'STALE_BASE':
        // The agent's layer moved since the merge read it; applying this text would overwrite a merge nobody read.
        Modal.error({
          title: intl.formatMessage({
            id: 'pages.memory.draft.refused.staleTitle',
            defaultMessage: 'This merge is out of date',
          }),
          content: (
            <Space direction="vertical" size={8} style={{ display: 'flex' }}>
              <span>
                {intl.formatMessage(
                  {
                    id: 'pages.memory.draft.refused.stale',
                    defaultMessage:
                      'The long-term layer is at version {version}, but this candidate was merged against an older one, so nothing was written. The conversation will propose against the current layer next time its merge window opens.',
                  },
                  { version: decision.currentBaseVersion ?? '-' },
                )}
              </span>
              {/* The merge now spans 1 + K objects, so the refusal names the one that moved rather than assuming the layer did. */}
              {decision.staleTarget ? (
                <span>
                  {intl.formatMessage(
                    {
                      id: 'pages.memory.draft.refused.staleTarget',
                      defaultMessage:
                        'The object that moved since the merge read it is: {path}',
                    },
                    { path: decision.staleTarget },
                  )}
                </span>
              ) : null}
            </Space>
          ),
        });
        break;
      case 'ALREADY_REVIEWED':
        showAlreadyReviewed(decision);
        break;
      default:
        message.error(
          decision.reason ||
            intl.formatMessage({
              id: 'pages.memory.draft.decisionFailed',
              defaultMessage: 'The decision was refused',
            }),
        );
    }
    load();
  };

  const sendApprove = async (body: API.MemoryDraftApproveRequest) => {
    setActing(true);
    try {
      const response = await approveMemoryDraft(draftId, body, { skipErrorHandler: true });
      if (response.data) {
        handleDecision(response.data);
      } else {
        message.error(
          response.message ||
            intl.formatMessage({
              id: 'pages.memory.draft.decisionFailed',
              defaultMessage: 'The decision was refused',
            }),
        );
      }
    } catch (error: any) {
      message.error(
        error?.message ||
          error?.info?.errorMessage ||
          intl.formatMessage({
            id: 'pages.memory.draft.decisionFailed',
            defaultMessage: 'The decision was refused',
          }),
      );
    } finally {
      setActing(false);
    }
  };

  const approve = () => {
    if (!draft?.contentDigest) {
      message.error(
        intl.formatMessage({
          id: 'pages.memory.draft.digestMissing',
          defaultMessage: 'This merge carries no content digest; reload the page before approving',
        }),
      );
      load();
      return;
    }
    const dailyTargets = draft.targets || [];
    Modal.confirm({
      title: intl.formatMessage({
        id: 'pages.memory.draft.approve.confirmTitle',
        defaultMessage: 'Merge this into the agent memory?',
      }),
      content: (
        <Space direction="vertical" size={8} style={{ display: 'flex' }}>
          <span>
            {intl.formatMessage(
              {
                id: 'pages.memory.draft.approve.confirmBody',
                defaultMessage:
                  'The text below replaces the whole long-term layer of {agent}, and every conversation that agent starts afterwards is told it. The conversation memory files this merge read are cleared once the write lands.',
              },
              { agent: draft.agentName },
            )}
          </span>
          {/* Still one button for the whole decision, but the reviewer is told what else it writes. */}
          {dailyTargets.length > 0 ? (
            <span>
              {intl.formatMessage(
                {
                  id: 'pages.memory.draft.approve.confirmDaily',
                  defaultMessage:
                    'This same approval also writes {count} day file(s) of memory. If any one of them moved since the merge read it, nothing is written at all.',
                },
                { count: dailyTargets.length },
              )}
            </span>
          ) : null}
        </Space>
      ),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'OK' }),
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
      onOk: () => sendApprove({ expectedDigest: draft.contentDigest }),
    });
  };

  const reject = async () => {
    const values = await rejectForm.validateFields();
    setActing(true);
    try {
      const response = await rejectMemoryDraft(
        draftId,
        { reason: values.reason.trim() },
        { skipErrorHandler: true },
      );
      if (response.data) {
        setRejectOpen(false);
        rejectForm.resetFields();
        handleDecision(response.data);
      } else {
        message.error(
          response.message ||
            intl.formatMessage({
              id: 'pages.memory.draft.decisionFailed',
              defaultMessage: 'The decision was refused',
            }),
        );
      }
    } catch (error: any) {
      message.error(
        error?.message ||
          error?.info?.errorMessage ||
          intl.formatMessage({
            id: 'pages.memory.draft.decisionFailed',
            defaultMessage: 'The decision was refused',
          }),
      );
    } finally {
      setActing(false);
    }
  };

  const tabItems = () => {
    if (!draft) return [];
    const dailyTargets = draft.targets || [];
    // The count rides the label so a reviewer sees, before clicking, that one approval covers 1 + K objects.
    const dailyLabel = dailyTargets.length
      ? intl.formatMessage(
          {
            id: 'pages.memory.draft.tab.daily.count',
            defaultMessage: 'Daily merges ({count})',
          },
          { count: dailyTargets.length },
        )
      : intl.formatMessage({
          id: 'pages.memory.draft.tab.daily',
          defaultMessage: 'Daily merges',
        });
    const items: any[] = [
      {
        key: 'merged',
        label: intl.formatMessage({
          id: 'pages.memory.draft.tab.merged',
          defaultMessage: 'What the agent would be told',
        }),
        children: (
          <div style={{ padding: 16, maxHeight: 'calc(100vh - 420px)', overflow: 'auto' }}>
            <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
              {intl.formatMessage(
                {
                  id: 'pages.memory.draft.mergedHint',
                  defaultMessage:
                    'The complete MEMORY.md the merge produced — {count} characters. Approving writes this text as a whole, so an item the merge dropped leaves the agent.',
                },
                { count: draft.mergedMd.length },
              )}
            </Typography.Paragraph>
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{draft.mergedMd || ''}</ReactMarkdown>
          </div>
        ),
      },
      {
        key: 'base',
        label: intl.formatMessage({
          id: 'pages.memory.draft.tab.base',
          defaultMessage: 'What it is told now',
        }),
        children:
          (draft.baseMd || '').trim() ? (
            <div style={{ padding: 16, maxHeight: 'calc(100vh - 420px)', overflow: 'auto' }}>
              <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                {intl.formatMessage(
                  {
                    id: 'pages.memory.draft.baseHint',
                    defaultMessage:
                      'The owner text this merge started from, at version {version} — {count} characters.',
                  },
                  { version: draft.baseVersion, count: (draft.baseMd || '').length },
                )}
              </Typography.Paragraph>
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{draft.baseMd || ''}</ReactMarkdown>
            </div>
          ) : (
            <Empty
              description={intl.formatMessage({
                id: 'pages.memory.draft.base.empty',
                defaultMessage:
                  'This agent has no long-term memory yet, so this merge would be the first text it is told',
              })}
              style={{ paddingTop: 40 }}
            />
          ),
      },
      {
        key: 'daily',
        label: dailyLabel,
        children: <DailyTab targets={dailyTargets} />,
      },
      {
        key: 'sources',
        label: intl.formatMessage({
          id: 'pages.memory.draft.tab.sources',
          defaultMessage: 'Conversation files',
        }),
        children: <SourcesTab sources={draft.sources || []} />,
      },
    ];
    return items;
  };

  return (
    <PageContainer
      header={{
        title: (
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <BackButton onClick={() => history.push('/optimization/memory/drafts')} />
            <div style={{ width: 1, height: 24, background: 'var(--vip-border)' }} />
            <span style={{ fontSize: 18, fontWeight: 600, color: 'var(--vip-text-primary)', margin: 0 }}>
              <BookOutlined style={{ marginRight: 10, color: '#531dab' }} />
              {intl.formatMessage({
                id: 'pages.memory.draft.detail.title',
                defaultMessage: 'Memory merge for review',
              })}
              {draft && (
                <Tag color={STATUS_COLORS[draft.status] || 'default'} style={{ marginLeft: 10 }}>
                  {intl.formatMessage({
                    id: `pages.memory.draft.status.${draft.status}`,
                    defaultMessage: draft.status,
                  })}
                </Tag>
              )}
            </span>
          </div>
        ),
        extra:
          draft?.status === 'PENDING'
            ? [
                <Button key="reject" danger icon={<StopOutlined />} disabled={acting} onClick={() => setRejectOpen(true)}>
                  {intl.formatMessage({ id: 'pages.memory.draft.reject', defaultMessage: 'Reject' })}
                </Button>,
                <Button key="approve" type="primary" icon={<AuditOutlined />} loading={acting} onClick={approve}>
                  {intl.formatMessage({ id: 'pages.memory.draft.approve', defaultMessage: 'Approve' })}
                </Button>,
              ]
            : undefined,
      }}
    >
      <Spin spinning={loading}>
        {!draft && !loading ? (
          <Empty
            description={intl.formatMessage({
              id: 'pages.memory.draft.detail.notFound',
              defaultMessage: 'This merge is no longer in your queue',
            })}
          />
        ) : (
          draft && (
            <>
              <Card
                style={{ borderRadius: 16, border: '1px solid var(--vip-border)', marginBottom: 16 }}
                styles={{ body: { padding: 16 } }}
              >
                <Descriptions column={2} size="small">
                  <Descriptions.Item
                    label={intl.formatMessage({
                      id: 'pages.memory.draft.agent',
                      defaultMessage: 'Agent memory',
                    })}
                  >
                    <Typography.Text strong>{draft.agentName}</Typography.Text>
                  </Descriptions.Item>
                  <Descriptions.Item
                    label={intl.formatMessage({
                      id: 'pages.memory.draft.session',
                      defaultMessage: 'From session',
                    })}
                  >
                    <Typography.Text code copyable={{ text: draft.sessionId }}>
                      {draft.sessionId}
                    </Typography.Text>
                  </Descriptions.Item>
                  <Descriptions.Item
                    label={intl.formatMessage({
                      id: 'pages.memory.draft.proposedAt',
                      defaultMessage: 'First proposed',
                    })}
                  >
                    {draft.createTime ? dayjs(draft.createTime).format('YYYY-MM-DD HH:mm') : '-'}
                  </Descriptions.Item>
                  <Descriptions.Item
                    label={intl.formatMessage({
                      id: 'pages.memory.draft.lastMerge',
                      defaultMessage: 'Last merge',
                    })}
                  >
                    {draft.updateTime ? dayjs(draft.updateTime).format('YYYY-MM-DD HH:mm') : '-'}
                  </Descriptions.Item>
                </Descriptions>
                {draft.status === 'PENDING' ? (
                  <Alert
                    type="info"
                    showIcon
                    message={intl.formatMessage(
                      {
                        id: 'pages.memory.draft.digestHint',
                        defaultMessage:
                          'Approving sends this digest ({digest}); if the conversation merges again first, the approval is refused and this page reloads to the new text.',
                      },
                      { digest: `${draft.contentDigest.slice(0, 12)}…` },
                    )}
                  />
                ) : (
                  <Alert
                    type={draft.status === 'APPROVED' ? 'success' : 'warning'}
                    showIcon
                    message={intl.formatMessage(
                      {
                        id: 'pages.memory.draft.decidedHint',
                        defaultMessage:
                          '{reviewer} decided this on {at}. The long-term layer is read-only from here — the only way it changes is another merge you approve.',
                      },
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
                <Tabs defaultActiveKey="merged" items={tabItems()} size="large" style={{ padding: '0 24px' }} />
              </Card>
            </>
          )
        )}
      </Spin>

      <Modal
        open={rejectOpen}
        title={intl.formatMessage({
          id: 'pages.memory.draft.reject.title',
          defaultMessage: 'Reject this merge',
        })}
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
            label={intl.formatMessage({
              id: 'pages.memory.draft.rejectReason',
              defaultMessage: 'Reason',
            })}
            rules={[
              {
                required: true,
                message: intl.formatMessage({
                  id: 'pages.memory.draft.reject.reasonRequired',
                  defaultMessage: 'A rejection needs a reason',
                }),
              },
              {
                validator: (_rule, value) =>
                  (value || '').trim().length > MAX_REJECT_REASON
                    ? Promise.reject(
                        new Error(
                          intl.formatMessage(
                            {
                              id: 'pages.memory.draft.reject.tooLong',
                              defaultMessage: 'Keep the reason under {count} characters',
                            },
                            { count: MAX_REJECT_REASON },
                          ),
                        ),
                      )
                    : Promise.resolve(),
              },
            ]}
            extra={intl.formatMessage({
              id: 'pages.memory.draft.reject.reasonHint',
              defaultMessage:
                'Rejecting closes this candidate and leaves both layers alone, so the same material comes back next merge window. This reason is the only thing that carries over.',
            })}
          >
            <Input.TextArea rows={4} maxLength={MAX_REJECT_REASON + 1} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

/**
 * The conversation memory this candidate was made from, with the exact bytes the merge read.
 *
 * Listed rather than hidden because an approval clears these objects: the owner is agreeing to move them out
 * of that conversation's own layer, and a file whose bytes have moved since is kept instead.
 */
const SourcesTab: React.FC<{ sources: API.MemoryDraftSource[] }> = ({ sources }) => {
  const intl = useIntl();
  if (sources.length === 0) {
    return (
      <Empty
        description={intl.formatMessage({
          id: 'pages.memory.draft.sources.empty',
          defaultMessage: 'The merge recorded no conversation memory files',
        })}
        style={{ paddingTop: 40 }}
      />
    );
  }
  const columns: ColumnsType<API.MemoryDraftSource> = [
    {
      title: intl.formatMessage({ id: 'pages.memory.draft.sources.path', defaultMessage: 'Path' }),
      dataIndex: 'path',
      key: 'path',
      render: (path?: string) => <Typography.Text code>{path}</Typography.Text>,
    },
    {
      title: intl.formatMessage({ id: 'pages.memory.draft.sources.chars', defaultMessage: 'Characters' }),
      key: 'chars',
      width: 120,
      align: 'right',
      render: (_: unknown, record) => (record.content || '').length,
    },
  ];
  return (
    <div style={{ padding: 16, maxHeight: 'calc(100vh - 420px)', overflow: 'auto' }}>
      <Table<API.MemoryDraftSource>
        rowKey={(record) => record.path || ''}
        columns={columns}
        dataSource={sources}
        size="small"
        pagination={false}
        expandable={{
          expandedRowRender: (record) => (
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 12 }}>{record.content || ''}</pre>
          ),
        }}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 8, fontSize: 12 }}>
        {intl.formatMessage({
          id: 'pages.memory.draft.sources.hint',
          defaultMessage:
            'The text each file held when the merge read it. An approval clears a file only while it still holds those bytes.',
        })}
      </Typography.Paragraph>
    </div>
  );
};

/**
 * The day files this same merge also writes, besides the conclusion layer.
 *
 * These are shown rather than folded away because the two cases look identical in the conclusion layer: a day
 * whose memory was cut back to a quarter of itself, and a day that came through the merge intact, are told
 * apart only by the day's own text the merge read. Expanding a row is evidence — it changes no state, and the
 * decision stays the one approve button at the top of the page.
 */
const DailyTab: React.FC<{ targets: API.MemoryDraftTarget[] }> = ({ targets }) => {
  const intl = useIntl();
  if (targets.length === 0) {
    return (
      <Empty
        description={intl.formatMessage({
          id: 'pages.memory.draft.daily.empty',
          defaultMessage: 'This merge writes the conclusion layer only, no day file is touched',
        })}
        style={{ paddingTop: 40 }}
      />
    );
  }
  const versionText = (target: API.MemoryDraftTarget) => {
    if (!target.expectedVersion) {
      // 0 is how the server says the object did not exist when the merge read it, so approving creates that day.
      return (
        <Tag color="blue">
          {intl.formatMessage({
            id: 'pages.memory.draft.daily.newDay',
            defaultMessage: 'New for this day',
          })}
        </Tag>
      );
    }
    return `v${target.expectedVersion}`;
  };
  const columns: ColumnsType<API.MemoryDraftTarget> = [
    {
      title: intl.formatMessage({ id: 'pages.memory.draft.daily.path', defaultMessage: 'Day file' }),
      dataIndex: 'path',
      key: 'path',
      render: (path?: string) => <Typography.Text code>{path}</Typography.Text>,
    },
    {
      title: intl.formatMessage({
        id: 'pages.memory.draft.daily.expectedVersion',
        defaultMessage: 'Merged against',
      }),
      key: 'expectedVersion',
      width: 160,
      render: (_: unknown, record) => versionText(record),
    },
    {
      title: intl.formatMessage({ id: 'pages.memory.draft.daily.chars', defaultMessage: 'New length' }),
      key: 'chars',
      width: 120,
      align: 'right',
      render: (_: unknown, record) => (record.mergedText || '').length,
    },
  ];
  return (
    <div style={{ padding: 16, maxHeight: 'calc(100vh - 420px)', overflow: 'auto' }}>
      <Table<API.MemoryDraftTarget>
        rowKey={(record) => record.path || ''}
        columns={columns}
        dataSource={targets}
        size="small"
        pagination={false}
        expandable={{
          expandedRowRender: (record) => (
            <Space direction="vertical" size={12} style={{ display: 'flex' }}>
              <div>
                <Typography.Paragraph strong style={{ marginBottom: 4, fontSize: 12 }}>
                  {intl.formatMessage({
                    id: 'pages.memory.draft.daily.baseHeading',
                    defaultMessage: 'This day as it stands',
                  })}
                </Typography.Paragraph>
                {(record.baseText || '').trim() ? (
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{record.baseText || ''}</ReactMarkdown>
                ) : (
                  <Typography.Paragraph type="secondary" style={{ marginBottom: 0, fontSize: 12 }}>
                    {intl.formatMessage({
                      id: 'pages.memory.draft.daily.baseEmpty',
                      defaultMessage:
                        'This day has no memory yet, so this merge would be the first note it carries',
                    })}
                  </Typography.Paragraph>
                )}
              </div>
              <div>
                <Typography.Paragraph strong style={{ marginBottom: 4, fontSize: 12 }}>
                  {intl.formatMessage({
                    id: 'pages.memory.draft.daily.mergedHeading',
                    defaultMessage: 'After the merge',
                  })}
                </Typography.Paragraph>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{record.mergedText || ''}</ReactMarkdown>
              </div>
            </Space>
          ),
        }}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 8, fontSize: 12 }}>
        {intl.formatMessage({
          id: 'pages.memory.draft.daily.hint',
          defaultMessage:
            'Approving writes the conclusion layer and every day file above as one decision. If the stored version of any one of them no longer matches, the whole merge is refused and none of them is written.',
        })}
      </Typography.Paragraph>
    </div>
  );
};

export default MemoryDraftDetail;
