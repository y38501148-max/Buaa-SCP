package cn.edu.buaa.scp.ui

import cn.edu.buaa.scp.R

/**
 * 「其他功能」里的六项卡务操作。
 *
 * 这些操作都会直接写入学校校园卡系统，因此文案与二次确认集中在枚举里，
 * 页面只负责收集参数与调用接口。
 */
enum class CardOp(
    val title: String,
    val subtitle: String,
    val icon: Int,
    val danger: Boolean,
) {
    LOSS(
        title = "校园卡挂失",
        subtitle = "卡片丢失后立即冻结",
        icon = R.drawable.ic_lock,
        danger = true,
    ),
    UNLOSS(
        title = "校园卡解挂",
        subtitle = "恢复冻结的校园卡",
        icon = R.drawable.ic_unlock,
        danger = false,
    ),
    PASSWORD(
        title = "密码修改",
        subtitle = "修改校园卡支付密码",
        icon = R.drawable.ic_shield,
        danger = false,
    ),
    QUOTA(
        title = "限额修改",
        subtitle = "调整单笔与单日消费上限",
        icon = R.drawable.ic_limit,
        danger = false,
    ),
    BIND_BANK(
        title = "绑定银行卡",
        subtitle = "绑定或更换银行卡",
        icon = R.drawable.ic_bank,
        danger = false,
    ),
    UNBIND_BANK(
        title = "银行卡解绑",
        subtitle = "解除已绑定的银行卡",
        icon = R.drawable.ic_unlink,
        danger = true,
    ),
}
