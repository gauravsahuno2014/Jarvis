package com.stark.jarvis

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisService : Service() {
    companion object { @Volatile var running = false }

    private lateinit var prefs: SharedPreferences
    private val h = Handler(Looper.getMainLooper())
    private var pm: PorcupineManager? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onDone: (() -> Unit)? = null
    private var glow: GlowView? = null
    private var rec: SpeechRecognizer? = null
    private var busy = false
    private var torch = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        prefs = getSharedPreferences("jarvis", 0)
        try { notif() } catch (e: Exception) {
            prefs.edit().putString("err", e.message ?: "Cannot start service").apply()
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        val cmd = i?.getStringExtra("cmd")
        if (!running) {
            running = true
            prefs.edit().remove("err").apply()
            initTts()
            startWake()
        } else if (cmd != null) {
            handleText(cmd, false)
        }
        return START_STICKY
    }

    private fun notif() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "JARVIS", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("JARVIS is listening")
            .setContentText("Say \"Jarvis\" then your command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    private fun initTts() {
        tts = TextToSpeech(this) { s ->
            ttsReady = s == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(u: String?) {}
                override fun onError(u: String?) { fin() }
                override fun onDone(u: String?) { fin() }
                private fun fin() { h.post { val d = onDone; onDone = null; d?.invoke() } }
            })
        }
    }

    private fun speak(t: String, done: () -> Unit = {}) {
        val e = tts
        if (!ttsReady || e == null) { done(); return }
        onDone = done
        applyVoice(e, prefs)
        e.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    private fun startWake() {
        val key = pref(prefs, "key", "")
        if (key.isEmpty()) { fail("No Picovoice access key"); return }
        val sens = when (pref(prefs, "sens", "1")) { "0" -> 0.3f; "2" -> 0.8f; else -> 0.5f }
        try {
            val b = PorcupineManager.Builder().setAccessKey(key).setSensitivity(sens)
            if (assets.list("")?.contains("custom.ppn") == true) b.setKeywordPath("custom.ppn")
            else b.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            pm = b.build(applicationContext, PorcupineManagerCallback { _ -> h.post { onWake() } })
            pm?.start()
        } catch (e: Exception) {
            fail(e.message ?: "Wake word engine error")
        }
    }

    private fun fail(m: String) {
        prefs.edit().putString("err", m).apply()
        running = false
        stopSelf()
    }

    private fun onWake() {
        if (busy) return
        busy = true
        if (pref(prefs, "test", "") == "1") {
            prefs.edit().putString("test", "").apply()
            speak("Wake word detected.") { busy = false }
            return
        }
        try { pm?.stop() } catch (e: Exception) {}
        showGlow()
        h.postDelayed({ listen() }, 250)
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition is not available.") { finish() }
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) finish() else handleText(t, true)
            }
            override fun onError(e: Int) { finish() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, pref(prefs, "lang", "en-US"))
        )
        h.postDelayed({ if (busy && rec === r) finish() }, 9000)
    }

    private fun handleText(text: String, voice: Boolean) {
        rec?.destroy(); rec = null
        val c = text.lowercase().trim().replace(Regex("^(hey )?jarvis[,. ]*"), "")
        val res = try { execute(c) } catch (e: Exception) { "I could not do that" }
        log(text, res)
        speak(res) { if (voice) finish() }
    }

    private fun finish() {
        rec?.destroy(); rec = null
        hideGlow()
        busy = false
        h.postDelayed({ try { pm?.start() } catch (e: Exception) {} }, 400)
    }

    private fun showGlow() {
        if (!Settings.canDrawOverlays(this) || glow != null) return
        val v = GlowView(this)
        val L = WindowManager.LayoutParams
        val lp = WindowManager.LayoutParams(
            L.MATCH_PARENT, L.MATCH_PARENT, L.TYPE_APPLICATION_OVERLAY,
            L.FLAG_NOT_FOCUSABLE or L.FLAG_NOT_TOUCHABLE or L.FLAG_LAYOUT_IN_SCREEN or L.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp); glow = v } catch (e: Exception) {}
    }

    private fun hideGlow() {
        glow?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) {} }
        glow = null
    }

    private fun log(cmd: String, res: String) {
        val a = try { JSONArray(prefs.getString("hist", "[]")) } catch (e: Exception) { JSONArray() }
        val o = JSONObject().put("cmd", cmd).put("res", res)
            .put("t", SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date()))
        val n = JSONArray().put(o)
        for (i in 0 until minOf(a.length(), 199)) n.put(a.get(i))
        prefs.edit().putString("hist", n.toString()).apply()
    }

    private fun go(i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i) }
    private fun web(url: String) = go(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    private fun nowTime() = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    private fun nowDate() = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
    private fun battery(): Int =
        (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun execute(c: String): String {
        if (c.contains("briefing")) return "Good day. It is ${nowTime()} on ${nowDate()}. Battery is at ${battery()} percent."
        if (c.contains("timer")) {
            val m = Regex("(\\d+)\\s*(second|sec|minute|min|hour)").find(c)
            if (m != null) {
                val n = m.groupValues[1].toInt()
                val u = m.groupValues[2]
                val s = n * (if (u.startsWith("h")) 3600 else if (u.startsWith("m")) 60 else 1)
                go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                return "Timer set for $n $u"
            }
        }
        if (c.contains("battery")) return "Battery is at ${battery()} percent"
        if (Regex("\\btime\\b").containsMatchIn(c)) return "It is ${nowTime()}"
        if (Regex("\\b(date|day|today)\\b").containsMatchIn(c)) return "Today is ${nowDate()}"
        if (c.contains("flashlight") || c.contains("torch")) {
            val on = !c.contains("off")
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList[0], on)
            torch = on
            return if (on) "Flashlight on" else "Flashlight off"
        }
        if (c.contains("volume")) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val up = c.contains("up") || c.contains("increase") || c.contains("raise")
            repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            return if (up) "Volume up" else "Volume down"
        }
        Regex("(?:call|dial)\\s*([\\d\\s+]+)").find(c)?.let {
            val num = it.groupValues[1].replace(" ", "")
            val act = if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) Intent.ACTION_CALL else Intent.ACTION_DIAL
            go(Intent(act, Uri.parse("tel:$num")))
            return "Calling $num"
        }
        Regex("(?:navigate to|directions to|take me to)\\s+(.+)").find(c)?.let {
            web("geo:0,0?q=" + Uri.encode(it.groupValues[1])); return "Navigating to ${it.groupValues[1]}"
        }
        Regex("^play\\s+(.+)").find(c)?.let {
            web("https://www.youtube.com/results?search_query=" + Uri.encode(it.groupValues[1])); return "Playing ${it.groupValues[1]}"
        }
        Regex("(?:search for|search|look up|google)\\s+(.+)").find(c)?.let {
            web("https://www.google.com/search?q=" + Uri.encode(it.groupValues[1])); return "Searching for ${it.groupValues[1]}"
        }
        if (c.contains("go home") || c.contains("home screen")) {
            go(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)); return "Going home"
        }
        Regex("^(?:open|launch|start)\\s+(.+)").find(c)?.let {
            val name = it.groupValues[1].trim()
            val l = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val app = l.firstOrNull { r -> r.loadLabel(packageManager).toString().lowercase() == name }
                ?: l.firstOrNull { r -> r.loadLabel(packageManager).toString().lowercase().contains(name) }
            if (app != null) {
                val i = packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)
                if (i != null) { go(i); return "Opening $name" }
            }
            if (name.contains(".")) { web("https://" + name.replace(" ", "")); return "Opening $name" }
            return "I could not find $name"
        }
        return "Command not recognised"
    }

    override fun onDestroy() {
        try { pm?.stop(); pm?.delete() } catch (e: Exception) {}
        hideGlow()
        rec?.destroy()
        tts?.shutdown()
        running = false
        super.onDestroy()
    }
}
