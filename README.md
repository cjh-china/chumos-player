# 初墨的播放器

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

**v2.1 新增：播放习惯与智能曲库**
- A-B 片段复读：播放页设 A 起点 / B 终点自动循环，切歌自动取消
- 摇一摇切歌：四档灵敏度 + 1.5 秒防抖，关闭档不注册传感器
- 耳机 / 蓝牙断开自动暂停（默认开启，可在设置里关闭）
- 智能歌单：最近添加 / 最常播放 / 久未播放 / 超长曲目，自动生成，支持一键播放全部与随机播放
- M3U8 歌单导出、M3U 歌单导入（绝对路径 / 小写路径 / 文件名三级匹配，去重后加入队列，不改动磁盘文件）
- 歌词时间校准：±5 秒、100ms 步进，播放页与设置页共用，偏好随备份导出

**v2.2 新增：文本转音频（接收文本生成音频）**
- 输入或粘贴任意文字，用系统文字转语音引擎离线合成 WAV，作为一首本地曲目进入播放队列
- 语速 / 音调 0.5x–2.0x 可调并持久化；单次最多 4000 字，超长自动截断并提示
- 生成记录保留最近 30 条，支持播放 / 下一首播放 / 加入队列末尾 / 复制原文 / 删除，元数据随备份导出
- 生成的音频可直接用倍速、A-B 复读、均衡器、收藏、通知栏与锁屏控制，与本地歌曲完全一致
- 「我的」页一级入口；同时对外开放 Intent 接口（见下一节）

## 对外接口（文本转音频）

`TextToAudioActivity` 在 `AndroidManifest.xml` 中声明 `android:exported="true"`，注册三个 intent-filter，
其他 App 无需任何权限即可把文本交给初墨合成并播放。

### 1. 系统分享

任意 App 执行 `ACTION_SEND` + `text/plain`，初墨会以「**朗读文本**」出现在分享菜单里：

```java
Intent share = new Intent(Intent.ACTION_SEND);
share.setType("text/plain");
share.putExtra(Intent.EXTRA_TEXT, "今天天气不错，适合听一首歌。");
share.putExtra(Intent.EXTRA_SUBJECT, "天气播报");   // 可选，作为曲目标题
startActivity(Intent.createChooser(share, "分享到"));
```

### 2. 选中文字的上下文菜单（Android 6.0+）

`ACTION_PROCESS_TEXT` + `text/plain`，系统会在长按选字菜单里加入「朗读文本」，`EXTRA_PROCESS_TEXT`
即选中内容。只读文本不会回写原 App。

### 3. 显式 API

| 项 | 值 | 说明 |
|---|---|---|
| Action | `com.chumosplayer.action.SPEAK_TEXT` | 固定字符串 |
| Extra | `com.chumosplayer.extra.TEXT` | `String`，**必填**，待合成文本 |
| Extra | `com.chumosplayer.extra.TITLE` | `String`，可选，曲目标题；缺省截取正文前 24 字 |
| Extra | `com.chumosplayer.extra.AUTO_PLAY` | `boolean`，可选，默认 `false` |

```java
Intent i = new Intent("com.chumosplayer.action.SPEAK_TEXT");
i.putExtra("com.chumosplayer.extra.TEXT", "今天天气不错，适合听一首歌。");
i.putExtra("com.chumosplayer.extra.TITLE", "天气播报");
i.putExtra("com.chumosplayer.extra.AUTO_PLAY", true); // 自动生成并进入播放页
i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);            // 从非 Activity Context 调用时必需
startActivity(i);
```

命令行验证：

```bash
adb shell am start -a com.chumosplayer.action.SPEAK_TEXT \
  --es com.chumosplayer.extra.TEXT "Hello Chumo" \
  --ez com.chumosplayer.extra.AUTO_PLAY true \
  -n com.chumosplayer/.TextToAudioActivity
```

### 行为与安全约束

- `AUTO_PLAY=false`（默认）：只把文本填入输入框，用户确认后再点「生成并播放」，保证**用户可控**。
- `AUTO_PLAY=true`：自动合成，成功后自动进入播放页。
- 只接收纯文本，**不接受外部输出路径或文件名**；WAV 一律写入应用私有 `filesDir/tts_audio`，不需要存储权限，卸载自动清理。
- 外部文本统一清洗（不间断空格、首尾空白）并截断到 4000 字；空文本忽略，不会触发播放。
- 语音由设备系统 TTS 引擎生成，不联网。设备需启用「文字转语音」服务并安装语音数据；缺失时页面提供「前往语音设置」入口，中文语音包缺失会自动回退系统默认语言。
- 生成的 WAV 走 `ParcelFileDescriptor` 写入（API 21+ 的 `synthesizeToFile` 重载），因此引擎进程可以写应用私有目录。

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
