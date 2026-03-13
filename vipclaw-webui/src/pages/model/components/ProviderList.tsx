import React from 'react';
import { Card, Switch, Button, Space, Popconfirm, Tag, Typography } from 'antd';
import { EditOutlined, DeleteOutlined, ApiOutlined, CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';

const { Text } = Typography;

interface ProviderListProps {
  providers: API.ModelProviderItem[];
  selectedProvider: API.ModelProviderItem | null;
  onSelect: (provider: API.ModelProviderItem) => void;
  onEdit: (provider: API.ModelProviderItem) => void;
  onToggle: (id: number) => void;
  onDelete: (id: number) => void;
  onConnectivityTest: (id: number) => void;
}

const ProviderList: React.FC<ProviderListProps> = ({
  providers,
  selectedProvider,
  onSelect,
  onEdit,
  onToggle,
  onDelete,
  onConnectivityTest,
}) => {
  const getProviderIcon = (name: string) => {
    const icons: Record<string, string> = {
      dashscope: '/icons/providers/alibabacloud.svg',
      openai: '/icons/providers/openai.svg',
      ollama: '/icons/providers/ollama.svg',
    };
    return icons[name];
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
      {providers.map((provider) => (
        <Card
          key={provider.id}
          hoverable
          size="small"
          style={{
            border: selectedProvider?.id === provider.id ? '2px solid #1890ff' : '1px solid #d9d9d9',
            background: selectedProvider?.id === provider.id ? '#e6f7ff' : '#fff',
          }}
          styles={{
            body: { padding: '12px' }
          }}
          onClick={() => onSelect(provider)}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              {getProviderIcon(provider.name) ? (
                              <img
                                src={getProviderIcon(provider.name)}
                                alt={provider.name}
                                style={{ width: '24px', height: '24px' }}
                              />
                            ) : (
                              <span style={{ fontSize: '20px' }}>📦</span>
                            )}
              <div>
                <Text strong style={{ fontSize: '14px' }}>{provider.displayName}</Text>
                <br />
                <Text type="secondary" style={{ fontSize: '12px' }}>{provider.name}</Text>
              </div>
            </div>
            <Tag color={provider.status === 1 ? 'green' : 'red'}>
              {provider.status === 1 ? '启用' : '禁用'}
            </Tag>
          </div>

          <div style={{ marginTop: '8px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Space size={4}>
              <Button
                type="link"
                size="small"
                icon={<ApiOutlined />}
                onClick={(e) => {
                  e.stopPropagation();
                  onConnectivityTest(provider.id);
                }}
              >
                测试
              </Button>
              <Button
                type="link"
                size="small"
                icon={<EditOutlined />}
                onClick={(e) => {
                  e.stopPropagation();
                  onEdit(provider);
                }}
              >
                编辑
              </Button>
              <Popconfirm
                title="确定要删除此服务商吗？"
                onConfirm={(e) => {
                  e?.stopPropagation();
                  onDelete(provider.id);
                }}
                onCancel={(e) => e?.stopPropagation()}
              >
                <Button
                  type="link"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  onClick={(e) => e.stopPropagation()}
                >
                  删除
                </Button>
              </Popconfirm>
            </Space>
            <Switch
              checked={provider.status === 1}
              onChange={(checked, e) => {
                e.stopPropagation();
                onToggle(provider.id);
              }}
              size="small"
            />
          </div>
        </Card>
      ))}
    </div>
  );
};

export default ProviderList;
