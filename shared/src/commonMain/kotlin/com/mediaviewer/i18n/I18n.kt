package com.mediaviewer.i18n

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.sharedPreferences
import com.mediaviewer.resources.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * The languages Stellar's own words can be shown in (Settings → App
 * Functionality → Language, and the login page). Posts, names and anything
 * else people wrote are never translated by this — only the app's own text.
 */
enum class AppLanguage(val code: String, val nativeName: String) {
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    ITALIAN("it", "Italiano"),
    PORTUGUESE("pt-BR", "Português (Brasil)"),
    RUSSIAN("ru", "Русский"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어"),
    CHINESE("zh-Hans", "简体中文");

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}

/**
 * The app's text in the chosen language. Stellar's text is written in
 * English right in the code; each language is a file of translations keyed
 * by that English text (composeResources/files/i18n/<code>.json), so every
 * screen is translated where its text is drawn ([Text], toasts) without
 * changing how screens are written. Text that isn't in the file stays in
 * English.
 *
 * Sentences with a name or number in them are written in the files with
 * {0}, {1}… where those go ("Replying to @{0}"), and matched as patterns.
 */
object Translations {
    private const val PREFS = "app_language"
    private const val KEY = "code"

    /** The chosen language (Compose state: text recomposes when it changes). */
    var language: AppLanguage by mutableStateOf(AppLanguage.ENGLISH)
        private set

    /** The language whose file is loaded (may trail [language] while loading). */
    private var loadedFor: AppLanguage = AppLanguage.ENGLISH
    private var exact: Map<String, String> = emptyMap()
    private var patterns: List<Pattern> = emptyList()
    private val cache = HashMap<String, String>()

    /** True once the saved language's words are ready to show. */
    var ready: Boolean by mutableStateOf(false)
        private set

    private var context: PlatformContext? = null
    private var initialized = false

    /** Reads which language was chosen (call once at startup). */
    fun init(context: PlatformContext) {
        if (initialized) return
        initialized = true
        this.context = context
        val saved = runCatching { context.sharedPreferences(PREFS).getString(KEY, null) }.getOrNull()
        language = AppLanguage.fromCode(saved)
        if (language == AppLanguage.ENGLISH) ready = true
    }

    /** Switches the app to [lang] and remembers it. */
    suspend fun select(lang: AppLanguage) {
        context?.let { runCatching { it.sharedPreferences(PREFS).edit().putString(KEY, lang.code).apply() } }
        load(lang)
        language = lang
        ready = true
    }

    /** Loads the file for [lang] (nothing to load for English). */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(lang: AppLanguage) {
        if (lang == loadedFor && (lang == AppLanguage.ENGLISH || exact.isNotEmpty())) return
        if (lang == AppLanguage.ENGLISH) {
            setTable(lang, emptyMap())
            return
        }
        val table = runCatching {
            val text = Res.readBytes("files/i18n/${lang.code}.json").decodeToString()
            val root = Json.parseToJsonElement(text) as JsonObject
            root.mapValues { it.value.jsonPrimitive.content }
        }.getOrElse { emptyMap() }
        setTable(lang, table)
    }

    private fun setTable(lang: AppLanguage, table: Map<String, String>) {
        val plain = HashMap<String, String>(table.size * 2)
        val pats = ArrayList<Pattern>()
        for ((k, v) in table) {
            if (v.isBlank()) continue
            if (PLACEHOLDER.containsMatchIn(k)) Pattern.of(k, v)?.let { pats += it } else plain[k] = v
        }
        // Longer patterns first: "{0} of {1} checked" before "{0} of {1}".
        pats.sortByDescending { it.literalLength }
        synchronized(cache) {
            exact = plain
            patterns = pats
            loadedFor = lang
            cache.clear()
        }
    }

    /** [text] in the chosen language, or [text] itself when there's no translation. */
    fun tr(text: String): String {
        // (Reading [language] makes a composable that calls this recompose
        // when the language changes.)
        val lang = language
        if (lang == AppLanguage.ENGLISH || lang != loadedFor || text.isEmpty()) return text
        exact[text]?.let { return it }
        if (text.length > 400 || patterns.isEmpty()) return text
        synchronized(cache) { cache[text] }?.let { return it }
        var out = text
        for (p in patterns) {
            val m = p.match(text) ?: continue
            out = m
            break
        }
        // Surrounding spaces and line breaks don't change the meaning:
        // " · {0}" and "{0}\n" are looked up without them.
        if (out === text) {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty() && trimmed.length != text.length) {
                val inner = tr(trimmed)
                if (inner != trimmed) out = text.replace(trimmed, inner)
            }
        }
        synchronized(cache) {
            if (cache.size > 4000) cache.clear()
            cache[text] = out
        }
        return out
    }

    /** A value inside a pattern ("list" in "Delete this {0}?"), translated
     *  when it's one of the app's own words. */
    internal fun trValue(value: String): String = exact[value] ?: value

    private val PLACEHOLDER = Regex("\\{\\d+}")

    /** One sentence with blanks, and its translation. */
    private class Pattern(private val regex: Regex, private val order: List<Int>, private val anchor: String, private val translation: String, val literalLength: Int) {
        fun match(text: String): String? {
            if (anchor.isNotEmpty() && !text.contains(anchor)) return null
            val m = regex.matchEntire(text) ?: return null
            var out = translation
            order.forEachIndexed { group, index ->
                out = out.replace("{$index}", trValue(m.groupValues[group + 1]))
            }
            return out
        }

        companion object {
            fun of(source: String, translation: String): Pattern? {
                val parts = PLACEHOLDER.split(source)
                val indexes = PLACEHOLDER.findAll(source).map { it.value.trim('{', '}').toInt() }.toList()
                val literal = parts.joinToString("")
                // Too little fixed text to recognise safely ("{0} {1}").
                if (literal.count { it.isLetter() } < 3) return null
                val regex = buildString {
                    parts.forEachIndexed { i, part ->
                        append(Regex.escape(part))
                        if (i < indexes.size) append("(.+?)")
                    }
                }
                return runCatching {
                    Pattern(Regex(regex, RegexOption.DOT_MATCHES_ALL), indexes, parts.maxByOrNull { it.length }.orEmpty(), translation, literal.length)
                }.getOrNull()
            }
        }
    }
}

/** [text] in the app's chosen language (see [Translations]). */
fun tr(text: String): String = Translations.tr(text)

/**
 * Waits for the saved language's words before showing [content] (only a
 * moment, and only when a language other than English was chosen), so the
 * app never flashes English first.
 */
@Composable
fun AppLanguageGate(context: PlatformContext, content: @Composable () -> Unit) {
    Translations.init(context)
    LaunchedEffect(Unit) {
        if (!Translations.ready) Translations.select(Translations.language)
    }
    if (Translations.ready) content()
}

/** material3's Text, with the app's own words in the chosen language. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) = androidx.compose.material3.Text(
    tr(text), modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
    textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style
)

/** material3's Text for styled text (passed through as it is: styled text
 *  is built from words that were already translated, or from posts). */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current
) = androidx.compose.material3.Text(
    text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
    textAlign, lineHeight, overflow, softWrap, maxLines, minLines, inlineContent, onTextLayout, style
)
