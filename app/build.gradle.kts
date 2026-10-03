import java.util.Properties

/**
 * The release tag pointing at HEAD, if there is one, e.g. "0.0.1" or "v0.0.2-rc1".
 *
 * The tag is the single source of truth for a release, so the About screen
 * (which reads BuildConfig.VERSION_NAME) and the uploaded asset name can never
 * disagree with it. Returns null for an untagged build, which is normal on a
 * working branch and in CI's ordinary (untagged) builds.
 */
fun releaseTag(): String? {
    val tag = try {
        val process = ProcessBuilder("git", "describe", "--tags", "--exact-match", "HEAD")
            .redirectErrorStream(false)
            .start()
        if (process.waitFor() == 0) {
            process.inputStream.bufferedReader().readText().trim()
        } else {
            ""
        }
    } catch (e: Exception) {
        // No git, or not a repository: fall back rather than fail the build.
        ""
    }
    return tag.removePrefix("v").takeIf { SEMVER.matches(it) }
}

private val SEMVER = Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?""")
private val SEMVER_PARTS = Regex("""(\d+)\.(\d+)\.(\d+)""")

// Untagged builds are labelled as such rather than borrowing a release number.
private val DEV_VERSION = "0.0.1-dev"
// Gradle cannot see that the generated BuildConfig depends on git state, so it
// would happily reuse a cached one and the About screen would show a stale
// version. Always regenerate.
tasks.matching { it.name.contains("GenerateBuildConfig", ignoreCase = true) }
    .configureEach {
        outputs.upToDateWhen { false }
    }

val tagVersion: String? = releaseTag()
val versionCodeFromTag: Int? = tagVersion?.let { v ->
    SEMVER_PARTS.find(v)?.groupValues?.let { (_, major, minor, patch) ->
        (major.toLong() * 1_000_000L + minor.toLong() * 1_000L + patch.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Signing credentials are never committed. They come from, in order:
//   1. environment variables (this is what CI uses, fed from repo secrets)
//   2. keystore.properties in this directory, which is git-ignored
// With neither present, `assembleRelease` still builds, just unsigned — so a
// contributor without the key is not blocked, and CI can still smoke-test the
// release variant.
val signingProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(env: String, key: String): String? =
    System.getenv(env) ?: signingProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseKeystoreFile = signingValue("MCQ_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("MCQ_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("MCQ_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("MCQ_KEY_PASSWORD", "keyPassword")

val releaseSigningConfigured =
    releaseKeystoreFile != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.mcqapp"
    // 37 required by navigation-compose 2.10.2 / compose BOM 2026.09 (AAR metadata).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mcqapp"
        minSdk = 26
        targetSdk = 36
        // Derived from the release tag (git tag 0.0.2 -> code 2002), so the
        // versionCode can never need bumping by hand and can never disagree
        // with the tag. Untagged builds fall back to a dev label.
        versionCode = versionCodeFromTag ?: 1
        versionName = tagVersion ?: DEV_VERSION
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Enables v1+v2+v3 so every Android release can install it.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Shrinking and obfuscation. This is what takes the APK from
            // ~22 MB to single figures: material-icons-extended alone is a
            // 34 MB dependency for the two dozen icons actually used, and R8
            // deletes the other ~11,000 classes.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // For BuildConfig.VERSION_NAME, so the About row cannot drift from
        // the version Gradle actually builds.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Imported directly by 16 main sources but previously only resolved
    // transitively through room-ktx and lifecycle-runtime-ktx.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1") {
        // Uber jar ships x86_64 natives only; regular artifact has ARM64.
        exclude(group = "org.conscrypt", module = "conscrypt-openjdk-uber")
    }
    testImplementation("androidx.test:core:1.7.0")
    // Regular Conscrypt 2.7.0 with per-arch native jars: the uber jar that
    // Robolectric pulls in ships x86_64 natives only and breaks its setup
    // with UnsatisfiedLinkError on any other host (both jars on the
    // classpath; the loader picks the matching architecture).
    testImplementation("org.conscrypt:conscrypt-openjdk:2.7.0")
    testImplementation("org.conscrypt:conscrypt-openjdk:2.7.0:linux-aarch_64")
    testImplementation("org.conscrypt:conscrypt-openjdk:2.7.0:linux-x86_64")
}
