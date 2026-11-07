plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.glagol"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.glagol"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Для хакатона: подписываем debug-ключом, чтобы APK ставился без доп. шагов.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // Модели читаются напрямую из APK — не сжимаем их.
    androidResources { noCompress += listOf("onnx", "litertlm", "txt") }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

    // play-services-wearable тянет старый androidx.fragment 1.1.0; проверка касается только FragmentActivity,
    // а у нас ComponentActivity — без неё release не собирается
    lint { disable += "InvalidFragmentVersionForActivityResult" }
}

dependencies {
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation(libs.litertlm.android)
    testImplementation("junit:junit:4.13.2")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Часы: приём записи по Wearable Data Layer (Bluetooth, без интернета)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
