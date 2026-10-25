plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.media"
}

// Feature dependencies mirror iOS type references; the graph is acyclic:
// media, voice, system < transcript, composer, settings < session < drawer < app.
dependencies {
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    // The debug-only media lab harness (src/debug, MediaLabActivity).
    debugImplementation(libs.androidx.activity.compose)
}
