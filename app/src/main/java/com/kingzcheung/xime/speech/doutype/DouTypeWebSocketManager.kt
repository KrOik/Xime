package com.kingzcheung.xime.speech.doutype

import android.content.Context
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.RecognitionState
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.UUID

class DouTypeWebSocketManager(private val context: Context, private val onResult: (String) -> Unit, private val onState: (RecognitionState) -> Unit, private val onError: (String) -> Unit) {
    var state = RecognitionState.IDLE; private set
    private var socket: WebSocket? = null; private val task = UUID.randomUUID().toString(); private var firstFrame = true; private var credentials: DouTypeCredentials? = null
    fun connect(): Boolean { state = RecognitionState.PROCESSING; onState(state); return try { credentials = DouTypeCredentialManager(context).ensure(); val c = credentials!!; val req = Request.Builder().url("wss://frontier-audio-ime-ws.doubao.com/ocean/api/v1/ws?aid=401734&device_id=${c.deviceId}").header("Authorization", "Bearer ${c.token}").header("Sec-WebSocket-Protocol", "frontier-v2").build(); socket = OkHttpClient().newWebSocket(req, Listener()); true } catch (e: Exception) { state = RecognitionState.ERROR; onState(state); onError("DouType 自动注册失败: ${e.message ?: "网络不可用"}"); false } }
    fun sendAudio(data: ByteArray) { data.asList().chunked(640).forEach { chunk -> val audio=chunk.toByteArray().let { if(it.size<640) it+ByteArray(640-it.size) else it }; socket?.send(ByteString.of(*audioEnvelope(audio, if(firstFrame) 1 else 3))); firstFrame=false } }
    fun finish() { socket?.send(ByteString.of(*audioEnvelope(ByteArray(640), 9))); socket?.send(ByteString.of(*control("FinishSession", ""))) }
    fun close() { socket?.cancel(); socket = null; state = RecognitionState.IDLE; onState(state) }
    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(ByteString.of(*control("StartTask", ""))) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { parse(bytes.toByteArray()) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { state = RecognitionState.ERROR; onState(state); onError("DouType 连接失败: ${t.message ?: "未知错误"}") }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { state = RecognitionState.IDLE; onState(state) }
    }
    private fun payload() = JSONObject().apply { put("audio_info", JSONObject().apply { put("channel",1);put("format","raw");put("sample_rate",16000) });put("enable_punctuation",SettingsPreferences.isDouTypePunctuationEnabled(context));put("language","zh-CN");put("result_type","full");put("extra", JSONObject().apply { put("enable_asr_twopass",SettingsPreferences.isDouTypeTwoPassEnabled(context));put("enable_asr_threepass",SettingsPreferences.isDouTypeThreePassEnabled(context));put("enable_sentence_seg",SettingsPreferences.isDouTypeSentenceSegEnabled(context));put("enable_timestamp",SettingsPreferences.isDouTypeTimestampEnabled(context));put("remove_space_between_han_num",SettingsPreferences.isDouTypeRemoveSpacesEnabled(context)) });put("enable_speech_rejection",SettingsPreferences.isDouTypeSpeechRejectionEnabled(context)) }.toString()
    private fun control(event:String,payload:String):ByteArray { val c=credentials ?: error("credentials missing"); return concat(str(1,c.token),str(2,c.productKey),str(3,"ASR"),str(4,"v2"),str(5,event),if(payload.isEmpty()) byteArrayOf() else str(6,payload),str(7,task)) }
    private fun audioEnvelope(audio:ByteArray,frameState:Int)=concat(str(3,"ASR"),str(5,"TaskRequest"),str(6,JSONObject().put("timestamp_ms",System.currentTimeMillis()).toString()),bytes(7,audio),str(8,task),integer(9,frameState))
    private fun parse(data: ByteArray) { var i=0; var event=""; while(i<data.size){ val tag=data[i++].toInt() and 255;val field=tag ushr 3;val wire=tag and 7;if(wire==0){while(i<data.size && data[i++].toInt() and 128!=0){};continue};if(wire!=2)continue;var len=0;var shift=0;var b:Int;do{b=data[i++].toInt() and 255;len=len or((b and 127) shl shift);shift+=7}while(b and 128!=0);val end=(i+len).coerceAtMost(data.size);val value=String(data,i,end-i);if(field==4)event=value;if(field in listOf(6,7,8)&&value.startsWith("{"))runCatching{val obj=JSONObject(value);val item=obj.optJSONArray("results")?.optJSONObject(0)?:obj.optJSONObject("result")?:obj;val text=item.optString("text");if(text.isNotBlank())onResult(text)};i=end};when(event){"TaskStarted"->socket?.send(ByteString.of(*control("StartSession",payload())));"SessionStarted"->{state=RecognitionState.LISTENING;onState(state)};"TaskFailed","SessionFailed"->{state=RecognitionState.ERROR;onState(state);onError("DouType 服务启动失败")};"SessionFinished","TaskFinished"->close()} }
    private fun str(field:Int, value:String):ByteArray { val b=value.toByteArray(); return concat(byteArrayOf((field shl 3 or 2).toByte()), varint(b.size), b) }
    private fun bytes(field:Int,value:ByteArray)=concat(byteArrayOf((field shl 3 or 2).toByte()),varint(value.size),value)
    private fun integer(field:Int,value:Int)=concat(byteArrayOf((field shl 3).toByte()),varint(value))
    private fun varint(v:Int):ByteArray { var n=v; val out=ArrayList<Byte>(); do { var b=n and 127; n=n ushr 7; if(n!=0)b=b or 128; out.add(b.toByte()) } while(n!=0); return out.toByteArray() }
    private fun concat(vararg a:ByteArray)=a.fold(ByteArray(0)){x,y->x+y}
}
