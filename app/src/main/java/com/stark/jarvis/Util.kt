package com.stark.jarvis

import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import java.util.Locale

fun pref(p: SharedPreferences, k: String, d: String): String {
    val v = p.getString(k, d) ?: d
    return if (v.isBlank()) d else v
}

fun applyVoice(t: TextToSpeech, p: SharedPreferences) {
    t.setSpeechRate(pref(p, "rate", "1").toFloatOrNull() ?: 1f)
    t.setPitch(pref(p, "pitch", "1").toFloatOrNull() ?: 1f)
    t.setLanguage(Locale.forLanguageTag(pref(p, "lang", "en-US")))
    val n = pref(p, "voice", "")
    if (n.isNotEmpty()) {
        val v = t.getVoices()?.firstOrNull { it.name == n }
        if (v != null) t.setVoice(v)
    }
}
