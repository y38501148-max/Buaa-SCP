package cn.edu.buaa.scp.util

import java.io.File

/**
 * 仅调试版本启用的原始响应转储，便于用 `adb shell run-as` 拉取真实返回结构。
 *
 * Release 版本在 [cn.edu.buaa.scp.ScpApp] 中不会设置 [dir]，因此完全不会落盘。
 */
object DebugDump {

    @Volatile
    var dir: File? = null

    fun maybeDump(name: String, content: String?) {
        val target = dir ?: return
        runCatching {
            target.mkdirs()
            File(target, name + ".txt").writeText(content.orEmpty())
        }
    }
}
