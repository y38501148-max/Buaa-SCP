# 账单与流水（合并页）

v0.6.0 起，原「账单」「流水」两个底部标签合并为一个「账单」页：上半部分是月度统计，下半部分是交易明细。

## 1. 页面结构

- 月份选择：左右翻月或点月份直接选年月；
- 概览：所选月份的消费合计（含笔数）与充值合计；
- 消费分类：`queryUserMonthlybill` 返回的分类金额与占比；
- 充值：近半年每月充值金额与笔数；
- 支出趋势：`queryUserMonthlybillHalfOfYear` 的近半年消费柱状图；
- 明细：`queryCardSelfTradeList` 按交易类型（全部 / 消费 / 充值 / 补助 / 转账）筛选并分页加载。

## 2. 消费金额

- 月度消费合计与分类：`POST /bill/queryUserMonthlybill`，`sumamt` 单位为元，`billList` 为分类；
- 近半年消费趋势：`POST /bill/queryUserMonthlybillHalfOfYear`，`txamt` 单位为分；
  - 部分请求下服务端不返回 `totalamt` / `totalcount`（实测 `totalamt=null`），客户端用
    `billList` 自行求和，笔数缺失时不显示。

## 3. 充值金额：不信任官方充值汇总接口

官方前端用 `POST /bill/queryRechargeGroupByMonth`（带 `queryChartList` 返回的 `chartId`）
取充值按月汇总。实测（2026-11-14/15，真实账号）发现：

1. `POST /bill/queryChartList` 在当前会话下返回业务错误「开放平台服务异常」，
   拿不到任何 `chartId`；
2. 不带 `chartId` 调 `queryRechargeGroupByMonth` 时，返回的 `billList` 与同期
   `queryUserMonthlybillHalfOfYear` **逐月完全一致**——即服务端按消费口径返回，
   这就是「充值显示成消费」的原因；
3. 充值流水 `queryCardSelfTradeList`（`tradetype=2`）返回的是真实充值记录
   （含 1 元、10 元小额测试充值），逐笔相加与充值汇总接口的数值无关。

因此客户端**不再使用** `/bill/queryRechargeGroupByMonth`：

- 进入账单页时拉取近半年的充值流水（`tradetype=2`，分页读完）；
- 以交易时间 `txdate` 的 `yyyy-MM` 归月，按 `txamt`（分 → 元）求和、计笔数；
- 概览中的「充值」与「充值」卡片都来自这份流水汇总，口径与「明细 → 充值」一致。

## 4. 明细

- `POST /selftrade/queryCardSelfTradeList`，`pageNumber` 从 0 开始，`pageSize=10`；
- 兼容两种返回结构：`{"resultData":{"rows":[...],"totalpage":"3"}}` 与
  `{"data":{"data":[...]}}`（无分页字段时按「本页是否取满」判断是否还有下一页）；
- 正数标绿（充值 / 补助），负数标红（消费）。

## 5. 验证

- 充值按月汇总与流水口径的单元测试：`ModelsTest.billRecharge_groupsByMonthFromRechargeTrades`；
- 真机实测结论见 [`VERIFICATION.md`](VERIFICATION.md) 的 v0.6.0 一节。
