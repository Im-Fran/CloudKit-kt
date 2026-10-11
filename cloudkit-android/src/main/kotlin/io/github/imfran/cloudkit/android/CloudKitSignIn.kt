package io.github.imfran.cloudkit.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.browser.customtabs.CustomTabsIntent
import io.github.imfran.cloudkit.Container
import io.github.imfran.cloudkit.model.UserIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * iCloud sign-in for Android: opens Apple's sign-in page in a Custom Tab and finishes the sign-in when Apple
 * redirects to `<cloudkitAuthScheme>://…?ckWebAuthToken=…`.
 *
 * Security: other apps can register the same custom scheme and intercept the token. In production, use a
 * verified https App Link as the Sign In Callback and add it to [CloudKitAuthCallbackActivity] (see README).
 *
 * ```
 * // Application.onCreate
 * CloudKitSignIn.register(container)
 * // UI
 * lifecycleScope.launch { val user = CloudKitSignIn.signIn(activity, container) }
 * ```
 */
public object CloudKitSignIn {
    @Volatile private var container: Container? = null
    @Volatile private var returnActivity: Class<out Activity>? = null
    // The callback activity is exported, so any app or web page can fire it. Only the first callback after
    // signIn() opened the Custom Tab is honored; otherwise an attacker could sign the user into their account.
    // Holds the SystemClock.elapsedRealtime() deadline of the sign-in in progress, 0 when none.
    private val pendingUntil = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)

    /** Failures while completing a sign-in from the callback. */
    public val errors: SharedFlow<Throwable> = _errors.asSharedFlow()

    /** Container that receives callbacks. */
    public fun register(container: Container) {
        this.container = container
    }

    /**
     * Returns the signed-in user, opening the Apple sign-in page first when needed. Suspends until the
     * callback arrives; cancel the calling coroutine if the user abandons the flow.
     */
    public suspend fun signIn(activity: Activity, container: Container): UserIdentity {
        register(container)
        container.setUpAuth()?.let { return it }
        val url = checkNotNull(container.signInUrl.value) { "CloudKit did not return a sign-in URL" }
        returnActivity = activity.javaClass
        pendingUntil.set(SystemClock.elapsedRealtime() + SIGN_IN_WINDOW_MS)
        try {
            CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
            return container.whenUserSignsIn()
        } finally {
            pendingUntil.set(0)
        }
    }

    private const val SIGN_IN_WINDOW_MS = 10 * 60 * 1000L

    internal fun handleCallback(context: Context, uri: Uri) {
        val target = container
        if (SystemClock.elapsedRealtime() > pendingUntil.getAndSet(0)) {
            // ponytail: also rejects a legit callback after process death; the user just signs in again.
            _errors.tryEmit(SecurityException("Ignored CloudKit sign-in callback with no sign-in in progress"))
        } else if (target == null) {
            _errors.tryEmit(IllegalStateException("CloudKitSignIn.register(container) was not called"))
        } else {
            scope.launch { runCatching { target.completeSignIn(uri.toString()) }.onFailure { _errors.tryEmit(it) } }
        }
        // Bring the app back on top of the Custom Tab.
        val back = returnActivity?.let { Intent(context, it) } ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
        back?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)?.let(context::startActivity)
    }
}

/** Target of Apple's sign-in redirect; declared in this library's manifest. */
public class CloudKitAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { CloudKitSignIn.handleCallback(this, it) }
        finish()
    }
}
