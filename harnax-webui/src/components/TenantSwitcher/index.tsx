import React, { useState, useEffect } from 'react';
import { Select, message } from 'antd';
import { ShopOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { request } from '@umijs/max';

/**
 * 租户切换组件
 * 显示用户所属的所有租户，支持快速切换
 */
const TenantSwitcher: React.FC = () => {
  const intl = useIntl();
  const [tenants, setTenants] = useState<any[]>([]);
  const [currentTenantId, setCurrentTenantId] = useState<number | undefined>(undefined);
  const [loading, setLoading] = useState<boolean>(false);

  // 加载用户所属租户列表
  useEffect(() => {
    loadUserTenants();
  }, []);

  const loadUserTenants = async () => {
    setLoading(true);
    try {
      // 获取当前用户信息（包含租户列表）
      const tokenInfoStr = localStorage.getItem('tokenInfo');
      if (!tokenInfoStr) {
        return;
      }

      const tokenInfo = JSON.parse(tokenInfoStr);
      const currentUser = tokenInfo.currentUser;
      
      if (currentUser && currentUser.tenants) {
        setTenants(currentUser.tenants);
        
        // 优先使用localStorage中保存的租户ID，其次使用token中的
        const savedTenantId = localStorage.getItem('currentTenantId');
        const tenantId = savedTenantId ? parseInt(savedTenantId) : (currentUser.currentTenantId || currentUser.tenants[0]?.id);
        
        setCurrentTenantId(tenantId);
        
        // 持久化当前租户ID
        if (tenantId) {
          localStorage.setItem('currentTenantId', String(tenantId));
        }
      }
    } catch (error) {
      console.error('加载租户列表失败:', error);
    } finally {
      setLoading(false);
    }
  };

  // 切换租户
  const handleTenantChange = async (tenantId: number) => {
    try {
      // 更新localStorage中的当前租户ID
      const tokenInfoStr = localStorage.getItem('tokenInfo');
      if (tokenInfoStr) {
        const tokenInfo = JSON.parse(tokenInfoStr);
        if (tokenInfo.currentUser) {
          tokenInfo.currentUser.currentTenantId = tenantId;
          localStorage.setItem('tokenInfo', JSON.stringify(tokenInfo));
        }
      }
      
      // 持久化当前租户ID
      localStorage.setItem('currentTenantId', String(tenantId));

      setCurrentTenantId(tenantId);
      message.success(
        intl.formatMessage({
          id: 'pages.tenant.switch.success',
          defaultMessage: '租户切换成功',
        })
      );

      // 刷新页面以更新数据
      window.location.reload();
    } catch (error: any) {
      message.error(error?.message || '租户切换失败');
    }
  };

  // 如果没有租户或只有一个租户，不显示切换组件
  if (tenants.length <= 1) {
    return null;
  }

  // 所有版本都隐藏租户切换组件（多租户由系统自动管理，不需要用户手动切换）
  return null;

  const currentTenant = tenants.find((t) => t.id === currentTenantId);

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <span style={{ fontSize: 14, color: '#666' }}>
        {intl.formatMessage({
          id: 'pages.tenant.current',
          defaultMessage: '当前租户:',
        })}
      </span>
      <Select
        value={currentTenantId}
        onChange={handleTenantChange}
        loading={loading}
        style={{ width: 200 }}
        options={tenants.map((tenant) => ({
          label: (
            <span>
              <ShopOutlined style={{ marginRight: 4 }} />
              {tenant.name}
              {tenant.role === 'admin' && (
                <span style={{ marginLeft: 4, color: '#1890ff', fontSize: 12 }}>
                  (管理员)
                </span>
              )}
            </span>
          ),
          value: tenant.id,
        }))}
      />
    </div>
  );
};

export default TenantSwitcher;
