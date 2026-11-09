package ai.opencode.feature.system.intents

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process

/**
 * The App Actions door (`actions.intent.OPEN_APP_FEATURE` in `shortcuts.xml`): the only
 * system-action entry other apps can start, since Assistant launches App Actions as itself.
 * It reads nothing but the spoken feature and hands it to [SystemActionActivity], which is
 * private to the app (shortcuts, tiles, widgets and notifications reach it through the app's
 * own identity). iOS runs its intents only from Siri and Shortcuts; here any app could send
 * this intent, so starting a call without the app showing (Start Voice Call in Background)
 * is kept to a verified assistant ([isVerifiedAssistant]); from anything else it opens the
 * app on the call, where the call is seen.
 */
class AppActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val feature = intent.getStringExtra(SystemActionActivity.EXTRA_APP_FEATURE)
        if (feature != null) {
            startActivity(
                Intent(this, SystemActionActivity::class.java)
                    .setAction(SystemActionActivity.ACTION_SYSTEM)
                    .putExtra(SystemActionActivity.EXTRA_APP_FEATURE, feature)
                    .putExtra(SystemActionActivity.EXTRA_FROM_ASSISTANT, isVerifiedAssistant(this)),
            )
        }
        finish()
    }

    companion object {
        /**
         * Google's assistant, which runs App Actions, by the SHA-256 of its signing certificate
         * (Google's release key, read from the Google app on a Play emulator image). Gemini's
         * app (`com.google.android.apps.bard`) is a stub that hands off to the Google app and
         * ships under more than one key, so it isn't pinned.
         */
        private val assistants = mapOf(
            "com.google.android.googlequicksearchbox" to
                "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83".hexToByteArray(),
        )

        /**
         * Whether a verified assistant started [activity]: the system's record of the launching
         * app (`launchedFromPackage`/`launchedFromUid`, which a caller can't forge, unlike the
         * referrer) names Google's assistant, that UID owns the package, and it's signed with
         * Google's key. The system shares the launching app only when it opts in
         * (`ActivityOptions.setShareIdentityEnabled`); otherwise this is false, and the launch
         * gets the path the user confirms.
         */
        fun isVerifiedAssistant(activity: Activity): Boolean =
            isVerifiedAssistant(activity.launchedFromPackage, activity.launchedFromUid, activity.packageManager)

        internal fun isVerifiedAssistant(launchedFromPackage: String?, launchedFromUid: Int, packages: PackageManager): Boolean {
            val certificate = assistants[launchedFromPackage ?: return false] ?: return false
            if (launchedFromUid == Process.INVALID_UID) return false
            if (packages.getPackagesForUid(launchedFromUid)?.contains(launchedFromPackage) != true) return false
            return packages.hasSigningCertificate(launchedFromUid, certificate, PackageManager.CERT_INPUT_SHA256)
        }
    }
}
