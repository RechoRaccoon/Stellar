package com.mediaviewer.util

/** No on-device translation engine in a browser: "Translate" stays grayed out. */
internal actual fun defaultTranslationEngine(): TranslationEngine? = null

internal actual fun englishLanguageName(languageTag: String): String = runCatching { jsLanguageName(languageTag) }.getOrDefault("").takeIf { it != languageTag }.orEmpty()

private fun jsLanguageName(tag: String): String = js("(new Intl.DisplayNames(['en'], { type: 'language' })).of(tag) || ''")
