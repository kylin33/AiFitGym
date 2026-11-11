# FitGym · 徒手 + 有氧健身 App

Kotlin + Jetpack Compose 纯原生 Android App。动作数据与配图源自开源动作库
[openGym](https://github.com/DuarteSantos8/openGym)（catalogue 目录）。

> 本项目以 **GNU AGPL-3.0** 发布（见 [LICENSE](LICENSE)）。内置动作数据与演示图属于
> 第三方素材，**授权条件与此不同**，详见 [NOTICE](NOTICE)。打算上架或商用的，请先读「许可证」一节。

## 当前进度

- [x] 动作库筛选脚本 + 资源打包进 `assets/`
- [x] Room 本地库（动作 / 训练计划 / 打卡记录）+ 首启 `RoomDatabase.Callback.onCreate` 自动导入
- [x] 动作列表（LazyColumn，中文名 + 配图 + 主副肌群标签）与详情页
- [x] Navigation Compose 路由骨架
- [x] 人体肌肉热力图 SVG 路径与配色算法提取
- [x] `MuscleHeatmap` 组件（0…1 强度版）+ 动作详情抽屉（演示图 + 热力图）
- [x] 训练计划表（`routines` / `routine_exercises`）+ 三套内置模板
- [x] 计划列表 / 编排页（组数·次数·秒数·休息·排序·平替）/ 动作选择器
- [x] 计划删除：长按卡片 → 底部操作面板 → 二次确认 → 底部提示条可撤销
- [x] 全屏跟练 + 双模计时器（计次手动确认 / 计时自动流转），含 3 秒准备音、组间休息、平替切换、训练结算
- [ ] 训练历史页与肌肉恢复期（后续）

## 内置动作包

`app/src/main/assets/`

| 内容 | 说明 |
| --- | --- |
| `exercises.json` | 2303 个动作，含中英文名、部位、目标肌群、副肌群、器械、步骤（1.7 MB） |
| `gifs/<id>.webp` | 与动作一一对应的演示图，2303 张（10.6 MB） |
| `taxonomy.json` | 部位 / 分类枚举及计数，调试参考用 |
| `anatomy/` | 人体肌肉热力图资源（见下文） |

筛选口径：`equipment == "body weight"`（徒手）**或** `category == "cardio"`（有氧）。
数据源实际用词是 `"body weight"`（带空格），分类里徒手动作多为 `calisthenics`。

### 重新生成

```bash
python tools/build_catalogue.py --catalogue <openGym 仓库>/catalogue
```

脚本会遍历 `catalogue/exercises/*.json`，跳过仓库里只剩 2 个字段的占位文件，
合并 `catalogue/i18n/zh.json` 的中文名与中文步骤，复制对应 WebP 到 `assets/gifs/`，
最后打印数量、体积与中英文覆盖率。常用参数：

- `--keep-without-media`：缺图的动作也保留
- `--limit N`：只导前 N 个，调试用
- `--i18n zh-TW`：换繁体中文包

## 技术栈

| 用途 | 选型 |
| --- | --- |
| UI | Jetpack Compose + Material 3（BOM 2026.09.00） |
| 导航 | Navigation Compose 2.10.2 |
| 数据库 | Room 2.8.5 + KSP 2.3.13 |
| JSON | kotlinx.serialization 1.12.0（`ignoreUnknownKeys`，脚本多吐字段不会崩） |
| 图片 | Coil 3.6.3（直接吃 `file:///android_asset/...`，自带内存/磁盘缓存与降采样） |
| 依赖注入 | 手写 `AppContainer`，不上 Hilt |

## 数据层

### 四张业务表

| 表 | 实体 | 说明 |
| --- | --- | --- |
| `exercises` | `ExerciseEntity` | 内置动作库，主键是上游 id（字符串），带 `searchKey` 供 LIKE 模糊搜索 |
| `routines` | `RoutineEntity` | 计划主表：`routineId` / `name` / `description` / `category` / `presetKey` |
| `routine_exercises` | `RoutineExerciseEntity` | 计划条目：`orderIndex` 排序，`targetSets` / `targetReps` / `targetDurationSec` / `restDurationSec` / `backupExerciseId`，随计划级联删除 |
| `workout_logs` | `WorkoutLogEntity` | 按「组」记录的打卡历史，`batchId` 把同一次训练串起来 |
| `app_meta` | `AppMetaEntity` | 键值表，记录导入条数、时间与模板版本 |

计划条目里 **计次和计时二选一**：`targetReps` 有值是计次动作（俯卧撑 12 次），
`targetDurationSec` 有值是计时动作（平板支撑 45 秒）。两个都为空时按计次 10 兜底。
`isTimed` 是计算属性，不占列。

三个刻意的取舍：

- **`exerciseId` 不设外键**。动作库是 assets 里的可再生数据，重建库时会整表替换，
  外键级联会把用户的计划条目和历史记录一起删掉。只有 `routineId → routines` 用 CASCADE。
- **`workout_logs` 按组存**，不是按次存。后面算肌肉热力图/恢复期时可以直接
  `GROUP BY exerciseId` 聚合训练量，不用再拆表；`WorkoutLogDao.volumeSince()` 已经预留了这个查询。
- **保存计划用 `@Update` 而不是 `REPLACE`**。SQLite 的 REPLACE 是「删了再插」，会触发外键
  CASCADE 把条目一起删掉；`RoutineDao.save()` 在事务里按 `orderIndex` 重排后整批重写。

### 数据库版本

当前 `version = 2`。v1 → v2 把计划拆成 `routines` + `routine_exercises` 并补了计时/平替字段，
当时两张计划表都是空的（还没有编辑入口），所以直接走 `fallbackToDestructiveMigration`：
升级后动作库由 `AppContainer.bootstrap()` 自动重灌，内置模板由 `PresetRoutineSeeder` 补上。

### 内置计划模板

`data/seeding/RoutinePresets.kt` 里有三套，启动时由 `PresetRoutineSeeder` 幂等灌入：

| 模板 | 类型 | 内容 |
| --- | --- | --- |
| 囚徒健身 · 基础推力日 | CALISTHENICS | 上斜俯卧撑 3×8 / 俯卧撑 4×10 / 派克俯卧撑 3×8 / 反向臂屈伸 3×10 / 前平板 3×45s |
| 囚徒健身 · 基础拉力日 | CALISTHENICS | 反向划船 4×8 / 引体向上 4×5 / 屈膝反向划船 3×10 / 悬垂空心 3×25s |
| Tabata 燃脂间歇 | HIIT_CARDIO | 波比 / 开合跳 / 高抬腿跑 / 登山者，各 8×20s，歇 10s |

灌入前会用 `ExerciseDao.allIds()` 校验动作是否存在，缺动作的条目跳过、整条都缺的计划跳过，
所以换动作包也不会灌出空计划。去重靠 `app_meta.preset_routines_version` 而不是「数一下有几个内置计划」——
后者会让用户删掉的模板每次启动又回来。

### 首启导入

入口在 `FitGymDatabase.create()`：

```kotlin
val bootstrap = object : Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        // onCreate 在建库的写事务里同步回调，只把任务丢给 IO 协程就立刻返回
        scope.launch { onFirstCreate(database) }
    }
}
```

`onFirstCreate` 实际是 `CatalogueSeeder.importInto(db)`：

1. `CatalogReader.readEntities()` 在 IO 线程读 `assets/exercises.json`，
   kotlinx.serialization 反序列化后映射成 `ExerciseEntity`（每 200 条回调一次进度）；
2. `ExerciseDao.replaceAll()` 在**单个事务**内分块写入，中途被杀进程不会留下半套数据；
3. 成功后写 `app_meta`（条数 + 时间戳），失败则 `ImportState.Failed`，UI 可重试。

设计细节：

- `onCreate` 只在数据库文件第一次创建时触发，所以二次启动不会再导入；
  `AppContainer.bootstrap()` 额外做一次幂等检查（`count() > 0` 直接返回），
  同时兜住「建库后导入失败」的情况。
- Seeder 不持有数据库实例（数据库回调要反过来调 Seeder，会成环），改为方法传库。
- 并发保护：`Mutex` + 条数检查，建库回调和启动兜底同时进来也只导一次。

实测（Android 16 模拟器，debug 包）：**2303 条 / 总 2.6s（解析 1.2s + 写库 0.3s）**，
二次启动直接读缓存库，无导入。首次会看到进度页，之后秒进。

## 人体肌肉热力图资源

`app/src/main/assets/anatomy/`

| 内容 | 说明 |
| --- | --- |
| `body-paths.json` | male/female × front/back 四个视图的 viewBox 与逐肌肉 path data，共 333 条（92 KB） |
| `muscles.json` | 18 块肌肉的 slug / 中英文名 / 疲劳半衰期、50 条别名映射、体部位回退权重、色阶与疲劳参数 |

来源：openGym `frontend/src/lib/body-paths.js`，原始图形是 **MuscleMap by Melih Colpan（MIT）**，
上游已注明「Generated — do not hand-edit」。几何数据只做了结构归一化，path 字符串原样保留。

### 重新提取

```bash
python tools/extract_anatomy.py --frontend <openGym 仓库>/frontend
```

脚本会解析 `body-paths.js`（本来就是 JSON 字面量）、从 `muscles.js` 抠出 `MUSCLES` / `INERT` /
`ALIAS` / `BY_BODYPART`、从 `recovery.js` 抠出每块肌肉的疲劳半衰期，并生成一份
`tools/preview/anatomy-preview.html` 供肉眼校验配色。

### 配色算法（照搬上游）

- 未训练底色 = `label 11%` 叠在 `surface` 上；躯干轮廓 = `label 18%`
- 分级 l1…l4 = 底色与 accent 按 **32% / 56% / 78% / 100%** 混合
- 相对分级（默认）：`level = ceil(该肌肉负荷 / 最大负荷 × 4)`，0 负荷为 l0
- 固定分级：传入 `{at, level, exclusive}` 阈值表，恢复期视图用它保持档位语义稳定
- 恢复期配色：l1 黄 32%、l2 黄、l3 橙、l4 红；疲劳度 `<0.25` 已恢复、`0.25–0.5` 恢复中、`>0.5` 仍酸痛
- 疲劳度累计：按肌肉半衰期（手臂/核心 24h、躯干 30h、腿与下背 48h）指数衰减后走 `1 - exp(-v)` 饱和曲线

### Compose 渲染

几何用 `PathParser` 把 path data 解析成 `Path`，在 `Canvas` 上逐块上色——`VectorDrawable`
的 tint 只能整体着色，改不到单块肌肉，所以没走 VectorDrawable。

两个入口，共用同一个绘制内核 `BodyPair`：

| 组件 | 输入 | 用途 |
| --- | --- | --- |
| `MuscleHeatmap` | `Map<String, Float>` 强度 0…1 | 动作预览、计划预览、统计页（主入口） |
| `MuscleBodyMap` | `Map<String, Int>` 档位 0…4 + `fatigue` | 打卡恢复期视图（黄→橙→红） |

```kotlin
// 单个动作：主肌群 1.0（深色），副肌群 0.4（浅色）
val scores = MuscleScoring.forExercise(taxonomy, exercise)
MuscleHeatmap(scores, Modifier.fillMaxWidth().height(250.dp))

// 整份计划：按组数累加后归一化到 0…1，最吃力的那块永远最深
val planScores = MuscleScoring.forPlan(
    taxonomy,
    entries.map { MuscleScoring.PlannedExercise(it.exercise, it.sets) },
)
MuscleHeatmap(planScores, Modifier.height(260.dp))

// 恢复期（档位语义，颜色换成黄橙红）
MuscleBodyMap(mapOf("chest" to 4, "abs" to 3), Modifier.height(220.dp), fatigue = true)
```

强度 → 颜色在 5 个档位锚点（0 / .32 / .56 / .78 / 1.0）之间线性插值，所以 0.4 得到的
是明显的浅色（`#85bba6`）而不是中深色，和 1.0 的深色（`#11855c`）区分得开。

**尺寸约束**：`Canvas` 内部是 `Spacer`，只有拿到固定尺寸才撑得开。`BodyPair` 里两个画布都用
`weight(1f) + fillMaxHeight()`，并包了一层 `BoxWithConstraints`——调用方给了高度就用给定的，
只给宽度就按两个人形并排的比例（1.14）自算高度。不加这层，Row 传下去的 `minHeight = 0`
会让画布量成 0 高，界面表现为一块空白。

### 动作 → 肌群

`data/anatomy/MuscleTaxonomy` 把动作库里的四种写法都翻成人体图 slug：
英文枚举（`pectorals` / `lats`）、中文副肌群（`胸肌` / `下背`）、已是 slug（`chest`）、
零星英文残留（`wrists`）。

规则（`MuscleScoring`）：

1. 主肌群 = `target` → 1.0；画不出来的（`cardiovascular system`）退到身体部位权重，
   身体部位也没有（有氧）就铺一层全身底色
2. 副肌群 = 每个 0.4，同块肌肉重复叠加，最后压回 0…1
3. 计划预览：`Σ(强度 × 组数)` 后再除以最大值归一化

2303 个动作实测：22 种 `target` 全部命中，39 种副肌群中文写法全部命中，
**没有一个动作出现全灰**（只有踝关节/足部/手部这类画不出来的会被跳过）。

## 跟练状态机

`WorkoutSessionViewModel` 里一个 `StateFlow<WorkoutSessionState>` 管全部：

```
IDLE ──start──> PREPARE(3s) ──> ACTIVE_SET ──> RESTING ──┐
                   ↑                                       │
                   └────────── 下一组 / 下一个动作 ◄────────┘
                                                    ACTIVE_SET ──> COMPLETED
```

- **计次（REPS）**：进入 `ACTIVE_SET` 不倒计时，界面给 `+ / −` 调实际次数，点「完成本组」才进休息。
- **计时（TIMED）**：`PREPARE` 3 秒（每秒一短音，结束时一长音）→ `ACTIVE_SET` 自动倒计时 →
  归零自动标记完成并进休息 → 休息归零自动进下一组，全程不用碰屏幕。
- 计时不靠 `delay(1000)` 累加，而是每阶段记一个 `SystemClock.elapsedRealtime()` 截止点，
  由 80ms 的 ticker 对比。界面卡顿或主线程繁忙都不会让倒计时走慢。
- **一组一落库**：每完成一组立刻写一条 `workout_logs`（同一次训练共用 `batchId`），
  中途退出或进程被杀，做过的组不会丢。
- 切平替动作后，本动作剩下的组都按平替走，打卡记的也是平替动作的 id ——
  这样结算热力图反映的是「实际练了什么」而不是「计划想练什么」。

## 关于上游仓库

`catalogue/` 实际有 6554 个动作文件（其中 922 个是占位文件），媒体资源是
`media/still/<id>.webp`（静态图）与 `media/clip/<id>.mp4`（动作短片），
**没有 GIF**。本次只打包 WebP；后续若要播放动作短片，可引入 Media3 按需读取 mp4。

## 工程结构

```
io.github.kylin33.aifitgym
├── FitGymApp.kt / MainActivity.kt
├── di/AppContainer.kt              手动依赖容器（DB、Seeder、Repository 单例）
├── data/
│   ├── catalog/
│   │   ├── ExerciseCatalog.kt      @Serializable 的 JSON 模型
│   │   └── CatalogReader.kt        assets 读取 + DTO→Entity 映射（含 searchKey）
│   ├── local/                      Room：5 实体 + 4 DAO + Database + 类型转换
│   ├── anatomy/
│   │   ├── MuscleTaxonomy.kt       肌群词表（英文/中文/slug 三向映射 + 部位回退权重）
│   │   └── MuscleScoring.kt        动作→肌群强度、计划→归一化负荷
│   ├── seeding/
│   │   ├── CatalogueSeeder.kt      动作库导入流程与 ImportState
│   │   ├── RoutinePresets.kt       三套内置计划模板（真实动作 ID）
│   │   └── PresetRoutineSeeder.kt  模板幂等灌入（校验动作存在 + 版本标记）
│   └── repo/
│       ├── ExerciseRepository.kt   动作查询封装
│       ├── RoutineRepository.kt    计划读取与保存
│       └── WorkoutRepository.kt    计划展开成跟练序列 + 单组打卡落库
├── audio/BeepPlayer.kt             ToneGenerator 现场合成提示音（不占 assets）
└── ui/
    ├── FitGymRoot.kt               ImportState 门控 + NavHost
    ├── LibraryViewModel.kt         动作库列表状态 + 搜索
    ├── workout/
    │   ├── WorkoutSessionViewModel.kt  跟练状态机（单一 StateFlow）
    │   └── ActiveWorkoutScreen.kt      全屏跟练 UI + 结算页
    ├── routines/
    │   ├── RoutineListScreen.kt    计划卡片列表（首页，长按弹删除面板）
    │   ├── RoutineEditorScreen.kt  编排页：名称/分类/条目组次/排序/平替
    │   └── *ViewModel.kt           列表与草稿状态机
    ├── picker/
    │   ├── ExercisePickerScreen.kt 复用动作库列表的选择器（多选 / 单选平替）
    │   └── ExercisePickerViewModel.kt
    ├── library/
    │   ├── LibraryScreen.kt        动作库（搜索 + 点击弹抽屉）
    │   ├── ExerciseRow.kt          列表行（动作库与选择器共用）
    │   └── ExercisePreviewSheet.kt ModalBottomSheet：演示图 + 肌肉热力图 + 图例
    ├── detail/DetailScreen.kt      大图 + 目标肌群 + 步骤
    ├── legal/
    │   ├── LegalContent.kt         隐私政策与免责声明正文（App 与仓库文档的唯一来源）
    │   └── LegalScreen.kt          长文阅读页（无卡片、纯排版）
    ├── components/
    │   ├── ExerciseImage.kt        Coil 加载 assets 图片
    │   ├── MuscleBodyMap.kt        人体几何加载 + BodyPair 绘制内核（档位版）
    │   ├── MuscleHeatmap.kt        MuscleHeatmap（强度版）+ 颜色插值
    │   ├── CountdownRing.kt        倒计时圆环（准备 / 计时 / 休息共用）
    │   └── StateViews.kt           等待 / 错误 / 空结果页
    └── theme/                      品牌色（跑道绿 + 能量橙）
```

## 验收记录

模拟器（Android 16，1080×2400）实机截图见 `tools/preview/`：

| 截图 | 验证点 |
| --- | --- |
| `verify-list.png` | 首启导入完成 2303 条 / 2589 ms；列表刷出中文名、英文名、配图、主动肌（实心）+ 辅助肌（浅色）标签 |
| `verify-search.png` | 搜索 `squat` 命中 131 个，排序与筛选走 DAO 的 `searchKey LIKE` |
| `verify-detail.png` | Navigation Compose 跳转详情，大图 / 标签 / 目标肌群 / 步骤齐全 |
| `verify-relaunch.png` | 二次启动显示「本地库已就绪 · 2303 个动作」，不再重导 |

## 构建

```bash
./gradlew :app:assembleDebug
```

编译环境沿用 `D:\work\AndroidDev` 约定：AGP 9.5.0 / Kotlin 2.4.21 / Compose BOM 2026.09.00 /
Room 2.8.5（KSP 2.3.13）/ compileSdk 37.2 / minSdk 24。



## 隐私与免责

App 内「我的 → 通用」下有完整的两页，仓库里是同一份文本：

- [PRIVACY.md](PRIVACY.md) — 数据存在哪、什么时候会离开这台手机、申请了哪些权限。
- [DISCLAIMER.md](DISCLAIMER.md) — 训练风险、AI 生成内容的局限、责任范围。

要点：没有账号、没有埋点、没有广告 SDK；训练数据只在本机，唯一会联网的地方是
**你自己配置**的 AI 端点（默认什么也不发）。API Key 用 Android Keystore 绑定的
AES-GCM 密钥加密，备份文件里不含它。

`baseUrl` 填 `http://` 时会怎样：App 允许明文（`network_security_config.xml`），
因为用户可能填局域网 Ollama；但设置页会按目标地址分两档提示——局域网一句提示，
公网地址红色警告，讲清楚 Key 和对话正文会明文过网。

## 许可证

本项目以 **GNU Affero General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)。
内置动作数据、演示图、人体图几何属于第三方内容，授权条件另见 [NOTICE](NOTICE)。

| 你可以 | 你必须 |
| --- | --- |
| 自由使用、修改、再分发 | 分发时（**包括把 APK 传到应用商店**）提供完整对应源码 |
| 收费——AGPL 不禁止商业行为 | 修改版继续以 AGPL 发布，不能转成闭源 |
| 部署成网络服务 | 让通过网络使用它的用户也能拿到源码（第 13 条） |

选 AGPL 而不是 MIT / Apache-2.0 的原因很实际：动作数据来自同为 AGPL-3.0 的 openGym。
上游是 copyleft，本项目跟着用同一个许可证，就不必去逐条论证「哪部分算数据、哪部分算代码、
引用多少才构成衍生」——那是律师的活儿。代价是这份代码从此不能做成闭源商业版。

### 想上架的话

- **Google Play 允许 AGPL**，但商店描述里要给出源码仓库链接，且该链接必须对应实际
  分发的那个版本。发版时打个 `git tag`，把链接指到 tag 而不是 `main` 最稳妥。
- **Apple App Store 与 AGPL 存在冲突**：App Store 的使用条款附加了 GPL 第 6 条明确
  禁止的限制（安装设备数量、DRM 等），历史上 VLC 就因此被下架过。要上 iOS，这条路
  基本走不通。
- **演示图的版权不在本项目手上**，与 AGPL 无关：`app/src/main/assets/gifs/` 里的
  2303 张图属于 **Gym visual**，上游只授权给 openGym 使用（非商业 fork 可原样保留并
  附声明）。商用或以本 App 上架，需要自行向 [gymvisual.com](https://gymvisual.com/)
  取得授权，或替换成自有 / CC0 素材。
- **上架前还差的四件事**：
  1. 隐私政策 URL：把 [PRIVACY.md](PRIVACY.md) 发布成网页（GitHub Pages 或仓库的
     raw 链接），填进 Play Console。审核时会真的点开看，仓库首页不算。
  2. `FOREGROUND_SERVICE_SPECIAL_USE`：Console 的「敏感权限声明」里要单独写清楚
     这是组间倒计时，并给出演示视频。
  3. Data safety 表单：按 PRIVACY.md 填「不收集、不共享」；AI 请求是用户主动发起、
     发往用户自选服务商，属于「用户自己的服务商」而非开发者收集。
  4. release 签名 + `isMinifyEnabled = true`（现在 debug 能跑，release 还没配签名）。

### 如果以后想改成闭源

AGPL 不允许单方面改。只要有过外部贡献者，再想换成 MIT 或商业许可证就需要**每一位**
贡献者同意。真留了这个念头，从一开始就在 `CONTRIBUTING.md` 里要求签署 CLA（贡献者
许可协议，授予你重新授权的权利）——事后补是补不回来的。