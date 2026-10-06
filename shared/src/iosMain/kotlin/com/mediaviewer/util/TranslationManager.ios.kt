package com.mediaviewer.util

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageCode

/** iOS translates with Apple's own on-device Translation framework, which
 *  only Swift can reach: the Swift app hands it over at launch (see
 *  platform/IosBridges.kt, registerIosTranslator), so there's nothing here
 *  until then — and nothing at all below iOS 18. */
internal actual fun defaultTranslationEngine(): TranslationEngine? = null

internal actual fun englishLanguageName(languageTag: String): String =
    NSLocale(localeIdentifier = "en").displayNameForKey(NSLocaleLanguageCode, languageTag) ?: ""
