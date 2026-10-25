package ai.opencode.core.ui

import android.content.res.Resources

/**
 * The device the app runs on, as its copy names it (iOS `ThisDevice`, declared in
 * `OpenCode/App/AppView.swift` and used by App, Session and Settings copy). iPhone/iPad map to
 * phone/tablet; a tablet is a smallest width of at least 600 dp, Android's tablet breakpoint.
 */
object ThisDevice {
    val isTablet: Boolean get() = Resources.getSystem().configuration.smallestScreenWidthDp >= 600

    /** “this phone”, “this tablet” (iOS “this iPhone”, “this iPad”). */
    val name: String get() = if (isTablet) "tablet" else "phone"

    /** “your phone”, “your tablet”. */
    val casualName: String get() = if (isTablet) "tablet" else "phone"
}
