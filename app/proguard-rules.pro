# ============================================================
# 体积优化规则：只做「代码收缩」，不做「混淆改名」（-dontobfuscate）
# —— 类名/字段名保持原样，便于排查；R8 负责删掉用不到的代码。
# ============================================================
-dontobfuscate

-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,EnclosingMethod,InnerClasses

# ---- Gson 靠反射读写字段，模型类与歌单类必须整体保留 ----
-keep class com.chumosplayer.model.** { *; }
-keep class com.chumosplayer.util.PlaylistStore$Playlist { *; }

# ---- jaudiotagger：ID3 标签用反射改帧字段（AbstractID3v2Frame/ID3Tags），
#      标签编辑器与「写歌词到标签」依赖它，整包保留 ----
-keep class org.jaudiotagger.** { *; }

# ---- slf4j 走 ServiceLoader 查找 provider（无 provider 时回退 NOP），体积很小 ----
-keep class org.slf4j.** { *; }

# ---- 可选依赖/未使用路径的告警，避免 R8 中断构建 ----
-dontwarn org.slf4j.**
-dontwarn com.hierynomus.**
-dontwarn net.engio.**
-dontwarn org.bouncycastle.**
-dontwarn org.jaudiotagger.**
-dontwarn com.google.gson.**
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ---- 打印被 R8 删掉的条目，便于核对收缩效果 ----
-printusage D:/dsh-build/r8-removed.txt
