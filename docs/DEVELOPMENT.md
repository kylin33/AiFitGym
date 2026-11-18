# 开发与构建

这份文档面向要改代码、编译安装包的人。产品的设计取舍在
[ARCHITECTURE.md](ARCHITECTURE.md)，这里只讲「怎么跑起来」。

## 环境要求

| 项 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **25** | `gradle/gradle-daemon-jvm.properties` 里锁定了 toolchain，本机没有时 Gradle 会自己下载 |
| Android SDK | compileSdk **37.2** | 需装 Platform 37 与对应 Build-Tools |
| Gradle | 9.8.2 | 不用单独装，wrapper 自带 |
| 设备 / 模拟器 | Android 7.0（API 24）以上 | minSdk 24；锁屏倒计时依赖前台服务，注意别把通知权限关掉 |

## 构建

```bash
./gradlew :app:assembleDebug      # 调试包，applicationId 带 .debug 后缀
./gradlew :app:assembleRelease    # R8 + 签名，约 39 MB
./gradlew :app:testDebugUnitTest  # 69 个纯 JVM 单测
```

`assembleRelease` 读仓库根目录的 `keystore.properties` 签名；**没有这个文件也能构建**，
只是产出一个未签名的 APK，方便 clone 下来先验证代码。模板见 `keystore.properties.example`。

debug 与 release 可以同时装在一台机器上（包名后缀不同），不必卸了装、装了卸。

## 项目结构

```
app/src/main/java/io/github/kylin33/aifitgym/
├── data/          Room 实体与 DAO、动作导入、肌群映射、检查点、备份、短片存储
│   ├── ai/        提示词、计划解析、训练统计与规则、安全校验、AI 协议
│   ├── skill/     技能树：六条流派 × 八阶，进度与经验结算
│   └── seeding/   首启导入动作库（计划表留空，由用户或 AI 排）
├── service/       跟练倒计时的前台服务
├── audio/         提示音（SoundPool + 音频焦点闪避）
└── ui/            Compose 界面：动作库、计划编排、跟练、历史、恢复、技能树、AI 教练

app/src/main/assets/    动作 JSON（2.5 MB）+ 演示图（16 MB）+ 动态短片（27 MB）+ 人体图几何
tools/                  动作包、人体图与短片的生成脚本
docs/                   架构文档与界面截图
```

## 技术栈

| 用途 | 选型 |
| --- | --- |
| UI | Jetpack Compose + Material 3（BOM 2026.09） |
| 导航 | Navigation Compose |
| 数据库 | Room 2.8 + KSP，导出 schema 到 `app/schemas/` |
| JSON | kotlinx.serialization |
| 图片 | Coil 3（直接读 `file:///android_asset/`） |
| 视频 | Media3 ExoPlayer（`asset:///` 与 `file://` 都吃，静音循环） |
| 依赖注入 | 手写 `AppContainer`，不上 Hilt |
| 网络 | `HttpURLConnection` + 手写 SSE，零额外依赖 |

## 测试

全部是纯 JVM 单测，不依赖设备：

```bash
./gradlew :app:testDebugUnitTest
```

覆盖的是那些「错一次就是静默坏数据」的地方 —— 模型输出的 JSON 解析与容错、
训练统计的口径（训练日怎么去重、趋势怎么判）、训练规则与安全校验的边界、
提示词的分层结构（加了专项模块后核心层必须逐字不变）。

界面没有自动化测试，改动 UI 后请自己跑一遍：动作库搜索 → 计划编排 → 跟练 →
组间休息 → 结束 → 恢复热力图。

## CI 与发版

`.github/workflows/release.yml` 在推 `v*` tag 时构建签名 APK 并建 Release：

```bash
git tag -a v1.0.2 -m "1.0.2"
git push origin v1.0.2
```

需要在仓库的 Settings → Secrets and variables → Actions 配三个 secret：

| secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | `keystore/fitgym-release.jks` 的 base64（Windows 上 `base64 -w 0`，别换行） |
| `KEYSTORE_PASSWORD` | storePassword |
| `KEY_ALIAS` | keyAlias |

没有 `KEY_PASSWORD`：这个 keystore 是 PKCS12，整库一个密码，JDK 会忽略 `-keypass`。
少配一个「验证不了的 secret」，就少一个打包时才爆的失败点。

## 素材生成

`tools/` 下的脚本把上游 openGym 的数据整理成本项目的动作包、演示图与短片：

| 脚本 | 作用 | 是否需要联网 |
| --- | --- | --- |
| `build_catalogue.py` | 从上游仓库生成 `exercises.json` | 需要先克隆上游仓库 |
| `tag_exercises.py` | 给动作打流派、阶数、达标口径 | 否 |
| `fetch_clips.py` | 拉取演示短片（默认内置的 989 个，`--all` 全量） | 是 |
| `extract_anatomy.py` | 生成人体图几何数据 | 否 |
| `make_beeps.py` | 生成 3 秒准备提示音 | 否 |

短片是上游原样的 **180×180、约 9 秒、静音 H.264**，中位 23 KB，不做二次转码；
内置 989 个（约 24 MB），其余 1314 个在 App 里按需下载。详细取舍见
[ARCHITECTURE.md](ARCHITECTURE.md) 的「动态演示（短片）」。

素材的授权条件与代码不同，改素材或商用之前请先读 [NOTICE](../NOTICE)。

## 常见问题

**Q：构建时提示 `AccessDeniedException: ...gradle-9.8.2-bin.zip.lck`**
多半是另一个 Gradle 进程占着缓存目录（或者同时装了多个 Gradle 版本）。关掉 Android Studio
里的构建，删掉 `~/.gradle/wrapper/dists` 下对应版本的 `.lck` 再试。

**Q：装到手机上，部分动作没有动态演示**
短片只内置了训练类常用的 989 个。缺片时动作详情与抽屉里会出现「可下载补全」，
下载后当场从静态图切成循环播放；下载源是 jsDelivr 与 raw.githubusercontent，
完全离线时回退到静态图，不影响其他功能。
