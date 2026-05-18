package com.piremote.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.piremote.PiWebSocket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.piremote.dataStore

private const val TAG = "PiTest"

object TestState {
    val ws = PiWebSocket()
    var serverUrl = ""
}

class TestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Log.d(TAG, "RID:$intent.action received")
        val ws = TestState.ws
        when (intent.action) {
            "com.piremote.test.CONNECT" -> {
                val url = intent.getStringExtra("url") ?: "ws://10.0.2.2:8765"
                TestState.serverUrl = url
                Log.d(TAG, "Connecting to: $url")
                ws.connect(url)
            }
            "com.piremote.test.SEND_PROMPT" -> {
                val msg = intent.getStringExtra("message") ?: "Hello from ADB test"
                Log.d(TAG, "Send prompt: $msg")
                ws.sendPrompt(msg)
            }
            "com.piremote.test.SEND_STEER" -> {
                val msg = intent.getStringExtra("message") ?: "Steer here"
                Log.d(TAG, "Send steer: $msg")
                ws.sendSteer(msg)
            }
            "com.piremote.test.SEND_FOLLOWUP" -> {
                val msg = intent.getStringExtra("message") ?: "Follow up"
                Log.d(TAG, "Send followup: $msg")
                ws.sendFollowUp(msg)
            }
            "com.piremote.test.DISCONNECT" -> {
                Log.d(TAG, "Disconnecting")
                ws.disconnect()
            }
            "com.piremote.test.CLEAR_HISTORY" -> {
                Log.d(TAG, "Clear history")
                runBlocking {
                    ctx.dataStore.edit { it[stringSetPreferencesKey("url_history")] = emptySet() }
                }
            }
            else -> Log.d(TAG, "Unknown action: ${intent.action}")
        }
    }
}
