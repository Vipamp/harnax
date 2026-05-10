import React, { useState, useEffect } from 'react';
import { ApiOutlined } from '@ant-design/icons';
import EntityCard from '@/components/EntityCard';
import { getModelProviderStats } from '@/services/ant-design-pro/modelProvider';
import { useIntl } from '@umijs/max';

interface ProviderCardProps {
  provider: API.ModelProviderItem;
  index: number;
  onSelect: (provider: API.ModelProviderItem) => void;
  onEdit: (provider: API.ModelProviderItem) => void;
  onToggle: (id: number, status: number) => void;
  onDelete: (id: number) => void;
  onConnectivityTest: (id: number) => void;
  screenSize?: 'xs' | 'sm' | 'md' | 'lg' | 'xl' | 'xxl';
}

const ProviderCard: React.FC<ProviderCardProps> = ({
  provider,
  index,
  onSelect,
  onEdit,
  onToggle,
  onDelete,
  onConnectivityTest,
}) => {
  const intl = useIntl();
  const [stats, setStats] = useState<API.ModelStatsInfo>({
    totalModels: 0,
    enabledModels: 0,
    disabledModels: 0,
  });
  const [loading, setLoading] = useState(false);

  // 加载统计信息
  useEffect(() => {
    const loadStats = async () => {
      if (provider.id) {
        setLoading(true);
        try {
          const response = await getModelProviderStats(provider.id);
          if (response.data) {
            setStats(response.data);
          }
        } catch (error) {
          console.error('Failed to load stats:', error);
        } finally {
          setLoading(false);
        }
      }
    };
    loadStats();
  }, [provider.id]);

  // 获取服务商图标
  const getProviderIcon = (type: string) => {
    const icons: Record<string, string> = {
      dashscope: '/icons/providers/alibabacloud.svg',
      openai: '/icons/providers/openai.svg',
      ollama: '/icons/providers/ollama.svg',
    };
    return icons[type];
  };

  // 获取服务商颜色
  const getProviderColor = (type: string) => {
    const colors: Record<string, string> = {
      dashscope: '#ff6a00',
      openai: '#10a37f',
      ollama: '#4f6ef7',  // 蓝色
    };
    return colors[type] || '#4f6ef7';
  };

  const providerColor = getProviderColor(provider.type);
  const iconUrl = getProviderIcon(provider.type);

  // 渲染图标（支持 SVG 图片）
  const renderIcon = () => {
    if (iconUrl) {
      return (
        <img
          src={iconUrl}
          alt={provider.type}
          style={{ width: '65%', height: '65%', objectFit: 'contain' }}
        />
      );
    }
    return <ApiOutlined />;
  };

  return (
    <EntityCard
      entity={provider}
      index={index}
      icon={renderIcon()}
      name={provider.name}
      tagLabel={provider.type.toUpperCase()}
      tagColor={providerColor}
      tagBgHover={providerColor}
      description={provider.description}
      status={provider.status}
      isPublic={provider.isPublic}
      creator={provider.creator}
      createTime={provider.createTime}
      stats={[
        {
          label: intl.formatMessage({ id: 'pages.model.totalModels', defaultMessage: 'Total' }),
          value: loading ? '-' : stats.totalModels,
          color: providerColor,
        },
        {
          label: intl.formatMessage({ id: 'pages.model.enabledModels', defaultMessage: 'Enabled' }),
          value: loading ? '-' : stats.enabledModels,
          color: '#52c41a',
        },
        {
          label: intl.formatMessage({ id: 'pages.model.disabledModels', defaultMessage: 'Disabled' }),
          value: loading ? '-' : stats.disabledModels,
          color: '#ff4d4f',
        },
      ]}
      actions={{
        showTest: true,
        showEdit: true,
        showDelete: true,
        onTest: () => onConnectivityTest(provider.id!),
        onEdit: () => onEdit(provider),
        onDelete: () => onDelete(provider.id!),
      }}
      onToggle={(id, status) => onToggle(id, status)}
      onClick={() => onSelect(provider)}
    />
  );
};

export default ProviderCard;
