# 财务收入查询

> 数据来自学校财务查询系统 `chaxun.icw.buaa.edu.cn`，内容为财务系统录入的个人收入记录
> （酬金、助学金、奖学金等）。到账时间与金额以银行记录为准，本页不推断到账。

## 1. 入口与链路

App 通过统一身份认证 `CASTGC` 静默进入财务系统，不需要再次输入密码：

```
GET  https://sso.buaa.edu.cn/login?service=http%3A%2F%2Ficw.buaa.edu.cn%2Fcheckbuaa.aspx
  → 302（带 CASTGC 时 CAS 直接签票）
GET  https://icw.buaa.edu.cn/checkbuaa.aspx?ticket=ST-…
  → 302 /Newindex.aspx（建立 icw 门户会话）
GET  https://icw.buaa.edu.cn/jump.aspx?sysid=wscx
  → 302 https://chaxun.icw.buaa.edu.cn/gxzhcxzhb/login?Userid=…&Cypt=…&Stamp=…
POST https://chaxun.icw.buaa.edu.cn/api-zhcx/login
  body: username=<Userid>&password=<Cypt>&yzm=yzm&stamp=<Stamp>
  → { "code": 200, "data": { "token": "…" } }
POST https://chaxun.icw.buaa.edu.cn/api-zhcx/getStudentSRCX
  Authorization: Bearer <token>
  body: { "userId": "<Userid>", "currentPage": 1, "limit": 100 }
  → { "code": 200, "data": { "mx": [ … ], "total": N } }
```

要点：

- 全程只走 https。CAS 的 `service` 参数注册值是 `http://icw.buaa.edu.cn/checkbuaa.aspx`，
  但服务端会跳回 http；客户端逐跳把 sso / icw / chaxun 三个域名的跳转地址强制改成 https。
- `HttpEngine` 不自动跟随 3xx，财务链路显式逐跳并校验 host，避免被带到未知站点。
- 财务 token 缓存在内存；出现 401 或跳回登录页时清空缓存、重新走一次链路。
- `CASTGC` 失效时抛「统一身份认证会话已失效」，由应用的免密续期逻辑处理；仍失败则提示重新登录。
- token、`Cypt`、`Userid`、Cookie 不写日志、不落盘。

## 2. 分页

`getStudentSRCX` 每次最多 100 条，返回 `data.mx`（或 `data.data`）与 `total`（或 `count`）。
客户端循环读取直到条数等于 `total`，并做以下保护：

- 按 `lsh`（流水号）去重；同一流水号内容不一致或分页无法收敛时报「数据重复 / 未收敛」；
- `total` 上限 5000 条、页码上限 100，超出直接报错，避免异常分页拖死请求；
- 行数据为空但总数未凑齐时视为结构异常。

## 3. 字段

| 接口字段 | 页面含义 |
|---|---|
| `lsh` | 流水号（记录唯一标识） |
| `ffxmmc` | 发放项目名称 |
| `zy` | 摘要 |
| `je` | 应发金额（元） |
| `se` | 扣税（元） |
| `sfje` | 实发金额（元） |
| `slbfb` | 税率（百分比） |
| `lrrq` | 录入日期 |
| `nian` / `yue` | 所属年 / 月 |

金额接口返回「元」（数字或字符串），客户端统一换算成「分」保存：最多两位小数，
更多小数位按 HALF_UP 处理，无法解析时保留「未知」而不是显示 0.00。
所属年月优先取 `nian` + `yue`，缺失时退回 `lrrq` 的年月。

## 4. 页面

- 按年度筛选（可选「全部」）；
- 顶部为当前范围的实发合计、笔数、应发与扣税（为 0 时不显示扣税）；
- 记录按所属年月倒序分组，组内按录入日期倒序；
- 支持按项目 / 摘要 / 流水号搜索；
- 点记录查看应发、扣税、实发、税率、所属年月、录入日期与流水号。

页面只做展示，不写回学校系统。

## 5. 参考实现

协议参考 [BUAASubnet/UBAA](https://github.com/BUAASubnet/UBAA) 的财务查询实现与
[CoolwindHF/Auto-BUAA-icw](https://github.com/CoolwindHF/Auto-BUAA-icw) 的协议分析。
本客户端用 Kotlin 重新实现，未引入第三方依赖。

## 6. 验证状态

- 纯数据模型（分页、金额换算、分组、搜索、合计）有单元测试；
- 链路需要真实统一认证会话，`FinanceApi` 使用现有 `CASTGC`；真机只读验证见
  [`VERIFICATION.md`](VERIFICATION.md)。
