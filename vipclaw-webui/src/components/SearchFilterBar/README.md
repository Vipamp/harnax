# 搜索筛选栏组件 (SearchFilterBar)

统一的搜索筛选栏组件库，确保所有管理页面的筛选框样式一致。

## 📦 组件列表

### 1. SearchFilterBar - 搜索筛选栏容器

主容器组件，包含搜索区域和操作按钮区域。

#### 属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| onSearch | `() => void` | - | 搜索回调 |
| onReset | `() => void` | - | 重置回调 |
| extra | `ReactNode` | - | 额外的操作按钮（右侧） |
| children | `ReactNode` | - | 子元素（筛选组件） |
| searchText | `string` | `'Search'` | 搜索按钮文本 |
| resetText | `string` | `'Reset'` | 重置按钮文本 |
| showSearchButton | `boolean` | `true` | 是否显示搜索按钮 |
| showResetButton | `boolean` | `true` | 是否显示重置按钮 |
| style | `CSSProperties` | - | 自定义样式 |

#### 示例

```tsx
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';

<SearchFilterBar
  onSearch={handleSearch}
  onReset={handleReset}
  searchText="查询"
  resetText="重置"
  extra={
    <ActionButton type="primary" onClick={handleCreate}>
      新建
    </ActionButton>
  }
>
  <SearchInput
    value={keyword}
    onChange={setKeyword}
    onSearch={handleSearch}
    placeholder="搜索..."
  />
  <FilterSelect
    value={status}
    onChange={setStatus}
    placeholder="状态筛选"
    options={[
      { label: '启用', value: 1 },
      { label: '禁用', value: 0 },
    ]}
  />
</SearchFilterBar>
```

---

### 2. SearchInput - 搜索输入框

带搜索图标的输入框，统一样式。

#### 属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| value | `string` | - | 输入值 |
| onChange | `(value: string) => void` | - | 变化回调 |
| onSearch | `() => void` | - | 回车搜索回调 |
| placeholder | `string` | `'Please enter to search'` | 占位符 |
| width | `number` | `240` | 宽度（px） |
| style | `CSSProperties` | - | 自定义样式 |

#### 示例

```tsx
<SearchInput
  value={keyword}
  onChange={setKeyword}
  onSearch={handleSearch}
  placeholder="搜索智能体..."
  width={280}
/>
```

---

### 3. FilterSelect - 筛选下拉框

统一样式的筛选下拉框。

#### 属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| value | `any` | - | 选中值 |
| onChange | `(value: any) => void` | - | 变化回调 |
| placeholder | `string` | `'Please select'` | 占位符 |
| options | `{ label: string; value: any }[]` | - | 选项列表 |
| width | `number` | `120` | 宽度（px） |
| mode | `'multiple' \| 'tags'` | - | 是否支持多选 |
| style | `CSSProperties` | - | 自定义样式 |

#### 示例

```tsx
// 单选
<FilterSelect
  value={status}
  onChange={setStatus}
  placeholder="状态筛选"
  options={[
    { label: '启用', value: 1 },
    { label: '禁用', value: 0 },
  ]}
/>

// 多选
<FilterSelect
  value={types}
  onChange={setTypes}
  placeholder="类型筛选"
  mode="multiple"
  width={160}
  options={[
    { label: 'STDIO', value: 'stdio' },
    { label: 'SSE', value: 'sse' },
    { label: 'HTTP', value: 'http' },
  ]}
/>
```

---

### 4. FilterDatePicker - 时间范围选择器

统一样式的时间范围选择器，支持日期和日期时间。

#### 属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| value | `[Dayjs \| null, Dayjs \| null] \| null` | - | 选中值 |
| onChange | `(dates, dateStrings) => void` | - | 变化回调 |
| placeholder | `[string, string]` | `['Start date', 'End date']` | 占位符 |
| showTime | `boolean` | `false` | 是否显示时间 |
| width | `number` | `240` | 宽度（px） |
| format | `string` | `'YYYY-MM-DD'` 或 `'YYYY-MM-DD HH:mm:ss'` | 日期格式 |
| disabledDate | `function` | - | 禁用日期函数 |
| disabledTime | `function` | - | 禁用时间函数 |
| style | `CSSProperties` | - | 自定义样式 |

#### 示例

```tsx
import dayjs from 'dayjs';

// 日期范围
<FilterDatePicker
  value={dateRange}
  onChange={setDateRange}
  placeholder={['开始日期', '结束日期']}
/>

// 日期时间范围
<FilterDatePicker
  value={dateTimeRange}
  onChange={setDateTimeRange}
  placeholder={['开始时间', '结束时间']}
  showTime
  width={280}
/>

// 自定义格式
<FilterDatePicker
  value={dateRange}
  onChange={setDateRange}
  format="YYYY/MM/DD"
/>

// 禁用未来日期
<FilterDatePicker
  value={dateRange}
  onChange={setDateRange}
  disabledDate={(current) => current && current > dayjs().endOf('day')}
/>
```

---

### 5. ActionButton - 操作按钮

右侧操作按钮（如"新建"、"导入"等）。

#### 属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| type | `'primary' \| 'default' \| 'dashed' \| 'text' \| 'link'` | `'primary'` | 按钮类型 |
| icon | `ReactNode` | - | 图标 |
| onClick | `() => void` | - | 点击回调 |
| children | `ReactNode` | - | 按钮文本 |
| style | `CSSProperties` | - | 自定义样式 |
| danger | `boolean` | `false` | 危险按钮 |

#### 示例

```tsx
import { PlusOutlined, ImportOutlined } from '@ant-design/icons';

<ActionButton
  type="primary"
  icon={<PlusOutlined />}
  onClick={handleCreate}
>
  新建
</ActionButton>

<ActionButton
  type="default"
  icon={<ImportOutlined />}
  onClick={handleImport}
>
  导入
</ActionButton>
```

---

## 🎨 统一样式规范

### 搜索卡片
- **圆角**: 12px
- **内边距**: 12px 20px
- **边框**: 1px solid var(--vip-border)
- **阴影**: 0 2px 12px rgba(0,0,0,0.04)
- **下边距**: 24px

### 筛选组件
- **高度**: 28px
- **字体**: 12px
- **圆角**: 6px
- **间距**: 12px（筛选组件之间）

### 搜索输入框
- **宽度**: 240px（默认）
- **图标颜色**: #8c8c9a
- **图标大小**: 12px

### 筛选下拉框
- **宽度**: 120px（默认）

### 按钮
- **高度**: 28px
- **内边距**: 0 12px（搜索/重置）、0 16px（操作按钮）
- **字体**: 12px
- **字重**: 500（操作按钮）

### 布局
- **主容器间距**: 16px
- **搜索组最小宽度**: 300px
- **响应式**: 支持 flexWrap

---

## 📝 完整使用示例

```tsx
import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';
import { PlusOutlined } from '@ant-design/icons';

const AgentManagement: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<number | undefined>(undefined);

  const handleSearch = () => {
    console.log('搜索:', { keyword, status });
    // 加载数据
  };

  const handleReset = () => {
    setKeyword('');
    setStatus(undefined);
    handleSearch();
  };

  const handleCreate = () => {
    console.log('新建');
  };

  return (
    <PageContainer header={{ title: '智能体管理' }}>
      <SearchFilterBar
        onSearch={handleSearch}
        onReset={handleReset}
        searchText="查询"
        resetText="重置"
        extra={
          <ActionButton
            type="primary"
            icon={<PlusOutlined />}
            onClick={handleCreate}
          >
            新建智能体
          </ActionButton>
        }
      >
        <SearchInput
          value={keyword}
          onChange={setKeyword}
          onSearch={handleSearch}
          placeholder="搜索智能体..."
        />
        <FilterSelect
          value={status}
          onChange={setStatus}
          placeholder="状态筛选"
          options={[
            { label: '启用', value: 1 },
            { label: '禁用', value: 0 },
          ]}
        />
      </SearchFilterBar>

      {/* 其他内容 */}
    </PageContainer>
  );
};

export default AgentManagement;
```

---

## 🔄 迁移指南

### 从旧代码迁移

#### 旧代码
```tsx
<Card
  style={{ marginBottom: 24, borderRadius: '12px', boxShadow: '0 2px 12px rgba(0,0,0,0.04)' }}
  styles={{ body: { padding: '12px 20px' } }}
>
  <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, flex: 1, minWidth: 300 }}>
      <Input
        placeholder="搜索..."
        value={keyword}
        onChange={(e) => setKeyword(e.target.value)}
        style={{ width: 240, borderRadius: '6px', height: '28px', fontSize: '12px' }}
      />
      <Select
        placeholder="状态"
        value={status}
        onChange={(val) => setStatus(val)}
        style={{ width: 120, height: '28px', fontSize: '12px' }}
        options={[...]}
      />
      <Button type="primary" onClick={handleSearch} style={{ borderRadius: '6px', height: '28px', padding: '0 12px', fontSize: '12px' }}>
        查询
      </Button>
      <Button onClick={handleReset} style={{ borderRadius: '6px', height: '28px', padding: '0 12px', fontSize: '12px' }}>
        重置
      </Button>
    </div>
    <Button type="primary" onClick={handleCreate} style={{ borderRadius: '6px', height: '28px', padding: '0 16px', fontSize: '12px' }}>
      新建
    </Button>
  </div>
</Card>
```

#### 新代码
```tsx
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';

<SearchFilterBar
  onSearch={handleSearch}
  onReset={handleReset}
  extra={
    <ActionButton type="primary" onClick={handleCreate}>
      新建
    </ActionButton>
  }
>
  <SearchInput
    value={keyword}
    onChange={setKeyword}
    onSearch={handleSearch}
    placeholder="搜索..."
  />
  <FilterSelect
    value={status}
    onChange={setStatus}
    placeholder="状态"
    options={[...]}
  />
</SearchFilterBar>
```

**代码行数减少**: ~30 行 → ~15 行（减少 50%）

---

## ⚠️ 注意事项

1. **所有筛选组件默认高度为 28px**，如果需要调整高度，通过 `style` 属性覆盖
2. **搜索输入框默认宽度 240px**，筛选下拉框默认 120px
3. **按钮自动适配国际化**，通过 `searchText` 和 `resetText` 属性传入翻译文本
4. **支持响应式布局**，在小屏幕上自动换行
5. **样式已统一**，无需额外添加 borderRadius、height、fontSize 等样式

---

## 📂 文件结构

```
src/components/SearchFilterBar/
├── index.tsx       # 组件实现（包含 SearchFilterBar, SearchInput, FilterSelect, FilterDatePicker, ActionButton）
├── index.d.ts      # TypeScript 类型声明
└── README.md       # 使用说明
```

---

## 🎯 已应用页面

- ✅ Agent 管理页面
- ⏳ MCP 管理页面（待迁移）
- ⏳ Channel 管理页面（待迁移）
- ⏳ Job 管理页面（待迁移）
- ⏳ Job Log 执行日志页面（待迁移）
- ⏳ 用户管理页面（待迁移）
- ⏳ 租户管理页面（待迁移）

---

## 🚀 后续优化

- [ ] 添加日期范围选择器组件（FilterDatePicker）
- [ ] 添加级联选择器组件（FilterCascader）
- [ ] 支持保存筛选条件
- [ ] 支持筛选条件预设模板
- [ ] 添加筛选历史功能
