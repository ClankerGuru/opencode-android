plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.session"
}

// Feature dependencies mirror iOS type references; the graph is acyclic:
// media, voice, system < transcript, composer, settings < session < drawer < app.
dependencies {
    api(project(":feature:transcript"))
    api(project(":feature:composer"))
    api(project(":feature:voice"))
    api(project(":feature:media"))
    api(project(":feature:system"))
    // PredictiveBackHandler / LocalOnBackPressedDispatcherOwner for SessionPageStack's back.
    implementation(libs.androidx.activity.compose)
    // ProviderIcon: OpenCode's provider logos ship as the iOS SVGs (assets/provider-icons), drawn by Coil.
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    // SinceYouLeftClient.liveValue: Gemini Nano via AICore, as iOS uses FoundationModels' on-device model.
    implementation(libs.mlkit.genai.prompt)
    // android.icu (SessionContextText's compact window) in the ported SessionSheetTests line.
    testImplementation(libs.robolectric)
}
