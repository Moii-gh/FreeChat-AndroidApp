import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("kotlin-kapt")
}

val envFile = rootProject.file(".env")
val envVars = mutableMapOf<String, String>()
if (envFile.exists()) {
    envFile.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
            val (key, value) = trimmed.split("=", limit = 2)
            envVars[key.trim()] = value.trim()
        }
    }
}

fun String.toBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.example.chatapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.chatapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PRIMARY_AI_API_KEY", (envVars["PRIMARY_AI_API_KEY"] ?: "").toBuildConfigString())
        buildConfigField("String", "SECONDARY_AI_API_KEY", (envVars["SECONDARY_AI_API_KEY"] ?: "").toBuildConfigString())
        buildConfigField(
            "String",
            "APP_API_BASE_URL",
            (envVars["APP_API_BASE_URL"] ?: "http://10.0.2.2:4000/api/").toBuildConfigString()
        )
        buildConfigField("String", "PUBLIC_INFO_URL", (envVars["PUBLIC_INFO_URL"] ?: "").toBuildConfigString())
        buildConfigField("String", "SUPPORT_URL", (envVars["SUPPORT_URL"] ?: "").toBuildConfigString())
        buildConfigField(
            "String",
            "PRIMARY_AI_STREAM_URL_TEMPLATE",
            (envVars["PRIMARY_AI_STREAM_URL_TEMPLATE"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "PRIMARY_AI_IMAGE_URL",
            (envVars["PRIMARY_AI_IMAGE_URL"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "SECONDARY_AI_CHAT_URL",
            (envVars["SECONDARY_AI_CHAT_URL"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "SECONDARY_AI_IMAGE_URL",
            (envVars["SECONDARY_AI_IMAGE_URL"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "PRIMARY_AI_TEXT_MODEL",
            (envVars["PRIMARY_AI_TEXT_MODEL"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "PRIMARY_AI_VISION_MODEL",
            (envVars["PRIMARY_AI_VISION_MODEL"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "PRIMARY_AI_IMAGE_MODEL",
            (envVars["PRIMARY_AI_IMAGE_MODEL"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "SECONDARY_AI_TEXT_MODEL",
            (envVars["SECONDARY_AI_TEXT_MODEL"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "SECONDARY_AI_VISION_MODEL",
            (envVars["SECONDARY_AI_VISION_MODEL"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "SECONDARY_AI_IMAGE_MODEL",
            (envVars["SECONDARY_AI_IMAGE_MODEL"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "SECONDARY_AI_SEARCH_MODEL",
            (envVars["SECONDARY_AI_SEARCH_MODEL"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "SECONDARY_AI_TITLE_MODEL",
            (envVars["SECONDARY_AI_TITLE_MODEL"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "SECONDARY_AI_SUMMARY_MODEL",
            (envVars["SECONDARY_AI_SUMMARY_MODEL"] ?: "").toBuildConfigString()
        )
        buildConfigField(
            "String",
            "SECONDARY_AI_AUDIT_MODEL",
            (envVars["SECONDARY_AI_AUDIT_MODEL"] ?: "").toBuildConfigString()
        )
        val telegramLoginClientId = envVars["TELEGRAM_LOGIN_CLIENT_ID"] ?: ""
        val telegramLoginRedirectUri = envVars["TELEGRAM_LOGIN_REDIRECT_URI"]
            ?: if (telegramLoginClientId.isNotBlank()) {
                "https://app${telegramLoginClientId}-login.tg.dev/tglogin"
            } else {
                "https://app0-login.tg.dev/tglogin"
            }
        val telegramLoginScopes = envVars["TELEGRAM_LOGIN_SCOPES"] ?: "profile"
        val telegramLoginRedirectHost = runCatching {
            URI(telegramLoginRedirectUri).host
        }.getOrNull().orEmpty()

        buildConfigField("String", "TELEGRAM_LOGIN_CLIENT_ID", telegramLoginClientId.toBuildConfigString())
        buildConfigField("String", "TELEGRAM_LOGIN_REDIRECT_URI", telegramLoginRedirectUri.toBuildConfigString())
        buildConfigField("String", "TELEGRAM_LOGIN_SCOPES", telegramLoginScopes.toBuildConfigString())
        manifestPlaceholders["telegramLoginRedirectScheme"] = runCatching {
            URI(telegramLoginRedirectUri).scheme
        }.getOrNull().orEmpty().ifBlank { "https" }
        manifestPlaceholders["telegramLoginRedirectHost"] = telegramLoginRedirectHost.ifBlank {
            "app0-login.tg.dev"
        }
        manifestPlaceholders["telegramLoginRedirectPath"] = runCatching {
            URI(telegramLoginRedirectUri).path
        }.getOrNull().orEmpty().ifBlank { "/tglogin" }

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
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.foundation:foundation:1.7.3")
    implementation("androidx.compose.ui:ui:1.7.3")
    implementation("androidx.compose.ui:ui-graphics:1.7.3")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.3")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.3")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    kapt("androidx.room:room-compiler:2.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.browser:browser:1.8.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.json:json:20240303")

    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.yandex.android:mobileads:7.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.3")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.3")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.3")
}
