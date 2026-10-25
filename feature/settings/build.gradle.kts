plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.settings"
}

// Feature dependencies mirror iOS type references; the graph is acyclic:
// media, voice, system < transcript, composer, settings < session < drawer < app.
dependencies {
    api(project(":feature:voice"))
    api(project(":feature:system"))
    // Settings › Advanced › Developer shows the devtools screen (devtools depends only on core and server).
    implementation(project(":devtools"))
}

dependencies {
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
}

// Pairing scanner (servers/): CameraX frames decoded by ZXing; the camera permission launcher.
dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    implementation(libs.zxing.core)
}
