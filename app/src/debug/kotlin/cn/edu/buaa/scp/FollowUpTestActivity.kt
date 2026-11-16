package cn.edu.buaa.scp

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** 仪器测试的空宿主，仅编入 debug APK，不加载账号或调用业务接口。 */
class FollowUpTestActivity : Activity() {
    lateinit var frame: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frame = FrameLayout(this)
        setContentView(frame)
    }
}
