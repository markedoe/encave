# kotlinx.serialization keeps its generated serializers through its own rules.
# Navigation routes are @Serializable classes and need no extra rules either.
# The libraries bring the other rules the app needs: WorkManager keeps the names
# and constructors of the workers (stored by class name in its database),
# zxing-cpp keeps the classes its native code uses, and the activity, the
# application and the widget receivers are kept from the manifest.

# Glance stores the class name of each widget (GlanceAppWidget) next to its
# receiver, and updateAll() looks the widgets up by that name. Keep the names
# stable across releases so an update does not lose track of placed widgets.
-keepnames class * extends androidx.glance.appwidget.GlanceAppWidget
