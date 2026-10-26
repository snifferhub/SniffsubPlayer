@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.sniffsub.sniff_sub_player.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create

class IosAppPreferencesRepository(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : AppPreferencesRepository {
    private val mutex = Mutex()
    private var migrationError: String? = null
    private val mutableValues = MutableStateFlow(load())
    override val values = mutableValues.asStateFlow()

    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        mutex.withLock {
            check(migrationError == null) { requireNotNull(migrationError) }
            val next = transform(mutableValues.value)
            defaults.setObject(AppPreferencesCodec.encode(next), forKey = PREFERENCES_KEY)
            // The remaining native player observes this legacy key with @AppStorage.
            val color = (next.accentArgb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
            defaults.setObject("#$color", forKey = LEGACY_ACCENT_KEY)
            mutableValues.value = next
        }
    }

    private fun load(): AppPreferences {
        defaults.stringForKey(PREFERENCES_KEY)?.let { stored ->
            return try {
                json.decodeFromString<AppPreferences>(stored)
            } catch (error: Exception) {
                migrationError = "无法读取设置，原始数据已保留：${error.message.orEmpty()}"
                AppPreferences()
            }
        }
        val accent = defaults.stringForKey(LEGACY_ACCENT_KEY)
            ?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)
        var initial = AppPreferences(
            userAgent = defaults.stringForKey("SniffSubPlayer.userAgent").orEmpty(),
            accentArgb = accent?.or(0xFF000000L) ?: DEFAULT_ACCENT_ARGB,
        )
        try {
            val data = defaults.dataForKey("SniffSubPlayer.bookmarks")
            if (data != null) {
                val text = NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
                    ?: error("无法读取旧版收藏数据")
                val bookmarks = json.decodeFromString<List<LegacyBookmark>>(text)
                initial = initial.copy(bookmarks = bookmarks.map {
                    Bookmark(
                        id = it.id, title = it.title, url = it.url,
                        // JSONEncoder's default Date representation uses the 2001 reference date.
                        createdAtEpochMs = ((it.createdAt + 978307200.0) * 1000).toLong(),
                    )
                })
            }
            defaults.setObject(AppPreferencesCodec.encode(initial), forKey = PREFERENCES_KEY)
        } catch (error: Exception) {
            // Leave the original data and migration marker untouched on a decoding failure.
            migrationError = "旧版收藏迁移失败，原始数据已保留：${error.message.orEmpty()}"
        }
        return initial
    }

    fun migrationFailure(): String? = migrationError

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val PREFERENCES_KEY = "SniffSubPlayer.preferences.kmp.v1"
        const val LEGACY_ACCENT_KEY = "SniffSubPlayer.playerAccentColor"
    }
}

@Serializable
private data class LegacyBookmark(
    val id: String,
    val title: String,
    val url: String,
    val createdAt: Double,
)
