# AduKit

自用的 Android 模块合集（LSPosed / Xposed）。每个模块都放在独立子目录里，互不依赖，可以单独构建、单独安装。

> 仅用于学习和研究。模块会修改目标 App 的运行时行为，请自行评估风险，后果自负。

## 模块列表

| 模块 | 目标 App | 版本 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| [AdSkip](Adskip/) | 慧生活798 `com.cloudora.android` | 1.1 | 可用 | 观看视频 / 观看广告类积分任务免播放，直接让 App 自己提交加分 |

## 目录结构

```
AduKit/
├── Adskip/                  # 一个模块 = 一个独立 Android 工程
│   ├── app/
│   │   ├── src/main/java/com/yg/adskip/
│   │   │   ├── Hook.java            # 模块主体（Xposed 入口）
│   │   │   └── SettingsActivity.java # 模块说明页（App 图标打开的那一页）
│   │   ├── src/main/assets/xposed_init
│   │   ├── src/main/res/
│   │   ├── libs/api-82.jar          # Xposed API，compileOnly，不会打进 APK
│   │   └── build.gradle.kts
│   ├── apk/AdSkip-1.1.apk           # 已构建产物
│   └── README.md                    # 模块说明
├── LICENSE
└── README.md
```

## 使用前提

1. 设备已 root，并装了 **LSPosed**（或兼容 Xposed API 82 的框架，如 Vector）
2. 目标 App 已安装（版本要求见各模块 README）
3. 在本机自行构建，或直接下载子目录 `apk/` 下的产物

通用启用流程：

1. LSPosed 管理器 → 模块 → 选择对应模块 → 打开「启用模块」
2. 作用域里勾选目标 App
3. **强行停止**目标 App 再重新打开（只重启窗口不生效）
4. 回到目标 App 的功能页触发

## 构建

每个模块是独立的 Gradle 工程，需要 **JDK 21**（AGP 9 要求）：

```powershell
$env:JAVA_HOME="C:\path\to\jdk-21"
cd Adskip
.\gradlew.bat --no-daemon assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

模块日志统一用 `XposedBridge.log` 输出，tag 是框架自己的（LSPosed 下是 `LSPosedFramework`），所以过滤时要按内容匹配：

```powershell
adb logcat | Select-String "AdSkip:"
```

## 说明

- 仓库只放**模块本身的代码**与使用说明，不包含目标 App 的安装包、反编译产物、抓包与调试日志。
- 模块不修改目标 App 的 APK，也不改动其签名；一切改动都发生在运行时。
- 各模块的具体能力、边界与已知问题，见对应子目录的 README。

## License

[MIT](LICENSE) © 2026 adu
