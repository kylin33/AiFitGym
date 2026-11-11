package io.github.kylin33.aifitgym.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.kylin33.aifitgym.data.seeding.ImportState
import io.github.kylin33.aifitgym.di.AppContainer
import io.github.kylin33.aifitgym.ui.ai.AiSettingsScreen
import io.github.kylin33.aifitgym.ui.ai.AiSettingsViewModel
import io.github.kylin33.aifitgym.ui.ai.CoachScreen
import io.github.kylin33.aifitgym.ui.ai.CoachViewModel
import io.github.kylin33.aifitgym.ui.backup.BackupScreen
import io.github.kylin33.aifitgym.ui.backup.BackupViewModel
import io.github.kylin33.aifitgym.ui.components.SeedErrorScreen
import io.github.kylin33.aifitgym.ui.components.SeedScreen
import io.github.kylin33.aifitgym.ui.detail.DetailScreen
import io.github.kylin33.aifitgym.ui.detail.DetailViewModel
import io.github.kylin33.aifitgym.ui.history.HistoryScreen
import io.github.kylin33.aifitgym.ui.history.HistoryViewModel
import io.github.kylin33.aifitgym.ui.history.SessionDetailScreen
import io.github.kylin33.aifitgym.ui.history.SessionDetailViewModel
import io.github.kylin33.aifitgym.ui.legal.LegalContent
import io.github.kylin33.aifitgym.ui.legal.LegalScreen
import io.github.kylin33.aifitgym.ui.library.LibraryScreen
import io.github.kylin33.aifitgym.ui.picker.ExercisePickerScreen
import io.github.kylin33.aifitgym.ui.picker.ExercisePickerViewModel
import io.github.kylin33.aifitgym.ui.profile.ProfileScreen
import io.github.kylin33.aifitgym.ui.profile.ProfileViewModel
import io.github.kylin33.aifitgym.ui.recovery.RecoveryScreen
import io.github.kylin33.aifitgym.ui.recovery.RecoveryViewModel
import io.github.kylin33.aifitgym.ui.routines.RoutineEditorScreen
import io.github.kylin33.aifitgym.ui.routines.RoutineEditorViewModel
import io.github.kylin33.aifitgym.ui.routines.RoutineListScreen
import io.github.kylin33.aifitgym.ui.routines.RoutineListViewModel
import io.github.kylin33.aifitgym.ui.skill.SkillTreeScreen
import io.github.kylin33.aifitgym.ui.skill.SkillTreeViewModel
import io.github.kylin33.aifitgym.ui.workout.ActiveWorkoutScreen
import io.github.kylin33.aifitgym.ui.workout.RequestWorkoutNotificationPermission
import io.github.kylin33.aifitgym.ui.workout.WorkoutSessionViewModel

/** 路由表。计划列表是首页，动作库与编辑器都从这里进。 */
object Routes {
    const val ROUTINES = "routines"

    const val ROUTINE_ID = "routineId"
    const val ROUTINE_EDITOR = "routine_editor?$ROUTINE_ID={$ROUTINE_ID}"

    const val EXERCISE_PICKER = "exercise_picker"

    /** 全屏跟练页。 */
    const val WORKOUT = "workout/{$ROUTINE_ID}"

    /** 历史详情页。 */
    const val SESSION_ID = "sessionId"
    const val SESSION_DETAIL = "session/{$SESSION_ID}"

    /** 训练历史列表，从「我的」进。 */
    const val HISTORY = "history"

    /** 备份与恢复。 */
    const val BACKUP = "backup"

    /** AI 教练对话页；设置是它上面的一层，返回后对话还在。 */
    const val COACH = "coach"
    const val AI_SETTINGS = "ai_settings"

    /** 法律文本：两条独立路由，从「我的 → 通用」进。 */
    const val PRIVACY = "legal/privacy"
    const val DISCLAIMER = "legal/disclaimer"

    const val LIBRARY = "library"
    const val EXERCISE_ID = "exerciseId"
    const val EXERCISE = "exercise/{$EXERCISE_ID}"

    /** 技能树的「直接开始考级」：只有一条动作的临时训练。 */
    const val WORKOUT_EXERCISE = "workout_exercise/{$EXERCISE_ID}"

    fun routineEditor(routineId: Long = -1L) = "routine_editor?routineId=$routineId"

    fun workout(routineId: Long) = "workout/$routineId"

    fun workoutExercise(exerciseId: String) = "workout_exercise/$exerciseId"

    fun session(sessionId: Long) = "session/$sessionId"

    fun exercise(id: String) = "exercise/$id"
}

/**
 * 整页路由的推入/推出动效。
 *
 * 原来走的是 NavHost 默认过渡（淡入 + 轻微缩放），从底栏点进「训练历史」时
 * 观感就是「凭空出现」，退出又往中间缩一下再没，和「进入下一层」的心智模型对不上。
 *
 * 现在改成横向位移：新页从右侧整屏推入，被盖住的那页只往左让出四分之一 ——
 * 保留纵深，又不至于让底栏整个滑出屏幕。退出时两台方向反过来，哪来的回哪去。
 *
 * 刻意**不加淡入淡出**：NavHost 本身不画背景色，半透明过渡会漏出白色窗口底色，
 * 在深色主题下就是一下闪白。
 */
private const val PAGE_SLIDE_MS = 280

private val pageEnter: EnterTransition = slideInHorizontally(
    animationSpec = tween(PAGE_SLIDE_MS, easing = FastOutSlowInEasing),
) { fullWidth -> fullWidth }

private val pageExit: ExitTransition = slideOutHorizontally(
    animationSpec = tween(PAGE_SLIDE_MS, easing = FastOutSlowInEasing),
) { fullWidth -> -fullWidth / 4 }

private val pagePopEnter: EnterTransition = slideInHorizontally(
    animationSpec = tween(PAGE_SLIDE_MS, easing = FastOutSlowInEasing),
) { fullWidth -> -fullWidth / 4 }

private val pagePopExit: ExitTransition = slideOutHorizontally(
    animationSpec = tween(PAGE_SLIDE_MS, easing = FastOutSlowInEasing),
) { fullWidth -> fullWidth }

/**
 * 应用外壳：先等内置动作库进库，再进导航。
 *
 * 导入是首启一次性的，之后 [ImportState.Done] 会立刻带着库里的条数返回，
 * 所以第二次启动不会出现等待页（最多闪一下检查态）。
 */
@Composable
fun FitGymRoot(container: AppContainer) {
    val libraryViewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(container))
    val importState by libraryViewModel.importState.collectAsStateWithLifecycle()

    when (val state = importState) {
        ImportState.Idle, is ImportState.Running -> SeedScreen(state = state)

        is ImportState.Failed -> SeedErrorScreen(
            message = state.message,
            onRetry = libraryViewModel::retryImport,
        )

        is ImportState.Done -> AppNavHost(container, libraryViewModel, state)
    }
}

@Composable
private fun AppNavHost(
    container: AppContainer,
    libraryViewModel: LibraryViewModel,
    seed: ImportState.Done,
) {
    val navController = rememberNavController()

    // 编排页和动作选择器是两个路由，但要共用同一份草稿，所以 ViewModel 建在 NavHost 外（Activity 作用域）
    val routineListViewModel: RoutineListViewModel = viewModel(factory = RoutineListViewModel.Factory(container))
    val editorViewModel: RoutineEditorViewModel = viewModel(factory = RoutineEditorViewModel.Factory(container))
    val pickerViewModel: ExercisePickerViewModel = viewModel(factory = ExercisePickerViewModel.Factory(container))

    val routines by routineListViewModel.routines.collectAsStateWithLifecycle()
    val exercises by libraryViewModel.exercises.collectAsStateWithLifecycle()
    val query by libraryViewModel.query.collectAsStateWithLifecycle()
    val gymEquipmentVisible by libraryViewModel.gymEquipmentVisible.collectAsStateWithLifecycle()

    // AI 教练的会话建立在 NavHost 外层（Activity 作用域）：从教练页点进设置、再返回时，
    // 对话不该被清空重来 —— 建在路由作用域的话，退出路由 ViewModel 就销毁了。
    val coachViewModel: CoachViewModel = viewModel(factory = CoachViewModel.Factory(container))
    val coachState by coachViewModel.state.collectAsStateWithLifecycle()
    val coachSettings by coachViewModel.settings.collectAsStateWithLifecycle()
    val coachMessage by coachViewModel.message.collectAsStateWithLifecycle()

    // 首启导入耗时只在真的导入时才有意义，读缓存时说明「本地库已就绪」就好
    val statusText = if (seed.fromCache) {
        "本地库已就绪 · ${seed.count} 个动作"
    } else {
        "首次导入完成 · ${seed.count} 条 · 耗时 ${seed.elapsedMs} ms"
    }

    NavHost(
        navController = navController,
        startDestination = Routes.ROUTINES,
        enterTransition = { pageEnter },
        exitTransition = { pageExit },
        popEnterTransition = { pagePopEnter },
        popExitTransition = { pagePopExit },
    ) {
        composable(Routes.ROUTINES) {
            // Tab 1 计划 / Tab 2 技能树 / Tab 3 恢复 / Tab 4 我的（历史、备份、设置）。
            // VM 建在导航条目作用域，切 Tab 不会丢状态
            var tab by rememberSaveable { mutableStateOf(0) }
            val skillViewModel: SkillTreeViewModel = viewModel(factory = SkillTreeViewModel.Factory(container))
            val skillState by skillViewModel.state.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            val recoveryViewModel: RecoveryViewModel = viewModel(factory = RecoveryViewModel.Factory(container))
            val recoveryState by recoveryViewModel.state.collectAsStateWithLifecycle()
            val profileViewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory(container))
            val profileStats by profileViewModel.stats.collectAsStateWithLifecycle()
            val profileClearing by profileViewModel.clearing.collectAsStateWithLifecycle()
            val profileMessage by profileViewModel.message.collectAsStateWithLifecycle()

            // 「已加进计划 / 已经有了」这类回执走 Snackbar，不打断页面
            LaunchedEffect(skillState.message) {
                skillState.message?.let {
                    snackbarHostState.showSnackbar(it)
                    skillViewModel.clearMessage()
                }
            }

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                            label = { Text("计划") },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                            label = { Text("技能树") },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                            label = { Text("恢复") },
                        )
                        NavigationBarItem(
                            selected = tab == 3,
                            onClick = { tab = 3 },
                            icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                            label = { Text("我的") },
                        )
                    }
                },
                // 顶部交给各页面自己处理（它们都有 statusBarsPadding），这里只吃底栏的高度
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { inner ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(inner),
                ) {
                    when (tab) {
                        0 -> RoutineListScreen(
                            routines = routines,
                            events = routineListViewModel.events,
                            onOpenRoutine = { id ->
                                editorViewModel.endSession()
                                navController.navigate(Routes.routineEditor(id))
                            },
                            onCreateRoutine = {
                                editorViewModel.endSession()
                                navController.navigate(Routes.routineEditor())
                            },
                            onStartWorkout = { id -> navController.navigate(Routes.workout(id)) },
                            onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                            onOpenCoach = { navController.navigate(Routes.COACH) },
                            onDeleteRoutine = routineListViewModel::delete,
                            onUndoDelete = routineListViewModel::undoDelete,
                        )

                        1 -> SkillTreeScreen(
                            state = skillState,
                            onSelectBranch = skillViewModel::select,
                            onOpenBoss = skillViewModel::openBoss,
                            onCloseBoss = skillViewModel::closeBoss,
                            onAddToRoutine = skillViewModel::addToRoutine,
                            onStartBoss = { id -> navController.navigate(Routes.workoutExercise(id)) },
                            onOpenExerciseDetail = { id -> navController.navigate(Routes.exercise(id)) },
                        )

                        2 -> RecoveryScreen(
                            state = recoveryState,
                            onModeChange = recoveryViewModel::setMode,
                            onRangeChange = recoveryViewModel::setRange,
                            onRefresh = recoveryViewModel::refresh,
                        )

                        else -> ProfileScreen(
                            stats = profileStats,
                            exerciseCount = seed.count,
                            clearing = profileClearing,
                            message = profileMessage,
                            onOpenHistory = { navController.navigate(Routes.HISTORY) },
                            onOpenBackup = { navController.navigate(Routes.BACKUP) },
                            onOpenCoach = { navController.navigate(Routes.COACH) },
                            onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                            onOpenDisclaimer = { navController.navigate(Routes.DISCLAIMER) },
                            onClearAllData = profileViewModel::clearAllData,
                            onMessageShown = profileViewModel::consumeMessage,
                        )
                    }
                }
            }
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                exercises = exercises,
                statusText = statusText,
                query = query,
                onQueryChange = libraryViewModel::onQueryChange,
                onOpenDetail = { id -> navController.navigate(Routes.exercise(id)) },
                gymEquipmentVisible = gymEquipmentVisible,
                onToggleGymEquipment = libraryViewModel::toggleGymEquipment,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.EXERCISE,
            arguments = listOf(navArgument(Routes.EXERCISE_ID) { type = NavType.StringType }),
        ) { entry ->
            val exerciseId = entry.arguments?.getString(Routes.EXERCISE_ID).orEmpty()
            val detailViewModel: DetailViewModel = viewModel(
                key = "detail-$exerciseId",
                factory = DetailViewModel.Factory(container, exerciseId),
            )
            val exercise by detailViewModel.exercise.collectAsStateWithLifecycle()
            DetailScreen(
                exercise = exercise,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.ROUTINE_EDITOR,
            arguments = listOf(
                navArgument(Routes.ROUTINE_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { entry ->
            val routineId = entry.arguments?.getLong(Routes.ROUTINE_ID) ?: -1L
            LaunchedEffect(routineId) { editorViewModel.start(routineId) }

            // 系统返回键也要走同一条退出路径，否则下次进来会看到上一次没保存的草稿
            BackHandler {
                editorViewModel.endSession()
                navController.popBackStack()
            }

            val editorState by editorViewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(editorState.saved) {
                if (editorState.saved) {
                    editorViewModel.consumeSaved()
                    editorViewModel.endSession()
                    navController.popBackStack()
                }
            }

            RoutineEditorScreen(
                state = editorState,
                onBack = {
                    editorViewModel.endSession()
                    navController.popBackStack()
                },
                onNameChange = editorViewModel::onNameChange,
                onDescriptionChange = editorViewModel::onDescriptionChange,
                onCategoryChange = editorViewModel::onCategoryChange,
                onAddExercise = {
                    editorViewModel.openPicker()
                    navController.navigate(Routes.EXERCISE_PICKER)
                },
                onPickBackup = { draftId ->
                    editorViewModel.openBackupPicker(draftId)
                    navController.navigate(Routes.EXERCISE_PICKER)
                },
                onClearBackup = editorViewModel::clearBackup,
                onRemove = editorViewModel::remove,
                onMove = editorViewModel::move,
                onSetsChange = editorViewModel::setSets,
                onRepsChange = editorViewModel::setReps,
                onDurationChange = editorViewModel::setDuration,
                onRestChange = editorViewModel::setRest,
                onToggleTimed = editorViewModel::toggleTimed,
                onSave = editorViewModel::save,
            )
        }

        composable(
            route = Routes.WORKOUT,
            arguments = listOf(navArgument(Routes.ROUTINE_ID) { type = NavType.LongType }),
        ) { entry ->
            val routineId = entry.arguments?.getLong(Routes.ROUTINE_ID) ?: -1L
            // key 带上 routineId：不同计划的跟练会话互不影响，返回时随 back stack 一起销毁
            val workoutViewModel: WorkoutSessionViewModel = viewModel(
                key = "workout-$routineId",
                factory = WorkoutSessionViewModel.Factory(container),
            )
            LaunchedEffect(routineId) { workoutViewModel.start(routineId) }
            // 通知栏倒计时要在锁屏时也看得见，进跟练页顺手把权限要了
            RequestWorkoutNotificationPermission()

            val workoutState by workoutViewModel.state.collectAsStateWithLifecycle()

            ActiveWorkoutScreen(
                state = workoutState,
                onAdjustReps = workoutViewModel::adjustReps,
                onCompleteSet = workoutViewModel::completeSet,
                onFinishTimedSet = workoutViewModel::finishTimedSetEarly,
                onSkipRest = workoutViewModel::skipRest,
                onExtendRest = { workoutViewModel.extendRest() },
                onToggleBackup = workoutViewModel::toggleBackup,
                onFinishEarly = workoutViewModel::finishEarly,
                onDismissPromotions = workoutViewModel::dismissPromotions,
                onSwapPromoted = workoutViewModel::swapPromotedToNext,
                onExit = { navController.popBackStack() },
            )
        }

        // 技能树的「直接开始考级」：没有计划，只练一个动作，落库时 routineId 为空
        composable(
            route = Routes.WORKOUT_EXERCISE,
            arguments = listOf(navArgument(Routes.EXERCISE_ID) { type = NavType.StringType }),
        ) { entry ->
            val exerciseId = entry.arguments?.getString(Routes.EXERCISE_ID).orEmpty()
            val workoutViewModel: WorkoutSessionViewModel = viewModel(
                key = "workout-exercise-$exerciseId",
                factory = WorkoutSessionViewModel.Factory(container),
            )
            LaunchedEffect(exerciseId) { workoutViewModel.startSingle(exerciseId) }
            RequestWorkoutNotificationPermission()

            val workoutState by workoutViewModel.state.collectAsStateWithLifecycle()

            ActiveWorkoutScreen(
                state = workoutState,
                onAdjustReps = workoutViewModel::adjustReps,
                onCompleteSet = workoutViewModel::completeSet,
                onFinishTimedSet = workoutViewModel::finishTimedSetEarly,
                onSkipRest = workoutViewModel::skipRest,
                onExtendRest = { workoutViewModel.extendRest() },
                onToggleBackup = workoutViewModel::toggleBackup,
                onFinishEarly = workoutViewModel::finishEarly,
                onDismissPromotions = workoutViewModel::dismissPromotions,
                onSwapPromoted = workoutViewModel::swapPromotedToNext,
                onExit = { navController.popBackStack() },
            )
        }

        // 历史从「我的」进，是一条独立的整页路由（不再是底栏 Tab）
        composable(Routes.HISTORY) {
            val historyViewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory(container))
            val sessions by historyViewModel.sessions.collectAsStateWithLifecycle()

            HistoryScreen(
                sessions = sessions,
                onBack = { navController.popBackStack() },
                onOpenSession = { id -> navController.navigate(Routes.session(id)) },
            )
        }

        composable(Routes.BACKUP) {
            val backupViewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory(container))
            val backupState by backupViewModel.state.collectAsStateWithLifecycle()
            val overview by backupViewModel.overview.collectAsStateWithLifecycle()

            BackupScreen(
                state = backupState,
                overview = overview,
                onBack = { navController.popBackStack() },
                onExport = backupViewModel::export,
                onPickImport = backupViewModel::pickImport,
                onConfirmImport = backupViewModel::confirmImport,
                onCancelImport = backupViewModel::cancelImport,
                onMessageShown = backupViewModel::consumeMessage,
            )
        }

        composable(Routes.COACH) {
            CoachScreen(
                state = coachState,
                settings = coachSettings,
                message = coachMessage,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.AI_SETTINGS) },
                onSend = coachViewModel::send,
                onStop = coachViewModel::stop,
                onImport = coachViewModel::importPlan,
                // 导入后「打开计划」直接进编排页，用户通常还要微调组数
                onOpenRoutine = { id -> navController.navigate(Routes.routineEditor(id)) },
                onNewChat = coachViewModel::newChat,
                onMessageShown = coachViewModel::consumeMessage,
            )
        }

        composable(Routes.AI_SETTINGS) {
            val aiSettingsViewModel: AiSettingsViewModel = viewModel(
                factory = AiSettingsViewModel.Factory(container),
            )
            val settingsState by aiSettingsViewModel.state.collectAsStateWithLifecycle()
            val testState by aiSettingsViewModel.test.collectAsStateWithLifecycle()

            AiSettingsScreen(
                state = settingsState,
                test = testState,
                onBack = { navController.popBackStack() },
                onPreset = { preset -> aiSettingsViewModel.applyPreset(preset.baseUrl, preset.model) },
                onBaseUrlChange = aiSettingsViewModel::setBaseUrl,
                onModelChange = aiSettingsViewModel::setModel,
                onSaveKey = aiSettingsViewModel::saveKey,
                onClearKey = aiSettingsViewModel::clearKey,
                onTest = aiSettingsViewModel::testConnection,
                onGoalChange = aiSettingsViewModel::setGoal,
                onLevelChange = aiSettingsViewModel::setLevel,
                onDaysChange = aiSettingsViewModel::setDays,
                onMinutesChange = aiSettingsViewModel::setMinutes,
                onNotesChange = aiSettingsViewModel::setNotes,
            )
        }

        composable(Routes.PRIVACY) {
            LegalScreen(
                title = LegalContent.PRIVACY_TITLE,
                sections = LegalContent.privacy,
                updated = LegalContent.UPDATED,
                intro = "这一页只描述 FitGym 真正做过的事，所以它比一般的隐私政策短得多 —— " +
                    "一个没有账号、数据不出手机的 App，本来就没有多少数据流动可讲。",
                showRepoLink = true,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.DISCLAIMER) {
            LegalScreen(
                title = LegalContent.DISCLAIMER_TITLE,
                sections = LegalContent.disclaimer,
                updated = LegalContent.UPDATED,
                intro = "开始训练前，请花两分钟读完这一页。它不会让你练得更好，" +
                    "但能让你在受伤之前知道哪些事不该做。",
                showRepoLink = false,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.SESSION_DETAIL,
            arguments = listOf(navArgument(Routes.SESSION_ID) { type = NavType.LongType }),
        ) { entry ->
            val sessionId = entry.arguments?.getLong(Routes.SESSION_ID) ?: -1L
            val detailViewModel: SessionDetailViewModel = viewModel(
                key = "session-$sessionId",
                factory = SessionDetailViewModel.Factory(container, sessionId),
            )
            val detailState by detailViewModel.state.collectAsStateWithLifecycle()

            // 删完整次训练就自动退回列表，不用再点一次返回
            LaunchedEffect(detailState.closed) {
                if (detailState.closed) navController.popBackStack()
            }

            SessionDetailScreen(
                state = detailState,
                onBack = { navController.popBackStack() },
                onEditSet = detailViewModel::updateSet,
                onAddSet = detailViewModel::addSet,
                onDeleteSet = detailViewModel::deleteSet,
                onFinishSession = detailViewModel::finishSession,
                onDeleteSession = detailViewModel::deleteSession,
            )
        }

        composable(Routes.EXERCISE_PICKER) {
            val editorState by editorViewModel.state.collectAsStateWithLifecycle()
            val pickerQuery by pickerViewModel.query.collectAsStateWithLifecycle()
            val pickerExercises by pickerViewModel.exercises.collectAsStateWithLifecycle()

            ExercisePickerScreen(
                exercises = pickerExercises,
                query = pickerQuery,
                onQueryChange = pickerViewModel::onQueryChange,
                selectedIds = editorState.selectedIds,
                multiSelect = editorState.pickerMultiSelect,
                onToggle = { id ->
                    if (editorState.pickerMultiSelect) {
                        editorViewModel.toggleSelection(id)
                    } else {
                        editorViewModel.selectOnly(id)
                    }
                },
                onConfirm = {
                    editorViewModel.confirmPicker()
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
