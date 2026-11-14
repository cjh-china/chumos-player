# 初墨播放器 · SimpleMusic

一个功能覆盖较全的 Android 音乐播放器（Java + 传统 View，minSdk 21 / targetSdk 34）。

## 功能

**播放**
- 本地曲库 + 在线搜索播放（网易云、B 站）
- 播放模式：列表循环 / 随机播放 / 单曲循环
- 睡眠定时（15/30/60 分钟、本首歌结束）
- 进度拖动（含缓冲期 seek 排队、拖动期间进度钉住不回弹）
- 逐字歌词（增强型 LRC `<00:12.50>`）+ 多歌词源优先级（网易云 / 酷狗，失败自动兜底）
- 桌面悬浮歌词、桌面音乐小组件、媒体通知与锁屏控制（MediaStyle）

**曲库管理**
- 扫描本地音乐、手动添加扫描目录、屏蔽文件夹
- 删除歌曲（连带清理同名歌词/封面，并从歌单、收藏、历史中移除）
- 标签编辑：标题 / 艺术家 / 专辑 / 歌词（jaudiotagger，MP3=USLT、FLAC=LYRICS）
- 联网自动补全专辑封面（落盘为同名 `.jpg`，每首只联网一次）

**歌单与收藏**
- 歌单：新建、重命名、删除、多选添加、上移下移排序、JSON 导入导出
- 我的收藏（红心），与歌单、播放历史一样支持备份恢复
- 当前播放队列查看（自动定位到正在播放的那首）

**远程**
- WebDAV 串流（PROPFIND 列目录 + Basic 认证 + MediaPlayer 带鉴权头播放）
- SMB 串流（smbj，本地 HTTP 代理 + Range 支持，边下边播、可拖进度）

**个性化**
- 深色 / 浅色 / **AMOLED 纯黑**，6 套主题色（靛蓝/樱粉/薄荷/紫罗兰/琥珀/翠绿）
- 首页分类标签可配置、问候语弹出时间、在线歌词音源优先级、联网补全封面开关

**界面与交互**
- 底部导航「本地 / 发现 / 我的」三个一级入口常驻可见；「我的」聚合页集中收藏、歌单、历史、队列、统计、音效、定时、远程、设置九个快捷入口
- 悬浮迷你播放条：圆角卡片 + 细进度线 + 收藏按钮，左右滑切歌、上滑展开播放页
- 曲库内实时搜索（歌名 / 歌手 / 专辑 / 路径）、四种排序、空状态引导、正在播放高亮、常驻「更多」操作菜单
- 播放队列底部弹层：当前曲目高亮、点击切歌、上移下移、移除、清空；歌曲支持「下一首播放 / 加入队列末尾」
- 播放页快捷工具：倍速、睡眠定时、均衡器、更多（封面/歌词切换、加入歌单、歌曲信息、桌面悬浮歌词、设置）
- 听歌统计：累计播放次数、收听时长、Top 歌曲 / 歌手，随备份一起导出
- 设计系统：60-30-10 配色、8dp 网格、语义色与主色 10 级色阶、48dp 触控目标、WCAG AA 对比度、完整无障碍标签，深浅 / AMOLED / 6 套主题色一致适配

## 构建

```powershell
gradle assembleDebug assembleRelease
```

- 需 **JDK 17**：AGP 8.1.4 / Gradle 8.x 无法在 JDK 22 上运行（会报 `Unsupported class file major version 66`）
- 工作区目录名含非 ASCII 字符，`gradle.properties` 中已通过 `android.overridePathCheck=true` 放行
- release 开启 **R8 代码收缩 + 资源收缩**，并在 `app/proguard-rules.pro` 中使用 `-dontobfuscate`
  **（只删无用代码、不改类名，便于排查）**，APK 从 8.35 MB 降到约 4.09 MB
- `app/libs/*.jar` 为 SMB（smbj + BouncyCastle 等）依赖，必须随仓库提交

> 签名文件 `release.jks` 与口令 `keystore.properties` 已被 `.gitignore` 排除，**不会进入仓库**；
> 真要发布需自行放置到项目根目录。

## 说明

- 功能上参考了开源项目 [howshea/ArtisanMusic](https://github.com/howshea/ArtisanMusic)（GPL-3.0）的功能清单，
  **全部为独立实现，未复制其任何代码**。
- 依赖：androidx、Material、Gson、jaudiotagger、smbj。
