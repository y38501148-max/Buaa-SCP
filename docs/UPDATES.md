# 应用内更新

客户端从 GitHub 最新正式 Release 读取版本、APK 和校验文件。0.8.2 优先使用同一仓库的资源 API 下载 APK 和校验文件，请求头为 `Accept: application/octet-stream`；缺少合法资源 API 地址时保留网页下载地址。API 的 200 / 302 下载行为见 [GitHub 官方文档](https://docs.github.com/en/rest/releases/assets#get-a-release-asset)。发现新版本后，用户确认下载；下载完成后校验 SHA-256、包名和签名，再交给 Android 系统安装器。更新使用完整 APK，安装后生效。

## 检查时机与界面

0.8.2 起，每次进入主界面前台都会判断检查间隔，同一应用版本在上次成功检查的 15 分钟后可再次检查。检查失败不更新时间；时钟回拨也不会长期阻止检查。离线示例模式不自动联网检查。

设置页的「检查更新」绕过时间间隔和「跳过此版本」。点击后立即显示检查进度；如果自动检查正在进行，手动操作接续同一请求，完成后按手动检查显示结果或错误。错误弹窗保留服务端或网络错误消息，并提供重试和浏览器入口；错误也写入应用运行日志。

检查任务、进度框和更新弹窗均属于当前 Activity。页面重建或销毁时取消任务并清理弹窗；旧请求晚到的回调不能关闭新页面或新请求的进度框。取消检查后可以再次发起检查。

## 0.8.0 / 0.8.1 的问题

旧版自动检查成功后会等待 24 小时，因此当天稍后发布的新版本可能不会在首页提示。手动检查本应绕过间隔，但旧代码的 `checking` 和 `dialogShowing` 是进程级变量。检查协程在页面重建时取消，会跳过 `checking = false`；更新弹窗随页面销毁时，也没有统一的 dismiss 清理。这些路径会使后续检查被全局状态拦截，直到进程重启。

0.8.2 改为按 Activity 管理状态，并在任务 `finally`、弹窗 dismiss 和 Activity 销毁时清理。没有读取用户设备上的故障日志，本次确认的是源码中可导致所述现象的路径；用户当次触发的是哪条路径无法确定。旧版可在系统设置中强行停止应用后重新打开，再手动检查，或直接下载新版 APK 覆盖安装，无需清除数据。

## 验证与边界

`UpdateCheckPolicyTest` 覆盖首次检查、版本变化、15 分钟边界与时钟回拨。`UpdateUiTest` 在独立测试包中覆盖检查期间重建 Activity、弹窗期间重建、取消后重试、旧请求迟到、手动接续自动检查失败，以及绕过跳过版本和最近检查时间。模拟响应不会下载或安装 APK。

公开服务探针使用实际 `UpdateManager` 读取 Release、下载 APK 并校验 SHA-256，显式开启后才联网：

```bash
./gradlew :app:connectedDebugAndroidTest -PisolatedTest=1 \
  -Pandroid.testInstrumentationRunnerArguments.class=cn.edu.buaa.scp.LiveUpdateTest \
  -Pandroid.testInstrumentationRunnerArguments.liveUpdate=1
```

2026-11-16 的 Android 15 模拟器探针发现，旧下载地址在下载阶段报 `Trust anchor for certification path not found`，版本检查已经成功。本机直连 `github.com` 收到的证书主体为 `Scholar Service`、签发者为 `Scholar Root CA v1`，与 GitHub API 的证书不同。改用官方资源 API 后，同一模拟器完成版本读取、APK 下载和 SHA-256 校验，全部通过。没有添加额外根证书或关闭证书校验。

网络探针不启动系统安装器。需要安装权限时仍由用户在系统设置中授权；本次没有完成用户手机上的安装流程。当前连接仅有 Android 模拟器，模拟器联网结果不能确定用户手机到 GitHub 的网络状态。
