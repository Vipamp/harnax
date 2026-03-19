import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl, useRequest } from '@umijs/max';
import { Button, Card, Input, message, Modal, Select, Space, Tag, Tooltip, Typography } from 'antd';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { deleteUser, getUserPage, updateUser as updateUserApi, createUser } from '@/services/ant-design-pro/user';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import { DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined, UserOutlined } from '@ant-design/icons';

const { Text } = Typography;

const UserManagement: React.FC = () => {
  const actionRef = useRef<ActionType | null>(null);

  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<API.UserItem>();
  const [tableLoading, setTableLoading] = useState<boolean>(false);
  const [data, setData] = useState<API.UserItem[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [current, setCurrent] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const [keyword, setKeyword] = useState<string>('');
  const [status, setStatus] = useState<number | undefined>(undefined);

  const intl = useIntl();
  const [messageApi, contextHolder] = message.useMessage();

  /** 加载数据 */
  const loadData = async (page = current, size = pageSize) => {
    setTableLoading(true);
    try {
      const res = await getUserPage({
        pageNum: page,
        pageSize: size,
        keyword: keyword || undefined,
        status: status,
      });
      setData(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch (error) {
      messageApi.error('获取数据失败');
    } finally {
      setTableLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [current, pageSize]);

  /** 搜索 */
  const handleSearch = () => {
    setCurrent(1);
    loadData(1);
  };

  /** 删除节点 */
  const handleRemove = async (userId: number) => {
    Modal.confirm({
      title: intl.formatMessage({
        id: 'pages.user.management.deleteConfirm',
        defaultMessage: '确认删除该用户吗？',
      }),
      content: intl.formatMessage({
        id: 'pages.user.management.deleteContent',
        defaultMessage: '此操作不可恢复，请谨慎操作',
      }),
      okText: intl.formatMessage({
        id: 'pages.user.management.confirm',
        defaultMessage: '确定',
      }),
      cancelText: intl.formatMessage({
        id: 'pages.user.management.cancel',
        defaultMessage: '取消',
      }),
      onOk: async () => {
        const hide = message.loading('正在删除');
        if (!userId) return;
        try {
          await deleteUser(userId);
          hide();
          messageApi.success(
            intl.formatMessage({
              id: 'pages.user.management.deleteSuccess',
              defaultMessage: '删除成功',
            }),
          );
          loadData();
        } catch (error) {
          hide();
          messageApi.error(
            intl.formatMessage({
              id: 'pages.user.management.deleteFailed',
              defaultMessage: '删除失败，请重试',
            }),
          );
        }
      },
    });
  };

  /** 批量删除 */
  const handleBatchRemove = useCallback(async () => {
    // TODO: 实现批量删除逻辑
    messageApi.warning('批量删除功能开发中');
  }, []);

  const columns: ProColumns<API.UserItem>[] = [
    {
      title: intl.formatMessage({
        id: 'pages.user.management.userId',
        defaultMessage: '用户 ID',
      }),
     dataIndex: 'id',
      valueType: 'text',
     hideInForm: true,
     hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.keyword',
        defaultMessage: '关键词',
      }),
  dataIndex: 'keyword',
      valueType: 'text',
  hideInTable: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.username',
        defaultMessage: '用户名',
      }),
     dataIndex: 'username',
      valueType: 'text',
     hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.nickname',
        defaultMessage: '昵称',
      }),
     dataIndex: 'nickname',
      valueType: 'text',
     hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.email',
        defaultMessage: '邮箱',
      }),
     dataIndex: 'email',
      valueType: 'text',
     hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.phone',
        defaultMessage: '手机号',
      }),
     dataIndex: 'phone',
      valueType: 'text',
     hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.gender',
        defaultMessage: '性别',
      }),
     dataIndex: 'gender',
      valueEnum: {
        0: { text: '女' },
        1: { text: '男' },
        2: { text: '保密' },
      },
     hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.status',
        defaultMessage: '状态',
      }),
     dataIndex: 'status',
      filters: true,
     onFilter: true,
      valueEnum: {
        0: { text: '禁用', status: 'Error' },
        1: { text: '正常', status: 'Success' },
      },
     render: (_, record) => {
       return (
          <Tag color={record.status === 1 ? 'success' : 'error'}>
            {record.status === 1 ? '正常' : '禁用'}
          </Tag>
        );
      },
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.isAdmin',
        defaultMessage: '管理员',
      }),
     dataIndex: 'isAdmin',
      valueEnum: {
        0: { text: '否' },
        1: { text: '是' },
      },
     hideInSearch: true,
     render: (_, record) => {
       return (
          <Tag color={record.isAdmin === 1 ? 'blue' : 'default'}>
            {record.isAdmin === 1 ? '是' : '否'}
          </Tag>
        );
      },
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.createTime',
        defaultMessage: '创建时间',
      }),
    dataIndex: 'createTime',
     valueType: 'dateTime',
    hideInForm: true,
    hideInSearch: true,
   sorter: true,
    defaultSortOrder: 'descend',
    },
    {
      title: intl.formatMessage({
        id: 'pages.user.management.action',
        defaultMessage: '操作',
      }),
      valueType: 'option',
      key: 'option',
     render: (text, record) => (
        <Space size={4}>
          <Tooltip title="编辑">
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              style={{
                color: '#4f6ef7',
                borderRadius: '6px',
                fontWeight: 500,
              }}
              onClick={() => {
                setCurrentRow(record);
                setUpdateModalVisible(true);
              }}
            >
              {intl.formatMessage({
                id: 'pages.user.management.edit',
                defaultMessage: '编辑',
              })}
            </Button>
          </Tooltip>
          <Tooltip title="删除">
            <Button
              type="text"
              size="small"
              danger
              icon={<DeleteOutlined />}
              style={{ borderRadius: '6px', fontWeight: 500 }}
              onClick={() => {
                handleRemove(record.id!);
              }}
            >
              {intl.formatMessage({
                id: 'pages.user.management.delete',
                defaultMessage: '删除',
              })}
            </Button>
          </Tooltip>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '20px', fontWeight: 600, color: '#1a1a2e' }}>
            <UserOutlined style={{ marginRight: 10, color: '#4f6ef7' }} />
            {intl.formatMessage({
              id: 'pages.user.management.title',
              defaultMessage: '用户管理',
            })}
          </span>
        ),
      }}
    >
      {contextHolder}

      {/* 搜索和工具栏 */}
      <Card
        style={{ marginBottom: 24, borderRadius: '12px', boxShadow: '0 2px 12px rgba(0,0,0,0.04)' }}
        styles={{ body: { padding: '16px 20px' } }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <Input
            placeholder="搜索用户名或邮箱"
            prefix={<SearchOutlined />}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={handleSearch}
            style={{ width: 280, borderRadius: '8px' }}
            allowClear
          />
          <Select
            placeholder="状态筛选"
            value={status}
            onChange={(val) => setStatus(val)}
            style={{ width: 140, borderRadius: '8px' }}
            allowClear
            options={[
              { label: '启用', value: 1 },
              { label: '禁用', value: 0 },
            ]}
          />
          <Button type="primary" onClick={handleSearch} style={{ borderRadius: '8px' }}>
            查询
          </Button>
          <Button onClick={() => { setKeyword(''); setStatus(undefined); setCurrent(1); loadData(1); }} style={{ borderRadius: '8px' }}>
            重置
          </Button>
          <div style={{ flex: 1 }} />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateModalVisible(true)}
            style={{ borderRadius: '8px', fontWeight: 600 }}
          >
            {intl.formatMessage({
              id: 'pages.user.management.add',
              defaultMessage: '新建用户',
            })}
          </Button>
        </div>
      </Card>

      <ProTable<API.UserItem>
        headerTitle={undefined}
        rowKey="id"
        loading={tableLoading}
        pagination={{
          current,
          pageSize,
          total,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (page, size) => {
            setCurrent(page);
            if (size) setPageSize(size);
          },
        }}
        dataSource={data}
        search={false}
        toolBarRender={false}
    columns={columns}
   />

      {/* 新建用户弹窗 */}
      <CreateForm
        onCancel={() => setCreateModalVisible(false)}
        onSubmit={async (values: API.UserItem) => {
          try {
            await createUser(values);
            messageApi.success('创建成功');
            setCreateModalVisible(false);
            loadData();
          } catch (error) {
            messageApi.error('创建失败，请重试');
          }
        }}
        visible={createModalVisible}
      />

      {/* 更新用户弹窗 */}
      {currentRow && (
        <UpdateForm
          onSubmit={async (values) => {
            try {
              await updateUserApi(currentRow.id || 0, values);
              messageApi.success('更新成功');
              setUpdateModalVisible(false);
              setCurrentRow(undefined);
              loadData();
            } catch (error) {
              messageApi.error('更新失败，请重试');
            }
          }}
          onCancel={() => {
            setUpdateModalVisible(false);
            setCurrentRow(undefined);
          }}
          visible={updateModalVisible}
          values={currentRow}
        />
      )}
    </PageContainer>
  );
};

export default UserManagement;
