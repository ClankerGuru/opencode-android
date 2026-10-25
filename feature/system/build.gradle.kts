plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.system"
}

// Feature dependencies mirror iOS type references; the graph is acyclic:
// media, voice, system < transcript, composer, settings < session < drawer < app.
dependencies {
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.datastore.preferences)
}
