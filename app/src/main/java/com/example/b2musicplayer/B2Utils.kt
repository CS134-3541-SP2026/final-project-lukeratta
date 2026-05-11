package com.example.b2musicplayer

import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.File
import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@OptIn(UnstableApi::class)
object B2Utils {
    fun getDataSourceFactory(context: Context): DataSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        return DefaultDataSource.Factory(context, httpDataSourceFactory)
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    var authToken: String = ""
        private set

    var apiUrl: String = ""
        private set

    var downloadUrl: String = ""
        private set

    var accountId: String = ""
        private set

    /**
     * Authorizes with Backblaze B2 using v2 API.
     */
    suspend fun authorize(keyId: String, appKey: String) {
        try {
            val response: B2AuthResponse = client.get(
                "https://api.backblazeb2.com/b2api/v2/b2_authorize_account"
            ) {
                header(
                    HttpHeaders.Authorization,
                    "Basic " + Base64.encodeToString(
                        "$keyId:$appKey".toByteArray(),
                        Base64.NO_WRAP
                    )
                )
            }.body()

            authToken = response.authorizationToken
            apiUrl = response.apiUrl
            downloadUrl = response.downloadUrl
            accountId = response.accountId
            Log.d("B2_DEBUG", "Auth Success. Account: $accountId, API: $apiUrl, Download: $downloadUrl")
        } catch (e: Exception) {
            Log.e("B2_DEBUG", "Auth Error: ${e.message}", e)
            throw e
        }
    }

    /**
     * Lists "directories" (virtual folders) in the specified bucket.
     * Includes a fallback to manually parse paths if the B2 delimiter fails.
     */
    suspend fun getAlbumDirectories(bucketId: String, prefix: String = "MUSIC/ALBUMS/"): List<String> {
        Log.d("B2_DEBUG", "Fetching folders from bucketId: $bucketId with prefix: '$prefix'")

        return try {
            val files = listFileNames(bucketId = bucketId, prefix = prefix, delimiter = "/")

            Log.d("B2_DEBUG", "B2 response contained ${files.size} total items")

            // 1. Try to get folders from 'folder' action (standard B2 behavior)
            val folderActions = files
                .filter { it.action == "folder" }
                .map { it.fileName.removePrefix(prefix).removeSuffix("/") }
                .filter { it.isNotEmpty() }

            if (folderActions.isNotEmpty()) {
                Log.d("B2_DEBUG", "Found folders via 'folder' action: $folderActions")
                return folderActions
            }

            // 2. Fallback: Parse from 'upload' action (handles your specific bucket structure)
            val parsedFolders = files
                .filter { it.action == "upload" }
                .map { it.fileName.removePrefix(prefix) }
                .map { if (it.contains('/')) it.substringBefore('/') else "" }
                .filter { it.isNotEmpty() && !it.startsWith(".") }
                .distinct()
                .sorted()

            Log.d("B2_DEBUG", "Found folders via path parsing: $parsedFolders")
            parsedFolders
        } catch (e: Exception) {
            Log.e("B2_DEBUG", "List Error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Lists songs in a specific album directory.
     */
    suspend fun getSongsInAlbum(bucketId: String, albumPrefix: String): List<Song> {
        Log.d("B2_DEBUG", "Fetching songs for prefix: '$albumPrefix'")

        return try {
            listFileNames(bucketId = bucketId, prefix = albumPrefix)
                .filter { it.action == "upload" && it.fileName.lowercase().endsWith(".mp3") }
                .sortedBy { it.fileName }
                .map { b2File ->
                    val fileName = b2File.fileName.substringAfterLast("/")
                    val titleWithoutExtension = fileName.removeSuffix(".mp3")
                    // Remove prefix numbering like "01 - ", "01. ", "01 "
                    val title = titleWithoutExtension.replaceFirst(Regex("""^\d+[\s.-]*"""), "").trim()
                    
                    Song(
                        title = if (title.isEmpty()) titleWithoutExtension else title,
                        fileName = b2File.fileName,
                        durationInSeconds = 0 // Note: B2 listing doesn't provide audio duration
                    )
                }
        } catch (e: Exception) {
            Log.e("B2_DEBUG", "Error fetching songs for $albumPrefix: ${e.message}")
            emptyList()
        }
    }

    private suspend fun listFileNames(
        bucketId: String,
        prefix: String,
        delimiter: String? = null
    ): List<B2File> {
        val url = "$apiUrl/b2api/v2/b2_list_file_names"
        val files = mutableListOf<B2File>()
        var startFileName: String? = null

        do {
            val requestBody = ListFileNamesRequest(
                bucketId = bucketId,
                prefix = prefix,
                maxFileCount = 10_000,
                delimiter = delimiter,
                startFileName = startFileName
            )

            val response: ListFileNamesResponse = client.post(url) {
                header(HttpHeaders.Authorization, authToken)
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body()

            files += response.files
            val nextFileName = response.nextFileName
            startFileName = nextFileName?.takeIf { it != startFileName }
        } while (startFileName != null)

        return files
    }

    suspend fun getAlbumArtworkUrl(
        bucketName: String,
        albumPrefix: String
    ): String {
        Log.d("B2_DEBUG", "Fetching artwork for prefix: '$albumPrefix'")

        val pngPath = "${albumPrefix}cover.png"
        val pngUrl = getDownloadUrl(bucketName, pngPath)
        if (downloadUrlExists(pngUrl)) {
            return pngUrl
        }

        val jpgPath = "${albumPrefix}cover.jpg"
        return getDownloadUrl(bucketName, jpgPath)
    }

    private suspend fun downloadUrlExists(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Range", "bytes=0-0")

                val responseCode = connection.responseCode
                responseCode in 200..299
            } catch (e: Exception) {
                Log.d("B2_DEBUG", "Artwork probe failed: ${e.message}")
                false
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Constructs a download URL for a file in a public/private bucket.
     * For private buckets, the authToken can be appended as a query parameter.
     */
    fun getDownloadUrl(bucketName: String, filePath: String): String {
        return buildDownloadUrl(downloadUrl, bucketName, filePath, authToken)
    }

    suspend fun cacheSong(context: Context, bucketName: String, filePath: String): File {
        return withContext(Dispatchers.IO) {
            val destination = getSongCacheFile(context, filePath)
            if (destination.isFile && destination.length() > 0L) {
                return@withContext destination
            }

            destination.parentFile?.mkdirs()
            val tempFile = File(destination.parentFile, "${destination.name}.download")
            if (tempFile.exists()) {
                tempFile.delete()
            }

            val connection = URL(getDownloadUrl(bucketName, filePath)).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("B2 download failed with HTTP $responseCode for $filePath")
                }

                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (tempFile.length() == 0L) {
                    throw IllegalStateException("B2 download produced an empty file for $filePath")
                }

                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }

                destination
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            } finally {
                connection.disconnect()
            }
        }
    }

    fun getCachedSong(context: Context, filePath: String): File? {
        val file = getSongCacheFile(context, filePath)
        return if (file.isFile && file.length() > 0L) file else null
    }

    fun getCachedSongArtist(context: Context, filePath: String): String? {
        val file = getCachedSong(context, filePath) ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.d("B2_DEBUG", "Could not read artist tag for $filePath: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    internal fun buildDownloadUrl(
        downloadBaseUrl: String,
        bucketName: String,
        filePath: String,
        authorizationToken: String
    ): String {
        val baseUrl = downloadBaseUrl.removeSuffix("/")
        val encodedBucketName = bucketName.encodeURLPathSegment()
        val encodedPath = filePath.split("/").joinToString("/") { it.encodeURLPathSegment() }
        val encodedToken = authorizationToken.encodeURLParameter()
        return "$baseUrl/file/$encodedBucketName/$encodedPath?Authorization=$encodedToken"
    }

    private fun String.encodeURLPathSegment(): String {
        return encodeURLParameter(spaceToPlus = false)
    }

    private fun getSongCacheFile(context: Context, filePath: String): File {
        val extension = filePath.substringAfterLast('.', "mp3")
            .takeIf { it.isNotBlank() && it.length <= 8 }
            ?: "mp3"
        return File(File(context.cacheDir, "downloaded-songs"), "${filePath.sha256()}.$extension")
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

@Serializable
data class B2AuthResponse(
    val authorizationToken: String,
    val accountId: String,
    val apiUrl: String,
    val downloadUrl: String
)

@Serializable
data class ListFileNamesResponse(
    val files: List<B2File>,
    val nextFileName: String? = null
)

@Serializable
data class ListFileNamesRequest(
    val bucketId: String,
    val prefix: String,
    val maxFileCount: Int,
    val delimiter: String? = null,
    val startFileName: String? = null
)

@Serializable
data class B2File(
    val fileName: String,
    val action: String,
    val contentLength: Long? = null,
    val fileInfo: Map<String, String>? = null
)
