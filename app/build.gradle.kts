plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "org.karuk.gasreceipttracker"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.karuk.gasreceipttracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.7"
    }
    buildTypes {
        getByName("debug") {
            // Side-by-side test install avoids signature mismatch with older debug APKs.
            // Keep production applicationId unchanged for future signed releases.
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
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.apache.poi:poi:5.2.5")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
}
