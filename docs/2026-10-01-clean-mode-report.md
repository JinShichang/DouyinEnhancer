# 清爽模式重构与验收

清爽模式改为向抖音原生 `ICleanModeService` 提交清屏命令，让宿主负责视频、控件和顶部/底部区域的联动布局。暂停或离开视频页时撤销本模块的命令。静态分析以本地抖音 38.8.0 反编译结果为依据；文末列出必须进行的实机验收。

## 范围与上游

本次工作由项目所有者授权：重构已有清爽模式、整理本地目录、同步上游并将源码同步到个人 GitHub 仓库。分析仅使用已有本地 APK 参考文件与公开上游代码/PR 评论。r2 已推送到个人仓库 main，按用户选择保留该仓库原有工作流；没有发布 GitHub Release 或安装到设备。

已合并上游 `twyora/DouyinEnhancer` main 的 `a38a76a7d1bdb901da2d7346a3d82b17d6886136`（v0.13.1），合并提交为 `4b0f7fe`。保留上游分别处理双击点赞和双击评论的 Hook，清爽模式配置迁移到 `ConfigManager.ui.cleanMode`，继续使用原来的 `clean_mode_main_switch` 存储键。首轮版本为 `0.13.1-cleanmode`，versionCode 为 1302；r2 的 versionCode 为 1303；r3 为 `0.13.1-cleanmode-r3`，versionCode 为 1304。

作者在 [PR #128 的回复](https://github.com/twyora/DouyinEnhancer/pull/128#issuecomment-5811217214) 中提出职责拆分、减少硬编码与试探、记录异常堆栈以及优先从数据层切入。本次按这些建议调整实现。

## 实现与证据

宿主参考文件位于项目外层 `reference/douyin-38.8.0`。以下表格将观察、判断和代码改动对应起来，静态观察不等于实机验证。

| 证据 | 判断 | 改动 |
| --- | --- | --- |
| `jadx-classes30/sources/com/ss/android/ugc/aweme/feed/cleanmode/CleanModeCommand.java`：命令携带 caller、自动退出和内容位掩码，提供反向命令 | 清屏有原生状态协议，不必递归修改整棵视图树 | `NativeCleanModeSymbols` 通过 DexKit 解析并缓存服务、命令、字段和方法；`CleanModeHooker` 只提交/撤销本模块 caller 的命令 |
| 同目录 `feed/plato/business/contentconsumption/cleanmode/CleanModeServiceImpl.java`：`toggleCleanMode` 同时通知视频与非视频区域 | 仅修改 ViewHolder 会遗漏顶部/底部布局联动；原生服务能进入完整联动链路 | 调用服务的 `toggleCleanMode(Fragment, String, CleanModeCommand)`，删除像素尺寸修改、平移和伪造底栏切换 |
| `CleanModeCommand` 的进度条 getter 检查位 8，反向命令复制内容与自动退出参数 | 原生命令可保留进度条，并按一致参数恢复 | 清除隐藏进度条的位，关闭宿主自动退出，暂停/离开时主动撤销 |
| `CleanModePresenter.java`：`i0` 接收白名单，`q0` 递归寻找指定后代并隐藏其兄弟控件 | 阻止整个弹幕祖先隐藏会连带保留无关按钮；白名单能够保留真正需要的后代 | 将弹幕 View ID 加入原生白名单，删除祖先 `setVisibility` 拦截及其 DexKit 扫描依赖 |
| `jadx-classes34/sources/com/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel.java`：播放事件 2、暂停事件 4，以及恢复播放、滚动和生命周期回调 | 清屏状态应由视频页的真实播放状态决定；滑动中的旧视频暂停需要延迟处理 | `CleanModePlaybackState` 处理播放/暂停/滚动/退出；弱引用记录所属 panel 与 fragment；退出先清除所有权，避免原生通知重入 |

旧实现中的 Activity 全局沉浸式修改、decor 递归扫描、触摸转发、延时重扫和硬编码尺寸均已删除。r2 增加独立的主页窗口全屏 Hook，见下一节。清爽模式启用时自动启用弹幕白名单。原有独立的“清屏不隐藏弹幕”开关仍可单独使用。

原生映射失败会记录异常堆栈并停止该功能；不会退回旧的强制改布局方案。混淆字段根据使用关系解析，非混淆的宿主入口集中在 `NativeCleanModeSymbols`。

## r2：38.8.0 实机反馈后的修正

用户确认宿主仍为抖音 38.8.0，并通过两张截图反馈首版有底部占位、状态栏显示、直播卡片恢复主页栏，以及暂停后继续播放不再清屏的问题。这是首版的实机反馈，不代表 r2 已完成实机验收。r2 版本为 `0.13.1-cleanmode-r2`，versionCode 为 1303。

| 反馈与证据 | 原因 | r2 改动 |
| --- | --- | --- |
| 底栏不可见但仍占位；`BaseListFragmentPanel.handleTopAndBottomSpace()` 单独决定 spacer 是否 GONE | 原生清屏的底栏命令只影响栏本身，不能自动取消 pager 的占位布局 | 清屏命令持有期间调整仅供 spacer 布局使用的 `shouldHandleTopAndBottomSpaceInPinch` 判断；调用原生 `handleTopAndBottomSpace` 和 `adaptation`，退出命令后恢复原生判断并重新适配 |
| `RunnableC29241J0k.run()` 在上下占位 View 为 GONE 时以 0 参与 pager 高度计算 | 宿主已有完整的移除占位与恢复布局路径 | 使用上述原生路径；不修改 pager 锚点、固定高度、全局尺寸缓存或视频平移 |
| 状态栏重新出现 | 原生清屏不负责 Android 系统栏 | 新增 `CleanModeWindowHooker`，仅在主页 `MainActivity` 使用 WindowInsets 隐藏系统栏并保持 FULLSCREEN；暂停时保留全屏，窗口重新获得焦点时重申；允许滑动临时呼出系统栏 |
| 点击恢复播放后控件未隐藏；`C20670yEk.onResumePlay()` 调用 `r0(3)`，`C06720XxU` 构造函数将状态码存入映射字段 | 首版只处理首次播放的事件 2，没有处理恢复播放的事件 3 | 将事件 3 作为真实播放恢复，移除推测播放已恢复的 `resumePlay(Aweme)` Hook |
| 刷到直播时上下栏重现 | 普通视频滑出产生暂停事件，直播卡片没有同样的首次播放事件 | 在 `onPageSelected` 判断当前 `Aweme.isLive()` 并重申原生命令；直播卡片保持清屏，旧视频的暂停事件不会撤销直播的命令；切回普通视频后恢复正常暂停策略 |

新增的参考文件在外层 `reference/douyin-38.8.0/jadx-r2-layout` 和 `jadx-r2-status`。这些文件与原有 `jadx-classes44/sources/X/RunnableC29241J0k.java` 一起支撑布局链路分析。上述 Hook 点集中在 `NativeCleanModeSymbols`；系统窗口逻辑与原生清屏状态逻辑分别维护。

r2 的格式检查、8 项状态测试（0 失败/0 错误）、Debug/Release 构建及 Release lintVital 均通过。新增测试覆盖旧视频暂停后进入直播、直播切回视频后暂停，以及离开直播后的状态重置。运行时截图验收仍由设备测试确认。

r2 Release APK 位于外层 `artifacts/DouyinEnhancer_0.13.1-cleanmode-r2.apk`。包名与上一轮相同，签名证书 SHA-256 仍为 `e754c0d2f2070f84a24863c7435e64a8024522962289a1e08b6132e81b3319b9`，可以覆盖上一轮安装包。`apksigner verify` 通过；APK SHA-256 为 `29a95ca4c6c18f092e590e349500b56e8f2ca6a1d279840bf69ac37bb5fd72c0`。安装后强制停止并重新打开抖音，使新 Hook 生效。

## r3：播放时保留弹幕

用户实测反馈：暂停时能看到弹幕，但全屏播放进入清爽模式后弹幕消失。38.8.0 的 `jadx-classes7/sources/com/ss/android/ugc/aweme/feed/danmaku/ultra/DanmakuModule.java` 包含传统 `Presenter` 和 `DDanmakuPresenter` 两种实现，后者使用 `DDanmakuComposeView`。两者都使用 `DanmakuModule.onCreateView(Context, ViewGroup)` 创建的专用 `FrameLayout`。旧 Hook 仅在传统 `DanmakuView.onAttachedToWindow` 收集 ID，遗漏 Compose 渲染器，并依赖渲染器已经附着的时机。

r3 在专用容器创建后立即分配 ID 并登记，原生 `CleanModePresenter.i0` 白名单在清屏前保留整个弹幕模块，不再依赖渲染器类型或附着时机。传统渲染器的登记保留用于其他弹幕入口。无 ID 的视图各自使用独立生成的 ID；登记使用视图弱引用，避免持有已销毁页面，也避免旧 ID 集合持续累积。容器类和方法记录在 HookInfo 的 `DanmakuView` 映射中，升级模块版本会重新生成缓存。

`CleanModePresenter.i0` 对滑出容器 `l` 直接调用 `h0(View, int, int, boolean)`，没有对它调用白名单遍历；`s0` 随后会将这个共享容器平移到屏幕外并延迟隐藏。因此只扩充白名单仍不足以修复弹幕。r3 在宿主处理包含已登记弹幕的容器时，调用宿主自己的 `q0(View, List)` 分解为需要隐藏的兄弟控件，再将这些控件交给原有 `h0`，保留其优先级和退出恢复策略。没有阻止整个共享祖先隐藏后就直接放过兄弟控件。`h0` 通过参数签名及日志字符串定位，`q0` 通过静态方法签名定位，均缓存在 `CleanModePresenter` 映射中；没有固定资源 ID 或全局 View.setVisibility 拦截。

此改动只扩充原生清屏白名单，不强制打开用户关闭的弹幕、不恢复共享祖先控件，也不改变暂停/恢复播放、系统栏和底栏布局逻辑。参考 `FeedDanmakuPresenter.java` 与 `AObjectS272S0100000_1.java` 已补充到外层参考目录，用于核对弹幕外层 presenter 的布局逻辑。

r3 的实机验收需覆盖：打开抖音自身弹幕开关后，冷启动首个视频、切换视频、连续暂停/恢复时，清爽播放仍显示弹幕；关闭抖音弹幕开关后仍保持关闭；其他控件仍按暂停/播放切换显隐。还需检查弹幕发送、听抖音入口及仅开启“清屏不隐藏弹幕”的原生手动清屏路径。本机未连接设备，静态分析与编译不能替代这些运行时验收。

## r3：关闭评论后保持清屏

用户实测双击打开再关闭评论后，顶部和底部主页控件以半透明方式重新出现。`jadx-classes30/sources/X/C10330xpM.java` 的 `LK1` 按 caller 去重，已存在的开启命令会直接返回；此前对同一命令调用 `toggleCleanMode` 无法重新通知控件。r3 将重新应用改为通过原生服务先提交本模块的反向命令，再提交开启命令，在同一个 UI 调用中更新 holder 和 non-holder 观察者；重入保护避免刷新回调递归，不撤销其他 caller 的命令。

`BaseListFragmentPanel.setCommentDialogShowing(false)` 是评论关闭通知路径的一部分，`C0OBg.onCommentDialogEvent` 与 `CommentComponent.onCommentDialogEvent` 在其后还执行宿主恢复操作。r3 在该通知后使用 View.post 排队一次刷新，等同步关闭通知完成；不使用延迟轮询或反复定时隐藏。刷新检查 panel 所有权、评论通知序号、真实播放状态、页面可见性和 fragment 生命周期，避免过期任务影响已退出的页面。真实播放事件 2/3 和切换视频也使用同一刷新逻辑。评论关闭时若视频仍暂停，不会主动开始播放或误隐藏暂停控件。

实机需反复双击打开评论、关闭评论（返回键和下滑两种方式），确认顶部/底部主页控件不残留、全屏继续播放且弹幕仍可见；另测暂停后打开/关闭评论，以及评论关闭前切后台、重新打开评论或离开页面。

r3 验证：`spotlessKotlinCheck`、8 项 `testAppDebugUnitTest`（0 失败/0 错误）、Debug/Release 构建及 Release lintVital 均通过；`git diff --check` 通过。外层 `artifacts/DouyinEnhancer_0.13.1-cleanmode-r3.apk` 的 v2 签名验证通过，与 r2 使用相同 Android Debug 证书，可覆盖升级。APK SHA-256 为 `835c2da5187a03fc30e248654292c4904c550d13abf34e91fe381181fc0b1dfb`。安装后强制停止并重新打开抖音，使新 Hook 和新映射生效。源码同步保持原有 GitHub 工作流，提交使用 `[skip ci]`，不推送标签或发布 GitHub Release。

## r4：以最终渲染约束评论关闭后的主页栏

用户确认 r3 弹幕正常，但收起评论后上下栏仍半透明，需暂停/继续或切换视频才消失。r3 评论修复未通过实机验收，下面的改动替代其单次 View.post 刷新。r4 版本为 `0.13.1-cleanmode-r4`，versionCode 为 1305。

38.8.0 的 `MFBarContainerComponent.en0` 向 `VisibilityControlledFrameLayout` 和 `TopShadowViewProxy` 提交优先级显隐请求；最终由 `LJ(int, boolean)` 调用 `super.setVisibility` 或启动显隐动画。`MPFBottomTabComponent.Cp1` 可排队执行底栏请求，`MainBottomTabViewNew.LJIIJ` 再交给优先级管理器，最终由 `LJIIJJI(int)` 应用显隐。这些后续请求不受 r3 单次刷新持续约束。参考文件在外层 `reference/douyin-38.8.0`，包括上述四个类的反编译代码。

r4 新增独立 `CleanModeChromeHooker`，只在本模块清爽命令持有且页面可见、fragment 已附着、Activity 相同的情况下，将主页栏最终 VISIBLE 渲染改为 GONE；顶部关闭该次显示动画。顶部和阴影 ID 从宿主 HomePageUIService 获取，底栏限定专用类。DexKit 通过顶部日志字符串与底栏 setHasFixSize 调用关系唯一定位最终渲染方法，映射存入新增 HookInfo.CleanModeChrome。不会改变宿主优先级请求表，不使用全局视图显隐 Hook 或固定资源 ID。

被拦住且仍为最新意图的显示请求以弱引用保存。暂停或离开时先交还所有权并执行原生撤销，之后恢复尚未被原生再次渲染的待显示请求；更晚的隐藏请求会取消待显示记录。移除 r3 评论通知 Hook 和 View.post，保留真实播放/选页的原生命令重申。弹幕实现未修改。

当前维护结论和逐版本验收要求见 [维护与兼容性审查](clean-mode-maintainability.md)。r4 的设备行为仍需验证，不能因构建通过直接标记评论问题已验收。

r4 验证：格式检查、8 项播放状态测试（0 失败/0 错误）、Debug/Release 构建和 Release lintVital 均通过，`git diff --check` 通过。生成的 HookerRegistry 已包含 CleanModeChromeHooker；38.8.0 classes37.dex 的类定义和方法表确认 HomePageUIService.INSTANCE 及两个无参 int ID getter。Release APK v2 签名验证通过，证书与 r3 相同；外层 `artifacts/DouyinEnhancer_0.13.1-cleanmode-r4.apk` 的 SHA-256 为 `e756405c66f5b5b246b77a9f08126aaf401144abc073fe6f522e76034c864102`。ADB 无连接设备。

安装后强制停止并重新打开抖音。重点测试播放中连续开关评论（返回键和下滑），关闭后上下栏继续隐藏、弹幕仍显示；再测试暂停时开关评论、继续播放、离开主页及切后台，确认正常操作与恢复。源码继续保留 GitHub 当前工作流，使用 `[skip ci]`，不发布 GitHub Release。

## 首轮构建与测试

使用项目的 Java 21、Gradle 9.4.1 和现有依赖。普通环境可通过 Gradle Wrapper 构建：

```powershell
./gradlew.bat spotlessKotlinCheck :app:testAppDebugUnitTest :app:assembleAppRelease :app:assembleAppDebug
```

本机 Java 子进程启动曾报 `CreateProcess error=5`，另提供 [本地构建脚本](../scripts/build-local.ps1)。脚本使用外层 `.toolchain/jdk21` 与 `.toolchain/gradle-9.4.1`，临时移开 daemon JVM criteria，结束后在 `finally` 恢复；通过一致 JVM 参数在当前进程构建。该脚本供当前 Windows 环境使用，可传入 `-JavaHome`、`-GradleHome`。

```powershell
./scripts/build-local.ps1 -Tasks @('spotlessKotlinCheck', 'testAppDebugUnitTest', 'assembleAppRelease', 'assembleAppDebug')
```

状态测试覆盖暂停后继续播放、滑动过程中旧视频暂停、新视频未开始播放、滑动时离开页面，以及取消滑动后的延迟暂停。测试只验证状态转换，不验证 Android 视图与 Hook 的运行时行为。

最终验证结果（2026-10-01）：`spotlessKotlinCheck`、5 项 `testAppDebugUnitTest`（0 失败/0 错误）、`assembleAppDebug`、`assembleAppRelease` 与 Release lintVital 均通过。`git diff --check` 通过；临时移动的 daemon JVM criteria 已恢复。构建存在上游生成代码的 Kotlin 警告及 Gradle 弃用提示，没有阻断构建。

Release APK 使用本机 Android Debug 签名（未提供专用发布密钥），`apksigner verify` 通过；包名为 `io.github.twyora.douyinenhancer`、versionCode 为 1302。外层 `artifacts/DouyinEnhancer_0.13.1-cleanmode.apk` 是本次产物，Debug 版本也放在同目录。如果已安装模块使用另一签名，需要先处理签名不兼容；本次没有卸载设备上的模块。

Release APK SHA-256：`e5f94211aa6c55e2933f1fc4e1cd72fb66c5ffa1a12283b7d6ab1738ec67549d`。

## 实机验收

本机 ADB 当前没有连接设备，以下项目尚未验证。请先在 LSPosed 激活模块，打开清爽模式并强制停止、重新启动抖音。首次启动等待 Hook 映射完成；若日志出现 native clean mode mapping unavailable，应先检查映射日志。

1. 冷启动进入首个视频：播放时检查顶部、底部、互动栏和文案是否隐藏，首帧底部两侧是否仍有圆角；无需先暂停再恢复。
   r2 还需确认系统状态栏隐藏，视频能延伸到底部，不再留下应用底栏的黑色占位。
2. 检查“听抖音”入口：确认其未因弹幕共同祖先被保留；不同入口和视频类型分别检查。
3. 播放中点击发送弹幕入口：确认点击可达、键盘与发送动作正常，关闭输入后能回到正确状态。
4. 点击暂停，再点击继续：暂停恢复控件，继续清屏；进度条能够拖动。
   r2 需在同一个视频反复暂停/继续至少三次，确认不需要切换视频才能重新隐藏；暂停时系统状态栏仍隐藏。
5. 连续上下滑动、取消一次滑动：观察顶部/底栏是否闪烁或卡在错误状态；没有播放时不要误进入清屏。
6. 从推荐流进入评论、搜索、作者页、听抖音和直播，再返回；检查清屏命令是否正确撤销，其他页面点击是否正常。
   r2 需另测推荐流中的直播预览卡片：上下主页栏保持隐藏，切回视频后暂停/继续仍正常。进入完整直播间的控件由直播间自身负责。
7. 测试暂停后切后台/回前台，同时开启“阻止自动恢复播放”和“播放完毕自动暂停”；检查功能组合。
8. 分别开启/关闭双击评论、禁用双击点赞，验证两者互不读取对方开关。
9. 单独打开“清屏不隐藏弹幕”、关闭清爽模式，确认原生手动清屏仍保留弹幕且没有无关组件恢复。

圆角、听抖音入口、弹幕触摸行为目前只能说明旧代码中的干扰路径已移除，不能据此宣称所有抖音版本均已修复。其他宿主版本需要重新核对映射和上述行为。

## 目录整理

外层目录的 39 项历史 APK、实验 dexcheck、旧补丁、备份及重复缓存已移到 `artifacts/legacy`，共 604,058,138 字节（约 576 MiB）。对应关系保存在外层 `artifacts/cleanup-manifest.json`，可逐项恢复。参考 APK、反编译证据、工具和独立 Git 仓库保留。归档不释放磁盘空间。

自动审批拒绝了永久批量删除，原因是删除不可逆且现有备份覆盖不完整，因此采用可恢复归档。仓库内移除了无用的示例测试和旧配置键文件，并新增 `**/build/` 忽略规则。

## 工作记录

2026-10-01：读取 PR 作者反馈 → 合并 v0.13.1 → 依据本地 38.8.0 静态代码定位命令协议 → 重写 Hook 与弹幕白名单 → 添加状态转换测试 → 归档历史产物 → 构建与实机验收交接。GitHub 临时令牌未保存到项目或 Git 配置，应由提供者撤销。
