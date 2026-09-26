import { PageContainer } from '@ant-design/pro-components';
import { Empty, message, Modal, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useRef, useState } from 'react';
import { history, useIntl } from '@umijs/max';
import { CodeOutlined } from '@ant-design/icons';
import SearchFilterBar, { SearchInput } from '@/components/SearchFilterBar';
import { getCliPage, getCliRelatedAgents, toggleCliStatus } from '@/services/ant-design-pro/cli';
import StatusSwitch from '@/components/StatusSwitch';
import EnvParamsPopover from '@/components/EnvParamsPopover';
import AgentRefreshModal from '@/pages/agent/components/AgentRefreshModal';
import CliDetailDrawer from './components/CliDetailDrawer';

const { Text } = Typography;

/**
 * The CLI plugin packages admin registered at startup. Nothing here creates or edits a row: a CLI is
 * published by dropping its package into admin's package directory (design D2), so the only operator
 * action the page offers is the kill switch (design D9).
 */
const CliManagement: React.FC = () => {
  const intl = useIntl();

  const [loading, setLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.CliItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const [keyword, setKeyword] = useState<string>('');
  const [refreshTarget, setRefreshTarget] = useState<API.CliItem | undefined>();
  const [detailTarget, setDetailTarget] = useState<API.CliItem | undefined>();

  const searchTimerRef = useRef<NodeJS.Timeout | null>(null);
  const filtersRef = useRef({ keyword: '' });
  const [messageApi, contextHolder] = message.useMessage();

  const loadData = async (page = 1, size = pageSize) => {
    const { keyword: kw } = filtersRef.current;
    setLoading(true);
    try {
      const res = await getCliPage({
        pageNum: page,
        pageSize: size,
        name: kw || undefined,
      });
      setData(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch {
      messageApi.error(intl.formatMessage({ id: 'pages.message.loadFailed', defaultMessage: 'Failed to load data' }));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData(pageNum, pageSize);
  }, [pageNum, pageSize]);

  useEffect(() => {
    return () => {
      if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    };
  }, []);

  const handleKeywordChange = (value: string) => {
    setKeyword(value);
    filtersRef.current.keyword = value;
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    searchTimerRef.current = setTimeout(() => {
      setPageNum(1);
      loadData(1);
    }, 500);
  };

  const handleReset = () => {
    setKeyword('');
    filtersRef.current = { keyword: '' };
    setPageNum(1);
    loadData(1);
  };

  const applyToggle = async (record: API.CliItem, newStatus: number) => {
    try {
      const response = await toggleCliStatus(record.id!, newStatus);
      if (response.code === 200) {
        messageApi.success(
          newStatus === 1
            ? intl.formatMessage({ id: 'pages.message.enableSuccess', defaultMessage: 'Enabled successfully' })
            : intl.formatMessage({ id: 'pages.message.disableSuccess', defaultMessage: 'Disabled successfully' }),
        );
        loadData(pageNum, pageSize);
      } else {
        Modal.error({
          title: intl.formatMessage({ id: 'pages.cli.toggleBlocked', defaultMessage: 'Cannot change CLI status' }),
          content: response.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed' }),
          okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
        });
        loadData(pageNum, pageSize);
      }
    } catch (error: any) {
      Modal.error({
        title: intl.formatMessage({ id: 'pages.cli.toggleBlocked', defaultMessage: 'Cannot change CLI status' }),
        content: error?.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed' }),
        okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
      });
      loadData(pageNum, pageSize);
    }
  };

  /**
   * Agents still binding this CLI, or `failed` when admin could not be asked. A failed read is never
   * reported as an empty one: "no agents" is the answer that lets a toggle run with no confirmation.
   */
  const relatedAgents = async (id: number): Promise<{ agents: API.CliRelatedAgent[] } | { failed: true }> => {
    try {
      // skipErrorHandler: the caller shows the one error surface this read needs, and it has to be able
      // to tell "failed" apart from "empty" — the global handler swallows both into undefined.
      const res = await getCliRelatedAgents(id, { skipErrorHandler: true });
      return res.code === 200 ? { agents: res.data || [] } : { failed: true };
    } catch {
      return { failed: true };
    }
  };

  const handleToggle = async (record: API.CliItem, newStatus: number) => {
    const result = await relatedAgents(record.id!);
    if ('failed' in result) {
      // Blocked, not proceeded: nothing was sent to admin, and StatusSwitch reads its checked state off
      // the row data, so the switch stays where it was.
      Modal.error({
        title: intl.formatMessage({ id: 'pages.cli.toggleBlocked', defaultMessage: 'Cannot change CLI status' }),
        content: intl.formatMessage({
          id: 'pages.message.loadFailedRetry',
          defaultMessage: 'Failed to load the related list, please retry',
        }),
        okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
      });
      return;
    }
    const agents = result.agents;
    if (agents.length === 0) {
      await applyToggle(record, newStatus);
      return;
    }
    // Bound agents are the reason to offer a session refresh either way; only taking a CLI out of
    // circulation is worth a confirmation, because that is the direction that removes a capability.
    const applyAndOfferRefresh = async () => {
      await applyToggle(record, newStatus);
      setRefreshTarget(record);
    };
    if (newStatus === 1) {
      await applyAndOfferRefresh();
      return;
    }
    // Disabling is deliberately not refused while agents still bind the CLI (design D9), so the
    // blast radius has to be shown here rather than returned as an error by admin.
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.cli.disableConfirmTitle', defaultMessage: 'Disable this CLI' }),
      content: intl.formatMessage(
        {
          id: 'pages.cli.disableConfirmContent',
          defaultMessage:
            '{count} agent(s) bind this CLI ({names}). Their sandboxes lose the command and its skill stops loading.',
        },
        { count: agents.length, names: agents.slice(0, 5).map((a) => a.agentName).join('、') },
      ),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
      okButtonProps: { danger: true },
      onOk: applyAndOfferRefresh,
    });
  };

  const columns: ColumnsType<API.CliItem> = [
    {
      title: intl.formatMessage({ id: 'pages.cli.name', defaultMessage: 'Name' }),
      dataIndex: 'name',
      key: 'name',
      width: 200,
      ellipsis: true,
      render: (text: string, record: API.CliItem) => (
        <Tooltip
          title={intl.formatMessage({
            id: 'pages.cli.detailOpen',
            defaultMessage: 'Click to see what this package declares',
          })}
        >
          <Text strong style={{ fontFamily: 'monospace', cursor: 'pointer' }} onClick={() => setDetailTarget(record)}>
            <CodeOutlined style={{ marginRight: 6, color: 'var(--vip-primary)' }} />
            {text}
          </Text>
        </Tooltip>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' }),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (text: string) => text || <Text type="secondary">-</Text>,
    },
    {
      title: intl.formatMessage({ id: 'pages.cli.version', defaultMessage: 'Version' }),
      dataIndex: 'version',
      key: 'version',
      width: 100,
      render: (text: string) => text || <Text type="secondary">-</Text>,
    },
    {
      title: intl.formatMessage({ id: 'pages.cli.skill', defaultMessage: 'Shipped Skill' }),
      dataIndex: 'skill',
      key: 'skill',
      width: 180,
      render: (skill: API.CliItem['skill']) => {
        const { skillName, skillDescription, skillId } = skill || {};
        if (!skillName) return <Text type="secondary">-</Text>;
        const tooltip = [
          skillDescription,
          intl.formatMessage({ id: 'pages.cli.skillOpen', defaultMessage: 'Click to open the skill' }),
        ]
          .filter(Boolean)
          .join(' — ');
        return (
          <Tooltip title={tooltip}>
            <Tag
              color="blue"
              style={{ cursor: 'pointer' }}
              onClick={() => history.push(`/context/skill/detail/${skillId}`)}
            >
              {skillName}
            </Tag>
          </Tooltip>
        );
      },
    },
    {
      title: intl.formatMessage({ id: 'pages.cli.envParams', defaultMessage: 'Env Params' }),
      dataIndex: 'envParams',
      key: 'envParams',
      width: 120,
      align: 'center',
      render: (entries: API.CliItem['envParams']) => <EnvParamsPopover entries={entries} />,
    },
    {
      title: intl.formatMessage({ id: 'pages.cli.healthCheck', defaultMessage: 'Health Check' }),
      dataIndex: 'checkCommand',
      key: 'checkCommand',
      width: 200,
      ellipsis: true,
      render: (text: string) => (text ? <Text code>{text}</Text> : <Text type="secondary">-</Text>),
    },
    {
      title: intl.formatMessage({ id: 'pages.cli.packageDigest', defaultMessage: 'Package Digest' }),
      dataIndex: 'packageDigest',
      key: 'packageDigest',
      width: 140,
      render: (digest: string) =>
        digest ? (
          <Tooltip title={digest}>
            <Text code>{digest.substring(0, 12)}</Text>
          </Tooltip>
        ) : (
          <Text type="secondary">-</Text>
        ),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' }),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      align: 'center',
      render: (val: number, record) => (
        <StatusSwitch status={val ?? 1} onChange={(newStatus) => handleToggle(record, newStatus)} />
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <CodeOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.cli.title', defaultMessage: 'CLI Tools' })}
          </span>
        ),
      }}
    >
      {contextHolder}

      <Space direction="vertical" size={16} style={{ display: 'flex' }}>
        <SearchFilterBar
          onSearch={() => {}}
          onReset={handleReset}
          showSearchButton={false}
          searchText={intl.formatMessage({ id: 'pages.common.search', defaultMessage: 'Search' })}
          resetText={intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
        >
          <SearchInput
            value={keyword}
            onChange={handleKeywordChange}
            placeholder={intl.formatMessage({ id: 'pages.cli.searchPlaceholder', defaultMessage: 'Search CLI name' })}
            width="auto"
          />
        </SearchFilterBar>

        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          loading={loading}
          scroll={{ x: 'max-content' }}
          pagination={{
            current: pageNum,
            pageSize: pageSize,
            total: total,
            showSizeChanger: true,
            showQuickJumper: true,
            pageSizeOptions: ['10', '20', '50'],
            onChange: (page, size) => {
              setPageNum(page);
              setPageSize(size);
              loadData(page, size);
            },
          }}
          locale={{
            emptyText: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={intl.formatMessage({ id: 'pages.cli.noData', defaultMessage: 'No CLI tools' })}
              />
            ),
          }}
        />
      </Space>

      <AgentRefreshModal
        visible={!!refreshTarget}
        source="cli"
        agentId={refreshTarget?.id}
        agentName={refreshTarget?.name}
        onClose={() => setRefreshTarget(undefined)}
      />

      <CliDetailDrawer
        cliId={detailTarget?.id}
        cliName={detailTarget?.name}
        onClose={() => setDetailTarget(undefined)}
      />
    </PageContainer>
  );
};

export default CliManagement;
