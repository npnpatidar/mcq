plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mcqapp"
    // 37 required by navigation-compose 2.10.2 / compose BOM 2026.09 (AAR metadata).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mcqapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
