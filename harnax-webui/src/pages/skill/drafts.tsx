import { useIntl, history } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import { Button, Empty, Input, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { FileProtectOutlined, QuestionCircleOutlined, SearchOutlined } from '@ant-design/icons';
import React, { useCallback, useEffect, useState } from 'react';
import dayjs from 'dayjs';
import { pageSkillDrafts } from '@/services/ant-design-pro/skillDraft';

const STATUS_COLORS: Record<string, string> = {
  PENDING: 'orange',
  APPROVED: 'green',
  REJECTED: 'red',
};

/**
 * Review queue for skills agents proposed (design section 6.2 / 7).
 *
 * Rows carry no body on purpose — the queue is a work list, and the columns that decide a row's place in it
 * are the two timestamps. `updateTime` later than `createTime` means the agent patched after the proposal,
 * which is exactly what the digest check at approval exists to catch, so it is marked here rather than left
 * for the reviewer to notice on the detail screen.
 */
const SkillDrafts: React.FC = () => {
  const intl = useIntl();
  const [status, setStatus] = useState<API.SkillDraftStatus>('PENDING');
  const [name, setName] = useState<string>('');
  const [rows, setRows] = useState<API.SkillDraftRow[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(20);
  const [loading, setLoading] = useState<boolean>(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const response = await pageSkillDrafts(
        { status, name: name || undefined, pageNum, pageSize },
        { skipErrorHandler: true },
      );
      // A refusal arrives on HTTP 200 with the code in the envelope, so umi's errorThrower never fires here.
      if (response.code !== 200) {
        throw new Error(response.message || '');
      }
      setRows(response.data?.records ?? []);
      setTotal(response.data?.total ?? 0);
    } catch (error: any) {
      setRows([]);
      setTotal(0);
      // An unreadable queue must say why: a silent empty table reads as "no agent proposed anything".
      message.error(
        error?.message ||
          error?.info?.errorMessage ||
          intl.formatMessage({ id: 'pages.skill.draft.loadFailed', defaultMessage: 'Failed to load the review queue' }),
      );
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, name, pageNum, pageSize]);

  useEffect(() => {
    load();
  }, [load]);

  const columns: ColumnsType<API.SkillDraftRow> = [
    {
      title: intl.formatMessage({ id: 'pages.skill.draft.name', defaultMessage: 'Proposed skill' }),
      dataIndex: 'name',
      key: 'name',
      width: 240,
      render: (rowName: string, record) => (
        <a
          onClick={() => history.push(`/optimization/skill-draft/detail/${record.id}`)}
          style={{ fontWeight: 500, cursor: 'pointer' }}
        >
          {rowName}
        </a>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.draft.proposedAt', defaultMessage: 'Proposed' }),
      dataIndex: 'createTime',
      key: 'createTime',
      width: 170,
      render: (time?: string) => (time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: (
        <Tooltip title={intl.formatMessage({ id: 'pages.skill.draft.patchedHint', defaultMessage: 'A later patch time means the agent edited the draft after proposing it, which is what the approval digest check refuses' })}>
          <span>
            {intl.formatMessage({ id: 'pages.skill.draft.patchedAt', defaultMessage: 'Last patch' })}{' '}
            <QuestionCircleOutlined style={{ color: 'var(--vip-text-tertiary)' }} />
          </span>
        </Tooltip>
      ),
      dataIndex: 'updateTime',
      key: 'updateTime',
      width: 210,
      render: (time: string | undefined, record) => {
        if (!time) return '-';
        const patched = !!record.createTime && dayjs(time).isAfter(dayjs(record.createTime));
        return (
          <Space size={6}>
            <span>{dayjs(time).format('YYYY-MM-DD HH:mm')}</span>
            {patched && (
              <Tag color="blue">{intl.formatMessage({ id: 'pages.skill.draft.patched', defaultMessage: 'patched' })}</Tag>
            )}
          </Space>
        );
      },
    },
    {
      title: (
        <Tooltip title={intl.formatMessage({ id: 'pages.skill.draft.upstreamHint', defaultMessage: 'What the sandbox reported with the proposal. Whether an approval lands enabled is decided by harnax rescan on the detail screen' })}>
          <span>
            {intl.formatMessage({ id: 'pages.skill.draft.upstreamFindings', defaultMessage: 'Reported hits' })}{' '}
            <QuestionCircleOutlined style={{ color: 'var(--vip-text-tertiary)' }} />
          </span>
        </Tooltip>
      ),
      dataIndex: 'upstreamFindingCount',
      key: 'upstreamFindingCount',
      width: 140,
      render: (count: number, record) => (
        <Space size={6}>
          <span>{count ?? 0}</span>
          {record.scanVerdict && <Tag>{record.scanVerdict}</Tag>}
        </Space>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.list.status', defaultMessage: 'Status' }),
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (rowStatus: string) => (
        <Tag color={STATUS_COLORS[rowStatus] || 'default'}>
          {intl.formatMessage({ id: `pages.skill.draft.status.${rowStatus}`, defaultMessage: rowStatus })}
        </Tag>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.draft.reviewer', defaultMessage: 'Decided by' }),
      dataIndex: 'reviewedBy',
      key: 'reviewedBy',
      width: 150,
      render: (reviewer: string | undefined, record) =>
        reviewer ? (
          <Space size={6}>
            <span>{reviewer}</span>
            {record.reviewedAt && <span style={{ color: 'var(--vip-text-tertiary)' }}>{dayjs(record.reviewedAt).format('MM-DD HH:mm')}</span>}
          </Space>
        ) : (
          '-'
        ),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.actions', defaultMessage: 'Actions' }),
      key: 'actions',
      fixed: 'right',
      width: 100,
      render: (_text, record) => (
        <Button type="link" size="small" onClick={() => history.push(`/optimization/skill-draft/detail/${record.id}`)}>
          {intl.formatMessage({ id: 'pages.skill.draft.review', defaultMessage: 'Review' })}
        </Button>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '18px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <FileProtectOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.skill.draft.title', defaultMessage: 'Skill drafts' })}
            {status === 'PENDING' && total > 0 && (
              <Tag color="orange" style={{ marginLeft: 10 }}>
                {total}
              </Tag>
            )}
          </span>
        ),
        extra: [
          <Button key="usage" onClick={() => history.push('/monitor/skill-usage')}>
            {intl.formatMessage({ id: 'pages.skill.usage.title', defaultMessage: 'Skill Usage' })}
          </Button>,
        ],
      }}
    >
      <div style={{ display: 'flex', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
        <Select
          value={status}
          onChange={(value) => {
            setStatus(value);
            setPageNum(1);
          }}
          style={{ width: 160 }}
          options={[
            { value: 'PENDING', label: intl.formatMessage({ id: 'pages.skill.draft.status.PENDING', defaultMessage: 'Pending' }) },
            { value: 'APPROVED', label: intl.formatMessage({ id: 'pages.skill.draft.status.APPROVED', defaultMessage: 'Approved' }) },
            { value: 'REJECTED', label: intl.formatMessage({ id: 'pages.skill.draft.status.REJECTED', defaultMessage: 'Rejected' }) },
          ]}
        />
        <Input.Search
          allowClear
          placeholder={intl.formatMessage({ id: 'pages.skill.draft.searchPlaceholder', defaultMessage: 'Search by name' })}
          enterButton={<SearchOutlined />}
          style={{ width: 260 }}
          onSearch={(value) => {
            setName(value.trim());
            setPageNum(1);
          }}
        />
      </div>

      <Table<API.SkillDraftRow>
        className="styled-pro-table"
        rowKey="id"
        columns={columns}
        dataSource={rows}
        loading={loading}
        size="small"
        scroll={{ x: 'max-content' }}
        locale={{
          emptyText: (
            <Empty
              description={
                status === 'PENDING'
                  ? intl.formatMessage({ id: 'pages.skill.draft.emptyPending', defaultMessage: 'No draft is waiting for a decision' })
                  : intl.formatMessage({ id: 'pages.skill.draft.emptyDecided', defaultMessage: 'No decided draft' })
              }
            />
          ),
        }}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          onChange: (page, size) => {
            setPageNum(page);
            setPageSize(size);
          },
          showTotal: (t) =>
            `${intl.formatMessage({ id: 'pages.common.total', defaultMessage: 'Total' })} ${t} ${intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}`,
        }}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 8, fontSize: 12 }}>
        {intl.formatMessage({
          id: 'pages.skill.draft.queueHint',
          defaultMessage: 'A draft stays here until a human decides. Nothing in this table reaches an agent, and approving one does not enable it if the content scan has hits.',
        })}
      </Typography.Paragraph>
    </PageContainer>
  );
};

export default SkillDrafts;
