# Cloudflare Workers 后端

这个后端运行在 Cloudflare Workers，数据存在 D1，Android App 仍然调用同一套 REST API。

## 本地开发

```bash
cd server
npm install
npm run d1:migrate:local
npm run dev
```

本地 Worker 默认会给出一个 `http://localhost:8787` 地址。Android 模拟器可以填 `http://10.0.2.2:8787`。

## 部署

1. 登录 Cloudflare：

```bash
npx wrangler login
```

2. 创建 D1 数据库：

```bash
npm run d1:create
```

3. 把命令输出里的 `database_id` 填到 `wrangler.toml` 的 `database_id`。

4. 初始化远程数据库：

```bash
npm run d1:migrate
```

5. 设置 DeepSeek API key：

```bash
npx wrangler secret put LLM_API_KEY
```

当前默认模型配置在 `wrangler.toml`：

```toml
LLM_BASE_URL = "https://api.deepseek.com"
LLM_MODEL = "deepseek-v4-flash"
```

也可以按需把 `APP_TOKEN_HKF`、`APP_TOKEN_CL`、`LOGIN_CODE_HKF`、`LOGIN_CODE_CL` 改成更私密的值，或用 `wrangler secret put` 设置。

6. 部署：

```bash
npm run deploy
```

Android App 里已经固定线上 Worker 地址，不需要在 App 内填写后端地址。
