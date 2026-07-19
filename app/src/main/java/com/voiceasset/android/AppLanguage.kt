package com.voiceasset.android

import java.util.Locale

enum class AppLanguage(
    val languageTag: String,
) {
    ENGLISH("en"),
    SIMPLIFIED_CHINESE("zh-CN"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            when (tag?.lowercase(Locale.ROOT)) {
                "zh", "zh-cn", "zh-hans" -> SIMPLIFIED_CHINESE
                else -> ENGLISH
            }

        fun fromLocale(locale: Locale): AppLanguage = fromTag(locale.toLanguageTag())
    }
}
