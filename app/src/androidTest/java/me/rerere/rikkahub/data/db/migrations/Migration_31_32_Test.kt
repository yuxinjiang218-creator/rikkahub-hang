package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDateTime
import java.time.ZoneId
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * fork 的 30→31 被压缩字段占用，上游新增的用量流水表顺延到 31→32，
 * 这里验证 31→32 建表并从历史消息回填。
 */
@RunWith(AndroidJUnit4::class)
class Migration_31_32_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun createsUsageRecordTableAndBackfillsAssistantMessages() {
        helper.createDatabase(TEST_DB, 31).use { db ->
            db.execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                    "VALUES ('conv', 'assistant', 'Test', '[]', 1000, 1000)"
            )
            db.insertNode(
                "node-1",
                0,
                """[{"role":"user","createdAt":"2026-08-12T09:00:00"}]""",
            )
            // 一个节点两个分支都要算；没有 usage 和 usage 全为 0 的跳过
            db.insertNode(
                "node-2",
                1,
                """[
                    {"role":"assistant","createdAt":"2026-08-12T09:01:00.123","modelId":"model-a",
                     "modelSnapshot":{"modelId":"gpt-a","displayName":"A"},
                     "usage":{"promptTokens":100,"completionTokens":40,"cachedTokens":20}},
                    {"role":"assistant","createdAt":"2026-08-12T09:02","modelId":"model-b",
                     "usage":{"promptTokens":50,"completionTokens":10,"cachedTokens":0}},
                    {"role":"assistant","createdAt":"2026-08-12T09:03:00"},
                    {"role":"assistant","createdAt":"2026-08-12T09:04:00",
                     "usage":{"promptTokens":0,"completionTokens":0,"cachedTokens":0}}
                ]""",
            )
            db.insertNode("node-3", 2, "not json")
        }

        val rows = helper.runMigrationsAndValidate(TEST_DB, 32, true, Migration_31_32).use { db ->
            db.query(
                "SELECT created_at, source, model_id, model_name, assistant_id, conversation_id, " +
                    "prompt_tokens, completion_tokens, cached_tokens FROM usage_record ORDER BY created_at"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            listOf(
                                cursor.getLong(0),
                                cursor.getString(1),
                                cursor.getString(2),
                                cursor.getString(3),
                                cursor.getString(4),
                                cursor.getString(5),
                                cursor.getInt(6),
                                cursor.getInt(7),
                                cursor.getInt(8),
                            )
                        )
                    }
                }
            }
        }

        assertEquals(
            listOf(
                listOf(epochMillis("2026-08-12T09:01:00"), "CHAT", "model-a", "gpt-a", "assistant", "conv", 100, 40, 20),
                listOf(epochMillis("2026-08-12T09:02:00"), "CHAT", "model-b", null, "assistant", "conv", 50, 10, 0),
            ),
            rows,
        )
    }

    private fun SupportSQLiteDatabase.insertNode(id: String, index: Int, messages: String) {
        execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, 'conv', ?, ?, 0)",
            arrayOf<Any?>(id, index, messages),
        )
    }

    private fun epochMillis(localDateTime: String): Long =
        LocalDateTime.parse(localDateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private companion object {
        const val TEST_DB = "migration-31-32-test"
    }
}
