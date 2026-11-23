# Gson читає й пише поля через рефлексію: після перейменування R8 JSON перестав би збігатися
# з DTO сервера та з файлами, вже збереженими на пристрої.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class ua.kpi.building_audit.data.remote.** { <fields>; <init>(...); }
-keep class ua.kpi.building_audit.data.files.** { <fields>; <init>(...); }
-keep class ua.kpi.building_audit.domain.** { <fields>; <init>(...); }
-keep class * extends com.google.gson.reflect.TypeToken

# osmdroid посилається на необов'язкові класи, яких немає в Android SDK.
-dontwarn org.osmdroid.**

# Номери рядків у стеках падінь лишаються, імена файлів приховуються.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
