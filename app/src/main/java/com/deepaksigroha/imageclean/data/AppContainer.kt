package com.deepaksigroha.imageclean.data

import android.content.Context
import com.deepaksigroha.imageclean.data.db.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Simple manual dependency container (keeps the build free of annotation-processor DI). */
class AppContainer(ctx: Context) {
    val appContext: Context = ctx.applicationContext
    val db: AppDatabase = AppDatabase.create(appContext)
    val trust = TrustStore(appContext)
    val analyzer = ImageAnalyzer(appContext, trust)
    val cleaner = MetadataCleaner(appContext)
    val exporter = SocialExporter(cleaner)
    val prefs = Prefs(appContext)
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(sp.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme
    private val _dynamic = MutableStateFlow(sp.getBoolean("dynamic", true))
    val dynamicColor: StateFlow<Boolean> = _dynamic
    private val _quality = MutableStateFlow(sp.getInt("quality", 90))
    val defaultQuality: StateFlow<Int> = _quality
    private val _keepCredit = MutableStateFlow(sp.getBoolean("keepCredit", true))
    val keepCredit: StateFlow<Boolean> = _keepCredit

    fun setTheme(t: ThemeMode) { sp.edit().putString("theme", t.name).apply(); _theme.value = t }
    fun setDynamic(b: Boolean) { sp.edit().putBoolean("dynamic", b).apply(); _dynamic.value = b }
    fun setQuality(q: Int) { sp.edit().putInt("quality", q).apply(); _quality.value = q }
    fun setKeepCredit(b: Boolean) { sp.edit().putBoolean("keepCredit", b).apply(); _keepCredit.value = b }
}
