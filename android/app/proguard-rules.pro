# kotlinx.serialization: keep generated serializers for BrandConfig and friends.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.yodesla.omniverse.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.yodesla.omniverse.**$$serializer { *; }

# WorkManager builds its Room database reflectively (WorkDatabase_Impl.<init>). R8 full mode
# stripped the no-arg constructor and the release build crashed at startup (2026-09-27).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
