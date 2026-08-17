# Twig 🌿

[English](README.md) · **简体中文**

一个体积优先的双面板 Android 文件管理器,对标 X-plore。原生 Kotlin + XML View,
不引入 Material 库,依赖压到最低。能手写就手写——WebDAV、S3 签名、git、restic
全部从零实现。

**版本 1.1.1**(versionCode 270)· minSdk 24 / targetSdk 34 / compileSdk 36 ·
[GPL-3.0](LICENSE)

<!-- TODO: 截图 / GIF 放这里。四个最有说服力的演示:
     1. 跨来源复制:SMB 上的目录直接压成本地 zip
     2. 树式就地展开,压缩包像目录一样点开
     3. 占用图(treemap)双指缩放
     4. WiFi 共享后,从电脑上直接挂载成网络盘 -->

---

## 为什么是 Twig

**一棵树,所有来源。** 本地存储、压缩包、FTP、SFTP、SMB、WebDAV、S3、restic 仓库,
对 UI 来说是同一个东西。任意两者之间的复制走同一条代码路径——SMB 上的目录可以直接
压成本地 zip,远程压缩包里的文件可以流式传到 FTP 服务器。

**它一直很小。** 单设备下载约 6.7 MB,而这里面**包含**了 FFmpeg 音频解码器、
一套 SMB 实现和一个视频播放器。同类文件管理器普遍是这个数字的几倍。加依赖之前
先算它的 APK 增量。

**它做到了别的文件管理器止步的地方。**

- **只读浏览 restic 备份仓库**,在设备上解密,快照按日期呈现为目录——据我们所知,
  Android 上没有第二个这么做的。
- **网络视频缩略图不下载整个文件。** Twig 自己解析容器(MP4 采样表、Matroska EBML
  Cues),定位到时长 1/10 处的关键帧,只取约 2 MB。74 GB 的远程 remux 也能在几秒内
  出图。
- **纯 Kotlin 从零写的 git 客户端**——状态、历史、双栏 patience diff、worktree 与
  子模块,本地仓库和远程仓库都支持。
- **真正的蓝光 M2TS 播放**:192 字节 BDAV 包、HDMV 私有 stream type(DTS-HD MA、
  经内嵌 AC-3 core 的 TrueHD)、PGS 位图字幕——这些 media3 都不直接支持。
- **root / Shizuku 特权访问是本地文件系统的回落**,不是另起一棵树。所以
  `/data/data/…` 往 SMB 复制、缩略图、搜索全都零改动可用。
- **WiFi 共享**:Twig 反过来当 HTTP 与 WebDAV 服务器,电脑可以直接挂载——而且能下载
  位于 SMB 共享上、甚至压缩包内部的文件,因为对服务端来说它们都只是 `openInput()`。

---

## 体积

1.1.1 release 构建(R8 + 资源裁剪)实测:

| | 大小 |
|---|---|
| Release APK(`arm64-v8a` + `x86_64`) | 9.0 MB |
| **单设备下载(`arm64-v8a`)** | **≈ 6.7 MB** |

构成:

| 组件 | 大小 |
|---|---|
| `classes.dex` | 2.6 MB |
| Bouncy Castle | 1.3 MB |
| `libffmpegJNI.so` | 1.4 MB |
| `libsamba_jni.so`(libsmb2) | 464 KB |
| 资源(`resources.arsc` + `res`) | 793 KB |
| `libtwigzstd` / `libtermux` / `libtwigpty` | 108 KB |

每种来源都是独立的 Gradle 模块,所以构建时可以裁掉用不着的部分——去掉媒体播放与
网络来源,上表中的绝大部分就没了。

---

## 一句话架构

所有来源——本地、压缩包、FTP、SFTP、SMB、WebDAV、S3、restic——实现同一套
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
| **ZIP** | **读 + 写**,老包 GBK 名自动识别 | 当文件系统挂载,点开即进入 |
| **7z** | 读 + **打包写入**(LZMA2) | commons-compress + xz |
| **RAR** | 只读(RAR4) | junrar |
| **加密压缩包** | 读 zip/7z/rar,**创建** AES-256 zip/7z | WinZip AES 与老式 ZipCrypto 全手写 |
| **FTP** | 读写全功能 | Apache Commons Net |
| **SFTP** | 读写全功能 | SSHJ(带完整 Bouncy Castle 解决 X25519) |
| **SMB / CIFS** | 读写全功能 | libsmb2,经 NDK/JNI |
| **WebDAV** | 读写全功能 | 手写 PROPFIND/MKCOL/MOVE,不引 SDK |
| **S3 兼容对象存储** | 读写全功能 | 手写 SigV4 + REST——AWS S3、MinIO、R2、OSS、COS、B2 |
| **restic** | 只读,解密 | 从零实现;仓库格式 v1 与 v2 |
| **SAF 文档树** | 读写 | 没有 `MANAGE_EXTERNAL_STORAGE` 时的系统级回退 |
| **特权(root / Shizuku)** | 读写 | 不是独立来源,而是普通 API 够不着的本地路径的回落 |

网络来源展开即连接,支持多服务器(每台一个唯一 scheme),配置持久化。解压就是一次
跨来源复制,压缩也是——所以"把 SMB 上的目录压缩到本地"不需要任何特例代码。

### 浏览

- **一棵完整的树。** 顶级节点是内部存储、根目录、局域网(SMB)、FTP、SSH、WebDAV、
  S3、文档树和收藏。压缩包是可展开的文件节点。
- **永远就地展开,从不"进入"目录。** 当前目录就是你最后点的那个,它同时是新建、
  复制、移动的目标。
- 竖屏单面板(左右滑动切换),**横屏双面板并排**。
- 多选、跨面板复制/移动(带进度与取消)、新建目录、重命名、递归删除、剪贴板式粘贴。
- **收藏**与**最近位置**存的是"如何到达"(连接标签 + 路径),不是会话内的动态
  scheme,所以重启后依然有效。
- **恢复上次位置**,逐级展开;恢复期间一碰列表就交还控制权。
- **网格视图**,与缩略图开关正交,列数按面板宽度自适应。
- **属性卡片**内嵌在文件行下方:EXIF、媒体轨道、应用信息、哈希——全走系统 API,
  且绝不为了填一个字段去整读文件。
- **占用图**(SpaceSniffer 式 treemap)内嵌在面板里,双指缩放,长按菜单与树共用。
- **Twig 可以当文件选择器**,对外接 `GET_CONTENT`,对内替掉 SAF——所以能选到
  SMB 共享里、压缩包里的文件,而系统选择器看不见这些来源。

### 查看器与播放器

所有查看器都经 `FsRegistry` 读取,本地、压缩包内、远程文件一视同仁。

- 文本查看器,手写词法着色,多主题
- Hex 查看器
- 图片查看器(降采样防 OOM),幻灯片边扫边播,找到第一张就先显示
- **视频/音频播放器**(media3 + FFmpeg 软解),覆盖 AVI、真 M2TS、HDMV 私有音轨与
  PGS 字幕;系统解码器崩溃时两级自动降级
- 音乐播放器,带波形显示

### 终端

- **本地 shell** 跑在真 PTY 上,**SSH 会话**走 SSHJ,同一个会话列表,顶部切换
- **特权终端**(root / Shizuku)始终是单独一项、身份写在文案里——绝不把普通 shell
  悄悄换成 root
- 字体与配色可导入(任何 termux `colors.properties` 都能用);双指缩放字号,
  行列变化即同步远端 PTY
- **命令快捷方式**:给 SFTP 目录或服务器挂一条命令,在终端里跑或后台静默执行,
  可固定到桌面

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

---

## 模块

| 模块 | 内容 |
|---|---|
| `:core-fs` | 纯 JVM:`XFile` / `FileSystem` / `FsRegistry` / `CopyEngine` |
| `:fs-local` | `LocalFileSystem` + `priv/`:root/Shizuku 共用的特权 shell 回落 |
| `:fs-archive` | `ArchiveFileSystem` + zip(读写、加密)/ 7z / RAR,以及 `ArchiveWriter` |
| `:fs-network` | `FtpFileSystem` / `SftpFileSystem` / `WebDavFileSystem` / `S3FileSystem` |
| `:fs-smb` | `SmbFileSystem`——libsmb2,经 NDK/JNI |
| `:fs-restic` | restic 仓库读取器(纯 Kotlin) |
| `:fs-zstd` | `NativeZstd`——zstd,经 JNI |
| `:git-lite` | 纯 Kotlin 迷你 git |
| `:app` | 双面板 UI、查看器、终端、Git 视图、占用图、WiFi 共享 |

---

## 构建

```bash
./gradlew :app:assembleDebug      # 可安装的 debug APK
./gradlew :app:assembleRelease    # R8 优化的 release
./gradlew :app:bundleRelease      # 上架用 AAB(按设备拆分,下载更小)
```

测试:

```bash
./gradlew :core-fs:test :fs-archive:test :fs-network:test :fs-restic:test :git-lite:test
./gradlew :app:testReleaseUnitTest
```

说明:build-tools 固定 36.1.0;只构建 `arm64-v8a` 与 `x86_64`;R8 开启,
libsmb2 与 zstd 的 JNI 入口按名 keep。

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

实现决策及其来龙去脉——包括一长串代价高昂的排错记录——记在 [CLAUDE.md](CLAUDE.md)。
改终端、缩略图、TS 解复用这类子系统之前,先读对应那节。

---

## 路线图

- `:fs-cloud` —— Google Drive / Dropbox / OneDrive,走纯 REST,不用各家 SDK
- 全局搜索
- 体积:Bouncy Castle 瘦身(Conscrypt-only,需真机验证)

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
- **0.8 / 0.9** —— 对齐 X-plore 的 UX:树就地展开、展开即连接、竖屏单面板 +
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

</details>
