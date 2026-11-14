package cn.edu.buaa.scp

import org.json.JSONObject

/** 读取 `src/test/resources` 下的夹具。 */
object TestFixtures {

    fun text(name: String): String {
        val stream = TestFixtures::class.java.classLoader?.getResourceAsStream(name)
            ?: error("缺少测试夹具：$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun json(name: String): JSONObject = JSONObject(text("json/$name"))
}
