plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.emreata.thermalprinterbridge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.emreata.thermalprinterbridge"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Yalnızca Türkçe ve İngilizce dil kaynaklarını APK'ya dahil eder
        resourceConfigurations += listOf("en", "tr")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Eksik çeviri ve tipografi tire uyarılarının derlemeyi kesmesini engeller
    lint {
        abortOnError = false
        disable += "MissingTranslation"
        disable += "TypographyDashes"
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}