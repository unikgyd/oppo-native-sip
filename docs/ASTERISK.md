# Asterisk MESSAGE 接入示例

以下使用示例分机 `1001`，请替换为自己的端点。示例仅演示已鉴权端点的内部回环，不包含生产运营商短信路由。

在已有 PJSIP endpoint 上设置：

```ini
; pjsip.conf，已有 endpoint 的相关选项
message_context=mysip-messages
force_rport=yes
rewrite_contact=yes
rtp_symmetric=yes
direct_media=no
```

在拨号计划中添加测试上下文：

```ini
; extensions.conf
[mysip-messages]
exten => 1001,1,NoOp(MySIP local MESSAGE loopback)
 same => n,MessageSend(pjsip:1001,${MESSAGE(from)})
 same => n,Hangup()
```

接收方向应向分机当前 Contact / 连接投递 MESSAGE，呼入通常使用 `Dial(PJSIP/1001)`。保留真实发件号码，并在网关侧实现外发短信的号码路由。

MySIP 使用 `text/plain`、UTF-8 正文，默认关闭 CPIM、输入状态和主动 IMDN XML。若上游使用 IMS `application/vnd.3gpp.sms`，需要上游网关完成 RPDU 与文本转换。

`200 / 202` 仅表示 SIP 服务接受，不表示最终手机送达。若鉴权后仍没有收到回环，应检查 `message_context`、拨号计划和分机在线 Contact。

参考：[Asterisk MessageSend 官方文档](https://docs.asterisk.org/Latest_API/API_Documentation/Dialplan_Applications/MessageSend/)。
