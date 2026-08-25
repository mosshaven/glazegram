import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun localSecret(name: String): String? {
    val file = file("${System.getProperty("user.home")}/.glazegram/secrets.env")
    if (!file.isFile) return null
    return file.useLines { lines ->
        lines.map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.startsWith("$name=") }
            .map { it.substringAfter('=').trim().trim('"', '\'') }
            .firstOrNull()
    }
}

fun configuredSecret(name: String): String? =
    providers.environmentVariable(name).orNull
        ?: providers.gradleProperty(name).orNull
        ?: localSecret(name)

val telegramAppId = configuredSecret("TELEGRAM_APP_ID") ?: "0"
val telegramAppHash = configuredSecret("TELEGRAM_APP_HASH") ?: ""

android {
    namespace = "com.glazegram"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.slutvibe.glazegram"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha.1"

        buildConfigField("String", "TELEGRAM_APP_ID", "\"$telegramAppId\"")
        buildConfigField("String", "TELEGRAM_APP_HASH", "\"$telegramAppHash\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")

    // Prebuilt official TDLib JNI/API redistribution, pinned for reproducible builds.
    implementation("com.github.capullo-tech:lib-tdlib-android:11850efeb5791d4fd386c0eeec1ebce4c5080435")
}
