import { history } from '@umijs/max';
import React, { useEffect } from 'react';

// 旧会话地址（/agent/session?id=…）保活：路由级字符串 redirect 带不住查询串，所以在组件里带过去。
const SessionRedirect: React.FC = () => {
  useEffect(() => {
    history.replace(`/${history.location.search}`);
  }, []);
  return null;
};

export default SessionRedirect;
