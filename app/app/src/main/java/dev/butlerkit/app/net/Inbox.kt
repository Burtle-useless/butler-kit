package dev.butlerkit.app.net

import org.json.JSONObject

/**
 * 助理放在電腦上、等你拿的一個檔案。
 *
 * [gone] 是伺服器現算的：登記只是「指向磁碟上某個路徑」，原檔被移走或刪掉很正常。
 * 沒有這個旗標的話，使用者要點下去才知道拿不到。
 */
data class OfferedFile(
    val id: String,
    val name: String,
    val bytes: Long,
    val note: String,
    val at: String,
    val gone: Boolean,
)

fun JSONObject.toOfferedFile() = OfferedFile(
    id = optString("file_id"),
    name = optString("name"),
    bytes = optLong("bytes"),
    note = optString("note"),
    at = optString("at"),
    gone = optBoolean("gone"),
)
