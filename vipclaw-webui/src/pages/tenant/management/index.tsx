import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { 
  Button, 
  Card, 
  Input, 
  message, 
  Modal, 
  Select, 
  Space, 
  Tag, 
  Tooltip, 
  Typography,
  Divider,
  Statistic,
  Row,
  Col,
  Badge,
  Avatar
} from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import {
  createTenant,
  deleteTenant,
  getTenantList,
  toggleTenantStatus,
  updateTenant,
} from '@/services/tenant';
import CreateForm from './components/CreateForm';
import UpdateForm from './components/UpdateForm';
import { 
  DeleteOutlined, 
  EditOutlined, 
  PlusOutlined, 
  SearchOutlined,
  ReloadOutlined,
  ShopOutlined,
  UserOutlined,
  CheckCircleOutlined,
  StopOutlined,
  TrophyOutlined
} from '@ant-design/icons';

const { Text, Title } = Typography;

const TenantManagement: React.FC = () => {
  const actionRef = useRef<ActionType | null>(null);

  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [updateModalVisible, setUpdateModalVisible] = useState<boolean>(false);
  const [currentRow, setCurrentRow] = useState<any>();
  const [tableLoading, setTableLoading] = useState<boolean>(false);
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [pageNum, setPageNum] = useState<number>(1);
  const [pageSize, setPageSize] = useState<number>(10);
  const [name, setName] = useState<string>('');
  const [status, setStatus] = useState<number | undefined>(undefined);

  const intl = useIntl();
  const [messageApi, contextHolder] = message.useMessage();

  /** 加载数据 */
  const loadData = async (page = pageNum, size = pageSize) => {
    setTableLoading(true);
    try {
      const res = await getTenantList({
        pageNum: page,
        pageSize: size,
        name: name || undefined,
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
  }, [pageNum, pageSize]);

  /** 搜索 */
  const handleSearch = () => {
    setPageNum(1);
    loadData(1);
  };

  /** 删除租户 */
  const handleRemove = async (tenantId: number) => {
    Modal.confirm({
      title: intl.formatMessage({
        id: 'pages.tenant.management.deleteConfirm',
        defaultMessage: '确认删除该租户吗？',
      }),
      content: intl.formatMessage({
        id: 'pages.tenant.management.deleteContent',
        defaultMessage: '此操作将删除租户及其所有关联数据，请谨慎操作',
      }),
      okText: intl.formatMessage({
        id: 'pages.tenant.management.confirm',
        defaultMessage: '确定',
      }),
      cancelText: intl.formatMessage({
        id: 'pages.tenant.management.cancel',
        defaultMessage: '取消',
      }),
      onOk: async () => {
        const hide = message.loading('正在删除');
        if (!tenantId) return;
        try {
          const response = await deleteTenant(tenantId);
          hide();
          if (response.code === 200) {
            messageApi.success(
              intl.formatMessage({
                id: 'pages.tenant.management.deleteSuccess',
                defaultMessage: '删除成功',
              }),
            );
            loadData();
          } else {
            const errorMsg = response.message || intl.formatMessage({
              id: 'pages.tenant.management.deleteFailed',
              defaultMessage: '删除失败，请重试',
            });
            messageApi.error(errorMsg);
          }
        } catch (error: any) {
          hide();
          const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({
            id: 'pages.tenant.management.deleteFailed',
            defaultMessage: '删除失败，请重试',
          });
          messageApi.error(errorMsg);
        }
      },
    });
  };

  /** 切换租户状态 */
  const handleToggleStatus = async (tenantId: number) => {
    const hide = message.loading('正在切换状态');
    try {
      const response = await toggleTenantStatus(tenantId);
      hide();
      if (response.code === 200) {
        messageApi.success(
          intl.formatMessage({
            id: 'pages.tenant.management.toggleSuccess',
            defaultMessage: '状态切换成功',
          }),
        );
        loadData();
      } else {
        const errorMsg = response.message || intl.formatMessage({
          id: 'pages.tenant.management.toggleFailed',
          defaultMessage: '状态切换失败',
        });
        messageApi.error(errorMsg);
      }
    } catch (error: any) {
      hide();
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({
        id: 'pages.tenant.management.toggleFailed',
        defaultMessage: '状态切换失败',
      });
      messageApi.error(errorMsg);
    }
  };

  /** 创建租户 */
  const handleCreate = async (fields: any) => {
    const hide = message.loading('正在创建');
    try {
      const response = await createTenant(fields);
      hide();
      if (response.code === 200) {
        messageApi.success(
          intl.formatMessage({
            id: 'pages.tenant.management.createSuccess',
            defaultMessage: '创建成功',
          }),
        );
        setCreateModalVisible(false);
        loadData();
      } else {
        const errorMsg = response.message || intl.formatMessage({
          id: 'pages.tenant.management.createFailed',
          defaultMessage: '创建失败，请重试',
        });
        messageApi.error(errorMsg);
      }
    } catch (error: any) {
      hide();
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({
        id: 'pages.tenant.management.createFailed',
        defaultMessage: '创建失败，请重试',
      });
      messageApi.error(errorMsg);
    }
  };

  /** 更新租户 */
  const handleUpdate = async (fields: any) => {
    const hide = message.loading('正在更新');
    try {
      const response = await updateTenant(currentRow.id, fields);
      hide();
      if (response.code === 200) {
        messageApi.success(
          intl.formatMessage({
            id: 'pages.tenant.management.updateSuccess',
            defaultMessage: '更新成功',
          }),
        );
        setUpdateModalVisible(false);
        setCurrentRow(undefined);
        loadData();
      } else {
        const errorMsg = response.message || intl.formatMessage({
          id: 'pages.tenant.management.updateFailed',
          defaultMessage: '更新失败，请重试',
        });
        messageApi.error(errorMsg);
      }
    } catch (error: any) {
      hide();
      const errorMsg = error?.message || error?.info?.errorMessage || intl.formatMessage({
        id: 'pages.tenant.management.updateFailed',
        defaultMessage: '更新失败，请重试',
      });
      messageApi.error(errorMsg);
    }
  };

  const columns: ProColumns<any>[] = [
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.tenantId',
        defaultMessage: '租户 ID',
      }),
      dataIndex: 'id',
      valueType: 'text',
      hideInForm: true,
      hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.tenantName',
        defaultMessage: '租户名称',
      }),
      dataIndex: 'name',
      valueType: 'text',
      hideInTable: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.name',
        defaultMessage: '租户名称',
      }),
      dataIndex: 'name',
      valueType: 'text',
      hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.creator',
        defaultMessage: '创建人',
      }),
      dataIndex: 'creator',
      valueType: 'text',
      hideInSearch: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.status',
        defaultMessage: '状态',
      }),
      dataIndex: 'status',
      filters: true,
      onFilter: true,
      valueEnum: {
        0: { text: '禁用', status: 'Error' },
        1: { text: '启用', status: 'Success' },
      },
      render: (_, record) => {
        return (
          <Badge
            status={record.status === 1 ? 'success' : 'error'}
            text={
              <span style={{ 
                fontWeight: 600,
                color: record.status === 1 ? '#52c41a' : '#ff4d4f'
              }}>
                {record.status === 1
                  ? intl.formatMessage({
                      id: 'pages.tenant.management.enabled',
                      defaultMessage: '启用',
                    })
                  : intl.formatMessage({
                      id: 'pages.tenant.management.disabled',
                      defaultMessage: '禁用',
                    })}
              </span>
            }
          />
        );
      },
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.createTime',
        defaultMessage: '创建时间',
      }),
      dataIndex: 'createTime',
      valueType: 'dateTime',
      hideInSearch: true,
      hideInForm: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.updateTime',
        defaultMessage: '更新时间',
      }),
      dataIndex: 'updateTime',
      valueType: 'dateTime',
      hideInSearch: true,
      hideInForm: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.tenant.management.operation',
        defaultMessage: '操作',
      }),
      valueType: 'option',
      width: 250,
      render: (_, record) => (
        <Space size="middle">
          <Tooltip
            title={intl.formatMessage({
              id: 'pages.tenant.management.toggleStatus',
              defaultMessage: '切换状态',
            })}
          >
            <Button
              type="text"
              size="small"
              icon={record.status === 1 ? <StopOutlined /> : <CheckCircleOutlined />}
              onClick={() => handleToggleStatus(record.id)}
              style={{
                color: record.status === 1 ? '#faad14' : '#52c41a',
                fontWeight: 600,
                borderRadius: 8,
                padding: '4px 12px'
              }}
            >
              {record.status === 1 ? '禁用' : '启用'}
            </Button>
          </Tooltip>
          <Tooltip
            title={intl.formatMessage({
              id: 'pages.tenant.management.edit',
              defaultMessage: '编辑',
            })}
          >
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setCurrentRow(record);
                setUpdateModalVisible(true);
              }}
              style={{
                color: '#667eea',
                fontWeight: 600,
                borderRadius: 8,
                padding: '4px 12px'
              }}
            >
              {intl.formatMessage({
                id: 'pages.tenant.management.edit',
                defaultMessage: '编辑',
              })}
            </Button>
          </Tooltip>
          <Tooltip
            title={intl.formatMessage({
              id: 'pages.tenant.management.delete',
              defaultMessage: '删除',
            })}
          >
            <Button
              type="text"
              danger
              size="small"
              icon={<DeleteOutlined />}
              onClick={() => handleRemove(record.id)}
              style={{
                fontWeight: 600,
                borderRadius: 8,
                padding: '4px 12px'
              }}
            >
              {intl.formatMessage({
                id: 'pages.tenant.management.delete',
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
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <Avatar 
              size={48} 
              icon={<ShopOutlined />} 
              style={{ 
                background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
                boxShadow: '0 4px 12px rgba(102, 126, 234, 0.3)'
              }} 
            />
            <div>
              <Title level={3} style={{ margin: 0, color: '#1a1a2e', fontWeight: 700 }}>
                {intl.formatMessage({
                  id: 'pages.tenant.management.title',
                  defaultMessage: '租户管理',
                })}
              </Title>
              <Text type="secondary" style={{ fontSize: 14 }}>
                管理系统中的所有租户及其用户权限
              </Text>
            </div>
          </div>
        ),
      }}
    >
      {contextHolder}

      {/* 统计卡片 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col span={8}>
          <Card 
            hoverable
            style={{ 
              borderRadius: 16, 
              border: 'none',
              background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
              color: 'white'
            }}
          >
            <Statistic
              title={<span style={{ color: 'rgba(255,255,255,0.85)', fontSize: 14 }}>总租户数</span>}
              value={total}
              prefix={<ShopOutlined style={{ fontSize: 24 }} />}
              valueStyle={{ color: 'white', fontWeight: 700 }}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card 
            hoverable
            style={{ 
              borderRadius: 16, 
              border: 'none',
              background: 'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)',
              color: 'white'
            }}
          >
            <Statistic
              title={<span style={{ color: 'rgba(255,255,255,0.85)', fontSize: 14 }}>启用中</span>}
              value={data.filter(d => d.status === 1).length}
              prefix={<CheckCircleOutlined style={{ fontSize: 24 }} />}
              valueStyle={{ color: 'white', fontWeight: 700 }}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card 
            hoverable
            style={{ 
              borderRadius: 16, 
              border: 'none',
              background: 'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)',
              color: 'white'
            }}
          >
            <Statistic
              title={<span style={{ color: 'rgba(255,255,255,0.85)', fontSize: 14 }}>已禁用</span>}
              value={data.filter(d => d.status === 0).length}
              prefix={<StopOutlined style={{ fontSize: 24 }} />}
              valueStyle={{ color: 'white', fontWeight: 700 }}
            />
          </Card>
        </Col>
      </Row>

      {/* 搜索和工具栏 */}
      <Card
        style={{ 
          marginBottom: 24, 
          borderRadius: 16, 
          border: 'none',
          boxShadow: '0 4px 20px rgba(0,0,0,0.06)'
        }}
        styles={{ body: { padding: '20px 24px' } }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
          <Input
            placeholder="搜索租户名称"
            prefix={<SearchOutlined style={{ color: '#667eea' }} />}
            value={name}
            onChange={(e) => setName(e.target.value)}
            onPressEnter={handleSearch}
            style={{ 
              width: 320, 
              borderRadius: 12,
              border: '2px solid #e8ecfb'
            }}
            allowClear
          />
          <Select
            placeholder="状态筛选"
            value={status}
            onChange={(val) => setStatus(val)}
            style={{ 
              width: 160, 
              borderRadius: 12,
              border: '2px solid #e8ecfb'
            }}
            allowClear
            options={[
              { label: '启用', value: 1 },
              { label: '禁用', value: 0 },
            ]}
          />
          <Button 
            type="primary" 
            onClick={handleSearch} 
            style={{ 
              borderRadius: 12, 
              fontWeight: 600,
              background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
              border: 'none',
              boxShadow: '0 4px 12px rgba(102, 126, 234, 0.3)'
            }}
          >
            查询
          </Button>
          <Button 
            onClick={() => { setName(''); setStatus(undefined); setPageNum(1); loadData(1); }} 
            style={{ 
              borderRadius: 12,
              border: '2px solid #e8ecfb'
            }}
            icon={<ReloadOutlined />}
          >
            重置
          </Button>
          <div style={{ flex: 1 }} />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateModalVisible(true)}
            style={{ 
              borderRadius: 12, 
              fontWeight: 600,
              background: 'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)',
              border: 'none',
              boxShadow: '0 4px 12px rgba(245, 87, 108, 0.3)',
              padding: '0 24px',
              height: 40
            }}
          >
            {intl.formatMessage({
              id: 'pages.tenant.management.createNew',
              defaultMessage: '新建租户',
            })}
          </Button>
        </div>
      </Card>

      <ProTable<any>
        rowKey="id"
        loading={tableLoading}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (page, size) => {
            setPageNum(page);
            if (size) setPageSize(size);
          },
        }}
        dataSource={data}
        search={false}
        toolBarRender={false}
        style={{ borderRadius: 16 }}
        columns={columns}
      />

      {/* 创建表单 */}
      <CreateForm
        onCancel={() => setCreateModalVisible(false)}
        visible={createModalVisible}
        onSubmit={handleCreate}
      />

      {/* 更新表单 */}
      {currentRow && (
        <UpdateForm
          onCancel={() => {
            setUpdateModalVisible(false);
            setCurrentRow(undefined);
          }}
          visible={updateModalVisible}
          onSubmit={handleUpdate}
          values={currentRow}
        />
      )}
    </PageContainer>
  );
};

export default TenantManagement;
