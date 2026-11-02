import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
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

// Release builds (docs/RELEASING.md). The release workflow passes the version from the tag and
// the upload key through the environment; local builds keep the defaults below.
val releaseVersionName: String = providers.gradleProperty("yuvomi.versionName").getOrElse("0.1.0")
val releaseVersionCode: Int = providers.gradleProperty("yuvomi.versionCode").getOrElse("1").toInt()
val releaseKeystore: String? = System.getenv("YUVOMI_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
// Without a release key, the workflow may ask for a debug-signed release (docs/RELEASING.md).
val debugSignRelease: Boolean = providers.gradleProperty("yuvomi.debugSignRelease").getOrElse("false").toBoolean()

android {
    namespace = "io.github.marcomow.yuvomi"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.marcomow.yuvomi"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField("String", "UPSTREAM_PIN", "\"$upstreamPin\"")
        buildConfigField("String", "UPSTREAM_MIN", "\"$upstreamMin\"")
        // In-app updates (ADR 0018): where the release workflow publishes the APK.
        buildConfigField("String", "UPDATE_REPO", "\"marcomow/yuvomi-android\"")
        buildConfigField("boolean", "UPDATES_ENABLED", "false")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("YUVOMI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("YUVOMI_KEY_ALIAS")
                keyPassword = System.getenv("YUVOMI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Only a release build can install a release over itself (same signing key).
            buildConfigField("boolean", "UPDATES_ENABLED", "true")
            signingConfig = when {
                releaseKeystore != null -> signingConfigs.getByName("release")
                debugSignRelease -> signingConfigs.getByName("debug")
                else -> null
            }
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
            // A failing test prints its exception in the build log, which is all CI keeps.
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showCauses = true
                showStackTraces = true
            }
            // A hung test fails the task instead of running into the CI job's limit (the
            // watchdog in .github/scripts prints the stacks first), and slow tests are named.
            it.timeout.set(Duration.ofMinutes(20))
            it.addTestListener(object : TestListener {
                private val started = ConcurrentHashMap<String, Long>()
                override fun beforeSuite(suite: TestDescriptor) {}
                override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
                override fun beforeTest(test: TestDescriptor) {
                    started["${test.className}.${test.name}"] = System.nanoTime()
                }
                override fun afterTest(test: TestDescriptor, result: TestResult) {
                    val name = "${test.className}.${test.name}"
                    val start: Long = started.remove(name) ?: return
                    val seconds = (System.nanoTime() - start) / 1_000_000_000L
                    if (seconds >= 30L) println("Slow test (${seconds}s): $name")
                }
            })
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
