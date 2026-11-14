package cn.edu.buaa.scp.net

/** 业务接口异常：区分「会话失效」与普通业务错误，便于上层决定是否重新登录。 */
class ApiException(
    message: String,
    val authExpired: Boolean = false,
) : Exception(message)
