import {
  LockOutlined,
  MobileOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {
  LoginForm,
  ProFormCaptcha,
  ProFormCheckbox,
  ProFormText,
} from '@ant-design/pro-components';
import { FormattedMessage, Helmet, SelectLang, useIntl, useModel, history } from '@umijs/max';
import { Alert, Image, Spin, Tabs, message } from 'antd';
import { createStyles } from 'antd-style';
import React, { useState } from 'react';
import { flushSync } from 'react-dom';
import { Footer } from '@/components';
import { login as loginApi, getCaptcha } from '@/services/ant-design-pro/login';
import Settings from '../../../../config/defaultSettings';
import CryptoJS from 'crypto-js';

const useStyles = createStyles(({ token }) => {
  return {
    action: {
      marginLeft: '8px',
      color: 'rgba(0, 0, 0, 0.2)',
      fontSize: '24px',
      verticalAlign: 'middle',
      cursor: 'pointer',
      transition: 'color 0.3s',
      '&:hover': {
        color: token.colorPrimaryActive,
      },
    },
    lang: {
      width: 42,
      height: 42,
      lineHeight: '42px',
      position: 'fixed',
      right: 16,
      top: 16,
      borderRadius: token.borderRadius,
      zIndex: 100,
      ':hover': {
        backgroundColor: token.colorBgTextHover,
      },
    },
    container: {
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      justifyContent: 'center',
      minHeight: '100vh',
      background: 'linear-gradient(135deg, #1a1a2e 0%, #16213e 30%, #0f3460 60%, #4f6ef7 100%)',
      position: 'relative',
      overflow: 'hidden',
      padding: '24px',
    },
    decorativeCircle1: {
      position: 'absolute',
      width: 600,
      height: 600,
      borderRadius: '50%',
      background: 'radial-gradient(circle, rgba(79,110,247,0.15) 0%, transparent 70%)',
      top: '-200px',
      right: '-100px',
      pointerEvents: 'none',
    },
    decorativeCircle2: {
      position: 'absolute',
      width: 500,
      height: 500,
      borderRadius: '50%',
      background: 'radial-gradient(circle, rgba(102,126,234,0.12) 0%, transparent 70%)',
      bottom: '-150px',
      left: '-100px',
      pointerEvents: 'none',
    },
    decorativeCircle3: {
      position: 'absolute',
      width: 300,
      height: 300,
      borderRadius: '50%',
      background: 'radial-gradient(circle, rgba(79,110,247,0.1) 0%, transparent 70%)',
      top: '50%',
      left: '10%',
      transform: 'translateY(-50%)',
      pointerEvents: 'none',
    },
    loginCard: {
      width: '100%',
      maxWidth: '480px',
      position: 'relative',
      zIndex: 10,
    },
    loginCardInner: {
      background: 'rgba(255, 255, 255, 0.95)',
      backdropFilter: 'blur(20px)',
      borderRadius: '24px',
      boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.25), 0 0 0 1px rgba(255, 255, 255, 0.1)',
      padding: '48px 40px',
      '@media (max-width: 480px)': {
        padding: '32px 24px',
        borderRadius: '20px',
      },
    },
    logoSection: {
      textAlign: 'center',
      marginBottom: 32,
    },
    logoIcon: {
      width: 72,
      height: 72,
      borderRadius: '20px',
      background: 'linear-gradient(135deg, #4f6ef7 0%, #667eea 100%)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      margin: '0 auto 20px',
      fontSize: '36px',
      boxShadow: '0 10px 30px rgba(79, 110, 247, 0.3)',
    },
    logoTitle: {
      fontSize: '32px',
      fontWeight: 700,
      color: '#1a1a2e',
      margin: '0 0 8px',
      letterSpacing: '-0.5px',
    },
    logoSubtitle: {
      fontSize: '15px',
      color: '#888',
      margin: 0,
    },
    features: {
      display: 'flex',
      gap: '12px',
      justifyContent: 'center',
      marginTop: 24,
      flexWrap: 'wrap',
      '@media (max-width: 480px)': {
        gap: '8px',
      },
    },
    featureTag: {
      padding: '8px 16px',
      background: 'linear-gradient(135deg, rgba(79,110,247,0.08) 0%, rgba(102,126,234,0.08) 100%)',
      borderRadius: '20px',
      fontSize: '13px',
      color: '#4f6ef7',
      fontWeight: 500,
      border: '1px solid rgba(79,110,247,0.15)',
    },
    footerWrapper: {
      position: 'absolute',
      bottom: 24,
      left: 0,
      right: 0,
      zIndex: 10,
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
 const [userLoginState, setUserLoginState] = useState<API.LoginResult>({});
  const [type, setType] = useState<string>('account');
  const { initialState, setInitialState } = useModel('@@initialState');
  const { styles } = useStyles();
  const intl = useIntl();

  // 验证码相关状态
  const [captchaImage, setCaptchaImage] = useState<string>('');
  const [captchaKey, setCaptchaKey] = useState<string>('');
  const [loadingCaptcha, setLoadingCaptcha] = useState<boolean>(false);

  // 检查用户是否已登录，如果已登录则跳转到欢迎页
  React.useEffect(() => {
    // 只有当 localStorage 中有有效的 token 且 currentUser 存在时，才认为是已登录状态
    const tokenInfoStr = localStorage.getItem('tokenInfo');
    const hasValidToken = tokenInfoStr && (() => {
      try {
        const tokenInfo = JSON.parse(tokenInfoStr);
        // 检查 token 是否过期
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
    // 对密码进行前端加密（SHA-256）
    const encryptedPassword = values.password 
      ? CryptoJS.SHA256(values.password).toString()
      : values.password;
    
    // 登录
   // 提交时包含 captchaKey，后端会校验验证码
 const msg = await loginApi({ ...values, password: encryptedPassword, type, captchaKey });
  if (msg.code === 200 && msg.data) {
    const defaultLoginSuccessMessage = intl.formatMessage({
     id: 'pages.login.success',
      defaultMessage: '登录成功！',
       });
   message.success(defaultLoginSuccessMessage);
     
      // 直接从登录响应中提取用户信息并保存
 const userInfo = msg.data.userInfo;
 console.log('[登录成功] 后端返回的用户信息:', userInfo);
 if (userInfo) {
 const currentUser= {
  userId: userInfo.userId,
  username: userInfo.username,
  nickname: userInfo.nickname,
  avatar: userInfo.avatar,
  email: userInfo.email,
 phone: userInfo.phone,
 gender: userInfo.gender,
 isAdmin: userInfo.isAdmin,
};
  console.log('[登录成功] 保存到 localStorage 的 currentUser:', currentUser);
  console.log('[登录成功] isAdmin 值:', currentUser.isAdmin, '类型:', typeof currentUser.isAdmin);
  
 // 保存到 React 状态
 flushSync(() => {
 setInitialState((s) => ({
   ...s,
 currentUser,
 }));
 });
 
 // 保存到 localStorage 以便刷新后恢复
 localStorage.setItem('currentUser', JSON.stringify(currentUser));
 }
  
 // 保存 token 信息到 localStorage（用于后续请求认证）
 if (msg.data.accessToken) {
 const tokenInfo = {
 accessToken: msg.data.accessToken,
  tokenType: msg.data.tokenType || 'Bearer',
 expiresIn: msg.data.expiresIn,
 expiresAt: msg.data.expiresAt,
};
 localStorage.setItem('tokenInfo', JSON.stringify(tokenInfo));
 console.log('[登录成功] Token 已保存:', tokenInfo);
 
 // 确保 token 已经持久化到 localStorage
 try {
  const savedToken = localStorage.getItem('tokenInfo');
  if (savedToken) {
   console.log('[登录成功] Token 验证成功，可以开始请求');
  } else {
   console.error('[登录成功] Token 保存失败！');
  }
 } catch (e) {
  console.error('[登录成功] 验证 Token 失败:', e);
 }
}
    
// 使用 history.push 进行跳转，避免页面刷新导致 token 丢失
const urlParams = new URL(window.location.href).searchParams;
const redirect = urlParams.get('redirect');
if (redirect) {
 // 给一个短暂的延迟，确保 localStorage 已经完全写入
 setTimeout(() => {
  console.log('[登录成功] 准备跳转到 redirect:', redirect);
  history.push(redirect);
 }, 100);
} else {
 setTimeout(() => {
  console.log('[登录成功] 准备跳转到 /welcome');
  history.push('/welcome');
 }, 100);
}
return;
    }
   console.log(msg);
     // 如果失败去设置用户错误信息
  setUserLoginState(msg);
   } catch (error) {
      const defaultLoginFailureMessage = intl.formatMessage({
        id: 'pages.login.failure',
        defaultMessage: '登录失败，请重试！',
      });
      console.log(error);
      message.error(defaultLoginFailureMessage);
    }
  };
  const { status, type: loginType } = userLoginState;

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

      {/* 背景装饰元素 */}
      <div className={styles.decorativeCircle1} />
      <div className={styles.decorativeCircle2} />
      <div className={styles.decorativeCircle3} />

      {/* 登录卡片 */}
      <div className={styles.loginCard}>
        <div className={styles.loginCardInner}>
          {/* Logo 区域 */}
          <div className={styles.logoSection}>
            <div className={styles.logoIcon}>🐾</div>
            <h1 className={styles.logoTitle}>VipClaw</h1>
            <p className={styles.logoSubtitle}>智能体平台 · 一站式解决方案</p>
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
                submitText: '登录',
              },
              submitButtonProps: {
                size: 'large',
                style: {
                  width: '100%',
                  height: '50px',
                  fontSize: '16px',
                  fontWeight: 600,
                  borderRadius: '12px',
                  background: 'linear-gradient(135deg, #4f6ef7 0%, #667eea 100%)',
                  border: 'none',
                  boxShadow: '0 8px 24px rgba(79, 110, 247, 0.35)',
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
                  label: intl.formatMessage({
                    id: 'pages.login.accountLogin.tab',
                    defaultMessage: '账户密码登录',
                  }),
                },
                {
                  key: 'mobile',
                  label: intl.formatMessage({
                    id: 'pages.login.phoneLogin.tab',
                    defaultMessage: '手机号登录',
                  }),
                },
              ]}
            />

            {status === 'error' && loginType === 'account' && (
              <LoginMessage
                content={intl.formatMessage({
                  id: 'pages.login.accountLogin.errorMessage',
                  defaultMessage: '账户或密码错误(admin/ant.design)',
                })}
              />
            )}
            {type === 'account' && (
              <>
                <ProFormText
                name="username"
                  fieldProps={{
                   size: 'large',
                 prefix: <UserOutlined />,
                  }}
                 placeholder={intl.formatMessage({
                 id: 'pages.login.username.placeholder',
                 defaultMessage: '用户名：admin or user',
                 })}
                 rules={[
                   {
                   required: true,
                   message: (
                       <FormattedMessage
                       id="pages.login.username.required"
                       defaultMessage="请输入用户名!"
                       />
                     ),
                   },
                 ]}
               />
               <ProFormText.Password
               name="password"
                 fieldProps={{
                  size: 'large',
                prefix: <LockOutlined />,
                 }}
                 placeholder={intl.formatMessage({
                 id: 'pages.login.password.placeholder',
                 defaultMessage: '密码：ant.design',
                 })}
                 rules={[
                   {
                   required: true,
                   message: (
                       <FormattedMessage
                       id="pages.login.password.required"
                       defaultMessage="请输入密码！"
                       />
                     ),
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
                 placeholder={intl.formatMessage({
                 id: 'pages.login.captcha.placeholder',
                 defaultMessage: '请输入验证码',
                 })}
                 rules={[
                   {
                   required: true,
                   message: (
                       <FormattedMessage
                       id="pages.login.captcha.required"
                       defaultMessage="请输入验证码！"
                       />
                     ),
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
                 border: '1px solid #e0e4f4',
                 transition: 'box-shadow 0.2s',
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

          {status === 'error' && loginType === 'mobile' && (
            <LoginMessage content="验证码错误" />
          )}
          {type === 'mobile' && (
            <>
              <ProFormText
                fieldProps={{
                  size: 'large',
                  prefix: <MobileOutlined />,
                }}
                name="mobile"
                placeholder={intl.formatMessage({
                  id: 'pages.login.phoneNumber.placeholder',
                  defaultMessage: '手机号',
                })}
                rules={[
                  {
                    required: true,
                    message: (
                      <FormattedMessage
                        id="pages.login.phoneNumber.required"
                        defaultMessage="请输入手机号！"
                      />
                    ),
                  },
                  {
                    pattern: /^1\d{10}$/,
                    message: (
                      <FormattedMessage
                        id="pages.login.phoneNumber.invalid"
                        defaultMessage="手机号格式错误！"
                      />
                    ),
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
               placeholder={intl.formatMessage({
               id: 'pages.login.captcha.placeholder',
               defaultMessage: '请输入验证码',
               })}
               captchaTextRender={(timing, count) => {
                 if (timing) {
                  return `${count} ${intl.formatMessage({
                    id: 'pages.getCaptchaSecondText',
                    defaultMessage: '获取验证码',
                   })}`;
                 }
                return intl.formatMessage({
                  id: 'pages.login.phoneLogin.getVerificationCode',
                  defaultMessage: '获取验证码',
                 });
               }}
              name="captcha"
               rules={[
                 {
                 required: true,
                 message: (
                     <FormattedMessage
                     id="pages.login.captcha.required"
                     defaultMessage="请输入验证码！"
                     />
                   ),
                 },
               ]}
          onGetCaptcha={async (phone) => {
          const result = await getCaptcha();
              if (!result || result.code !== 200) {
           message.error('获取验证码失败');
           return;
              }
              // 保存验证码 key（实际项目中应该验证）
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
              <FormattedMessage
                id="pages.login.rememberMe"
                defaultMessage="自动登录"
              />
            </ProFormCheckbox>
            <a style={{ color: '#4f6ef7', fontWeight: 500 }}>
              <FormattedMessage
                id="pages.login.forgotPassword"
                defaultMessage="忘记密码"
              />
            </a>
          </div>
          </LoginForm>

          {/* 功能标签 */}
          <div className={styles.features}>
            <span className={styles.featureTag}>🤖 多模型集成</span>
            <span className={styles.featureTag}>🛠️ 工具平台</span>
            <span className={styles.featureTag}>⚡ 高效协同</span>
          </div>
        </div>
      </div>

      {/* 页脚 */}
      <div className={styles.footerWrapper}>
        <Footer />
      </div>
    </div>
  );
};

export default Login;
