package com.stark.jarvis

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var ttsOk = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("jarvis", 0)
        window.statusBarColor = Color.parseColor("#020a16")
        window.navigationBarColor = Color.parseColor("#020a16")
        tts = TextToSpeech(this) { ttsOk = it == TextToSpeech.SUCCESS }
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#020a16"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(need.toTypedArray(), 1)
    }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    inner class Bridge {
        @JavascriptInterface fun getHistory(): String = prefs.getString("hist", "[]") ?: "[]"
        @JavascriptInterface fun clearHistory() { prefs.edit().remove("hist").apply() }
        @JavascriptInterface fun getSetting(k: String, d: String): String = prefs.getString(k, d) ?: d
        @JavascriptInterface fun setSetting(k: String, v: String) {
            prefs.edit().putString(k, v).apply()
            if (k == "sens" || k == "key") runOnUiThread { restart() }
        }
        @JavascriptInterface fun isRunning(): Boolean = JarvisService.running
        @JavascriptInterface fun getError(): String = prefs.getString("err", "") ?: ""
        @JavascriptInterface fun getVoices(): String {
            if (!ttsOk) return "[]"
            val l = tts?.getVoices()?.map { it.name }?.sorted() ?: emptyList()
            return JSONArray(l).toString()
        }
        @JavascriptInterface fun speak(t: String) {
            val e = tts ?: return
            if (!ttsOk) return
            applyVoice(e, prefs)
            e.speak(t, TextToSpeech.QUEUE_FLUSH, null, "m")
        }
        @JavascriptInterface fun runCommand(c: String) {
            runOnUiThread {
                startService(Intent(this@MainActivity, JarvisService::class.java).putExtra("cmd", c))
            }
        }
        @JavascriptInterface fun stop() {
            runOnUiThread { stopService(Intent(this@MainActivity, JarvisService::class.java)); JarvisService.running = false }
        }
        @JavascriptInterface fun start(): String {
            if (pref(prefs, "key", "").isEmpty())
                return "Paste your Picovoice access key in Wake Word & Voice settings and tap Save key first."
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
                return "Allow the microphone, then tap again."
            }
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                runOnUiThread {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                return "Turn on 'Display over other apps' for JARVIS, come back, then tap again."
            }
            runOnUiThread {
                startForegroundService(Intent(this@MainActivity, JarvisService::class.java))
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("askedBatt", false)) {
                    prefs.edit().putBoolean("askedBatt", true).apply()
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {}
                }
            }
            return "ok"
        }
    }

    private fun restart() {
        if (!JarvisService.running) return
        stopService(Intent(this, JarvisService::class.java))
        JarvisService.running = false
        Handler(Looper.getMainLooper()).postDelayed({
            startForegroundService(Intent(this, JarvisService::class.java))
        }, 700)
    }
}
