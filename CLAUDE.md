# Twig — 工程笔记与工作指南

> **给人类读者**:这份文件是 Twig 的工程笔记。它面向"要改这块代码的人"而不是
> "第一次了解这个项目的人"——项目介绍看 [README.md](README.md)。
> 最有价值的是底部的**「踩过的坑」**(全文约 80%):每一条都是真实事故的定案记录,
> 写明了症状、错误归因、根因和现方案。**改终端、缩略图、TS 解复用、特权访问、
> WiFi 共享这些子系统之前,先读对应那一节**——里面好几条是花了两三次上线事故
> 才定位到的,重踩一次的代价很高。
>
> 文件名沿用 `CLAUDE.md`,因为 Claude Code 会自动读它;内容对人同样适用。
> 本机专属的配置(测试机地址、发布目录等)在 `CLAUDE.local.md` 里,不入库。

体积优先的双面板 Android 文件管理器(对标 X-plore),原生 Kotlin + XML View,不引 Material 库、最小依赖。

## 交付流程(每次改完必走)

固定四步,**顺序别乱**(第 3、4 步的具体地址见本机的 `CLAUDE.local.md`):

```bash
# 1) 构建 release(R8 + 资源裁剪;build-tools 固定 36.1.0)
./gradlew :app:assembleRelease

# 2) aapt 校验版本号(★ 别信管道退出码 —— 构建失败也可能装成旧包)
"$ANDROID_HOME"/build-tools/36.1.0/aapt dump badging \
  app/build/outputs/apk/release/app-release.apk | grep -E "versionName|versionCode"

# 3) 装到测试机
# 4) 复制到发布目录 + 通知
```

- **验证纪律**:安装前一定用 aapt 确认 `versionName` 是你刚打的版本。曾经构建失败被管道 `exit 0` 掩盖,把旧包当新包发出去(0.20.3 == 0.20.2 事故)。
- **版本号**:只在真正要装机测试/对外交付时才递增 `app/build.gradle.kts` 的 `versionCode`(+1)和 `versionName`;fix 走 patch 位,feat 走 minor 位。同一轮来回打磨的中间小改(比如反复调圆角大小/间距这种)只需编译验证(`compileReleaseKotlin`/`assembleRelease`),不必每次都升版本号。

## 构建环境

- **release 目前用 debug 签名**(`signingConfig = debug`),直接 `install -r` 可覆盖。
  ★ 对外发布前必须换成自有 keystore——debug key 是公开的,任何人都能签出覆盖安装包。
- ABI 只打 `arm64-v8a` + `x86_64`;上架用 `bundleRelease` 出 AAB 按设备拆分。

## 架构一句话

所有来源(本地/压缩包/FTP/SFTP/SMB/WebDAV/restic)对 UI 都是同一套
`FileSystem` + `XFile` 接口,跨来源复制/移动由 `CopyEngine` 走
`openInput()/openOutput()` 流式完成。**加一种来源 = 新增一个 FileSystem 实现,UI 与拷贝零改动。**
每个 `XFile` 带 `scheme`,靠 `FsRegistry.of(scheme)` 找到对应 FileSystem;同类多服务器每台一个唯一 scheme。

### 模块

| 模块 | 说明 |
|---|---|
| `:core-fs` | 纯 JVM:`XFile` / `FileSystem` / `FsRegistry` / `CopyEngine` |
| `:fs-local` | `LocalFileSystem`(java.io.File)+ `priv/` 特权回落(root/Shizuku 共用的 `PrivilegedShell`) |
| `:fs-archive` | `ArchiveFileSystem` 基类 + Zip(读写,GBK 自识别)/7z/RAR(只读);`ArchiveWriter` 打包(zip/7z),跨来源;加密包读写(`ZipAes`/`ZipCrypto`/`ZipWriter`) |
| `:fs-network` | `FtpFileSystem` / `SftpFileSystem`(SSHJ)/ `WebDavFileSystem`(手写 PROPFIND)/ `S3FileSystem`(手写 SigV4 + REST) |
| `:fs-smb` | `SmbFileSystem` —— libsmb2 NDK/JNI,读写 |
| `:fs-restic` | restic 仓库只读解密读取器(纯 Kotlin) |
| `:fs-zstd` | `NativeZstd` —— zstd JNI(供 restic/压缩用) |
| `:git-lite` | 纯 Kotlin 迷你 git(`GitRepo`/`ObjectStore`/`IndexFile`/patience `Diff`),浏览工作区状态与历史;worktree/子模块经 `GitLayout`+`WorktreeGitFs` |
| `:app` | 双面板 UI、权限、增删改、查看器、终端、SAF 回退、WiFi 共享服务 |

### UI 关键类(`:app`)

- `MainActivity` + 两个常驻 `PaneFragment`(弃用 ViewPager2,可见性切换 + fling 手势,横竖屏同布局)。
- `PaneViewModel` 管导航栈/树展开;`FileAdapter` 渲染行。树永远"就地展开/折叠",不"进入"目录。
- 连接持久化 `ConnectionStore`、收藏 `FavoritesStore`、偏好 `Prefs`,均存 SharedPreferences。
- 查看器:`TextViewerActivity` / `HexViewerActivity` / `ImageViewerActivity` / `MediaPlayerActivity`(ExoPlayer + ffmpeg 音频软解)。
- Git:`GitActivity`(状态/历史)、`DiffActivity`(双栏 patience diff),SFTP 仓库经 `SshGitData` 远程 exec git。
- 与其他 App 互通:进来两条(`SEND` → `ShareTargetActivity` 复制到…;`VIEW` →
  `ViewIntentActivity` 用 Twig 打开),出去一条(`OpenFiles.openWith` 经 `StreamProvider`
  流式暴露 content://)。外来 `content://` 统一由 `ShareSourceFileSystem`(scheme `share`)
  承载,`openRandom` 走 `openFileDescriptor` 定位读——压缩包免物化流式解析、视频能 seek。
  `ViewIntentActivity` 是无界面中转页:按 `OpenFiles` 的扩展名规则(认不出再看 MIME 大类)
  分发到查看器,压缩包走 `MainActivity.mountIntent` → `PaneViewModel.mountExternal` 挂到
  树顶就地展开。★ 分发时**不能加 `FLAG_ACTIVITY_NEW_TASK`**:content:// 的临时读权限跟
  接收方任务栈走,丢到新栈里查看器一读就 SecurityException。基础 FileSystem 的注册也因此
  从 MainActivity 挪到 `TwigApp.registerBaseFs`(这些入口会在没有主界面时冷启动进程)。
- 设置:`SettingsActivity`(手写独立设置页:显示/网格视图/缩略图,无 preference 库)。
  布局类偏好(行高/网格/缩略图)改动走 `Prefs.uiSignature` —— `MainActivity.onResume`
  对比签名变化即 `recreate()`;`PaneFragment.onViewCreated` 里 **VM rows 非空时只走
  `resortAll()` 不再 bootstrap**(recreate 后 VM 仍在,重复 bootstrap 会被内部 guard
  跳过/破坏位置恢复)。
- 缩略图:`Thumbs`(引擎)。两层缓存:内存 LruCache → `cacheDir/thumbs` 磁盘 LRU
  100MB;key = md5(文件名:大小:mtime),**与路径无关**(换路径/挂载点仍命中、文件变更
  自动失效)。曾有过"写文件同目录 `.thumbnails` 共享层"的可选开关,2026-08-06 已整体移除。网络图片受开关控制,关闭时只读 jpg 头部 EXIF
  内嵌图;PDF 需可 seek 真文件仅本地生成。压缩包内文件按"网络"对待(外层可能挂在 SMB)。
  失败带 60s 冷却(非永久拉黑);生成队列 2 线程 FIFO;目录折叠 `cancelPending` 摘队列。
  **应用图标不走缩略图**(2026-08-06):APK 文件与「应用」树条目(scheme `apps`)的图标
  一律由 `FileIcons` 直接问 PackageManager 要(`apk 路径` / `pkg:包名` 两种 key),
  异步 + 自带缓存、不受缩略图开关影响;再走一遍缩略图管线只是同一张图生成两遍、多占一份
  磁盘缓存和生成队列名额。
- **网络视频缩略图**(★ 折腾史见下"踩过的坑"):不整包下载,自己解析容器精确定位
  1/10 时长处的关键帧、只下 ~2MB。MP4 解 moov 采样表(`findKeyframeOffset`)、MKV 解
  EBML(SeekHead→Cues→目标 Cluster)。四种网络来源(SMB/WebDAV/SFTP/FTP)都实现了
  高效 `openRandom`(`FileSystem.randomAccessEfficient()==true`),播放器 seek 也一起受益。
- 网格视图(独立于缩略图开关):同一 RecyclerView 用 `GridLayoutManager` 混排——普通行
  full span、文件格子占 1 列,列数按面板宽 /96dp 自适应;目录与可展开项(压缩包)永远
  占整行,树展开逻辑不变。分组排序在 `PaneViewModel.sortList`——**缩略图总开关开着或网格
  开着**就生效(树式列表与网格一视同仁):目录 → 〔网格「全部文件」时:可展开的压缩包〕→
  能出缩略图的文件 → 其余,组内仍按用户选的排序。压缩包单独成组只在「全部文件」网格下——
  那时它是格子海里唯一的整行,夹在中间会把网格切断。
- 属性卡片:长按「属性」在文件行下方插 tab 卡片(`PaneViewModel.InfoNode` + FileAdapter
  第二 viewType);内容由 `FileInfo` 全走系统 API(EXIF/MediaMetadataRetriever/
  PackageManager;媒体轨道明细用 media3 `MetadataRetriever` 枚举)。**免整读原则**:
  要整文件读取的信息不自动读——远程 apk 不显示应用信息,哈希 tab 本地自动算、网络手动点。
- 占用图(SpaceSniffer 式 treemap):`TreemapView`(手写 squarified;双指缩放几何变换、
  文字字号固定→放大自动显示更多标签)+ `TreemapScanner`,**内嵌 PaneFragment**(mapMode
  就地替换树,不是新页面)。返回键经 `MainActivity.onBackPressed → activePane().handleBack()`
  路由(回上级/退出);块菜单与树长按菜单共用 `commonFileActions`;图上「选择」多选存
  `mapSelected`,侧栏复制/移动/删除经 `selectionOrCurrent()` 优先取它;「对侧显示」=
  `PaneViewModel.revealPath` 逐级展开定位(仅本地与已连接服务器 scheme)。
- **WiFi 共享**(`com.twig.app.share`,0.96.0):Twig 唯一一条"别人来连 Twig"的通路。
  `HttpServer`(裸 ServerSocket 上的 HTTP/1.1:请求解析、Basic 认证、Range、keep-alive、
  chunked 请求体、100-continue)+ `ShareHandler`(GET/POST 给浏览器,PROPFIND/PUT/MKCOL/
  MOVE/COPY/LOCK 给 WebDAV 客户端)+ `WebUi`(自包含 HTML/CSS/JS,流式 `Multipart` 上传)
  + `ShareRoot`(URL 路径 ↔ XFile)+ `Discovery`(UDP 探测/应答)+ `ShareService`(前台服务)。
  界面只有 `ui/ShareDialogs`(配置/状态框 + 扫描框),没有独立 Activity;入口在**操作列**
  (`as_share`),服务开着时图标换成实心扇形(`syncShareIcon`,三条操作列都要刷),
  共享范围默认取绿框选中的 `PaneViewModel.currentDir`——框里不做目录选择器,
  入口本来就长在文件树旁边,位置在点开对话框之前就已经指定过了。
  几条定死的东西:
  - **默认只读**;所有写方法的判定集中在 `ShareHandler.requireWrite` 一处,不散到各 handler。
  - **"所有来源"模式的顶层路径段用 scheme,不用显示名**:scheme 全局唯一且由连接标签
    确定性算出,WebDAV 客户端把它当挂载点存下来后路径不会因为新展开了一台服务器就整体挪位。
    显示名只用在 HTML 的链接文字上。
  - **`..` 一律拒绝**(`ShareRoot.segments` 返回 null),上传的文件名也只取末段——
    路径穿越在这个功能里等于把共享目录之外的东西送出去。
  - **必须声明 `DAV: 2` 并实现 LOCK**(哪怕是不做互斥的假锁):macOS Finder 与 Windows
    资源管理器发现不支持 LOCK 就整个以只读挂载,写入功能白做。同理 `PROPPATCH` 要答 207
    而不是 501,否则 Finder 复制完会判定失败并把刚传上去的文件删掉。
  - **WakeLock 只在有请求在飞时持有**(`HttpServer` 的 `onActive` 回调),空闲时靠前台服务
    保命就够了;WiFi 锁则整个共享期间持有(息屏后 WiFi 睡了这台设备就从局域网上消失)。
  - **保活不做 START_STICKY**:进程真被杀了 socket 和线程都没了,系统重启服务只会拿到
    一个 `session == null` 的空壳,不如跟着进程一起结束诚实。
- `CodeHighlighter`:手写词法着色,TextViewer 与 git 双栏 `DiffActivity` 共用——diff 是
  整侧文本 tokenize 一次再按行 `subSequence` 切片(跨行注释/字符串状态才对)。DiffActivity
  用户选的主题深浅色跟系统当前深浅色模式不冲突就直接用;冲突时(如系统浅色模式下选了
  Monokai)临时换成对应深浅色的默认主题(浅→GitHub Light,深→Monokai),避免 diff 区域
  跟还是跟着系统走的 toolbar/状态栏撞色——只影响这次显示,不改 `Prefs.codeTheme` 本身。
  `listLeft`/`listRight` 背景同步换成 `theme.bg`,行默认字色同步换成 `theme.fg`,增删行
  底色是半透明叠色,盖在任意背景上自然偏红/偏绿,不用为换后的主题单独调配色——
  2026-07-28 之前有个按背景亮度**无条件**强制回退 GitHub Light 的 `diffTheme()`,原因是
  背景一直没跟着主题换,深色主题字看不清,根子在背景没换,不在主题本身该不该用。

## 约定

- **体积优先**:不加重依赖;能手写就手写(WebDAV 没用 SDK、git 自实现、restic 自解密)。加依赖前先算 APK 增量。
- **R8**:混淆开启。JNI(libsmb2/zstd)按名绑定,native 方法/类名必须 keep;Termux、BouncyCastle 也在 `app/proguard-rules.pro` 里 keep。改这些库的调用先看该文件。
- **面向用户的文案分两处**,加新文案前先看自己在哪一层:
  - `:app` 里的一律走 `strings.xml`(zh/en 两份,name 集合必须对齐)。`FileInfo`、
    `GitVfs`、`SafFileSystem` 这些即使不是 Activity 也拿得到 Context,没有例外。
  - **纯 JVM 模块**(`core-fs` / `fs-*` / `git-lite`,用的是 `kotlin.jvm` 插件)
    没有 Context 也没有 R,**一律写英文,不写中文**。它们的异常消息会经
    `it.message` 直接 toast 给用户,中文的话英文界面就露馅;而为了这些消息把模块
    改成 Android library,代价是丢掉那批毫秒级的纯 JVM 单测,不划算。
    真要本地化,该走"`FsException` 带错误码 + UI 层查表"而不是搬资源。
- **提交信息**:中文 Conventional Commit,`类型: 任务名——摘要`(如 `fix: 终端一输入就断——resize 挪后台线程`)。规则见全局 `~/.claude/CLAUDE.md`。
- **设置三件套**:新增显示类功能 = 独立开关(不与其他开关绑死,保持正交)+ 顶栏菜单快速
  入口 + 设置页细项;配置进设置页后**原菜单快捷项保留**(用户重视一步可达,0.45.0 网格
  与缩略图解耦即此教训)。
- 单元测试在各 `:fs-*` 模块的 `test`(zip/ftp/sftp/webdav/restic/git 都有);跑 `./gradlew :fs-network:test` 等。

## 踩过的坑(改相关代码前先读)

- **SSHJ + BouncyCastle**:Android 自带的 "BC" 是阉割版(缺 X25519),握手报 `no such algorithm x25519`。`SftpFileSystem` 的 `ensureFullBouncyCastle()` 把它顶掉换成完整版;依赖里显式带 `bcprov-jdk18on`。别删。
- **SSH 终端**(`TerminalActivity` + `com/termux/`):Termux 的 `TerminalSession` 是 final、绑本地 PTY。这里**不起本地进程**,反射注入自建 `TerminalEmulator`(`mEmulator`)、置 `mShellPid=1` 让按键入队,由桥接线程搬到 SSHJ shell;读包级私有队列靠同包的 `TermBridge`。
- **终端 resize「一输入就断」真相(★ 三次事故后定案,2026-07-16)**:历史上两次上线「监听布局→转发 window-change」都秒断,当时归因「个别服务器容不下 window-change」——**全错,与服务器、termux-view 均无关**。
  - **根因是主线程**:布局监听/Handler 回调在主线程,`changeWindowDimensions` 最终是 socket 写→抛 `NetworkOnMainThreadException`;而 SSHJ `TransportImpl.write` 在写 socket **之前**就已推进出站包序号+加密流状态——异常一抛,包没发出去但状态已+1,连接「中毒」。下一个正常出站包(第一个按键,来自后台桥接线程)序号错位→服务器 MAC 校验失败→关 TCP(`Broken transport; encountered EOF`)。症状因此精确表现为「一输入就断」且**所有**服务器复现。
  - **吞异常的双重代价**:当时 `runCatching` 把 NMTE 静默吞掉——不仅丢了唯一线索,还把「有毒的失败」伪装成「无害的未发送」(`sent=false`)。
  - **探测盲点(0.30.0 为何没自动回退,而是输入→断→重连无限循环)**:probe 判定挂在 `if (sent)` 之后,发送「失败」就不判定→cap 永远停在 `unknown`→重连后再毒化再断,`broken` 回退一次都没触发。教训:**降级/兜底逻辑必须覆盖失败路径——「发送失败」≠「什么都没发生」,失败也可能已产生副作用**。
  - **铁律**:一切 SSHJ 出站调用(含 resize)只能在后台线程;网络层异常绝不静默吞(resize 失败现在打 `twig: window-change failed` 到 logcat `W/System.err`)。
  - **现方案**(`TerminalActivity.syncRemoteSize()`,0.30.1 实测 htop 留白根治):布局防抖 300ms→后台线程发 resize;按服务器(scheme)在 Prefs 记忆三态能力——`unknown` 首发即探测,3s 内断连标 `broken` 永不再发(防真有容不下 resize 的服务器陷入断连循环),存活标 `ok` 放心转发。`ShellSession` 出站(stdin + resize)用一把锁串行化(并发写会搅乱加密流计数器→MAC 失败断连)。
- **终端断线自动重连**:SSHJ 连接开了 15s keepalive(`SftpFileSystem.newAuthedClient()`),缓解移动网络 NAT/运营商网关对空闲连接的静默掐断。真断线时 `TerminalActivity.reconnect()` 退避重试 3 次自动接上,不再直接判「已结束」;stdin 桥接线程整个会话生命周期只起一条(绑定 `TermSession.gen` 而非闭包捕获旧连接),避免重连时并发起第二条线程抢按键队列丢键。
- **终端多会话**:`TermManager`(静态)持有会话列表,顶部 Spinner 切换;每条会话独立 SSH 连接 + 模拟器,切走仍在后台累积输出。附加键按钮必须 `isFocusable=false`,否则抢终端焦点导致按键路由错乱。
- **git 视图的缓存分层(2026-07-17)**:树的 `children` 缓存(PaneViewModel)→ `GitFileSystem` 的 status/log/branches/diff 缓存 → 底层 `ObjectStore.packsLoaded`(pack 列表只加载一次)与 `XFileGitFs.listCache`(远程目录清单永久缓存)。前两层由 `invalidate()` + 长按「刷新」覆盖;**最底层不受任何刷新管**——本地仓库 `git fetch`/`gc` 出新 pack、SMB/WebDAV 仓库远端变更后,刷新也可能看不到新提交(本地新 commit 是松散对象,不受影响)。要修得给 `GitFs`/`GitData` 加 dropCaches 钩子,且要处理并发关闭 pack reader,评估过收益低暂不做。另:本地/SSH(`cheapGitSchemes`)每次展开自动强刷,SMB/WebDAV 只靠手动刷新。
- **git worktree / 子模块(★ 2026-08-11,`GitLayout`/`WorktreeGitFs`)**:`.git` 是文件时
  (`gitdir: <路径>`),那个目录**不是一个完整仓库**——里面只有 HEAD / index / ORIG_HEAD /
  logs/HEAD 这些"每工作区一份"的东西,`objects`、`refs/heads`、`packed-refs`、`info/exclude`
  全在它 `commondir` 指向的主仓库 `.git` 里。老代码把 gitdir 直接当 metaFs 用,表现是
  **不报错但全空**:历史空、分支空、diff 空,status 还会把 index 里每个文件都算成新增
  (HEAD tree 读不出来)。现在 `GitLayout.resolve` 解出 (gitDir, commonDir),两者不同就用
  `WorktreeGitFs` 按路径分流。分界线照 gitrepository-layout 的 "per-worktree file" 走,
  **分反了同样不报错**:HEAD 落到公共目录就成了主仓库的分支。
  - **`gitdir:` 里写的是绝对路径,而且是建这个 worktree 那台机器上的**。经 SMB/WebDAV
    挂载时看到的只是那台机器的某棵子树,拿它直接找必然落空 —— 直取不中就从工作区目录
    逐级往上找同样的尾巴(`.git/worktrees/<名>`),worktree 建在仓库自己里面是常见布局。
  - 判"是不是仓库"不能只看 `.git` 存在:`.git` 是文件但指向的目录找不到 = 不是可读仓库,
    否则长按菜单里给出 Git 项、点进去一片空白。`GitRepo.isRepo` 与树里的检测
    (`PaneViewModel.listChildren`,**别再要求 `isDir`**)都走同一套解析。
  - **虚拟树的「工作区」节点**(`GitFileSystem` 的 `/worktrees`):每条 worktree 是
    **另一套 `GitFileSystem`**、注册成独立 scheme,子项属于别的 FileSystem 这件事树本来
    就支持(压缩包挂载同款)——好处是更改/历史/diff 那套逻辑一行不用改。两个要点:
    嵌套那层的 `host` 传 **null**(不再列工作区,否则 A→B→A 无限套);和「其他分支」
    一样**不列自己**,没有别的工作区时整个节点不出现。
  - **工作区路径要映射**:`worktrees/<名>/gitdir` 记的是那台机器上的绝对路径,SMB/WebDAV
    这边根本不存在。以主工作区在这边的路径为锚,从**最短的尾巴**开始逐段往回试
    (`…/repo/nested/wt` → `wt`、`nested/wt`…),再用那个目录的 `.git` 是否确实指向
    `worktrees/<名>` 确认——只看"目录存在"会把同名无关目录认成它。★ 别用"当前工作区的
    记录路径 ↔ host 路径"去反推挂载偏移:站在**主仓库**看时,主工作区那条的路径是这边
    算出来的(commondir 的父目录),根本没有"记录路径"可对,推出来的偏移恒为空。
  - **判存在/类型不许用 `FileSystem.resolve()`**(★ 2026-08-11 真机 SFTP 上栽的):
    `SftpFileSystem.resolve` 的实现就是 `XFile(scheme, path, isDir = true)` —— 不 stat、
    不报错、任何路径都说是目录。拿它判断,worktree 那个 `.git` **文件**会被当成目录,
    内容一个字节都读不出来,症状是「工作区 (3)」个数对、展开一条都没有(个数只依赖
    `git worktree list`/元数据,展开才需要读 `.git`)。`GitVfs.statEntry` 统一改成
    **列父目录按名字找**;凡是要判类型的地方都走它,别再碰 resolve。
  - 测试:`GitWorktreeTest`(本地,真 `git worktree add` 造 fixture)+
    `RemoteGitWorktreeTest`(远程,`MountFs` 把本地目录当共享根,专门覆盖绝对路径失效
    那条兜底、以及经 `GitFileSystem` 列「工作区」再点进去)。远程那条本来最容易漏测,
    而它恰恰是唯一走兜底与路径映射逻辑的。
- **网络视频缩略图(★ 2026-07-22,`Thumbs.genVideoNetworkFrame` 一路踩下来的定案)**:核心是**别让系统 `MediaMetadataRetriever`(MMR)自己在慢速网络上 seek**——它不吃容器的 Cues 索引,而是从头**顺序扫 Cluster**(实测 74GB REMUX MKV 扫了 111MB / 14s)。正确姿势是自己解析容器、只喂它需要的字节:
  - **MP4/MOV**:`scanTopBoxes` 找 moov/mdat，`findKeyframeOffset` 解采样表(stts/stss/stsc/stco/stsz)算出目标关键帧**精确字节偏移**（早期按 mdat 大小线性估比例，VBR 偏差可达 9MB+，恒失败退黑图）。数据源 `NetVideoDataSource` = 预取 head+moov+关键帧窗口 + **未命中回落定位读**（不能 return -1 硬判 EOF——MMR 会读预取段之外，一硬判就解码失败）。4K/60fps 的 I 帧可超 2MB 窗口,靠回落读补齐。
  - **MKV(EBML)**:解析 SeekHead→定位 Cues(常在文件尾)→挑**视频轨**的 CuePoint(Cues 会分别索引视频/字幕轨,选到字幕轨→指向字幕块→绿屏/RPS 错)→用 CueRelativePosition 找关键帧块。**合成一个自足小 MKV**(EBML头+Info+Tracks+一个只含该视频关键帧块的 Cluster,时间戳全清零)喂 MMR——文件里只有一帧、在 t=0,MMR 没别的可扫/可解到。关键帧块靠扫块头精确定位(SimpleBlock 看 keyframe 标志 / BlockGroup 看无 ReferenceBlock),**不依赖 CueRelativePosition**(有些文件不写,默认 0 会撞到 Timestamp 元素→绿屏)；**只取那一个块**(不是"关键帧起到簇尾"),否则 Android 硬解会解到后续帧出绿屏。
  - **一次取帧 15s 超时**(MMR 无取消 API,截断容器可能卡住),跑在独立 `videoExecutor` 不占 `twig-thumbs` 池。取时长 1/10 处(开头常黑场/片头)。
  - **解不了的**:10-bit H.264(High 10)、部分 Dolby Vision——**设备硬件/软件都没有对应解码器**(查过 `media_codecs*.xml`),非字节定位能解决,不引入完整软解就只能显示图标。
  - **验证套路**:改容器解析逻辑先在电脑上用真实文件 + `ffprobe`/`ffmpeg` 核对偏移、合成小文件解帧,再移植 Kotlin;Android 端行为(尤其硬解绿屏)只能抓 logcat（`grep twig` 看 `thumbs: mkv/mp4/video` 的 `frame=`/`ms=`/`miss=`）。**注意缓存**:旧版生成的坏图会被缓存(key=md5(名:大小:mtime),与版本无关),改完让用户去设置清缩略图缓存才看得到新结果。
- **AVI 播放 + 缩略图(★ 2026-07-22 一路排查定案,`media3` 1.3.1→1.9.0)**:
  - **内部媒体源 URI 必须带真实扩展名**:非本地来源一直用不带路径的假 URI(如 `"twig://media"`,`media` 会被解析成 authority 不是 path),`DefaultExtractorsFactory` 认不出扩展名就退化成固定顺序挨个 `sniff()`——这个顺序里 **MP3 排在 AVI 前面**,AVI 音轨若恰好被 `Mp3Extractor` 误判命中(常见于 XVID+MP3 老 AVI),整个容器解析全崩,只剩一条音轨、时长算成几百毫秒。改成 `"twig:///media.$ext"`(注意三斜杠,让扩展名落在 path 里)即修。
  - **media3 1.3.1 的 `Mp3Extractor.synchronize` 对 CBR MP3 有已知同步 bug**(1.4.1 修复,upstream commit `b09cea9`,根因 `ConstantBitrateSeeker` 不知道文件末尾位置导致无限往后扫同步字),表现为 `ParserException: Searched too many bytes`。升级到 1.9.0 解决(`jellyfin-media3-ffmpeg-decoder` 版本要跟着对齐,且新版 media3 要求 `compileSdk` ≥ 35,连带把 `compileSdk` 提到 36)。
  - **系统解码器运行时崩溃 ≠ "无解码器"**:遇到过某台测试机上 `c2.android.mp3.decoder` 一启动就 native 崩(`err 0xe/14` 随即被系统 Release),`DefaultRenderersFactory` 的扩展 fallback(`EXTENSION_RENDERER_MODE_ON`)只在"平台没有声明支持的解码器"时才会落到 ffmpeg,对这种"声明支持但一解就炸"的情况无能为力。只能在 `onPlayerError` 里事后兜底,两级降级:先整体切 `EXTENSION_RENDERER_MODE_PREFER` 重建播放器重试一次;如果源文件音轨本身局部损坏(填充垃圾字节的坏样本,ffmpeg 也解不动,表现为 playback 卡住不动触发 `ERROR_CODE_TIMEOUT`),再退一级直接 `setTrackTypeDisabled(TRACK_TYPE_AUDIO, true)` 静音放视频。两级都要整个重建播放器,error 状态下 renderer 已 disable 没法就地换。
  - **AVI 缩略图不能走 MediaMetadataRetriever**:系统 MMR 不支持 AVI 解封装,本地/网络都一样,一直是黑图。播放器修好后确认 media3 的 `AviExtractor` 能正常解出帧,于是 `GlFrameGrabber` 绕开 MMR:起一个不可见的 ExoPlayer 解码到 `SurfaceTexture`,自己写一套最小 EGL/GL 管线把外部 OES 纹理转绘到普通 2D 纹理 FBO 上 `glReadPixels`。**不能直接接 `ImageReader`**:硬解视频吐出来的是设备相关的不透明/YUV 缓冲区,不透过 GL 采样直接读在不少厂商设备上会花屏/格式不兼容(这也是官方要为 `FrameExtractor` 另起一整套 `media3-effect` GL 管线的原因;不想引入那一整包依赖增体积,才手写这条最小路径)。**拿视频宽高建 Surface 不能用 `onVideoSizeChanged`**——`MediaCodecVideoRenderer` 没有输出 Surface 时不会真正启动解码管线,这个回调永远不触发,先有鸡还是先有蛋卡死;改用 `onTracksChanged` 读 Format 宽高,不依赖解码管线是否已经跑起来。时长要解完容器头(甚至 idx1)才知道,不能像 MP4/MKV 那样提前算好目标时间点,改成 `onEvents` 里时长一到手就补一次 `seekTo`。网络来源 seek 到 idx1(常在文件尾)会有一堆小读回程,复用播放器同一个 `BufferedRandomSource` 预读缓存,不然裸 `RandomSource` 在网络上很容易把生成超时预算耗光。
- **真 M2TS(蓝光 BDAV)播放(★ 2026-07-22 一路排查定案,`M2tsStrippingDataSource`/`PgsTsReader`)**:
  - **每包 192 字节,不是 188**:BDAV 封装每个 TS 包前面多 4 字节时间戳前缀,media3 `TsExtractor` 硬编码按 188 步进找同步字节(0x47),对不上直接判"无法识别容器"。**连第一个包前面也带前缀**(同步字节落在 offset=4,不是 0)——踩过一次坑,别想当然认为流从字节 0 就是同步字节。也有工具把普通 188 字节 TS 流存成 .m2ts 后缀,得先探测真实包大小(同步字节在 188 还是 192 步进上连续对齐)再决定要不要剥,不能看后缀就假设。剥的话顺带用 `ProgressiveMediaSource.Factory` 的双参构造显式指定 `TsExtractor`,不吃 `DefaultExtractorsFactory` 的 sniff 顺序。
  - **HDMV 私有 stream_type 和标准注册表撞车**:蓝光/HDMV 命名空间自己复用了几个字节值,和 ATSC/DVB 标准注册表里的含义完全不同,media3 只认标准含义:
    - `0x86` 标准含义是 SCTE-35 插播信令,HDMV 复用给 **DTS-HD Master Audio**——不处理的话,DTS-X/DTS-HD MA 那条最高质量主音轨会被当 SCTE-35 直接吞掉,从没建出音轨。这个 app 只播本地文件用不上真 SCTE-35,直接按 DTS-HD 处理更符合场景。
    - `0x83` 是 **TrueHD/Atmos** 音轨,media3 **整个 `extractor.ts` 包里没有任何 TrueHD/MLP 的 `ElementaryStreamReader`**(不是漏了个 case,是压根没实现过),不处理直接完全没有声音、连音轨都选不出来。蓝光规范要求每条 TrueHD 流都内嵌一份向下兼容的 AC-3 core(5.1)——用 `Ac3Reader` 去扫同步字,跳过中间穿插的 MLP 帧、只挑真正的 AC-3 核心帧解出来,放弃 Atmos 沉浸声道和 TrueHD 无损换能出声。
    - `0x90` 是 **HDMV PGS**(图形位图字幕),media3 同样没写 TS 层切样器(只支持了 DVB 字幕那一套不同标准)。PGS 位图解码本身 media3 有(`PgsParser`,MKV 走它),缺的是"怎么把 PES 包切成它认的样本"。新写的 `PgsTsReader`:**一个 PES 包正好装一个完整 PGS segment**,时间戳由 PES 包自己的 PTS 给(不像离线 .sup 文件那样每个 segment 前面还带冗余的 'PG' magic+PTS+DTS,那是 .sup 脱离容器自己发明的);按 PES 包累积,直到看到 segment_type=END(0x80)才把攒的这一串整体吐出一个样本,和 MKV 一个 block 一个 Display Set 的粒度对齐。
    - 上述三个自定义 stream_type 都通过 `BluRayTsPayloadReaderFactory` 委托给 `DefaultTsPayloadReaderFactory`(开 `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS`)实现,不是全量重写。
    - 判断某个 stream_type 是否被官方描述符覆盖前先看 `TsExtractor` 源码里 `readEsInfo` 那段——它只在原始 PMT stream_type 是 `0x05`/`0x06`(通用占位)时才会用 registration_descriptor 覆盖,像 `0x83` 这种具体值不受影响,不用担心和这段逻辑打架。
  - **TsExtractor 新版 API 变了**:`FLAG_EMIT_RAW_SUBTITLE_DATA` + `SubtitleParser.Factory.UNSUPPORTED` 这套旧写法在新版 `TextRenderer` 上直接抛 `IllegalStateException: Legacy decoding is disabled`——旧的"原始样本 + legacy SubtitleDecoder"整条路已经不支持了,必须不带这个 flag、传真正的 `DefaultSubtitleParserFactory`,在抽取阶段就把样本转成 `application/x-media3-cues`。
  - **media3 内置 reader 对畸形输入没有防御,会崩到把整个播放器拖垮**:实测撞过 `SpliceInfoDecoder`(畸形 SCTE-35 越界,已经靠关掉 metadata 轨规避)和 `Ac3Util.parseAc3SyncframeInfo`(某文件 AC3 帧头 `frmsizecod` 字段超出标准范围,数组越界,`ERROR_CODE_IO_UNSPECIFIED`,发生在**提取线程**不是解码线程,disable 音轨救不了)。`SafeTsPayloadReader` 包一层防御性 try-catch,统一包在 `BluRayTsPayloadReaderFactory` 出口(无论我们自己的 reader 还是委托给内置工厂的都受保护):吞掉异常丢这一整包、调 `seek()` 复位内部状态继续解后面的包。
  - **自己写的 `DataSource` 千万别在 `read()` 热路径里现分配**:`M2tsStrippingDataSource` 每跳一次 4 字节前缀就 `ByteArray(4)`,量大时(seek 探测/大量顺序读)分配速率能把 GC 打爆——实测持续一整分钟 "Waiting for a blocking GC",这才是"m2ts seek 慢、缩略图超时"的真正根因,跟网络速度无关。复用成员变量搞定。顺手把 `read()` 改成单次调用内部循环尽量填满调用方要的 `length`(192 字节一包的话,不这么改每次只能吐 188 字节,逼调用方连续调几百次)。
  - **极高码率超大文件(30GB+、~30Mbps)的 seek 仍然慢**:GC 问题修完后确认 seek 确实只落地一次(没有反复二分搜索重进),但从落地到真正渲染出首帧要几十秒、期间持续读大量数据。`DefaultLoadControl` 缓冲目标只需 2 秒(`bufferForPlaybackAfterRebufferMs`),不是缓冲策略过保守。真正原因在 media3 官方 `TsBinarySearchSeeker` 落地后找到能渲染的关键帧这一步本身慢——TS 格式没有 MP4/MKV 那种精确索引,只能靠 PCR 时间戳插值+二分逼近。要根治得 fork 这部分逻辑,评估对这种极端文件收益有限,暂不做。
  - **验证套路**:手工核对某个 stream_type 的真实含义/描述符,别用 `ffprobe` 的高层 `codec_tag_string`(它会把结果映射成人类可读名字,掩盖真实 stream_type 数值,之前被 "AC-3" 这个字符串误导过)——自己写 Python 解析 PAT→PMT→ES loop,直接读 `stream_type` 原始字节和 descriptor(尤其 tag=0x05 registration_descriptor 的 format_identifier)才是地面真相。反混淆播放器崩溃堆栈别手工按 mapping.txt 行号查(会撞到不同类复用相同混淆短名的假象,比如看着像是 `AES256Options`/`OpenJSSEPlatform` 这种完全不相关的类,其实是没跑真正的 retrace、单看行号对应错了方法)——用 SDK 自带的 `"$ANDROID_HOME"/tools/proguard/bin/retrace.sh` 配 `app/build/outputs/mapping/release/mapping.txt` 才能正确按方法级别反解。
- **压缩打包(`ArchiveWriter`,2026-08-01)**:源/目标都只经 `openInput()/openOutput()`,与
  `CopyEngine` 同一套路,所以「SMB 目录 → 本地 zip」这类组合零成本。几个要点:
  - **7z 必须可定位写**(头部要回写),只能先写本地文件——目标非本地时写 cacheDir 再整包搬过去;
    zip 是纯顺序写,直接串到目标 openOutput 上。
  - **别用 `SevenZOutputFile(File)`**:它内部 `Files.newByteChannel`(java.nio.file,API 26+)
    而 minSdk 是 24;自己传 `RandomAccessFile(f,"rw").channel`。同理条目时间戳走 `FileTime`,
    用 runCatching 包住。RandomAccessFile 不截断,写本地目标前要先 delete 掉旧文件。
  - **LZMA2 字典别用默认 8MB**:编码器要 ~11 倍字典的内存(~90MB Java 堆),手机上压大文件
    容易 OOM;显式设 4MB(`ArchiveWriter.LZMA2`),压缩率只差一点点。
  - 进度/取消复用 `CopyEngine.ProgressListener`/`Cancelled`,读写流水线复用 `CopyEngine.pipe`
    (从原 `pump` 提出来的),UI 那边复制与压缩因此共用同一个 `PaneFragment.ProgressBox`。
- **WiFi 共享「所有来源」模式返工(★ 2026-08-10,0.96.0 → 0.97.0)**:第一版这个模式
  **一条单测都没有**(用例全跑在「指定目录」上),三个错误假设一路带到真机——页面上多出
  `Archive`/`7z archive`/`RAR archive`/`Share` 四个点进去必报错的条目、「应用」能展开却
  下载不了、中文目录一律打不开。教训是**新增一个模式就要有覆盖那个模式的测试**,
  别指望"另一个模式测过了"能顺带保住它。三条根因:
  - **`FsRegistry.all()` ≠ 可浏览的根**。zip/7z/rar 是挂在宿主文件上的容器,
    `root()` 直接抛 `Archive must be mounted via rootOf(archive)`;SAF 要先选目录树;
    `share` 是接 content:// 的中转。判据就一条:`root()` 能不能正常返回,不能就不列。
    另外 `LocalFileSystem` 的根是 `/`(用户看到的是 `acct`/`apex` 那堆),要另拆成
    「内部存储」(`Environment.getExternalStorageDirectory()`)与「根目录」两个来源。
  - **不能假设"URL 路径段拼起来 = `XFile.path`"**。`AppsFileSystem` 的 path 是包名
    `/user/com.tencent.mm` 而 `XFile.name` 是 `微信 8.0.1.apk`,SAF 的 path 是整条
    document URI——URL 里只可能出现显示名,拼回去 resolve 必然失败。`ShareRoot.resolve`
    现在**从来源根逐级列目录、按 name 找子项**,拿到的是它真正的 XFile;代价由一个
    32 条 / 5s 的 `DirCache` 兜住(浏览本来就是一级级点下去,祖先全在缓存里)。
  - **拼链接必须用没解码的路径**。拿解码后的 `req.path` 当前缀、再把子项名字
    `encodeSegment` 一遍,拼出的是"解码前缀 + 编码末段"的半成品 URL,目录名一带中文或
    空格整棵子树就断链。`HttpRequest` 因此同时留 `path`(**寻址**用)与 `rawPath`
    (**拼链接**用),`ShareServerTest.中文目录的链接能一路点进去` 是照着页面里给的 href
    去请求的,前后缀编码不一致就必然对不上。
  - 顺带:目录列不动时**不要回光秃秃的 500**——页面照常渲染,把异常消息摆在顶部错误条。
    「打不开」和「打开了但是空的」在浏览器上长得一模一样,而原因是唯一能往下查的线索。
- **共享页的样式改动要真的渲染出来看(★ 2026-08-10)**:断言测不出视觉问题。同一轮里
  踩了两个,都是"HTML 完全正确、CSS 也没报错,但屏幕上什么都没有":
  - **图标是 fill 渲染的,路径必须围出面积**。下载图标的竖杆写成 `M12 3v10.2`
    (零宽度线段),只画出了箭头尖,行尾看着像个孤零零的 `˅`。别照搬 stroke 版的路径。
  - **`.act` 这种小图标按钮必须显式 `padding:0`**:通用 `button{padding:7px 14px}` 照样
    命中它,29px 的方块里塞 28px 内边距,里面的 svg 被 flex 压成 **1px 宽**。表现为
    "改名/删除按钮凭空消失,只有下载还在"——因为下载那个是 `<a>` 不吃 button 的内边距。
  - 同理 `[hidden]` 要写 `!important`,否则被 `button{display:inline-flex}` 盖掉,
    `<button hidden>` 一直露在工具栏上。
  - 走查方式见 `DumpPageTest`:`TWIG_DUMP_DIR=/tmp/page ./gradlew :app:testReleaseUnitTest
    --tests "*DumpPageTest"` 出 HTML,再用 Playwright 截图 + 查 `getBoundingClientRect`
    (那个 1px 宽就是这么量出来的,肉眼只能看出"没显示")。
- **WiFi 共享的两个 bug 都是单测抓出来的(★ 2026-08-10,`ShareServerTest`/`MultipartTest`)**
  ——这类 bug 在真机上表现为"下载的文件坏了""明明是复制,源文件却没了",事后几乎无从归因,
  而一发报文就能钉死。改这块**先跑 `./gradlew :app:testReleaseUnitTest --tests "com.twig.app.share.*"`**:
  - **multipart 边界匹配必须校验后随两字节**。只匹配 `\r\n--boundary` 的话,正文里恰好
    出现这串前缀(上传的正是一份 multipart 报文,或者二进制文件里就带着这段字节)会被当成
    边界,文件从那里被腰斩,而且断口两侧都是合法数据。RFC 2046 规定分隔符后只能跟 `--`
    或 CRLF,多看两个字节就排除了误判(`Multipart.isDelimAt`)。
  - **WebDAV 的 COPY/MOVE 不能直接用 `CopyEngine.transfer(listOf(src), destDir, move)`**:
    那个接口的语义是"复制进这个目录、保持原名",改名只能事后补一次 rename。而 COPY 完全
    允许目标就在源所在的目录里(`COPY /a.txt → /b.txt`),那时它会先把文件复制到自己身上、
    再把**源**改成新名字,源文件当场消失。现在走 `ShareHandler.transferAs`:文件直接流式
    写到确切的目标条目,目录先 mkdir 再搬子项。另外**"源与目标同路径"的 403 判定必须排在
    "覆盖已存在目标"的 delete 之前**,否则那句 delete 删掉的正是源文件。
- **往 zip 里加文件走追加,不是整包重写(★ 2026-08-11,`ZipFileSystem.appendEntry`)**:
  老实现每加一个条目都 `rewrite()` 整包,而且是**解压再重新压缩**
  (`getInputStream().copyTo(zos)`);`CopyEngine` 又是一个文件调一次 `openOutput`,
  于是往 1GB 的包里拖 10 个文件 = 嚼 10GB。实测 100MB 包加一个小文件:
  **重写 1875ms,追加 1ms**。这跟条目压没压缩**无关**——追加根本不读旧数据。
  - **新条目接在文件末尾,不覆盖旧中央目录的位置**。覆盖能省下垃圾字节,但不可回滚:
    写到一半进程被杀,旧中央目录没了,整包报废。接在末尾则原有字节全程不变,出错
    `setLength` 截回原长度即完好如初。代价是每次追加把旧中央目录留成中间的垃圾
    (典型几 KB),删除/改名那些整包重写的操作会顺手清干净。
  - **旧中央目录的记录原样字节复制**:旧条目没挪窝,记录里的 localHeaderOffset 全都
    还成立,不用逐条重新解析(`ZipWriter.Base`)。EOCD 必须重写(偏移/条目数变了)。
  - **退回整包重写的情况**:包内已有同名条目(覆盖要把旧的摘掉)、远程宿主(不可定位写)、
    尾部结构读不懂。`atomicOverwrite()` 仍是 true —— 它问的是"覆盖自身会不会写出残片",
    而覆盖走的正是 rewrite。
  - 测试判据是**「原包前 N 字节逐字节相等」**(`ZipAppendTest`),不是卡耗时:
    时间断言在 CI 上必然时好时坏,而字节相等直接证明"没重写"。
  - **界面上的入口是「展开压缩包 = 选中包根」**(`PaneViewModelArchiveTargetTest`)。
    树上压缩包那一行的 XFile 是**宿主文件**(`isDir=false`),而 `toggleFile` 里
    `if (n.file.isDir) currentDir = n.file` 管不着它 —— 于是绿框停在包那一行、
    粘贴目标却还是外面的目录,展开了也复制不进去(只有再点包内**子目录**才行,
    包根和没有子目录的包完全没入口)。现在 `listChildren` 把挂载得到的包根经
    `Listing.mountRoot` 带出来、记进 `mountRoots`,展开(含吃缓存那条分支)即设为
    `currentDir`,收起时退回包所在目录。★ **别把包根塞进 `keyFile`**:那张表存的是
    "重新列举需要的 XFile",对压缩包必须是宿主文件本身(刷新要走归档分支去物化、解密码)。
- **树上两行同 key = 有一处展开是空的(★ 2026-08-17,`FileNode.keyPrefix`)**:行的 key
  (`f:<scheme>:<path>`)是 `submitList` 里 DiffUtil 认行的**唯一**依据,重复了它就会
  认错行。撞得最狠的一处:别的 App「用 Twig 打开」一个压缩包 → `mountExternal` 挂到
  树顶,而这个包**在存储树里原本那一行也还在**(只要它所在目录展开着)。症状是
  「同一个包,一处展开好好的,另一处展开却是空的」,而两处内容其实共用同一份
  `children[key]` —— 所以看着像"读不出来",实际是渲染认错了行。
  - 修法是给外部挂载那棵子树整体加 key 前缀(`x:`),**子项递归时必须把前缀带下去**,
    否则包内条目又和原位置那棵撞上。
  - **附属行(属性卡片/搜索结果)反而不能带前缀**:`addAttachments` 用不带前缀的
    `fileKey` 去重(`attachedKeys`),同一个文件只在树上第一处挂一份,本来就不会重复。
  - 复现测试要**先 `mountExternal` 再把那个目录重新展开**(`accordionExpand` 会在挂载时
    把它收起来,不重新展开根本撞不上)—— 第一版测试就是这么"通过"的。
- **加密压缩包(★ 2026-08-11,`ZipAes`/`ZipCrypto`/`ZipWriter`)**:7z 与 rar 的加解密
  是库自带的(`SevenZFile.Builder.setPassword` / `Archive(File, password)`,连
  `SevenZOutputFile(channel, char[])` 写加密包也现成),**zip 那一整套是手写的**——
  commons-compress 和 `java.util.zip` 对加密 zip **连读都不支持**,而 zip4j 是几百 KB
  的 APK 增量。几条定死的东西:
  - **AES-CTR 的计数器是小端、从 1 开始**,JDK 的 `AES/CTR` 是大端 —— 直接用解出来
    全是乱码。用 `AES/ECB` 加密计数块自己异或(`ZipAes.Ctr`)。HMAC 认证码对**密文**算,
    不是明文;写出一律 AE-2(CRC 字段填 0,不泄露明文校验值)。
  - **加密条目不能走 commons-compress 的 `getInputStream`**(直接抛
    UnsupportedZipFeature),按中央目录里的本地头偏移自己解析、定位到数据首字节读。
    0x9901 extra **取本地头里的那一份**,不依赖库对未知 extra 字段的处理方式。
    同理 `storedSlice` 必须排除加密条目,否则"STORED 直接切片"读出来的是密文。
  - **判密码错要和判包损坏分开**。zip 有现成的校验值(AES 2 字节 pwVerify /
    ZipCrypto 加密头末字节),一读就知道;7z 没有,只能整读第一个非空条目——它的 CRC
    要读到流末才校验,只读个开头判不出来(密码错时通常轮不到 CRC,LZMA 解码先炸)。
    `SevenZFileSystem.isChecksumIssue` 那条判据**只在已经给过密码的前提下**才敢用。
  - **`ZipWriter` 是纯顺序写**(本地头 → 数据 → 数据描述符,最后中央目录 + EOCD),
    所以和 `ZipOutputStream` 一样能直接串到远程目标的 `openOutput()` 上。zip64
    **只能在写本地头之前决定**(要预留 extra 位置),靠调用方给的 sizeHint;hint 不准
    而实际写超 4GB 会当场抛错,不写出坏包。
  - **加密包一律只读**(`writable()` 带上 `!needsPassword`):整包重写要"全解密再全
    加密",一次失手就是整包数据损坏,收益远不抵风险。
  - **★ 探测加密必须排在 `rootOf()` 之后**(2026-08-17):归档的字节是经
    `FsRegistry.of(host).openRandom(host)` 拿的,而"host 是谁"是 `rootOf` **挂载那一刻
    才登记**的(`ArchiveFileSystem.hosts`)。`needsPassword()` 要扫一遍中央目录,排在
    `rootOf` 前面的话,`hostOf()` 会把远程路径当本地文件去开 —— **本地包一切正常,
    远程包(SMB/WebDAV/S3…)读不到字节**,而 `firstEncrypted` 的 runCatching 又把它
    吞成"不需要密码":远程加密包于是**不弹密码框**,要点开里面的文件才报错。
    不报错但答案是错的,最难查。见 `RemoteArchiveMountTest`(顺带补上了"非本地宿主
    的压缩包"这个一直空白的测试面)。
  - **验证套路**:往返测试测不出互操作性。样本包是 `7z` / `zip` 命令行真造出来的
    (base64 内嵌在 `ArchivePasswordTest`),写出来的包反过来用
    `TWIG_DUMP_DIR=... ./gradlew :fs-archive:test --tests "*ArchiveEncryptWriteTest*"`
    导出,再 `7z t -psecret` 验一遍。密码从哪来、错了怎么办、保存的还认不认这些控制流
    在 `PaneViewModelArchivePasswordTest`(Robolectric)里。
- **S3(★ 2026-08-16,`S3FileSystem`/`Sigv4`)**:零新增依赖——HTTP 用已有的 OkHttp,
  XML 用 WebDAV 那条 `DocumentBuilderFactory`,SigV4 用 JDK 自带的 HMAC-SHA256 手写。
  **绝不要引 AWS SDK for Java**:光 s3 模块连着依赖十几 MB,比整个 APK 还大,而实际
  用到的只是几个 REST 调用。几条踩过/定死的东西:
  - **★ 请求路径和响应对象名是两个不同的编码空间,别当成一套**(2026-08-16 拿真
    MinIO 打出来才发现,mock 测试一路绿):
    - **请求路径 / canonical URI = RFC 3986**(unreserved 集合 `A-Za-z0-9-_.~`)。
      不能用 `URLEncoder`:它把空格编成 `+`、`~` 也编、`*` 反而不编。见 `Sigv4.uriEncode`。
    - **`encoding-type=url` 的响应里的 Key/Prefix/Delimiter = form 编码**
      (`application/x-www-form-urlencoded`)。实测原始报文:
      `my notes.txt → my+notes.txt`(空格是 `+`,**不是** `%20`)、
      `a+b.txt → a%2Bb.txt`(字面加号被转义,所以 `+` 无歧义)、
      `100% done #1.txt → 100%25+done+%231.txt`。因此解码要**先**把 `+` 换成空格
      **再**解 `%XX`;反了的话 `%2B` 解出来的加号会被当空格,`a+b.txt` 变 `a b.txt`,
      **列表看着没毛病、一点开就 404**。见 `S3FileSystem.decodeKey`。
    - **`NextContinuationToken` 不受 `encoding-type` 影响**,是 opaque 值(实测里面的
      `=` 原样返回)。拿它走对象名那套 form 解码,会把 base64 里的 `+` 变成空格、
      分页当场断掉——一个字都不解,原样带回给服务端(发送时正常 uriEncode)。
    - 教训:**这条只有真服务端能验出来**。mock 测试断言的是我们自己对报文的想象,
      编码方向猜反了照样全绿;`S3LiveTest`(打真 MinIO)第一次跑就把它抓了出来。
  - **签名算的字符串必须与发出去的字节一模一样**。URL 用 `encodedPath`/`encodedQuery`
    自己编码到底、不让 okhttp 按它自己的规则再改一遍;canonical query 与真实 query
    共用 `Sigv4.canonicalQuery` 的同一份输出。差一个字符就是 SignatureDoesNotMatch,
    而服务端不会告诉你差在哪——所以 `Sigv4Test` **断言的是中间产物**(canonical request
    与 string to sign),官方文档印出来的那串 canonical request SHA-256 对上了,
    就说明前半程规范化一个字节没差。
  - **S3 没有目录**,树上的目录是两件东西凑的:`delimiter=/` 让服务端折叠出
    `CommonPrefixes`,再用以 `/` 结尾的空对象当占位符(否则**没有对象的空目录根本不存在**,
    新建完刷新就没了)。列目录时这些占位符要滤掉,不然会冒出 0 字节的怪文件。
  - **上传走分片、全程不落盘**(WebDAV 那种"先落临时文件再整包 PUT"在大文件上要占一份
    等大的存储)。不足一片(8 MiB)的仍走单次 PUT,省掉 initiate/complete 两趟往返。
    失败必须 `AbortMultipartUpload`——否则那些片一直躺在桶里按存储计费,而且在控制台
    的对象列表里**看不见**。分片的 payload hash 用 `UNSIGNED-PAYLOAD`,为签名把 8 MiB
    再整读一遍算 SHA-256 是白烧 CPU,S3 本来就接受这个值。
  - **同端点内的移动/改名走服务端 `CopyObject`**(`moveWithin` + `rename`),几十 GB 的
    对象也是一次请求;不覆写 `moveWithin` 的话 `CopyEngine` 会老老实实下载再上传一遍。
  - **CopyObject 与 CompleteMultipartUpload 会先回 200 再流式发结果**,失败信息藏在
    响应体的 `<Error>` 里而不是状态码上。只看 `isSuccessful` 会把失败当成功。
  - **path-style 下桶级操作是 `/bucket` 不带尾斜杠**(带了在部分兼容实现上被当成
    "名为空串的对象")。virtual-host 风格则是桶名进主机名、路径里不再出现它。
  - **桶名可留空**:那时根目录列出账号下所有桶。但很多凭证只被授权了单个桶、没有
    `ListAllMyBuckets` 权限,那种情况必须填桶名,否则开局就是一个 AccessDenied。
    反过来,填了桶名时**根就是那个桶,不允许删**——删它等于把整条连接的内容清空,
    而用户看到的只是自己按了删除键的那一行。
  - `openRandom` 是 HTTP Range,`randomAccessEfficient()==true`,网络视频缩略图与
    播放器 seek 一并受益;那套流池逻辑与 WebDAV 抽成了共用的 `HttpRangeSource`。
  - **测试分两层,缺一不可**:`S3FileSystemTest`(mockwebserver,报文级)钉住我们发出
    的字节;`S3LiveTest`(打真服务端,**没配环境变量就整个跳过**,`./gradlew test` 照常绿)
    钉住服务端真的认。上面那条编码坑就是后者抓到的——前者证明不了签名能被接受,也
    证明不了我们对响应格式的理解没跑偏。本地起一个 MinIO 就能跑:
    ```
    docker run -p 9000:9000 -e MINIO_ROOT_USER=<key> -e MINIO_ROOT_PASSWORD=<secret> \
      minio/minio server /data          # 再建一个桶
    TWIG_S3_ENDPOINT=http://127.0.0.1:9000 TWIG_S3_KEY=<key> \
    TWIG_S3_SECRET=<secret> TWIG_S3_BUCKET=<bucket> \
      ./gradlew :fs-network:test --tests "*S3LiveTest*"
    ```
- **特权访问 root / Shizuku(★ 2026-08-16,`fs-local/priv/` + `com.twig.app.Privileged`)**:
  - **不是新 scheme,是 `LocalFileSystem` 的回落**(`LocalFileSystem.elevation`,与
    `changed` 钩子同一套可插拔套路)。用户要的是**「根目录」那棵树点得进去**,不是旁边
    多一棵一模一样的特权树;走同一个 `file` scheme,收藏、跨来源复制、缩略图、搜索
    全部零改动直接受益。**只在普通 API 失败的那一步才提权**——能自己读的一律不走,
    每条命令都要 fork 进程,拿它列 `/sdcard` 白白慢几十倍。
  - **root 与 Shizuku 在 `PrivilegedLauncher` 这一层就合流**:两者给的都是"一个带
    stdin/stdout 的特权进程",往下共用同一个 `PrivilegedShell` + `PrivilegedFs`。
    差别只有 uid(su = 0;Shizuku 通常是 shell 2000,**但它自己若是用 root 启动的就是
    0**——状态文案要按实际读回来的 uid 说,别按用户选的模式写死)。
  - **长驻一个 shell,不是每次 `su -c`**:每次 exec 都过一遍授权检查,某些 Magisk 版本
    还会重复弹框。命令边界靠命令后 `echo <唯一marker> $?`——长驻 shell 永远不给 EOF。
    **但二进制流必须另起一次性进程**(`cat`),它的 EOF 才是文件结尾:marker 那套对
    文件内容毫无意义(内容里可能就有 marker、可能一个换行都没有、还不能按文本解码)。
  - **★ stderr 排空线程绝不能和 `exec` 用同一把锁**(`errLock`):`exec` 是握着锁等命令
    跑完的,同锁则排空线程卡住 → 没人读 stderr → 管道 64KB 写满 → 命令永远不结束 →
    只能等超时。`find /` 满屏 Permission denied 就够触发。回归测试
    `a command flooding stderr still completes`。
  - **列目录用 `find -H <dir> -maxdepth 1 -mindepth 1 -exec stat -c '%f|%s|%Y|%n' {} +`**,
    不用 `ls -l`(时间格式随 locale 变、名字带空格难切、符号链接的 ` -> ` 混在名字里)。
    ★ **`-H` 不是可选的**:find 默认不跟随作为起点的符号链接,`-mindepth 1` 又把唯一
    找到的那条丢掉,于是 `/sdcard`(指向 `/storage/emulated/0` 的链接)整个列成空,
    **而且退出码是 0** —— 长得像"空目录"而不是"失败"。`%n` 放最后:文件名可能含分隔符,
    解析只切前三段、剩下整段当名字。符号链接要再跑一次 `stat -L` 才知道指向的是不是
    目录(否则树上不给展开箭头),按 200 个一批分块免得撞 ARG_MAX。
  - **`exists()` 是批量复制的热路径**,本地返回 false 就去问 shell 的话,往 `/sdcard`
    拷一千个文件就多一千次往返。`maybeHidden()` 先用 `parent.canRead()`(一次 access(2),
    不起进程)筛一道:父目录读得动,"不存在"就是真的。
  - **`touch -t` 按本地时区解释**,时间戳格式化成 UTC 会让每个文件的 mtime 整体偏移。
  - **Shizuku 的 `newProcess` 在 13.x 是 private 的**(上游想把人赶去 `bindUserService`),
    只能反射调 → R8 必须 keep `rikka.shizuku.**` 的方法名,否则 100% NoSuchMethod。
    manifest 三件套缺一不可:`ShizukuProvider`(exported=true + multiprocess=false)、
    `moe.shizuku.manager.permission.API_V23`、`<queries>` 里的
    `moe.shizuku.privileged.api` ——**最后这条不写,targetSdk 30+ 下包可见性会让
    「明明装了却说没装」**。binder 是异步到的,恢复上次选择要用
    `addBinderReceivedListenerSticky`,不能启动就查。
  - **授权只能在前台由用户点出来**:Android 10+ 挡掉后台启动 Activity,Magisk 的授权框
    从后台弹不出来只会退化成一条通知,表现是"点了没反应"。
  - 想要真随机读/免解析的话,Shizuku 那侧的官方路线是 `bindUserService`(把我们的代码
    跑进 shell 进程,能传 ParcelFileDescriptor 回来);但它**对 root 完全不适用**,
    采用就等于维护两套毫不相干的后端,现在这层抽象也就没了。评估后没做。
  - **★ 「设置里那一行点开一个选项都没有」(2026-08-16 真机上栽的)**:对话框同时调了
    `setMessage()`(说明文字)和 `setSingleChoiceItems()`。**AlertDialog 的内容面板只放得下
    一样东西,两个都设时 message 赢,列表整个不挂进视图树**。说明要与选项并存只能走
    `setCustomTitle()`(或自定义 view),内容面板留给列表。
    - 这一个 bug 制造了**三个看着不相干的症状**:选不了 → 从不调 `requestPermission`
      → Twig 在 Shizuku 的应用列表里也永远不出现。当时先去查了清单声明、服务端 AIDL、
      R8 mapping,全是好的 —— 方向从一开始就错了,该做的是**把界面渲染出来看**
      (与「共享页的样式改动要真的渲染出来看」同一条教训)。
    - **回归测试的判据必须是「有没有挂进视图树」,不是 `listView != null`**:
      AlertController 在有 message 时**照样把 ListView 构造出来**、adapter 里几行俱全,
      只是不往面板里加。按非空断言的话,测试在出 bug 的那版上照样绿(实测反转验证过)。
      见 `PrivilegedDialogTest`。
  - **应用自己 `File("/").listFiles()` 在新系统上返回 null**(实测 Android 16):
    所以「根目录」那个节点本来就是空的,提权回落正是它的用武之地 —— 别以为
    `/` 谁都读得动而把它当成"白白走了特权"。
  - **诊断日志要分清「非零退出 = 出事」和「非零退出 = 答案」**(`exec(quiet=)`):
    `[ -e path ]` 文件不存在就返回 1,而 `exists()` 是批量复制的热路径,每个不存在的
    目标刷一条,logcat 当场淹掉真正的错误;`/` 下总有几条断链(`/adb_keys`、`/d`),
    整批 `stat -L` 必然退出 1 而好的条目照常输出。这些一律 `quiet = true`。
  - 诊断串里**别直接印 `checkSelfPermission()` 的数值**:`PERMISSION_GRANTED` 恰好是
    **0**,"granted=0" 读起来像"没授权",意思正相反。
  - **特权终端(`PrivShell`,2026-08-16)**:本地终端本来就有一个 termux
    `JNI.createSubprocess` 分配的**真 PTY**,所以"以特权身份开终端"= 只换跑什么,
    resize/作业控制/全屏程序全部照旧,**不需要桥接线程**(那是 SSH 才要的)。
    - **root**:把 shell 二进制从 `/system/bin/sh` 换成 `su`,完事。不传 `-p` ——
      各家 su(Magisk/KernelSU/APatch)对它支持不一,不认的直接报错退出。
    - **Shizuku**:走 rish **这条路是死的**(2026-08-16 定案,代码留在 `PrivShell`
      但 `available()` 对 SHIZUKU 恒返回 false)。rish 的 dex 只能落到应用私有目录,
      而 **`untrusted_app` 不允许执行/加载 `app_data_file` 标签的文件**(W^X 的
      SELinux 落地形式),`app_process` 直接 `ClassNotFoundException`。
      ★ **定位方法**:同一份 dex(md5 相同)、同一套环境、同一条命令,分别在
      `u:r:su:s0`(adb root)、`u:r:runas_app:s0`(debug 包 + `run-as`,**uid 与应用
      完全相同**)、`u:r:untrusted_app:s0`(应用自己)下跑——前两个正常加载,只有第三个
      失败,变量因此收敛到**只剩 SELinux 域**。Termux 能用 rish 是因为它常年停在
      targetSdk 28。**现方案改走 `bindUserService`**,见下。
    - `librish.so` 里有 `grantpt`/`unlockpt`/`setsid`/`ioctl` —— 它**在特权侧自己
      分配真 PTY**,管道只当传输通道,与 SSH 同构。所以"Shizuku 只能拿到管道、
      做不成真终端"这个判断是**错的**;判这类问题别靠推理,**把 so 的符号 dump 出来看**。
    - **★ 现方案:`bindUserService` + 特权侧 forkpty**(2026-08-17 实测跑通,
      `priv/TwigPrivService` + `priv/PrivService` + `cpp/twigpty.c`)。
      关键就一句:**特权进程加载的是谁的代码**。rish 要我们把 Shizuku 的 dex 落到
      自己的私有目录再加载(被挡);`bindUserService` 是 Shizuku 用 app_process
      拉起**我们自己的 APK**(本来就在 `/data/app`,标签 `apk_data_file`),天然合规。
      链路:app --bind--> 特权进程(uid 0/2000)--forkpty--> `sh`,主设备端 fd 经
      `ParcelFileDescriptor` 传回 app。拿到 fd 之后与 SSH 会话同构(注入模拟器 +
      两条桥接线程),复用现成代码。
      - **自己写 `libtwigpty.so`(arm64 仅 7KB),不复用 termux 的 `JNI`**:后者是
        包内私有、静态块里写死 `loadLibrary("termux")`,而特权进程里
        `nativeLibraryDir` 是**空目录**(`.so` 不解压地存在 APK 内,
        `extractNativeLibs=false` 是现代默认),这一步必然失败 —— 而**静态初始化块
        抛过异常的类就永久损坏,没有重试机会**。我们这个类**没有静态块**,
        加载路径由调用方给,特权进程把 `.so` 从 APK 抠到 `/data/local/tmp/twig`
        再 `System.load` 绝对路径。
      - ★ **抠出来的 `.so` 一刻都不能是可写的**。第一版落地就是 `-rwxrwxrwx`
        (root 的 umask + `setExecutable(true,false)`),而它随后要被加载进一个
        **root 进程** —— 那是一条实打实的本地提权路径,不只是系统警告的
        `Attempt to load writable file ... will throw on a future Android version`。
        写法必须是**写临时名 → 先收权限 → 原子改名**:直接写目标文件的话,从创建到
        chmod 之间有一个全局可写的窗口。收权限收成"任何人不可写"而**不是"仅属主"**
        —— Shizuku 可能这次以 root、下次以 shell 起来,属主换了就读不到自己上一轮
        留下的文件。(删除靠**目录**的写权限,与文件自身权限位无关,所以只读文件也删得掉。)
      - ★ **`daemon(false)` 不保证进程会死**。app 被升级/杀死时 binder 已经断开,
        Shizuku 的 `destroy()` **根本送不到**,于是留下一个 root 进程常驻
        (2026-08-17 实测:Shizuku 日志写着 "Remove service record ... all
        connections are gone",而 `ps` 里 `com.twig.app:priv` 仍然活着 ——
        **"记录被移除"不等于"进程结束"**,别照着日志想当然)。
        解法是 app 连上就 `attach()` 一个只为存活而存在的 `Binder`,助手
        `linkToDeath` 到它身上,app 一没就 `System.exit(0)`。
      - **把真实 fd 反射注进 `mTerminalFileDescriptor`**:termux 的
        `TerminalSession.updateSize` 本来就拿这个字段去 `setPtyWindowSize`,
        注进去之后**窗口同步一行都不用写**,横竖屏/键盘弹收自动带 SIGWINCH。
        SSH 那边要探测+记忆,是因为那头没有本地 fd 可用。
      - **fork 之后只能做 async-signal-safe 的事**:所有字符串在 fork 前就转成 C
        数组(JNI 调用可能拿一把别的线程在 fork 那一刻正持有的锁)。并且要**重置
        信号掩码与 SIGPIPE/SIGINT**——JVM 屏蔽了一堆信号,shell 继承过去之后
        Ctrl+C 看着像死了。
      - `PrivService` **缓存连接**,多条会话共用一个特权进程(拉起一次要几百毫秒);
        `daemon(false)` 让它跟着 app 走,不在用户机上留常驻进程;改了
        `TwigPrivService` 的行为要把 `VERSION` +1,否则可能连到旧代码起的进程。
      - 收尾有两条正常路径,都不是故障:shell 自己退出 → 主设备端读抛
        **EIO**;用户结束会话 → `close()` 打断阻塞中的读。
    - **`localEnv()` 是整套替换环境的,不是追加** —— 少了 `ANDROID_ROOT`/`ANDROID_DATA`/
      `ANDROID_ART_ROOT`/`BOOTCLASSPATH`,**任何要启动 ART 的东西都零输出秒退**,
      屏幕上只剩 termux 那句 "[Process completed]",看着像"命令不存在"。受影响的
      远不止 rish:`am`/`pm`/`dumpsys`/`settings` 全是 `app_process` 的包装脚本。
    - **启动就死的本地会话不要自动收掉**:失败原因正打在那块屏幕上,直接收掉的话
      界面一闪回文件列表,唯一线索也没了。现在 3 秒内退出的本地会话保留(见
      `QUICK_EXIT_MS`)。
    - ★ **Android 14+ 不允许 `app_process` 加载可写的 dex**,落地后必须去掉写位
      (`setWritable(false,false)`);也因此只能放应用私有目录 —— 别处改不动权限位。
      `app_process` 是系统二进制,不受 W^X 限制;dex 是被它读的数据,不是 execve 的目标。
    - `RISH_APPLICATION_ID` 要填**持有 Shizuku 授权的包**,也就是我们自己
      (Shizuku 按 uid 授权,而跑 rish 的正是那个 uid)。
    - **特权终端必须单独一项菜单、身份写在文案里**,不能把原来那条「在此打开终端」
      悄悄换成 root:用户以为在应用 uid 下试命令,实际一条 `rm` 就是全盘。
      起不来时也直说是哪种身份起不来,**不静默退回普通 shell**。
  - 测试:`PrivilegedShellTest` / `LocalFileSystemElevationTest` 拿 **真 `/bin/sh`** 当
    launcher 跑(su 和 Shizuku 只差"进程怎么起来",框架/引号/解析这些出 bug 的地方是
    同一套)。★ **测不到的那一半**:真正的权限不对称——开发机上回落 shell 同 uid,
    在完全相同的路径上失败,造不出差异。那部分只能上 root 机 / Shizuku 真机验。

- **SMB**:`smb2_context` 非线程安全,`NativeSmbClient` 用可重入锁串行化,流式读写在流关闭时才释放锁。`exec()`(旧名 `run`)派发到单线程 executor——**成员函数别叫 `run`/`let` 等 stdlib 作用域函数名**,匿名内部类里会被解析成标准库版、直接在调用线程跑,绕过序列化→并发进 libsmb2 崩溃(缩略图并发读时踩过)。
- **zstd/aircompressor**:用到 `Unsafe`,新设备上纸面有风险,换机测 restic/zstd 时留意。
- **不要 SSH 进服务器读日志**:自动模式会拦截"对未授权主机的远程 shell 读取"。要诊断远端让用户配合。
- **播放页封面圆角(★ 2026-07-23)**:`clipToOutline` 裁的是整个 View 的矩形边界;cover 用
  `fitCenter` 保留原始比例时,非同比例图片会在 View 内产生透明 letterbox——圆角只要还是靠
  `clipToOutline`/`background` 这类裁 View 轮廓的方式做,裁的都是这圈透明留白,肉眼看不出
  圆角(这也是之前来回折腾 `SquareImageView`/`centerCrop` 的根源:那些方案本质是拿"强制铺满
  View 边界"换圆角可见性,代价是破坏原始比例)。正确做法(`MusicPlayerActivity.setRoundedCover`):
  算出 fitCenter 缩放后图片**实际落地的矩形**(而不是整个 View 边界),圆角画在这个矩形上、
  直接烧进位图本身,letterbox 部分保持全透明——原始比例和圆角可见性同时满足,不需要自定义
  View 强制正方形。
- **波形对比度(★ 2026-07-23)**:`Waveform.normalize()` 曾用 `sqrt(rms/max)` "平滑"动态范围,
  副作用是安静段被拉得跟响的段差不多高,波形看着扁平没有层次感。改对比度类归一化曲线时优先选
  **线性或 >1 次幂**(把小值压得比大值更狠),不要用开根号这类 <1 次幂(会拉近大小值差距、
  降低对比度)。改这类算法记得**顺带处理缓存失效**——`Waveform` 磁盘缓存 key 不含算法版本,
  直接改目录名(`waves`→`waves2`)比事后指望用户手动清缓存更省心,参考视频缩略图缓存那条坑
  的教训。
- **文本编辑器的光标(★ 2026-08-16,`TextViewerActivity`/`CodeEditText`)**:两个症状,
  根子都在框架而不在我们的状态机:
  - **「光标看不见,打一个字才出现」**:光标闪烁的定时器只在 `Editor.onFocusChanged`
    里重启,`setCursorVisible(true)` 只 `invalidate()` 一次。而正文**早在布局时就已经
    是焦点**(`setTextIsSelectable` 让它成了页面上唯一可聚焦的视图),`requestFocus()`
    直接返回、什么都不做——画出来的那一帧若正好落在"灭"的相位上就再没人重画它
    (文本变化会走 `handleTextChanged → makeBlink`,所以打个字就好了)。修法是进编辑
    态时先 `clearFocus()` 再 `requestFocus()`,逼出一次真正的焦点变化。
  - **「行尾光标压在最后一个字上」**:`Editor.clampHorizontalPosition` 一旦发现光标 x
    顶到文本区右边界,就把它整体往左拉一个光标宽度塞回可见区,而 `wrap_content` 的
    文本区宽度**恰好等于最长行的宽度**,光标走到那一行行尾必然触发。加 padding 没用
    (`viewClippedWidth` 本来就把 padding 排除在外),得在**测量宽度**上加——
    `CodeEditText.onMeasure` 加 3dp;自动换行那条路的 `maxWidth` 要相应减掉,
    否则换行后正好比视口宽出这几像素,平白多出一段横向滚动。
- **拷进去的图不进相册(★ 2026-08-16,`MediaScan`)**:MediaStore 只认自己扫过的东西,
  `java.io` 写文件不会触发扫描——不通知的话,从网盘拷进 `DCIM/` 的图在相册里根本不
  出现;删除/改名不说一声则留下点开就报错的死记录。钩子挂在
  `LocalFileSystem.changed`(**所有本地写入的唯一出口**),复制/解压/编辑器保存/
  WiFi 共享上传因此一次全覆盖,各调用点零改动。三个要点:**写入报的时机是流关闭**
  (打开时报,扫到的是 0 字节空壳);`FilterOutputStream` 的
  `write(ByteArray,Int,Int)` 必须覆盖,默认实现逐字节转发;`:app` 侧只报 `/storage`
  下的路径(私有目录/cacheDir 媒体库本就不收),并攒 800ms 一批再交——拷一个目录是
  一个文件一次回调。
- **勾选一下整列表闪一下(★ 2026-08-02)**:`FileAdapter` 的选择变更原来一律
  `notifyDataSetChanged()`,所有可见行整行重绑。树式缩略图行的高度是**异步定的**——
  `bind` 先把图标框 `size()` 成方形 thumbDp、`FileIcons.bind` 把图片复位成类型图标,
  再靠 `Thumbs.fillAspect` 的 `view.post{}`(要等 infoBox 本轮布局完才读得到真实高度)
  按原图比例把高度撑回去。于是每次勾选,所有缩略图行都走一遍「塌成方形 → 下一帧撑回」,
  行高集体抖动 = 肉眼的「闪」。**修法是别整行重绑**:选择态走
  `notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)`,`onBindViewHolder` 的
  payload 重载里只调 `bindSelection()` 改勾的配色与 `root.isActivated`。教训:凡是绑定
  里有"先复位再异步回填"的视图(缩略图、异步图标),整表 `notifyDataSetChanged` 的代价
  不是性能而是**可见的闪烁**,局部刷新要用 payload。
- **终端里中文变胖、`●` 细高(★ 2026-08-02)**:termux `TerminalRenderer` 把列宽定死为
  `measureText("X")`,画每段文本时若实测宽度对不上「列数 × 列宽」,就
  `canvas.scale(比例, 1f)` **只横向**压拉塞进网格(垂直永远不动)。于是:系统
  `MONOSPACE` 没有汉字、回落 Noto Sans CJK(汉字 1.0em)而拉丁 X 只有 ≈0.6em,
  2 列目标 1.2em > 实测 1.0em → 中文**横向拉宽 20%** = 胖;`●`(U+25CF)是 East Asian
  **Ambiguous**,`WcWidth` 表里没有它(dump 过,附近区间是 9725–9726 等)按 1 列算,
  可字形是全角 → 压到六成宽、高度不变 = 看着细高。
  - **换字体是唯一解**(缩放开关在 renderer 内部,没有 API;改 wcwidth 会和远端
    `wcwidth` 不一致导致光标错位,更糟)。判据是**字体的 advance 必须是 0.5em**——
    这样拉丁 1 列、系统回落的汉字 1.0em 正好 2 列,两个缩放比都回到 1.0。
    **不必是 CJK 等宽字体**:`Iosevka`(0.5em,且自带几何符号块,`●` 也按 0.5em 出)
    2026-08-02 实测完美,体积远小于更纱黑体。0.6em 的等宽字体(JetBrains Mono 等)
    换了也照样变形。
  - 字体不打进包(体积优先):`TerminalFont` 走 SAF 导入 → 校验 sfnt 魔数 + 试加载 →
    拷进 `filesDir/fonts`(content:// 权限跨重启不可靠,与 SFTP 私钥导入同一套路)。
- **本地 shell 会话(★ 2026-08-03)**:termux 的 `TerminalSession` 本来就会自己
  `JNI.createSubprocess` fork 本地 PTY,`libtermux.so` 早已随 terminal-emulator
  依赖打进 APK(arm64/x86_64 各 ~10KB),所以本地终端**不需要**桥接线程、resize
  探测、断线重连那一套——那些全是为 SSH 准备的。两个必须知道的时序:
  - `TerminalView.attachSession` → `updateSize()` → `TerminalSession.updateSize()`
    在 `mEmulator == null` 时**会自己 `initializeEmulator`(即 fork 出 shell)**。
    attach 之后再手动调一次就会**fork 出第二个进程**并覆盖掉第一个的 fd/模拟器。
    先判 `getEmulator() == null` 再补。
  - SSH 会话是先注入模拟器再建视图,本地会话却要等进程起来才有 —— `TermSession`
    的 `lateinit var emulator` 在界面回调(`maybeShowIme` 等)里会
    `UninitializedPropertyAccessException`。凡是可能在会话就绪前跑到的地方一律走
    `emulatorOrNull`。
  - 限制:进程是本 app 的 uid、无 root;只有系统 mksh + toybox;API 29+ 的 W^X
    让应用私有目录里的二进制不能 execve(脚本用 `sh xxx.sh` 解释执行没问题)。
  - **`$HOME` 只对 shell 自己有效,OpenSSH 不认**:`ssh`/`ssh-keygen` 解析 `~` 走
    `getpwuid(getuid())->pw_dir`,Android 上应用 uid 的 `pw_dir` 恒为不可写的
    `/data` → `Could not create directory '/data/.ssh'`。密钥能放应用私有目录,但
    路径必须处处写全(`-f`/`-i`/`-o UserKnownHostsFile=`/`-F`,config 里也不能用
    `~`)。现方案:`SshHome` 建 `filesDir/.ssh`(700)+ 一份路径全绝对的 `config`
    (600),`.mkshrc` 给 `ssh`/`scp`/`sftp` 加 `-F` 指向它。★ config **必须先存在**
    ——`-F` 指向不存在的文件时 ssh 直接报错退出,不像默认 `~/.ssh/config` 缺失时
    静默跳过。`ssh-keygen` 用包装函数而不是 alias 给默认 `-f`:`-R`/`-F` 的 `-f`
    指的是 known_hosts,写死 alias 会让 `ssh-keygen -R host` 去重写私钥文件。
  - **跨会话历史做不到(★ 2026-08-06 实测定案,别再试)**:Android 自带
    `/system/bin/sh`(mksh R59)是 `HAVE_PERSISTENT_HISTORY=0` 编译的,
    `strings /system/bin/sh | grep -i hist` 里**连 `HISTFILE` 字符串都没有**
    (只有 `HISTSIZE`)——设了既不写也不读(预造好文件当环境变量传进去,`fc -l`
    照样 "no history (yet)")。历史是纯内存数组,`fc` 也没有 bash `history -r`
    那样的加载命令;App 层同样补不了,唯一通路是 PTY 输入而输入即执行。要真做只能
    打包带持久历史的 shell(放 nativeLibraryDir 绕过 W^X),与体积优先冲突,不做。
  - **Tab 补不出命令 = PATH 目录列不了(★ 2026-08-06,`CmdShims`)**:系统 PATH
    目录是 `drwxr-x--x root:shell`(`/system/xbin` 是 `drwxr-x---`),应用 uid 只有
    `x`(按名字进入/执行)没有 `r`(列目录),mksh 补全 `opendir` 一个候选都读不出来。
    `/apex/…/bin` 是 0755,所以表现为「有些能补有些不能补」。**`adb shell` 里一切
    正常,因为那个用户在 `shell` 组——诊断这类问题别拿 adb 下的行为当准**;要以
    app uid 验证得临时装 debug 包走 `run-as`(release 包 `run-as` 会拒)。这是 DAC
    不是 SELinux,所以 logcat 里**没有 avc denied 可查**。
    绕法(`CmdShims`)是不列目录、改成按名字问:`toybox` 无参运行自报它支持的全部
    命令名(实测 210 个,`/system/bin` 下多数命令本就是指向它的 symlink)+ 一份写死
    的 Android/第三方名单沿 PATH 逐个 stat 探测,命中的在 `filesDir/bin` 建同名
    symlink,再把该目录前置进 PATH。★ symlink **不受 W^X 限制**:内核解析后 execve
    的是 `/system/bin` 下的真身,不是 `/data` 上的文件。★ 探测**必须走完整 PATH**:
    `ssh`/`scp`/`ssh-keygen` 实测在 `/product/bin` 而不是 `/system/bin`。
  - **前缀搜索历史是白捡的**:mksh 自带 `search-history-up`/`-down`(拿光标前已输入
    的内容当前缀搜),但默认只绑在 PageUp/PageDown(`^[[5~`/`^[[6~`)上,手机键盘按
    不到。`.mkshrc` 模板把它绑到上下键;空行时行为与 `up-history` 完全一致,纯增强。
    `^[[`(普通光标键)和 `^[O`(应用光标键 SS3)两套都绑——绑后者会让 mksh 顺带把
    `^[O` 认成 prefix-2,SS3 版的 Home/End 也跟着能用。★ 查 mksh 绑定要
    `adb shell -t -t "sh -ic 'bind'"`:`bind` 要求**交互式**shell(`sh -c` 会报
    "can't bind, not a tty",光有 pty 不够),输出里 `^X` 是 `^[[` 的显示形式。
- **终端双指缩放调不动字号(★ 2026-08-02)**:termux 的 `mScaleFactor` 是**累积**因子,
  且会被 `TerminalViewClient.onScale` 的**返回值覆写**。老实现以固定初始字号为基准、
  又每次返回 1.0f 把累积清零,一次手势里只会反复设成 `base×阈值` 那同一个值 →
  捏了没反应。基准必须是**当前字号**。另两处配套:`setTextSize` 不 `requestLayout()`,
  所以 `OnGlobalLayoutListener` 那条同步远端 PTY 的通路**不会触发**(远端继续按旧
  cols/rows 输出、也收不到 SIGWINCH,屏幕上就留着旧内容)——改挂 termux 在行列真变时
  回调的 `onEmulatorSet`;且 `updateSize()` 的 `invalidate()` 关在「行列有变化」分支内
  (`setTypeface` 自己补了,`setTextSize` 漏了),换字号要自己补一次。
