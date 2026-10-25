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
}
