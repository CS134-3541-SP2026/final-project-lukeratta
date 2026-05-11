package com.example.b2musicplayer

import kotlinx.serialization.Serializable

/**
 * Data class representing a music track.
 *
 * @param title The name of the song
 * @param fileName The full path/filename on B2
 * @param durationInSeconds The length of the song (changed from 'length' for clarity)
 */
@Serializable
data class Song(
    val title: String = "",
    val fileName: String = "",
    val durationInSeconds: Int = 0
)
