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
}

dependencies {
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
}
