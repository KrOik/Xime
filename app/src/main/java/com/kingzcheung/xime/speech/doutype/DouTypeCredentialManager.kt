package com.kingzcheung.xime.speech.doutype

import android.content.Context
import android.os.Build
import android.provider.Settings
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class DouTypeCredentials(val deviceId: String, val productKey: String, val token: String)

@Suppress("DEPRECATION")
class DouTypeCredentialManager(private val context: Context) {
    companion object {
        private const val PREFS = "doutype_credentials"
        private const val TOKEN_TTL_MS = 9 * 60 * 60 * 1000L
        private const val AID = "401734"
        private const val APP = "oime"
        private const val VERSION = "1.1.2"
        private const val REGISTER = "https://log.snssdk.com/service/2/device_register/"
        private const val SETTINGS = "https://is.snssdk.com/service/settings/v3/"
        private const val TOKEN = "https://ime.oceancloudapi.com/api/v1/user/get_config"
    }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    fun ensure(): DouTypeCredentials {
        var device = prefs.getString("device_id", null)
        var cdid = prefs.getString("cdid", null)
        if (device.isNullOrBlank() || cdid.isNullOrBlank()) {
            val id = register(); device = id.first; cdid = id.second
            prefs.edit().putString("device_id", device).putString("cdid", cdid).apply()
        }
        val now = System.currentTimeMillis()
        var product = prefs.getString("product_key", null)
        if (product.isNullOrBlank() || now - prefs.getLong("product_updated", 0) > TOKEN_TTL_MS) {
            product = fetchSettings(device!!, cdid!!)
            prefs.edit().putString("product_key", product).putLong("product_updated", now).apply()
        }
        var token = prefs.getString("session_token", null)
        if (token.isNullOrBlank() || now - prefs.getLong("token_updated", 0) > TOKEN_TTL_MS) {
            token = exchangeToken(product!!)
            prefs.edit().putString("session_token", token).putLong("token_updated", now).apply()
        }
        return DouTypeCredentials(device!!, product!!, token!!)
    }
    fun status(): String {
        val device = prefs.getString("device_id", null)
        val token = prefs.getString("session_token", null)
        return when { device.isNullOrBlank() -> "未注册"; token.isNullOrBlank() -> "已注册，待获取会话"; else -> "已注册并就绪" }
    }
    private fun register(): Pair<String, String> {
        val cdid = UUID.randomUUID().toString(); val open = UUID.randomUUID().toString().replace("-", "").take(16); val client = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        val header = JSONObject().apply { put("device_id", 0); put("install_id", 0); put("aid", AID.toInt()); put("app_name", APP); put("version_code", 100102018); put("version_name", VERSION); put("manifest_version_code", 100102018); put("update_version_code", 100102018); put("channel", "official"); put("package", "com.bytedance.android.doubaoime"); put("device_platform", "android"); put("os", "android"); put("os_api", Build.VERSION.SDK_INT); put("os_version", Build.VERSION.RELEASE); put("device_type", Build.MODEL); put("device_brand", Build.MANUFACTURER); put("device_model", Build.MODEL); put("language", "zh"); put("timezone", 8); put("access", "wifi"); put("region", "CN"); put("tz_name", "Asia/Shanghai"); put("tz_offset", 28800); put("cpu_abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"); put("openudid", open); put("clientudid", client); put("cdid", cdid) }
        val body = JSONObject().apply { put("magic_tag", "ss_app_log"); put("header", header); put("_gen_time", now) }
        val url = HttpUrl.get(REGISTER).newBuilder().addQueryParameter("device_platform", "android").addQueryParameter("os", "android").addQueryParameter("aid", AID).addQueryParameter("app_name", APP).addQueryParameter("version_code", "100102018").addQueryParameter("version_name", VERSION).addQueryParameter("cdid", cdid).addQueryParameter("_rticket", now.toString()).build()
        val req = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).header("User-Agent", "com.bytedance.android.doubaoime/100102018 (Android)").build()
        val json = http.newCall(req).execute().use { JSONObject(it.body?.string() ?: "{}") }
        val device = json.optString("device_id_str").ifBlank { json.optString("device_id") }
        if (device.isBlank() || device == "0") error("设备注册失败")
        return device to cdid
    }
    private fun fetchSettings(device: String, cdid: String): String {
        val now = System.currentTimeMillis(); val url = HttpUrl.get(SETTINGS).newBuilder().addQueryParameter("device_platform", "android").addQueryParameter("os", "android").addQueryParameter("aid", AID).addQueryParameter("app_name", APP).addQueryParameter("version_code", "100102018").addQueryParameter("version_name", VERSION).addQueryParameter("device_id", device).addQueryParameter("cdid", cdid).addQueryParameter("_rticket", now.toString()).build()
        val raw = "body=null"; val stub = md5(raw).uppercase(); val req = Request.Builder().url(url).post(raw.toRequestBody("application/x-www-form-urlencoded".toMediaType())).header("x-ss-stub", stub).header("User-Agent", "com.bytedance.android.doubaoime/100102018 (Android)").build()
        val json = http.newCall(req).execute().use { JSONObject(it.body?.string() ?: "{}") }
        val key = json.optJSONObject("data")?.optJSONObject("settings")?.optJSONObject("asr_config")?.optString("app_key") ?: ""
        if (key.isBlank()) error("配置接口未返回语音产品密钥")
        return key
    }
    private fun exchangeToken(product: String): String {
        val req = Request.Builder().url(TOKEN).post(JSONObject().put("sami_app_key", product).toString().toRequestBody("application/json".toMediaType())).header("X-Request-Id", UUID.randomUUID().toString()).header("User-Agent", "DoubaoIME/1.1.2 (Android)").build()
        val json = http.newCall(req).execute().use { JSONObject(it.body?.string() ?: "{}") }
        val data = json.optJSONObject("Data") ?: json.optJSONObject("data") ?: json
        return data.optString("sami_token").ifBlank { error("Token 接口未返回会话令牌") }
    }
    private fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
