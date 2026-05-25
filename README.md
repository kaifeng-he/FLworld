# FL小世界

这是一个只给两个人使用的 Android App。仓库包含：

- `server/`：腾讯云 CloudBase HTTP 云函数后端，负责共享聊天、留言、日历、云存储相册、位置距离和 DeepSeek API 代理。
- `android/`：Kotlin 原生 Android 客户端，使用 Jetpack Compose。

## 后端运行

```bash
cd server
npm install
npm run dev
```

线上部署到腾讯云 CloudBase HTTP 云函数，使用 CloudBase 默认 HTTPS 域名即可在国内访问。后端使用：

- 云数据库：保存聊天、留言、日历、位置和相册元数据。
- 云存储：保存相册照片和视频文件。
- DeepSeek API：由云函数使用你配置的 API Key 提供流式聊天回复。

## Android 运行

将 CloudBase 后端地址配置到 `android/gradle.properties`：

```properties
FLWORLD_API_BASE_URL=https://你的默认域名
```

然后用 Android Studio 打开 `android/` 目录，等待 Gradle 同步后运行到手机或模拟器。

从零部署 CloudBase 后端、配置 Android 和日常更新的完整步骤见：

- [重新部署和运行教程](./重新部署和运行教程.md)

## 默认账号

后端默认支持两个固定用户：

- `hkf`：App 内显示为“锋宝”
- `cl`：App 内显示为“璐宝”

线上登录口令和 token 通过 CloudBase 云函数环境变量配置，部署前应替换默认值。
