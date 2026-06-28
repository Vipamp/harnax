/**
 * @name 代理的配置
 * @see 在生产环境 代理是无法生效的，所以这里没有生产环境的配置
 * -------------------------------
 * The agent cannot take effect in the production environment
 * so there is no configuration of the production environment
 * For details, please see
 * https://pro.ant.design/docs/deploy
 *
 * @doc https://umijs.org/docs/guides/proxy
 */
export default {
  // 如果需要自定义本地开发服务器  请取消注释按需调整
  dev: {
    // localhost:8000/api/admin/** -> http://localhost:8080/api/admin/**
    '/api/admin/': {
      target: 'http://localhost:8080',
      changeOrigin: true,
    },
    // localhost:8000/api/router/** -> http://localhost:8081/api/router/**
    '/api/router/': {
      target: 'http://localhost:8081',
      changeOrigin: true,
    },
    // localhost:8000/api/agent/** -> http://localhost:8082/api/agent/**
    '/api/agent/': {
      target: 'http://localhost:8082',
      changeOrigin: true,
    },
    // localhost:8000/ai/** -> http://localhost:8080/ai/**
    '/ai/': {
      target: 'http://localhost:8080',
      changeOrigin: true,
      // 接管响应处理，手动转发每个 chunk，绕过 umi 开发服务器 gzip 缓冲
      selfHandleResponse: true,
      onProxyRes: (proxyRes: any, _req: any, res: any) => {
        // 设置响应头，禁止压缩
        const headers: Record<string, string> = {};
        for (const key of Object.keys(proxyRes.headers)) {
          if (key.toLowerCase() !== 'content-encoding') {
            headers[key] = proxyRes.headers[key];
          }
        }
        headers['Cache-Control'] = 'no-cache, no-transform';
        headers['X-Accel-Buffering'] = 'no';
        res.writeHead(proxyRes.statusCode, headers);

        // 逐块转发，每写一块立即 flush
        proxyRes.on('data', (chunk: any) => {
          res.write(chunk);
          if (typeof res.flush === 'function') res.flush();
        });
        proxyRes.on('end', () => {
          res.end();
        });
      },
    },
  },
  /**
   * @name 详细的代理配置
   * @doc https://github.com/chimurai/http-proxy-middleware
   */
  test: {
    // localhost:8000/api/** -> https://preview.pro.ant.design/api/**
    '/api/': {
      target: 'https://proapi.azurewebsites.net',
      changeOrigin: true,
      pathRewrite: { '^': '' },
    },
  },
  pre: {
    '/api/': {
      target: 'your pre url',
      changeOrigin: true,
      pathRewrite: { '^': '' },
    },
  },
};
