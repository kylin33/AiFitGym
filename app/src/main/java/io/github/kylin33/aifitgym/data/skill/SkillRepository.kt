package io.github.kylin33.aifitgym.data.skill

import io.github.kylin33.aifitgym.data.local.BranchProgressDao
import io.github.kylin33.aifitgym.data.local.BranchProgressEntity
import io.github.kylin33.aifitgym.data.local.ExerciseDao
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.RoutineDao
import io.github.kylin33.aifitgym.data.local.RoutineExerciseEntity
import io.github.kylin33.aifitgym.data.local.WorkoutSessionDao
import io.github.kylin33.aifitgym.data.repo.SetOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** 一个主干节点在树上的位置状态。 */
enum class BossState {
    /** 已通关（做过一次合格训练）。 */
    MASTERED,

    /** 当前正在攻克的阶。 */
    CURRENT,

    /** 还没轮到它。 */
    LOCKED,
}

/**
 * 技能树上的一个主干节点。
 *
 * 没有单独的节点表 —— 节点就是「某个流派里 skillLevel = N 的那条动作」，
 * 条件（组数/目标值/口径）也存在动作表上，所以这里只是把动作和进度拼起来。
 */
data class BranchBoss(
    val branch: SkillBranch,
    val tier: Int,
    val exercise: ExerciseEntity?,
    val state: BossState,
    /** 历史最好成绩：计次看次数、计时看秒数、累计类看单场总和。 */
    val best: Int,
    val requirement: UnlockRequirement,
    /** 只有当前阶会带上突破折扣，其他阶为 0。 */
    val discountPercent: Int,
) {
    /** 打折后的实际要求。 */
    val effective: UnlockRequirement get() = requirement.discounted(discountPercent)

    /** UI 上的达标文案；有折扣时会显示折后的数。 */
    val label: String get() = effective.label(discountPercent)

    val unit: String get() = requirement.unit

    /** 距离达标还差多少（已经达标返回 0）。 */
    val gap: Int get() = (effective.value - best).coerceAtLeast(0)
}

/** 一条流派的完整看板数据。 */
data class BranchBoard(
    val branch: SkillBranch,
    val progress: BranchProgressEntity,
    val bosses: List<BranchBoss>,
) {
    val currentTier: Int get() = progress.currentTier.coerceIn(1, SkillRules.MAX_TIER)

    val masteredCount: Int get() = bosses.count { it.state == BossState.MASTERED }

    val availableXp: Int get() = progress.availableXp

    /** 距离下一次突破还差多少 XP；已到折扣上限时为 0。 */
    val xpToBreakthrough: Int
        get() = if (progress.discountPercent >= SkillRules.MAX_DISCOUNT) 0
        else (SkillRules.XP_PER_BREAKTHROUGH - availableXp).coerceAtLeast(0)

    /** 进度条比例 0f…1f。 */
    val xpFraction: Float
        get() = (availableXp.toFloat() / SkillRules.XP_PER_BREAKTHROUGH).coerceIn(0f, 1f)

    /** 当前正在攻克的节点。 */
    val current: BranchBoss? get() = bosses.firstOrNull { it.state == BossState.CURRENT }
}

/** 一次训练里某个流派刷到多少 XP。 */
data class BranchXp(
    val branch: SkillBranch,
    val sets: Int,
    val xp: Int,
)

/** 晋升事件：通关第 fromTier 阶，指针前进到 toTier。 */
data class BranchPromotion(
    val branch: SkillBranch,
    val fromTier: Int,
    val toTier: Int,
    val clearedId: String,
    val clearedName: String,
    val nextId: String?,
    val nextName: String?,
    val nextImage: String?,
    /** 已经是最后一阶，整条链通关。 */
    val allClear: Boolean,
)

/** 熟练度突破：花了 300 XP，当前阶要求降了 [discountPercent]。 */
data class BranchBreakthrough(
    val branch: SkillBranch,
    val discountPercent: Int,
    /** 打折后的条件文案，如「3 组 × 12 次」；拿不到节点时为空。 */
    val newLabel: String = "",
)

/** 结算时「双轨升级」的全部结果。 */
data class Settlement(
    val xp: List<BranchXp> = emptyList(),
    val promotions: List<BranchPromotion> = emptyList(),
    val breakthroughs: List<BranchBreakthrough> = emptyList(),
) {
    val totalXp: Int get() = xp.sumOf { it.xp }

    val isEmpty: Boolean get() = promotions.isEmpty() && breakthroughs.isEmpty()

    companion object {
        val EMPTY = Settlement()
    }
}

/** 往计划里加动作的结果。 */
enum class AddToRoutineResult { ADDED, ALREADY_THERE, FAILED }

/**
 * 技能树读写。
 *
 * 三件事：
 *  1. **看板**：把 6 条流派 × 8 阶拼成 [BranchBoard]，成绩永远现算（`workout_set_logs`）。
 *  2. **结算**：练完一场后给流派加 XP、判定考级、必要时按 XP 打折。见 [settle]。
 *  3. **支线池**：给定一个节点，从 2255 个自由动作里捞同流派同肌群的强化动作。
 */
class SkillRepository(
    private val progressDao: BranchProgressDao,
    private val exerciseDao: ExerciseDao,
    private val sessionDao: WorkoutSessionDao,
    private val routineDao: RoutineDao,
) {

    /**
     * 技能树看板。
     *
     * 除了进度表，还要监听「组明细指纹」—— 历史页可以手动改成绩，
     * 改完不监听的话「最好 12 次」会停在旧数字上。
     */
    fun observeBoards(): Flow<List<BranchBoard>> =
        combine(
            progressDao.observeAll(),
            exerciseDao.observeAllBosses(),
            sessionDao.observeSetFingerprint(),
        ) { progress, bosses, _ -> progress to bosses }
            .map { (progress, bosses) -> buildBoards(progress, bosses) }

    /**
     * 首次启动补齐 6 行进度，并把「currentTier 之前的阶」都补成已通关。
     *
     * 幂等：只在真的缺行/状态漂移时才写库。数据包更新导致某个主干动作换 id 时，
     * 靠这一步把状态接上，不会出现「第 5 阶已解锁但第 3 阶还没通关」的空洞。
     */
    suspend fun ensureInitialized() {
        val rows = progressDao.all().associateBy { it.branch }
        val now = System.currentTimeMillis()

        val missing = SkillBranch.entries.filter { it.key !in rows }
        if (missing.isNotEmpty()) {
            progressDao.upsertAll(
                missing.map { BranchProgressEntity(branch = it.key, currentTier = 1, updatedAt = now) }
            )
        }

        val stale = mutableListOf<String>()
        SkillBranch.entries.forEach { branch ->
            val tier = (rows[branch.key]?.currentTier ?: 1).coerceIn(1, SkillRules.MAX_TIER)
            exerciseDao.bossesOf(branch.key).forEach { boss ->
                if (boss.skillLevel < tier && !boss.isMastered) stale += boss.id
            }
        }
        if (stale.isNotEmpty()) exerciseDao.markMastered(stale)
    }

    /** 某个节点的同流派强化动作池。 */
    suspend fun accessoryPool(
        branch: SkillBranch,
        target: String,
        limit: Int = ACCESSORY_LIMIT,
    ): List<ExerciseEntity> =
        runCatching { exerciseDao.accessoryPool(branch.key, target, limit) }.getOrDefault(emptyList())

    /**
     * 结算「双轨升级」。
     *
     * 第一轨：**任意**同流派动作都给 XP，每组 [SkillRules.XP_PER_SET] 点 ——
     * 练的不是树上的动作也算数，2255 个自由动作因此都有意义。
     * 攒满 [SkillRules.XP_PER_BREAKTHROUGH] 点且当前阶还没通关，就吃掉 300 点换 20% 的要求折扣
     * （最多 60%），这是卡关时的保底。
     *
     * 第二轨：**正好练了当前阶的主干动作**且达标 → 直接通关、指针 +1。
     *
     * 一次训练每个流派最多晋升一阶：新解锁的那阶当次不参与判定，
     * 否则一次练透会把整条链连推好几阶，「考级」就没有分量了。
     */
    suspend fun settle(outcomes: List<SetOutcome>): Settlement {
        if (outcomes.isEmpty()) return Settlement.EMPTY

        val exercises = exerciseDao.findByIds(outcomes.map { it.exerciseId }.distinct())
            .associateBy { it.id }
        // 动作库里查不到的组不计经验：没有 branch 就没法归流派，硬塞进 CORE 会算错账
        val known = outcomes.filter { exercises.containsKey(it.exerciseId) }
        if (known.isEmpty()) return Settlement.EMPTY
        val byBranch = known.groupBy { SkillBranch.of(exercises[it.exerciseId]?.branch) }

        val now = System.currentTimeMillis()
        val xpGains = mutableListOf<BranchXp>()
        val promotions = mutableListOf<BranchPromotion>()
        val breakthroughs = mutableListOf<BranchBreakthrough>()

        byBranch.forEach { (branch, branchSets) ->
            val row = progressDao.find(branch.key)
                ?: BranchProgressEntity(branch = branch.key, currentTier = 1)

            // 1) 日常刷经验：每一组都算，不管是不是考级动作
            val gained = branchSets.size * SkillRules.XP_PER_SET
            var totalXp = row.totalXp + gained
            xpGains += BranchXp(branch, branchSets.size, gained)

            var tier = row.currentTier.coerceIn(1, SkillRules.MAX_TIER)
            var discount = row.discountPercent.coerceIn(0, SkillRules.MAX_DISCOUNT)
            var spent = row.xpSpent.coerceIn(0, totalXp)

            // 2) 主干考级与熟练度突破
            //
            // 顺序很关键：先按**当前**折扣判一次能不能过，过不了才花 XP 买折扣。
            // 反过来先扣 XP 再判，会出现「这场本来就能过、却先把攒下的 300 XP 花掉」
            // 的浪费 —— 突破是保底手段，不该抢通关的风头。
            val boss = exerciseDao.findBoss(branch.key, tier)
            val cleared = boss?.isMastered == true
            val baseRequirement = boss?.requirement()
            val bossSets = if (boss == null) emptyList() else branchSets.filter { it.exerciseId == boss.id }

            var passes = baseRequirement != null && !cleared &&
                baseRequirement.discounted(discount).meets(bossSets)

            while (!cleared && !passes && discount < SkillRules.MAX_DISCOUNT &&
                totalXp - spent >= SkillRules.XP_PER_BREAKTHROUGH
            ) {
                spent += SkillRules.XP_PER_BREAKTHROUGH
                discount += SkillRules.BREAKTHROUGH_STEP
                breakthroughs += BranchBreakthrough(
                    branch = branch,
                    discountPercent = discount,
                    newLabel = baseRequirement?.discounted(discount)?.label(discount).orEmpty(),
                )
                passes = baseRequirement?.discounted(discount)?.meets(bossSets) == true
            }

            if (boss != null && !cleared && passes) {
                exerciseDao.markMastered(listOf(boss.id))

                val fromTier = tier
                val atLast = fromTier >= SkillRules.MAX_TIER
                val nextTier = if (atLast) fromTier else fromTier + 1
                val nextBoss = if (atLast) null else exerciseDao.findBoss(branch.key, nextTier)

                promotions += BranchPromotion(
                    branch = branch,
                    fromTier = fromTier,
                    toTier = nextTier,
                    clearedId = boss.id,
                    clearedName = boss.name,
                    nextId = nextBoss?.id,
                    nextName = nextBoss?.name,
                    nextImage = nextBoss?.image,
                    allClear = atLast,
                )
                tier = nextTier
                // 晋升后新的一阶从满要求开始，之前攒的折扣不跟着走
                discount = 0
            }

            progressDao.upsert(
                row.copy(
                    currentTier = tier,
                    totalXp = totalXp,
                    xpSpent = spent,
                    discountPercent = discount,
                    updatedAt = now,
                )
            )
        }

        return Settlement(xp = xpGains, promotions = promotions, breakthroughs = breakthroughs)
    }

    /**
     * 把计划里的旧动作换成刚通关后解锁的新动作。
     *
     * 返回值约定：
     *  - `> 0` 改了几行，成功
     *  - `0`   这套计划里没有旧动作
     *  - `-1`  计划里**已经有**新动作了，再换会出现同一个动作占两格
     */
    suspend fun swapInRoutine(routineId: Long, oldId: String, newId: String): Int {
        if (routineId <= 0L) return 0
        return runCatching { routineDao.replaceSkill(routineId, oldId, newId) }.getOrDefault(0)
    }

    /**
     * 把一个动作追加到计划末尾（技能树上的「加入计划」）。
     *
     * 组数/目标值优先用节点自己的达标条件 —— 加进去就是一份现成的考级安排，
     * 用户不用再去编排页调；支线动作没有条件，给 3 组 × 10 次的默认值。
     */
    suspend fun addToRoutine(routineId: Long, exercise: ExerciseEntity): AddToRoutineResult {
        if (routineId <= 0L) return AddToRoutineResult.FAILED
        return runCatching {
            if (routineDao.countOfExercise(routineId, exercise.id) > 0) {
                return@runCatching AddToRoutineResult.ALREADY_THERE
            }
            val requirement = exercise.requirement()
            val timed = requirement.isTimed || exercise.category == "isometric"
            val sets = if (exercise.isBoss) requirement.sets.coerceIn(1, 10) else DEFAULT_SETS
            val value = if (exercise.isBoss && requirement.value > 0) {
                requirement.value
            } else if (timed) {
                DEFAULT_SECONDS
            } else {
                DEFAULT_REPS
            }

            routineDao.insertItem(
                RoutineExerciseEntity(
                    routineId = routineId,
                    exerciseId = exercise.id,
                    orderIndex = routineDao.nextOrderIndex(routineId),
                    targetSets = sets,
                    targetReps = if (timed) null else value,
                    targetDurationSec = if (timed) value else null,
                    restDurationSec = if (timed) 30 else 60,
                )
            )
            AddToRoutineResult.ADDED
        }.getOrDefault(AddToRoutineResult.FAILED)
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun buildBoards(
        progress: List<BranchProgressEntity>,
        bosses: List<ExerciseEntity>,
    ): List<BranchBoard> {
        if (bosses.isEmpty()) {
            return SkillBranch.entries.map {
                BranchBoard(it, progress.firstOrNull { p -> p.branch == it.key }
                    ?: BranchProgressEntity(it.key), emptyList())
            }
        }

        val bests = sessionDao.bestsOf(bosses.map { it.id }).associateBy { it.exerciseId }
        // 累计类要比「单场总和」，单组最好成绩说明不了问题，单独再查一次
        val volumeIds = bosses
            .filter { UnlockMetricType.from(it.unlockType) == UnlockMetricType.TOTAL_VOLUME_REPS }
            .map { it.id }
        val volumes = if (volumeIds.isEmpty()) {
            emptyMap()
        } else {
            sessionDao.bestSessionReps(volumeIds).associateBy { it.exerciseId }
        }

        val progressByBranch = progress.associateBy { it.branch }

        return SkillBranch.entries.map { branch ->
            val row = progressByBranch[branch.key]
                ?: BranchProgressEntity(branch = branch.key, currentTier = 1)
            val currentTier = row.currentTier.coerceIn(1, SkillRules.MAX_TIER)

            val nodes = bosses
                .filter { it.branch == branch.key }
                .sortedBy { it.skillLevel }
                .map { exercise ->
                    val requirement = exercise.requirement()
                    val best = when {
                        requirement.isVolume -> volumes[exercise.id]?.reps ?: 0
                        requirement.isTimed -> bests[exercise.id]?.seconds ?: 0
                        else -> bests[exercise.id]?.reps ?: 0
                    }
                    val state = when {
                        exercise.isMastered -> BossState.MASTERED
                        exercise.skillLevel == currentTier -> BossState.CURRENT
                        else -> BossState.LOCKED
                    }
                    BranchBoss(
                        branch = branch,
                        tier = exercise.skillLevel,
                        exercise = exercise,
                        state = state,
                        best = best,
                        requirement = requirement,
                        discountPercent = if (state == BossState.CURRENT) row.discountPercent else 0,
                    )
                }

            BranchBoard(branch = branch, progress = row, bosses = nodes)
        }
    }

    companion object {
        const val ACCESSORY_LIMIT = 15

        private const val DEFAULT_SETS = 3
        private const val DEFAULT_REPS = 10
        private const val DEFAULT_SECONDS = 30
    }
}

/** 把动作表上的四个字段拼成一条条件对象。 */
fun ExerciseEntity.requirement(): UnlockRequirement = UnlockRequirement(
    type = UnlockMetricType.from(unlockType),
    sets = unlockSets.coerceAtLeast(1),
    value = unlockValue.coerceAtLeast(0),
    text = unlockText,
)
