import {
  AlipayCircleOutlined,
  LockOutlined,
  MobileOutlined,
  TaobaoCircleOutlined,
  UserOutlined,
  WeiboCircleOutlined,
} from '@ant-design/icons';
import {
  LoginForm,
  ProFormCaptcha,
  ProFormCheckbox,
  ProFormText,
} from '@ant-design/pro-components';
import { FormattedMessage, Helmet, SelectLang, useIntl, useModel, history } from '@umijs/max';
import { Alert, App, Image, Space, Spin, Tabs } from 'antd';
import { createStyles } from 'antd-style';
import React, { useState } from 'react';
import { flushSync } from 'react-dom';
import { Footer } from '@/components';
import { login as loginApi, getCaptcha } from '@/services/ant-design-pro/login';
import Settings from '../../../../config/defaultSettings';

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
      borderRadius: token.borderRadius,
      ':hover': {
        backgroundColor: token.colorBgTextHover,
      },
    },
    container: {
      display: 'flex',
      flexDirection: 'column',
      height: '100vh',
      overflow: 'auto',
      backgroundImage:
        "url('https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/V-_oS6r-i7wAAAAAAAAAAAAAFl94AQBr')",
      backgroundSize: '100% 100%',
    },
  };
});

const ActionIcons = () => {
  const { styles } = useStyles();

  return (
    <>
      <AlipayCircleOutlined
        key="AlipayCircleOutlined"
        className={styles.action}
      />
      <TaobaoCircleOutlined
        key="TaobaoCircleOutlined"
        className={styles.action}
      />
      <WeiboCircleOutlined
        key="WeiboCircleOutlined"
        className={styles.action}
      />
    </>
  );
};

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
  const { message } = App.useApp();
  const intl = useIntl();

  // 验证码相关状态
  const [captchaImage, setCaptchaImage] = useState<string>('');
  const [captchaKey, setCaptchaKey] = useState<string>('');
  const [loadingCaptcha, setLoadingCaptcha] = useState<boolean>(false);

  // 检查用户是否已登录，如果已登录则跳转到欢迎页
  React.useEffect(() => {
    if (initialState?.currentUser) {
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
    // 登录
   // 提交时包含 captchaKey，后端会校验验证码
 const msg = await loginApi({ ...values, type, captchaKey });
  if (msg.code === 200 && msg.data) {
    const defaultLoginSuccessMessage = intl.formatMessage({
     id: 'pages.login.success',
      defaultMessage: '登录成功！',
       });
   message.success(defaultLoginSuccessMessage);
     
      // 直接从登录响应中提取用户信息并保存
 const userInfo = msg.data.userInfo;
 if (userInfo) {
 const currentUser= {
  userId: userInfo.userId,
  username: userInfo.username,
  nickname: userInfo.nickname,
  avatar: userInfo.avatar,
  email: userInfo.email,
 phone: userInfo.phone,
 gender: userInfo.gender,
};
  
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
 }
     
      // 使用 history 跳转而不是 window.location.href，避免页面刷新丢失状态
  const urlParams = new URL(window.location.href).searchParams;
  const redirect = urlParams.get('redirect');
   if (redirect) {
       window.location.href = redirect;
      } else {
   history.push('/');
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
      <div
        style={{
          flex: '1',
          padding: '32px 0',
        }}
      >
        <LoginForm
          contentStyle={{
            minWidth: 280,
            maxWidth: '75vw',
          }}
          logo={<img alt="logo" src="/logo.svg" />}
          title="Ant Design"
          subTitle={intl.formatMessage({
            id: 'pages.layouts.userLayout.title',
          })}
          initialValues={{
            autoLogin: true,
          }}
          actions={[
            <FormattedMessage
              key="loginWith"
              id="pages.login.loginWith"
              defaultMessage="其他登录方式"
            />,
            <ActionIcons key="icons" />,
          ]}
          onFinish={async (values) => {
            await handleSubmit(values as API.LoginParams);
          }}
        >
          <Tabs
            activeKey={type}
            onChange={setType}
            centered
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
             <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
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
             style={{ cursor: 'pointer', flexShrink: 0 }}
             >
               {loadingCaptcha ? (
                 <Spin />
               ) : captchaImage ? (
                 <Image
                  src={captchaImage}
                 preview={false}
                 style={{ height: '40px', borderRadius: '4px' }}
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
              marginBottom: 24,
            }}
          >
            <ProFormCheckbox noStyle name="autoLogin">
              <FormattedMessage
                id="pages.login.rememberMe"
                defaultMessage="自动登录"
              />
            </ProFormCheckbox>
            <a
              style={{
                float: 'right',
              }}
            >
              <FormattedMessage
                id="pages.login.forgotPassword"
                defaultMessage="忘记密码"
              />
            </a>
          </div>
        </LoginForm>
      </div>
      <Footer />
    </div>
  );
};

export default Login;
