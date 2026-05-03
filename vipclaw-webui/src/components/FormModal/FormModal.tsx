import React from 'react';
import { Modal } from 'antd';
import type { ModalProps } from 'antd';
import './FormModal.less';

/**
 * 弹窗尺寸预设
 */
export type FormModalSize = 'sm' | 'md' | 'lg' | 'xl';

/**
 * 尺寸与宽度映射
 */
const SIZE_MAP: Record<FormModalSize, number> = {
  sm: 480,
  md: 620,
  lg: 760,
  xl: 900,
};

/**
 * 弹窗标题配置
 */
export interface FormModalTitleConfig {
  /** 主标题 */
  mainTitle: React.ReactNode;
  /** 副标题（可选） */
  subtitle?: React.ReactNode;
  /** 图标（Ant Design Icon 组件） */
  icon: React.ReactNode;
  /** 图标背景渐变色 */
  iconGradient?: string;
  /** 图标阴影色 */
  iconShadowColor?: string;
}

/**
 * 表单弹窗属性
 */
export interface FormModalProps extends Omit<ModalProps, 'title'> {
  /** 标题配置 */
  titleConfig: FormModalTitleConfig;
  /** 是否显示弹窗 */
  open: boolean;
  /** 取消回调 */
  onCancel: () => void;
  /** 弹窗尺寸预设 */
  size?: FormModalSize;
  /** 弹窗宽度（覆盖 size 预设） */
  width?: number | string;
  /** 子元素（表单内容） */
  children: React.ReactNode;
}

/**
 * FormModal - 统一的表单弹窗组件
 * 提供标准化的标题、样式和布局
 */
const FormModal: React.FC<FormModalProps> = (props) => {
  const {
    titleConfig,
    open,
    onCancel,
    size = 'md',
    width,
    children,
    ...modalProps
  } = props;

  const {
    mainTitle,
    subtitle,
    icon,
    iconGradient = 'linear-gradient(135deg, var(--vip-primary) 0%, var(--vip-primary-light) 100%)',
    iconShadowColor = 'rgba(var(--vip-primary-rgb), 0.25)',
  } = titleConfig;

  // 使用传入的 width 覆盖 size 预设，默认使用 md
  const finalWidth = width ?? SIZE_MAP[size ?? 'md'];

  return (
    <Modal
      className="form-modal"
      open={open}
      onCancel={onCancel}
      width={finalWidth}
      footer={null}
      centered
      maskClosable={false}
      destroyOnClose
      styles={{
        header: {
          background: 'transparent',
          borderBottom: '1px solid var(--vip-border)',
          padding: '16px 24px',
          borderRadius: '12px 12px 0 0',
        },
        body: {
          padding: '20px 24px',
          background: 'var(--vip-bg-container)',
          borderRadius: '0 0 12px 12px',
        },
        mask: {
          backdropFilter: 'blur(4px)',
        },
      }}
      title={
        <div className="form-modal-title">
          <div
            className="form-modal-icon"
            style={{
              background: iconGradient,
              boxShadow: `0 4px 12px ${iconShadowColor}`,
            }}
          >
            {icon}
          </div>
          <div className="form-modal-title-text">
            <div className="form-modal-title-main">{mainTitle}</div>
            {subtitle && (
              <div className="form-modal-title-subtitle">{subtitle}</div>
            )}
          </div>
        </div>
      }
      {...modalProps}
    >
      {children}
    </Modal>
  );
};

export default FormModal;
