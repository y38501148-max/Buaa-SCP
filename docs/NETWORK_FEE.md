# 网费：余额、流量与充值

> 本文记录 v0.5.0 网费功能的三个数据源、登录方式与充值链路。
> 所有结论来自 2026-11-14 的真实账号只读验证；写操作（支付）未在文档中真实完成。

## 1. 数据源

| 展示项 | 来源 | 接口 |
|---|---|---|
| 网费余额 | i北航融合门户 `it.buaa.edu.cn` | `GET /personal/frontend/data/items?type=personal_data` → 找 `net.netFee` 的 id → `GET /personal/frontend/data/detail?id=<id>` |
| 剩余免费流量 | 校园网自助服务 `zfw.buaa.edu.cn` | `GET /home`，产品信息表「免费流量剩余(不含套餐)」 |
| 剩余计费流量 | 校园网自助服务 `zfw.buaa.edu.cn` | `GET /home`，产品信息表「计费流量剩余(不含套餐)」 |

实测样例（已脱敏）：

```json
{"name":"网费","value":103.79,"unit":"","url":"https://zfw.buaa.edu.cn/sso/cas/index"}
```

门户指标值可能是 Double（`103.79000000000001`），客户端统一格式化为两位小数。

## 2. 门户余额的鉴权

客户端已有统一认证 `CASTGC`，不需要再输密码：

```
GET https://it.buaa.edu.cn/personal/frontend/data/items?type=personal_data
  → 302 /manage/common/login/index
  → 302 /manage/common/cas_login/30001?to_bind=0&redirect=...
  → 302 https://sso.buaa.edu.cn/login?service=...
      （携带 CASTGC，CAS 直接签票）
  → 302 /manage/common/cas_login/30001?...&ticket=ST-...
      （门户落下 SESS_ID / CK_LC / CK_VL）
  → 302 /personal/frontend/data/items
  → 200 JSON
```

实现见 `net/PortalApi.kt`。会话失效会抛 `authExpired`，由 `ScpApp.withRecovery` 先续期校园卡会话再重试一次。

## 3. 校园网登录（与统一认证密码不同）

- 未登录访问 `zfw.buaa.edu.cn/home`、`/pay/card` 都会 302 到登录页；
- 登录页是深澜（SRun）系统，账号为校园网账号，密码与统一身份认证**不同**；
- 密码使用页面每次随机下发的 RSA 公钥加密，部分账号还会触发短信验证码；
- 因此客户端不在原生层复刻登录，而是用 WebView 打开官方登录页，用户手动完成一次；
- 登录后通过 `CookieManager.getCookie("https://zfw.buaa.edu.cn/")` 读取会话 Cookie（含 HttpOnly），
  用 AndroidKeyStore 加密保存；之后原生 `GET /home` 只需 `PHPSESSID_8800` 即可。

实现见 `ui/ZfwActivity.kt`、`net/ZfwApi.kt`、`store/ZfwSession.kt`。

## 4. /home 解析

产品信息表按表头定位 `data-col-seq`：

| 列 | 字段 |
|---|---|
| 1 | 产品名称 |
| 2 | 计费策略 |
| 3 | 已用流量 |
| 4 | 已用时长 |
| 6 | 免费流量剩余(不含套餐) |
| 7 | 计费流量剩余(不含套餐) |
| 12 | 结算日期 |

注意不能按 `<td>` 顺序取：在线信息表也使用 `data-col-seq`，必须先定位「产品名称」表头。
流量单位可能是 `G`、`M`、`byte`、`T`，展示统一成 `GB/MB/KB`，`0byte` 显示为 `0`。

实现见 `net/ZfwHtml.kt`，测试夹具 `app/src/test/resources/html/zfw_home.html`（已脱敏）。

## 5. 网费充值与二维码自动接管

官方充值页：`https://zfw.buaa.edu.cn/pay/card`

- 表单字段：`OneCardForm[productId]`、`OneCardForm[amount]`、`OneCardForm[verifyCode]`；
- 图形验证码：`/pay/captcha`；
- 提交后返回「北航手机支付」页面，二维码图片为：

```html
<img src="/pay/qrcode?url=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Fid%3D<订单id>">
<input id="orderid" value="2026...">
```

即二维码内容是航财通收银台 URL，**不需要解码图片**。

### 5.1 自动接管流程

1. 识别到 `/pay/qrcode?url=` 后，提取 `url` 参数并只接受 `https://cashier.cc-pay.cn/...`；
2. 切换 WebView UA 为移动 Chrome UA；
3. 注入统一认证 `CASTGC` 到 `sso.buaa.edu.cn`；
4. 打开
   `https://pass.cc-pay.cn/login?backUrl=<encoded cashier>&channel=BUAASSO`，
   静默完成统一认证并回到收银台；
5. 收银台在移动 UA 下请求 `GET /api/pay_ways?payScene=wap&goodsId=...`，返回
   `wxpay_wap`（微信 H5 支付）、`alipay_wap`（支付宝手机网站支付）等；
6. 用户点「微信支付」时页面请求
   `GET /transaction/pay?id=<订单id>&payWayId=<wxpay_wap id>`，
   返回 `payUrl`（`https://wx.tenpay.com/...checkmweb...`），WebView 交给微信 App 完成支付；
7. 回到 App 后按订单 id 轮询 `GET /transaction?id=<订单id>`，`status=success` 即视为支付成功，
   回到首页刷新。微信/支付宝返回时也走同一检查。

实现见 `ui/ZfwActivity.kt`、`net/ZfwPay.kt`。

### 5.2 兜底

- 如果二维码不是航财通收银台，或本地没有 `CASTGC`，保留原二维码页面，用户仍可保存图片后手动扫码；
- 订单 5 分钟未支付会自动关闭，客户端会提示重新发起；
- 客户端不调用下单/支付确认接口，支付动作始终由用户在微信/支付宝内完成。

### 5.3 实测边界

- 已验证门户余额接口、zfw `/home` 解析、收银台 `payScene=wap` 与 `wxpay_wap` 的 `payUrl` 返回；
- 未在真机完成一笔真实网费支付（需要用户在微信内确认扣款）；
- 校外访问 zfw 可能受校园网限制，客户端会区分「网络受限」与「未登录」。

## 6. 相关文件

- `net/PortalApi.kt`：门户网费余额
- `net/ZfwApi.kt`、`net/ZfwHtml.kt`：校园网首页与解析
- `net/ZfwPay.kt`：二维码 URL 解析
- `ui/ZfwActivity.kt`：校园网登录 / 流量查看 / 充值 / 收银台接管
- `store/ZfwSession.kt`：校园网会话加密持久化
