import type { RequestOptions } from '@@/plugin-request/request';
import type { RequestConfig } from '@umijs/max';
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
  success: boolean;
  data: any;
  errorCode?: number;
  errorMessage?: string;
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
 
 // 请求拦截器 - 添加 JWT Token
 requestInterceptors: [
 async (url, options) => {
 console.log('[请求拦截器] === 开始处理 ===');
 console.log('[请求拦截器] URL:', url);
 console.log('[请求拦截器] options:', JSON.stringify(options, null, 2));
 
 // 从 localStorage 获取 token
 const tokenInfoStr = localStorage.getItem('tokenInfo');
 console.log('[请求拦截器] tokenInfoStr:', tokenInfoStr);

 if (tokenInfoStr) {
  try {
 const tokenInfo = JSON.parse(tokenInfoStr);
 console.log('[请求拦截器] tokenInfo:', tokenInfo);
 
  if (tokenInfo.accessToken) {
   // 在请求头中添加 Authorization
 const headers = {
    ...options.headers,
 Authorization: `Bearer ${tokenInfo.accessToken}`,
   };
 console.log('[请求拦截器] 新的 headers:', headers);
 console.log('[请求拦截器] Authorization:', headers.Authorization);
   
 const result = { url, options: { ...options, headers } };
 console.log('[请求拦截器] 返回值:', result);
 return result;
 }
 } catch (e) {
 console.error('[请求拦截器] 解析 token 失败:', e);
 }
 }
 console.log('[请求拦截器] 未添加 Authorization，返回原始 options');
 return { url, options };
 },
 ],

 // 与后端约定的响应数据格式

 // 错误处理： umi@3 的错误处理方案。
 errorConfig: {
  // 错误抛出
  errorThrower: (res) => {
  const { success, data, errorCode, errorMessage, showType } =
      res as unknown as ResponseStructure;
    if (!success) {
    const error: any = new Error(errorMessage);
      error.name = 'BizError';
      error.info = { errorCode, errorMessage, showType, data };
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
      message.error(`Response status:${error.response.status}`);
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
   const { data } = response as unknown as ResponseStructure;

     if (data?.success === false) {
       message.error('请求失败！');
     }
     return response;
   },
 ],
};
