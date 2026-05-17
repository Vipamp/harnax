import { filterRoutesByEdition, FEATURES, isPersonal, isEnterprise, isPublic, EDITION } from '@/utils/edition';

describe('Edition Utilities', () => {
  describe('EDITION constant', () => {
    it('should be defined', () => {
      expect(EDITION).toBeDefined();
      expect(typeof EDITION).toBe('string');
    });
  });

  describe('Version check functions', () => {
    it('should check if is personal edition', () => {
      // 根据当前 EDITION 值测试
      const result = isPersonal();
      expect(typeof result).toBe('boolean');
    });

    it('should check if is enterprise edition', () => {
      const result = isEnterprise();
      expect(typeof result).toBe('boolean');
    });

    it('should check if is public edition', () => {
      const result = isPublic();
      expect(typeof result).toBe('boolean');
    });
  });

  describe('FEATURES configuration', () => {
    it('should have userManagement feature', () => {
      expect('userManagement' in FEATURES).toBe(true);
      expect(typeof FEATURES.userManagement).toBe('boolean');
    });

    it('should have phoneLogin feature', () => {
      expect('phoneLogin' in FEATURES).toBe(true);
      expect(typeof FEATURES.phoneLogin).toBe('boolean');
    });

    it('should have multiTenant feature', () => {
      expect('multiTenant' in FEATURES).toBe(true);
      expect(typeof FEATURES.multiTenant).toBe('boolean');
    });

    it('should have modelMarket feature', () => {
      expect('modelMarket' in FEATURES).toBe(true);
      expect(typeof FEATURES.modelMarket).toBe('boolean');
    });

    it('should have billing feature', () => {
      expect('billing' in FEATURES).toBe(true);
      expect(typeof FEATURES.billing).toBe('boolean');
    });

    it('userManagement should be disabled in personal edition', () => {
      if (EDITION === 'personal') {
        expect(FEATURES.userManagement).toBe(false);
      } else {
        expect(FEATURES.userManagement).toBe(true);
      }
    });

    it('multiTenant should only be enabled in public edition', () => {
      if (EDITION === 'public') {
        expect(FEATURES.multiTenant).toBe(true);
      } else {
        expect(FEATURES.multiTenant).toBe(false);
      }
    });
  });

  describe('filterRoutesByEdition', () => {
    const mockRoutes = [
      {
        path: '/dashboard',
        name: 'dashboard',
      },
      {
        path: '/system',
        name: 'system',
        edition: ['enterprise', 'public'],
        routes: [
          {
            path: '/system/tenant',
            name: 'tenant',
            edition: ['public'],
          },
          {
            path: '/system/user',
            name: 'user',
            edition: ['enterprise', 'public'],
          },
        ],
      },
      {
        path: '/context',
        name: 'context',
        routes: [
          {
            path: '/context/model',
            name: 'model',
            edition: ['public'],
          },
          {
            path: '/context/agent',
            name: 'agent',
          },
        ],
      },
    ];

    it('should filter routes based on edition', () => {
      const filtered = filterRoutesByEdition(mockRoutes);
      
      // 应该返回数组
      expect(Array.isArray(filtered)).toBe(true);
      
      // 所有路由都应该有定义
      expect(filtered.length).toBeGreaterThan(0);
    });

    it('should remove routes not matching current edition', () => {
      const filtered = filterRoutesByEdition(mockRoutes);
      
      // 验证过滤逻辑(根据当前 EDITION)
      filtered.forEach(route => {
        if (route.edition && Array.isArray(route.edition)) {
          expect(route.edition).toContain(EDITION);
        }
      });
    });

    it('should recursively filter nested routes', () => {
      const filtered = filterRoutesByEdition(mockRoutes);
      
      // 检查嵌套路由
      const systemRoute = filtered.find((r: any) => r.path === '/system');
      if (systemRoute && systemRoute.routes) {
        systemRoute.routes.forEach((route: any) => {
          if (route.edition && Array.isArray(route.edition)) {
            expect(route.edition).toContain(EDITION);
          }
        });
      }
    });

    it('should keep routes without edition property', () => {
      const filtered = filterRoutesByEdition(mockRoutes);
      
      // dashboard 没有 edition,应该始终保留
      const dashboardRoute = filtered.find((r: any) => r.path === '/dashboard');
      expect(dashboardRoute).toBeDefined();
    });
  });
});
