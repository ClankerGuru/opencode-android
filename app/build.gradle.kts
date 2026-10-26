plugins {
    id("opencode.android.application")
}

android {
    namespace = "ai.opencode.app"
    defaultConfig {
        // Matches the iOS bundle identifier.
        applicationId = "ai.opencode.mobilecode"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":server"))
    implementation(project(":feature:session"))
    implementation(project(":feature:transcript"))
    implementation(project(":feature:composer"))
    implementation(project(":feature:drawer"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:voice"))
    implementation(project(":feature:media"))
    implementation(project(":feature:system"))
    implementation(project(":devtools"))
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.process)
    debugImplementation(libs.compose.ui.tooling)
    // TestIsolationGuard, WireFixture and the network block (see AGENTS.md "Tests").
    testImplementation(testFixtures(project(":server")))
}
