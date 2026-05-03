# TableActions 组件使用指南

## 📦 组件位置

`/vipclaw-webui/src/components/TableActions/index.tsx`

## 🎯 功能说明

TableActions 是一个通用的表格操作列组件,统一了编辑、删除、启停开关的样式和交互。

## ✨ 特性

- ✅ 统一的按钮样式(type="link", padding: 4px)
- ✅ 内置删除确认(Popconfirm)
- ✅ 统一的启停开关样式
- ✅ 完整的国际化支持
- ✅ 权限控制(无权限时只显示禁用开关)
- ✅ 灵活的配置选项

## 📝 使用方式

### 1. 基础用法(编辑 + 删除 + 启停)

```tsx
import TableActions from '@/components/TableActions';

// 在表格列定义中
{
  title: '操作',
  key: 'action',
  render: (_, record) => (
    <TableActions
      onEdit={() => handleEdit(record)}
      onDelete={() => handleDelete(record.id)}
      onToggle={() => handleToggle(record.id, record.status)}
      status={record.status}
    />
  ),
}
```

### 2. 带删除确认

```tsx
<TableActions
  onEdit={handleEdit}
  onDelete={handleDelete}
  deleteConfirmTitle="确定要删除这个模型吗?"
  onToggle={handleToggle}
  status={record.status}
/>
```

### 3. 带权限控制

```tsx
import { hasOperationPermission } from '@/utils/permissionUtil';

const canOperate = hasOperationPermission(isAdmin, currentUser, record.creator);

<TableActions
  onEdit={handleEdit}
  onDelete={handleDelete}
  onToggle={handleToggle}
  status={record.status}
  hasPermission={canOperate}
/>
```

### 4. 只显示启停开关

```tsx
<TableActions
  showEdit={false}
  showDelete={false}
  onToggle={handleToggle}
  status={record.status}
/>
```

### 5. 自定义编辑按钮颜色

```tsx
<TableActions
  onEdit={handleEdit}
  onDelete={handleDelete}
  editColor="#52c41a"  // 自定义颜色
/>
```

## 🔧 Props 说明

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| showEdit | boolean | true | 是否显示编辑按钮 |
| showDelete | boolean | true | 是否显示删除按钮 |
| showSwitch | boolean | true | 是否显示启停开关 |
| onEdit | () => void | - | 编辑按钮点击回调 |
| onDelete | () => void | - | 删除按钮确认回调 |
| onToggle | () => void | - | 状态切换回调 |
| status | number | 0 | 当前状态 (1: 启用, 0: 禁用) |
| deleteConfirmTitle | string | 'Are you sure to delete?' | 删除确认标题 |
| editColor | string | '#1890ff' | 编辑按钮颜色 |
| size | number | 8 | 按钮间距 |
| hasPermission | boolean | true | 是否有操作权限 |

## ✅ 已应用的页面

### 1. 模型列表 (ModelListTable)
- 文件: `/vipclaw-webui/src/pages/model/components/ModelListTable.tsx`
- 代码减少: **46行** → **11行** (减少 76%)
- 功能: 编辑 + 删除 + 启停开关
- 特点: 带权限控制,带自定义删除确认

### 2. 技能列表 (SkillList)
- 文件: `/vipclaw-webui/src/pages/skill/components/SkillList.tsx`
- 代码减少: **6行** → **6行** (保持不变,但样式统一)
- 功能: 仅启停开关
- 特点: 使用 showEdit={false}, showDelete={false}

### 3. 渠道列表 (Channel)
- 文件: `/vipclaw-webui/src/pages/channel/index.tsx`
- 代码减少: **41行** → **15行** (减少 63%)
- 功能: 编辑 + 删除 + 启停开关
- 特点: 
  - 移除了单独的状态列,合并到操作列
  - 带权限控制
  - 带自定义删除确认

### 4. 租户用户列表 (TenantUserList)
- 文件: `/vipclaw-webui/src/pages/tenant/management/components/TenantUserList.tsx`
- 代码减少: **11行** → **7行** (减少 36%)
- 功能: 仅删除按钮
- 特点: 
  - 使用 showEdit={false}, showSwitch={false}
  - 带自定义删除确认
  - 国际化标题

## 📋 不建议应用的页面

### 1. 任务列表 (Job)
- 文件: `/vipclaw-webui/src/pages/job/index.tsx`
- 原因: Job 页面有启动/暂停、立即执行等自定义按钮,不完全适用标准 TableActions
- 建议: 保持现有实现,或创建专门的 JobActions 组件

### 2. 用户管理列表 (UserManagement)
- 文件: `/vipclaw-webui/src/pages/user/management/index.tsx`
- 原因: 有自定义的“管理租户”按钮,且按钮带有文字,样式特殊
- 建议: 保持现有实现

### 3. 租户管理列表 (TenantManagement)
- 文件: `/vipclaw-webui/src/pages/tenant/management/index.tsx`
- 原因: 有自定义的“管理用户”按钮,且按钮带有文字,样式特殊
- 建议: 保持现有实现

## 🎨 样式规范

所有使用 TableActions 的页面将拥有统一的操作列样式:

- **编辑按钮**: 蓝色链接样式 (#1890ff),padding: 4px
- **删除按钮**: 红色链接样式,带 Popconfirm 确认, padding: 4px
- **启停开关**: 统一的 Switch 样式,启用时 var(--vip-primary),禁用时 var(--vip-border)
- **按钮间距**: 8px
- **Hover 效果**: 无背景色,仅图标变色(type="link")

## 🔍 与 CardActions 的区别

| 特性 | CardActions | TableActions |
|------|-------------|--------------|
| 使用场景 | 卡片布局 | 表格布局 |
| 删除确认 | ❌ 无 | ✅ Popconfirm |
| 启停开关 | ❌ 无 | ✅ 内置 |
| 权限控制 | ❌ 无 | ✅ 内置 |
| 按钮类型 | type="link" | type="link" |
| Hover 效果 | 无背景 | 无背景 |

## 📊 效果对比

### 使用 TableActions 前
```tsx
<Space size={8}>
  <Tooltip title="编辑">
    <Button
      type="link"
      size="small"
      icon={<EditOutlined />}
      onClick={() => handleEdit(record)}
      style={{ padding: '4px', color: '#1890ff' }}
    />
  </Tooltip>
  <Popconfirm title="确定删除?" onConfirm={() => handleDelete(record.id)}>
    <Tooltip title="删除">
      <Button
        type="link"
        size="small"
        danger
        icon={<DeleteOutlined />}
        style={{ padding: '4px' }}
      />
    </Tooltip>
  </Popconfirm>
  <Switch
    checked={record.status === 1}
    onChange={() => handleToggle(record.id, record.status)}
    checkedChildren="启用"
    unCheckedChildren="禁用"
    style={{ backgroundColor: record.status === 1 ? '#4f6ef7' : '#d9d9d9' }}
  />
</Space>
```

### 使用 TableActions 后
```tsx
<TableActions
  onEdit={() => handleEdit(record)}
  onDelete={() => handleDelete(record.id)}
  onToggle={() => handleToggle(record.id, record.status)}
  status={record.status}
/>
```

**代码减少约 70-80%!**

## 🚀 后续优化建议

1. **统一删除确认文案**: 所有页面使用统一的国际化 key
2. **添加更多操作**: 如查看、复制等常用操作
3. **响应式优化**: 在小屏幕下自动收起多余按钮
4. **性能优化**: 使用 React.memo 避免不必要的渲染
