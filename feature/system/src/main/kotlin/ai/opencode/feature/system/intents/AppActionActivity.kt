package ai.opencode.feature.system.intents

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The App Actions door (`actions.intent.OPEN_APP_FEATURE` in `shortcuts.xml`): the only
 * system-action entry other apps can start, since Assistant launches App Actions as itself.
 * It reads nothing but the spoken feature and hands it to [SystemActionActivity], which is
 * private to the app (shortcuts, tiles, widgets and notifications reach it through the app's
 * own identity). iOS runs its intents only from Siri and Shortcuts; here any app could send
 * this intent, so starting a call without the app showing (Start Voice Call in Background)
 * is kept to Assistant ([isAssistant]); from anything else it opens the app on the call,
 * where the call is seen.
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
                    .putExtra(SystemActionActivity.EXTRA_FROM_ASSISTANT, isAssistant(this)),
            )
        }
        finish()
    }

    companion object {
        /** Google's assistants, which run App Actions. */
        private val assistants = setOf("com.google.android.googlequicksearchbox", "com.google.android.apps.bard")

        /**
         * Whether the activity was started by an assistant, by the referrer the system records
         * for the launch. A best-effort check: an app can name a referrer of its own.
         */
        fun isAssistant(activity: Activity): Boolean =
            activity.referrer?.takeIf { it.scheme == "android-app" }?.host in assistants
    }
}
