package io.github.kylin33.aifitgym.data.seeding

import io.github.kylin33.aifitgym.data.local.RoutineCategory

/**
 * 内置计划模板。动作 ID 对应 `assets/exercises.json` 里的条目，改数据包后要一起核对。
 *
 * 模板只在「库里一个内置计划都没有」时灌一次；灌入时会先用 [io.github.kylin33.aifitgym.data.local.ExerciseDao.allIds]
 * 校验动作是否存在，缺的条目直接跳过，所以就算动作库换了也不会灌出空计划。
 */
data class PresetRoutineItem(
    val exerciseId: String,
    val sets: Int,
    /** 计次动作用，和 [durationSec] 二选一。 */
    val reps: Int? = null,
    val durationSec: Int? = null,
    val restSec: Int = 60,
    /** 做不动时的退阶动作。 */
    val backupExerciseId: String? = null,
)

data class PresetRoutine(
    /** 稳定标识，用来判断模板是否已经灌过。 */
    val key: String,
    val name: String,
    val description: String,
    val category: RoutineCategory,
    val items: List<PresetRoutineItem>,
)

object RoutinePresets {

    /** 《囚徒健身》基础推力日。 */
    private val PUSH_BASICS = PresetRoutine(
        key = "preset_push_basics",
        name = "囚徒健身 · 基础推力日",
        description = "推的链条：俯卧撑打底，派克练肩，臂屈伸补三头，平板收核心。每组间休息 90 秒。",
        category = RoutineCategory.CALISTHENICS,
        items = listOf(
            PresetRoutineItem("0493", sets = 3, reps = 8, restSec = 90),                 // 上斜俯卧撑
            PresetRoutineItem("0662", sets = 4, reps = 10, restSec = 90, backupExerciseId = "0493"), // 俯卧撑
            PresetRoutineItem("2921", sets = 3, reps = 8, restSec = 90, backupExerciseId = "0662"),  // 派克俯卧撑
            PresetRoutineItem("0672", sets = 3, reps = 10, restSec = 75),                // 反向臂屈伸
            PresetRoutineItem("0463", sets = 3, durationSec = 45, restSec = 45),         // 平板支撑
        ),
    )

    /** 《囚徒健身》基础拉力日。 */
    private val PULL_BASICS = PresetRoutine(
        key = "preset_pull_basics",
        name = "囚徒健身 · 基础拉力日",
        description = "从水平拉过渡到垂直拉：反向划船找背部发力，引体向上练力量，悬垂空心收尾。",
        category = RoutineCategory.CALISTHENICS,
        items = listOf(
            PresetRoutineItem("0499", sets = 4, reps = 8, restSec = 90, backupExerciseId = "2300"), // 反向划船
            PresetRoutineItem("0652", sets = 4, reps = 5, restSec = 120, backupExerciseId = "1326"), // 引体向上
            PresetRoutineItem("2300", sets = 3, reps = 10, restSec = 75),                // 屈膝反向划船
            PresetRoutineItem("6547", sets = 3, durationSec = 25, restSec = 45),         // 悬垂空心保持
        ),
    )

    /** Tabata 燃脂间歇：20 秒做 / 10 秒歇，每个动作 8 轮。 */
    private val TABATA = PresetRoutine(
        key = "preset_tabata",
        name = "Tabata 燃脂间歇",
        description = "经典 20/10：每个动作 8 轮，共 4 个动作约 16 分钟。全程徒手，动作之间不额外休息。",
        category = RoutineCategory.HIIT_CARDIO,
        items = listOf(
            PresetRoutineItem("1160", sets = 8, durationSec = 20, restSec = 10, backupExerciseId = "5039"), // 波比
            PresetRoutineItem("3094", sets = 8, durationSec = 20, restSec = 10),         // 开合跳
            PresetRoutineItem("3556", sets = 8, durationSec = 20, restSec = 10),         // 高抬腿跑
            PresetRoutineItem("0630", sets = 8, durationSec = 20, restSec = 10),         // 登山者
        ),
    )

    val all: List<PresetRoutine> = listOf(PUSH_BASICS, PULL_BASICS, TABATA)
}
