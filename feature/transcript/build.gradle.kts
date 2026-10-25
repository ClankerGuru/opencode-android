plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.transcript"
}

// Feature dependencies mirror iOS type references; the graph is acyclic:
// media, voice, system < transcript, composer, settings < session < drawer < app.
dependencies {
    api(project(":feature:media"))
    implementation(libs.coil.compose)
    implementation(libs.highlights)
    implementation(libs.jlatexmath.android)
}
