pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Zapretify-Android"

// core:tgproxy — Telegram-прокси как обычная Kotlin-библиотека (тесты идут на компьютере, без телефона).
// app — Android-приложение.
include(":core:tgproxy")
include(":app")
