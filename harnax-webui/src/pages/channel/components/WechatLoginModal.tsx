import React, { useState, useEffect, useRef } from 'react';
import { useIntl } from '@umijs/max';
import { Modal, Spin, Result, Button, Typography } from 'antd';
import { WechatOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  startWechatLogin,
  getWechatLoginStatus,
  cancelWechatLogin,
} from '@/services/ant-design-pro/channel';

const { Text } = Typography;

interface WechatLoginModalProps {
  visible: boolean;
  channelId?: number;
  onCancel: () => void;
  onSuccess: () => void;
}

type LoginPhase = 'loading' | 'waiting' | 'scanned' | 'success' | 'expired' | 'error';

const POLL_INTERVAL_MS = 2000;

/**
 * WeChat iLink QR-code login modal.
 *
 * Flow: request QR (base64 PNG) → render → poll status every 2s until
 * LOGGED_IN / EXPIRED / ERROR. On success, notify parent to refresh the list;
 * credentials are persisted server-side into the channel configJson.
 */
const WechatLoginModal: React.FC<WechatLoginModalProps> = ({
  visible,
  channelId,
  onCancel,
  onSuccess,
}) => {
  const intl = useIntl();
  const [phase, setPhase] = useState<LoginPhase>('loading');
  const [qrImage, setQrImage] = useState<string>('');
  const [errorMsg, setErrorMsg] = useState<string>('');
  const pollTimerRef = useRef<NodeJS.Timeout | null>(null);
  const beginningRef = useRef<boolean>(false);

  const clearPoll = () => {
    if (pollTimerRef.current) {
      clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  };

  const beginLogin = async () => {
    if (!channelId) return;
    if (beginningRef.current) return;
    beginningRef.current = true;
    clearPoll();
    setPhase('loading');
    setQrImage('');
    setErrorMsg('');
    try {
      const res = await startWechatLogin(channelId);
      if (res.code === 200 && res.data) {
        setQrImage(res.data);
        setPhase('waiting');
        startPolling();
      } else {
        setPhase('error');
        setErrorMsg(res.message || intl.formatMessage({ id: 'pages.channel.wechat.qrFailed', defaultMessage: 'Failed to generate QR code' }));
      }
    } catch (e: any) {
      setPhase('error');
      setErrorMsg(e?.message || intl.formatMessage({ id: 'pages.channel.wechat.qrFailed', defaultMessage: 'Failed to generate QR code' }));
    } finally {
      beginningRef.current = false;
    }
  };

  const startPolling = () => {
    clearPoll();
    pollTimerRef.current = setInterval(async () => {
      if (!channelId) return;
      try {
        const res = await getWechatLoginStatus(channelId);
        if (res.code !== 200 || !res.data) return;
        const status = res.data.status as string;
        if (status === 'SCANNED') {
          setPhase('scanned');
        } else if (status === 'LOGGED_IN') {
          clearPoll();
          setPhase('success');
          setTimeout(() => onSuccess(), 800);
        } else if (status === 'EXPIRED' || status === 'NOT_LOGIN') {
          clearPoll();
          setPhase('expired');
        } else if (status === 'ERROR') {
          clearPoll();
          setPhase('error');
          setErrorMsg(res.data.message || intl.formatMessage({ id: 'pages.channel.wechat.loginError', defaultMessage: 'Login failed' }));
        }
      } catch {
        // transient poll error, keep polling
      }
    }, POLL_INTERVAL_MS);
  };

  const handleClose = () => {
    clearPoll();
    if (channelId) {
      cancelWechatLogin(channelId).catch(() => {});
    }
    onCancel();
  };

  useEffect(() => {
    if (visible && channelId) {
      beginLogin();
    }
    return () => clearPoll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, channelId]);

  const renderBody = () => {
    switch (phase) {
      case 'loading':
        return (
          <div style={{ textAlign: 'center', padding: '48px 0' }}>
            <Spin size="large" />
            <div style={{ marginTop: 16 }}>
              <Text type="secondary">{intl.formatMessage({ id: 'pages.channel.wechat.generating', defaultMessage: 'Generating QR code...' })}</Text>
            </div>
          </div>
        );
      case 'waiting':
      case 'scanned':
        return (
          <div style={{ textAlign: 'center', padding: '16px 0' }}>
            <img
              src={qrImage}
              alt="WeChat QR"
              style={{ width: 240, height: 240, border: '1px solid var(--vip-border)', borderRadius: 8 }}
            />
            <div style={{ marginTop: 16 }}>
              <Text strong>
                {phase === 'scanned'
                  ? intl.formatMessage({ id: 'pages.channel.wechat.scanned', defaultMessage: 'Scanned, please confirm on your phone' })
                  : intl.formatMessage({ id: 'pages.channel.wechat.scanPrompt', defaultMessage: 'Open WeChat and scan the QR code to log in' })}
              </Text>
            </div>
          </div>
        );
      case 'success':
        return (
          <Result
            status="success"
            title={intl.formatMessage({ id: 'pages.channel.wechat.loginSuccess', defaultMessage: 'Login successful' })}
            subTitle={intl.formatMessage({ id: 'pages.channel.wechat.loginSuccessHint', defaultMessage: 'Credentials saved. The channel will connect automatically.' })}
          />
        );
      case 'expired':
        return (
          <Result
            status="warning"
            title={intl.formatMessage({ id: 'pages.channel.wechat.expired', defaultMessage: 'QR code expired' })}
            extra={
              <Button type="primary" icon={<ReloadOutlined />} onClick={beginLogin}>
                {intl.formatMessage({ id: 'pages.channel.wechat.refresh', defaultMessage: 'Refresh QR code' })}
              </Button>
            }
          />
        );
      case 'error':
      default:
        return (
          <Result
            status="error"
            title={intl.formatMessage({ id: 'pages.channel.wechat.loginError', defaultMessage: 'Login failed' })}
            subTitle={errorMsg}
            extra={
              <Button type="primary" icon={<ReloadOutlined />} onClick={beginLogin}>
                {intl.formatMessage({ id: 'pages.channel.wechat.retry', defaultMessage: 'Retry' })}
              </Button>
            }
          />
        );
    }
  };

  return (
    <Modal
      open={visible}
      onCancel={handleClose}
      footer={null}
      destroyOnClose
      width={420}
      title={
        <span>
          <WechatOutlined style={{ color: '#07C160', marginRight: 8 }} />
          {intl.formatMessage({ id: 'pages.channel.wechat.title', defaultMessage: 'WeChat Scan Login' })}
        </span>
      }
    >
      {renderBody()}
    </Modal>
  );
};

export default WechatLoginModal;
