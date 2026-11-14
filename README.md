# Oopz 原生 Android 客户端

> 第三方客户端，与 Oopz 官方无关。仅供个人使用，遵守官方服务条款。

## 下载

最新安装包在 [Releases](https://github.com/HosisoraLing/oopz-native/releases) 页面（arm64，约 21MB）。
安装说明见 [INSTALL.md](INSTALL.md)。


无广告、无内置浏览器内核（登录复用系统 WebView）、原生 TRTC 语音的 Oopz 第三方客户端。

## 现状

| 能力 | 状态 |
|---|---|
| 网页登录（系统 WebView 拿 signature） | 已实现，待真机验证 |
| 密码/验证码登录（纯 REST） | 接口存在，但需要客户端签名，未打通 |
| 域列表 | 已实现（`/client/v1/area/v1/home/v1/subscribed`） |
| 频道列表 | 已实现（`/client/v1/area/v1/detail/v1/channels?area=`，另有网关事件兜底） |
| 进语音频道取 TRTC 凭证 | 已实现（`/client/v1/channel/enter` + 宽松字段解析，单测覆盖） |
| TRTC 原生语音 | 已接入腾讯 LiteAVSDK_TRTC 13.6.0 |
| 输出设备切换 | 扬声器/听筒/有线/蓝牙/A2DP，基于 `setCommunicationDevice` |
| 车机模式 | 车端麦克风（HFP）/ 手机麦+车媒体音（A2DP）/ 自动，含 SCO 反抢守护 |
| 降噪 | TRTC 内置 3A（AEC/ANS/AGC），语音优先档更激进 |
| 小米超级岛 | 通知栏三按钮（麦/声/退出）确定可用；`miui.focus.*` 属于非公开接口，尽力而为 |
| MIUI 保活 | 前台服务 + 常驻通知 + WakeLock + 自启动/电池白名单引导 |

## 关键技术结论

- 老文档里的 `api.oopz.cn` **已无 DNS**；当前网关是 `gateway-n.oopz.cn`，路径前缀 `/client/v1`、`/rtc/v1`。
- REST 需要 `Oopz-Sign`（客户端签名，算法在 Dart 编译产物中）。因此登录走系统 WebView，
  从 `localStorage` 的 `oopz__*` 键里取 signature，之后全部原生。
- 网页端语音 = 腾讯 TRTC Web SDK（`web.oopz.cn/rtc/v2/trtc.js`），Android 用同一家的原生 SDK。
- AI 降噪（AIDenoiser）只有 Web 插件版；Android 用 SDK 内置 3A。

## 构建

> 仓库里不含签名密钥（`release.keystore` 被 .gitignore 排除）。
> 自己构建请把 `app/build.gradle.kts` 里的 signingConfigs 换成自己的 keystore，或直接 `assembleDebug`。


```bash
export ANDROID_HOME=/root/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
gradle :app:assembleRelease      # 产物 app/build/outputs/apk/release/app-release.apk
gradle :app:testDebugUnitTest    # RtcParser 单测
```

签名：`release.keystore`（alias `oopz`），版本号在 `app/build.gradle.kts`。

## 目录

```
app/src/main/java/com/hosira/oopznative/
  OopzApi.kt         REST 客户端 + 凭证解析入口
  RtcParser.kt       TRTC 凭证宽松解析（有单测）
  OopzWs.kt          wss://ws.oopz.cn 网关（握手 event 253）
  VoiceManager.kt    TRTC 封装：路由/车机模式/音量
  CustomMic.kt       自定义采集（手机麦 + 车媒体音模式）
  CarLinkHelper.kt   车机蓝牙识别
  VoiceService.kt    前台保活 + 通知三按钮
  SessionStore.kt    签名持久化
  DeviceDiag.kt      设备诊断
  WebLoginActivity.kt 系统 WebView 登录
```

## 网关协议（2026-11 实测）

```
连 wss://ws.oopz.cn/  →  发 event 253 握手（person/deviceId/signature/deviceName）
网关回 {"event":21,"body":"{\"checkRes\":false}"} 然后断开
checkRes=true 才是通过；false 表示签名无效/过期，需要重新登录
ws-n.oopz.cn / ws1 / ws2 行为一致
```

## 签名机制（逆向结论）

登录/鉴权用的 `Oopz-Sign` 是**用内置 RSA 私钥做的数字签名**：

- 私钥以 base64 分片形式硬编码在 `main.dart.js`（`B.bxy/bI4/bw2/bsW` 四段 + 若干 `u.*` 引用），
  PEM 头尾也以 base64 常量出现（`-----BEGIN PRIVATE KEY-----`）
- 签名器：`afg(a,b,c)` → 先对 `请求内容 + 时间` 做摘要，再拼上 body 做二次摘要，最后交给
  一个 WASM 引擎（`ifl`）用私钥签名，结果 base64 后放进 `Oopz-Sign` 头
- 由于引擎是 WASM + 密钥分片，纯静态逆出来成本高；当前方案是系统 WebView 登录一次拿 signature

## 已知待办

1. 真机回归：网页登录 → 域 → 频道 → 进房（凭 TRTC 凭证）
2. `channel/enter` 响应字段名对齐（有诊断页和日志可导出）
3. 小米超级岛正式接入需要小米开放平台权限
4. 密码/验证码纯 REST 登录：需要还原 RSA 私钥 + 复刻签名管线（密钥分片已定位，未拼完整）
