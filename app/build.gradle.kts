plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "org.karuk.gasreceipttracker"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.karuk.gasreceipttracker"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.10-updateable"
    }
    signingConfigs {
        create("stableRelease") {
            // CI injects the same secure key every build; never publish a
            // GitHub-runner debug certificate as an upgradeable app.
            val filePath = providers.environmentVariable("GRT_SIGNING_KEYSTORE").orNull
            if(filePath != null) {
                storeFile = file(filePath)
                storePassword = providers.environmentVariable("GRT_SIGNING_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("GRT_SIGNING_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("GRT_SIGNING_KEY_PASSWORD").orNull
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("stableRelease")
            isMinifyEnabled = false
        }
        getByName("debug") {
            // Keep the existing preview identity and storage area.
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core:1.13.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.apache.poi:poi:5.2.5")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
}
