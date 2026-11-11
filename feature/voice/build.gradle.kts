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
    implementation(libs.androidx.lifecycle.process)
    // Audio in Calls: plays an agent's track on the call's route (iOS: AVAudioPlayerNode).
    implementation(libs.media3.exoplayer)
    // The real VoiceHapticEngine (Vibrator, ProcessLifecycleOwner) in the ported teardown test.
    testImplementation(libs.robolectric)
    // A ComponentActivity to compose the call transcript in (FloatingCallTranscriptTests).
    testImplementation(libs.androidx.activity.compose)
}
