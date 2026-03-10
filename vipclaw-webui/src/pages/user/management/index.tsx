import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl, useRequest } from '@umijs/max';
import { Button, Drawer, Input, message, Modal, Tag } from 'antd';
import React, { useCallback, useRef, useState } from 'react';
import { deleteUser, getUserPage, updateUser as updateUserApi, createUser } from '@/services/ant-design-pro/user';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';

const UserManagement: React.FC = () => {
  const actionRef = useRef<ActionType | null>(null);

  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<API.UserItem>();

  const intl = useIntl();
  const [messageApi, contextHolder] = message.useMessage();

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
          actionRef.current?.reloadAndRest?.();
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

  const { run: delRun, loading } = useRequest(deleteUser, {
    manual: true,
    onSuccess: () => {
      actionRef.current?.reloadAndRest?.();
      messageApi.success(
        intl.formatMessage({
          id: 'pages.user.management.deleteSuccess',
          defaultMessage: '删除成功',
        }),
      );
    },
    onError: () => {
      messageApi.error(
        intl.formatMessage({
          id: 'pages.user.management.deleteFailed',
          defaultMessage: '删除失败，请重试',
        }),
      );
    },
  });

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
        <>
          <a
           onClick={() => {
             setCurrentRow(record);
             setUpdateModalVisible(true);
            }}
          >
            {intl.formatMessage({
              id: 'pages.user.management.edit',
              defaultMessage: '编辑',
            })}
          </a>
          <a
           onClick={() => {
              handleRemove(record.userId!);
            }}
           style={{ color: 'red', marginLeft: 8 }}
          >
            {intl.formatMessage({
              id: 'pages.user.management.delete',
              defaultMessage: '删除',
            })}
          </a>
        </>
      ),
    },
  ];

  return (
    <PageContainer>
      {contextHolder}
      <ProTable<API.UserItem>
       headerTitle={intl.formatMessage({
         id: 'pages.user.management.title',
          defaultMessage: '用户管理',
        })}
       actionRef={actionRef}
       rowKey="userId"
      search={{
         labelWidth: 120,
      }}
      toolBarRender={() => [
         <Button
          type="primary"
          key="create"
         onClick={() => setCreateModalVisible(true)}
        >
          {intl.formatMessage({
           id: 'pages.user.management.add',
            defaultMessage: '新建',
          })}
        </Button>,
      ]}
     request={async (params) => {
        try {
          const res = await getUserPage({
            pageNum: params.current,
            pageSize: params.pageSize,
             keyword: params.keyword,
            status: params.status,
           });
         return {
           data: res.data?.records || [],
            success: true,
            total: res.data?.total || 0,
          };
        } catch (error) {
         messageApi.error('获取数据失败');
         return {
           data: [],
            success: false,
            total: 0,
          };
        }
      }}
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
            actionRef.current?.reloadAndRest?.();
          } catch (error) {
            messageApi.error('创建失败，请重试');
          }
        }}
        visible={createModalVisible}
      />

      {/* 更新用户弹窗 */}
      {currentRow && (
        <UpdateForm
          onSubmit={async (values: API.UserItem) => {
            try {
              await updateUserApi(currentRow.id || 0, values);
              messageApi.success('更新成功');
              setUpdateModalVisible(false);
              setCurrentRow(undefined);
              actionRef.current?.reloadAndRest?.();
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
