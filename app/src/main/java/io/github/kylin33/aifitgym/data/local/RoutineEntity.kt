package io.github.kylin33.aifitgym.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.Serializable

/**
 * 计划类型。存字符串，加字段也安全。
 *
 * 序列化用枚举名（`CALISTHENICS` / `HIIT_CARDIO`），和 [key] 字面一致，
 * 所以备份文件里读到的值和数据库里存的是同一个词，改 key 时不会静默错位。
 */
@Serializable
enum class RoutineCategory(val key: String, val label: String) {
    /** 徒手力量（俯卧撑、引体、平板支撑这类） */
    CALISTHENICS("CALISTHENICS", "徒手力量"),

    /** 有氧间歇（Tabata、HIIT） */
    HIIT_CARDIO("HIIT_CARDIO", "有氧间歇"),
    ;

    companion object {
        fun fromKey(key: String?): RoutineCategory =
            entries.firstOrNull { it.key == key } ?: CALISTHENICS
    }
}

class RoutineCategoryConverter {

    @TypeConverter
    fun toKey(value: RoutineCategory): String = value.key

    @TypeConverter
    fun fromKey(value: String?): RoutineCategory = RoutineCategory.fromKey(value)
}

/**
 * 训练计划（一次训练要练哪几个动作）。
 *
 * 计划只是壳，「做几组、几次、歇多久」在 [RoutineExerciseEntity] 里。
 * [presetKey] 用来标记内置模板：启动时靠它去重，用户自建的计划这里是 null。
 */
@Serializable
@Entity(
    tableName = "routines",
    indices = [Index("category"), Index("updatedAt")],
)
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val routineId: Long = 0,
    val name: String,
    val description: String = "",
    val category: RoutineCategory = RoutineCategory.CALISTHENICS,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 归档后不出现在列表里，历史记录仍能引用。 */
    val archived: Boolean = false,
    /** 内置模板的稳定标识（如 `preset_push_basics`），用户自建为 null。 */
    val presetKey: String? = null,
)

/**
 * 计划里的一个动作，编排属性都在这里。
 *
 * 计次和计时二选一：`targetReps` 有值表示计次动作（俯卧撑 12 次），
 * `targetDurationSec` 有值表示计时动作（平板支撑 60 秒）。两者都为 null 时按计次 10 兜底。
 *
 * `exerciseId` 故意**不加外键**：动作库是首启从 assets 灌进来的可再生数据，重建时会整表替换，
 * 外键级联会把用户的计划条目一起删掉。`routineId` 才用 CASCADE。
 */
@Serializable
@Entity(
    tableName = "routine_exercises",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["routineId"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("routineId"),
        Index("exerciseId"),
        Index(value = ["routineId", "orderIndex"], unique = true),
    ],
)
data class RoutineExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val exerciseId: String,
    /** 在计划里的顺序，从 0 开始。 */
    val orderIndex: Int,
    val targetSets: Int = 3,
    /** 计次动作的目标次数；计时动作为 null。 */
    val targetReps: Int? = null,
    /** 计时动作的目标秒数；计次动作为 null。 */
    val targetDurationSec: Int? = null,
    /** 组间休息秒数，力量日 90s、HIIT 10~20s。 */
    val restDurationSec: Int = 60,
    /** 做不动时的平替/退阶动作，可为空。 */
    val backupExerciseId: String? = null,
) {
    /** 计时动作判定，UI 和统计都用它，别各写一套。 */
    val isTimed: Boolean get() = targetDurationSec != null && targetDurationSec > 0
}

/** 计划列表卡片用的聚合结果：计划本体 + 条目数 / 总组数 / 封面动作。 */
data class RoutineSummary(
    @Embedded val routine: RoutineEntity,
    val exerciseCount: Int,
    val totalSets: Int,
    val coverExerciseId: String?,
    /** 封面动作的演示图相对路径，直接喂给 ExerciseImage。 */
    val coverImage: String?,
)
