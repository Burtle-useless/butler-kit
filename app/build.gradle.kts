// 頂層建置檔：宣告各模組共用的外掛版本。
// 這組版本已驗證能一起編譯。要升版請整組一起升，Compose 編譯器與 Kotlin 是綁死的。
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
