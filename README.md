# 单机版安卓课表 App

一个**纯本地、不联网、不需要注册登录**的安卓课表应用。数据只存在你的手机里，没有服务端，没有账号，没有云同步。

> 当前版本 **0.4.0**（versionCode 4）
> 仓库：<https://github.com/sumu0308/class-schedule>

---

## 目录

- [这个 App 能做什么](#这个-app-能做什么)
- [功能状态](#功能状态)
- [装到手机上（普通用户）](#装到手机上普通用户)
- [自己构建（开发者）](#自己构建开发者)
- [项目结构](#项目结构)
- [技术栈](#技术栈)
- [构建踩过的坑](#构建踩过的坑)
- [文档](#文档)
- [路线图](#路线图)
- [范围外（明确不做）](#范围外明确不做)

---

## 这个 App 能做什么

把「这周上什么课、在哪上、几点上」在一屏里看清楚，并且让你把自己学校的课表录进去。

- **一周课表网格**：左侧节次时间，右侧 5 天（可切 7 天）
- **周次概念完整**：开学日期决定"今天是第几周"，支持按周次范围 + 单双周显示课程
- **录课很快**：长按课表空白格即可新增，自动带上该格的星期与节次
- **提醒、导入**：见[路线图](#路线图)，尚未实现

---

## 功能状态

### 已实现（v0.4.0）

| 功能 | 说明 |
| --- | --- |
| 周视图课表 | 节次时间栏 + 星期表头 + 课程块，深色/浅色主题自适应 |
| 行高按真实时长成比例 | 40 分钟的小节占 80 dp、整段 100 分钟占 200 dp，节次间空档如实显示，左侧时间与内容严格对齐 |
| 行高随屏幕自适应 | 排版比例以"节次时长中位数"为基准，再按屏幕可用高度放大到上限内 |
| 字号自适应 | 课程块字号随块高收敛（14→9 sp），块矮时也不会文字溢出 |
| 默认作息 | 一天 4 个大节 × 2 个小节，每小节 40 分钟，小节间休息 20 分钟（08:00–08:40 / 09:00–09:40 / 10:00–10:40 / 11:00–11:40 / 14:30–15:10 / 15:30–16:10 / 16:30–17:10 / 17:30–18:10） |
| 按"大节"选课 | 课程编辑里点「第 N 大节」即可整块占位，也可逐小节微调 |
| 新增 / 编辑 / 删除课程 | 课程名、教师、教室、星期、起止节次、单双周、周次范围、颜色 |
| 周次范围输入 | 支持 `1-16`、`1-8,10-16`、`1~8`、中文逗号等多种写法，自动规范化与去重 |
| 单双周 | 每周 / 单周 / 双周，与周次范围求交集 |
| 跨节次课程 | 一门课可占连续多节，块高随之变化 |
| 周次切换 | 上一周 / 下一周，一键回到"本周" |
| 学期设置 | 学期名、总周数、开学日期用系统日历精确到年月日，非周一时提示并提供"改为周一" |
| 节次时间设置 | 逐节修改开始/结束时间，可增删节次；改动立即反映到课表排版 |
| 空状态引导 | 没课时明确告诉用户下一步做什么，不留空白死胡同 |
| 撤销删除 | 删除课程后底部提示条可撤销 |
| 数据库迁移 | 已建立迁移机制（`VERSION = 2` + `Migration(1,2)`），**不使用**破坏性重建，升级不会清空已录课程 |
| 旧版本升级兼容 | 从 0.1.0（12 小节模板）或 0.2.0（4 节模板）升级时，自动换成当前作息并打一次性标记，不会覆盖用户自己调过的时间表 |
| 离线 | 不声明任何网络权限（`AndroidManifest.xml` 里没有 `INTERNET`） |

### 尚未实现

上课提醒、教务系统导入、ICS/CSV 文件导入导出、JSON 备份、桌面小组件、拖动调课。

---

## 装到手机上（普通用户）

1. 拿到 `app-release.apk`（自己构建见下文）
2. 把 APK 传到手机。**推荐**：电脑与手机连同一个网络，双击 `serve-apk.bat`，用手机浏览器打开它打印出的地址即可下载
3. 手机点安装，提示"未知来源应用"时允许一次
4. 首次打开后建议先进 **「学期」** 把**开学日期**改成你学校校历上第 1 周的周一

> 当前 APK 使用**调试签名**（v2 签名有效，可正常安装）。正式发布签名在 M6 阶段接入。

---

## 自己构建（开发者）

### 你需要什么

| 依赖 | 要求 | 说明 |
| --- | --- | --- |
| JDK | 17 或更高 | 本机实测 JDK 21 可用 |
| Node.js | 18 或更高 | 只用于项目自带的工具脚本，不参与 App 运行 |
| Android SDK | **不需要自己装** | 项目自带安装脚本，装进项目目录 |
| Android Studio | **不需要** | 命令行即可打包；想改界面再另装 |

### 三步跑起来

```bat
:: 1. 安装 Android SDK 到项目内 .android-sdk（支持断点续传）
node tools\install-sdk-packages.mjs

:: 2. 准备 Gradle 与 wrapper
node tools\setup-gradle-wrapper.mjs

:: 3. 打包
build.bat
```

产物在 `app\build\outputs\apk\release\app-release.apk`。

### 常用命令

| 命令 | 作用 |
| --- | --- |
| `build.bat` | 打正式包（release） |
| `build.bat debug` | 打调试包 |
| `build.bat test` | 只跑单元测试 |
| `build.bat clean` | 清理后重新打包 |
| `serve-apk.bat` | 在局域网内共享 APK，手机浏览器直接下载 |
| `push.bat` | 把改动提交并推送到 GitHub（失败自动重试，推送后自动核对） |

也可以直接用 Gradle：`gradlew.bat assembleRelease`、`gradlew.bat testDebugUnitTest`。

### 环境变量

`build.bat` 已内置好，无需手动配置。若你要单独调用 Gradle：

```bat
set JAVA_HOME=D:\JDK\jdk21
set ANDROID_HOME=<项目目录>\.android-sdk
set GRADLE_USER_HOME=<项目目录>\.gradle-home
```

---

## 项目结构

```
class-schedule/
├─ app/
│  ├─ build.gradle.kts                  模块构建配置
│  └─ src/
│     ├─ main/java/com/classschedule/app/
│     │  ├─ MainActivity.kt             入口 Activity
│     │  ├─ ScheduleViewModel.kt        状态与业务逻辑，含数据库单例
│     │  ├─ data/
│     │  │  ├─ Entities.kt              Term / Period / Course 实体
│     │  │  ├─ AppDatabase.kt           Room 数据库与 DAO
│     │  │  └─ CourseRepository.kt      数据访问唯一入口
│     │  ├─ ui/
│     │  │  ├─ WeekScreen.kt            主界面：顶栏、周次切换、空状态
│     │  │  ├─ ScheduleGrid.kt          课表网格：排版计算 + Canvas 自绘
│     │  │  ├─ CourseEditor.kt          课程新增/编辑弹窗
│     │  │  ├─ PeriodEditor.kt          学期设置 + 节次时间编辑
│     │  │  ├─ CourseColors.kt          课程配色
│     │  │  └─ Theme.kt                 Material 3 主题
│     │  └─ util/
│     │     ├─ WeekRanges.kt            周次表达式解析与规范化
│     │     ├─ TermCalendar.kt          epochDay ↔ 日期 ↔ 第几周
│     │     └─ GridClock.kt             时间字符串 ↔ 分钟换算
│     ├─ test/java/com/classschedule/app/
│     │  ├─ WeekRangesCalcTest.kt       20 个用例：周次解析与日期换算
│     │  ├─ GridGeometryTest.kt         20 个用例：行高比例与点击定位
│     │  └─ PeriodBlockTest.kt          10 个用例：大节划分与作息间隔
│     └─ main/res/                      图标、主题、字符串
├─ tools/
│  ├─ install-sdk-packages.mjs          装 Android SDK（不依赖 sdkmanager）
│  ├─ setup-gradle-wrapper.mjs          准备 Gradle 与 wrapper
│  ├─ wifi-share.mjs                    局域网传包服务器
│  ├─ verify-layout-math.mjs            排版数值独立复算
│  └─ verify-remote.mjs                 核对 GitHub 上的内容与本地一致
├─ gradle/libs.versions.toml            依赖版本集中管理
├─ build.bat / serve-apk.bat            一键打包 / 局域网传包
├─ push.bat                             推送到 GitHub
├─ 单机版安卓课表App-计划文档.md          需求与技术方案
├─ 如何传到手机.md                        三种传输方式与排查顺序
└─ 第一版问题.md                         用户实测反馈与处理记录
```

### 设计取舍（为什么这么做）

| 决定 | 原因 |
| --- | --- |
| `Course` 直接存"一条上课安排"（星期+节次+周次），而不是"课程实体 + 多个时间段"两级模型 | 教务导出与手工录入的粒度天然就是安排级，单机场景无需归一化，实现与校验成本都更低 |
| 周次用文本表达式（`1-8,10-16`）而非关联表 | 单学期数据量小，查询时在内存展开即可；换取写入与备份的简单 |
| "大节"按结构两两配对，不按时间间隔自动识别 | 实测本作息所有相邻小节休息都是 20 分钟（含大节边界），间隔无法区分大节，只有午休 170 分钟 |
| 课表网格用 Canvas 自绘 | 行高按分钟成比例、块跨行、块内塞两行小字，自绘比嵌套 Compose 布局更可控 |
| 不用 `java.time`，只用 epochDay 整数运算 | `java.time` 在 minSdk 24 上需要 API 26 或额外脱糖配置；本项目只需要"天数差"和"星期几" |

---

## 技术栈

| 层 | 选型 | 版本 |
| --- | --- | --- |
| 语言 | Kotlin | 2.4.21 |
| UI | Jetpack Compose + Material 3 | Compose BOM 2025.09.00 |
| 架构 | 单 Activity + ViewModel + Repository | — |
| 本地数据库 | Room | 2.8.5（KSP 2.3.12） |
| 构建 | Gradle + AGP | Gradle 9.8.1 / AGP 9.4.1 |
| 编译目标 | minSdk 24 · compileSdk 36 · targetSdk 36 | — |
| 依赖注入 | 手动（数据库单例），未引入 Hilt | — |

---

## 构建踩过的坑

这些是在真实环境（Windows 11 + 校园网/手机热点，代理软件未启动）里踩到并已修复的问题。它们**不影响你使用 App，但影响你构建**，所以记录下来。

| 现象 | 根因 | 处理方式 |
| --- | --- | --- |
| 所有下载失败，`无法连接到远程服务器` | Windows 系统代理指向 `127.0.0.1:7897`，但代理软件没启动；Java/Gradle 会照读注册表里的代理设置 | `gradle.properties` 里显式清空 `systemProp.*.proxyHost`，强制直连 |
| `sdkmanager` 报 `Failed to download any source lists` | 该工具内部的超时/重试策略在移动网络下顶不住（同一时刻 Java 直连其实只要 650 ms） | 弃用 sdkmanager，改用 `tools/install-sdk-packages.mjs` 直接下载官方组件包并校验 SHA1 |
| 批处理脚本执行出一堆乱码命令 | 批处理文件里写了 UTF-8 中文注释，cmd.exe 按字节解析会错位 | `.bat` 文件一律保持纯 ASCII |
| `timeout /t 3 /nobreak >nul` 的等待不生效 | stdin 被重定向时 `timeout.exe` 直接报 `Input redirection is not supported` 退出，等待退化成立刻执行 | 改用 `ping -n 4 127.0.0.1 >nul 2>&1`（把 stdin 接到 `nul` 也算重定向，解决不了） |
| `NoClassDefFoundError: org/gradle/wrapper/IDownload` | Gradle 9.x 把 wrapper 拆成 `gradle-wrapper-main` 与 `gradle-wrapper-shared` 两个 jar，生成脚本只拷贝了前者 | 从 `gradle-wrapper-main-*.jar` 内层取出官方完整 `gradle-wrapper.jar`（48 KB） |
| `Failed to find target with hash string 'android-37.0-ext2'` | AGP 9.4.1 期望的平台目录名与实际发布的 `platforms/android-37.2` 命名体系不一致 | 锁定 `compileSdk 36` + 配套版本的 AndroidX（版本目录里写了注释说明） |
| `validateSigningRelease` 报 `AccessDeniedException` | 调试密钥库默认位于 `C:\Users\<用户>\.android\`，受限环境下不可写 | 密钥库移入项目 `.keystore/`，构建脚本用推导出的绝对路径引用，项目可整体迁移 |
| `git push` 报 `schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS` | git 默认用 Windows schannel 做 TLS，在受限环境下取不到客户端凭据（与账号密码无关，握手前就失败） | 本仓库配置 `git config http.sslBackend openssl`，改用自带的 OpenSSL |
| `git push` 报 `Failed to connect to github.com:443 after 21000 ms`，但同一条命令重试就好了 | 本机到 `github.com:443` 的连接不稳定（实测约 1/3 概率超时）；`api.github.com` 一直稳定 | 用 `push.bat` 推送，失败会自动重试；别把这种超时误判成账号或密码问题 |
| Gradle 报 `Couldn't open current thread, error = 5` | 文件监视器在受限环境下无法工作 | 无害警告，不影响构建结果 |

---

## 文档

| 文档 | 内容 |
| --- | --- |
| [单机版安卓课表App-计划文档.md](单机版安卓课表App-计划文档.md) | 产品目标与范围、功能清单、技术栈、数据模型、导入方案、页面流程、里程碑、风险与待确认问题 |
| [第一版问题.md](第一版问题.md) | 第一版实测反馈与逐条处理记录 |
| [如何传到手机.md](如何传到手机.md) | 三种把 APK 传到手机的方式与打不开时的排查顺序 |

---

## 路线图

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| M1 | 工程脚手架与数据层 | ✅ 完成 |
| M2 | 课表核心（手工录入 + 周视图） | ✅ 完成 |
| 体验打磨 | 节次设置可视化（已完成）、拖动调课、课程搜索 | 🚧 进行中 |
| M3 | 上课提醒（本地通知、精确闹钟、重启恢复） | ⬜ 待开发 |
| M4 | 文件导入导出（JSON 备份、ICS/CSV） | ⬜ 待开发 |
| M5 | 教务系统导入（需先确认目标学校，见计划文档 Q1） | ⬜ 待开发 |
| M6 | 发布准备（正式签名、隐私说明、安装文档） | ⬜ 待开发 |

---

## 范围外（明确不做）

不做账号体系、云同步、多端协同、服务端后台、社交分享、成绩/考试/作业管理、广告与内购，也**不会**代选课、代签到或修改教务系统数据。

---

## 许可

个人学习项目。第三方依赖许可见各库自身说明（Room、Compose、Kotlin 等均为 Apache-2.0）。
