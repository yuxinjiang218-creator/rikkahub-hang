package me.rerere.rikkahub.data.index

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuralQueryTest {

    @Test
    fun `paths and files are structural`() {
        assertTrue(StructuralQuery.isStructural("/sdcard/Download/a.txt"))
        assertTrue(StructuralQuery.isStructural("app/src/main/AndroidManifest.xml"))
        assertTrue(StructuralQuery.isStructural("MainViewModel.kt"))
        assertTrue(StructuralQuery.isStructural("C:\\Users\\rikka\\db.sqlite"))
    }

    @Test
    fun `uuid and hash are structural`() {
        assertTrue(StructuralQuery.isStructural("3f2504e0-4f89-11d3-9a0c-0305e82c3301"))
        assertTrue(StructuralQuery.isStructural("a591a6d40bf420404a011733cfb7b190d62c65bf"))
    }

    @Test
    fun `error codes are structural`() {
        assertTrue(StructuralQuery.isStructural("ERR_2043"))
        assertTrue(StructuralQuery.isStructural("SQLITE_BUSY"))
        assertTrue(StructuralQuery.isStructural("ENOENT"))
        assertTrue(StructuralQuery.isStructural("HTTP 502"))
    }

    @Test
    fun `natural language is not structural`() {
        assertFalse(StructuralQuery.isStructural("上次我们讨论的那个向量检索怎么做"))
        assertFalse(StructuralQuery.isStructural("帮我找一下之前关于模型切换的对话"))
        assertFalse(StructuralQuery.isStructural(""))
        assertFalse(StructuralQuery.isStructural("   "))
    }

    @Test
    fun `urls and backticks are structural`() {
        assertTrue(StructuralQuery.isStructural("https://api.siliconflow.cn/v1/embeddings"))
        assertTrue(StructuralQuery.isStructural("`vector_full_scan`"))
    }
}
