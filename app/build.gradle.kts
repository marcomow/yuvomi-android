import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The upstream Yuvomi version this build is tested against (ADR 0008).
val upstreamPin: String = rootProject.file("upstream/VERSION").readText().trim()
// The oldest server version the app accepts. Moves forward only when the app
// starts relying on something a newer upstream added (docs/UPSTREAM-SYNC.md).
val upstreamMin = "2.83.0"

android {
    namespace = "io.github.marcomow.yuvomi"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.marcomow.yuvomi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "UPSTREAM_PIN", "\"$upstreamPin\"")
        buildConfigField("String", "UPSTREAM_MIN", "\"$upstreamMin\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("yuvomi.upstreamDir", rootProject.file("upstream").absolutePath)
            it.systemProperty("yuvomi.assetsDir", file("src/main/assets").absolutePath)
            it.systemProperty("yuvomi.sourceDir", file("src/main/java").absolutePath)
            // Contract tests run only when a seeded upstream server is given (docs/UPSTREAM-SYNC.md).
            System.getenv("YUVOMI_CONTRACT_URL")?.let { url -> it.systemProperty("yuvomi.contractUrl", url) }
            // Optional Maven mirror for Robolectric's Android runtime jars (rate-limited sandboxes).
            System.getenv("ROBOLECTRIC_REPO_URL")?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url) }
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
