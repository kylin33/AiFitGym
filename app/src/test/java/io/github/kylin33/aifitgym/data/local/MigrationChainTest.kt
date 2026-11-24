package io.github.kylin33.aifitgym.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据库结构的「体检」，纯 JVM 跑，不需要设备。
 *
 * 这里查的不是迁移写得好不好，而是四件「错了就要么删库、要么崩、要么历史读不出来」的事：
 *
 * 1. 代码里的版本号 == `app/schemas/` 里最新导出的那一份；
 * 2. 除了白名单里的 v1~v4，任意两级之间都必须有迁移 —— 缺一级，从那一级升上来的用户
 *    启动就崩（现在不会静默删库了，见 `FitGymDatabase` 里的 `fallbackToDestructiveMigrationFrom`）；
 * 3. 迁移里 `DROP TABLE` 不许碰训练历史，`ALTER TABLE ADD COLUMN` 加的列必须真的出现在
 *    目标版本的 schema 里（对不上说明迁移和实体已经漂移，Room 运行期会直接抛异常）；
 * 4. 历史表只允许加列，不允许删列/改名 —— 删了列，老记录就读不出来了。
 *
 * 不做的事：不验数据类型、不验默认值、不验索引。那些靠 Room 打开库时的
 * `validateMigration`（identity hash 校验）在真机上炸，比在这里猜更准。
 */
class MigrationChainTest {

    /** 训练历史的两张表：删它们等于删用户的账。 */
    private val historyTables = setOf("workout_sessions", "workout_set_logs")

    private val schemaDir: File = run {
        val dbName = FitGymDatabase::class.java.name
        listOf(File("schemas/$dbName"), File("app/schemas/$dbName"))
            .firstOrNull { it.isDirectory }
            ?: error("找不到导出的 schema 目录（schemas/$dbName）；单测的工作目录应该是 app 模块")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 版本号 → (表名 → 列名集合)，全部从导出的 schema json 里读。 */
    private val tablesByVersion: Map<Int, Map<String, Set<String>>> by lazy {
        val files = schemaDir.listFiles { file -> file.extension == "json" }.orEmpty()
        assertTrue("schema 目录是空的：${schemaDir.absolutePath}", files.isNotEmpty())
        files.associate { file ->
            val database = json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
            val version = database["version"]!!.jsonPrimitive.int
            val tables = database["entities"]!!.jsonArray.associate { entity ->
                val obj = entity.jsonObject
                val table = obj["tableName"]!!.jsonPrimitive.content
                val columns = obj["fields"]!!.jsonArray
                    .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
                    .toSet()
                table to columns
            }
            version to tables
        }
    }

    private val latestVersion: Int get() = tablesByVersion.keys.max()

    // ------------------------------------------------------------ 版本号本身

    @Test
    fun schema_files_are_contiguous_from_one() {
        assertEquals(
            "导出的 schema 版本必须从 1 连续到 $latestVersion；缺的一级说明那版没导出或文件被删了",
            (1..latestVersion).toList(),
            tablesByVersion.keys.sorted(),
        )
    }

    @Test
    fun database_version_matches_latest_schema() {
        assertEquals(
            "FitGymDatabase.DB_VERSION 与最新的 schema 文件不是同一版：" +
                "改表后忘了升版本，或者升了版本却没提交新导出的 schema",
            FitGymDatabase.DB_VERSION,
            latestVersion,
        )
    }

    // ------------------------------------------------------------ 迁移覆盖

    @Test
    fun every_step_between_versions_has_a_migration() {
        val destructiveStarts = FitGymDatabase.DESTRUCTIVE_START_VERSIONS.toSet()
        val covered = FitGymDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()

        for (version in 2..latestVersion) {
            if ((version - 1) in destructiveStarts) continue
            assertTrue(
                "v${version - 1} → v$version 没有迁移。从这一版升上来的用户会直接抛异常打不开库",
                (version - 1 to version) in covered,
            )
        }
    }

    @Test
    fun destructive_whitelist_only_covers_unreleased_versions() {
        val destructiveStarts = FitGymDatabase.DESTRUCTIVE_START_VERSIONS.toSet()

        assertFalse(
            "当前版本 ${FitGymDatabase.DB_VERSION} 不能出现在允许重建的白名单里",
            FitGymDatabase.DB_VERSION in destructiveStarts,
        )
        assertTrue(
            "白名单只该放从没发布过的版本（v1~v4），现在写的是 $destructiveStarts",
            destructiveStarts.all { it in 1..4 },
        )

        // Room 的硬约束：白名单版本不能同时是某条迁移的起点或终点，否则打开库时直接抛异常
        FitGymDatabase.ALL_MIGRATIONS.forEach { migration ->
            val range = "v${migration.startVersion}→v${migration.endVersion}"
            assertFalse("$range 的起点在白名单里，Room 会拒绝这个配置", migration.startVersion in destructiveStarts)
            assertFalse("$range 的终点在白名单里，Room 会拒绝这个配置", migration.endVersion in destructiveStarts)
        }
    }

    // ------------------------------------------------------------ 迁移内容和 schema 是否一致

    @Test
    fun migrations_never_drop_history_tables() {
        FitGymDatabase.ALL_MIGRATIONS.forEach { migration ->
            val statements = runMigration(migration)
            assertTrue(
                "v${migration.startVersion}→v${migration.endVersion} 一条 SQL 都没记录到 —— " +
                    "代理没截住 execSQL，这条断言就成了摆设",
                statements.isNotEmpty(),
            )
            statements.forEach { sql ->
                val dropped = DROP_TABLE.find(sql)?.groupValues?.get(1) ?: return@forEach
                assertFalse(
                    "v${migration.startVersion}→v${migration.endVersion} 里 DROP 了 $dropped。" +
                        "历史表只能加列，不能重建 —— 重建等于清空用户的训练记录",
                    dropped in historyTables,
                )
            }
        }
    }

    /**
     * 上面两条断言依赖正则从 SQL 里抠表名/列名。正则一旦匹配不上，它们会「静默通过」，
     * 所以拿真实语句反向验一次 —— 这条挂了，说明另外两条的绿灯不算数。
     */
    @Test
    fun sql_patterns_recognize_the_real_statements() {
        val drop = DROP_TABLE.find("DROP TABLE IF EXISTS `workout_set_logs`")
        assertEquals("workout_set_logs", drop?.groupValues?.get(1))
        assertEquals("exercises", DROP_TABLE.find("DROP TABLE IF EXISTS \"exercises\"")?.groupValues?.get(1))

        val alter = ADD_COLUMN.find(
            "ALTER TABLE `workout_set_logs` ADD COLUMN `restActualSec` INTEGER NOT NULL DEFAULT 0",
        )
        assertEquals("workout_set_logs", alter?.groupValues?.get(1))
        assertEquals("restActualSec", alter?.groupValues?.get(2))
    }

    @Test
    fun columns_added_by_migration_exist_in_target_schema() {
        FitGymDatabase.ALL_MIGRATIONS.forEach { migration ->
            val target = tablesByVersion[migration.endVersion]
                ?: error("找不到 v${migration.endVersion} 的 schema")
            val statements = runMigration(migration)

            statements.forEach { sql ->
                val match = ADD_COLUMN.find(sql) ?: return@forEach
                val table = match.groupValues[1]
                val column = match.groupValues[2]
                val columns = target[table]
                    ?: error("v${migration.endVersion} 里没有表 $table，迁移和实体已经对不上")

                assertTrue(
                    "迁移给 $table 加了 $column，但 v${migration.endVersion} 的 schema 里没有这一列：" +
                        "实体改了却没重新构建导出 schema？",
                    column in columns,
                )
            }
        }
    }

    @Test
    fun history_tables_only_gain_columns() {
        for (version in 2..latestVersion) {
            val before = tablesByVersion[version - 1] ?: continue
            val after = tablesByVersion[version] ?: continue

            historyTables.forEach { table ->
                val oldColumns = before[table] ?: return@forEach
                val newColumns = after[table]
                    ?: error("v$version 里 $table 消失了 —— 历史表不能改名或删除")

                val lost = oldColumns - newColumns
                assertTrue(
                    "v$version 的 $table 少了 $lost。删列会让老记录读不出来；" +
                        "要淘汰一列请分两步：先停止写入，隔一个版本再删",
                    lost.isEmpty(),
                )
            }
        }
    }

    // ------------------------------------------------------------ 工具

    /**
     * 在记录型假库上跑一遍迁移，拿回它执行过的 SQL。
     *
     * 不用 Room 的 `MigrationTestHelper`：那套要仪器/模拟器，而这条链在 CI 上没有设备。
     * 迁移里只用到 `execSQL` 和一次 `query`，用动态代理截住就够，顺带不用引入任何测试依赖。
     */
    private fun runMigration(migration: Migration): List<String> {
        val statements = mutableListOf<String>()

        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    statements += (args?.firstOrNull() as? String).orEmpty()
                    null
                }
                "query" -> emptyCursor()
                "isOpen" -> true
                "isDbLockedByCurrentThread" -> false
                "toString" -> "RecordingDatabase"
                else -> defaultValueOf(method.returnType)
            }
        } as SupportSQLiteDatabase

        migration.migrate(database)
        return statements
    }

    /** 迁移里那次「读老节点表」的查询：这里不需要数据，游标直接返回空。 */
    private fun emptyCursor() = Proxy.newProxyInstance(
        android.database.Cursor::class.java.classLoader,
        arrayOf(android.database.Cursor::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "moveToNext", "moveToFirst" -> false
            "isClosed" -> false
            "getCount" -> 0
            "getPosition" -> -1
            else -> defaultValueOf(method.returnType)
        }
    } as android.database.Cursor

    private fun defaultValueOf(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    private companion object {
        val DROP_TABLE = Regex(
            """DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?[`"']?([A-Za-z_][A-Za-z0-9_]*)[`"']?""",
            RegexOption.IGNORE_CASE,
        )

        val ADD_COLUMN = Regex(
            """ALTER\s+TABLE\s+[`"']?([A-Za-z_][A-Za-z0-9_]*)[`"']?\s+ADD\s+COLUMN\s+[`"']?([A-Za-z_][A-Za-z0-9_]*)[`"']?""",
            RegexOption.IGNORE_CASE,
        )
    }
}
