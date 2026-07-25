package cn.vectory.syncplayer.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUrisTest {
    @Test
    fun authorityConstant() {
        // 在纯 JVM 中唯一稳定可测的常量
        assertEquals("cn.vectory.syncplayer.media", MediaUris.AUTHORITY)
    }

    // mediaUri / thumbUri / isMedia / isThumb / idOf 依赖 android.net.Uri，
    // 在纯 JVM 单元测试环境中不稳定，跳过。
    // 功能验证由集成测试（instrumented）覆盖。
}
