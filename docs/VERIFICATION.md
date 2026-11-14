# 验证记录

## v0.6.0（2026-11-15）

包名：`cn.edu.buaa.scp` · versionCode 11 · versionName `0.6.0` · 独立 release keystore

### 1. 自动化测试

```
./gradlew :app:testDebugUnitTest
→ 136 tests, 0 failed, 8 skipped
```

新增 / 更新：

- `FinanceModelsTest`（22）：财务分页两种结构、金额分换算、按年月分组、年度、搜索、实发/应发/扣税合计；
- `FinanceApiTest`（4）：财务登录跳转地址规范化（https 升级、去端口、相对地址、未知 host）；
- `RechargeFailureTest`（17）：充值失败原因分类（缴费时段 / 会话失效 / 其它）与失败页 URL 解析；
- `ModelsTest`：账单合并模型、`BillRecharge` 充值流水按月汇总、流水解析与分页。

真实链路探针（`Live*Test`）默认跳过，沿用 v0.5.0 的本机网络环境。

### 2. 构建

- `./gradlew :app:assembleDebug` / `:app:assembleRelease` 均通过；
- release APK：`app/build/outputs/apk/release/app-release.apk`（versionCode 11 / 0.6.0）。

### 3. 模拟器（离线示例数据）

| 项目 | 结果 |
|---|---|
| 底部导航 | ✅ 首页 / 账单 / 财务 / 设置，图标与标题正常 |
| 首页快捷入口 | ✅ 账单 / 财务 / 充值 / 其他 |
| 账单页 | ✅ 月份选择、消费/充值概览、消费分类、近半年充值、支出趋势、类型筛选与明细分页 |
| 财务页 | ✅ 年度/全部筛选、搜索、实发合计、按月分组、记录详情弹窗 |
| 设置 → 关于 | ✅ 仅保留版本号、GitHub 仓库、检查更新 |
| 截图 | ✅ `docs/screenshot_home.png`、`screenshot_bills.png`、`screenshot_finance.png`（均为示例数据） |

### 4. 真机（iQOO 15 / Android 16）

0.6.0 release 以同签名覆盖安装到 0.5.0 之上，原有登录态保留。

| 项目 | 结果 |
|---|---|
| 覆盖安装 | ✅ versionCode 11 / `0.6.0`，未重新登录即可进入首页 |
| 财务查询 | ✅ 真实链路跑通：统一认证 → icw 财务门户 → 财务查询系统，读到本人收入记录并正常按月分组 |
| 充值金额口径 | ✅ 定位并修复：官方 `queryChartList` 返回「开放平台服务异常」拿不到 chartId；`queryRechargeGroupByMonth` 返回的逐月金额与消费趋势完全一致（消费口径）。改为按 `queryCardSelfTradeList`（`tradetype=2`）的充值流水按月汇总；修复后真机概览「消费」「充值」显示不同金额，充值卡片按月笔数与充值明细一致 |
| 概览排版 | ✅ 消费 / 充值标签与金额顶对齐；充值不再显示笔数，避免两列高度不一致 |
| 缴费时段外提示 | ⚠️ 当前处于缴费时段，未能在真机复现「不在缴费期间」；已用单元测试覆盖分类逻辑与服务端真实报文（`不在缴费期间`、`未认证的请求`） |
| 更新检查 | ✅ 当前 GitHub latest 仍为 0.5.0，手动检查提示已是最新版本（符合预期；发布 v0.6.0 后旧版本会收到更新弹窗） |

### 5. 未覆盖 / 待发布后验证

- 财务记录超过 100 条时的多页读取（本次账号 16 条）；
- 发布 v0.6.0 Release 后，从 0.5.0 发起的应用内更新下载、校验与安装流程；
- 非缴费时段真机提示（需等校方缴费入口关闭时验证）。

---

## v0.5.0（历史）

日期：2026-11-14
包名：`cn.edu.buaa.scp` · versionCode 10 · versionName `0.5.0`
签名：独立 release keystore（非 debug）

> 本轮按要求在代码与构建完成后暂停，**尚未进行完整真机验收**；
> 用户连接真机后再补最后一节。已完成的验证如下。

## 1. 环境

| 组件 | 版本 |
|---|---|
| macOS | Apple Silicon（arm64） |
| JDK | Oracle JDK 21.0.8 |
| Gradle / AGP / Kotlin | 8.8 / 8.6.1 / 2.0.21 |
| Android SDK | Platform 35 / Build-Tools 35.0.0 |
| 模拟器 | Android 15（API 35, arm64-v8a, 1080×2340） |
| 真机 | iQOO 15（V2505A）· Android 16（API 36）· 暂未参与 v0.5.0 完整验收 |

## 2. 自动化测试

```
./gradlew :app:testDebugUnitTest
→ 91 tests, 0 failed, 7 skipped
```

本轮新增：

- `CardOpsTest`：六个写操作的字段名、金额分/元换算、密码与银行卡号校验；
- `ZfwHtmlTest`：zfw 首页解析（用户信息、三列流量、在线设备、登录页、空表）；
- `ZfwPayTest`：二维码 `url` 参数解析、收银台域名白名单、订单 id；
- `ZfwSessionTest`：校园网 Cookie 解析与加密会话序列化；
- `PortalApiTest`：门户网费余额格式化；
- `CryptoTest`：新增官方请求信封测试向量；
- `LivePortalTest` / `LiveZfwTest`：真实链路探针（默认跳过，本轮用真实 `CASTGC` 与 zfw Cookie 跑通）。

真实链路结果（不写账号信息）：

| 探针 | 结果 |
|---|---|
| 门户 `net.netFee` | ✅ 成功取到网费余额（两次分别为 103.79 / 104.79，随消费变化） |
| zfw `/home` | ✅ 成功解析用户、免费/计费流量、在线设备数 |

## 3. 构建

```
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

- debug / release 均构建成功；
- release APK 已生成（`app/build/outputs/apk/release/app-release.apk`），versionCode 10 / 0.5.0；
- 早前已将 release 覆盖安装到 iQOO（保留 0.4.0 会话），本轮未在真机继续操作。

## 4. 模拟器验证（真实会话 + 离线预览）

用真实 `CASTGC` / `JSESSIONID` / 校园网 Cookie 注入模拟器 debug 应用，验证：

| 项目 | 结果 |
|---|---|
| 首页顺序 | ✅ 校园卡余额 → 网费（余额 / 免费剩余 / 计费剩余）→ 快捷入口（账单/流水/充值/其他）→ 账户信息 |
| 网费余额 | ✅ 门户实时值 103.79 → 104.79，随账号状态变化 |
| 剩余流量 | ✅ 免费 0、计费 419.18 GB（与 zfw 首页一致） |
| 校园卡 | ✅ 余额 443.17、状态正常 |
| 底部导航 | ✅ 4 个 tab，卡务已移除 |
| 其他功能 | ✅ 六项入口正常；挂失/解挂/改密/限额/绑卡/解绑初始化数据读取成功 |
| 限额修改 | ✅ 读取到当前 50.00 / 200.00 元；校园码缺 `qrcodeid` 时按官方行为隐藏 |
| 设置 | ✅ 校园网登录状态、退出校园网、会话信息正常 |
| 离线预览 | ✅ demo 模式截图不包含真实姓名、学号、卡号 |

截图见 `docs/screenshot_home.png`、`docs/screenshot_other.png`、`docs/screenshot_card_ops.png`。

## 5. 网费充值二维码链路（只读验证）

- 实测 `/pay/card` 提交后返回的二维码图片为
  `/pay/qrcode?url=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Fid%3D...`；
- 实测收银台 `payScene=wap` 返回 `wxpay_wap`，`/transaction/pay` 返回微信 H5 `payUrl`；
- 客户端实现：提取二维码 URL → 移动 UA → 注入 CASTGC → 自动打开收银台 → 微信/支付宝 H5；
- **尚未完成一笔真实网费支付**（需要用户在微信内确认扣款）。

## 6. 尚未覆盖

- **真机验收**：用户暂停任务，待连接真机后补；
- **六个卡务写接口的真实提交**：出于安全未在生产账号执行，建议真机用「限额改回原值」做低风险验证；
- 网费充值真实扣款与回跳；
- 不同 ROM / 机型下的 WebView 外部 App 拉起行为；
- 校外网络访问 zfw / 门户的限制差异。

## 7. 复现命令

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
./install.sh
```

## 8. 独立复核（QA）

- 独立 QA 报告：`work/scp-050-recon/qa/QA_REPORT.md`；
- 3 个 P1 已修复：收银台兜底回退、收银台 UA 时序、支付成功判定；
- 修复记录：`work/scp-050-recon/qa/FIXES.md`；
- 修复后重新运行单元测试与 release 构建，均通过（最终 91 tests / 0 failed / 7 skipped）。

## 9. v0.5.0 Release 1（真机补充）

| 项目 | 结果 |
|---|---|
| 真机覆盖安装 | ✅ versionCode 10 / `0.5.0`，原会话保留 |
| 缴费时段外登录 | ✅ 修复 `openCardPay` 返回「不在缴费期间」被误判为会话失效；改用卡务读接口校验后真机登录成功 |
| 首页真实数据 | ✅ 校园卡 443.17；网费余额 104.79；剩余免费 0 / 计费 419.18 GB |
| 校园网持久会话 | ✅ 首次登录后 Cookie 加密保存；重启 App 仍显示「已登录」与流量 |
| 校园网原生登录 | ✅ 真机打开原生登录页；Live 探针验证 CSRF/RSA 加密与验证码加载，伪造账号能被服务端正确拒绝（`LiveZfwAuthTest`） |
| 网费充值 | ✅ 用户真机完成一笔 0.01 元测试（支付链路通过） |
| 卡务写操作 | ⚠️ 仍需做一次低风险真机验证（建议把限额改回原值） |

### 9.1 发布

- GitHub Release：`v0.5.0 Release 1`
  <https://github.com/y38501148-max/Buaa-SCP/releases/tag/v0.5.0>
- 资源：`TongCaiHang-v0.5.0-release1.apk`
  SHA-256：`d8311b6c815348ba9729d737504814240ac894a0a1f21458e3bfb1ad79fedad4`

### 9.2 待办

- 原生登录在真机完成一次完整的「账号密码 → 登录成功」闭环（本机登录页已渲染，密码输入需账号本人）；
- 卡务写接口低风险验证；
- 真机原生登录页截图补入 docs。
