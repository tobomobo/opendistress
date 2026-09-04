// SPDX-License-Identifier: MIT

plugins {
    id("com.android.library")
}

android {
    namespace = "dev.opendistress.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
