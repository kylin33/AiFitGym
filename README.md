# FitGym

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-24-brightgreen.svg)](#环境要求)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF.svg)](gradle/libs.versions.toml)

纯原生 Android 健身 App：Kotlin + Jetpack Compose，**完全离线可用**。
内置 2303 个徒手与有氧动作，可自建训练计划，跟练时带倒计时与锁屏通知；
另有一个可选的 AI 教练，接你自己填的 OpenAI 兼容端点。

无账号、无埋点、无广告 SDK。所有训练数据只存在本机，可导出成一份 JSON 带走。

## 功能

- **动作库** — 2303 个动作，中英文名 + 演示图 + 目标肌群，支持模糊搜索与部位/器械筛选
  常用动作（技能树主干与内置模板）带**动态演示**，其余为单帧示意图
- **肌肉热力图** — 动作、整份计划、完成后的恢复状态都用同一套人体图着色
- **训练计划** — 三套内置模板，也可自建：组数 / 次数或秒数 / 组间休息 / 拖动排序 / 平替动作
- **跟练** — 计次与计时双模；3 秒准备提示音、自动进组、组间休息、屏幕常亮、
  锁屏后由前台服务继续倒数并在通知栏给「+15 秒 / 跳过休息 / 结束训练」
- **记录与恢复** — 每完成一组即时落库；训练到一半被杀进程，回到首页会出现
  「检测到未完成的训练」
- **AI 教练** — 流式对话，能按你的目标直接产出可导入的计划；Key 用 Android Keystore 加密
- **数据备份** — 全量导出/导入 JSON（计划、历史、技能进度），支持跨机迁移

## 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | **25**（`gradle/gradle-daemon-jvm.properties` 锁定的 toolchain；本机没有时 Gradle 会自己下载） |
| Android SDK | compileSdk 37.2，需装 Build-Tools 与 Platform 37 |
| Gradle | 不用装，wrapper 自带 9.8.2 |

## 构建

```bash
./gradlew :app:assembleDebug      # 调试包，applicationId 带 .debug 后缀
./gradlew :app:assembleRelease    # R8 + 签名，体积约 14 MB
./gradlew :app:testDebugUnitTest  # 单元测试
```

`assembleRelease` 读仓库根目录的 `keystore.properties` 签名；**没有这个文件也能构建**，
只是产出一个未签名的 APK，方便 clone 下来验证代码。模板见 `keystore.properties.example`。

debug 与 release 可以同时装在一台机器上（包名后缀不同），不必卸了装、装了卸。

## 项目结构

```
app/src/main/java/io/github/kylin33/aifitgym/
├── data/          Room 实体与 DAO、动作导入、肌群映射、检查点、备份、短片存储、AI 协议
├── service/       跟练倒计时的前台服务
├── audio/         提示音（SoundPool + 音频焦点闪避）
└── ui/            Compose 界面：动作库、计划编排、跟练、历史、我的、AI 教练

app/src/main/assets/    动作 JSON（1.7 MB）+ 演示图（10.6 MB）+ 动态短片（24 MB）+ 人体图几何
tools/                  动作包与人体图的生成脚本（需要上游仓库）
docs/ARCHITECTURE.md    设计取舍与实现细节
```

## 技术栈

| 用途 | 选型 |
| --- | --- |
| UI | Jetpack Compose + Material 3 |
| 导航 | Navigation Compose |
| 数据库 | Room + KSP |
| JSON | kotlinx.serialization |
| 图片 | Coil 3（直接读 `file:///android_asset/`） |
| 视频 | Media3 ExoPlayer（`asset:///` 与 `file://` 都吃，静音循环） |
| 依赖注入 | 手写 `AppContainer`，不上 Hilt |

## 素材来源与许可

代码以 **AGPL-3.0** 发布，见 [LICENSE](LICENSE)。

但**内置素材的授权条件不同**，务必先读 [NOTICE](NOTICE)：

- 动作数据、演示图与演示短片来自 [openGym](https://github.com/DuarteSantos8/openGym)
  （同为 AGPL-3.0），其中图片与短片的版权属于 **Gym visual**，上游仅获授权用于 openGym 本身。
  短片内置了训练类里常用的 989 个，其余在同仓库按需下载，两类是同一批素材、同样未放大。
  非商业 fork 可原样保留并附声明；**商用或上架应用商店需要自行取得授权**，或替换成自有/CC0 素材。
- 人体图几何数据为 **MuscleMap by Melih Colpan**（MIT）。

## 文档

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 数据表设计、热力图配色、状态机、检查点、构建与发版细节
- [PRIVACY.md](PRIVACY.md) — 数据存在哪、什么时候会离开这台手机
- [DISCLAIMER.md](DISCLAIMER.md) — 训练风险与 AI 生成内容的局限
- [CONTRIBUTING.md](CONTRIBUTING.md) — 提交规范与 CLA

## 贡献

欢迎 Issue 和 PR。提交代码前请先看 [CONTRIBUTING.md](CONTRIBUTING.md)：
本项目要求签署 CLA（贡献者许可协议），否则无法合并。
