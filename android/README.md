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

App 后端地址通过构建属性配置。在 `gradle.properties` 中增加：

```properties
FLWORLD_API_BASE_URL=https://你的-cloudbase-默认域名
```

地址的获得方式见仓库根目录的 `重新部署和运行教程.md`。
