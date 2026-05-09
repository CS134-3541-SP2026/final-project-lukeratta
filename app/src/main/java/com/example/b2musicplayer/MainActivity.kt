@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
package com.example.b2musicplayer

import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import com.example.b2musicplayer.ui.theme.B2MusicPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataSourceFactory = B2Utils.getDataSourceFactory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000,
                60_000,
                10_000,
                10_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
        lifecycleScope.launch {
            warmUpAudioOutput()
        }

        enableEdgeToEdge()
        setContent {
            var authStatusMessage by remember { mutableStateOf<String?>(null) }
            var showBottomSheet by remember { mutableStateOf(false) }
            var hasAutoOpenedBottomSheet by remember { mutableStateOf(false) }
            var albumList by remember { mutableStateOf<List<Album>>(emptyList()) }
            var isLoadingAlbums by remember { mutableStateOf(true) }
            var selectedAlbum by remember { mutableStateOf<Album?>(null) }
            var currentSong by remember { mutableStateOf<Song?>(null) }
            var currentArtworkUrl by remember { mutableStateOf<String?>(null) }
            var isPlaying by remember { mutableStateOf(false) }
            var currentPosition by remember { mutableLongStateOf(0L) }
            var totalDuration by remember { mutableLongStateOf(0L) }
            var playWhenReadyAfterPrepare by remember { mutableStateOf(false) }
            var requestedSongFileName by remember { mutableStateOf<String?>(null) }
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

            // Sync playback state
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            totalDuration = player?.duration?.coerceAtLeast(0L) ?: 0L
                            if (playWhenReadyAfterPrepare) {
                                playWhenReadyAfterPrepare = false
                                player?.play()
                            }
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        selectedAlbum?.let { album ->
                            val fileName = mediaItem?.mediaId
                            val song = album.songs.firstOrNull { it.fileName == fileName }
                                ?: album.songs.getOrNull(player?.currentMediaItemIndex ?: -1)

                            if (song != null) {
                                currentSong = song
                                currentArtworkUrl = album.artworkUrl
                                // Force duration update for the new track
                                totalDuration = player?.duration?.coerceAtLeast(0L) ?: 0L
                                currentPosition = player?.currentPosition ?: 0L
                            }
                        }
                    }
                }
                player?.addListener(listener)
                onDispose {
                    player?.removeListener(listener)
                }
            }

            // Polling for progress
            LaunchedEffect(isPlaying) {
                while (isPlaying) {
                    currentPosition = player?.currentPosition ?: 0L
                    delay(500)
                }
            }

            LaunchedEffect(Unit) {
                try {
                    B2Utils.authorize(BuildConfig.B2_KEY_ID, BuildConfig.B2_ACCESS_KEY)
                    authStatusMessage = "Authorization Successful!"

                    val names = B2Utils.getAlbumDirectories(
                        bucketId = BuildConfig.B2_BUCKET_ID,
                        prefix = "MUSIC/ALBUMS/"
                    )
                    albumList = names.map { title ->
                        val albumPath = "MUSIC/ALBUMS/$title/"
                        val songs = B2Utils.getSongsInAlbum(BuildConfig.B2_BUCKET_ID, albumPath)
                        Album(
                            albumTitle = title,
                            songs = songs,
                            artworkUrl = B2Utils.getAlbumArtworkUrl(
                                BuildConfig.B2_BUCKET_ID,
                                BuildConfig.B2_BUCKET_NAME,
                                albumPath
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("B2_DEBUG", "Error: ${e.message}")
                    authStatusMessage = "Authorization Failed: ${e.message}"
                } finally {
                    isLoadingAlbums = false
                }
            }

            B2MusicPlayerTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()

                fun buildMediaItem(song: Song): MediaItem {
                    val cachedFile = B2Utils.getCachedSong(this@MainActivity, song.fileName)
                    return if (cachedFile != null) {
                        Log.d(
                            "B2_PLAYBACK",
                            "Queue cached file for ${song.fileName}: ${cachedFile.absolutePath} (${cachedFile.length()} bytes)"
                        )
                        MediaItem.Builder()
                            .setMediaId(song.fileName)
                            .setUri(Uri.fromFile(cachedFile))
                            .build()
                    } else {
                        val streamUrl = B2Utils.getDownloadUrl(
                            BuildConfig.B2_BUCKET_NAME,
                            song.fileName
                        )
                        Log.d("B2_PLAYBACK", "Queue streaming URL for ${song.fileName}")
                        MediaItem.Builder()
                            .setMediaId(song.fileName)
                            .setUri(streamUrl)
                            .setCustomCacheKey(song.fileName)
                            .build()
                    }
                }

                fun requestedSongIndex(album: Album): Int {
                    val requestedIndex = album.songs.indexOfFirst { it.fileName == requestedSongFileName }
                    if (requestedIndex != -1) {
                        return requestedIndex
                    }

                    val currentIndex = currentSong?.let { album.songs.indexOf(it) } ?: -1
                    if (currentIndex != -1) {
                        return currentIndex
                    }

                    return player?.currentMediaItemIndex ?: -1
                }

                fun playSongAt(album: Album, songIndex: Int, openBottomSheet: Boolean = false) {
                    val song = album.songs.getOrNull(songIndex) ?: return

                    currentSong = song
                    currentArtworkUrl = album.artworkUrl
                    if (openBottomSheet) {
                        showBottomSheet = true
                    }
                    requestedSongFileName = song.fileName

                    val mediaItems = album.songs.map(::buildMediaItem)
                    val selectedUri = mediaItems[songIndex].localConfiguration?.uri

                    player?.let {
                        it.setMediaItems(mediaItems)
                        it.seekTo(songIndex, 0L)
                        Log.d(
                            "B2_PLAYBACK",
                            "Selected ${song.fileName}; index=$songIndex; uriScheme=${selectedUri?.scheme}; uri=$selectedUri"
                        )
                        playWhenReadyAfterPrepare = true
                        it.prepare()
                    }

                    scope.launch {
                        try {
                            B2Utils.cacheSong(
                                context = this@MainActivity,
                                bucketName = BuildConfig.B2_BUCKET_NAME,
                                filePath = song.fileName
                            )
                        } catch (e: Exception) {
                            Log.e("B2_DEBUG", "Background cache failed for ${song.fileName}: ${e.message}", e)
                        }
                    }
                }

                val showDebug = false
                if (authStatusMessage != null && showDebug) {
                    AlertDialog(
                        onDismissRequest = { authStatusMessage = null },
                        title = { Text(text = "B2 Auth Status") },
                        text = { Text(text = authStatusMessage.orEmpty()) },
                        confirmButton = {
                            Button(onClick = { authStatusMessage = null }) {
                                Text("OK")
                            }
                        }
                    )
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (currentSong != null) {
                            MediaControlBar(
                                song = currentSong,
                                artworkUrl = currentArtworkUrl,
                                isPlaying = isPlaying,
                                onPlayPause = {
                                    if (isPlaying) player?.pause() else player?.play()
                                },
                                onNext = {
                                    selectedAlbum?.let { album ->
                                        val nextIndex = requestedSongIndex(album) + 1
                                        if (nextIndex in album.songs.indices) {
                                            playSongAt(album, nextIndex)
                                        }
                                    }
                                },
                                onClick = { showBottomSheet = true }
                            )
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "main_screen",
                        modifier = Modifier.padding(innerPadding),
                        enterTransition = {
                            slideIntoContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(500)
                            )
                        },
                        exitTransition = {
                            slideOutOfContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(500)
                            )
                        },
                        popEnterTransition = {
                            slideIntoContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(500)
                            )
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(500)
                            )
                        }
                    ) {
                        composable("main_screen") {
                            MainScreen(
                                albums = albumList,
                                isLoadingAlbums = isLoadingAlbums,
                                onNavigateToSub = { album ->
                                    selectedAlbum = album
                                    navController.navigate("sub_screen")
                                }
                            )
                        }
                        composable("sub_screen") {
                            SubScreen(
                                album = selectedAlbum,
                                onBack = { navController.popBackStack() },
                                onTrackClick = { song ->
                                    selectedAlbum?.let { album ->
                                        val songIndex = album.songs.indexOf(song)
                                        if (songIndex != -1) {
                                            val shouldOpenBottomSheet = !hasAutoOpenedBottomSheet
                                            playSongAt(album, songIndex, openBottomSheet = shouldOpenBottomSheet)
                                            if (shouldOpenBottomSheet) {
                                                hasAutoOpenedBottomSheet = true
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }

                    if (showBottomSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showBottomSheet = false },
                            sheetState = sheetState,
                            dragHandle = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                sheetState.hide()
                                                showBottomSheet = false
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    BottomSheetDefaults.DragHandle()
                                }
                            }
                        ) {
                            PlayerDetailScreen(
                                song = currentSong,
                                album = selectedAlbum,
                                artworkUrl = currentArtworkUrl,
                                isPlaying = isPlaying,
                                currentPosition = currentPosition,
                                totalDuration = totalDuration,
                                onSeek = { pos -> player?.seekTo(pos) },
                                onPlayPause = {
                                    if (isPlaying) player?.pause() else player?.play()
                                },
                                onNext = {
                                    selectedAlbum?.let { album ->
                                        val nextIndex = requestedSongIndex(album) + 1
                                        if (nextIndex in album.songs.indices) {
                                            playSongAt(album, nextIndex)
                                        }
                                    }
                                },
                                onPrevious = {
                                    player?.let { currentPlayer ->
                                        selectedAlbum?.let { album ->
                                            if (currentPlayer.currentPosition > 3000L) {
                                                currentPlayer.seekTo(0)
                                            } else {
                                                val previousIndex = requestedSongIndex(album) - 1
                                                if (previousIndex in album.songs.indices) {
                                                    playSongAt(album, previousIndex)
                                                } else {
                                                    currentPlayer.seekTo(0)
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private suspend fun warmUpAudioOutput() {
        withContext(Dispatchers.IO) {
            val sampleRate = 44_100
            val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)

            if (minBufferSize <= 0) {
                Log.w("B2_PLAYBACK", "Audio warm-up skipped; invalid min buffer size: $minBufferSize")
                return@withContext
            }

            val bufferSize = maxOf(minBufferSize, sampleRate * 2 * 2 / 4)
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AndroidAudioAttributes.Builder()
                        .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                        .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            try {
                audioTrack.play()
                audioTrack.write(ByteArray(bufferSize), 0, bufferSize)
                Thread.sleep(300)
                audioTrack.pause()
                audioTrack.flush()
                Log.d("B2_PLAYBACK", "Audio output warm-up completed")
            } catch (e: Exception) {
                Log.w("B2_PLAYBACK", "Audio output warm-up failed: ${e.message}", e)
            } finally {
                audioTrack.release()
            }
        }
    }
}

@Composable
fun MediaControlBar(
    song: Song?,
    artworkUrl: String?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = "Current Album Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = song?.title ?: "No Track Playing",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
            Row {
                IconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(imageVector = Icons.Default.SkipNext, contentDescription = "Next", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }

}

@Composable
fun PlayerDetailScreen(
    song: Song?,
    album: Album?,
    artworkUrl: String?,
    isPlaying: Boolean,
    currentPosition: Long,
    totalDuration: Long,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(24.dp)
        ) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = "Large Album Art",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = song?.title ?: "Track Title",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = album?.albumTitle ?: "Album Title",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        
        Spacer(modifier = Modifier.height(24.dp))

        // Progress Bar
        Column(modifier = Modifier.fillMaxWidth()) {
            val trackColor = MaterialTheme.colorScheme.onSurface
            val progress = if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f
            Slider(
                value = progress,
                onValueChange = { percent ->
                    onSeek((percent * totalDuration).toLong())
                },
                modifier = Modifier.fillMaxWidth(),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .offset(y = 2.dp)
                            .background(trackColor, CircleShape)
                    )
                },
                track = {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                    ) {
                        val width = size.width
                        val centerY = size.height / 2
                        val strokeWidth = 4.dp.toPx()
                        
                        // Inactive
                        drawLine(
                            color = trackColor.copy(alpha = 0.2f),
                            start = Offset(0f, centerY),
                            end = Offset(width, centerY),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        // Active
                        drawLine(
                            color = trackColor,
                            start = Offset(0f, centerY),
                            end = Offset(width * progress, centerY),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(currentPosition),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatTime(totalDuration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(64.dp)) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious, 
                    contentDescription = "Prev", 
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(80.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(64.dp)
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(64.dp)) {
                Icon(
                    imageVector = Icons.Default.SkipNext, 
                    contentDescription = "Next", 
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(48.dp))
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun MainScreen(
    albums: List<Album>,
    isLoadingAlbums: Boolean,
    onNavigateToSub: (Album) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Albums",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(16.dp),
            fontWeight = FontWeight.Bold
        )
        
        if (albums.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (isLoadingAlbums) "Albums loading" else "No albums found",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(albums) { album ->
                    AlbumItem(
                        album = album,
                        onClick = { onNavigateToSub(album) }
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumItem(album: Album, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(60.dp),
                color = MaterialTheme.colorScheme.secondary,
                shape = RoundedCornerShape(8.dp)
            ) {
                AsyncImage(
                    model = album.artworkUrl,
                    contentDescription = "Album Cover",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = album.albumTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (album.songs.isNotEmpty()) {
                    Text(
                        text = "${album.songs.size} Songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun SubScreen(album: Album?, onBack: () -> Unit, onTrackClick: (Song) -> Unit) {
    if (album == null || album.songs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "No songs found")
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Album Header
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp, bottom = 24.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(240.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        AsyncImage(
                            model = album.artworkUrl,
                            contentDescription = "Album Cover",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = album.albumTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Track list
            itemsIndexed(album.songs) { index, song ->
                Column(
                    modifier = Modifier.clickable { onTrackClick(song) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier.width(36.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                }
            }
        }
    }
}
