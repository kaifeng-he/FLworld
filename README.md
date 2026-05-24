# FL小世界

这是一个只给两个人使用的 Android App。仓库包含：

- `server/`：Cloudflare Workers + D1 后端，负责共享聊天、留言、日历、相册、位置距离和大模型 API 代理。
- `android/`：Kotlin 原生 Android 客户端，使用 Jetpack Compose。

## 后端运行

```bash
cd server
npm install
npm run d1:migrate:local
npm run dev
```

线上 Worker 地址固定为：

```text
https://hkf-cl-world.flworld.workers.dev
```

## Android 运行

用 Android Studio 打开 `android/` 目录，等待 Gradle 同步后运行到手机或模拟器。

App 内不需要填写后端地址。

## 默认账号

后端默认支持两个固定用户：

- `hkf`：App 内显示为“锋宝”
- `cl`：App 内显示为“璐宝”

默认登录口令在 `server/wrangler.toml` 中，可按需修改。
