# 验证记录（v0.4.0）

日期：2026-11-14
包名：`cn.edu.buaa.scp` · versionCode 9 · versionName `0.4.0`
签名：独立 release keystore（非 debug）

## 1. 环境

| 组件 | 版本 |
|---|---|
| macOS | Apple Silicon（arm64） |
| JDK | Oracle JDK 21.0.8 |
| Gradle / AGP / Kotlin | 8.8 / 8.6.1 / 2.0.21 |
| Android SDK | Platform 35 / Build-Tools 35.0.0 |
| 真机 | iQOO 15（V2505A）· Android 16（API 36）· 1440×3168 @640dpi |
| 模拟器 | Android 15（API 35, arm64-v8a, 1080×2340） |

## 2. 自动化测试

```
./gradlew :app:testDebugUnitTest
→ 58 tests, 0 failed, 4 skipped
```

新增 `UpdateManagerTest`：语义化版本比较、Release JSON 解析、无 APK 资源时忽略。

## 3. 真机验证

| 项目 | 结果 |
|---|---|
| 签名 release 覆盖安装 | ✅ versionCode 9 / `0.4.0`，原会话保留 |
| 应用名与图标 | ✅ 通财航 / SCP 图标 |
| 登录页 | ✅ 简洁布局，登录、验证码、网络自检正常 |
| 首页 | ✅ 余额、卡状态、账户信息、消息通知；不再显示卡号与支付渠道 |
| 账单 / 流水 | ✅ 左右翻月；点击月份弹出年月选择器 |
| 卡务 | ✅ 卡面只保留余额、姓名、状态；信息列表正常 |
| 充值页 | ✅ 移除横幅；打开充值页不再误报「充值完成」 |
| 设置 | ✅ 会话状态、续期、自检、退出；新增检查更新入口 |
| 用户验收 | ✅ 用户在真机完成 UI 与主要功能验收 |

## 4. 应用内更新

| 项目 | 状态 |
|---|---|
| Release 拉取与版本比较 | ✅ 真机设置 → 检查更新提示「已是最新版本 v0.4.0」 |
| 发现新版本弹窗 | ✅ 模拟器临时 v0.4.1 验证，显示版本、大小与更新说明 |
| 下载、SHA-256、签名校验、系统安装 | ⚠️ 下载流程已实现；模拟器受本地代理证书影响未完成安装，真机待下一版本实测；已提供「浏览器打开」兜底 |

## 5. 尚未覆盖

- 真实扣款链路：沿用 v0.3.0 已验证的官方充值页方案，v0.4.0 只增加成功页判断与自动返回；
- 其他 ROM / 机型：仅模拟器与 iQOO 15；
- 接口字段与加密方式随校方发版可能变化。

## 6. 复现命令

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
./install.sh
```
