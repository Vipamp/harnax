# harnax 官网

四页 × 中英两棵树的纯静态站，零构建：`npx`、`npm install`、打包器都不需要，改完 HTML 刷新就是最终形态。

```
index.html  capability.html  deploy.html  boundaries.html
en/         同上四页的英文镜像
assets/     site.css（全部样式与配色 token）、site.js（移动端导航开合、滚动揭示）、icon.png
tools/      check.mjs（文案闸门）、shoot.mjs（无头渲染取证）
```

## 本地预览

页内链接一律是 `/index.html` 这种绝对路径，`file://` 直接打开必然 404，必须走 HTTP：

```bash
python3 -m http.server --directory harnax-site 8123
# 打开 http://127.0.0.1:8123/ ，英文在 http://127.0.0.1:8123/en/index.html
```

不想自己起服务就用取证脚本内置的那台（同一端口，跑完即关）：

```bash
node harnax-site/tools/shoot.mjs              # 全部 8 页
node harnax-site/tools/shoot.mjs deploy.html  # 只跑指定页（路径相对 harnax-site/）
```

取证脚本默认也占 8123，和上面那台手起的预览会撞（`EADDRINUSE`）。两个要同时跑就换个端口：`SITE_PORT=8134 node harnax-site/tools/shoot.mjs`。

## 托管

把 `harnax-site/` 整个目录当静态根，或让 nginx 的 `root` 指到它。`/en/` 是真实目录，不需要任何 rewrite；hreflang 与 canonical 写的是绝对路径，所以站点必须挂在域名根下——挂进 `/docs/harnax/` 这类子路径会让整批链接和 `hreflang` 一起指错。

不做的两件事，都是刻意的：没有语言协商（不按 `Accept-Language` 或 UA 猜，默认落中文，切换靠页头那两个链接），没有 404 页。

## 改一条文案要走哪道闸

先跑闸，再提：

```bash
node harnax-site/tools/check.mjs --report   # 文案：禁词 / 数字 / 成对限定语 / 双语对账 / hreflang / 对比度
node harnax-site/tools/check.mjs --selftest # 证明上面那道闸真的会红
node harnax-site/tools/shoot.mjs            # 渲染：每屏 section 在场 + 溢出与图注裁切
node --check harnax-site/assets/site.js
```

最容易拦住的几条规则，判据都在 `check.mjs` 顶部的数据表里：

- **禁词**（`BANNED`，14 条）：仓库地址、许可证、开源、一键/开箱即用、Kubernetes、SLA、计费与费用、客户案例、个人微信这一类说法不许出现。`money` 这条中文认「费用 / 账单 / 计费 / 金额」，英文认 `billing` / `costs?` / `pricing`——也就是说英文里顺手写一句 "costs two more hops" 会红，改成 `takes`；中文「成本」反而不在表里，别以为它拦得住。
- **数字**（`NUM` + `IGNORE`）：正文里每个数字都要在白名单内，白名单外的一律红。要新增一个数字，先回 `../docs/superpowers/specs/2026-10-12-harnax-website-design.md` 给它补上可回溯的判据（`git show HEAD:` 读到的仓库原文），再把它加进 `NUM`；顺序反了就等于给文案开了口子。日期、年份、HTTP 状态码、CSS 长度、算法名与依赖版本走 `IGNORE` 豁免，因为那些不是产品断言。
- **成对限定语**（`PAIR_RULES`，11 条）：写了前半句就得在**同一个 `<section>`** 里写后半句，例如「熔断」必须带上是哪一侧的熔断、「副本数」必须带上默认几个。跨 section 不算。两条规则的触发面刻意收窄：`channels` 只在「多渠道 / 每个渠道 / 渠道能力」这类**能力口径**上要求点名三家，`渠道` 指 channel-service 服务块时不算主张；`https` 认大写，协议白名单里那个小写 `https` 不会误触发。
- 中文「客户端」这类技术词不吃 `customers` 禁词（规则写成 `客户(?!端)`）；`--selftest` 的夹具里埋了这一条，改窄了规则会立刻红。

双语对账要求两棵树的 section id 序列、链接锚点多重集、h1–h3 层级序列逐项相等。改了一边的结构，另一边同步改，否则红。

`shoot.mjs` 除了数 section，还在 1440 与 390 两档宽度下用同源 iframe 探针量三件事：整页有没有横向溢出、卡片/表格容器有没有把内容切掉、示意图 `<text>` 的字宽有没有超出自己的 `viewBox`。最后那条会静默丢字——英文比中文长，图注尤其容易踩——命中就照输出里的偏移量把字画短，别放宽判据。

## 三条口径已拍板（2026-10-12）

重新论证之前先读这三条，它们是他逐条定下的，不是待办：

1. **渠道对外只讲三家**（飞书、钉钉、企业微信）。源码里还有第四个 IM 适配器，指向的是个人微信那一类，站内一律不写、也不暗示——`BANNED` 的 `wechat-personal` 拦的就是它。边界页「文件能直发到每个渠道」那行是这条口径唯一允许的侧写：只说能力位在某个适配上打开、且不在这里推广。
2. **小程序供应商表单的候选与运行期支持不一致，写成一条边界**，不做沉默处理。管理台只列 `dashscope`／`openai`／`ollama`，小程序那张表单另加 azure、deepseek、zhipu、moonshot、anthropic 与 other；运行期仍只认前三种，其余入库成功、装配时才在执行服务日志落一条 `Unsupported provider type`，会话按「模型配置不存在」那个错误码失败。这条断言落在边界页 `#not-said` 表，中英两行成对。
3. **compose `limits` 保留双口径**，两个数都要出现：按服务块相加 12.0 核 / 8448M，调度默认双副本实跑 13.0 核 / 9216M。首页 fineprint 与部署页各写一次，只留一个数会被对照 `docker-compose.yml` 的人当成算错。

## 页脚的占位链接

页脚在既有的 产品／形态／口径 三列之后另挂 资源 与 公司 两列，底行再一组法务链接。这些条目指的页面站内还不存在，一律写成 `href="#"` 加 `class="ftr-todo"`（暗一档、带虚下划线），别填假地址也别顺手删——替换的时机是那些页面真的上线。`官方客服：xxx-xxx` 同理是占位，它是纯文本 `<p class="ftr-contact">`，不是链接。

两组成员物刻意没铺：**教育版与合作伙伴**（站内没有任何版本或售卖口径能给它们背书），**最终用户许可协议与品牌使用条款**（许可类文本与「暂不开源、不放许可证信息」这条边界直接冲突）。要加的话先解这两条口径。
