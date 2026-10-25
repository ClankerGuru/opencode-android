plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.voice"
}

// Feature dependencies mirror iOS type references; the graph is acyclic:
// media, voice, system < transcript, composer, settings < session < drawer < app.
dependencies {
    implementation(libs.webrtc)
}
