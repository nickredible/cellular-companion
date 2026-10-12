plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.xuanhan.cellularcompanion"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nickredible.cellularcompanion"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "FULL"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val cameraxVersion = "1.3.0"
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("com.google.accompanist:accompanist-permissions:0.31.3-beta")
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.30.1")
    implementation("io.github.raamcosta.compose-destinations:animations-core:1.9.42-beta")
    ksp("io.github.raamcosta.compose-destinations:ksp:1.9.42-beta")
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:${shizukuVersion}")
    implementation("dev.rikka.shizuku:provider:${shizukuVersion}")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.navigation:navigation-compose:2.7.5")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation(platform("androidx.compose:compose-bom:2023.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}