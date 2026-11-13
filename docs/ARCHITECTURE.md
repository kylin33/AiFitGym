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
| `app_meta` | `AppMetaEntity` | 键值表：导入条数、模板版本、检查点、AI 对话 |

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

当前 `version = 2`。v1 → v2 把计划拆成 `routines` + `routine_exercises` 并补了计时/平替
字段，当时两张计划表都是空的（还没有编辑入口），所以直接走 `fallbackToDestructiveMigration`：
升级后动作库由 `AppContainer.bootstrap()` 自动重灌，内置模板由 `PresetRoutineSeeder` 补上。
导出的 schema 在 `app/schemas/`，改表结构时别忘了一起提交。

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

### 内置计划模板

`data/seeding/RoutinePresets.kt` 里三套，启动时由 `PresetRoutineSeeder` 幂等灌入：

| 模板 | 类型 | 内容 |
| --- | --- | --- |
| 囚徒健身 · 基础推力日 | CALISTHENICS | 上斜俯卧撑 3×8 / 俯卧撑 4×10 / 派克俯卧撑 3×8 / 反向臂屈伸 3×10 / 平板支撑 3×45s |
| 囚徒健身 · 基础拉力日 | CALISTHENICS | 反向划船 4×8 / 引体向上 4×5 / 屈膝反向划船 3×10 / 悬垂空心 3×25s |
| Tabata 燃脂间歇 | HIIT_CARDIO | 波比 / 开合跳 / 高抬腿跑 / 登山者，各 8×20s，歇 10s |

灌入前用 `ExerciseDao.allIds()` 校验动作是否存在，缺动作的条目跳过、整条都缺的计划跳过，
所以换动作包也不会灌出空计划。去重靠 `app_meta.preset_routines_version` 而不是「数一下有几个
内置计划」—— 后者会让用户删掉的模板每次启动又回来。

---

## 动作数据

`app/src/main/assets/`

| 内容 | 说明 |
| --- | --- |
| `exercises.json` | 2303 个动作，含中英文名、部位、目标肌群、副肌群、器械、步骤（1.7 MB） |
| `gifs/<id>.webp` | 与动作一一对应的演示图，2303 张（10.6 MB） |
| `taxonomy.json` | 部位 / 分类枚举及计数，调试参考用 |
| `anatomy/` | 人体热力图资源（见下节） |

筛选口径：`equipment == "body weight"`（徒手）**或** `category == "cardio"`（有氧）。
数据源实际用词是 `"body weight"`（带空格），分类里徒手动作多为 `calisthenics`。

### 重新生成

```bash
python tools/build_catalogue.py --catalogue <openGym 仓库>/catalogue
```

遍历 `catalogue/exercises/*.json`，跳过只剩 2 个字段的占位文件，合并 `catalogue/i18n/zh.json`
的中文名与中文步骤，复制对应 WebP 到 `assets/gifs/`，最后打印数量、体积与中英文覆盖率。
常用参数：`--keep-without-media`（缺图也保留）、`--limit N`（只导前 N 个）、
`--i18n zh-TW`（繁中）。

### 上游仓库

`catalogue/` 实际有 6554 个动作文件（其中 922 个占位），媒体是 `media/still/<id>.webp`（静态图）
与 `media/clip/<id>.mp4`（动作短片），**没有 GIF**。本项目只打包 WebP；若要播放短片，
可引入 Media3 按需读 mp4。

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

---

## AI 教练

- 端点用 OpenAI 兼容协议（`/chat/completions`），`HttpURLConnection` + 手写 SSE，零额外依赖。
- API Key 用 Android Keystore 里的 AES/GCM 密钥加密后存 SharedPreferences，不引 `security-crypto`。
- 上下文压缩：候选动作用 `id|名称|计次/计时` 一行一个，只给主攻流派 ± 最近 30 天练过的动作，
  上限 90 条（约 1.2k token）。不给英文名，减少一半体积。
- 计划用 ` ```json_routine ` 围栏里的 `{items:[{exerciseId,...}]}` 协议回传，解析后按 id 落回实体，
  查不到的丢弃并计数——模型幻觉出的动作不会进库。

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

`release` 开了 `isMinifyEnabled` + `isShrinkResources`，26 MB → **14 MB**。
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
