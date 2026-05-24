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

如果是真机访问本机后端，请把设置页的后端地址改成电脑局域网 IP，例如 `http://192.168.1.10:8787`。
