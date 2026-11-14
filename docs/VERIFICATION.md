# 验证记录（v0.2.0 · Kotlin 版）

日期：2026-11-14
项目：`/Users/muzermat/Desktop/buaa/Buaa-SCP`
包名：`cn.edu.buaa.scp` · versionCode 7 · versionName `0.2.0-debug`

## 1. 环境

| 组件 | 版本 |
|---|---|
| macOS | Apple Silicon（arm64） |
| JDK | Oracle JDK 21.0.8 |
| Gradle | 8.8（wrapper） |
| AGP | 8.6.1 |
| Kotlin | 2.0.21（kotlinx-coroutines 1.8.1） |
| Android SDK | Platform 35 / Build-Tools 35.0.0 |
| 模拟器 | Android 15（API 35, google_apis, arm64-v8a, 1080×2340） |
| 真机 | iQOO 15（V2505A）· Android 16（API 36）· 1440×3168 @640dpi |

## 2. 自动化测试

```
./gradlew :app:testDebugUnitTest
→ 49 tests completed, 4 skipped, 0 failed
```

4 项真实链路探针独立运行时全部通过：

```
SCP_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest -Plive=1 --tests 'cn.edu.buaa.scp.LiveChainTest'
→ 4 PASSED
```

| 测试类 | 覆盖点 | 测试数 |
|---|---|---|
| `CryptoTest` | handleKey、Python 独立生成的 AES 向量、中文往返、异常输入 | 5 |
| `HtmlTest` | 脱敏真实 CAS 登录页夹具（execution/_eventId/type）、属性顺序、错误文案、URL 编码、失败页解码 | 9 |
| `CookieJarTest` | 跨域隔离、多 Set-Cookie、Path 作用域、Max-Age 删除、Domain 匹配、Secure、恢复/清空 | 7 |
| `JsonTest` | 分→元、金额格式、月份运算、当月天数（含闰年）、月份列表、卡类型、脱敏 | 8 |
| `CasAuthTest` | 哨兵 openid 回调地址、authUrl 编码、noAutoRedirect、脱敏 | 4 |
| `ModelsTest` | 首页/账单/流水/卡务模型；含线上真实流水结构（`data.data[]`）与缺失 totalamt 的兜底 | 12 |
| `LiveChainTest` | CAS 登录页 200 + execution、入口 302、匿名业务接口 403、三项自检 | 4 |

## 3. 真实链路探针实测值

| 探测 | 结果 |
|---|---|
| `GET sso.buaa.edu.cn/login?service=…&noAutoRedirect=1` | HTTP 200，execution 令牌 **5701 字符** |
| `GET m.icw.buaa.edu.cn/card/home/openHomePage?usertype=6&code=DSF` | HTTP 302 → `/card#/pages/bind/login/login` |
| `POST /card/cardpay/openCardPay`（匿名） | HTTP **403**（未认证请求被正确拒绝） |
| `POST sso.buaa.edu.cn/login`（错误凭据，模拟器实测） | HTTP **401**，页面含 `Invalid credentials` + `Validation Code`，App 解析为可读中文提示 |

> `m.icw.buaa.edu.cn` 使用 **CFCA** 证书链，桌面 JDK 的 cacerts 默认不信任、Android 系统信任库可正常校验。
> 因此 JVM 探针内临时放宽证书校验，仅验证协议与连通性；设备侧证书校验以「网络自检」为准。

## 4. 模拟器实测（Android 15）

| 项目 | 结果 |
|---|---|
| 安装 / 启动 | ✅ 无崩溃 |
| 网络自检 | ✅ 三项全绿：统一身份认证 1195ms / 入口 1360ms / 业务接口（匿名 403） |
| 错误凭据登录 | ✅ 走完 GET 登录页 → POST 凭据，正确解析 CAS 401 错误页 |
| 运行日志 | ✅ 逐跳记录 `STEP` / `HTTP`（host+path 脱敏，无 Cookie 原文） |

## 5. 真机实测（iQOO 15 / Android 16，2026-11-14 19:03–19:16）

### 5.1 安装

vivo 有两层安装管控，均需在手机上确认：

1. 「"Shell"请求使用安装权限」→ 点 **允许**（勾选「不再提醒」后，后续重装不再询问）；
2. 「超级守护正在深度检测…」→ 检测完成后自动放行（若出现「授权本次安装」，点该链接）。

最终 `dumpsys package cn.edu.buaa.scp`：`versionCode=7 versionName=0.2.0-debug`。

### 5.2 真实账号登录与数据

| 页面 | 实测内容 | 结论 |
|---|---|---|
| 登录 | 学号 + 密码 → CAS 302 票据 → `goIndex?openid=…` → JSESSIONID | ✅ |
| 首页 | 余额 **¥429.17**、状态「正常」、学生卡、姓名、学号、支付渠道「暂未开通」 | ✅ |
| 账单 | 2026-11 合计 **¥80.58**（食堂消费 100%）；按月汇总 6 个月；趋势合计 **¥662.17** | ✅ |
| 流水 | 2026-11 共 5 笔真实消费（北区学生第三食堂 / 航味星语烘焙坊），合计 **¥80.58**，分页尾部「没有更多数据了」 | ✅ |
| 卡务 | 姓名/卡号/状态/卡类型/余额，只读提示 | ✅ |
| 设置 | JSESSIONID 32 字符、CASTGC 91 字符、登录/刷新时间、工具按钮 | ✅ |
| 免密恢复 | 重装/重启 App 后自动恢复会话（CASTGC 换票） | ✅ |

> 校验：流水 5 笔金额 16.80+28.18+11.80+9.00+14.80 = **80.58**，与账单/趋势一致，说明解析与换算正确。

### 5.3 真机暴露并修复的问题

| # | 现象 | 根因 | 修复版本 |
|---|---|---|---|
| 1 | 趋势「合计 ¥0.00」、消费汇总 ¥0.00 | 官方接口未返回 `totalamt/totalcount`（缺 `chartId`） | 0.1.1：按 `billList` 求和兜底 |
| 2 | 流水「未查询到记录」，但接口 200 且返回 1719B | ① 必须带 `openid` 查询参数；② 响应结构是 `{"data":{"data":[…]}}`，不是 `resultData.rows`；③ 无 `totalpage` | 0.1.2/0.1.4：带 openid（失败回退）+ 双结构解析 + 按「是否满一页」推断分页 |
| 3 | 顶栏标题被状态栏压住 | Android 15+ 对 targetSdk 35 强制 edge-to-edge | 0.1.5：消费 statusBar/navBar/IME insets |
| 4 | 「充值记录」数值与支出趋势完全一致 | 该接口按月汇总之口径疑似为全量交易汇总，非仅充值 | 0.2.0：改名为「按月汇总」并加口径说明；逐笔充值请在「流水」选「充值」 |

## 6. 尚未覆盖

1. **CAS 验证码 / 二次验证**：若触发 `Validation Code`，v0.1 无验证码输入，需先在浏览器完成一次验证；后续可加图形验证码支持；
2. **写操作**：按需求本版本不含充值、支付、挂失，故未测试（也不调用相关接口）；
3. **其他机型/ROM**：仅 Android 15 模拟器与 iQOO 15 真机；小米/华为等安装与 edge-to-edge 表现未验证；
4. **接口发版风险**：字段与加密方式随校方发版可能变化（已用双结构解析 + 容错取值降低影响）。

## 7. 复现命令

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
SCP_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest -Plive=1 --tests 'cn.edu.buaa.scp.LiveChainTest'

./install.sh    # 手机 USB 调试授权后一键安装并启动
```
