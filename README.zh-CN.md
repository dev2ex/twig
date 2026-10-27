<p align="center">
  <img src="docs/img/logo.png" width="120" alt="">
</p>

<h1 align="center">Twig</h1>

<p align="center"><a href="README.md">English</a> · <strong>简体中文</strong></p>

一个体积优先的双面板 Android 文件管理器:所有来源都在同一棵树里就地展开,而不是各自
一个要「进去」的界面。原生 Kotlin + XML View,不引入 Material 库,依赖压到最低。
能手写就手写——WebDAV、S3 签名、git、restic 全部从零实现。

**版本 1.16.0**(versionCode 313)· minSdk 24 / targetSdk 34 / compileSdk 36 ·
[GPL-3.0](LICENSE)

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02-dual-pane.png" width="88%" alt="横屏双面板">
</p>

| 一棵树,所有来源 | restic 备份,设备上解密 | Jellyfin 媒体库当文件系统 | 占用图 |
|---|---|---|---|
| ![](fastlane/metadata/android/en-US/images/phoneScreenshots/01-one-tree.png) | ![](fastlane/metadata/android/en-US/images/phoneScreenshots/03-restic-repository.png) | ![](fastlane/metadata/android/en-US/images/phoneScreenshots/04-media-server.png) | ![](fastlane/metadata/android/en-US/images/phoneScreenshots/06-space-map.png) |

<details>
<summary><strong>更多</strong> —— 目录对比、WiFi 共享、内置终端</summary>

| 目录对比 | WiFi 共享 | 内置终端 |
|---|---|---|
| ![](fastlane/metadata/android/en-US/images/phoneScreenshots/05-directory-compare.png) | ![](fastlane/metadata/android/en-US/images/phoneScreenshots/07-wifi-sharing.png) | ![](fastlane/metadata/android/en-US/images/phoneScreenshots/08-terminal.png) |

</details>

<details>
<summary><strong>其余截图</strong> —— 浏览、媒体服务器、查看器、共享、终端</summary>

**浏览**

| 收藏横跨所有来源 | 目录长按菜单 | 跨来源复制 | 打包到对侧面板 |
|---|---|---|---|
| ![](docs/img/screenshots/browse-favourites.webp) | ![](docs/img/screenshots/browse-folder-menu.webp) | ![](docs/img/screenshots/browse-copy.webp) | ![](docs/img/screenshots/browse-compress.webp) |

**来源、外观与快捷方式**

| 文档树按授权的那个应用命名 | 特权访问:root 或 Shizuku | 浅色主题,横屏 | 桌面上的快捷方式 |
|---|---|---|---|
| ![](docs/img/screenshots/browse-document-tree.webp) | ![](docs/img/screenshots/settings-elevated-access.webp) | ![](docs/img/screenshots/browse-light-theme.webp) | ![](docs/img/screenshots/shortcuts-home-screen.webp) |

**媒体服务器与音乐**

| Emby 媒体库的「最新」 | 音乐播放器与波形 | 播放列表 |
|---|---|---|
| ![](docs/img/screenshots/media-emby-latest.webp) | ![](docs/img/screenshots/media-music-player.webp) | ![](docs/img/screenshots/media-music-playlist.webp) |

**查看器与对比**

| 文本查看器,手写着色 | Hex 查看器 | 文本对比,逐块合并箭头 | 保存的对比,从那行直接同步 |
|---|---|---|---|
| ![](docs/img/screenshots/viewer-text.webp) | ![](docs/img/screenshots/viewer-hex.webp) | ![](docs/img/screenshots/compare-text.webp) | ![](docs/img/screenshots/compare-saved.webp) |

**共享、终端与 Git**

| UDP 扫到另一台 Twig | 共享反过来挂成 WebDAV | Git 状态与终端并排 | 命令固定到桌面 |
|---|---|---|---|
| ![](docs/img/screenshots/share-devices-found.webp) | ![](docs/img/screenshots/share-as-webdav.webp) | ![](docs/img/screenshots/terminal-git.webp) | ![](docs/img/screenshots/terminal-command-shortcut.webp) |

</details>

---

## 下载

页首版本对应当前源码，下载请以已发布版本为准。
每个 [GitHub Release](https://github.com/dev2ex/twig/releases/latest) 都附有签名 APK:

| 文件 | 适合 |
|---|---|
| `Twig-<版本>-full.apk` | **拿不准就选它。** arm64-v8a + x86_64,带只读 RAR |
| `Twig-<版本>-libre.apk` | 想要彻底的自由软件——不含 RAR,因为 `junrar` 不是自由软件 |
| `Twig-<版本>-full-arm64-v8a.apk` / `-x86_64.apk` | 只要单一架构、下载更小 |
| `Twig-<版本>-full-armeabi-v7a.apk` | 32 位 ARM 设备——实验性,未在真机上测试 |

全部用同一把密钥签名,可以互相覆盖安装。证书 SHA-256 为
`196f633e942bf4f32f875b98c8ff181138256ba329a63364e3a39f154c1a5e62`。

**不经应用商店自动更新**:把 `https://github.com/dev2ex/twig` 加进
[Obtainium](https://github.com/ImranR98/Obtainium)。一个 Release 里有多个 APK,需要在
*Filter APKs by Regular Expression* 里指定一个,否则 Obtainium 每次都会让你手选、也没法后台更新——
默认版填 `-full\.apk$`,自由软件版填 `-libre\.apk$`。

**[从 F-Droid 安装 Twig](https://f-droid.org/packages/com.twig.app/)**：提供 `libre` 版，
包含除 RAR 外的全部功能，通过可复现构建验证，使用与 GitHub APK 相同的签名密钥。
F-Droid 自动跟踪正式版标签，完成构建与仓库发布流程后提供更新。

---

## 为什么是 Twig

**一棵树,所有来源。** 本地存储、压缩包、FTP、SFTP、SMB、WebDAV、S3、restic 仓库和
Jellyfin/Emby 媒体库都就地展开。两侧可以放不同来源:把 SMB 上的目录压成本地 zip,
或把远程压缩包里的文件直接复制到 FTP 服务器。

**体积小，资源友好。** 1.16.0 full 构建的 arm64-v8a 单设备下载实测为 8.37 MiB,包含终端、
网络存储后端和媒体播放器。新增依赖前先测量 APK 增量。

**这些工具可以一起用。**

- **浏览加密备份。** 打开 restic 仓库,按日期浏览快照,将需要的文件复制到其他来源,
  解密在设备上完成。
- **文件旁边就是 Git。** 查看本地或 SFTP 仓库的状态、历史、worktree 与子模块,
  并排对比变更,也可为正在操作的目录打开终端。
- **终端与文件管理联动。** 从目录打开本地、SSH 或特权 shell,也能将终端当前目录定位回
  文件树。独立的 `SHIFT` 与 `|` 键方便在手机上操作交互式工具和命令管道,字体、配色与字号
  都可自定义。
- **媒体库也在同一棵树里。** Jellyfin 与 Emby 和其他存储一起浏览,海报、元数据与字幕
  直接取自服务器;可继续上次播放,并把播放进度同步回媒体库。
- **跨来源对比与同步。** SMB 目录对本地存储、文档树对压缩包,查看差异后再执行增量或
  镜像同步,目标侧较新的文件单独确认。
- **共享已连接的来源。** 通过 Twig 的 HTTP/WebDAV 服务,电脑可在一个连接中浏览与下载
  来自不同存储来源及压缩包内部的文件。
- **特权访问沿用原来的目录树。** 普通权限无法访问时,root 或 Shizuku 可扩展本地访问能力;
  受保护路径同样能参与复制、搜索和缩略图等操作。
- **内置查看器与独立音乐播放器。** 在正在浏览的来源中查看文件:文本可保留原编码编辑,
  二进制可用十六进制检查,文档与媒体也能直接预览。本地和网络音乐可放进同一播放列表,
  配合波形定位、双语同步歌词与后台播放。
- **网页也能按自己的习惯改。** 把自建面板、网页播放器放在文件旁边,添加到桌面,
  再用内置调试工具折腾脚本、样式与页面元素;下载也能直接选 Twig 里的存储位置。

---

## 体积

1.16.0 正式签名 release APK 实测(R8 + 资源裁剪;1 MiB = 1,048,576 字节):

| | 大小 |
|---|---|
| Full APK(`arm64-v8a` + `x86_64`) | 10.51 MiB |
| Libre APK(`arm64-v8a` + `x86_64`) | 10.45 MiB |
| **Full APK，仅 `arm64-v8a`** | **8.37 MiB** |
| Full APK，仅 `x86_64` | 8.49 MiB |
| Full APK，仅 `armeabi-v7a`（实验性） | 8.13 MiB |

构成(full `arm64-v8a` APK 内条目的压缩后大小):

| 组件 | 大小 |
|---|---|
| `classes*.dex`(自研代码 + 全部 JVM 依赖) | 3.09 MiB |
| `libffmpegJNI.so` | 1.39 MiB |
| Bouncy Castle 的数据文件 | 1.18 MiB |
| 资源(`resources.arsc` + `res`) | 1.83 MiB |
| `libsamba_jni.so`(libsmb2) | 484 KiB |
| `libtwigzstd` / `libtermux` / `libtwigpty` | 109 KiB |

Bouncy Castle 数据中有 1.16 MiB 来自 `picnic` 后量子签名算法的三张查找表
(`lowmcL{1,3,5}.bin.properties`)。引入完整 Bouncy Castle 只是因为 Android 自带的
阉割版缺 X25519,所以这 1.16 MiB 是纯粹的死重——见路线图。

每种来源都是独立的 Gradle 模块,所以构建时可以裁掉用不着的部分——去掉媒体播放与
网络来源,上表中的绝大部分就没了。

---

## 一句话架构

所有来源——本地、压缩包、FTP、SFTP、SMB、WebDAV、S3、restic、Jellyfin——实现同一套
`FileSystem` + `XFile` 接口,`CopyEngine` 通过 `openInput()` / `openOutput()`
在任意两者之间搬字节。

> **加一种来源 = 新增一个 `FileSystem` 实现。UI 与拷贝引擎零改动。**

由此白捡两件事:任意来源之间可以互拷;裁掉模块就能构建精简版本。

---

## 功能


### 存储来源

| 来源 | 能力 | 实现 |
|---|---|---|
| **本地** | 读写全功能 | `java.io.File` |
| **私有空间** | 读写全功能 | 应用专属存储，通过本地文件系统浏览；不纳入备份，卸载时删除 |
| **Android/data/** | 授权后读写 | 系统允许时,通过 SAF 持久授权整个目录或单个应用目录;也支持 root/Shizuku 访问 |
| **系统目录**（`/system`、`/vendor` 等） | 浏览可读目录与文件 | 即使无法列出 `/`,仍可直接访问可读的系统路径;受保护内容需 root/Shizuku,只读挂载仍保持只读 |
| **ZIP** | **读 + 写**,老包 GBK 名自动识别 | 当文件系统挂载,点开即进入 |
| **7z** | 读 + **打包写入**(LZMA2) | commons-compress + xz |
| **tar** | 只读 | 条目在包里是连续存放的,直接切片读——里面套着的压缩包不必先解出来就能打开 |
| **gz / xz / bz2 / zst** | 只读 | 不是归档而是单条数据流:挂成只有一个条目的包,于是 `foo.tar.gz` 打开是 `foo.tar`,再展开就是 tar,`.tgz`/`.txz`/`.tbz2`/`.tzst` 同理 |
| **zstd** | 只读 | 流式解压,复用 restic 已经带进包里的那份 libzstd,不多打一份 |
| **RAR** | 只读(RAR4 + RAR5) | junrar,单独一个模块——只有 `full` 版带,`libre` 版没有 RAR |
| **加密压缩包** | 读 zip/7z/rar,**创建** AES-256 zip/7z | WinZip AES 与老式 ZipCrypto 全手写 |
| **FTP** | 读写全功能 | Apache Commons Net |
| **SFTP** | 读写全功能 | SSHJ(带完整 Bouncy Castle 解决 X25519) |
| **SMB / CIFS** | 读写全功能 | SMB2/3 用 libsmb2,经 NDK/JNI;旧设备可单独开启基于 jcifs-ng 的 SMB1 |
| **WebDAV** | 读写全功能 | 手写 PROPFIND/MKCOL/MOVE,不引 SDK |
| **S3 兼容对象存储** | 读写全功能 | 手写 SigV4 + REST——AWS S3、MinIO、R2、OSS、COS、B2 |
| **restic** | 只读,解密 | 从零实现;仓库格式 v1 与 v2 |
| **Jellyfin / Emby** | 只读虚拟树 | 手写 REST;一份实现通吃两家,零新增依赖 |
| **SAF 文档树** | 读写 | 没有 `MANAGE_EXTERNAL_STORAGE` 时的系统级回退 |
| **特权(root / Shizuku)** | 读写 | 不是独立来源,而是普通 API 够不着的本地路径的回落 |
| **已安装应用** | 只读虚拟树 | PackageManager;分包应用现打成 XAPK,复制出来的包不会漏掉 `split_config.*` |

网络来源展开即连接,支持多服务器(每台一个唯一 scheme),配置持久化。解压就是一次
跨来源复制,压缩也是——所以"把 SMB 上的目录压缩到本地"不需要任何特例代码。
压缩弹窗记住上次选择的 ZIP/7Z 格式，即使取消或重启应用也保留。

### 浏览

- **一棵完整的树。** 顶级节点包括内部存储、根目录、私有空间、可移动存储、回收站、
  局域网(SMB)、FTP、SSH、WebDAV、S3、媒体服务器、文档树、收藏、网站和已安装应用。
  压缩包是可展开的文件节点。左右面板可分别选择根项目显隐，私有空间默认显示。
- **永远就地展开,从不"进入"目录。** 当前目录就是你最后点的那个,它同时是新建、
  复制、移动的目标。
- 竖屏单面板(左右滑动切换),**横屏双面板并排**,切换面板带滑动动画。长按面板切换按钮,
  可把当前目录固定到另一侧并切换过去。
- 多选、跨面板复制/移动(带进度与取消)、新建目录、重命名、递归删除、剪贴板式粘贴,
  以及通过系统分享面板分享多个选中文件。
- **回收站**用于普通可写的本地共享存储，保存在文件原来的卷上。可逐项或批量恢复、
  永久清理，空回收站自动隐藏。网络、仅 SAF 可访问及特权访问的删除仍为永久删除。
  删除和清理任务支持后台进度，取消会在当前后端调用结束后停止。
- **分享到 Twig**可复制传入的文件，也可将分享文本保存为 UTF-8 文件并编辑文件名。
  目标选择器支持双面板、排序、搜索和历史记录，遵循浏览界面的全屏与工具栏位置设置。
- **浏览操作设置**支持底部工具栏，以及面板导航处理完毕后按返回键退出主界面。
  完成振动和前台完成通知分别设置，默认关闭；后台完成时保留结果通知。
- **批量重命名**选中的文件与目录,实时预览原名和新名。支持普通/正则替换、区分大小写,
  可选择仅文件名、仅扩展名或两者;编号可放在原名前后,可设置起始编号与补零位数。
  也可从开头或结尾的零基字符偏移插入、覆盖文字，或转换大小写（含英文标题格式：
  常见短介词、冠词和连词保留小写，标题及副标题首尾除外）。模板支持 `{name}`（不含扩展名）、
  `{ext}`（含点）、`{number}`、`{date}`（修改日期，格式为 `yyyyMMdd`）。
  插入偏移超过名称长度时停在另一端，偏移 0 表示添加前缀或后缀；覆盖从该位置向右替换，
  必要时延长名称。执行前检查目标名称重复及已有文件冲突。
- **收藏**与**最近位置**存的是"如何到达"(连接标签 + 路径),不是会话内的动态
  scheme,所以重启后依然有效。
- **恢复上次位置**,逐级展开;恢复期间一碰列表就交还控制权。关闭位置记忆时默认展开收藏。
- **网格视图**,与缩略图开关正交,列数按面板宽度自适应。
- **目录排序与视图**可分别使用全局默认或本目录设置，视图可选列表、媒体网格、全部文件网格。
  每项设置都可单独应用到子目录；本目录设置优先，其次是最近的递归祖先设置，再回到全局默认。
  目录名称支持正序和倒序。
- **缩略图**支持本地和网络来源的图片与视频，可选在视频缩略图内显示记忆的播放进度条。
- **目录/文件计数**可显示在本地与 SMB 目录下方,默认关闭;日期格式可跟随系统地区,
  或选择固定格式。
- **属性卡片**内嵌在文件行下方:EXIF、媒体轨道、应用信息、哈希。点击行左侧区域可显示或
  隐藏属性,左右滑动切换 tab;基本信息先显示,媒体详情在后台加载。多选时可查看汇总大小与数量。
- **应用检查**位于「属性 → App」:签名证书、Activities、声明的快捷方式、Services、
  Receivers 与 Providers。可直接启动允许访问的 Activity 与快捷方式,长按可启动的 Activity
  可添加桌面快捷方式。
- **Android/data 的 SAF 访问**集成在本地目录树中,授权持久保存,应用目录显示应用图标。
  系统允许时可授权整个目录;也可从用户安装的应用中按名称/包名过滤,选中后直接请求对应
  应用目录的授权。目录探测可选,因为系统可能隐藏实际存在的路径。是否可授权取决于系统选择器,
  root 或 Shizuku 仍可作为其他访问方式。
- **可读系统目录入口**:Android 禁止列出 `/` 时,仍可访问 `/system`、`/vendor` 等可读目录,
  其中文件与子目录的访问继续受系统权限限制。
- **占用图**(SpaceSniffer 式 treemap)内嵌在面板里,支持双指缩放和一键重置缩放,长按菜单与树共用。
- **SD 卡与 U 盘**在树根上各占一行,名字与容量取系统给的。不新增任何权限——普通 API
  读不动的卷,可以用 SAF 授权,或者走特权侧列。
- **跳转到路径**:任何根行(服务器、内部存储、可移动卷、文档树、收藏)都能输入一条路径,
  树逐级展开到那个目录或那个文件所在的行;路上遇到没连的服务器会先连上。
  长按或双击路径栏可编辑当前目录的路径。
- **长按上级目录按钮**可将屏幕外绿色高亮的当前目录滚回可见位置；如果该行已在屏幕内，
  则折叠目录树。
- **分包 APK 直接安装**:`.xapk`、`.apks`、`.apkm` 从包里读出来写进 `PackageInstaller`
  会话,不用再装一个辅助 App。
- **Twig 可以当文件选择器**,对外接 `GET_CONTENT`,对内替掉 SAF——所以能选到
  SMB 共享里、压缩包里的文件,而系统选择器看不见这些来源。
- **添加到桌面**:目录和单个文件都能钉一个快捷方式——目录版展开到树里那一行;文件版
  还能选*怎么打开*(自动识别、文本查看、十六进制查看,或提前选定一个应用,因为快捷方式
  没法每次点都弹一次应用选择器),图标在已有缓存缩略图时直接用缩略图。跑在独立任务栈里,
  点开不会经过主界面。

Nova 等启动器的快捷方式／动作选择列表中，还提供 **Twig shortcut** 和
**Twig command shortcut**。前者选择目录、文件或网站后确认；后者选择命令运行的位置，
复用 Twig 内的命令配置对话框。SFTP 位置使用 SSH；本地目录（包括 `/`）默认使用应用 UID，
只有勾选默认关闭的**「使用特权会话」**，才按设置中的特权方式使用 Root（su）或 Shizuku。
创建后的快捷方式记住所选方式及 Shizuku 的 UID；身份不可用或发生变化时会报错，不会回退到
其他 shell。启动器可将结果绑定到图标的上划／下划手势，无需再放一个桌面图标。
压缩包内部、restic、Git 等临时挂载位置无法在进程重启后直接恢复，因此不可选择；
不支持的位置会禁用并淡化「选择」按钮。

### 网站与页面定制

把自建服务的面板、网页音乐播放器、常用网站放进 Twig 的**「网站」**根节点，也可以
顺手折腾一下网页，让它更合自己的习惯。每个网站用系统 WebView 在独立任务中打开，
也能添加桌面快捷方式直接启动。
如果页面还开着，再次点击会回到原来的页面，不会重新通过网址加载。

也可用 Twig 打开其他应用传入的 HTTP(S) 链接，以网站默认设置打开临时页面，只有点击
**「添加网站」**才加入书签。长按**「网站」**根节点可修改默认设置：显示选项用于初始化新书签；
共用脚本、样式、隐藏元素和拦截规则也作用于已有页面。网站自己的 User-Agent 覆盖共用值，
脚本、样式和规则则与共用设置组合生效。

展开**「网站 → 添加网站…」**，填写名称和网址，再选择打开方式。长按树中的网站条目，
可以编辑或添加到桌面。每个网站都能单独设置：

- **显示与手势**：全屏打开，分别隐藏工具栏、状态栏、导航栏，屏幕方向、桌面模式，
  页面顶部下拉刷新、滚动方向锁定和禁用文本选择。默认双击隐藏/显示工具栏；网页自身
  需要双击事件时，可以关闭这个选项。
- **定制页面**：展开**「高级」**，添加 JavaScript、CSS，以及要隐藏元素的 CSS 选择器。
  给面板换个样式、去掉横幅，或者加上自己的小工具。脚本在页面加载完成后执行；脚本、
  样式和隐藏元素只作用于与保存网址同源的主页面。
- **请求与登录**：自定义 User-Agent、手动添加请求 Header，以及 HTTP Basic 用户名和
  密码。保存的 Basic 凭据和 Header 值使用 Twig 的密钥存储加密。Header 仅用于同源 GET
  页面导航，不会统一附加到所有接口和资源请求。
- **请求拦截**：每行一个域名或网址规则，支持 `*` 通配符，拦截 WebView 能提供的图片、
  脚本、接口等资源请求。普通域名也匹配其子域名。这是手动资源规则，不是完整的广告
  过滤订阅引擎，也不拦截主页面导航。
- **边看边改**：工具栏的**「网页调试」**提供控制台、执行脚本、预览 CSS 和元素选择器。
  可以复制选择器或 HTML、点选隐藏元素，再把修改保存到网站配置。需要电脑 DevTools 时，
  开启**「电脑调试（本次会话）」**，配合 USB 调试和电脑 Chrome 的
  `chrome://inspect/#devices` 使用；部分 Android 开发版系统会强制开启 WebView 调试。
- **文件下载**：HTTP(S) 下载默认保存到 `Downloads/twig`。确认文件名，也可以通过 Twig
  自己的选择器另选可写位置，包括已连接的存储，支持传输进度与取消。
- **文件上传**：网页请求上传时，使用 Twig 自己的选择器选择一个或多个文件，包括已连接
  存储中的文件；单选或多选遵循网页的请求。
- **摄像头、麦克风与位置**：每个书签可为保存的 HTTPS 来源分别设置询问、允许或拒绝，
  同时仍需 Android 权限。临时页面始终询问，新书签不继承授权；书签来源变化后重置授权。
- **禁止截图**：每个网站独立设置，默认关闭，解锁后的最近任务预览也遵循这个选项。
- **后台运行**：按网站开启，默认关闭。用带通知的前台服务维持已打开的网站会话，适用于
  媒体播放或其他页面任务；关闭页面或通过通知结束。它能改善后台存活，但不能保证绕过
  Android 省电限制或网站自身的暂停逻辑，让页面始终不间断运行。

Cookie 和登录状态保存在 Twig 共用的 WebView 数据中，与其他浏览器分开；不同网站书签
不是彼此隔离的账号容器。网站兼容性取决于设备的系统 WebView。

设置了 Twig 主密码后，Twig 处于锁定状态时，打开网站或返回已有网站任务都需要先解锁。
Cookie 和原页面保留，已开启的后台运行继续。锁定期间网站窗口禁止截图与最近任务预览；
解锁后遵循该网站的**「禁止截图」**选项。

### 对比

- **目录对比**(Beyond Compare 式):两侧逐行对齐,中间一列状态符号;横屏并排,竖屏一次
  显示一侧但状态列始终在。两侧可以是任意来源,小文件按**内容**比,而不是只看大小与时间。
  大文件也可主动发起完整内容比较；读取失败单独显示为错误，并从同步中排除。
- **单向同步**方向写死「同步到左侧 / 同步到右侧」,不跟着活动侧走(同步是不可逆的)。
  默认**增量**:只推源侧独有与两侧不同的,目标侧多余的保留;关掉即**镜像**,多余的一并
  删除。目标侧更新的那些单独列出,不勾就不覆盖。
- **保存的对比**与收藏并排放在树根上,常看的一对点一下就打开,也能直接从那行发起同步。
- **文本对比**双栏、长行横向同步滚动、逐差异块合并;**图片对比**并排,缩放平移联动。
- **二进制走 hex 对比。** 一个文件是不是二进制,只有读了才知道,所以判定为二进制、或者
  大到文本视图扛不住的那一对,会转交给 hex 对比而不是停在"无法比较"。两侧**按相同偏移
  对齐、不做重新同步**——对固件、改过的可执行文件这类结构固定的东西,这才是诚实的答案;
  短的那侧用空行补齐,否则它先到底,末尾的差异永远翻不到。

### 查看器与播放器

所有查看器都经 `FsRegistry` 读取,本地、压缩包内、远程文件一视同仁。

- 文本查看器,手写词法着色,多主题,捏合缩放字号,带 Markdown 预览模式
- **文本编码是设置项,不是猜**:统一走 `TextCodec`——BOM → 严格 UTF-8 → 你在设置里排好的
  候选(默认 GB18030)。编辑不再只限 UTF-8:保存用读进来那个编码写回;原编码表示不了新输入的
  字符时会问你要不要转成 UTF-8,而不是静默替换成 `?`
- Hex 查看器,虚拟滚动、可拖动滚动条、文本/HEX 搜索
- **PDF 阅读器**,基于系统渲染器:连续或单页滚动、双击裁掉页边空白、文本选择,
  系统支持时还有全文搜索(Android 15+)
- 图片查看器(降采样防 OOM),支持左右或上下滑动切换图片;幻灯片边扫边播,找到第一张就先显示
- **视频/音频播放器**(media3 + FFmpeg 软解),支持 AVI、M2TS、HDMV 音轨与
  PGS 字幕;系统解码器崩溃时两级自动降级。M2TS 的跳转定位可能较慢
- **剧集自动连播**:Jellyfin/Emby 的队列由服务端给出,其余来源按文件名里的编号
  (`SxxExx`、`E01`、纯数字)分组。上/下一集按钮只在真的算得出队列时才出现
- **片头片尾跳过**用于识别为同一系列且至少包含两集的视频，不要求集数编号连续。
  在片头结束处点击片头按钮，或在片尾开始处点击片尾按钮，即可记忆同一系列共用的跳过边界；
  再次点击已标记的按钮可清除。
  若起播位置在片头内，自动跳到片头结束；播放到片尾标记时切换到下一集，最后一集则结束播放。
- **继续观看**在历史记录中有独立标签，列出本地及已保存文件服务器连接中的未看完视频。
  可继续播放、从头播放或定位所在目录；移除条目只隐藏记录，保留续播位置。
  打开或跳转后需连续播放十秒才更新进度。
- **播放偏好记忆**:手动选择的音轨、字幕与画面缩放方式,可在下一集及重新打开文件时恢复
- **播放操作**支持滑动定位、亮度/音量手势、长按临时加速,可切换音轨及内嵌/外挂字幕

#### 独立音乐播放器

- **跨来源听歌。** 本地、网络与 SAF 音乐可加入播放列表;挂载中的压缩包和备份内的音乐
  可使用临时队列播放。
- **封面与歌词。** 界面配色跟随封面,点击切换封面与歌词,横屏时两者并排显示;
  在封面左右滑动切换歌曲，上下滑动调节媒体音量，播放器内显示音量百分比。
- **双语同步歌词**支持同目录 LRC、内嵌标签及媒体服务器提供的歌词。点击带时间的歌词行
  可跳到对应位置,波形也可直接用于定位。
- **播放列表管理**支持收藏、名称/元数据搜索、排序、拖动调整顺序、随机与循环播放,
  保存的列表可导出为 `.m3u8`。
- **后台播放**支持通知栏、锁屏和耳机/蓝牙控制，通知提供收藏和退出，系统支持显示时还有
  原生播放计时。重新打开播放器可恢复上次队列，可从菜单、通知或长按随机播放按钮退出。

### 媒体服务器(Jellyfin / Emby)

一份实现通吃两家——Emby 正是 Jellyfin 当年 fork 的上游,那批端点同源同名——而且
**零新增依赖**:OkHttp 本来就在,`org.json` 是 Android 运行期自带的。

- 树按**服务器上实际存在的媒体库**组织,用你自己起的库名,而不是写死的几个类型。
  电影库点进去直接是所有电影,剧集库是所有剧(只有多季时才分季),音乐库是
  专辑 / 专辑艺术家 / 艺术家 / 文件夹四个分类。
- **播放进度双向同步。**「继续观看」就是服务器上那份,不是本机另存的一份;而且保持
  服务端给的顺序,不套用你在文件浏览里选的排序。
- **海报、标签、歌词、外挂字幕全部走 API。** 这些信息若去读媒体文件本身,在网络上
  每首歌要花几秒。「继续观看」用横版剧照,媒体库用竖版海报并按原始比例显示。
- **搜索走服务端的索引**,所以刮削成中文名的片子,用原名照样搜得到。
- **设计上只读**:服务端没有上传 API,而 `DELETE /Items/{id}` 删的是媒体库里的真实文件。

### 终端

- **本地、SSH 与特权 shell。** 可在选中目录打开本地系统 shell 或 SSH 会话,支持多个会话,
  顶部切换。root 与 Shizuku shell 有独立入口并明确标示身份。本地 shell 使用真 PTY,
  支持交互式终端程序。
  普通本地 shell 始终使用应用 UID，不会因为 Twig 获得过 root 授权而自动提升权限。
  「新建特权会话」使用设置中当前启用的 Root 或 Shizuku；Shizuku 的实际身份取决于其启动方式，
  root 启动为 UID 0，ADB 启动通常为 UID 2000。
- **文件与命令双向跳转。**「在此打开终端」从正在浏览的目录开始;「定位当前目录」将 shell
  的工作目录定位回文件树。SSH shell 提供当前路径时,远程目录也能定位。
- **适合触屏的附加键。** 两排按键提供 `CTRL`、`ALT`、`SHIFT`、`ESC`、`TAB`、方向键、
  Home/End、Page Up/Down 及常用 shell 符号。先点 `SHIFT`,再点方向键或 `TAB` 即可发送
  组合键;方向键支持长按连发。
- **Shift 组合键操作交互式工具。** 例如用 `Shift+←` 打开
  [Codex](https://github.com/openai/codex/blob/main/codex-rs/tui/src/chatwidget/tests/questions_tests.rs)
  的待回答问题,或用 `Shift+Tab` 切换
  [Claude Code](https://code.claude.com/docs/en/interactive-mode#general-controls) 的权限模式。
- **独立管道键 `|`。** 将一条命令的输出交给下一条命令,例如 `ls -l | grep .apk`,
  无需反复切换手机键盘的符号页。过滤日志、搜索输出、组合命令行工具时都经常用到。
- **软键盘显隐。** 工具栏按钮可随时显示或隐藏 Android 软键盘;点击命令提示符时弹出,
  隐藏光标的程序不会自动唤起。键盘显隐或屏幕旋转时调整终端行列,SSH 远端 PTY 也同步调整。
- **终端自定义。** 可导入字体及兼容 Termux 的 `colors.properties` 配色,也可使用默认配置;
  双指缩放字号。终端配色独立于应用主题,附加键条也跟随终端配色。
- **保持亮屏**可让会话持续显示，达到系统闲置超时后降低亮度，操作时恢复原来的亮度。
- **命令快捷方式**：支持 SFTP 目录、服务器和本地目录，在终端里运行或后台执行，
  可固定到桌面，也可通过启动器的 **Twig command shortcut** 入口绑定手势。
  本地命令可勾选「使用特权会话」，默认不勾选。

### Git

- `:git-lite` 从零实现 `GitRepo`、`ObjectStore`、`IndexFile` 与 patience `Diff`
- 状态与历史视图,双栏 diff 与文本查看器共用着色器
- 本地仓库直读,SFTP 仓库经远程 `exec git`
- **worktree 与子模块**处理到位,包括 `gitdir:` 里记的绝对路径在本机不存在的情况
- 虚拟的**「工作区」节点**列出同一仓库的其他 worktree,点进去就是那条工作区完整的
  状态/分支/历史视图

### WiFi 共享

直接在 `ServerSocket` 上手写的极小 HTTP/1.1 服务,零新增依赖。电脑浏览器输 IP 就是
目录列表 + Range 下载 + 拖拽上传;同一个端口同时说 **WebDAV**,Finder、资源管理器
或另一台 Twig 都能直接挂载。**默认只读**,可选 Basic 认证,前台服务 + WiFi 锁,
UDP 探测让另一台 Twig 扫一下就能把它存成连接。

### 安全

- 密码、API key、token、私钥口令**加密落盘**:一把随机 256 位 DEK 加密这些字段,DEK 外面
  套硬件 Keystore 密钥(默认,无感)或 scrypt(用户设的主密码)。开关主密码只换「谁包住
  DEK」,字段密文一个字节都不重写。零新增依赖:scrypt 用的是包里早就有的 Bouncy Castle。
- 主密码是**程序锁,各入口都验证**——主界面、「用 Twig 打开」、「复制到此」、文件选择器、
  终端快捷方式、远程命令快捷方式、通过启动器配置的动作和网站任务。菜单里的「锁定」只丢掉
  内存中的密钥,音乐照放、终端还在、共享继续。直接固定的文件桌面快捷方式是例外:本地和
  SAF 文件打开不需要解密任何东西,直接跳过;只有指向服务器的文件才会先解锁,因为重连要
  用到保存的(加密)凭据。
- **指纹解锁**走平台 `BiometricPrompt`(不引 androidx.biometric,APK 增量为 0),与主密码
  并存;主密码始终是根钥匙。
- **配置备份**(`.twigbak`):连接、设置与网站书签(含脚本、样式和规则)导出成 JSON,
  可选用单独的导出密码加密;保存位置走
  Twig 自己的目录选择器——所以能直接存到 SMB / WebDAV / S3。导入是合并不是替换,
  token、host key 与 DEK 一律不导出。网站书签列表按整份恢复,Basic 凭据与 Header 在目标
  设备重新加密；目录排序/视图设置和网站默认设置也包含在备份中。
  不包含 WebView 的 Cookie、登录会话和私有空间文件。

---

## 模块

| 模块 | 内容 |
|---|---|
| `:core-fs` | 纯 JVM:`XFile` / `FileSystem` / `FsRegistry` / `CopyEngine` |
| `:fs-local` | `LocalFileSystem` + `priv/`:root/Shizuku 共用的特权 shell 回落 |
| `:fs-archive` | `ArchiveFileSystem` + zip(读写、加密)/ 7z / tar / gz·xz·bz2·zst 单文件压缩,以及 `ArchiveWriter` |
| `:fs-archive-rar` | `RarFileSystem`(RAR4 + RAR5)——单独成模块纯粹是为了让 `libre` 版能整块去掉 |
| `:fs-network` | `FtpFileSystem` / `SftpFileSystem` / `WebDavFileSystem` / `S3FileSystem` / `JellyfinFileSystem` |
| `:fs-smb` | `SmbFileSystem`——libsmb2,经 NDK/JNI;`Smb1FileSystem`——按连接开启的 jcifs-ng 旧协议后端 |
| `:fs-restic` | restic 仓库读取器(纯 Kotlin) |
| `:fs-zstd` | `NativeZstd` + `ZstdInputStream`——块解码与流式 zstd 解码,经 JNI |
| `:git-lite` | 纯 Kotlin 迷你 git |
| `:app` | 双面板 UI、查看器、终端、Git 视图、占用图、WiFi 共享、网站与页面定制 |

---

## 构建

使用 JDK 21，以及 `app/build.gradle.kts` 中指定版本的 Android SDK/NDK。

```bash
./gradlew :app:assembleFullDebug       # 可安装的 debug APK(带 RAR)
./gradlew :app:assembleFullRelease     # R8 优化的 release(带 RAR)
./gradlew :app:assembleLibreRelease    # F-Droid 版(无 RAR,100% 自由软件)
./gradlew :app:bundleLibreRelease     # libre 版上架用 AAB
./gradlew :app:assembleFullRelease -PabiSplit=true  # 三个单 ABI APK
```

默认情况下，release 只有在仓库根存在 `keystore.properties` 时才签名(内含 `storeFile` /
`storePassword` / `keyAlias` / `keyPassword`)。没有它时产出的是**无签名包**
`app-<flavor>-release-unsigned.apk`,**不会**回落到公开的 Android debug key。
自己签,或者本地测试直接用 debug 包。也可在 `local.properties` 设置 `debugSign=true`，
或传入 `-PdebugSign=true`，显式用 debug key 签名 release；命令行参数优先。
正式发布应使用项目自己的签名密钥。

测试:

```bash
./gradlew :core-fs:test :fs-archive:test :fs-network:test :fs-restic:test :git-lite:test
./gradlew :app:testFullReleaseUnitTest
```

说明:build-tools 固定 36.1.0;默认构建 `arm64-v8a` 与 `x86_64`。启用 ABI 拆分时还会
构建实验性的 `armeabi-v7a` APK。R8 开启,libsmb2 与 zstd 的 JNI 入口按名 keep。

准备发布时，在已提交且干净的工作区配置正式签名后，运行 `tools/release.sh`。脚本会
构建并校验五种 APK，在 `build/release/<版本>/` 生成安装包和双语发布说明，不打标签也不推送。
`tools/release.sh --publish` 还会打标签、推送并创建 GitHub Release。

### 加一种新来源

1. 新建模块 `:fs-xxx`,实现 `com.twig.core.FileSystem`
2. 启动时注册:`FsRegistry.register(XxxFileSystem())`
3. 给用户一条导航到 `XxxFileSystem.root()` 的入口

浏览、复制、移动、删除、缩略图、搜索随即全部可用,无需改动。

---

## 许可

Twig 采用 **GPL-3.0-only**,见 [LICENSE](LICENSE)。

这是继承来的而不是选的:Termux 终端模拟器与 Jellyfin FFmpeg 解码器都是 GPLv3,
链接它们导致整个应用必须是 GPLv3。

- [LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md) —— 与 UnRAR 许可下的 `junrar`
  链接的附加许可、该许可要求的声明、LGPL 重新链接说明,以及商标保留
- [THIRD_PARTY.md](THIRD_PARTY.md) —— 全部第三方组件及其版本与许可

**商标。** "Twig" 名称与应用图标不在 GPL 授权范围内。代码可以自由 fork 和修改;
再分发修改版时请更换名称与图标,以免用户误认它来自本项目。

**RAR 声明。** 按 UnRAR 许可的要求:本程序中处理 RAR 的代码不得用于开发与 RAR
(WinRAR)兼容的压缩器。Twig 只解压 RAR,不实现 RAR 压缩。

---

## 参与贡献

欢迎贡献。请先读 [CLA.md](CLA.md)——很短,不拿走你的版权,并解释了为什么这个项目
需要保留对自研代码另行授权的权利。

签署方式是在 PR 描述里加一行:

```
I have read the CLA (CLA.md) and I agree to its terms.
```

实现决策记在 [AGENTS.md](AGENTS.md),那一长串代价高昂的排错记录按领域拆在
[docs/lessons/](docs/lessons/) 下,一个领域一个文件。改终端、缩略图、TS 解复用这类
子系统之前,先读对应那份。

---

## 路线图

- `:fs-cloud` —— Google Drive / Dropbox / OneDrive,走纯 REST,不用各家 SDK
- 全局搜索
- 体积:干掉 Bouncy Castle 那 1.2 MB 的 `picnic` 查找表,或者整个换成 Conscrypt
  ——两条路都需要真机验证 SSH 握手仍然找得到 X25519

---

<details>
<summary><strong>演进史</strong></summary>

- **Phase 1** —— `FileSystem`/`XFile` 抽象与 `CopyEngine`;本地文件系统;双面板 UI;
  多选/复制/移动/新建/改名/删除;R8 + 资源裁剪 + AAB 拆分。
- **Phase 1.5 / 1.6** —— 文本/Hex/图片查看器经 `FsRegistry` 通用;用其他应用打开;
  复制进度与取消;SAF 回退。
- **Phase 2 / 2.5** —— 压缩包挂载:ZIP 从只读到读写(GBK 自识别);7z / RAR 只读;
  解压复用 `CopyEngine`。
- **Phase 3 / 3.5 / 3.6** —— FTP,然后是 libsmb2 的 SMB,然后是 SSHJ 的 SFTP 与
  手写 WebDAV;连接持久化与多服务器管理。
- **Phase 3.7** —— restic 仓库只读解密浏览,支持格式 v1 与 v2。
- **0.8 / 0.9** —— 树式模型定型:就地展开、展开即连接、竖屏单面板 +
  横屏双面板、深色主题。
- **0.12** —— 收藏,覆盖全部来源,按需连接直达。
- **0.45** —— 网格视图与缩略图开关解耦(正交)。
- **0.62** —— 幻灯片:边扫边播、随机播放显示真实下标、悬浮栏自动隐藏。
- **0.65** —— 位置记忆修复 + 最近位置历史;「跳转到所在目录」精确定位到文件行。
- **0.70** —— **压缩打包**:打包到对侧当前目录,可选 zip/7z 与移动模式,天然跨来源。
- **0.73** —— 横屏布局重整:隐藏 Toolbar、操作列改双列、路径栏改 `类型:/服务器/路径`。
- **0.74** —— 分组排序扩展到树式列表;修「展开位置没被记住」与「勾选时整列表闪一下」。
- **0.77** —— 终端外观三件套:字体导入(判据是 advance 0.5em)、配色、双指缩放修复。
  Twig 可作文件选择器。
- **0.78** —— SFTP 命令快捷方式,终端或后台静默执行,可固定到桌面。
- **0.79** —— **本地 shell 终端**,走 termux 原生 PTY——不需要桥接线程、断线重连、
  resize 探测那套远程逻辑。
- **0.80** —— 本地 shell 好用起来:`CmdShims` 绕开「PATH 目录对应用 uid 不可读」,
  `SshHome` 给 OpenSSH 备好全绝对路径的 config,`.mkshrc` 把前缀搜索历史绑到上下键。
- **0.96** —— **WiFi 共享**:手写 HTTP/1.1 服务,同一端口兼说 WebDAV,暴露的是整个
  `FsRegistry`——浏览器能直接下载 SMB 上、甚至压缩包内的文件。
- **0.97 / 0.98 / 0.99** —— 共享返工:「所有来源」按能否真的浏览来筛、按名字逐级解析
  路径、修正非 ASCII 路径的链接编码;网页端重做(就地预览、排序、批量操作);
  入口挪到操作列。
- **1.00** —— **压缩包密码**:读加密的 zip/7z/rar,创建 AES-256 加密包。zip 那套是
  手写的——commons-compress 与 `java.util.zip` 对加密 zip 连读都不支持。
- **1.01+** —— 往已有 zip 里加文件改成真追加(100MB 包实测 1875ms → 1ms);展开压缩包
  等于选中包根;**S3 兼容对象存储**(手写 SigV4);root / Shizuku 特权访问,含基于
  `bindUserService`、在特权侧分配 PTY 的特权终端。

- **1.1** —— **Jellyfin 与 Emby** 成为一等来源:树按服务器自己的媒体库组织,播放进度
  双向同步,海报/标签/歌词/字幕改从 API 拿而不是读媒体文件,搜索走服务端索引,剧集
  自动连播。零新增依赖。连播对普通来源也可用(按文件名里的编号分组);另外只读来源上
  的写操作入口现在直接不出现,而不是点下去才报错。
- **1.2** —— **目录对比同步**:增量或镜像,方向写死左右两项而不跟着活动侧,目标侧更新的
  那些单独确认。**RAR5** 支持(junrar 升到 8.1.0,顺带修掉「密码错了却说对」);拆出
  `libre` / `full` 两个 flavor,junrar 单独成模块移出 libre,F-Droid 版 100% 自由软件。
  导航栏底色跟着当前页面走,底部不再是一道黑边。
- **1.3** —— **安全**:保存的密码经两层 Keystore 密钥加密落盘;主密码升级为程序锁,六个
  入口全守;指纹解锁;`.twigbak` 配置备份走 Twig 自己的目录选择器(所以能直接存到
  SMB / WebDAV)。**SD 卡与 U 盘**在树根上直接给入口。行高与文字大小拆成两档独立偏好,
  一次调好的项目从菜单收进设置页;自适应图标 + Android 13 主题图标;应用图标长按菜单
  新增「终端」。
- **1.4** —— 别的应用授权的文档树按**那个应用**命名并用它的图标,不再显示一长串 document
  id;授权也能从侧栏交还。
- **1.5** —— 任意根行都能**跳转到路径**;收藏与保存的对比可以指到文档树里面;终端附加键条
  打磨(方向键长按连发、整条键条共用一个字号),息屏再亮不再丢掉模拟器尺寸。
- **1.6** —— **tar,以及 gz/xz/bz2/zst 单文件压缩。** tar 里的条目按偏移切片就地读取,
  所以里面套着的压缩包不必先解出来就能打开、里面的视频还能 seek;单流格式挂成只有一个
  条目的包,于是 `foo.tar.gz` 打开是 `foo.tar`,再展开就是 tar,`.tgz`/`.txz`/`.tbz2`/
  `.tzst` 同理。容器里没记原始大小的,大小显示留空,不再写成 `0 B`。
- **1.7** —— 内置 **PDF 阅读器**:连续或单页滚动、双击裁掉页边空白、文本选择,系统支持时
  还有全文搜索(Android 15+)。判定为二进制、或大到文本视图扛不住的那一对,转交 **hex 对比**。
  **分包 APK**(`.xapk`、`.apks`、`.apkm`)经 `PackageInstaller` 直接安装。SMB 不填共享名即
  列出全部共享,FTP / SFTP / S3 连接可指定起始路径。文本编码候选里 GB18030 取代 GBK。
  值得一提的修复:AVI 声音重组帧与 B 帧顺序、4K 网络播放撑爆堆、SSH 上的 git 工作区。
- **1.8** —— 单个文件也能钉**桌面快捷方式**了,不再只有目录能:可以选怎么打开——自动识别、
  文本查看、十六进制查看,或者提前选定某个应用(快捷方式没法每次点都弹一次应用选择器);
  图标在已有缓存缩略图时直接用缩略图。主密码只在目标真的需要解密时才弹——服务器文件要靠
  保存的加密凭据重连,本地和 SAF 文件从来不需要。快捷方式跑在独立任务栈里,打开不会经过
  主界面,返回也不会绕回主界面。
- **1.14** —— **网站书签**支持按网站配置显示、脚本、样式、认证、下载、页面工具及可选后台运行，
  网站任务遵循应用锁。新增系统允许的 **Android/data SAF 授权**、可读系统目录入口、可配置的
  启动器快捷方式、支持替换与编号的批量重命名、多文件分享及选中项属性。长按或双击路径栏
  可编辑路径；改进面板切换与全屏刘海适配。
- **1.15** —— 本地回收站、更多批量重命名操作与模板、区分文件对比中的读取失败。
  网站共用默认设置、临时页面、文件上传与独立截图设置；历史记录新增继续观看，
  视频缩略图显示记忆进度，占用图增加重置缩放按钮。
- **1.16** —— 私有空间、左右面板独立的根项目显隐、可递归继承的目录排序与视图设置、
  分享文本保存与双面板目标选择器。后台删除、回收站批量恢复、可选底部工具栏和完成提醒。
  剧集片头片尾跳过、音乐音量手势与通知退出/计时、网站摄像头/麦克风/位置权限，以及终端闲置调暗。

</details>
