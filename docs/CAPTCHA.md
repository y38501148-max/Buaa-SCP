# CAS 图形验证码：机制与实现

北航统一身份认证（`sso.buaa.edu.cn`）在密码错误次数达到阈值后，会在登录页下发图形验证码。
v0.3.0 起客户端支持直接输入，不再需要先跳到浏览器完成验证。

## 1. 服务端下发的形状

正常登录页只有账号、密码和隐藏字段。触发验证码后，页面里会多出一段内联脚本：

```html
<script>
  config.captcha = { type: 'image', id: 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx' };
</script>
```

移动端页面（`/cas/login-mobile.html`）用这段配置拼出图片地址：

```
GET https://sso.buaa.edu.cn/captcha?captchaId=<id>&t=<timestamp>
```

刷新验证码时 `captchaId` 不变，只换 `t`；服务端会按同一个 id 重新生成字符。

## 2. 提交方式

登录表单仍然是 `POST https://sso.buaa.edu.cn/login?service=...`，在原有字段之外增加：

| 字段 | 值 |
|---|---|
| `username` | 学号 |
| `password` | 密码 |
| `type` | `username_password` |
| `execution` | 登录页里的隐藏字段 |
| `_eventId` | `submit` |
| `captcha` | 用户输入，**转小写**后提交 |

`execution` 是一次性的。登录失败后 CAS 返回的新页面会带**新的 execution** 和**新的 captcha id**；
必须用新页面重新提交，不能复用旧 execution，也不能用旧验证码。

## 3. 客户端状态机

```
GET /login
  ├─ 无 config.captcha → 直接 POST 账号密码
  └─ 有 config.captcha → 返回 LoginPage(execution, captcha)
        │
        ├─ 用户未输入 → 只提示「请输入验证码」，不 POST
        └─ 用户输入 → POST（execution + captcha）
              ├─ 302 + ticket → 回调换 JSESSIONID
              └─ 失败页 → 解析新 execution + 新 captcha → 刷新图片，重试
```

对应实现：

- `net/Html.kt`：`captcha()` 解析 `config.captcha`、JSON 形式与 `captchaId=` 图片地址；
- `net/CasAuth.kt`：`LoginPage` 保存 execution + captcha；`captchaImage()` 取 PNG；
- `ui/LoginActivity.kt`：验证码输入框 + 图片，点击图片刷新。

## 4. 边界情况

- 验证码类型不是 `image`（例如滑块）：客户端会明确提示暂不支持，不会盲目提交；
- 验证码图片加载失败：图片区域显示「加载失败，点击重试」；
- 账号被临时锁定：CAS 返回 HTTP 423，客户端提示「登录尝试过多」；
- `CASTGC` 免密续期流程不会经过验证码；验证码只影响密码登录。
- 会话 Cookie 必须与验证码图片请求共用同一个 `CookieJar`，否则图片和校验对不上。

## 5. 测试

离线单测覆盖了真实页面夹具（无验证码）、内联脚本、JSON、图片地址四种形状；
UI 层用调试构建注入一个合成 `LoginPage`，验证图片加载、输入框和点击刷新（见
`docs/screenshot_captcha.png`）。真实触发验证码需要连续输错密码，不建议在自动化里做。
