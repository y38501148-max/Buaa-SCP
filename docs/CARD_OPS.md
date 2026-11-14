# 卡务写操作（其他功能）

> v0.5.0 起，原「卡务」页删除，六项卡务操作合并到首页「快捷入口 → 其他」。
> 这些接口会**真实改变校园卡状态**，请谨慎操作。

## 1. 功能与接口

| 功能 | 初始化读（POST） | 提交 |
|---|---|---|
| 校园卡挂失 | `/losscard/openCardReportLoss` `{}` | `POST /losscard/cardReportLoss` |
| 校园卡解挂 | `/losscard/openCardsolutionHang` `{}` | `POST /losscard/CardsolutionHang`（大写 C） |
| 密码修改 | `/virtualcard/openQrcodePwdModify` `{}` | `POST /virtualcard/qrcodePwdModify` |
| 限额修改 | `/virtualcard/openQrcodeQuotaModify` `{}` | `GET /card/cardQuotaModify` / `GET /virtualcard/qrcodeQuotaModify` |
| 绑定银行卡 | `/bankcard/getBankCard` `{}` | `POST /bankcard/addbankcard` |
| 银行卡解绑 | `/bankcard/getBankCard` `{}` | `POST /bankcard/unbindBankCard` |

BASE：`https://m.icw.buaa.edu.cn/card`

固定请求头：

```
x-requested-with: XMLHttpRequest
content-type: application/json
session-type: uniapp
isWechatApp: true
orgid: 2
```

鉴权只认 `JSESSIONID` Cookie；`openid` 是写接口的一致性问题参数。

## 2. 请求信封（与官方前端一致）

官方所有业务请求都不是明文 JSON，而是 AES 信封：

1. 随机 16 个 `[A-Za-z0-9]` 字符前缀 `P`；
2. `K = handleKey(P) = reverse(P)[6:] + reverse(P)[:6]`；
3. AES-128-ECB / PKCS7 加密 `JSON.stringify(payload)`，标准 Base64；
4. `datajson = P + base64(cipher)`。

- **写 POST**：body `{"datajson":"..."}`，URL 追加 `?openid=<openid>`，payload 内补 `openid` 与 `orgid:"2"`；
- **写 GET**：整个 payload 加密后作为唯一的 `?datajson=` 参数，不拼明文 query。

实现见 `net/CardApi.kt`（`writePost` / `writeGet`）与 `net/Crypto.kt`（`wrapRequest`）。
`CryptoTest` 中有官方 bundle 的加密测试向量。

## 3. 各接口字段

### 3.1 挂失 / 解挂

```json
{"openid":"<openid>","username":"张同学","idserial":"20250001","bankpwd":"<6位校园卡密码>","orgid":"2"}
```

成功只判 `success === true`，不依赖官方前端里 `data.data.m1` 这种不稳定字段。

### 3.2 密码修改

```json
{"idserial":"20250001","oldpassword":"111111","newpwd":"222222","txpassword":"222222","openid":"<openid>","flag":1,"orgid":"2"}
```

前端校验：原密码/新密码/确认密码均为 6 位数字，两次新密码一致。

### 3.3 限额修改

校园卡（GET）：

```json
{"openid":"<openid>","idserial":"20250001","maxconsamt":"5000","maxconstolamt":"20000","cardid":"...","txpasswd":"<6位密码>","orgid":"2"}
```

校园码（GET）：

```json
{"openid":"<openid>","idserial":"20250001","maxconsamt":"5000","maxconstotalamt":"20000","qrcodeid":"...","txpasswd":"<6位密码>","orgid":"2"}
```

- 金额单位是**分**，客户端从「元」输入框换算；
- 注意校园码字段是 `maxconstotalamt`（比校园卡少一个 `l`）；
- 官方前端实际拦截上限为 2000 元（页面文案存在 1500/10,000 等矛盾，客户端按 2,000 元执行）；
- 实测初始化接口可能不返回 `qrcodeid`，此时客户端不展示校园码限额表单。

### 3.4 绑定 / 解绑银行卡

```json
// 绑定（新增/更换共用）
{"openid":"<openid>","idserial":"20250001","bankcdno":"6222...","wechatui":true,"orgid":"2"}

// 解绑
{"openid":"<openid>","idserial":"20250001","bankcdno":"6222...","bankpwd":"<6位校园卡密码>","wechatui":true,"orgid":"2"}
```

- 绑定不要求校园卡密码，但需要输入两遍卡号；
- 银行卡号校验：16–19 位数字、前缀在官方允许列表、Luhn 校验通过；
- 解绑参数名虽是 `bankpwd`，实际是**校园卡密码**（官方页面文案混用了「银行卡密码」）；
- 银行卡号只提交给学校系统，客户端不保存。

## 4. 客户端安全约束

- 校园卡密码只从输入框读取，提交后随 Activity 结束丢弃，不写 SharedPreferences、不写日志、不上报；
- 每次提交前使用系统弹窗二次确认；
- 写操作失败不自动重试，避免重复提交；
- 会话过期时提示重新登录，不自动用旧会话反复提交；
- 成功后回到首页并触发刷新（卡状态、限额等可能变化）。

## 5. 验证状态

- 6 个初始化读接口已用真实账号验证；
- 请求信封已用官方 bundle 测试向量验证（`CryptoTest.requestEnvelopeMatchesOfficialVector`）；
- 出于安全考虑，**六个写接口尚未在生产账号上真实提交**。发布前建议做一次低风险验证：
  把限额改成原值 → 确认成功 → 再改回；挂失/解挂、改密、绑卡/解绑不要在验证阶段触发。
