plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.4.10"
}

android {
    sourceSets.getByName("main").assets.srcDir("../web/maps")
    namespace = "cz.inzenyri.mobile"
    compileSdk = 35

    defaultConfig {
        val githubAppClientId =
            providers
                .gradleProperty("githubAppClientId")
                .orElse(providers.environmentVariable("GH_APP_CLIENT_ID"))
                .orElse("")
                .get()
        applicationId = "cz.inzenyri.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = providers.gradleProperty("appVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("appVersionName").orElse("2.3.0").get()
        buildConfigField("String", "GITHUB_APP_CLIENT_ID", "\"$githubAppClientId\"")
        manifestPlaceholders["appLabel"] = "Inženýři"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (providers.gradleProperty("localPreview").orNull == "true") {
                applicationIdSuffix = ".preview"
                versionNameSuffix = "-preview"
                manifestPlaceholders["appLabel"] = "Inženýři Preview"
            }
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.activity:activity:1.9.3")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:inline-parser:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
