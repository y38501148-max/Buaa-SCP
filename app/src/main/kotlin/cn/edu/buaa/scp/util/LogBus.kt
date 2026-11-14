package cn.edu.buaa.scp.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 极简运行日志：内存环形缓冲 + 监听器。
 *
 * 只记录流程与状态，绝不写入密码；Cookie 只记录「有没有 / 多长」，不记录原文。
 */
object LogBus {

    private const val MAX_LINES = 800
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
    private val lines = ArrayList<String>(MAX_LINES)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun add(tag: String, message: String) {
        val time = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "$time [$tag] $message"
        synchronized(lock) {
            lines.add(line)
            while (lines.size > MAX_LINES) {
                lines.removeAt(0)
            }
        }
        listeners.forEach { listener -> runCatching { listener() } }
    }

    fun step(message: String) = add("STEP", message)

    fun net(message: String) = add("HTTP", message)

    fun warn(message: String) = add("WARN", message)

    fun error(message: String) = add("ERR", message)

    /** 返回日志快照（可直接展示）。 */
    fun dump(): String = synchronized(lock) { lines.joinToString("\n") }

    fun size(): Int = synchronized(lock) { lines.size }

    fun clear() {
        synchronized(lock) { lines.clear() }
        listeners.forEach { listener -> runCatching { listener() } }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
