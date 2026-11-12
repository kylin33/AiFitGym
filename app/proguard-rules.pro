# FitGym R8 / ProGuard 规则
#
# 开了 isMinifyEnabled + isShrinkResources 之后，编译器只认「代码里直接引用」的东西。
# 这个项目有两处全靠反射/约定工作，规则就是为它们写的：
#   1. Room —— 生成的 *_Impl 靠类名和反射拿实体；
#   2. kotlinx.serialization —— 每个 @Serializable 类旁边有个编译器生成的
#      `Xxx$$serializer` 单例，靠 Companion 暴露出来，被改名就解析不出 JSON。
# 其余（Compose / 协程 / Coil / Navigation）都自带 consumer rules，不用重复写。

# ---------------------------------------------------------------- 基础属性

# 泛型签名与注解：Room 读实体字段类型、序列化读 @SerialName 都依赖它们
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations,AnnotationDefault

# 混淆后崩溃堆栈仍然能落到具体源码行（配合 app/build/outputs/mapping/release/mapping.txt 反解）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------- 组件类

# manifest 里的组件由 AGP 自动生成 keep 规则，这里显式写一遍纯粹是为了
# 「换个构建工具链也不会突然炸」，代价只有几十个类
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Activity

# 前台服务的通知按钮走 PendingIntent，Intent 里的 extras 名是字符串常量，不受影响；
# 但「启动哪个 Service」是按类名找的，所以 Service 必须保住名字
-keepnames class io.github.kylin33.aifitgym.service.WorkoutTimerService { *; }

# ---------------------------------------------------------------- 枚举

# 枚举名是数据：RoutineCategory / SkillBranch / PrMetric 的 name 会写进数据库和备份 JSON。
# 保住 values()/valueOf() 就等于保住了常量名，否则 valueOf 会在混淆包里直接抛异常
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------- Room

# Room 通过类名 + 反射实例化生成的实现，并读取实体字段上的注解
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keep @androidx.room.Embedded class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# 生成的实现类名固定是 <Database>_Impl，Room 用它做一次性校验
-keepnames class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------- kotlinx.serialization

# 官方规则（https://github.com/Kotlin/kotlinx.serialization#android）：
# 每个 @Serializable 类的 Companion 里挂着 `serializer()`，生成的 `Xxx$$serializer`
# 又反过来引用 Companion —— 只删一侧就会 NoClassDefFoundError
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# object 形式的 @Serializable（本项目的备份 payload 里没有，但规则写全一点更省事）
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# 兜底：序列化框架内部会在运行时校验 serializer 的类型参数
-keep,includedescriptorclasses class kotlinx.serialization.json.**$$serializer { *; }
-dontwarn kotlinx.serialization.**

# ---------------------------------------------------------------- 显式保留（反射入口）

# assets 里的动作库 JSON、肌群词表都是按字段名映射的，虽然走编译期序列化器，
# 但保留下来能省掉「改了字段顺序发现解析空值」这类排查成本
-keep class io.github.kylin33.aifitgym.data.catalog.** { *; }
-keep class io.github.kylin33.aifitgym.data.anatomy.MuscleTaxonomy** { *; }

# 备份 / 恢复的文件格式：这几个类的字段名就是 JSON 的 key（没加 @SerialName 时），
# 以后改结构、加版本迁移都要靠老字段能读出来
-keep class io.github.kylin33.aifitgym.data.backup.** { *; }
-keep class io.github.kylin33.aifitgym.data.checkpoint.** { *; }
