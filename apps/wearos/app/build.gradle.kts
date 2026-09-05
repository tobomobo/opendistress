// SPDX-License-Identifier: MIT

import java.util.Properties

plugins {
    id("com.android.application")
}

val localConfig = Properties().apply {
    val file = rootProject.file("opendistress.local.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}

fun setting(name: String, fallback: String): String =
    localConfig.getProperty(name, fallback)

fun quoted(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "dev.opendistress.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.opendistress.wear"
        minSdk = 30
        targetSdk = 36
        versionCode = 36_010_009
        versionName = "0.2.2-beta.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "OPENDISTRESS_ENDPOINT",
            quoted(setting("endpoint", "https://invalid.example/v2/events")),
        )
        buildConfigField("String", "OPENDISTRESS_DEVICE_ID", quoted(setting("deviceId", "INVALID")))
        buildConfigField("String", "OPENDISTRESS_AUTH_KEY_HEX", quoted(setting("authKeyHex", "INVALID")))
        buildConfigField("String", "OPENDISTRESS_ENC_KEY_HEX", quoted(setting("encKeyHex", "INVALID")))
        buildConfigField("String", "OPENDISTRESS_MAC_KEY_HEX", quoted(setting("macKeyHex", "INVALID")))
        buildConfigField("String", "OPENDISTRESS_TEMPLATE_ID_HEX", quoted(setting("templateIdHex", "INVALID")))
        buildConfigField("long", "OPENDISTRESS_KEY_VERSION", "${setting("keyVersion", "0").toLongOrNull() ?: 0}L")
        buildConfigField("long", "OPENDISTRESS_TTL_SECONDS", "${setting("ttlSeconds", "3600").toLongOrNull() ?: 0}L")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.systemProperty("opendistress.repo.root", rootProject.projectDir.resolve("../..").canonicalPath)
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.wear:wear:1.4.0")
    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation("androidx.wear:wear-remote-interactions:1.2.0")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-expression:1.4.2")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.concurrent:concurrent-futures:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("junit:junit:4.13.2")
}
