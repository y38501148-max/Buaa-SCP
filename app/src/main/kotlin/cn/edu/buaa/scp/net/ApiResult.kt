package cn.edu.buaa.scp.net

import kotlinx.coroutines.CancellationException

/** 一次接口调用结果：value 与 error 互斥，便于页面分区展示。 */
data class ApiResult<T>(
    val value: T?,
    val error: String?,
) {
    val ok: Boolean get() = value != null
}

/** 包裹一次接口调用，把异常转成可展示的错误文案。 */
suspend fun <T> resultOf(block: suspend () -> T): ApiResult<T> = try {
    ApiResult(block(), null)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ApiResult(null, SelfCheck.describe(e))
}
