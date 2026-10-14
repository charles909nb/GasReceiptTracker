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
        versionCode = 13
        versionName = "2.2-finance-mileage"
    }
    buildTypes {
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
