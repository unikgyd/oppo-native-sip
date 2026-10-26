# MySIP 系统电话

[![Android checks](https://github.com/unikgyd/oppo-native-sip/actions/workflows/android.yml/badge.svg)](https://github.com/unikgyd/oppo-native-sip/actions/workflows/android.yml)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

将 SIP 网关接入 Android 原生电话体系：来电、接听、挂断和通话记录使用手机原厂电话界面，后台服务维护 SIP 注册，不依赖 Google 推送。

基于 Kotlin、Linphone SDK、Android `PhoneAccount / ConnectionService`，可通过 Magisk 部署为常驻系统应用。当前版本 **0.2.2**，安装包仅支持 **ARM64**。

## 功能

- 原厂来电界面与系统通话记录；支持 SIP 呼出、听筒、免提和通话控制。
- SIP MESSAGE 文本短信、原生会话通知、通知内 SIP 回复和标为已读。
- 可选同步新收到的短信到原厂收件箱；应用内支持搜索、未读数、草稿和历史分页。
- 开机启动、注册续期、断线重连、可选解锁前接听。
- 纯图标导航、应用内拨号盘、桌面快捷方式和快捷设置磁贴。
- 可选 LSPosed 扩展，为适配的 OPPO 原厂拨号盘增加独立 SIP 按钮。

## 兼容范围

| 能力 | 条件 |
| --- | --- |
| 基础客户端 | Android 9+、ARM64（`arm64-v8a`）；其他设备和 ROM 仍需实际验证 |
| 系统常驻与收件箱同步 | Root + Magisk；安装系统模块并重启 |
| 原厂拨号盘第三按钮 | **Android 13 + OPPO 电话 13.20.0 + LSPosed**，作用域仅 `com.android.contacts` |
| 已验证设备 | OPPO PGJM10 / Android 13 / ARM64；旧版另有 Android 15 x86_64 模拟器验证，当前包不支持 x86_64 |

注册系统通话账户不代表所有厂商拨号盘都会出现第三个按钮。未适配的拨号器可使用 MySIP 自身拨号页或系统号码链接入口。

**通知里的 SIP 回复通过 SIP 发送；原厂短信 App 内的回复仍使用它选择的 SIM。** 收件箱同步默认关闭，只复制开启后新收到的文本，不导入旧历史或发件记录。

当前支持单账户、一路音频通话和 `text/plain` SIP MESSAGE。IMS `application/vnd.3gpp.sms` 需由上游网关转换；本项目不替换运营商 IMS / RIL，也不建立虚拟 SIM。

## 安装

从 [Releases](https://github.com/unikgyd/oppo-native-sip/releases) 获取维护者发布的 APK / Magisk 模块，或自行构建。

1. 安装 APK，在 MySIP 填写服务器、端口、分机、密码和传输方式。
2. 在系统通话账户中启用 **MySIP 网关**，授予麦克风、电话及通知权限。
3. 需要系统常驻时，在 Magisk 安装模块 ZIP 并重启。
4. 需要原厂收件箱副本时，在“设置 → 短信与通知”开启同步。
5. 适配机型可在 LSPosed 启用 MySIP，作用域仅选原厂“电话”，再重启电话应用。

账户初始参数全部留空，升级保留已有配置。后台在线仍需要手机实际可达 SIP 网关；开机时 VPN 未就绪会先等待网络恢复。

详见 [安装、升级与卸载](docs/INSTALLATION.md)，包括 Shamiko 白名单兼容方式。

## 构建

需要 **JDK 17、Android SDK 35**，Gradle Wrapper 固定为 8.14.3。Android Studio 可直接打开本目录。

Windows PowerShell：

```powershell
.\scripts\build.ps1
```

或直接执行 Gradle（Linux / macOS 将 `gradlew.bat` 换为 `./gradlew`）：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug verifyDebugPackage
.\scripts\package-module.ps1
```

脚本输出 `dist/MySIP-0.2.2.apk` 和 `dist/MySIP-system-0.2.2.zip`。本地调试包约 **24.4 MiB**，模块约 **32.5 MiB**；具体大小随构建和签名略有变化。仅包含 ARM64 库，并排除未使用的示例和备用媒体，保留默认通话资源、协议语法和 TLS 证书。

这是调试构建；公开分发请使用自己的签名，升级时保持原签名，参见 [发布说明](docs/RELEASING.md)。CI 执行构建、测试和 APK 内容检查，不上传 APK 或创建 Release。

## 文档

- [安装、升级与卸载](docs/INSTALLATION.md)
- [源码结构、构建参数和联调](docs/DEVELOPMENT.md)
- [Asterisk MESSAGE 接入示例](docs/ASTERISK.md)
- [验证范围与已知限制](docs/VALIDATION.md)
- [版本记录](CHANGELOG.md)

## 许可证

本项目自有源码采用 [GNU AGPLv3](LICENSE)（SPDX: `AGPL-3.0-only`）。依赖的许可证和来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

仓库仅保存源码、构建配置和文档；账号、密钥、设备抓包、原厂 APK、构建缓存与安装包均不纳入版本控制。
