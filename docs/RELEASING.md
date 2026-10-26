# 发布

APK 和模块由维护者手动上传到 GitHub Release。CI 仅检查源码，不保存签名密钥、不上传二进制。

## 版本与检查

1. 同步更新 `app/build.gradle.kts` 的 versionName / versionCode 与 `magisk/module.prop` 的 version / versionCode。
2. 更新 [CHANGELOG.md](../CHANGELOG.md)，执行单元测试、Lint 与构建。
3. 验证兼容设备的系统账户、后台来电、短信及升级；不要把仅模拟器或本地注入的结果写成完整实网验证。
4. 确认 `verifyDebugPackage` 通过；最终 release APK 也应只含 ARM64，并保留协议语法、TLS 证书、默认通话媒体和 LSPosed 入口。
5. 为对应源码提交创建版本标签，例如 `v0.2.2`。

## 签名与打包

`scripts/build.ps1` 生成的是用于联调的 debug APK。正式分发可在 Android Studio 生成签名 release APK，或执行 `assembleRelease` 后使用 Android SDK 签名工具完成签名。

持续使用同一签名密钥，密钥与密码留在本机或受控的签名环境。不同签名不能直接覆盖现有安装；从调试签名迁移前先确认设备升级方案。

将已经签名的最终 APK 传给模块打包脚本：

```powershell
.\scripts\package-module.ps1 -Apk path/to/signed.apk
```

核对 APK 与模块内 APK 的内容一致，发布以下资产：

- `MySIP-<版本>.apk`
- `MySIP-system-<版本>.zip`
- 可选的 SHA-256 校验值

0.2.2 起，APK 和模块均仅支持 ARM64；发布说明应标明架构限制。系统模块需要 APK 外的 ARM64 原生库，勿为了缩小 ZIP 手动删除这一目录。

GitHub 自动生成的 Source code 下载仅包含源码，不能当作 Magisk 模块安装。安装包不提交到 Git 历史。

## 许可与来源

自有源码按仓库 [AGPLv3](../LICENSE) 分发；保留第三方通知，并提供与发布二进制对应的源码和构建说明。Linphone 及其组件的来源与许可见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
