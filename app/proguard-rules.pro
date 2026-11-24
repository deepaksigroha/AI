# Keep Room entities and generated code
-keep class com.deepaksigroha.imageclean.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase
# WorkManager workers are instantiated reflectively
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
