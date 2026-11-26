package com.portafolios.app

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.portafolios.app.ui.AppRoot
import com.portafolios.app.ui.screens.LockScreen
import com.portafolios.app.ui.theme.PortafoliosTheme

class MainActivity : FragmentActivity() {
    private val locked = mutableStateOf(true)
    private lateinit var prompt: BiometricPrompt

    private val container get() = (application as PortafoliosApp).container

    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotSetting(window, container.settings.allowScreenshots)
        enableEdgeToEdge()
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlockedInProcess = true
                locked.value = false
            }
        })
        // Survive rotation without asking again, but never across a process restart.
        locked.value = lockRequired() && !(unlockedInProcess && !timedOut())
        setContent {
            PortafoliosTheme {
                if (locked.value) LockScreen(onUnlock = ::authenticate, canAuthenticate = canAuthenticate())
                else AppRoot(container)
            }
        }
        if (locked.value) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (!locked.value && timedOut() && lockRequired()) {
            unlockedInProcess = false
            locked.value = true
            authenticate()
        }
        backgroundedAt = 0L
    }

    private fun timedOut(): Boolean =
        backgroundedAt != 0L && (SystemClock.elapsedRealtime() - backgroundedAt) / 1000 >= container.settings.lockTimeoutSeconds

    private fun canAuthenticate() = BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    /** The lock is on unless the user turned it off or the device has no screen lock at all. */
    private fun lockRequired() = container.settings.lockEnabled && canAuthenticate()

    private fun authenticate() {
        if (!canAuthenticate()) { locked.value = false; return }
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear Portafolios")
                .setSubtitle("Usa tu huella, cara o PIN")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    companion object {
        /** Unless the user allows them: no screenshots, no screen recording, blank preview in recent apps. */
        fun applyScreenshotSetting(window: android.view.Window, allow: Boolean) {
            if (allow) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Process-wide: cleared when the process dies, so a restart always asks again.
        private var unlockedInProcess = false
        private var backgroundedAt = 0L
    }
}
