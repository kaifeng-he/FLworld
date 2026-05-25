# CloudBase HTTP 云函数后端

这个后端运行在腾讯云 CloudBase HTTP 云函数中，保留 Android 客户端使用的 REST API 与 SSE 流式聊天协议。

## 使用的云服务

- 云数据库：`features`、`personas`、`sessions`、`messages`、`notes`、`calendar_events`、`album_items`、`locations`、`auth_sessions`、`memory_documents`、`ai_memories`、`chat_requests` 集合。
- 云存储：相册文件存入 `album/` 路径；数据库只保存元数据和预览内容。
- DeepSeek API：默认模型 `deepseek-v4-flash`，用于小暖回复、标题与长期记忆提炼。

数据不会从旧 Cloudflare D1 自动迁移。新环境首次访问时会自动初始化默认功能和默认聊天风格。

## 环境变量

线上请在 HTTP 云函数配置中设置：

```text
LOGIN_CODE_HKF=锋宝登录口令
LOGIN_CODE_CL=璐宝登录口令
LLM_API_KEY=你的DeepSeek API Key
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-v4-flash
LLM_TIMEOUT_MS=60000
```

v8 由后端在登录时签发设备会话 token，同一身份的新登录会替换旧设备，不再需要配置固定 `APP_TOKEN_*`。

本地运行时复制 `.env.example` 为 `.env`，另需填入 `TCB_ENV_ID`，并按 CloudBase 官方方式配置服务端访问凭据。

## 部署

安装 Node.js 20 以上版本和 CloudBase CLI 后执行：

```bash
cd server
npm install
npm run check
npm install -g @cloudbase/cli
tcb login
tcb fn deploy hkf-cl-world-api --httpFn --dir .
```

HTTP 云函数需要启动脚本 `scf_bootstrap`，本目录已包含。部署后在 CloudBase 控制台的 HTTP 访问服务中将该函数绑定到默认 HTTPS 域名，触发路径设为 `/`。

函数还需要能够主动访问公网：DeepSeek 请求从函数内部发送到 `https://api.deepseek.com`。在函数的网络配置中开启**公网访问**；如果函数也配置了私有网络（VPC），必须选择同时允许公网访问，或为 VPC 配置 NAT 出网。仅给函数绑定公网访问域名不能提供这项出站能力。

完整的控制台配置、Android 配置和排错步骤见根目录 `重新部署和运行教程.md`。
