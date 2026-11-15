# 支付二维码与扫一扫

0.8.0 在首页直接显示支付二维码，标题居中，余额与卡状态显示在同一卡片底部。下方依次为网费、快捷入口；「充值」保留在快捷入口，「扫一扫」是首页右上角图标。二维码每 15 秒自动刷新，点击二维码也会刷新，没有独立刷新按钮。

## 接口依据

2026-11-16 访问用户提供的官方入口时，服务返回资源受限，统一认证需要重新登录。本轮协议依据为工作区此前保存的官方前端包；入口引用的主包文件名仍为 `index.7784c3e2.js`。本轮没有取得新的登录会话，也没有提交生产扫码交易。

- 取码：`pages_other-qrcode-qrcode-qrcode.e324c6d4.js` 中的 `initPage`、`Str2Bytes`、`queryOrder`。
- 扫码：`pages-homepage-index-index.83722cb6.js` 中的 `scanQRCode`。
- 取码页面：`https://m.icw.buaa.edu.cn/card/#/pages_other/qrcode/qrcode/qrcode`。
- 相机与二维码编解码使用 [ZXing Android Embedded 4.3.0](https://github.com/journeyapps/zxing-android-embedded/tree/v4.3.0)。

接口前缀为 `https://m.icw.buaa.edu.cn/card`，HTTPS 默认端口 443。

| 功能 | POST 路径 | 业务参数 |
|---|---|---|
| 生成支付码 | `/offlineCode/openVirtualcard` | `openid`；官方另支持可选 `paytype`，客户端未覆盖服务端默认值 |
| 查询支付结果 | `/virtualcard/queryOrderStatus` | `openid`、`paycode`（取码响应中的原始 `code`） |
| 提交扫码 | `/scan/scanningConsume` | `openid`、`paytime`（毫秒时间戳）、`qrcode`（扫描原文） |

请求携带现有 `JSESSIONID`。URL 追加 `openid`，请求体补充 `openid` 和 `orgid: "2"` 后，通过 `Crypto.wrapRequest` 生成 `datajson` 信封。固定头部沿用 `x-requested-with`、`session-type`、`isWechatApp`、`orgid`。响应支持明文 JSON 和加密信封。

## 支付码处理

`data.code` 是码串，`data.cardbal` 是余额。以 `5638` 开头的码按十六进制还原字节，再以 ISO-8859-1 字符串交给 QR 编码器；这里不添加 UTF-8 编码提示，避免 `80`—`FF` 的字节被扩展。其他码按 UTF-8 文本编码。二维码保留四模块留白。

官方页面固定每 15 秒取码、每 3 秒查支付结果；15 秒是刷新周期，服务端未在该前端逻辑中提供有效期字段。客户端从请求开始计时，达到刷新截止时间先清除旧码；独立清码计时器使结果查询阻塞时也不会继续显示旧码。取码失败后显示错误，点击二维码区域可以重试。

二维码加载独立于余额、网费加载。首页隐藏、应用进入后台或打开扫码页时，停止取码与轮询并清除位图；返回首页重新获取。支付码、扫描内容、原始响应不落盘，支付接口跳过 DebugDump；正式会话的首页及扫码页启用 `FLAG_SECURE`，离线预览保留截图能力。

## 扫码提交

相机支持 QR 与 CODE_128，申请运行时权限；拒绝后可进入应用设置重新授权。有闪光灯的设备显示手电筒按钮。

识别后暂停相机，用户确认才调用 `scanningConsume`。该接口可能直接扣款，也可能签到、开阀或绑定餐盘。客户端按返回的 `type` 显示结果。提交期间禁止重复提交；网络异常不自动重试，会话失效也不自动续期重放。Activity 重建后不恢复提交。

`resultData.url` 表示还需要进入官方页面，`success=false` 时也保留这个分支。客户端仅接受 `/pages/` 或 `/pages_other/` 内部路由，编码 `resultData` 后交给官方 WebView，注入当前校园卡会话。WebView 关闭文件访问、混合内容与缓存，限制主页面导航到学校校园卡域名，返回时不回退重放提交。未开通校园码时显示服务端错误，本轮不自动开通或变更扣款设置。

## 验证范围

单元测试覆盖官方信封解包、二进制二维码编码/解码往返、异常码串、会话过期、扫码字段、业务结果与后续页面路由。模拟器检查离线首页、手动/自动刷新、标签切换和相机权限流程。2026-11-16 已通过 ADB 覆盖安装至用户实机，用户自行验收后同意发布；各支付分支的逐项验证范围见 [验证记录](VERIFICATION.md)。
