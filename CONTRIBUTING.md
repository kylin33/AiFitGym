# 参与贡献

感谢你有兴趣改进 FitGym。这是一个纯本地的 Android 健身 App（Kotlin + Jetpack Compose），
没有后端、没有账号体系，所有数据都在用户自己手机上。

代码以 **AGPL-3.0** 发布。但在你提交第一个 Pull Request 之前，请先读下面的
**贡献者许可协议（CLA）** —— 它是合并的前提条件，不是可选项。


## 一、先开 issue，再动手

| 你想做的事 | 建议路径 |
| --- | --- |
| 报 bug | 开 issue，附机型 / Android 版本 / 复现步骤 / 崩溃日志 |
| 提新功能 | 先开 issue 说明使用场景，**达成一致后再写代码** |
| 改动作数据、增删动作 | 走 `tools/` 里的脚本，不要手改 `assets/exercises.json` |
| 修文案、补翻译 | 直接提 PR 即可 |

功能类 PR 未经讨论就被拒绝会很浪费你的时间，所以请先聊。判断标准只有一条：
**它是否让「打开就能练」这件事更顺**。加社交、加排行榜、加云同步这类需要后端的东西，
默认不做——那会把一个离线 App 变成一个需要运维的服务。


## 二、开发环境

```
JDK 17+ / Android Studio（AGP 9.5.0）/ Kotlin 2.4.21
./gradlew :app:assembleDebug      # 编译
./gradlew testDebugUnitTest       # 单元测试
```

依赖注入是手写的 `AppContainer`，没有 Hilt；网络层是 `HttpURLConnection` 手写，
没有 OkHttp/Retrofit。**引入新依赖前请先在 issue 里说明理由**，这个项目的体积和
可审计性比开发便利更重要。


## 三、代码约定

- 缩进 4 空格，不换 Tab；单文件超过 600 行考虑拆分。
- **注释写「为什么」，不写「是什么」**。比如「这里用 rememberSaveable 是因为转屏会丢草稿」，
  而不是「保存状态」。项目里现有的注释都是这个风格，跟着写。
- Compose 里状态用 `StateFlow` 单向下发，不要多个 `MutableState` 各自为政。
- 计时、倒计时一律用 `SystemClock.elapsedRealtime()` 算物理截止点，**禁止用
  `delay(1000)` 累加**——锁屏后协程会被挂起，累加法必然走不准。
- 数据库改动必须写迁移，并在 `app/schemas/` 里保留导出的新版本；不要用
  `fallbackToDestructiveMigration` 抹掉用户的历史记录。
- 提交信息用中文或英文都可以，格式建议 `模块: 做了什么`，例如
  `workout: 修复休息阶段锁屏后计时停住`。


## 四、Pull Request

1. Fork → 建分支（`fix/xxx`、`feat/xxx`）→ 改动 → 提交。
2. 确保 `./gradlew assembleDebug` 零警告通过，`testDebugUnitTest` 全绿。
3. 在 PR 描述里写清楚：**改了什么、为什么改、怎么验证的**。涉及 UI 的请附截图。
4. 在 PR 里签署 CLA（见下）。没签的 PR 不会被合并，也不会被 review。

**不要提交**：`local.properties`、任何 `*.jks` / `*.keystore`、`gradle.properties` 里的
本机路径、你自己的 API Key、`app/build/` 下的产物。`.gitignore` 已经覆盖这些，
但如果 `git status` 里出现了它们，请先检查。


## 五、贡献者许可协议（CLA）

> 本协议适用于你向 FitGym 仓库提交的每一份贡献（代码、文档、动作数据、素材、设计）。
> 提交 Pull Request 即表示你已阅读、理解并同意以下全部条款。

### 1. 定义

- **「项目方」**：本仓库的维护者，当前为 FitGym 的发起人，及其指定的继受主体。
- **「贡献」**：你通过 Pull Request、补丁、issue 附件或任何其他方式提交给本项目的
  任何原创内容，包括源代码、文档、测试、数据、图像与设计。
- **「你」**：提交贡献的自然人或法人。

### 2. 版权许可（含再许可权）

你在此授予项目方一项**永久的、全球范围的、非独占的、免版税的、不可撤销的**版权许可，
允许项目方复制、修改、改编、翻译、公开发表、公开表演、公开展示、分发你的贡献及其
衍生作品，并**以任何许可证（包括但不限于 AGPL-3.0、MIT、Apache-2.0 或商业专有许可证）
对上述内容进行再许可（relicense）**，且无需向你另行通知或支付报酬。

**这一条是本协议的核心**：它确保项目方将来可以在必要时更换整体许可证（例如为了上架
某个应用商店，或成立公司做商业化），而不必回头向每一位历史贡献者逐一索取同意。

### 3. 专利许可

你在此授予项目方及本项目所有使用者一项**永久的、全球范围的、非独占的、免版税的、
不可撤销的**专利许可，允许制造、使用、销售、许诺销售、进口及以其他方式转让你的贡献
（单独或与项目结合）。该许可仅覆盖你拥有或有权许可的、因你的贡献本身而必然被侵犯的
专利权利要求。

若你或你的关联方就本项目或其贡献提起专利诉讼（包括反诉与交叉诉讼），本条第 3 款
授予你的全部许可自起诉之日起终止。

### 4. 你保留版权

本协议**不转让著作权**。你仍然是自己贡献的版权所有者，可以自由地将同一份贡献用于
其他项目、以其他许可证发布。

### 5. 你的陈述与保证

你声明并保证：

1. 每份贡献均为你的原创作品，或你已获得足以按本协议授权的全部权利；
2. 若你的雇主对你在工作范围内创作的作品享有知识产权，你已取得雇主的书面授权，
   或雇主已就此放弃权利；
3. 贡献中不包含你明知属于第三方专有或保密的内容，也不包含你无权按本协议授权的
   第三方代码或素材；
4. 提交贡献不违反你与任何第三方之间的协议（包括保密协议与竞业限制）；
5. 贡献中如包含第三方开源代码，你已在 PR 中明确标注其来源与许可证，且该许可证
   与本项目兼容。

### 6. 无担保

贡献按**「现状」**提供，不附带任何明示或默示的担保，包括但不限于适销性、特定用途
适用性与不侵权的担保。你不对贡献的使用后果承担任何责任。

### 7. 项目方无合并义务

项目方没有义务使用、合并或保留任何贡献，也没有义务为贡献提供支持、维护或更新。
是否合并由项目方自行判断。

### 8. 通知义务

若你事后发现第 5 条中的任何陈述不再准确（例如发现贡献中包含未授权的第三方代码），
你应当立即通知项目方，并配合移除或替换相关部分。

### 9. 协议的转让

项目方有权将本协议项下的全部权利与义务转让给继受主体（例如因项目捐赠给基金会、
或成立公司承接项目），无需另行取得你的同意。转让后，本协议对继受主体同样有效。

### 10. 生效、存续与法律适用

- 本协议自你提交第一份贡献之日起生效，**在你停止参与本项目后继续有效**。
- 第 2、3 条授予的许可**不可撤销**。
- 本协议适用中华人民共和国法律。因本协议产生的争议，双方应先友好协商；协商不成的，
  提交项目方所在地有管辖权的人民法院解决。
- 若本协议的任何条款被认定无效或不可执行，其余条款继续有效，该条款应在最小必要
  范围内被替换为最接近其原意的有效条款。


## 六、如何签署

**只需要签一次**，之后的所有 PR 都自动覆盖。在 Pull Request 的描述或评论里贴下面这段，
把尖括号内容替换成你的真实信息：

```
I have read the CLA Document and I hereby sign the CLA.

Name: <你的姓名或法定名称>
GitHub: @<你的 GitHub 用户名>
Date: <YYYY-MM-DD>
Entity: <个人 / 公司名称，个人可填 Individual>
```

维护者会把它登记进 [CLA-SIGNERS.md](CLA-SIGNERS.md)。如果你代表公司贡献，
请额外提供可验证的公司邮箱，或由公司授权代表以同样格式签署一次。

**未成年人**：未满 18 周岁的贡献者需由法定监护人按同样格式代为签署，并在 `Entity`
一栏注明关系。


## 七、English Summary

> This is a non-binding summary. The Chinese text above prevails.

By submitting a pull request you agree to the Contributor License Agreement above.
In short:

1. You keep the copyright of your contribution.
2. You grant the project owner a perpetual, worldwide, non-exclusive, royalty-free,
   irrevocable license to use, modify and distribute your contribution, **including the
   right to relicense it under any license** (AGPL-3.0, MIT, Apache-2.0 or a commercial
   proprietary license).
3. You grant a patent license covering claims necessarily infringed by your contribution,
   with a patent-termination clause.
4. You confirm the contribution is yours to submit, contains no unauthorized third-party
   code, and does not violate any agreement you are party to.
5. The contribution is provided "as is", without warranty. The project owner is not
   obliged to merge it.

Sign once in your first pull request:

```
I have read the CLA Document and I hereby sign the CLA.
Name: <your legal name>
GitHub: @<your handle>
Date: <YYYY-MM-DD>
Entity: <Individual or company name>
```


## 八、为什么需要 CLA

因为项目用了 AGPL-3.0，而 AGPL 一旦有外部贡献就很难改。想象几年后你想把 App 上架到
某个不接受 copyleft 的商店，或者成立公司做闭源版本——只要仓库里有一个人的代码是
按 AGPL 进来的，你就得找到他、说服他、等他回复。有的人换了邮箱，有的人不再用 GitHub，
有的人开价。

CLA 在第一天就把这件事解决了：你仍然拥有自己的代码，项目方获得了重新授权的余地。
这是 Qt、Kubernetes、Apache 基金会等大型项目都在用的机制，不是什么霸王条款。

如果你不接受 CLA，完全可以不提交 PR——fork 一份自己改，AGPL 明确允许你这么做，
只是那份衍生作品也必须以 AGPL 发布。
