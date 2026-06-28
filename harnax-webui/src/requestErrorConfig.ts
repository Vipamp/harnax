import type { RequestOptions } from '@@/plugin-request/request';
import type { RequestConfig } from '@umijs/max';
import { history } from '@umijs/max';
import { message, notification } from 'antd';

// 错误处理方案： 错误类型
enum ErrorShowType {
  SILENT = 0,
  WARN_MESSAGE = 1,
  ERROR_MESSAGE = 2,
  NOTIFICATION = 3,
  REDIRECT = 9,
}
// 与后端约定的响应数据格式
interface ResponseStructure {
  success?: boolean;
  isSuccess?: boolean;
  code?: number;
  data?: any;
  errorCode?: number;
  errorMessage?: string;
  message?: string;
  showType?: ErrorShowType;
}

/**
 * @name 错误处理
 * pro 自带的错误处理，可以在这里做自己的改动
 * @doc https://umijs.org/docs/max/request#配置
 */
export const errorConfig: RequestConfig = {
 // 默认请求配置
 withCredentials: true, // 允许携带凭证（cookies）
 
 // 请求拦截器 - 添加 JWT Token、X-Tenant-ID 和 Accept-Language
 requestInterceptors: [
 async (url, options) => {
 // 从 localStorage 获取 token
 const tokenInfoStr = localStorage.getItem('tokenInfo');
 
 let headers: Record<string, string> = {
   ...options.headers,
 };

 if (tokenInfoStr) {
  try {
 const tokenInfo = JSON.parse(tokenInfoStr);
 
  if (tokenInfo.accessToken && !(options as any)?.skipAuthorization) {
   // 在请求头中添加 Authorization
   headers['Authorization'] = `Bearer ${tokenInfo.accessToken}`;
   
   // 添加 X-Tenant-ID 请求头（如果存在当前租户ID）
   if (tokenInfo.currentUser?.currentTenantId) {
     headers['X-Tenant-ID'] = String(tokenInfo.currentUser.currentTenantId);
   }
  }
 } catch (e) {
 console.error('[请求拦截器] 解析 token 失败:', e);
 }
 }
 
 // 添加 Accept-Language 请求头
 const locale = localStorage.getItem('umi_locale') || 'zh-CN';
 headers['Accept-Language'] = locale;
 
 return { url, options: { ...options, headers } };
 },
 ],

 // 与后端约定的响应数据格式

 // 错误处理： umi@3 的错误处理方案。
 errorConfig: {
  // 错误抛出
  errorThrower: (res) => {
  const { success, isSuccess, code, data, errorCode, errorMessage, message: msg, showType } =
      res as unknown as ResponseStructure;
    
    // 后端 ResultVo 结构：code=200 表示成功，其他表示失败
    // 优先使用 code 字段判断（后端标准格式）
    const isSuccessValue = code === 200 || success === true || isSuccess === true;
    
    // 优先使用 message 字段（后端 ResultVo 使用 message），其次使用 errorMessage
    const errorMsg = msg || errorMessage;
    const errorCodeValue = code || errorCode;
    
    if (!isSuccessValue) {
    const error: any = new Error(errorMsg || '请求失败');
      error.name = 'BizError';
      error.info = { errorCode: errorCodeValue, errorMessage: errorMsg, showType: showType || ErrorShowType.ERROR_MESSAGE, data };
      throw error; // 抛出自制的错误
    }
  },
  // 错误接收及处理
  errorHandler: (error: any, opts: any) => {
    if (opts?.skipErrorHandler) throw error;
    // 我们的 errorThrower 抛出的错误。
    if (error.name === 'BizError') {
    const errorInfo: ResponseStructure | undefined = error.info;
      if (errorInfo) {
      const { errorMessage, errorCode } = errorInfo;
        switch (errorInfo.showType) {
          case ErrorShowType.SILENT:
            // do nothing
            break;
          case ErrorShowType.WARN_MESSAGE:
            message.warning(errorMessage);
            break;
          case ErrorShowType.ERROR_MESSAGE:
            message.error(errorMessage);
            break;
          case ErrorShowType.NOTIFICATION:
            notification.open({
            description: errorMessage,
              message: errorCode,
            });
            break;
          case ErrorShowType.REDIRECT:
            // TODO: redirect
            break;
        default:
            message.error(errorMessage);
        }
      }
    } else if (error.response) {
      // Axios 的错误
      // 请求成功发出且服务器也响应了状态码，但状态代码超出了 2xx 的范围
      const { status, data } = error.response;
      
      // 尝试从响应数据中提取错误信息（后端可能返回 ResultVo 格式）
      const responseData = data as ResponseStructure | undefined;
      const errorMsg = responseData?.message || responseData?.errorMessage || `请求失败 (状态码: ${status})`;
      
      if (status === 401) {
        // 未授权，先检查 localStorage 中是否有 token
        const tokenInfoStr = localStorage.getItem('tokenInfo');
        
        // 清除本地存储的登录信息
        localStorage.removeItem('currentUser');
        localStorage.removeItem('tokenInfo');
        
        // 避免登录页重复跳转
        if (window.location.pathname !== '/login') {
          // 显示错误提示
          if (!tokenInfoStr) {
            // 没有 token，直接跳转
            history.push({
              pathname: '/login',
              search: `?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`,
            });
          } else {
            // 有 token 但返回 401，说明 token 过期或无效
            message.error('登录已过期，请重新登录');
            // 延迟跳转，让用户看到错误提示
            setTimeout(() => {
              if (window.location.pathname !== '/login') {
                history.push({
                  pathname: '/login',
                  search: `?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`,
                });
              }
            }, 1000);
          }
        }
        return;
      }
      
      // 显示后端返回的具体错误信息
      message.error(errorMsg);
    } else if (error.request) {
      // 请求已经成功发起，但没有收到响应
      // \`error.request\` 在浏览器中是 XMLHttpRequest 的实例，
      // 而在 node.js 中是 http.ClientRequest 的实例
      message.error('None response! Please retry.');
    } else {
      // 发送请求时出了点问题
      message.error('Request error, please retry.');
    }
  },
 },

 // 响应拦截器
 responseInterceptors: [
 (response) => {
     // 拦截响应数据，进行个性化处理
   const { data, success, code, message: msg } = response as unknown as ResponseStructure;

     // 支持两种格式：success 字段或 code 字段
     // 注意：这里不显示错误信息，让 errorThrower 抛出错误后由 errorHandler 处理
     // 避免重复显示错误信息
     return response;
   },
 ],
};
