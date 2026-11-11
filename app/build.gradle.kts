// AGP 9 компилирует Kotlin сам (встроенная поддержка), отдельный плагин kotlin-android не нужен.
val nativeSourcesPresent = rootProject.file("native/byedpi/main.c").exists() &&
    rootProject.file("native/hev-socks5-tunnel/src").isDirectory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Предварительный идентификатор (план, решение 8). После первого выпуска менять нельзя.
    namespace = "io.github.selfdestroyer.zapretify"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.selfdestroyer.zapretify"
        minSdk = 26 // Android 8.0 — предварительно (план, решение 9)
        targetSdk = 37
        versionCode = 14
        versionName = "0.4.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    // Встроенный набор стратегий — из папки strategies/ репозитория.
    sourceSets["main"].assets.directories += "../strategies"

    // Нативные движки (byedpi и hev-socks5-tunnel, src/main/jni) собираются, только если их исходники подключены в native/
    // (git-подмодули). Без них приложение собирается, но обход в нём недоступен.
    if (nativeSourcesPresent) {
        ndkVersion = "30.0.16248370"
        defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    }
}

dependencies {
    implementation(project(":core:tgproxy"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
