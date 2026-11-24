# Keep line numbers so stack traces in the debug log stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The shell server (Shevery / Shizuku) sends its binder wrapped in moe.shizuku.api.BinderContainer, unparcelled by class
# name, and talks the moe.shizuku.server AIDL interfaces: their names and members must stay as they are.
-keep class moe.shizuku.api.BinderContainer { *; }
-keep class moe.shizuku.server.** { *; }
-keep class rikka.shizuku.** { *; }
