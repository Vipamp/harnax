import {
  LockOutlined,
  MobileOutlined,
  UserOutlined,
  GithubOutlined,
} from '@ant-design/icons';
import {
  LoginForm,
  ProFormCaptcha,
  ProFormCheckbox,
  ProFormText,
} from '@ant-design/pro-components';
import { FormattedMessage, Helmet, SelectLang, useIntl, useModel, history } from '@umijs/max';
import { Alert, Image, Spin, Tabs, message, Button, Divider } from 'antd';
import { createStyles } from 'antd-style';
import React, { useState } from 'react';
import { flushSync } from 'react-dom';
import { Footer } from '@/components';
import { login as loginApi, getCaptcha } from '@/services/ant-design-pro/login';
import Settings from '../../../../config/defaultSettings';
import CryptoJS from 'crypto-js';
import { isPersonal, isPublic, FEATURES } from '@/utils/edition';

const useStyles = createStyles(({ token }) => {
  return {
    lang: {
      width: 42,
      height: 42,
      lineHeight: '42px',
      position: 'fixed',
      right: 16,
      top: 16,
      borderRadius: token.borderRadius,
      zIndex: 1000,
      ':hover': {
        backgroundColor: token.colorBgTextHover,
      },
    },
    // 整体容器 - 左右分栏布局
    container: {
      display: 'flex',
      minHeight: '100vh',
      position: 'relative',
      overflow: 'hidden',
    },
    // 左侧品牌展示区
    leftPanel: {
      flex: 1,
      background: 'linear-gradient(135deg, #f0f7ff 0%, #f0fdfa 50%, #e0f2fe 100%)',
      position: 'relative',
      display: 'flex',
      flexDirection: 'column',
      justifyContent: 'space-between',
      padding: '60px',
      overflow: 'hidden',
    },
    // 右侧登录操作区
    rightPanel: {
      flex: 1,
      background: '#ffffff',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: '40px',
      position: 'relative',
    },
    // 粒子背景
    particleBackground: {
      position: 'absolute',
      top: 0,
      left: 0,
      right: 0,
      bottom: 0,
      pointerEvents: 'none',
      overflow: 'hidden',
    },
    // Logo 区域
    logoArea: {
      position: 'relative',
      zIndex: 10,
    },
    logoIcon: {
      width: 56,
      height: 56,
      borderRadius: '16px',
      background: 'linear-gradient(135deg, #1677ff 0%, #36cbcb 100%)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontSize: '28px',
      marginBottom: '16px',
      boxShadow: '0 8px 24px rgba(22, 119, 255, 0.25)',
    },
    logoTitle: {
      fontSize: '28px',
      fontWeight: 700,
      color: '#1f2937',
      margin: '0 0 8px',
      letterSpacing: '-0.5px',
    },
    logoSubtitle: {
      fontSize: '14px',
      color: '#6b7280',
      margin: 0,
      fontWeight: 500,
    },
    // 中间视觉区域
    visualArea: {
      position: 'relative',
      zIndex: 10,
      flex: 1,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
    },
    visualIllustration: {
      width: '100%',
      maxWidth: '500px',
      height: 'auto',
    },
    // 底部 Slogan 区域
    bottomArea: {
      position: 'relative',
      zIndex: 10,
    },
    slogan: {
      fontSize: '20px',
      fontWeight: 600,
      color: '#1f2937',
      margin: '0 0 12px',
      lineHeight: 1.4,
    },
    description: {
      fontSize: '14px',
      color: '#6b7280',
      margin: '0 0 24px',
      lineHeight: 1.7,
    },
    openSourceBadge: {
      display: 'inline-flex',
      alignItems: 'center',
      gap: '8px',
      padding: '8px 16px',
      background: 'rgba(255, 255, 255, 0.8)',
      backdropFilter: 'blur(10px)',
      borderRadius: '20px',
      fontSize: '13px',
      color: '#6b7280',
      border: '1px solid rgba(229, 231, 235, 0.5)',
    },
    // 登录卡片
    loginCard: {
      width: '100%',
      maxWidth: '420px',
    },
    loginHeader: {
      textAlign: 'center',
      marginBottom: '32px',
    },
    loginTitle: {
      fontSize: '28px',
      fontWeight: 700,
      color: '#1f2937',
      margin: '0 0 8px',
    },
    loginSubtitle: {
      fontSize: '14px',
      color: '#6b7280',
      margin: 0,
    },
    features: {
      display: 'flex',
      gap: '12px',
      justifyContent: 'center',
      marginTop: 24,
      flexWrap: 'wrap',
    },
    featureTag: {
      padding: '6px 14px',
      background: 'linear-gradient(135deg, rgba(22,119,255,0.06) 0%, rgba(54,203,203,0.06) 100%)',
      borderRadius: '16px',
      fontSize: '12px',
      color: '#1677ff',
      fontWeight: 500,
      border: '1px solid rgba(22,119,255,0.12)',
    },
    thirdPartyLogin: {
      marginTop: 24,
      textAlign: 'center',
    },
    thirdPartyTitle: {
      fontSize: '13px',
      color: '#9ca3af',
      marginBottom: 16,
    },
    thirdPartyIcons: {
      display: 'flex',
      justifyContent: 'center',
      gap: 16,
    },
    thirdPartyIcon: {
      width: 40,
      height: 40,
      borderRadius: '50%',
      background: '#f9fafb',
      border: '1px solid #e5e7eb',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      cursor: 'pointer',
      transition: 'all 0.2s',
      fontSize: '18px',
      color: '#6b7280',
    },
  };
});

const Lang = () => {
  const { styles } = useStyles();

  return (
    <div className={styles.lang} data-lang>
      {SelectLang && <SelectLang />}
    </div>
  );
};

const LoginMessage: React.FC<{
  content: string;
}> = ({ content }) => {
  return (
    <Alert
      style={{
        marginBottom: 24,
      }}
      message={content}
      type="error"
      showIcon
    />
  );
};

const Login: React.FC = () => {
  const [type, setType] = useState<string>('account');
  const { initialState, setInitialState } = useModel('@@initialState');
  const { styles } = useStyles();
  const intl = useIntl();

  // 鼠标位置追踪（用于左侧粒子动画）
  const [mousePosition, setMousePosition] = useState({ x: 0, y: 0 });
  const leftPanelRef = React.useRef<HTMLDivElement>(null);

  // 鼠标移动处理函数 - 左侧面板粒子跟随
  const handleLeftPanelMouseMove = React.useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    if (leftPanelRef.current) {
      const rect = leftPanelRef.current.getBoundingClientRect();
      setMousePosition({ 
        x: e.clientX - rect.left, 
        y: e.clientY - rect.top 
      });
    }
  }, []);

  // 验证码相关状态
  const [captchaImage, setCaptchaImage] = useState<string>('');
  const [captchaKey, setCaptchaKey] = useState<string>('');
  const [loadingCaptcha, setLoadingCaptcha] = useState<boolean>(false);

  // 检查用户是否已登录
  React.useEffect(() => {
    const tokenInfoStr = localStorage.getItem('tokenInfo');
    const hasValidToken = tokenInfoStr && (() => {
      try {
        const tokenInfo = JSON.parse(tokenInfoStr);
        if (tokenInfo.expiresAt && Date.now() < tokenInfo.expiresAt) {
          return true;
        }
        return false;
      } catch (e) {
        return false;
      }
    })();

    if (initialState?.currentUser && hasValidToken) {
     message.success('您已登录，正在跳转到首页...');
     history.push('/welcome');
    }
  }, [initialState?.currentUser, message]);

  const fetchUserInfo = async () => {
    const userInfo = await initialState?.fetchUserInfo?.();
    if (userInfo) {
      flushSync(() => {
        setInitialState((s) => ({
          ...s,
          currentUser: userInfo,
        }));
      });
    }
  };

  // 获取验证码
  const getCaptchaImage = async () => {
    try {
      setLoadingCaptcha(true);
      const result = await getCaptcha();
      if (result.code === 200 && result.data) {
        setCaptchaImage(result.data.imageBase64 || '');
        setCaptchaKey(result.data.captchaKey || '');
      }
    } catch (error) {
      console.error('获取验证码失败:', error);
      message.error('获取验证码失败，请刷新重试');
    } finally {
      setLoadingCaptcha(false);
    }
  };

  // 页面加载时获取验证码
  React.useEffect(() => {
    if (type === 'account') {
      getCaptchaImage();
    }
  }, [type]);

  const handleSubmit = async (values: API.LoginParams) => {
    try {
      const encryptedPassword = values.password 
        ? CryptoJS.SHA256(values.password).toString()
        : values.password;
      
      const msg = await loginApi({ ...values, password: encryptedPassword, type, captchaKey });
  
      if (msg.code === 200 && msg.data) {
        const defaultLoginSuccessMessage = intl.formatMessage({
          id: 'pages.login.success',
          defaultMessage: '登录成功！',
        });
        message.success(defaultLoginSuccessMessage);
        
        const userInfo = msg.data.userInfo;
        if (userInfo) {
          const currentUser = {
            userId: userInfo.userId,
            username: userInfo.username,
            nickname: userInfo.nickname,
            avatar: userInfo.avatar,
            email: userInfo.email,
            phone: userInfo.phone,
            gender: userInfo.gender,
            isAdmin: userInfo.isAdmin,
          };
          
          flushSync(() => {
            setInitialState((s) => ({
              ...s,
              currentUser,
            }));
          });
          
          localStorage.setItem('currentUser', JSON.stringify(currentUser));
        }
  
        if (msg.data.accessToken) {
          const tokenInfo = {
            accessToken: msg.data.accessToken,
            tokenType: msg.data.tokenType || 'Bearer',
            expiresIn: msg.data.expiresIn,
            expiresAt: msg.data.expiresAt,
          };
          localStorage.setItem('tokenInfo', JSON.stringify(tokenInfo));
        }
        
        const urlParams = new URL(window.location.href).searchParams;
        const redirect = urlParams.get('redirect');
        if (redirect) {
          setTimeout(() => {
            history.push(redirect);
          }, 100);
        } else {
          setTimeout(() => {
            history.push('/welcome');
          }, 100);
        }
        return;
      }
      
      const errorMessage = msg.message || intl.formatMessage({
        id: 'pages.login.failure',
        defaultMessage: '登录失败，请重试！',
      });
      message.error(errorMessage);
      if (type === 'account') {
        getCaptchaImage();
      }
    } catch (error: any) {
      const errorMessage = error?.message || error?.response?.data?.message || intl.formatMessage({
        id: 'pages.login.failure',
        defaultMessage: '登录失败，请重试！',
      });
      console.error('[登录失败] 错误信息:', error);
      message.error(errorMessage);
      if (type === 'account') {
        getCaptchaImage();
      }
    }
  };

  return (
    <div className={styles.container}>
      <Helmet>
        <title>
          {intl.formatMessage({
            id: 'menu.login',
            defaultMessage: '登录页',
          })}
          {Settings.title && ` - ${Settings.title}`}
        </title>
      </Helmet>
      <Lang />

      {/* 左侧品牌展示区 */}
      <div 
        className={styles.leftPanel}
        ref={leftPanelRef}
        onMouseMove={handleLeftPanelMouseMove}
      >
        {/* 粒子背景效果 */}
        <div className={styles.particleBackground}>
          {[...Array(15)].map((_, i) => {
            const size = Math.random() * 6 + 3;
            return (
              <div
                key={i}
                style={{
                  position: 'absolute',
                  width: `${size}px`,
                  height: `${size}px`,
                  borderRadius: '50%',
                  background: `rgba(22, 119, 255, ${Math.random() * 0.3 + 0.1})`,
                  left: `${Math.random() * 100}%`,
                  top: `${Math.random() * 100}%`,
                  transition: 'all 0.3s ease-out',
                  transform: `translate(${(mousePosition.x / 10 - Math.random() * 50) * 0.1}px, ${(mousePosition.y / 10 - Math.random() * 50) * 0.1}px)`,
                }}
              />
            );
          })}
        </div>

        {/* 顶部 Logo 区域 */}
        <div className={styles.logoArea}>
          <div className={styles.logoIcon}>🦆</div>
          <h1 className={styles.logoTitle}>OpenDuck</h1>
          <p className={styles.logoSubtitle}>Open Source AI Agent · 开源智能体平台</p>
        </div>

        {/* 中间视觉区域 - SVG 插画 */}
        <div className={styles.visualArea}>
          <svg 
            className={styles.visualIllustration}
            viewBox="0 0 500 400" 
            fill="none" 
            xmlns="http://www.w3.org/2000/svg"
          >
            <circle cx="250" cy="200" r="120" fill="url(#gradient1)" opacity="0.1"/>
            <circle cx="250" cy="200" r="80" fill="url(#gradient1)" opacity="0.15"/>
            
            {/* 对话气泡 */}
            <rect x="180" y="140" width="140" height="80" rx="12" fill="white" stroke="#1677ff" strokeWidth="2"/>
            <circle cx="220" cy="170" r="4" fill="#1677ff"/>
            <circle cx="250" cy="170" r="4" fill="#36cbcb"/>
            <circle cx="280" cy="170" r="4" fill="#1677ff"/>
            <rect x="210" y="185" width="80" height="6" rx="3" fill="#e5e7eb"/>
            <rect x="220" y="195" width="60" height="6" rx="3" fill="#e5e7eb"/>
            
            {/* 代码节点 */}
            <circle cx="150" cy="280" r="15" fill="white" stroke="#1677ff" strokeWidth="2"/>
            <text x="150" y="285" textAnchor="middle" fontSize="12" fill="#1677ff">&lt;/&gt;</text>
            
            <circle cx="350" cy="280" r="15" fill="white" stroke="#36cbcb" strokeWidth="2"/>
            <text x="350" y="285" textAnchor="middle" fontSize="12" fill="#36cbcb">AI</text>
            
            {/* 连接线 */}
            <path d="M165 280 Q250 240 335 280" stroke="#1677ff" strokeWidth="2" fill="none" strokeDasharray="5,5"/>
            
            <defs>
              <linearGradient id="gradient1" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stopColor="#1677ff"/>
                <stop offset="100%" stopColor="#36cbcb"/>
              </linearGradient>
            </defs>
          </svg>
        </div>

        {/* 底部 Slogan 区域 */}
        <div className={styles.bottomArea}>
          <h2 className={styles.slogan}>
            让 AI 自由协作，构建轻量化智能体生态
          </h2>
          <p className={styles.description}>
            OpenDuck 是一款开源可定制 AI 智能体，支持自主决策、多任务执行、插件扩展
          </p>
          <div className={styles.openSourceBadge}>
            <span>🔓</span>
            <span>Open Source · Apache License</span>
            <span style={{ marginLeft: '8px' }}>🐙</span>
          </div>
        </div>
      </div>

      {/* 右侧登录操作区 */}
      <div className={styles.rightPanel}>
        <div className={styles.loginCard}>
          {/* 登录头部 */}
          <div className={styles.loginHeader}>
            <h1 className={styles.loginTitle}>欢迎登录 OpenDuck</h1>
            <p className={styles.loginSubtitle}>登录你的智能体控制台，开始 AI 创作</p>
          </div>

          <LoginForm
            contentStyle={{
              minWidth: 280,
              maxWidth: '100%',
            }}
            logo={null}
            title={null}
            subTitle={null}
            initialValues={{
              autoLogin: true,
            }}
            submitter={{
              searchConfig: {
                submitText: '立即登录',
              },
              submitButtonProps: {
                size: 'large',
                style: {
                  width: '100%',
                  height: '48px',
                  fontSize: '16px',
                  fontWeight: 600,
                  borderRadius: '12px',
                  background: 'linear-gradient(135deg, #1677ff 0%, #36cbcb 100%)',
                  border: 'none',
                  boxShadow: '0 8px 24px rgba(22, 119, 255, 0.3)',
                },
              },
            }}
            onFinish={async (values) => {
              await handleSubmit(values as API.LoginParams);
            }}
          >
            <Tabs
              activeKey={type}
              onChange={setType}
              centered
              size="large"
              items={[
                {
                  key: 'account',
                  label: '账号密码登录',
                },
                ...(FEATURES.phoneLogin ? [{
                  key: 'mobile',
                  label: '验证码登录',
                }] : []),
              ]}
            />

            {type === 'account' && (
              <>
                <ProFormText
                  name="username"
                  fieldProps={{
                    size: 'large',
                    prefix: <UserOutlined />,
                  }}
                  placeholder="请输入用户名 / 邮箱 / 手机号"
                  rules={[
                    {
                      required: true,
                      message: '请输入用户名!',
                    },
                  ]}
                />
                <ProFormText.Password
                  name="password"
                  fieldProps={{
                    size: 'large',
                    prefix: <LockOutlined />,
                  }}
                  placeholder="请输入登录密码"
                  rules={[
                    {
                      required: true,
                      message: '请输入密码！',
                    },
                  ]}
                />
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                  <ProFormText
                    name="captcha"
                    fieldProps={{
                      size: 'large',
                      prefix: <LockOutlined />,
                    }}
                    placeholder="请输入验证码"
                    rules={[
                      {
                        required: true,
                        message: '请输入验证码！',
                      },
                    ]}
                  />
                  <div
                    onClick={getCaptchaImage}
                    style={{
                      cursor: 'pointer',
                      flexShrink: 0,
                      borderRadius: '8px',
                      overflow: 'hidden',
                      border: '1px solid #e5e7eb',
                    }}
                  >
                    {loadingCaptcha ? (
                      <Spin />
                    ) : captchaImage ? (
                      <Image
                        src={captchaImage}
                        preview={false}
                        style={{ height: '42px', display: 'block' }}
                      />
                    ) : null}
                  </div>
                </div>
              </>
            )}

            {type === 'mobile' && (
              <>
                <ProFormText
                  fieldProps={{
                    size: 'large',
                    prefix: <MobileOutlined />,
                  }}
                  name="mobile"
                  placeholder="手机号"
                  rules={[
                    {
                      required: true,
                      message: '请输入手机号！',
                    },
                    {
                      pattern: /^1\d{10}$/,
                      message: '手机号格式错误！',
                    },
                  ]}
                />
                <ProFormCaptcha
                  fieldProps={{
                    size: 'large',
                    prefix: <LockOutlined />,
                  }}
                  captchaProps={{
                    size: 'large',
                  }}
                  placeholder="请输入验证码"
                  captchaTextRender={(timing, count) => {
                    if (timing) {
                      return `${count} 获取验证码`;
                    }
                    return '获取验证码';
                  }}
                  name="captcha"
                  rules={[
                    {
                      required: true,
                      message: '请输入验证码！',
                    },
                  ]}
                  onGetCaptcha={async (phone) => {
                    const result = await getCaptcha();
                    if (!result || result.code !== 200) {
                      message.error('获取验证码失败');
                      return;
                    }
                    setCaptchaKey(result.data?.captchaKey || '');
                    message.success('获取验证码成功！');
                  }}
                />
              </>
            )}
            
            <div
              style={{
                marginBottom: 16,
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}
            >
              <ProFormCheckbox noStyle name="autoLogin">
                记住我
              </ProFormCheckbox>
              <a style={{ color: '#1677ff', fontWeight: 500 }}>
                忘记密码？
              </a>
            </div>
          </LoginForm>

          {/* 功能标签 */}
          <div className={styles.features}>
            <span className={styles.featureTag}>🤖 多模型集成</span>
            <span className={styles.featureTag}>🛠️ 工具平台</span>
            <span className={styles.featureTag}>⚡ 高效协同</span>
          </div>

          {/* 注册跳转 - 仅公网版显示 */}
          {isPublic() && (
            <div style={{ textAlign: 'center', marginTop: 24 }}>
              <span style={{ color: '#6b7280', fontSize: '14px' }}>
                还没有 OpenDuck 账号？
              </span>
              <a style={{ color: '#1677ff', fontWeight: 500, marginLeft: 8 }}>
                立即注册
              </a>
            </div>
          )}

          {/* 第三方登录 */}
          <div className={styles.thirdPartyLogin}>
            <Divider style={{ margin: '24px 0 16px' }}>
              <span className={styles.thirdPartyTitle}>其他登录方式</span>
            </Divider>
            <div className={styles.thirdPartyIcons}>
              <div 
                className={styles.thirdPartyIcon}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = '#f0f7ff';
                  e.currentTarget.style.borderColor = '#1677ff';
                  e.currentTarget.style.color = '#1677ff';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = '#f9fafb';
                  e.currentTarget.style.borderColor = '#e5e7eb';
                  e.currentTarget.style.color = '#6b7280';
                }}
              >
                <GithubOutlined />
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default Login;
