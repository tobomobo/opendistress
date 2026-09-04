// SPDX-License-Identifier: MIT

plugins {
    id("com.android.application")
}

android {
    namespace = "dev.opendistress.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.opendistress.wear"
        minSdk = 30
        targetSdk = 36
        versionCode = 36_010_002
        versionName = "0.2.0-beta"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
}
