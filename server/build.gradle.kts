plugins {
    id("opencode.android.library")
}

android {
    namespace = "ai.opencode.server"
}

dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.okhttp.sse)
    testImplementation(libs.okhttp.mockwebserver)
}
