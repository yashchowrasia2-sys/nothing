package com.zenith.glyphstudio.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private val Context.glyphStore: DataStore<Preferences> by preferencesDataStore(name = "glyph_studio")

/** Single source of truth shared by the UI process and the Glyph Toy service (same process). */
class GlyphStorage private constructor(private val ctx: Context) {

    private val store get() = ctx.glyphStore

    val presets: Flow<List<GlyphAnimation>> = store.data.map { p ->
        p[KEY_PRESETS]?.let { runCatching { json.decodeFromString<List<GlyphAnimation>>(it) }.getOrNull() } ?: emptyList()
    }.distinctUntilChanged()

    val mode: Flow<EngineMode> = store.data.map { p ->
        p[KEY_MODE]?.let { runCatching { json.decodeFromString<EngineMode>(it) }.getOrNull() } ?: EngineMode.Off
    }.distinctUntilChanged()

    val draft: Flow<GlyphAnimation?> = store.data.map { p ->
        p[KEY_DRAFT]?.let { runCatching { json.decodeFromString<GlyphAnimation>(it) }.getOrNull() }
    }

    suspend fun setMode(mode: EngineMode) = store.edit { it[KEY_MODE] = json.encodeToString<EngineMode>(mode) }

    suspend fun saveDraft(anim: GlyphAnimation) = store.edit { it[KEY_DRAFT] = json.encodeToString(anim) }

    suspend fun savePreset(anim: GlyphAnimation) = store.edit { p ->
        val list = p[KEY_PRESETS]?.let { runCatching { json.decodeFromString<List<GlyphAnimation>>(it) }.getOrNull() }.orEmpty()
        val updated = (list.filterNot { it.id == anim.id } + anim.sanitized()).takeLast(MAX_PRESETS)
        p[KEY_PRESETS] = json.encodeToString(updated)
    }

    suspend fun deletePreset(id: String) = store.edit { p ->
        val list = p[KEY_PRESETS]?.let { runCatching { json.decodeFromString<List<GlyphAnimation>>(it) }.getOrNull() }.orEmpty()
        p[KEY_PRESETS] = json.encodeToString(list.filterNot { it.id == id })
    }

    suspend fun currentMode(): EngineMode = mode.first()

    companion object {
        private const val SHARE_PREFIX = "GLY1:"
        private const val MAX_PRESETS = 64
        private const val MAX_CODE_CHARS = 64_000
        private const val MAX_JSON_BYTES = 256_000
        private val KEY_PRESETS = stringPreferencesKey("presets")
        private val KEY_MODE = stringPreferencesKey("mode")
        private val KEY_DRAFT = stringPreferencesKey("draft")

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "t" }

        @Volatile private var instance: GlyphStorage? = null
        fun get(ctx: Context): GlyphStorage =
            instance ?: synchronized(this) { instance ?: GlyphStorage(ctx.applicationContext).also { instance = it } }

        /** Compact share code: "GLY1:" + Base64URL(gzip(json)). */
        fun exportCode(anim: GlyphAnimation): String {
            val raw = json.encodeToString(anim.sanitized()).toByteArray()
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(raw) }
            return SHARE_PREFIX + Base64.encodeToString(bos.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        /** Parses a share code (or plain JSON). Bounded to prevent zip-bomb / oversized input. */
        fun importCode(code: String): Result<GlyphAnimation> = runCatching {
            val trimmed = code.trim()
            require(trimmed.length <= MAX_CODE_CHARS) { "Code too large" }
            val text = if (trimmed.startsWith(SHARE_PREFIX)) {
                val bytes = Base64.decode(trimmed.removePrefix(SHARE_PREFIX), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
                    val out = ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
                    while (true) {
                        val n = gz.read(buf); if (n < 0) break
                        total += n; require(total <= MAX_JSON_BYTES) { "Decoded payload too large" }
                        out.write(buf, 0, n)
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            } else trimmed
            json.decodeFromString<GlyphAnimation>(text)
                .copy(id = System.currentTimeMillis().toString(36)) // never overwrite an existing preset by id
                .sanitized()
        }
    }
}
