# 充值相关接口与可行性

> 结论：`/card/` 下的充值接口是齐全的，但**不建议在客户端内自建支付**。
> 当前版本采用「复用业务会话 → 打开学校官方充值页」的方式：客户端只负责把会话带过去，
> 选金额、选渠道、跳转微信/支付宝/银行都由官方页面完成。

## 1. 官方充值页

充值页是 `m.icw.buaa.edu.cn/card` 这个 uni-app 的一个路由：

```
https://m.icw.buaa.edu.cn/card/#/pages_other/recharge/cardrecharge/cardrecharge?openid=<openid>&usertype=6
```

页面初始化会调用：

```http
POST /card/cardpay/openCardPay
Content-Type: application/json
Cookie: JSESSIONID=...
X-Requested-With: XMLHttpRequest
session-type: uniapp
isWechatApp: true
orgid: 2

{}
```

返回中与本项目相关的字段：

```json
{
  "success": true,
  "url": "/pages/recharge/cardrecharge/cardrecharge",
  "data": {
    "cardPay": { "idserial": "<卡号>", "cardbal": "<余额>" },
    "disableidserialstart": "<脱敏卡号前缀>",
    "appid": "<微信 appid>",
    "jsonmsg": {
      "wechat": "暂未开通",
      "alipay": "暂未开通",
      "abc": "暂未开通",
      "openPaytype": ""
    },
    "pcode": "20"
  }
}
```

`jsonmsg` 是各支付渠道的可用状态；不同账号/学期可能不同。

## 2. 支付接口清单

以下接口均以 `JSESSIONID` 为凭证，路径前缀为 `https://m.icw.buaa.edu.cn/card`。
标注「代码确认」表示从官方前端 bundle 中读出、尚未用真实账号逐笔下单验证。

| 方法 | 路径 | 关键入参 | 说明 |
|---|---|---|---|
| POST | `/cardpay/openCardPay` | `{}` | 充值页初始化，返回卡号、余额、渠道状态 |
| GET | `/queryPayInfoList` | `menuid` | 可用支付方式列表 |
| POST | `/wxpay/transferFromWx2Card` | `txamt, payWay, usertype, tradetype, idserial, openid` | 微信下单；`tradetype=WAP` 返回 `mweb_url`，`JSAPI` 需微信 JSSDK |
| GET | `/wxpay/getJssdk` | `openid, jssdkurl` | 微信 JS-SDK 签名，供 `chooseWXPay` |
| POST | `/alipay/transferFromAlipay2Card` | 同上 | 支付宝下单，返回自动提交的 HTML `form` |
| POST | `/ccbpay/transferFromCcb2Card` | 同上 | 建行下单，返回 `form` 或 `url` |
| POST | `/bankcard/rechargeCard` | `..., bankpwd` | 银校转账/银行卡直充；需 6 位校园卡密码，成功标志 `resultData.response == "0000"` |
| POST | `/recharge/rechargeByHct` | `txamt, ...` | 航财通通道，返回 `mweb_url` |
| POST | `/myaccount/userlastbind` | `payinfo.cardpayWay, idserial` | 记住上次支付方式 |

支付方式编码 `payway`：`1` 微信、`2` 建行、`4` 支付宝、`6` 校园卡密码/银校转账、`10` 航财通。
金额约束（官方前端校验）：单次 `> 0` 且 `<= 1000` 元；校园卡初始密码不允许充值。

## 3. 能否在客户端内实现充值？

| 方案 | 可行性 | 主要问题 |
|---|---|---|
| 原生实现所有渠道 | ⚠️ 技术上可拼，但不建议 | 微信要 JSSDK/`chooseWXPay`，支付宝要表单/App 跳转，建行/HCT 各有专有流程；要处理下单、回调、订单状态、退款与对账；任何一步出错都可能造成「钱扣了、卡没到」 |
| 原生调用银校转账（`/bankcard/rechargeCard`） | ✅ 单个请求可通 | 需要用户输入 6 位校园卡密码；客户端要接触并可能落盘资金凭据；初始密码、绑卡状态、银行侧失败原因都不可控；风险远高于收益 |
| WebView 打开官方充值页 | ✅ 推荐 | 需要把现有 `JSESSIONID` 注入 WebView；支付仍由官方页面完成；会话过期时页面会报错 |
| 外部浏览器打开 | ❌ | 浏览器没有本机的 `JSESSIONID`，用户要重新走一次统一身份认证；体验差且容易误以为已登录 |

**结论**：客户端最适合承担「入口」而不是「收银台」。充值页面本身已经把金额校验、渠道选择、
第三方支付跳转和结果页都做好了，复用官方页面既少写代码，也避免把资金操作和卡密码带进客户端。

## 4. 航财通的统一认证链路（v0.3.0 起）

航财通（校园付）的登录页本身提供「统一认证登录」：

```http
GET https://www.cc-pay.cn/login?channel=BUAASSO
  → 302 https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fpass.cc-pay.cn%2Flogin
  → 带 CASTGC 时 302 https://pass.cc-pay.cn/login?ticket=ST-…
  → 设置 .cc-pay.cn 会话，回到充值页
```

**之前为什么会让用户再登录一次**：充值 WebView 里只注入了 `m.icw.buaa.edu.cn` 的
`JSESSIONID`。`sso.buaa.edu.cn` 是另一个域，WebView 没有 `CASTGC`，CAS 只能显示登录表单。

**现在的处理**：打开充值页前，除了 `JSESSIONID`，还把本地保存的 `CASTGC` 注入
`sso.buaa.edu.cn` 的 Cookie。航财通跳到 CAS 时直接换票，不再要求输入密码。退出充值页时
清掉 WebView 里的这两份 Cookie；App 自己的加密会话不受影响，下次进入会重新注入。

> 如果仍然看到登录页，通常是 `CASTGC` 已过期（App 的会话也接近失效），
> 或航财通侧强制重新认证。重新登录一次 App 刷新 `CASTGC` 即可。

## 5. 能不能绕过官方充值页面？

可以，但**不解决航财通的登录问题**：

```
直接调用：POST /card/cardpay/openCardPay
          ↓ 用户填金额
          POST /card/recharge/rechargeByHct  → mweb_url
          ↓
          WebView 带 JSESSIONID + CASTGC 打开 mweb_url
```

这条路径只是少了一层官方表单，后面的 `mweb_url` 仍然会走
`www.cc-pay.cn → sso.buaa.edu.cn`，仍然需要 `CASTGC`。与此同时，客户端开始直接调用
资金下单接口，要自己处理金额校验、重复下单、订单状态、失败回滚与对账，风险明显更高。
因此当前版本仍以官方充值页为收银台，只做会话注入。

## 6. 当前实现

`ui/RechargeActivity.kt`：

1. 进入前检查本地会话；必要时用 `CASTGC` 续期；
2. 把 `JSESSIONID` 注入 `WebView` 的 `CookieManager`（`m.icw.buaa.edu.cn`），
   同时把 `CASTGC` 注入 `sso.buaa.edu.cn`；
3. 打开上面的官方充值页，`openid` 放在 hash 路由的 query 里；
4. 处理 `weixin://`、`alipays://`、`intent://` 等外部支付跳转；
5. 支付完成回到 App 后，识别成功页并自动返回通财航首页；
6. 退出时清掉注入的两份 Cookie，并销毁 WebView。

充值页顶部不再显示说明横幅，刷新改到 Toolbar 的刷新按钮。

客户端**不会**：

- 读取或保存校园卡密码；
- 调用任何 `transferFrom*` / `rechargeCard` / `rechargeByHct` 下单接口；
- 保存订单号、支付结果或银行卡信息。

## 7. 已知风险

- 官方页面路由/接口随校方发版可能变化；
- 部分账号的 `jsonmsg` 显示「暂未开通」，这是校方渠道状态，不是客户端问题；
- WebView 的第三方 Cookie/支付 App 跳转行为受 ROM 与支付 App 版本影响；
- 会话过期后 WebView 不会自动续期，需要返回 App 重新登录。
