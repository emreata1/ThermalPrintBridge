package com.emreata.thermalprinterbridge

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LocaleHelper {
    fun setAppLocale(languageCode: String) {
        // languageCode: "tr" veya "en"
        val appLocale = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
    }

    fun getCurrentLocale(): String {
        return AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { "tr" }
    }
}