import React from 'react';
import { Button } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

/**
 * 返回按钮组件属性
 */
export interface BackButtonProps {
  /** 点击返回按钮后的回调 */
  onClick: () => void;
  /** 按钮文字 */
  text?: string;
  /** 自定义样式 */
  style?: React.CSSProperties;
}

/**
 * 统一的返回按钮组件
 * 
 * 用于详情页、表单页等需要返回上一页的场景
 * 
 * @example
 * // 基础用法
 * <BackButton onClick={() => history.goBack()} />
 * 
 * @example
 * // 自定义文字
 * <BackButton 
 *   onClick={() => history.goBack()}
 *   text="返回列表"
 * />
 */
const BackButton: React.FC<BackButtonProps> = ({
  onClick,
  text,
  style,
}) => {
  const intl = useIntl();

  const buttonText = text || intl.formatMessage({
    id: 'pages.common.back',
    defaultMessage: 'Back',
  });

  return (
    <Button
      type="text"
      icon={<ArrowLeftOutlined />}
      onClick={onClick}
      style={{
        borderRadius: '8px',
        padding: '8px 12px',
        transition: 'all 0.3s ease',
        fontSize: '14px',
        fontWeight: 'normal',
        ...style,
      }}
    >
      {buttonText}
    </Button>
  );
};

export default BackButton;
