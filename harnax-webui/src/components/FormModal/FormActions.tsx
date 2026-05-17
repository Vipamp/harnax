import React from 'react';
import { Button } from 'antd';
import { useIntl } from '@umijs/max';
import './FormModal.less';

/**
 * 表单操作按钮属性
 */
export interface FormActionsProps {
  /** 额外的操作按钮（可选） */
  extraButtons?: React.ReactNode;
  /** 提交按钮文本 */
  submitText?: string;
  /** 重置按钮文本 */
  resetText?: string;
  /** 提交按钮类型 */
  submitButtonType?: 'primary' | 'default' | 'dashed' | 'link' | 'text';
  /** 是否显示重置按钮 */
  showReset?: boolean;
  /** 提交按钮加载状态 */
  submitLoading?: boolean;
}

/**
 * FormActions - 表单操作按钮组件
 * 提供统一的提交、重置按钮样式
 */
const FormActions: React.FC<FormActionsProps> = (props) => {
  const {
    extraButtons,
    submitText,
    resetText,
    submitButtonType = 'primary',
    showReset = true,
    submitLoading = false,
  } = props;

  const intl = useIntl();

  const defaultSubmitText = submitText || intl.formatMessage({
    id: 'pages.common.submit',
    defaultMessage: '提交',
  });

  const defaultResetText = resetText || intl.formatMessage({
    id: 'pages.common.reset',
    defaultMessage: '重置',
  });

  return (
    <div className="form-modal-actions">
      {extraButtons}
      {showReset && (
        <Button size="middle">
          {defaultResetText}
        </Button>
      )}
      <Button
        type={submitButtonType}
        loading={submitLoading}
        htmlType="submit"
        size="middle"
      >
        {defaultSubmitText}
      </Button>
    </div>
  );
};

export default FormActions;
