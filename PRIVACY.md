# FitGym 隐私政策

**更新日期：2026 年 11 月 11 日**

适用版本：`io.github.kylin33.aifitgym`（Android）

> 本文与 App 内「我的 → 通用 → 隐私政策」显示的内容一致。有冲突时以本文为准。

---

## 一句话概括

FitGym 没有账号体系，不收集、不上传你的任何个人信息。训练计划、历史成绩、
技能进度全部存在这台手机上。App 里唯一会联网的地方是「AI 教练」，
而它连的是你自己填的地址。

---

## 1. 我们不收集什么

- 没有注册登录，没有用户 ID。
- 没有埋点统计、没有崩溃上报、没有广告 SDK。
- 不读取设备标识符（IMEI / Android ID / 广告 ID）。
- 开发者没有任何服务器会收到你的数据——不是「收集后匿名化」，而是根本没有这条链路。

## 2. 数据存在哪里

| 数据 | 位置 | 说明 |
| --- | --- | --- |
| 训练计划、每场训练、每组成绩、技能树等级 | Room 数据库 | 位于应用私有目录，其他 App 无权读取，卸载即删 |
| AI 服务商地址、模型名、训练目标与水平 | SharedPreferences | 本地偏好文件 |
| API Key | SharedPreferences（密文） | 使用 Android Keystore 中的设备密钥做 AES-GCM 加密，明文不落盘、不进日志 |
| 导出的备份文件 | 你指定的位置 | 由系统文件选择器写入，**不含** API Key 与 AI 对话 |

App 不申请存储权限，不会往相册、下载目录或外部存储写任何文件。

## 3. 系统的自动备份

App 允许 Android 的「应用数据备份」功能（`android:allowBackup="true"`），
因此系统可能把上述数据库同步到你 Google 账号下的云端备份。这一步由 Android
系统与你自己的账号设置控制，开发者访问不到。

不想要的话，可在系统「设置 → Google → 备份」里关闭整机备份，或单独排除本 App。
API Key 即使被备份到云端，也无法在其它设备上解密——解密用的密钥绑在这台设备的
安全硬件里，不可导出。

## 4. 什么情况下数据会离开这台手机

只有一种情况：**你主动在「AI 教练」里发送消息**。

- **发给谁**：你在「AI 教练 → 设置」里填写的 Base URL。它可能是 DeepSeek、OpenAI、
  某个第三方中转站，也可能是你自己电脑上运行的 Ollama。地址由你填写，App 不预设服务商。
- **发什么**：
  - 你输入的问题与最近的对话记录（最多 30 条）；
  - 一份训练摘要：训练目标、水平、每周次数、单次时长、你填写的伤病与器械备注、
    六条技能流的等级、最近 5 次训练的名称与日期、近 30 天练得最多的几个动作；
  - 一份候选动作清单（用于防止 AI 编造不存在的动作，只含动作 id、名称与计次/计时类型）；
  - 你的 API Key（放在请求头中用于身份验证）。
- **不发什么**：姓名、手机号、位置、通讯录、相册、设备唯一标识、完整历史明细。
- **是否加密**：取决于你填写的地址。`https://` 全程加密；`http://` 为明文，
  设置页会在地址框下方给出警告。

## 5. 第三方服务商

数据一旦发给你配置的服务商，即同时受该服务商的隐私政策约束。FitGym 无法控制
对方如何存储、保留或使用这些内容。请只填写你信任的地址。

App 本身不内置任何第三方分析、广告、推送或社交 SDK。

## 6. 权限与用途

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 仅用于访问你配置的 AI 地址 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 组间倒计时在锁屏、切后台时继续运行 |
| `POST_NOTIFICATIONS` | 在通知栏显示倒计时与操作按钮；不授权时计时照常，只是锁屏看不见 |
| `WAKE_LOCK` | 息屏后让 CPU 不完全挂起，否则提示音会迟到 |
| `VIBRATE` | 倒计时最后三秒与动作切换的触感反馈 |

除此之外没有其他权限：不读取相机、麦克风、位置、通讯录、短信、存储。

## 7. 你的控制权

- **带走**：「我的 → 数据备份与恢复 → 导出」，得到一个 JSON 文件，随你处置。
- **删除训练数据**：「我的 → 清空所有数据」，会连同计划、历史、技能进度一起删除，
  内置动作库保留。
- **全部删除**：直接卸载 App，应用私有目录里的数据会被系统一并清除。
- **不让任何数据离开手机**：不使用 AI 教练即可，其余功能完全离线。

## 8. 儿童

本 App 不面向未满 14 周岁的儿童，也不会主动收集其任何信息。
若监护人发现孩子在无监护情况下使用，卸载 App 即可清除全部数据。

## 9. 政策变更

政策如有修改会随版本更新发布在本页，并更新页面顶部的日期。
涉及数据用途的重要变化，会在该版本的更新说明里单独提示。

## 10. 联系方式

对这份政策有疑问，或发现了与描述不符的行为，请在
[项目仓库](https://github.com/kylin33/FitGym/issues) 提 Issue。

---

### English summary

FitGym does not collect or upload any personal data. There is no account, no analytics,
no crash reporting and no ad SDK. All workout data stays in the app's private storage on
your device. The only network request the app ever makes is the one you explicitly trigger
in the AI Coach, and it goes to the endpoint **you** configure — it is never sent to the
developer. Your API key is encrypted with an Android Keystore–bound AES-GCM key.
The app is licensed under AGPL-3.0 and is provided "as is"; see [DISCLAIMER.md](DISCLAIMER.md).
