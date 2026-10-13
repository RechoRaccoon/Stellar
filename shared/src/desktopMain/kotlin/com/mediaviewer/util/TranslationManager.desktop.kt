package com.mediaviewer.util

/** No on-device translation engine on a computer yet (Android uses ML Kit,
 *  iOS Apple's Translation framework): "Translate" stays grayed out. */
internal actual fun defaultTranslationEngine(): TranslationEngine? = null

internal actual fun englishLanguageName(languageTag: String): String =
    java.util.Locale.forLanguageTag(languageTag).getDisplayLanguage(java.util.Locale.ENGLISH).takeIf { it != languageTag }.orEmpty()
