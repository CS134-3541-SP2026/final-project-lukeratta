package com.example.b2musicplayer

import kotlinx.serialization.Serializable

/**
 * Data class representing a Music Album.
 *
 * @param albumTitle The name of the album
 * @param songs A list of Song objects belonging to this album
 * @param artworkUrl The URL to the album's artwork (e.g. cover.jpg)
 */
@Serializable
data class Album(
    val albumTitle: String = "",
    val songs: List<Song> = emptyList(),
    val artworkUrl: String? = null
)
