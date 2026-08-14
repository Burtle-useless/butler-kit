plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.butlerkit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.butlerkit.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // ML Kit 的翻譯引擎帶 native lib，四種架構全打包會讓 APK 從 10MB 漲到 75MB。
        // 只留 arm64-v8a：2019 年後出的 Android 手機一律是這個架構。
        // 要裝到模擬器或舊 32 位裝置時再把 armeabi-v7a 加回來。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    // 前景／背景切換：決定 SSE 由 ViewModel 還是背景服務持有
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")

    // Compose（用 BOM 統一版本）
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 網路：okhttp-sse 原生支援 Last-Event-ID 續傳，這是選 SSE 而非 WebSocket 的原因之一
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 面對面翻譯：ML Kit 的 on-device 翻譯，語言包各約 30MB、下載後完全離線。
    // 語音轉文字與朗讀都用系統內建（SpeechRecognizer / TextToSpeech），不必額外依賴。
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
}
