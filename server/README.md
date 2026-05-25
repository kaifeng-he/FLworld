# CloudBase HTTP 云函数后端

这个后端运行在腾讯云 CloudBase HTTP 云函数中，保留 Android 客户端使用的 REST API 与 SSE 流式聊天协议。

## 使用的云服务

- 云数据库：`features`、`personas`、`sessions`、`messages`、`notes`、`calendar_events`、`album_items`、`locations` 集合。
- 云存储：相册文件存入 `album/` 路径；数据库只保存元数据和预览内容。
- CloudBase AI：默认模型 `deepseek-v4-flash`，用于聊天回复和新会话标题。

数据不会从旧 Cloudflare D1 自动迁移。新环境首次访问时会自动初始化默认功能和默认聊天风格。

## 环境变量

线上请在 HTTP 云函数配置中设置：

```text
APP_TOKEN_HKF=替换为随机长字符串
APP_TOKEN_CL=替换为另一个随机长字符串
LOGIN_CODE_HKF=锋宝登录口令
LOGIN_CODE_CL=璐宝登录口令
AI_MODEL=deepseek-v4-flash
```

本地运行时复制 `.env.example` 为 `.env`，另需填入 `TCB_ENV_ID`，并按 CloudBase 官方方式配置服务端访问凭据。

## 部署

安装 Node.js 20 以上版本和 CloudBase CLI 后执行：

```bash
cd server
npm install
npm run check
npx @cloudbase/cli login
npx @cloudbase/cli fn deploy hkf-cl-world-api --httpFn --dir .
```

HTTP 云函数需要启动脚本 `scf_bootstrap`，本目录已包含。部署后在 CloudBase 控制台的 HTTP 访问服务中将该函数绑定到默认 HTTPS 域名，触发路径设为 `/`。

完整的控制台配置、Android 配置和排错步骤见根目录 `重新部署和运行教程.md`。
