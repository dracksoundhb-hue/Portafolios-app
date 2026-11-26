package com.portafolios.app.security

import android.content.Context

/** Non-secret preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var lockEnabled: Boolean
        get() = prefs.getBoolean("lock_enabled", true)
        set(v) = prefs.edit().putBoolean("lock_enabled", v).apply()

    /** Off by default: screenshots and screen recording show a black screen. */
    var allowScreenshots: Boolean
        get() = prefs.getBoolean("allow_screenshots", false)
        set(v) = prefs.edit().putBoolean("allow_screenshots", v).apply()

    /** Seconds in background before the app locks again. */
    var lockTimeoutSeconds: Int
        get() = prefs.getInt("lock_timeout", 60)
        set(v) = prefs.edit().putInt("lock_timeout", v).apply()
}
