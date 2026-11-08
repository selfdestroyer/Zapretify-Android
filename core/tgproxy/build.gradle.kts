plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Чистый Kotlin без Android: прокси собирается и тестируется на компьютере (этап 1 плана).
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
