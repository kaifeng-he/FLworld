# hkf 和 cl 的小世界

这是一个只给 hkf 和 cl 使用的双人 Android App 初版工程。仓库包含：

- `server/`：Cloudflare Workers + D1 后端，负责共享聊天、人格、位置距离和大模型 API 代理。
- `android/`：Kotlin 原生 Android 客户端，使用 Jetpack Compose。

## 后端运行

```bash
cd server
npm install
npm run d1:migrate:local
npm run dev
```

本地 Worker 默认监听 `http://localhost:8787`。默认大模型配置为 DeepSeek `deepseek-v4-flash`，接口地址为 `https://api.deepseek.com`。如果没有配置 `LLM_API_KEY`，后端会返回本地兜底的陪伴式回复，方便先调通 App。

线上部署见 [server/README.md](/home/arw/workspace/projects/F_L/server/README.md)。

## Android 运行

用 Android Studio 打开 `android/` 目录，等待 Gradle 同步后运行到手机或模拟器。

首次使用前，在 App 设置页填写后端地址，例如：

- 模拟器访问本机：`http://10.0.2.2:8787`
- 真机访问电脑：`http://电脑局域网IP:8787`
- 线上使用：Cloudflare Workers 部署后给出的 `https://...workers.dev`

## 默认账号

后端默认支持两个固定用户：

- `hkf`
- `cl`

默认登录口令在 `server/.env.example` 中，可按需修改。
