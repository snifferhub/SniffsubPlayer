package com.sniffsub.sniff_sub_player.data

const val DEFAULT_ACCENT_ARGB: Long = 0xFFE8A7BFL

class PreferencesEditor(private val repository: AppPreferencesRepository) {
    suspend fun setUserAgent(value: String) {
        repository.update { it.copy(userAgent = value) }
    }

    suspend fun setAccent(value: Long) {
        repository.update { it.copy(accentArgb = value or 0xFF000000L) }
    }

    suspend fun resetAccent() = setAccent(DEFAULT_ACCENT_ARGB)

    suspend fun removeBookmark(id: String) {
        repository.update { current ->
            current.copy(bookmarks = current.bookmarks.filterNot { it.id == id })
        }
    }
}
