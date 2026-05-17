# FormModal 公共组件使用指南

## 📦 组件说明

`FormModal` 是一组公共组件，用于统一所有弹窗表单的样式，包括：
- **FormModal**: 弹窗容器组件，提供标准化的标题、图标和布局
- **FormActions**: 表单操作按钮组件，提供统一的提交、重置按钮

## 🎨 样式特性

### 自动支持的主题
- ✅ 浅色模式
- ✅ 深色模式（暗黑模式）
- ✅ 响应式布局

### 统一的样式规范
- **弹窗标题**: 图标 + 主标题 + 副标题
- **输入框**: 统一高度 36px，圆角 6px
- **密码框**: 与输入框一致的样式
- **下拉框**: 统一高度和交互效果
- **操作按钮**: 统一高度 36px，圆角 6px

## 📖 使用方法

### 1. 基础使用

```tsx
import React from 'react';
import { ProForm, ProFormText } from '@ant-design/pro-components';
import { FormModal } from '@/components/FormModal';
import { UserOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

const MyFormModal: React.FC = () => {
  const intl = useIntl();
  const [open, setOpen] = React.useState(false);

  return (
    <FormModal
      open={open}
      onCancel={() => setOpen(false)}
      titleConfig={{
        mainTitle: intl.formatMessage({
          id: 'pages.user.management.add',
          defaultMessage: '新建用户',
        }),
        subtitle: intl.formatMessage({
          id: 'pages.user.management.add.subtitle',
          defaultMessage: '填写用户基本信息，创建系统账号',
        }),
        icon: <UserOutlined />,
      }}
    >
      <ProForm
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
        submitter={{
          render: (_, dom) => (
            <div style={{ 
              display: 'flex', 
              justifyContent: 'flex-end', 
              gap: '12px',
              marginTop: '24px',
              paddingTop: '20px',
              borderTop: '1px solid var(--vip-border)'
            }}>
              {dom}
            </div>
          ),
        }}
      >
        <ProFormText
          name="username"
          label="用户名"
          placeholder="请输入用户名"
          rules={[{ required: true, message: '请输入用户名' }]}
        />
      </ProForm>
    </FormModal>
  );
};

export default MyFormModal;
```

### 2. 自定义图标颜色

```tsx
<FormModal
  open={open}
  onCancel={() => setOpen(false)}
  titleConfig={{
    mainTitle: '编辑用户',
    subtitle: '修改用户信息，保存后即时生效',
    icon: <EditOutlined />,
    // 自定义渐变色（使用警告色）
    iconGradient: 'linear-gradient(135deg, var(--vip-warning) 0%, var(--vip-warning-light) 100%)',
    iconShadowColor: 'rgba(var(--vip-warning-rgb), 0.25)',
  }}
>
  {/* 表单内容 */}
</FormModal>
```

### 3. 使用 FormActions 按钮组件

```tsx
import { FormModal, FormActions } from '@/components/FormModal';

<FormModal
  open={open}
  onCancel={() => setOpen(false)}
  titleConfig={{
    mainTitle: '新建用户',
    icon: <UserOutlined />,
  }}
>
  <ProForm
    submitter={{
      render: () => (
        <FormActions
          submitText="提交"
          resetText="重置"
          submitLoading={loading}
        />
      ),
    }}
  >
    {/* 表单项 */}
  </ProForm>
</FormModal>
```

### 4. 完整的表单弹窗示例

```tsx
import React from 'react';
import { Modal, message } from 'antd';
import { ProForm, ProFormText, ProFormSelect } from '@ant-design/pro-components';
import { FormModal } from '@/components/FormModal';
import { UserOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import * as CryptoJS from 'crypto-js';

interface CreateUserModalProps {
  onCancel: () => void;
  onSubmit: (values: any) => Promise<void>;
  visible: boolean;
}

const CreateUserModal: React.FC<CreateUserModalProps> = (props) => {
  const { onCancel, onSubmit, visible } = props;
  const intl = useIntl();

  const handleFinish = async (values: any) => {
    // 对密码进行前端加密
    const encryptedPassword = values.password 
      ? CryptoJS.SHA256(values.password).toString()
      : values.password;
    
    await onSubmit({ ...values, password: encryptedPassword });
  };

  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      titleConfig={{
        mainTitle: intl.formatMessage({
          id: 'pages.user.management.add',
          defaultMessage: '新建用户',
        }),
        subtitle: intl.formatMessage({
          id: 'pages.user.management.add.subtitle',
          defaultMessage: '填写用户基本信息，创建系统账号',
        }),
        icon: <UserOutlined />,
      }}
    >
      <ProForm
        onFinish={handleFinish}
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
        submitter={{
          render: (_, dom) => (
            <div style={{ 
              display: 'flex', 
              justifyContent: 'flex-end', 
              gap: '12px',
              marginTop: '24px',
              paddingTop: '20px',
              borderTop: '1px solid var(--vip-border)'
            }}>
              {dom.map((item: any) => 
                React.cloneElement(item, {
                  style: {
                    fontSize: '13px',
                    fontWeight: 500,
                    height: '36px',
                    padding: '6px 24px',
                    borderRadius: '6px',
                    ...(item.props.style || {})
                  }
                })
              )}
            </div>
          ),
          searchConfig: {
            submitText: intl.formatMessage({
              id: 'pages.user.management.submit',
              defaultMessage: '提交',
            }),
            resetText: intl.formatMessage({
              id: 'pages.user.management.reset',
              defaultMessage: '重置',
            }),
          },
        }}
      >
        <ProFormText
          name="username"
          label={intl.formatMessage({
            id: 'pages.user.management.username',
            defaultMessage: '用户名',
          })}
          placeholder={intl.formatMessage({
            id: 'pages.user.management.username.placeholder',
            defaultMessage: '请输入用户名',
          })}
          rules={[{ required: true, message: '请输入用户名' }]}
        />

        <ProFormText.Password
          name="password"
          label={intl.formatMessage({
            id: 'pages.user.management.password',
            defaultMessage: '密码',
          })}
          placeholder={intl.formatMessage({
            id: 'pages.user.management.password.placeholder',
            defaultMessage: '请输入密码',
          })}
          rules={[{ required: true, message: '请输入密码' }]}
        />

        <ProFormSelect
          name="gender"
          label={intl.formatMessage({
            id: 'pages.user.management.gender',
            defaultMessage: '性别',
          })}
          options={[
            { label: '男', value: 1 },
            { label: '女', value: 0 },
          ]}
        />
      </ProForm>
    </FormModal>
  );
};

export default CreateUserModal;
```

## 🎯 API

### FormModal

| 属性 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| open | 是否显示弹窗 | `boolean` | - |
| onCancel | 取消回调 | `() => void` | - |
| titleConfig | 标题配置 | `FormModalTitleConfig` | - |
| width | 弹窗宽度 | `number \| string` | `680` |
| children | 表单内容 | `React.ReactNode` | - |

### FormModalTitleConfig

| 属性 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| mainTitle | 主标题 | `React.ReactNode` | - |
| subtitle | 副标题（可选） | `React.ReactNode` | - |
| icon | 图标组件 | `React.ReactNode` | - |
| iconGradient | 图标背景渐变色 | `string` | `linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)` |
| iconShadowColor | 图标阴影色 | `string` | `rgba(var(--vip-primary-rgb), 0.25)` |

### FormActions

| 属性 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| submitText | 提交按钮文本 | `string` | `'提交'` |
| resetText | 重置按钮文本 | `string` | `'重置'` |
| submitButtonType | 提交按钮类型 | `'primary' \| 'default' \| 'dashed' \| 'link' \| 'text'` | `'primary'` |
| showReset | 是否显示重置按钮 | `boolean` | `true` |
| submitLoading | 提交按钮加载状态 | `boolean` | `false` |
| extraButtons | 额外的操作按钮 | `React.ReactNode` | - |

## 🎨 样式定制

### 使用 CSS 变量

所有样式都使用 CSS 变量，可以通过覆盖变量来自定义：

```less
// 自定义主色调
:root {
  --vip-primary: #4f6ef7;
  --vip-primary-hover: #3d5ce5;
  --vip-bg-container: #ffffff;
  --vip-border: #e8eaf2;
}

// 深色主题
html.dark {
  --vip-primary: #5c7cff;
  --vip-bg-container: #1a1d26;
  --vip-border: #2a2e3a;
}
```

### 覆盖局部样式

```less
// 自定义弹窗宽度
.my-custom-modal {
  :global {
    .form-modal {
      width: 800px !important;
    }
  }
}
```

## ✅ 最佳实践

1. **统一使用 FormModal**: 所有新建/编辑弹窗都应使用此组件
2. **国际化支持**: 标题和按钮文本都应使用 `intl.formatMessage`
3. **图标选择**: 根据操作类型选择合适的图标（新建用 UserOutlined，编辑用 EditOutlined）
4. **渐变色**: 新建用主色，编辑用警告色，删除用错误色
5. **表单布局**: 推荐使用 `layout="horizontal"` 和 `labelCol={{ span: 6 }}`

## 📝 迁移指南

### 从旧样式迁移

**旧代码**:
```tsx
<Modal
  title="新建用户"
  open={visible}
  onCancel={onCancel}
  styles={{
    body: { padding: '28px 32px' },
    header: { padding: '20px 28px' },
  }}
>
  {/* 表单内容 */}
</Modal>
```

**新代码**:
```tsx
<FormModal
  open={visible}
  onCancel={onCancel}
  titleConfig={{
    mainTitle: '新建用户',
    subtitle: '填写用户基本信息',
    icon: <UserOutlined />,
  }}
>
  {/* 表单内容 */}
</FormModal>
```

## 🌟 示例截图

组件会自动适配浅色和深色主题，保持一致的视觉效果。
