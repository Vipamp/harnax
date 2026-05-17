/**
 * @see https://umijs.org/docs/max/access#access
 * */
export default function access(
  initialState: { currentUser?: API.CurrentUser } | undefined,
) {
  const { currentUser } = initialState ?? {};
  console.log('[权限检查] currentUser:', currentUser);
  console.log('[权限检查] isAdmin:', currentUser?.isAdmin, '类型:', typeof currentUser?.isAdmin);
  console.log('[权限检查] canAccessUserManagement:', currentUser && currentUser.isAdmin === 1);
  return {
    canAdmin: currentUser && currentUser.access === 'admin',
    // 是否可以访问用户管理页面（只有管理员可以）
    canAccessUserManagement: currentUser && currentUser.isAdmin === 1,
  };
}
