# Android 客户端

用 Android Studio 打开本目录运行。

环境要求：

- JDK 17
- Android SDK 35
- Android Studio Ladybug 或更新版本

如果 Android Studio 报 `android.useAndroidX property is not enabled`，确认本目录下的 `gradle.properties` 存在，并包含：

```properties
android.useAndroidX=true
```

App 默认连接线上 Worker，不需要在 App 内填写后端地址。
