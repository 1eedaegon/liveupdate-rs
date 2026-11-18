# The public bridge is called from JNI by its binary name.
-keep class io.github.eedaegon.liveupdate.LiveUpdateBridge { public static *; }

# WearOngoing names classes of androidx.wear:wear-ongoing, which a watch application brings and no other
# has. It stays the one class that names them: kept, its code is not moved into its callers.
-dontwarn androidx.wear.ongoing.**
-dontwarn androidx.core.app.NotificationCompat$Builder
-keep class io.github.eedaegon.liveupdate.WearOngoing { *; }
