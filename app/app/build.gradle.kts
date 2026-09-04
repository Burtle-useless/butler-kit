import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 從 app/local.properties 讀祕密，塞給 BuildConfig。
//
// **不要把憑證寫進原始碼**——這個 repo 是公開的。local.properties 在 .gitignore 裡。
// 讀不到就給空字串，而不是讓 build 失敗：只有走 Cloudflare Tunnel + Access 的人
// 需要這兩個值，其他連線方式（Tailscale、區網、自架 VPN）留空即可，
// AccessAuth 看到空字串就一個標頭都不加（見 net/AccessAuth.kt）。
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(key: String): String = (localProps.getProperty(key) ?: "").trim()

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
        // 只留 arm64-v8a：現役 Android 手機一律是這個架構，而這支 App 只裝在他自己手機上。
        // 要裝到模擬器或舊 32 位裝置時再把 armeabi-v7a 加回來。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "CF_ACCESS_CLIENT_ID", "\"${secret("cfAccessClientId")}\"")
        buildConfigField("String", "CF_ACCESS_CLIENT_SECRET", "\"${secret("cfAccessClientSecret")}\"")
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
        // AGP 8 起預設關閉，不開的話上面那兩個 buildConfigField 不會產生任何東西
        buildConfig = true
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
    // extended 才有翻譯／螢幕／收件匣這些圖示，core 只給約四十個最通用的。
    // 沒開 minify 所以整包都會進 APK（約 +9MB）——工具頁與設定頁靠圖示分辨，
    // 四張純文字卡片排下來就是四個灰方塊，這個交換划算。
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 網路：okhttp-sse 原生支援 Last-Event-ID 續傳，這是選 SSE 而非 WebSocket 的原因之一
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 桌面 widget。Glance 是用 Compose 語法寫 RemoteViews，不是真的 Compose——
    // 能用的元件只有 Column/Row/Box/Text/LazyColumn 那幾個，沒有 Canvas、沒有動畫。
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // 用量 widget 要自己定時去拉（課表與行程吃 agendaCache，不需要）。
    // 用 WorkManager 而不是 AlarmManager：這件事晚幾分鐘無所謂，
    // 讓系統併到別人的喚醒窗口比較省電。
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 定位：助理問「你在哪」時抓一次。用 Fused 而不是原生 LocationManager——
    // 原生的一次性 API 是 API 30 才有，minSdk 26 只能用已棄用的訂閱式 API 自己收尾，
    // 而忘記取消訂閱就變成背景一直在定位，那正是這個設計要避免的。
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // 面對面翻譯：ML Kit 的 on-device 翻譯，語言包各約 30MB、下載後完全離線。
    // 語音轉文字與朗讀都用系統內建（SpeechRecognizer / TextToSpeech），不必額外依賴。
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
}
