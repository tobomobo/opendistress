// SPDX-License-Identifier: MIT

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localConfig = Properties().apply {
    val file = rootProject.file("panic.local.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}

fun setting(name: String, fallback: String): String =
    localConfig.getProperty(name, fallback)

fun quoted(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "dev.smartpanic.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.smartpanic.wear"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "SPB_ENDPOINT",
            quoted(setting("endpoint", "https://invalid.example/v2/events")),
        )
        buildConfigField("String", "SPB_DEVICE_ID", quoted(setting("deviceId", "INVALID")))
        buildConfigField("String", "SPB_AUTH_KEY_HEX", quoted(setting("authKeyHex", "INVALID")))
        buildConfigField("String", "SPB_ENC_KEY_HEX", quoted(setting("encKeyHex", "INVALID")))
        buildConfigField("String", "SPB_MAC_KEY_HEX", quoted(setting("macKeyHex", "INVALID")))
        buildConfigField("String", "SPB_TEMPLATE_ID_HEX", quoted(setting("templateIdHex", "INVALID")))
        buildConfigField("long", "SPB_KEY_VERSION", "${setting("keyVersion", "0").toLongOrNull() ?: 0}L")
        buildConfigField("long", "SPB_TTL_SECONDS", "${setting("ttlSeconds", "3600").toLongOrNull() ?: 0}L")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            it.systemProperty("spb.repo.root", rootProject.projectDir.resolve("../..").canonicalPath)
        }
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
    testImplementation("junit:junit:4.13.2")
}
