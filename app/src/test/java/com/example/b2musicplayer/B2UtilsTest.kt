package com.example.b2musicplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class B2UtilsTest {
    @Test
    fun buildDownloadUrl_encodesCommasInFilePath() {
        val url = B2Utils.buildDownloadUrl(
            downloadBaseUrl = "https://f000.backblazeb2.com/",
            bucketName = "music-bucket",
            filePath = "MUSIC/ALBUMS/Test Album/01 - Hello, World.mp3",
            authorizationToken = "token"
        )

        assertEquals(
            "https://f000.backblazeb2.com/file/music-bucket/MUSIC/ALBUMS/Test%20Album/01%20-%20Hello%2C%20World.mp3?Authorization=token",
            url
        )
    }

    @Test
    fun buildDownloadUrl_encodesAuthorizationTokenAsQueryParameter() {
        val url = B2Utils.buildDownloadUrl(
            downloadBaseUrl = "https://f000.backblazeb2.com",
            bucketName = "music-bucket",
            filePath = "MUSIC/ALBUMS/Test Album/Track.mp3",
            authorizationToken = "a+b/c="
        )

        assertEquals(
            "https://f000.backblazeb2.com/file/music-bucket/MUSIC/ALBUMS/Test%20Album/Track.mp3?Authorization=a%2Bb%2Fc%3D",
            url
        )
    }
}
