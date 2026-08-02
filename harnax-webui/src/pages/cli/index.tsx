import { PageContainer } from '@ant-design/pro-components';
import {
  Button,
  Empty,
  message,
  Modal,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useRef, useState } from 'react';
import { useIntl } from '@umijs/max';
import {
  CodeOutlined,
  PlusOutlined,
  DeleteOutlined,
  EditOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import SearchFilterBar, { SearchInput, ActionButton } from '@/components/SearchFilterBar';
import {
  getCliPage,
  createCli,
  updateCli,
  deleteCli,
  toggleCliStatus,
} from '@/services/ant-design-pro/cli';
import StatusSwitch from '@/components/StatusSwitch';
import CliForm from './components/CliForm';
import AgentRefreshModal from '@/pages/agent/components/AgentRefreshModal';

const { Text } = Typography;

const CliManagement: React.FC = () => {
  const intl = useIntl();

  const [formVisible, setFormVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<API.CliItem | undefined>();
  const [refreshTarget, setRefreshTarget] = useState<API.CliItem | undefined>();
  const [loading, setLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.CliItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const [keyword, setKeyword] = useState<string>('');

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
    } catch (error) {
      messageApi.error(intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed, please try again' }));
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

  const handleToggle = async (id: number, newStatus: number) => {
    try {
      const response = await toggleCliStatus(id, newStatus);
      if (response.code === 200) {
        messageApi.success(
          newStatus === 1
            ? intl.formatMessage({ id: 'pages.message.enableSuccess', defaultMessage: 'Enabled successfully' })
            : intl.formatMessage({ id: 'pages.message.disableSuccess', defaultMessage: 'Disabled successfully' }),
        );
        loadData(pageNum, pageSize);
      } else {
        // 禁用被关联 agent 阻塞时错误信息较长，用弹窗展示完整内容
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

  const handleDelete = (id: number) => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.cli.deleteConfirm', defaultMessage: 'Are you sure to delete this CLI tool?' }),
      content: intl.formatMessage({ id: 'pages.message.irreversibleOperation', defaultMessage: 'This operation cannot be undone, please proceed with caution' }),
      okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const response = await deleteCli(id);
          if (response.code === 200) {
            messageApi.success(intl.formatMessage({ id: 'pages.message.deleteSuccess', defaultMessage: 'Deleted successfully' }));
            loadData(pageNum, pageSize);
          } else {
            Modal.error({
              title: intl.formatMessage({ id: 'pages.cli.deleteBlocked', defaultMessage: 'Cannot delete CLI' }),
              content: response.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed' }),
              okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
            });
          }
        } catch (error: any) {
          Modal.error({
            title: intl.formatMessage({ id: 'pages.cli.deleteBlocked', defaultMessage: 'Cannot delete CLI' }),
            content: error?.message || intl.formatMessage({ id: 'pages.message.deleteFailed', defaultMessage: 'Delete failed' }),
            okText: intl.formatMessage({ id: 'pages.common.confirm', defaultMessage: 'Confirm' }),
          });
        }
      },
    });
  };

  const handleSubmit = async (values: API.CliCreateRequest | API.CliUpdateRequest) => {
    try {
      const editing = currentRow;
      const response = editing?.id
        ? await updateCli(editing.id, values)
        : await createCli(values as API.CliCreateRequest);
      if (response.code === 200) {
        messageApi.success(
          editing?.id
            ? intl.formatMessage({ id: 'pages.message.updateSuccess', defaultMessage: 'Updated successfully' })
            : intl.formatMessage({ id: 'pages.message.createSuccess', defaultMessage: 'Created successfully' }),
        );
        setFormVisible(false);
        setCurrentRow(undefined);
        loadData(pageNum, pageSize);
        // 编辑已有 CLI 会影响绑定它的 agent，提示刷新受影响会话
        if (editing?.id) {
          setRefreshTarget(editing);
        }
      } else {
        messageApi.error(response.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed' }));
      }
    } catch (error: any) {
      messageApi.error(error?.message || intl.formatMessage({ id: 'pages.message.operationFailed', defaultMessage: 'Operation failed' }));
    }
  };

  const columns: ColumnsType<API.CliItem> = [
    {
      title: intl.formatMessage({ id: 'pages.cli.name', defaultMessage: 'Name' }),
      dataIndex: 'name',
      key: 'name',
      width: 160,
      ellipsis: true,
      render: (text: string) => (
        <Text strong style={{ fontFamily: 'monospace' }}>
          {text}
        </Text>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.cli.version', defaultMessage: 'Version' }),
      dataIndex: 'version',
      key: 'version',
      width: 100,
      render: (text: string) => text || <Text type="secondary">-</Text>,
    },
    {
      title: intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' }),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (text: string) => text || <Text type="secondary">-</Text>,
    },
    {
      title: intl.formatMessage({ id: 'pages.cli.skills', defaultMessage: 'Skills' }),
      dataIndex: 'skillList',
      key: 'skillList',
      width: 220,
      render: (skillList: API.CliItem['skillList']) =>
        skillList && skillList.length > 0 ? (
          <Space size={[0, 4]} wrap>
            {skillList.map((s) => (
              <Tag key={s.skillId} color="blue">
                {s.skillName}
              </Tag>
            ))}
          </Space>
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
        <StatusSwitch
          status={val ?? 1}
          onChange={(newStatus) => handleToggle(record.id!, newStatus)}
        />
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.creator', defaultMessage: 'Creator' }),
      dataIndex: 'creator',
      key: 'creator',
      width: 100,
    },
    {
      title: intl.formatMessage({ id: 'pages.common.createTime', defaultMessage: 'Created At' }),
      dataIndex: 'createTime',
      key: 'createTime',
      width: 170,
      render: (text: string) => text?.replace('T', ' ')?.substring(0, 19),
    },
    {
      title: intl.formatMessage({ id: 'pages.common.actions', defaultMessage: 'Actions' }),
      key: 'actions',
      width: 150,
      align: 'center',
      render: (_: any, record) => (
        <Space>
          <Tooltip title={intl.formatMessage({ id: 'pages.cli.refreshSessions', defaultMessage: 'Refresh affected sessions' })}>
            <Button
              type="text"
              size="small"
              icon={<SyncOutlined />}
              onClick={() => setRefreshTarget(record)}
            />
          </Tooltip>
          <Tooltip title={intl.formatMessage({ id: 'pages.common.edit', defaultMessage: 'Edit' })}>
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setCurrentRow(record);
                setFormVisible(true);
              }}
            />
          </Tooltip>
          <Tooltip title={intl.formatMessage({ id: 'pages.common.delete', defaultMessage: 'Delete' })}>
            <Button
              type="text"
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={() => handleDelete(record.id!)}
            />
          </Tooltip>
        </Space>
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

      <SearchFilterBar
        onSearch={() => {}}
        onReset={handleReset}
        showSearchButton={false}
        searchText={intl.formatMessage({ id: 'pages.common.search', defaultMessage: 'Search' })}
        resetText={intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
        extra={
          <ActionButton
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setCurrentRow(undefined);
              setFormVisible(true);
            }}
          >
            {intl.formatMessage({ id: 'pages.cli.create', defaultMessage: 'Create CLI Tool' })}
          </ActionButton>
        }
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

      <CliForm
        visible={formVisible}
        values={currentRow}
        onCancel={() => {
          setFormVisible(false);
          setCurrentRow(undefined);
        }}
        onSubmit={handleSubmit}
      />

      <AgentRefreshModal
        visible={!!refreshTarget}
        source="cli"
        agentId={refreshTarget?.id}
        agentName={refreshTarget?.name}
        onClose={() => setRefreshTarget(undefined)}
      />
    </PageContainer>
  );
};

export default CliManagement;
