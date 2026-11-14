# AdSkip

慧生活798（`com.cloudora.android`）的积分任务辅助模块：点「去完成」时不播放广告，让 App 自己把加分请求发给服务端。

- 包名：`com.yg.adskip`
- 版本：1.1（versionCode 2）
- 适用：慧生活798 **3.1.10 (31010)** 实测通过（3.1.9 的类/方法名不同，见下方「已知情况」）
- 环境：Android 7.0+（minSdk 24），LSPosed / 兼容 Xposed API 82 的框架，设备已 root

## 能做什么

| 任务 | 单次 | 每日上限 | 本模块 |
| --- | --- | --- | --- |
| 观看视频，领取积分 | +30 | 5 次 | ✅ 免播放，直接到账 |
| 观看广告，获取积分 | +10 | 5 次 | ✅ 广告页直接关掉，走 App 自己的提交 |
| 玩赚广告 / 逛一逛 / 看资讯 | — | 1 次 | ❌ H5 计时类任务，未接管，需手动完成 |

按满额算，一天可以自动拿到 `30×5 + 10×5 = 200` 积分。

## 原理

模块只做两件事：**把广告环节跳过**，以及**让 App 自己完成加分**。不改 APK、不动签名。

**1. 视频任务（+30）**

hook 广告聚合工具 `MediationAdHelper#loadAndShow(String)`：

- 拦截后不加载任何广告，关掉它自己的 loading 弹窗
- 在主线程回调 `listener.onAdLoaded()`，800ms 后再回调 `listener.onAdRewardArrived(true)`
- reward 回调本身**不会**发加分请求（只会弹「+30」的本地弹窗），所以延迟 1.2s 后反射拿到当前 Fragment 上的 `AddScoreParm`（adId / addScore / addScoreType / type / weekday），直接调它的提交方法 `U(AddScoreParm)`，让 App 走自己的 `/api/v1/acc/score/score-send`

**2. 广告任务（+10）**

这个任务用的不是同一个广告 SDK，而是互动插屏。hook `Instrumentation#callActivityOnCreate`，任何广告 Activity 一创建就 `finish()`，然后靠 App 自己的关闭回调提交；同时做了提交去重（避免一次点击发多个请求被服务端限流），并在 3 秒后关掉残留的 loading 弹窗。

**3. 兼容处理**

目标 App 有壳，业务代码在运行时动态加载的 dex 里，`handleLoadPackage` 阶段拿不到目标类。模块 hook 了 `ClassLoader#loadClass` 作为观察点，命中目标类时再安装对应的 hook；另有 `Application.onCreate` 兜底 + 2s/5s/10s 重试。

## 安装

1. 安装 `apk/AdSkip-1.1.apk`
2. LSPosed 管理器 → 模块 → **AdSkip** → 打开「启用模块」
3. 作用域勾选 **慧生活798 / com.cloudora.android**
4. **强行停止**慧生活798，再重新打开（只重启窗口不生效）
5. 进「积分」页点「去完成」，广告不会出现，两三秒后自动加分

装好后桌面会出现一个 AdSkip 图标，那是模块自己的说明页，**没有任何开关**——看说明用，装好即在 LSPosed 里生效。

## 怎么确认生效

模块日志挂在框架自己的 tag 下（LSPosed 是 `LSPosedFramework`），不要按 `AdSkip` 过滤：

```powershell
adb logcat | Select-String "AdSkip:"
```

注入成功会有：

```
AdSkip: loaded in com.cloudora.android
```

点一次「去完成」，正常会出现：

```
AdSkip: loadAndShow intercepted ...
AdSkip: -> onAdRewardArrived(true) sent
AdSkip: [submit:reward] parm adId=... addScore=30 ...
AdSkip: [ds] m1 ENTER args=[adId=...]
```

## 边界与已知情况

- **加分请求由 App 自己发出**，模块只负责跳过广告并替它按下提交；请求的签名、token 都沿用 App 自己的。
- **服务端有频率限制**：短时间连续点会提示「请求太过频繁」，等一会儿再点即可，不是模块坏了。
- **每个任务有每日上限**，跑满会显示「明天再来」。
- **H5 计时任务不做**：「逛一逛 / 看资讯 / 玩赚广告」是 `WebActivity` 里的网页任务，浏览计时与完成判定都在 H5 内，native 侧既不构造加分参数也不发请求，收益 8 分/天，接管成本远高于收益。
- **版本敏感**：模块按 3.1.10 的类名与方法名编写。目标 App 升级后如果改动这些内部结构，模块可能失效（日志里会看到 hook 失败），需要重新适配。
- **仅本机使用**：不做账号、设备或网络层的任何伪装。

## 构建

需要 **JDK 21**（AGP 9 要求）。Xposed API 以 `compileOnly` 引入 `app/libs/api-82.jar`，不会打进 APK。

```powershell
$env:JAVA_HOME="C:\path\to\jdk-21"
cd Adskip
.\gradlew.bat --no-daemon assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

命令行构建时把 `-D...` 参数放在 `gradlew.bat` **前面**，写在后面会被当成 task 名。

## 目录结构

```
Adskip/
├── app/
│   ├── src/main/
│   │   ├── java/com/yg/adskip/
│   │   │   ├── Hook.java             # 模块主体：类加载观察、广告拦截、加分提交
│   │   │   └── SettingsActivity.java # 说明页（纯代码构建 UI，无 XML 布局）
│   │   ├── assets/xposed_init        # 入口类声明
│   │   ├── res/                      # 图标、字符串、xposed 作用域数组
│   │   └── AndroidManifest.xml       # xposed 元数据 + 说明页
│   ├── libs/api-82.jar
│   └── build.gradle.kts
├── apk/AdSkip-1.1.apk
└── README.md
```

## 版本历史

- **1.1** — 说明页重做（渐变头部整屏顶、5 张卡片、代码块可复制）；新增广告任务（+10）接管与提交去重；自动关闭残留 loading 弹窗；关闭全部调试日志开关
- **1.0** — 首个可用版本：视频任务（+30）免播放并真实到账

## 免责

仅供个人学习与研究使用。模块会修改目标 App 的运行时行为，可能导致账号受限或任务资格被取消，使用前请自行评估风险。

## License

[MIT](../LICENSE) © 2026 adu
