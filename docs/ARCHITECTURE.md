# 架构与实现笔记

README 只保留上手需要的信息，这里是实现层面的记录：为什么这么设计、踩过哪些坑。
按「数据 → 素材 → 跟练 → 构建」的顺序组织。

---

## 数据层

### 表结构

| 表 | 实体 | 说明 |
| --- | --- | --- |
| `exercises` | `ExerciseEntity` | 内置动作库，主键是上游 id（字符串），带 `searchKey` 供 LIKE 模糊搜索 |
| `routines` | `RoutineEntity` | 计划主表：`name` / `description` / `category` / `presetKey` |
| `routine_exercises` | `RoutineExerciseEntity` | 计划条目：`orderIndex` 排序，`targetSets` / `targetReps` / `targetDurationSec` / `restDurationSec` / `backupExerciseId`，随计划级联删除 |
| `workout_sessions` | `WorkoutSessionLogEntity` | 一次训练一条，结算页汇总用 |
| `workout_set_logs` | `WorkoutSetLogEntity` | 按「组」记录的打卡历史，`sessionId` 把同一次训练串起来 |
| `branch_progress` | `BranchProgressEntity` | 技能树的解锁与等级进度 |
| `app_meta` | `AppMetaEntity` | 键值表：导入条数、检查点、AI 对话 |

计划条目里 **计次和计时二选一**：`targetReps` 有值是计次动作（俯卧撑 12 次），
`targetDurationSec` 有值是计时动作（平板支撑 45 秒）。两个都为空时按计次 10 兜底。
`isTimed` 是计算属性，不占列。

三个刻意的取舍：

- **`exerciseId` 不设外键**。动作库是 assets 里的可再生数据，重建库时会整表替换，
  外键级联会把用户的计划条目和历史记录一起删掉。只有 `routineId → routines` 用 CASCADE。
- **按组存而不是按次存**。之后算肌肉热力图/恢复期可以直接 `GROUP BY exerciseId` 聚合
  训练量，不用再拆表；`WorkoutSetLogDao.volumeSince()` 已经预留了这个查询。
- **保存计划用 `@Update` 而不是 `REPLACE`**。SQLite 的 REPLACE 是「删了再插」，会触发
  外键 CASCADE 把条目一起删掉；`RoutineDao.save()` 在事务里按 `orderIndex` 重排后整批重写。

### 数据库版本

| 版本 | 改了什么 |
| --- | --- |
| v1 | 计划只有 position/sets/reps/restSeconds |
| v2 | 计划拆成 `routines` + `routine_exercises`，补计时与平替字段 |
| v3 | 打卡改成 `workout_sessions` + `workout_set_logs` |
| v4 | 加技能树解锁状态 |
| v5 | 技能树从 4 链 × 5 阶扩到 6 链 × 8 阶 |
| v6 | 技能树重做：48 个主干动作标在 `exercises` 上，进度收成 `branch_progress` 六行 |
| v7 | 计划条目加 `targetRir`（目标强度），老计划为 null |
| v8 | 组记录加实际休息与实际目标（`restPlannedSec` / `restActualSec` / `targetReps` / `targetDurationSec`） |

v1 → v2 时两张计划表都是空的，那时允许直接重建。现在的策略是「只给没发布过的版本开后门」：

```kotlin
.fallbackToDestructiveMigrationFrom(true, *DESTRUCTIVE_START_VERSIONS) // 只有 v1~v4
```

`fallbackToDestructiveMigration(...)` 会把 `requireMigration` 整体关掉 —— 以后任何一次漏写迁移
都会静默删库，用户的训练历史跟着没。换成 `From` 版本只把 v1~v4 这几个从没发布过的起点列入白名单，
v5 之后找不到迁移路径时 Room 照旧抛 `IllegalStateException`：闪退有反馈、数据还在文件里，
补一条迁移就能救回来。宁可崩，也不要悄悄清空别人练了半年的记录。

迁移都在 `FitGymDatabase` 里手写（`MIGRATION_5_6` / `MIGRATION_6_7` / `MIGRATION_7_8`），
只加可空列时用 `ALTER TABLE` 就够，不用重建表。改表结构的完整规矩见
[DEVELOPMENT.md](DEVELOPMENT.md#改数据库结构)，`MigrationChainTest` 会在单测阶段把这些规矩验一遍。

### 首启导入

入口在 `FitGymDatabase.create()` 的 `Callback.onCreate`，实际逻辑是 `CatalogueSeeder.importInto(db)`：

1. `CatalogReader.readEntities()` 在 IO 线程读 `assets/exercises.json`，反序列化后映射成
   `ExerciseEntity`（每 200 条回调一次进度）；
2. `ExerciseDao.replaceAll()` 在**单个事务**内分块写入，中途被杀进程不会留下半套数据；
3. 成功后写 `app_meta`（条数 + 时间戳），失败则 `ImportState.Failed`，UI 可重试。

几个细节：

- `onCreate` 只在数据库文件第一次创建时触发，所以二次启动不会再导入；
  `AppContainer.bootstrap()` 额外做一次幂等检查（`count() > 0` 直接返回），兜住「建库后导入失败」。
- Seeder 不持有数据库实例（数据库回调要反过来调 Seeder，会成环），改为方法传库。
- 并发保护：`Mutex` + 条数检查，建库回调和启动兜底同时进来也只导一次。

实测（Android 16 模拟器，debug 包）：**2303 条 / 2.6s（解析 1.2s + 写库 0.3s）**，
二次启动直接读缓存库。首次会看到进度页，之后秒进。

### 首启不预置计划

新装的 `routines` 表是空的。计划页显示一张空状态卡（`EmptyPlansCard`），给两条路：
让 AI 按器械和近期记录排一套，或者自己从动作库挑。

之前确实预置过三套（囚徒健身推力日 / 拉力日、Tabata 燃脂间歇），后来整套拆了。
不预置的理由：

- 计划里真正起作用的是「他现在能练什么、练多少」，模板给的是别人的量；
- 用户上手第一件事往往是删掉不符合器械的模板（没单杠就练不了引体向上），
  删一遍比从空白开始更烦；
- 模板会让「在原有计划上调整」失去参照 —— AI 到底是在改用户的计划，还是在改模板。

`PresetRoutineSeeder` 与 `RoutinePresets` 已删除，`bootstrap()` 只剩「灌动作库、
补技能树起点」两步。`routines.presetKey` 字段保留：升级用户库里那三套还在，
界面据此标「内置」；新建计划一律为 null。清空数据也不再补计划，只补 6 条 Lv.1 账本。

---

## 动作数据

`app/src/main/assets/`

| 内容 | 说明 |
| --- | --- |
| `exercises.json` | 2303 个动作，含中英文名、部位、目标肌群、副肌群、器械、步骤（1.7 MB） |
| `gifs/<id>.webp` | 与动作一一对应的**单帧**示意图，2303 张（10.6 MB） |
| `clips/<id>.mp4` | 主打动作的动态演示，989 个（24 MB），见下文 |
| `taxonomy.json` | 部位 / 分类枚举及计数，调试参考用 |
| `anatomy/` | 人体热力图资源（见下节） |

筛选口径：`equipment == "body weight"`（徒手）**或** `category == "cardio"`（有氧）。
数据源实际用词是 `"body weight"`（带空格），分类里徒手动作多为 `calisthenics`。

### 动态演示（短片）

上游的 `.webp` 只有一帧，动作过程全在 `media/clip/<id>.mp4` 里：
**180×180、约 9 秒、H.264、faststart（moov 在文件头）**，中位 23 KB、最大 79 KB。
分辨率与静态图一致，所以换成短片不会更糊，只是会动。

全量 2303 个约 56 MB（均 25 KB），整个塞进安装包就是 70 MB 的 APK，太重。折中是**内置主打动作、
冷门动作按需下载**：

| | 数量 | 体积 | 位置 |
| --- | --- | --- | --- |
| 随 APK 内置 | 989（徒手 865 + 力量 104，另补主干与模板用到的 20 个） | 24 MB | `assets/clips/` |
| 按需下载 | 1314（拉伸 408、柔韧 253、爆发力 180、有氧 145、等长 134、瑜伽 109、普拉提 47、搏击 36、康复 22） | 32 MB | `noBackupFilesDir/clips/` |

```bash
python tools/fetch_clips.py            # 内置集合（989 个，约 24 MB）
python tools/fetch_clips.py --all      # 全量（约 56 MB）
python tools/fetch_clips.py --ids 2308 --force
```

脚本并发拉取，幂等（已存在且是合法 mp4 就跳过），先写 `.part` 再改名，
中断不会留下半截文件被当成已下载。

#### 按需下载

入口在动作抽屉与详情页（`ClipDownloadAction`）：缺片时显示一行
「演示短片未内置，可下载补全 · 下载」，点一下下到私有目录，下完当场从静态图
切成循环播放，不用退出重进。**我的 → 通用 → 演示短片** 能看到占用并一键清理。

四个刻意的选择：

- **存 `noBackupFilesDir`**：manifest 里 `allowBackup=true`，`filesDir` 会被算进
  Google 云备份 —— 几十 MB 短片同步上去既没意义又费流量；`cacheDir` 更不行，
  系统清缓存会把用户「下载过」的片子删掉。
- **不落数据库**：文件在不在就是唯一状态。省一次 Room 迁移，也不会出现
  「库里说有、文件被删了」这种半截状态；启动时扫一遍目录得到集合。
- **两个源**：jsDelivr（国内更通）→ raw.githubusercontent（CDN 还没同步的新动作只有它）。
  两个都失败才报错，异常翻成人话（网络不可用 / 连接超时 / 连不上服务器）。
- **`.part` 再改名 + ftyp 校验**：半截文件不会被当成已下载。

#### 播放

Media3 ExoPlayer：内置片用 `asset:///clips/<id>.mp4`（`AssetDataSource` 原生支持），
下载片用 `file://`，同一个 `ExerciseClip` 都吃。三个必须的配置：

- `androidResources { noCompress += "mp4" }`：被压缩的 asset 拿不到文件描述符，
  `openFd()` 会失败，还会多占一份内存。
- `ExerciseClip` 在 `DisposableEffect` 里 `release()`：不释放的话解码器一直被占着，
  在动作之间来回切会 OOM；同时按 `ON_STOP / ON_START` 暂停与续播。
- 播放器 `remember(uri)`：下载完成后 `ClipStore.available` 变了、uri 也变了，
  播放器随之重建，抽屉不关也能出画面。

没有短片的动作自动回退到 `ExerciseImage`，两者尺寸相同，界面不会跳。
判断依据是 `ClipStore.available`（assets 目录一次列全 + 已下载集合），
比逐个 `open()` 撞异常便宜得多。

### 重新生成

```bash
python tools/build_catalogue.py --catalogue <openGym 仓库>/catalogue
```

遍历 `catalogue/exercises/*.json`，跳过只剩 2 个字段的占位文件，合并 `catalogue/i18n/zh.json`
的中文名与中文步骤，复制对应 WebP 到 `assets/gifs/`，最后打印数量、体积与中英文覆盖率。
常用参数：`--keep-without-media`（缺图也保留）、`--limit N`（只导前 N 个）、
`--i18n zh-TW`（繁中）。

### 上游仓库

`catalogue/` 实际有 6554 个动作文件（其中 922 个占位），媒体是 `media/still/<id>.webp`
（**单帧**静态图）与 `media/clip/<id>.mp4`（动作短片），**没有 GIF**。

---

## 人体肌肉热力图

`app/src/main/assets/anatomy/`

| 内容 | 说明 |
| --- | --- |
| `body-paths.json` | male/female × front/back 四个视图的 viewBox 与逐肌肉 path data，共 333 条（92 KB） |
| `muscles.json` | 18 块肌肉的 slug / 中英文名 / 疲劳半衰期、50 条别名映射、体部位回退权重、色阶与疲劳参数 |

来源：openGym `frontend/src/lib/body-paths.js`，原始图形是 **MuscleMap by Melih Colpan（MIT）**，
上游已注明「Generated — do not hand-edit」。几何只做了结构归一化，path 字符串原样保留。

```bash
python tools/extract_anatomy.py --frontend <openGym 仓库>/frontend
```

脚本解析 `body-paths.js`（本来就是 JSON 字面量）、从 `muscles.js` 抠出 `MUSCLES` / `INERT` /
`ALIAS` / `BY_BODYPART`、从 `recovery.js` 抠出疲劳半衰期，并生成
`tools/preview/anatomy-preview.html` 供肉眼校验配色。

### 配色算法（照搬上游）

- 未训练底色 = `label 11%` 叠在 `surface` 上；躯干轮廓 = `label 18%`
- 分级 l1…l4 = 底色与 accent 按 **32% / 56% / 78% / 100%** 混合
- 相对分级（默认）：`level = ceil(该肌肉负荷 / 最大负荷 × 4)`，0 负荷为 l0
- 固定分级：传入 `{at, level, exclusive}` 阈值表，恢复期视图用它保持档位语义稳定
- 恢复期配色：l1 黄 32%、l2 黄、l3 橙、l4 红；疲劳度 `<0.25` 已恢复、`0.25–0.5` 恢复中、`>0.5` 仍酸痛
- 疲劳度累计：按半衰期（手臂/核心 24h、躯干 30h、腿与下背 48h）指数衰减后走 `1 - exp(-v)` 饱和曲线

### Compose 渲染

几何用 `PathParser` 把 path data 解析成 `Path`，在 `Canvas` 上逐块上色——`VectorDrawable`
的 tint 只能整体着色，改不到单块肌肉，所以没走 VectorDrawable。

两个入口共用一个绘制内核 `BodyPair`：

| 组件 | 输入 | 用途 |
| --- | --- | --- |
| `MuscleHeatmap` | `Map<String, Float>` 强度 0…1 | 动作预览、计划预览、统计页（主入口） |
| `MuscleBodyMap` | `Map<String, Int>` 档位 0…4 + `fatigue` | 打卡恢复期视图（黄→橙→红） |

```kotlin
val scores = MuscleScoring.forExercise(taxonomy, exercise)
MuscleHeatmap(scores, Modifier.fillMaxWidth().height(250.dp))

val planScores = MuscleScoring.forPlan(
    taxonomy,
    entries.map { MuscleScoring.PlannedExercise(it.exercise, it.sets) },
)
MuscleHeatmap(planScores, Modifier.height(260.dp))

MuscleBodyMap(mapOf("chest" to 4, "abs" to 3), Modifier.height(220.dp), fatigue = true)
```

强度 → 颜色在 5 个锚点（0 / .32 / .56 / .78 / 1.0）之间线性插值，所以 0.4 得到的是明显的
浅色（`#85bba6`）而不是中深色，和 1.0 的深色（`#11855c`）区分得开。

**尺寸约束**：`Canvas` 内部是 `Spacer`，只有拿到固定尺寸才撑得开。`BodyPair` 里两个画布都用
`weight(1f) + fillMaxHeight()`，并包了一层 `BoxWithConstraints`——调用方给了高度就用给定的，
只给宽度就按两个人形并排的比例（1.14）自算高度。不加这层，Row 传下去的 `minHeight = 0`
会让画布量成 0 高，界面表现为一块空白。

### 动作 → 肌群

`data/anatomy/MuscleTaxonomy` 把动作库里的四种写法都翻成人体图 slug：英文枚举（`pectorals`）、
中文副肌群（`胸肌`）、已是 slug（`chest`）、零星英文残留（`wrists`）。

规则（`MuscleScoring`）：

1. 主肌群 = `target` → 1.0；画不出来的（`cardiovascular system`）退到身体部位权重，
   身体部位也没有（有氧）就铺一层全身底色
2. 副肌群 = 每个 0.4，同块肌肉重复叠加，最后压回 0…1
3. 计划预览：`Σ(强度 × 组数)` 后再除以最大值归一化

2303 个动作实测：22 种 `target` 全部命中，39 种副肌群中文写法全部命中，**没有一个动作全灰**
（只有踝关节/足部/手部这类画不出来的会被跳过）。

---

## 跟练状态机

`WorkoutSessionViewModel` 里一个 `StateFlow<WorkoutSessionState>` 管全部：

```
IDLE ──start──> PREPARE(3s) ──> ACTIVE_SET ──> RESTING ──┐
                   ↑                                       │
                   └────────── 下一组 / 下一个动作 ◄────────┘
                                                    ACTIVE_SET ──> COMPLETED
```

- **计次（REPS）**：进入 `ACTIVE_SET` 不倒计时，界面给 `+ / −` 调实际次数，点「完成本组」才进休息。
- **计时（TIMED）**：`PREPARE` 3 秒（每秒一短音，结束时一长音）→ 自动倒计时 → 归零标记完成
  并进休息 → 休息归零自动进下一组，全程不用碰屏幕。
- 计时不靠 `delay(1000)` 累加，而是每阶段记一个 `SystemClock.elapsedRealtime()` 截止点，
  由 80ms 的 ticker 对比。界面卡顿或主线程繁忙都不会让倒计时走慢。
- **一组一落库**：每完成一组立刻往 `workout_set_logs` 写一行（同一次训练共用 `sessionId`），
  中途退出或进程被杀，做过的组不会丢。
- **换动作前的休息换个画面**：最后一行做完进休息时，主视觉提前切到下一个动作的
  演示图、名称与目标组次，标签写「下一个动作」；组间休息（同一动作的 3→4 组）仍显示当前动作。
  判定收在 state 的 `upcomingEntry` / `restingIntoNext` 里，UI 不再自己判断阶段 ——
  否则用户盯着刚练完的动作歇 90 秒，抬头才发现要换器械。
- 切平替动作后，本动作剩下的组都按平替走，打卡记的也是平替动作的 id ——
  这样结算热力图反映的是「实际练了什么」而不是「计划想练什么」。

### 前台服务与锁屏倒计时

通知栏的剩余时间来自 `WorkoutTimerBridge`：ViewModel 只写**截止时刻**，Service 只读，
反向只传三个按钮指令（+15 秒 / 跳过休息 / 结束训练）。刻意做成单向，避免两个计时源各自累加后走偏。

前台服务本身**不保证 CPU 不休眠**，所以倒计时阶段额外持 `PARTIAL_WAKE_LOCK`（30 分钟超时，
每阶段续期）。Manifest 里是 `foregroundServiceType="specialUse"`，上架时需要在 Play Console
单独说明用途。

### 崩溃恢复（检查点）

「每一组都落库」只解决了成绩不丢，没解决**位置**不丢 ——「练到第 3 个动作的第 2 组」只活在
ViewModel 内存里，进程一死就回到首页。

所以每进入一组就往 `app_meta` 写一份 JSON 检查点（`data/checkpoint/`）：

- 写什么：`sessionId`、练到哪个动作第几组、有没有切平替、已练时长、已完成组的副本；
  **不写**倒计时剩余 —— 恢复时一律从当前这组的开头重来（关机两小时后接着数 40 秒没有意义）。
- 什么时候写：`enterCurrentSet()` 里，也就是位置唯一会变的时刻；收尾时写一次 `null` 删除。
  写入走一个 `Channel(CONFLATED)` 单消费者队列，连点两组不会出现「后写的先落盘」。
- 怎么读：首页用 Flow 监听这个键，所以训练结束后横幅会自己消失，不需要重启 App。
- 存哪：`app_meta` 而不是新建表 —— 为一张只有一行、随时被覆盖的表写 Room 迁移不值当。
- 丢弃时只删检查点，不动数据库：已完成组仍在历史里，怎么补完由用户决定。

首页横幅点「继续训练」走独立路由 `resume_workout`（不能复用 `workout/{routineId}`，
否则 `LongType` 参数解析会炸）。

### 一组记什么：事实，不是结论

`workout_set_logs` 每行除了次数/秒数，还记四样东西 —— 共同点是「事后算不出来」：

| 列 | 记的是什么 | 为什么必须当时记 |
| --- | --- | --- |
| `restPlannedSec` | 这组之前那次休息的计划秒数 | 计划会被改，改完就不知道当时要求歇多久 |
| `restActualSec` | 实际歇了多少秒 | 提前结束（偏小）与「+15 秒」（偏大）都是信号 |
| `targetReps` / `targetDurationSec` | 当时那套计划的目标值快照 | 不 join `routine_exercises`：计划改了、删了就再也对不上 |

判定口径（「歇到一半算不算提前」）一律不落库，放在 `TrainingStatsMath` 和提示词里。
存一个 `skipped` 布尔看似省事，实际是把阈值焊死在数据里 —— 阈值以后一定要改。

两个容易写错的点：

- 实际休息在**离开休息时**结算（`advanceToNextSet`），不能在下一组完成时算 ——
  那样会把 3 秒准备和整组时长一起算成休息。
- `begin()` 与 `resume()` 都要 `resetRestTracking()`：上一场歇到一半退出，
  残留的 40 秒会挂到新一场的第一组上，记录里就出现「第一组前面歇了 40 秒」。

v8 之前的老记录四列是 0/0/null/null，统计用 `targetReps IS NULL` 把它们挡在达标率之外 ——
当成「没达标」会让升级后第一周的数字全线崩掉。

---

## AI 教练

- 端点用 OpenAI 兼容协议（`/chat/completions`），`HttpURLConnection` + 手写 SSE，零额外依赖。
- API Key 用 Android Keystore 里的 AES/GCM 密钥加密后存 SharedPreferences，不引 `security-crypto`。
- 上下文压缩：候选动作用 `id|名称|计次/计时|类型|难度` 一行一个，只给主攻流派 ± 最近 30 天练过的动作，
  上限 90 条（约 1.5k token）。不给英文名，减少一半体积；类型/难度是为了让模型知道
  「这个动作该歇多久、该留几次余力」，两三个字换掉一轮来回问。
- 计划用 ` ```json_routine ` 围栏里的 `{items:[{exerciseId,...}]}` 协议回传，解析后按 id 落回实体，
  查不到的丢弃并计数——模型幻觉出的动作不会进库。

### 提示词的四层

`CoachPrompts` 不是一坨文本，拆成四层，各有各的改动频率：

| 层 | 内容 | 什么时候动 | 位置 |
| --- | --- | --- | --- |
| 一 角色与行为 | 身份、说话方式、对话规则、安全边界 | 半年一次 | `layer1Role()` |
| 二 用户动态上下文 | 档案、器械、禁忌、统计、现有计划 | 每轮刷新 | `context()`，**单独一条 system** |
| 三 训练决策规则 | 筛选 / 量 / 均衡 / 强度 / 休息 / 进阶 / 恢复 / 阶段 / 改旧计划 / 读数据 / 安全细则 | 原则变了才动 | `layer3Decisions()` |
| 四 输出协议 | 正文格式、动作池与 ID、json schema、字段约束 | 客户端解析变了才动 | `layer4Protocol()` |

为什么分层而不是写成一段：一段 prompt 的实际下场是规则互相稀释 —— 想改「别用表格」
得翻过 800 字训练原则，想加「跑步怎么排」又不知道塞在哪里不打断原有逻辑。
分层之后每条规则有唯一归属，冲突时也有明确优先级：第四层管格式、第三层管判断、
第一层的安全边界压过两者。

第二层单独发一条 system 消息不只是省 token：稳定内容在前、变动内容在后，缓存命中率更高；
更重要的是模型不会把简报里的默认值当成「用户说的」（三态标记见下一节）。

#### 加一个训练类型，不动核心

`TrainingModules.kt` 是第三层的可插拔部分，已内置 6 个：跑步、HIIT、核心专项、
增肌、减脂、长期周期。每个模块两段：

- `core`：一句话纪律，**常驻**在 3.12 索引里（六个模块合计约 400 字）；
- `body`：详细规则，**命中关键词才注入**（每段 500~700 字）。

`TrainingModuleSelector.pick()` 按用户这句话的关键词命中数排序，最多取两个。
不做语义判断，也不让模型自己选（它每轮都会「全都要」）。实测：不带专项 6.0k 字符，
带两个专项 6.8k —— 问「明天练什么」不会白花专项的 token。

新增类型的门槛只有三条：有运动专项知识、有能说清的禁忌边界、有用户会说的关键词。
缺一条就不该建模块 —— 那说明它是所有训练通用的，该写进 3.x。
结构由 `CoachPromptsTest` 卡住：四层顺序、`decisions_layer_is_identical_with_or_without_modules`
（保证加模块真的不改核心）、id 唯一、正文以 `### <ID>` 开头、长度 200~1600 字。

### 用户档案与来源标记

档案存在 `AiPreferences.profile`（`@Serializable`，普通 SharedPreferences）。
拆分字段的唯一标准是「模型会不会用错」：

| 字段 | 默认 | 为什么要单独存 |
| --- | --- | --- |
| `goal` | 空 | — |
| `level` | 新手 | 自评水平，和训练年限不是一回事 |
| `experience` | 空 | 练了多久、有没有断过：练两年停半年的人该按重启排 |
| `daysPerWeek` / `sessionMinutes` | 3 / 40 | 排计划的量纲 |
| `trainingDays` | 空 | 具体周几，才能排「隔天练」还是「连练三天」 |
| `equipment` | 空 | 清单比散文好遵循；「无器械」是明确回答而非空白 |
| `limitations` | 空 | **伤病 / 疼痛 / 动作禁忌**，提示词里的红线 |
| `preferredFocus` | 空 | 只影响优先排谁，不因此砍掉其他部位 |
| `availableSpace` | 空 | 空间、噪音限制 |
| `notes` | 空 | 作息等杂项，不再承接伤病 |

伤病原来混在 `notes` 一段自由文本里，模型经常只抓走「有哑铃」而漏掉「右肩有旧伤」。
拆出来还不够 —— 它必须是**档案里第一眼看到的东西**，所以简报里单独一行、排在最前。

#### 三态：谁说的？

`AiProfile.confirmed: Set<String>` 记「用户明确填过的字段 key」（`ProfileField` 常量）。
渲染简报时按三态处理：

| 状态 | 判定 | 渲染 |
| --- | --- | --- |
| 用户明确填写 | `field in confirmed` 且有值 | 原值 |
| 系统默认 / 旧数据 | 有值但不在 `confirmed` | `值（未确认）` |
| 尚未填写 | 值为空 | `未填` |

不能用「值是否为空」代替：`level` / `daysPerWeek` / `sessionMinutes` 有默认值，永远非空。
不标来源的话，「水平 新手」会被模型当成用户自述 —— 一个练了三年的人因为没动过设置，
就被按新手排一整套，这比不填更糟。

写入统一走 `AiSettingsViewModel.updateProfile(field) { ... }`，它顺手把 `field` 加进 `confirmed`。
设置页另有一行「档案已填 N/11 项 · 关键项还缺 X」，简报里也有 `【档案缺口】` 点名 ——
缺口不点名，模型会自己扫一遍，然后漏掉。

### 训练统计：程序算数，模型解释

原始记录是几百条「某天某动作几组几次」。直接丢给模型有三个问题：算术不可靠（问「上周几组」
会给一个看起来合理的错数）、token 贵（30 天明细轻松上万）、口径不明（「训练日」算自然日还是场次，
每次都不一样）。所以 `data/ai/TrainingStats.kt` 负责算准，模型只负责解读和决策。

| 程序算（`TrainingStatsMath`，纯函数） | 模型判断 |
| --- | --- |
| 7 天 / 前 7 天 / 30 天的场次、训练日、总组数 | 这个量对他来说是不是突然加太多 |
| 按流派（推/拉/腿/核心/爆发心肺/有氧）的组数分布 | 推拉是否失衡、要不要补 |
| 每动作单场最好成绩的序列与趋势标签 | 该加量、维持还是减量 |
| 距上次训练几天 | 中断后怎么重启 |
| 每条流派最近一次是几天前（14 天内） | 今天还能不能练同一个部位 |
| 建议单次组数区间（算法在 `VolumePolicy`） | 这次排几个动作、每个几组 |
| 每动作达标 x/y 组、超额几组、平均实际休息 vs 计划休息 | 这套强度对这个人是偏轻还是偏重 |

最后一行是「练得轻不轻」唯一可量化的证据：做满了还提前起身（实际休息明显小于计划）= 偏轻；
一直做不满、休息还得往长了加 = 偏重或恢复不足。**「超额」比「达标」更有信息量** ——
计次动作的次数框默认就是目标次数，用户不主动往上调就只有「刚好达标」；
计时动作到点自动结束，不会出现超额。

口径都写在代码注释与单测里，因为它们会被模型当成事实引用：

- **训练日**按本地自然日去重（`TrainingStatsMath.dayKey`），一天练两次算一天。
- **组数**按动作的 `branch` 归到 6 条流派，和技能树、经验结算同一套分类。
- **趋势**取「每场最好单组」而非全部组：12/10/8/6 代表能力的是 12；
  且**必须连续两次同向变化**才判上升/下降，`12→12→9` 是持平。单次掉下来可能只是没睡好，
  判成疲劳会让教练一直让用户减量。
- 不足 3 个数据点直接标「次数太少，看不出趋势」，不给方向。
- **达标率**只数当时有计划目标的组（`targetReps IS NOT NULL`），少于 3 组的动作不列出。
  **休息**的分子分母都只取「这组前面确实排了休息」的组，否则第一组会把平均值拉低。

只查一次 `performancesBetween(since30, now)` 的聚合结果，7 天 / 前 7 天 / 30 天在内存里切 ——
三次查询换来三套容易写歪的 SQL。没复用 `RecoveryRepository.volume()`：它按 30 个肌肉 slug
聚合，粒度太细，教练要的是「推/拉/腿」这种能一句话说清的粗粒度。

提示词里对应 3.10「读懂训练数据」：先看统计再看档案、直接引用数字不要重算、
不因单次下降判疲劳、不凭次数推强度、没数据就明说、新量接着最近负荷走；
并明确「提前结束休息也可能只是赶时间或误触通知栏」，至少两次同向再动计划。

### 训练规则：能算的都算

提示词只能「请求」模型，不能「保证」。同一句「复合动作休息 90~180 秒」写着，模型十次里
还是有两次给深蹲配 30 秒休息。所以能算的规则全在 `data/ai/TrainingRules.kt` 里做成纯函数，
计划落库前由程序兜一遍，提示词只负责让它理解**为什么**。

| 规则 | 实现 | 程序兜什么 |
| --- | --- | --- |
| 动作分类 | `ExerciseClass.of` | 复合 / 孤立 / 静态 / 有氧，从部位 + 参与肌群 + 计次方式推 |
| 难度 | `Difficulty.of` | 优先技能树阶数，其次「复合=中、孤立=低」 |
| 休息 | `RestPolicy` | 抬下界（复合 ≥90s），总上限 240s；长休息不削 |
| 强度 | `RirPolicy` | 夹到 0~5，计时与有氧不填；新手默认留更多 |
| 训练量 | `VolumePolicy` | 按近期每场组数 ×0.8~1.25；新手封 16 组；停练 14 天打七折 |

**只抬下界不削上限**：歇 30 秒的深蹲第二组基本是废组，必须拦；但接近极限的单组真需要
3 分钟以上，硬压回 180 是另一种错。用户要求把天花板从 180 提到 240 也是这个理由。

**难度和 RIR 是两件事**：难度是动作属性（俯卧撑比上斜俯卧撑难），RIR 是这一组的目标
（同样的俯卧撑，今天留 3 次还是力竭）。前者决定排不排、排几个，后者决定练到多狠。
库里 2303 条动作**没有任何强度标注**，所以不新增 `intensity` 列编一套分数 —— 那会让人
以为它是量过的；分类和难度都是从已有字段推的近似值，只用来选休息区间，不参与成绩计算。

RIR 落在 `routine_exercises.targetRir`（v7），不是只写进提示词：训练页要显示
「留 2 次余力」，否则用户看到「10 次」还得自己猜是力竭还是留力，教练说过的强度就丢了。

### 安全：提示词之外还有一道程序

这是 App 和聊天机器人最实质的差别。机器人能说「不舒服就停」，但它不会在**落库前**
替有伤的人看一眼计划。所以安全做成两层：

第一层是提示词里的安全规则，分两处：第一层「安全边界」是命中就执行的底线（不用看数据、
不用权衡），第三层 3.11「安全规则（判断细则）」才展开怎么判断。两处都明确「优先于用户的要求」：
不做诊断、不把疼痛解释成酸痛、出现胸痛晕厥呼吸困难立即停练并就医、不鼓励忍痛完成、
不给新手排高风险动作、不因用户坚持而突破身体限制、关键信息缺失时先问。

其中单独一段是**四类不适的区分**，因为模型最常犯的错不是漏掉危险信号，而是把关节疼
解释成「正常的酸痛，继续」：

| 类型 | 特征 | 处理 |
| --- | --- | --- |
| 肌肉疲劳 | 练中发酸发胀，停几分钟缓解，位置在肌腹 | 可以继续，今天不加量 |
| DOMS | 练后 12~48 小时才明显，按压酸痛，活动开缓解 | 可以练，同部位减量或换部位 |
| 关节疼痛 | 关节缝、特定角度刺痛、响且疼、肿胀 | 停相关动作，建议就医 |
| 突发异常 | 胸痛、头晕、冷汗、心慌、呼吸困难 | 立即停止，就医 |

分不清时按更严重的那类处理，并直接问清「疼在肌肉还是关节？练的时候疼还是第二天疼？有没有肿？」

第二层是 `data/ai/PlanGuard.kt` 的体检，在计划卡片上以警告形式出现（**只警告不拦截**，
用户可能就是要练，或者伤早好了）：伤病关键词映射到 `bodyPartCn` 找出会牵动的动作、
总组数超建议上限、某条流派今天刚练过又排了 9 组以上。上限 3 条 —— 警告一多就变成装饰。

### 在原计划上调整

没有这一层，模型每轮都会兴致勃勃设计一套新方案，用户一个月换四套计划，哪个动作都没练够
三个月。所以简报里有 `【现有计划】`（最近 3 套，每套最多 6 条，带组次/休息/RIR），
提示词用 3.8「他现在处在哪个阶段」+ 3.9「在原计划上调整」要求它先判阶段再决定改什么：

| 阶段 | 判据 | 排法 |
| --- | --- | --- |
| 新手适应期 | 档案新手 / 成绩还不稳定 | 3~5 个动作、2~3 组、RIR 3 |
| 建立期 | 能稳定完成 3 组目标次数 | 4~6 个动作、3~4 组、RIR 2 |
| 进阶期 | 同一动作连着几周都能加量 | 4~5 组、更难变式、RIR 1 |
| 重启期 | 停练 2 周以上 | 上次的六到七成，全套 RIR 3 |

聊天里也能改现有计划：回复里给 `json_plan_update` + `targetRoutine`，解析出的草稿带上
`targetRoutineName`，卡片按钮变成「更新到『推日』」，导入时**覆盖那一套**而不是再建一套同名的
（`importPlan` 与 `applyProposal` 共用 `saveInto`）。匹配不上名字才退回新建。

### 全程 AI 教练（练完复盘）

开关 `AiPreferences.autoCoach` 默认关，关着时结算页不出现复盘入口，也就不会产生任何 token 消耗。
开启要走一次确认弹窗，把三件事说清楚：计划由 AI 调整、改动先给你看、每次约 2~3k token。

流程是**一次请求、三段界面**：

1. 结算页「让教练复盘这次训练」→ `review/{sessionId}`；
2. `CoachRepository.reviewSession()` 现拼一条 user 消息：本次每个动作「目标 vs 实际」、
   计划外的动作、用户写的一句话反馈。聊天历史一条都不带——复盘的上下文是这一场训练；
3. 模型按复盘模式回答，需要改计划时在最后附 ` ```json_plan_update `：

```json
{"targetRoutine":"推日 · 上肢推力","name":"推日 · 上肢推力","reason":"…","items":[…]}
```

`items` 是**改完之后的完整列表**，不是增量——这样协议只有一种形态，
省掉「按 id 打补丁」在模型侧的实现难度；差异由本地 `PlanDiff.compare` 算给用户看。

应用（`applyProposal`）：`targetRoutine` 按名字匹配现有计划（精确优先，包含匹配只在唯一命中时认），
匹配不到就当新建；改现有计划走 `RoutineDao.save` 重写条目，平替动作按 exerciseId 沿用旧的。
**不自动落库**：卡片先列 `+ / ~ / − / =` 四类改动，点「应用」才写。

坑与取舍：

- `PlanBlockParser` 必须把 update 块从正文里剥掉，否则流式输出时气泡里会闪出半截 JSON；
  分块靠围栏标签，标签缺失时再看内容里有没有 `"targetRoutine"`。
- 计划名是唯一的匹配键，所以应用时**不跟着模型改名**——改一次名，下次复盘就匹配不上了。
- 规则分家以后，第四层只管格式（id 合法、组次范围、器械边界、来源标记），
  训练质量在第三层的 3.2~3.7（量 / 均衡 / 强度 / 休息 / 进阶 / 恢复）。
  早先把它们混在「硬规则」里，结果是模型守住了格式，照样给新手排 6 个动作 24 组。

---

## 构建细节

### 签名

release 包用仓库根目录的 `keystore.properties` 签名（已 gitignore，模板见 `keystore.properties.example`）：

```properties
storeFile=keystore/fitgym-release.jks
storePassword=…
keyAlias=fitgym
keyPassword=…
```

- 没有这个文件也能编译（产出未签名 APK），方便别人 clone 下来验证代码。
- debug 包带 `.debug` 后缀，和 release 共存 —— 两者签名必然不同，不加后缀只能装一个卸一个。
- `keytool` 现在默认生成 **PKCS12**，一个库只有一个密码，JDK 会忽略 `-keypass`。

### R8

`release` 开了 `isMinifyEnabled` + `isShrinkResources`，dex 侧 26 MB → **14 MB**。
内置短片扩到 989 个之后成品是 **41 MB** —— 这 24 MB 是 mp4 本身，R8 碰不到
（`noCompress` 还要求它们保持不压缩），能省的只有代码与资源那部分。
规则见 `app/proguard-rules.pro`，重点是三处：

| 谁 | 为什么容易被裁掉 |
| --- | --- |
| Room 的 `*_Impl` / `@Entity` / `@Dao` | 靠类名和反射实例化 |
| kotlinx.serialization 的 `$$serializer` | 编译器生成，`Companion.serializer()` 是反射入口 |
| 枚举常量名 | `RoutineCategory` / `SkillBranch` 的 `name` 会写进数据库和备份 JSON |

崩溃堆栈靠 `app/build/outputs/mapping/release/mapping.txt` 反解：

```bash
retrace mapping.txt crash.txt      # Android SDK/cmdline-tools 里带这个脚本
```

### 发版流水线

打 `v` 开头的 tag 触发 [`.github/workflows/release.yml`](../.github/workflows/release.yml)：
跑单测 → 构建签名 APK → 上传到 Release（附 sha256 与 mapping）。

需要在 Settings → Secrets 配三项：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`
（`base64 -w 0 keystore/fitgym-release.jks` 得到第一项）。

只有三项不是漏了：PKCS12 只有一个密码，`keyPassword` 直接复用 store 密码。工作流会先裁掉
密码首尾空白（粘贴时几乎必然带上）再用 `keytool -list` 预校验，失败时直接点名是哪个 secret 出错。

CI 上仓库顺序和本地相反：runner 在海外，阿里云镜像会 404 或超时，所以 `settings.gradle.kts`
按 `CI` 环境变量切换成官方源优先。

本地出包：

```bash
tools/release.sh              # 只出包，落在 build/release/
tools/release.sh v1.0.0       # 出包 + gh release create
```
