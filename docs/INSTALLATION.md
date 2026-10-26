# 安装、升级与卸载

## 首次安装

1. 安装 APK，打开 MySIP，填写完整 SIP 账户。服务器填写主机名或 IP，端口单独填写。
2. 选择网关实际支持的 UDP / TCP / TLS，不会自动使用预置服务器或测试分机。
3. 保存并连接，确认状态在线。
4. 打开“系统通话账户”，启用 MySIP 网关；完成麦克风、电话、通知等授权。
5. 如需系统常驻，在 Magisk 安装同版模块 ZIP 并重启。

普通 APK 可以验证 SIP 与原厂通话界面；模块部署才提供系统应用和 `persistent` 身份。模块只对 `dev.mysip` 设置后台豁免和必要授权，不修改 SIM / IMS 服务。

## 短信与通知

“同步到原厂短信收件箱”默认关闭。安装新版模块并重启后可在设置开启；模块仅向 MySIP 设置 WRITE_SMS AppOp，应用不请求读取全部系统短信，也不需要成为默认短信应用。

- 只复制新收到的文本短信。首次解锁前先排队，解锁后同步。
- MySIP 使用 Android 原生消息通知，支持 SIP 回复与标为已读。
- 原厂短信 App 内回复仍走它选择的 SIM。
- 在 MySIP 标为已读会更新自己写入的副本；原厂 App 的已读状态不反向同步。
- 删除 MySIP 会话不会删除原厂副本。
- 写入中途退出时可能显示“同步未确认”，不会盲目重复插入。

## 原厂拨号盘 SIP 按钮

仅适配 Android 13、OPPO 电话 `com.android.contacts` **13.20.0**。

1. 确认 LSPosed 已正常运行。
2. 在模块列表启用 MySIP，作用域只选“电话 / 电话本”（`com.android.contacts`）。
3. 无通话时重启电话应用，或重启手机。
4. 在原厂拨号盘输入号码，可使用独立的 SIP 按钮。

按钮使用已有 MySIP 系统通话账户；原有 SIM 按钮继续使用各自的路由。其他版本会跳过界面修改。停用 MySIP 的 LSPosed 扩展并重启电话应用即可移除按钮；SIP 服务仍可独立使用。

## 更新常驻系统应用

Android 会拒绝用普通覆盖安装更新 `persistent` 应用。安装新版系统模块后重启，版本号应高于设备上的旧版。更新包必须使用相同签名。

Shamiko 白名单模式可能向普通应用隐藏模块挂载路径。安装脚本会检测此模式，同时暂存同版本 APK 更新；重启后从 `/data/app` 加载代码，保留系统应用和常驻身份。脚本不会变更 Shamiko 的全局配置，也不授予 MySIP Root。

此兼容安装需要在已启动的 Android 10+ 系统内执行。手动恢复可使用：

```sh
adb install -r --staged --staged-ready-timeout 0 MySIP-0.2.1.apk
adb shell pm list staged-sessions
```

确认对应会话 `isReady=true`、`isFailed=false` 后重启。

## 解锁前接听与网络

开启“解锁前接听”后，凭据额外保存在应用的设备加密私有区；默认仅在首次解锁后加载账户。系统备份已禁用。

UDP / TCP 适合可信内网或 VPN；TLS 验证证书与主机名。首次解锁前的 VPN 连通性、整夜待机、跨网络漫游仍需按实际设备验证。

## 卸载与恢复

在 Magisk 禁用或移除 `mysip_system` 后重启，再按需卸载 APK。模块卸载脚本撤销本应用的后台 AppOps 和休眠豁免。若仍保留普通安装，应用会继续作为普通 APK 存在。

出现启动异常时，可用已有 Root ADB 禁用模块：

```sh
adb shell su -c 'touch /data/adb/modules/mysip_system/disable'
```

随后重启。应用数据不由模块脚本主动清除；卸载 APK 或系统清除数据会删除应用私有配置与消息。
