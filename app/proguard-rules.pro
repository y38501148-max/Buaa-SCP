# JSON 库由系统提供，保留反射访问入口。
-keep class org.json.** { *; }

# 保留行号，便于线上崩溃定位（不保留源文件名）。
-keepattributes LineNumberTable
