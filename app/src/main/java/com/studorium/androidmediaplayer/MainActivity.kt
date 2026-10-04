package com.studorium.androidmediaplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.content.ComponentName
import java.net.HttpURLConnection
import java.net.URL
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.common.util.concurrent.MoreExecutors

private enum class LibraryTab { ALBUMS, ARTISTS, SONGS, PLAYLISTS }
private enum class SearchField(val label: String) {
    ALL("All"), ARTIST("Artist"), ALBUM("Album"), SONG("Song"), GENRE("Genre"), YEAR("Year")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val store = remember { SavedServerStore(context) }
                val db = remember { MusicDatabase(context) }
                val settings = remember { AppSettingsStore(context) }
                var controller by remember { mutableStateOf<MediaController?>(null) }
                var nowPlaying by remember { mutableStateOf<MusicTrack?>(null) }
                var isPlaying by remember { mutableStateOf(false) }
                var isBuffering by remember { mutableStateOf(false) }
                var playbackError by remember { mutableStateOf<String?>(null) }
                var playbackQueue by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
                var playbackPositionMs by remember { mutableLongStateOf(0L) }
                var playbackDurationMs by remember { mutableLongStateOf(0L) }

                DisposableEffect(Unit) {
                    val sessionToken = SessionToken(
                        context,
                        ComponentName(context, MusicPlaybackService::class.java)
                    )
                    val future = MediaController.Builder(context, sessionToken).buildAsync()

                    future.addListener(
                        {
                            try {
                                val builtController = future.get()
                                controller = builtController
                                isPlaying = builtController.isPlaying
                                isBuffering = builtController.playbackState == Player.STATE_BUFFERING

                                builtController.currentMediaItem?.mediaId?.toLongOrNull()?.let { id ->
                                    nowPlaying = playbackQueue.firstOrNull { it.databaseId == id }
                                }
                            } catch (e: Exception) {
                                playbackError = e.message ?: "Could not connect to the music player."
                            }
                        },
                        MoreExecutors.directExecutor()
                    )

                    onDispose {
                        controller?.release()
                        MediaController.releaseFuture(future)
                    }
                }

                LaunchedEffect(controller, isPlaying, nowPlaying) {
                    while (controller != null) {
                        val activeController = controller
                        playbackPositionMs = activeController?.currentPosition?.coerceAtLeast(0L) ?: 0L
                        playbackDurationMs = (activeController?.duration ?: 0L).coerceAtLeast(0L)
                        delay(if (isPlaying) 500L else 1000L)
                    }
                }

                DisposableEffect(controller, playbackQueue) {
                    val activeController = controller
                    if (activeController == null) {
                        onDispose { }
                    } else {
                        val listener = object : Player.Listener {
                            override fun onIsPlayingChanged(value: Boolean) {
                                isPlaying = value
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                isBuffering = playbackState == Player.STATE_BUFFERING
                            }

                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                playbackError = error.message ?: "This track could not be played."
                            }

                            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                                val id = mediaItem?.mediaId?.toLongOrNull()
                                if (id != null) {
                                    nowPlaying = playbackQueue.firstOrNull { it.databaseId == id } ?: nowPlaying
                                }
                            }
                        }
                        activeController.addListener(listener)
                        onDispose { activeController.removeListener(listener) }
                    }
                }

                fun musicMediaItem(track: MusicTrack): MediaItem {
                    val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)

                    if (track.albumArtUrl.isNotBlank()) {
                        metadataBuilder.setArtworkUri(android.net.Uri.parse(track.albumArtUrl))
                    }

                    return MediaItem.Builder()
                        .setMediaId(track.databaseId.toString())
                        .setUri(track.resourceUrl)
                        .setMediaMetadata(metadataBuilder.build())
                        .build()
                }

                fun playQueue(queue: List<MusicTrack>, startTrack: MusicTrack) {
                    val playable = queue.filter { it.resourceUrl.isNotBlank() }
                    if (playable.isEmpty()) return

                    val startIndex = playable.indexOfFirst { it.resourceUrl == startTrack.resourceUrl }
                        .takeIf { it >= 0 } ?: 0

                    val activeController = controller
                    if (activeController == null) {
                        playbackError = "The playback service is still starting. Try the track again."
                        return
                    }

                    playbackQueue = playable
                    playbackError = null
                    nowPlaying = playable[startIndex]

                    activeController.setMediaItems(
                        playable.map { musicMediaItem(it) },
                        startIndex,
                        0L
                    )
                    activeController.prepare()
                    activeController.play()
                }

                fun playTrack(track: MusicTrack) {
                    playQueue(listOf(track), track)
                }

                fun togglePlayback() {
                    playbackError = null
                    val activeController = controller ?: return
                    if (activeController.isPlaying) activeController.pause() else activeController.play()
                }

                fun playNext() {
                    controller?.let {
                        if (it.hasNextMediaItem()) it.seekToNextMediaItem()
                    }
                }

                fun playPrevious() {
                    controller?.let {
                        if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem()
                        else it.seekTo(0L)
                    }
                }

                fun syncPlaybackQueueFromController(
                    activeController: MediaController,
                    candidates: List<MusicTrack>
                ) {
                    val byId = (playbackQueue + candidates)
                        .associateBy { it.databaseId.toString() }
                    playbackQueue = (0 until activeController.mediaItemCount).mapNotNull { index ->
                        byId[activeController.getMediaItemAt(index).mediaId]
                    }
                }

                fun playNextItems(items: List<MusicTrack>) {
                    val activeController = controller ?: return
                    val playable = items.filter { it.resourceUrl.isNotBlank() }
                    if (playable.isEmpty()) return

                    if (activeController.mediaItemCount == 0) {
                        playbackQueue = playable
                        nowPlaying = playable.first()
                        playbackError = null
                        activeController.setMediaItems(playable.map { musicMediaItem(it) })
                        activeController.prepare()
                        activeController.play()
                        return
                    }

                    val insertAt = (activeController.currentMediaItemIndex + 1)
                        .coerceIn(0, activeController.mediaItemCount)
                    activeController.addMediaItems(insertAt, playable.map { musicMediaItem(it) })
                    syncPlaybackQueueFromController(activeController, playable)
                }

                fun addToNowPlaying(items: List<MusicTrack>) {
                    val activeController = controller ?: return
                    val playable = items.filter { it.resourceUrl.isNotBlank() }
                    if (playable.isEmpty()) return

                    if (activeController.mediaItemCount == 0) {
                        playbackQueue = playable
                        nowPlaying = playable.first()
                        playbackError = null
                        activeController.setMediaItems(playable.map { musicMediaItem(it) })
                        activeController.prepare()
                        activeController.play()
                        return
                    }

                    activeController.addMediaItems(playable.map { musicMediaItem(it) })
                    syncPlaybackQueueFromController(activeController, playable)
                }

                fun seekTo(positionMs: Long) {
                    controller?.seekTo(positionMs.coerceAtLeast(0L))
                    playbackPositionMs = positionMs.coerceAtLeast(0L)
                }

                fun jumpToQueueIndex(index: Int) {
                    val activeController = controller ?: return
                    if (index in 0 until activeController.mediaItemCount) {
                        activeController.seekToDefaultPosition(index)
                        activeController.play()
                    }
                }

                fun removeQueueIndex(index: Int) {
                    val activeController = controller ?: return
                    if (index !in 0 until activeController.mediaItemCount) return
                    activeController.removeMediaItem(index)
                    playbackQueue = playbackQueue.toMutableList().apply {
                        if (index in indices) removeAt(index)
                    }
                }

                fun clearNowPlaying() {
                    controller?.stop()
                    controller?.clearMediaItems()
                    playbackQueue = emptyList()
                    nowPlaying = null
                    isPlaying = false
                    isBuffering = false
                    playbackPositionMs = 0L
                    playbackDurationMs = 0L
                    playbackError = null
                }

                var server by remember { mutableStateOf<DlnaServer?>(null) }
                var serverConnected by remember { mutableStateOf(false) }
                var reconnecting by remember { mutableStateOf(false) }
                var showDisconnectedDialog by remember { mutableStateOf(false) }
                var discovery by remember { mutableStateOf<DlnaDiscoveryResult?>(null) }
                var searchingServers by remember { mutableStateOf(false) }
                var restoring by remember { mutableStateOf(true) }
                var scanning by remember { mutableStateOf(false) }
                var progress by remember { mutableStateOf<MusicScanProgress?>(null) }
                var status by remember { mutableStateOf<String?>(null) }
                var error by remember { mutableStateOf<String?>(null) }

                var tracks by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
                var albums by remember { mutableStateOf<List<MusicAlbum>>(emptyList()) }
                var artists by remember { mutableStateOf<List<MusicArtist>>(emptyList()) }
                var selectedAlbum by remember { mutableStateOf<MusicAlbum?>(null) }
                var albumTracks by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }

                suspend fun loadLibrary() {
                    val data = withContext(Dispatchers.IO) {
                        Triple(db.getAllTracks(), db.getAlbums(), db.getArtists())
                    }
                    tracks = data.first
                    albums = data.second
                    artists = data.third
                }

                suspend fun serverResponds(candidate: DlnaServer): Boolean = withContext(Dispatchers.IO) {
                    var connection: HttpURLConnection? = null
                    try {
                        connection = URL(candidate.location).openConnection() as HttpURLConnection
                        connection.connectTimeout = 2500
                        connection.readTimeout = 2500
                        connection.requestMethod = "GET"
                        connection.instanceFollowRedirects = true
                        connection.connect()
                        connection.responseCode in 200..399
                    } catch (_: Exception) {
                        false
                    } finally {
                        connection?.disconnect()
                    }
                }

                suspend fun reconnectSavedServer(saved: DlnaServer): DlnaServer? {
                    if (serverResponds(saved)) return saved
                    return try {
                        val found = DlnaDiscovery(context).discover().servers.firstOrNull { candidate ->
                            candidate.friendlyName.equals(saved.friendlyName, ignoreCase = true) ||
                                (saved.manufacturer.isNotBlank() && saved.modelName.isNotBlank() &&
                                    candidate.manufacturer.equals(saved.manufacturer, ignoreCase = true) &&
                                    candidate.modelName.equals(saved.modelName, ignoreCase = true))
                        }
                        if (found != null && serverResponds(found)) found else null
                    } catch (_: Exception) {
                        null
                    }
                }

                fun reconnect(showFailure: Boolean = true) {
                    val saved = server ?: return
                    if (reconnecting) return
                    scope.launch {
                        reconnecting = true
                        status = "Reconnecting to ${saved.friendlyName}…"
                        val connected = reconnectSavedServer(saved)
                        if (connected != null) {
                            server = connected
                            store.saveServer(connected)
                            serverConnected = true
                            status = "Connected to ${connected.friendlyName}"
                            error = null
                            showDisconnectedDialog = false
                        } else {
                            serverConnected = false
                            status = "Music server offline — saved library is still available"
                            if (showFailure) showDisconnectedDialog = true
                        }
                        reconnecting = false
                    }
                }

                fun scan(s: DlnaServer) {
                    if (scanning) return
                    scope.launch {
                        scanning = true
                        error = null
                        status = "Starting music scan..."
                        progress = null
                        try {
                            val result = MusicLibraryScanner(db).scan(s) { progress = it }
                            loadLibrary()
                            status = "Scan complete: ${result.tracksFound} songs."
                        } catch (e: Exception) {
                            error = e.message ?: e.javaClass.simpleName
                        } finally {
                            scanning = false
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    val saved = store.loadServer()
                    server = saved
                    loadLibrary()
                    restoring = false
                    if (saved != null) {
                        reconnecting = true
                        val connected = reconnectSavedServer(saved)
                        if (connected != null) {
                            server = connected
                            store.saveServer(connected)
                            serverConnected = true
                            status = "Connected to ${connected.friendlyName}"
                        } else {
                            serverConnected = false
                            status = "Music server offline — saved library is still available"
                            showDisconnectedDialog = true
                        }
                        reconnecting = false
                    }
                }

                Surface(Modifier.fillMaxSize()) {
                    when {
                        restoring -> CenterLoading("Opening your music library...")
                        server == null -> ServerChooser(
                            result = discovery,
                            searching = searchingServers,
                            error = error,
                            onSearch = {
                                scope.launch {
                                    searchingServers = true
                                    error = null
                                    try { discovery = DlnaDiscovery(context).discover() }
                                    catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                                    finally { searchingServers = false }
                                }
                            },
                            onSelect = {
                                store.saveServer(it)
                                server = it
                                serverConnected = true
                                discovery = null
                            }
                        )
                        else -> LibraryHome(
                            tracks = tracks,
                            albums = albums,
                            artists = artists,
                            scanning = scanning,
                            serverConnected = serverConnected,
                            reconnecting = reconnecting,
                            progress = progress,
                            status = status,
                            error = error,
                            settings = settings,
                            database = db,
                            nowPlaying = nowPlaying,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            playbackError = playbackError,
                            playbackQueue = playbackQueue,
                            playbackPositionMs = playbackPositionMs,
                            playbackDurationMs = playbackDurationMs,
                            onPlayTrack = { playTrack(it) },
                            onPlayQueue = { queue, track -> playQueue(queue, track) },
                            onTogglePlayback = { togglePlayback() },
                            onNext = { playNext() },
                            onPrevious = { playPrevious() },
                            onPlayNextItems = { playNextItems(it) },
                            onAddToNowPlaying = { addToNowPlaying(it) },
                            onSeek = { seekTo(it) },
                            onJumpToQueueIndex = { jumpToQueueIndex(it) },
                            onRemoveQueueIndex = { removeQueueIndex(it) },
                            onClearNowPlaying = { clearNowPlaying() },
                            selectedAlbum = selectedAlbum,
                            albumTracks = albumTracks,
                            onSelectAlbum = { album ->
                                selectedAlbum = album
                                scope.launch {
                                    albumTracks = withContext(Dispatchers.IO) {
                                        db.getTracksForAlbum(album)
                                    }
                                }
                            },
                            onBackAlbum = {
                                selectedAlbum = null
                                albumTracks = emptyList()
                            },
                            onRefresh = {
                                if (serverConnected) scan(server!!)
                                else showDisconnectedDialog = true
                            },
                            onReconnectServer = { reconnect(true) },
                            onChangeServer = {
                                store.clearServer()
                                server = null
                                serverConnected = false
                                selectedAlbum = null
                            }
                        )
                    }
                }

                if (showDisconnectedDialog && server != null) {
                    AlertDialog(
                        onDismissRequest = { showDisconnectedDialog = false },
                        title = { Text("Music server disconnected") },
                        text = {
                            Text("Your saved music library is still available. Reconnect to the music server to play tracks. A library refresh is not required.")
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showDisconnectedDialog = false
                                    reconnect(false)
                                },
                                enabled = !reconnecting
                            ) { Text(if (reconnecting) "Reconnecting…" else "Reconnect") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDisconnectedDialog = false }) { Text("Continue Offline") }
                        }
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun LibraryHome(
    tracks: List<MusicTrack>,
    albums: List<MusicAlbum>,
    artists: List<MusicArtist>,
    scanning: Boolean,
    serverConnected: Boolean,
    reconnecting: Boolean,
    progress: MusicScanProgress?,
    status: String?,
    error: String?,
    settings: AppSettingsStore,
    database: MusicDatabase,
    nowPlaying: MusicTrack?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    playbackError: String?,
    playbackQueue: List<MusicTrack>,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    onPlayTrack: (MusicTrack) -> Unit,
    onPlayQueue: (List<MusicTrack>, MusicTrack) -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onPlayNextItems: (List<MusicTrack>) -> Unit,
    onAddToNowPlaying: (List<MusicTrack>) -> Unit,
    onSeek: (Long) -> Unit,
    onJumpToQueueIndex: (Int) -> Unit,
    onRemoveQueueIndex: (Int) -> Unit,
    onClearNowPlaying: () -> Unit,
    selectedAlbum: MusicAlbum?,
    albumTracks: List<MusicTrack>,
    onSelectAlbum: (MusicAlbum) -> Unit,
    onBackAlbum: () -> Unit,
    onRefresh: () -> Unit,
    onReconnectServer: () -> Unit,
    onChangeServer: () -> Unit
) {
    var tab by remember { mutableStateOf(LibraryTab.ALBUMS) }
    var toolsOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var randomOpen by remember { mutableStateOf(false) }
    var createPlaylistOpen by remember { mutableStateOf(false) }
    var nowPlayingOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchField by remember { mutableStateOf(SearchField.ALL) }
    var aboutOpen by remember { mutableStateOf(false) }
    var selectedPlaylist by remember { mutableStateOf<MusicPlaylist?>(null) }
    var playlists by remember { mutableStateOf<List<MusicPlaylist>>(emptyList()) }
    var playlistTracks by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var selectedArtist by remember { mutableStateOf<MusicArtist?>(null) }
    var randomTracks by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tab, createPlaylistOpen) {
        if (tab == LibraryTab.PLAYLISTS && !createPlaylistOpen) {
            playlists = withContext(Dispatchers.IO) { database.getPlaylists() }
        }
    }

    fun openPlaylist(playlist: MusicPlaylist) {
        selectedPlaylist = playlist
        scope.launch {
            playlistTracks = withContext(Dispatchers.IO) { database.getPlaylistTracks(playlist.id) }
        }
    }

    if (selectedAlbum != null) {
        AlbumDetail(
            album = selectedAlbum,
            tracks = albumTracks,
            database = database,
            onBack = onBackAlbum,
            onPlayTrack = { track -> onPlayQueue(albumTracks, track) },
            onPlayNextItems = onPlayNextItems,
            onAddToNowPlaying = onAddToNowPlaying
        )
        return
    }

    selectedPlaylist?.let { playlist ->
        PlaylistDetail(
            playlist = playlist,
            tracks = playlistTracks,
            database = database,
            onBack = { selectedPlaylist = null; playlistTracks = emptyList() },
            onPlayQueue = onPlayQueue,
            onPlayNextItems = onPlayNextItems,
            onAddToNowPlaying = onAddToNowPlaying
        )
        return
    }

    selectedArtist?.let { artist ->
        val artistTracks = remember(artist.name, tracks) {
            tracks.filter { it.artist.equals(artist.name, ignoreCase = true) }
        }
        val artistAlbums = remember(artist.name, albums) {
            albums.filter { it.artist.equals(artist.name, ignoreCase = true) }
        }
        ArtistDetail(
            artist = artist,
            albums = artistAlbums,
            tracks = artistTracks,
            database = database,
            onBack = { selectedArtist = null },
            onSelectAlbum = onSelectAlbum,
            onPlayTrack = onPlayTrack,
            onPlayQueue = onPlayQueue,
            onPlayNextItems = onPlayNextItems,
            onAddToNowPlaying = onAddToNowPlaying
        )
        return
    }

    BackHandler(
        enabled = searchOpen || randomOpen || settingsOpen || aboutOpen || createPlaylistOpen || nowPlayingOpen || queueOpen || toolsOpen
    ) {
        when {
            nowPlayingOpen -> nowPlayingOpen = false
            queueOpen -> queueOpen = false
            searchOpen -> searchOpen = false
            randomOpen -> randomOpen = false
            settingsOpen -> settingsOpen = false
            aboutOpen -> aboutOpen = false
            createPlaylistOpen -> createPlaylistOpen = false
            toolsOpen -> toolsOpen = false
        }
    }


    val normalizedQuery = query.trim().lowercase()
    val matchingSongs = remember(normalizedQuery, searchField, tracks) {
        if (normalizedQuery.isBlank()) emptyList() else tracks.asSequence().filter {
            when (searchField) {
                SearchField.ALL -> it.title.lowercase().contains(normalizedQuery) || it.artist.lowercase().contains(normalizedQuery) || it.album.lowercase().contains(normalizedQuery) || it.genre.lowercase().contains(normalizedQuery) || it.year.lowercase().contains(normalizedQuery)
                SearchField.SONG -> it.title.lowercase().contains(normalizedQuery)
                SearchField.ARTIST -> it.artist.lowercase().contains(normalizedQuery)
                SearchField.ALBUM -> it.album.lowercase().contains(normalizedQuery)
                SearchField.GENRE -> it.genre.lowercase().contains(normalizedQuery)
                SearchField.YEAR -> it.year.lowercase().contains(normalizedQuery)
            }
        }.take(300).toList()
    }
    val matchingAlbums = remember(normalizedQuery, searchField, albums) {
        if (normalizedQuery.isBlank() || searchField == SearchField.SONG) emptyList() else albums.filter {
            when (searchField) {
                SearchField.ALL -> it.title.lowercase().contains(normalizedQuery) || it.artist.lowercase().contains(normalizedQuery) || it.genre.lowercase().contains(normalizedQuery) || it.year.lowercase().contains(normalizedQuery)
                SearchField.ARTIST -> it.artist.lowercase().contains(normalizedQuery)
                SearchField.ALBUM -> it.title.lowercase().contains(normalizedQuery)
                SearchField.GENRE -> it.genre.lowercase().contains(normalizedQuery)
                SearchField.YEAR -> it.year.lowercase().contains(normalizedQuery)
                SearchField.SONG -> false
            }
        }.take(100)
    }
    val matchingArtists = remember(normalizedQuery, searchField, artists) {
        if (normalizedQuery.isBlank() || (searchField != SearchField.ALL && searchField != SearchField.ARTIST)) emptyList()
        else artists.filter { it.name.lowercase().contains(normalizedQuery) }.take(100)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(4.dp))

            SlimLibraryTabs(tab) { tab = it }

            SlimSearchBar(
                query = query,
                searchField = searchField,
                onSearchFieldChange = { searchField = it },
                onQueryChange = {
                    query = it
                    searchOpen = it.isNotBlank()
                },
                onSettings = { toolsOpen = true }
            )

            if (scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "${progress?.tracksFound ?: 0} songs • ${progress?.foldersScanned ?: 0} folders • ${progress?.currentFolder ?: "Preparing"}",
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (error != null) {
                Text(error, Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
            }

            when (tab) {
                LibraryTab.ALBUMS -> AlbumGrid(
                    albums = albums,
                    database = database,
                    onSelect = onSelectAlbum,
                    onPlayQueue = onPlayQueue,
                    onPlayNextItems = onPlayNextItems,
                    onAddToNowPlaying = onAddToNowPlaying
                )
                LibraryTab.ARTISTS -> ArtistList(artists, onSelect = { selectedArtist = it })
                LibraryTab.SONGS -> SongList(
                    tracks = tracks,
                    database = database,
                    onPlayTrack = { track -> onPlayTrack(track) },
                    onPlayNextItems = onPlayNextItems,
                    onAddToNowPlaying = onAddToNowPlaying
                )
                LibraryTab.PLAYLISTS -> PlaylistList(
                    playlists = playlists,
                    onSelect = { openPlaylist(it) },
                    onCreate = { createPlaylistOpen = true }
                )
            }
        }

        Box(
            modifier = Modifier.align(Alignment.TopStart)
        ) {
    
        }

        if (toolsOpen) {
            ToolsModal(
                songCount = tracks.size,
                albumCount = albums.size,
                artistCount = artists.size,
                scanning = scanning,
                serverConnected = serverConnected,
                reconnecting = reconnecting,
                status = status,
                nowPlayingAvailable = nowPlaying != null,
                onNowPlaying = {
                    queueOpen = true
                    toolsOpen = false
                },
                onRandom100 = {
                    scope.launch {
                        randomTracks = withContext(Dispatchers.IO) {
                            database.getRandomTracks(100)
                        }
                        randomOpen = true
                        toolsOpen = false
                    }
                },
                onCreatePlaylist = {
                    createPlaylistOpen = true
                    toolsOpen = false
                },
                onArtworkSettings = {
                    settingsOpen = true
                    toolsOpen = false
                },
                onAbout = {
                    aboutOpen = true
                    toolsOpen = false
                },
                onReconnect = {
                    onReconnectServer()
                    toolsOpen = false
                },
                onRefresh = {
                    onRefresh()
                    toolsOpen = false
                },
                onChangeServer = {
                    toolsOpen = false
                    onChangeServer()
                },
                onClose = { toolsOpen = false }
            )
        }

        if (searchOpen) {
            SearchOverlay(
                query = query,
                searchField = searchField,
                onSearchFieldChange = { searchField = it },
                albums = matchingAlbums,
                artists = matchingArtists,
                songs = matchingSongs,
                onQueryChange = { query = it },
                onSelectAlbum = {
                    // Keep Search open behind the album so Back returns to the same results.
                    onSelectAlbum(it)
                },
                onSelectArtist = { artist -> selectedArtist = artist },
                database = database,
                onPlayTrack = onPlayTrack,
                onPlayNextItems = onPlayNextItems,
                onAddToNowPlaying = onAddToNowPlaying,
                onClose = { searchOpen = false }
            )
        }

        if (randomOpen) {
            Random100Overlay(
                tracks = randomTracks,
                onPlayTrack = { track -> onPlayQueue(randomTracks, track) },
                onShuffleAgain = {
                    scope.launch {
                        randomTracks = withContext(Dispatchers.IO) {
                            database.getRandomTracks(100)
                        }
                    }
                },
                onClose = { randomOpen = false }
            )
        }

        if (createPlaylistOpen) {
            CreatePlaylistOverlay(
                database = database,
                onClose = { createPlaylistOpen = false }
            )
        }

        if (nowPlaying != null && !searchOpen && !randomOpen && !settingsOpen && !createPlaylistOpen && !nowPlayingOpen && !queueOpen && !toolsOpen) {
            NowPlayingBar(
                track = nowPlaying,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                error = playbackError,
                onPrevious = onPrevious,
                onTogglePlayback = onTogglePlayback,
                onNext = onNext,
                onOpen = { nowPlayingOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        if (nowPlayingOpen && nowPlaying != null) {
            NowPlayingOverlay(
                track = nowPlaying,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                error = playbackError,
                positionMs = playbackPositionMs,
                durationMs = playbackDurationMs,
                queueSize = playbackQueue.size,
                onPrevious = onPrevious,
                onTogglePlayback = onTogglePlayback,
                onNext = onNext,
                onSeek = onSeek,
                onOpenQueue = {
                    nowPlayingOpen = false
                    queueOpen = true
                },
                onClose = { nowPlayingOpen = false }
            )
        }

        if (queueOpen) {
            NowPlayingQueueOverlay(
                queue = playbackQueue,
                currentTrack = nowPlaying,
                database = database,
                onJumpToIndex = onJumpToQueueIndex,
                onPlayNextItems = onPlayNextItems,
                onAddToPlaylist = { },
                onRemoveIndex = onRemoveQueueIndex,
                onClear = onClearNowPlaying,
                onClose = { queueOpen = false }
            )
        }

        if (settingsOpen) {
            ArtworkSettingsOverlay(settings = settings, onClose = { settingsOpen = false })
        }
        if (aboutOpen) {
            AboutOverlay(tracks.size, albums.size, artists.size) { aboutOpen = false }
        }
    }
}

@Composable
private fun SlimSearchBar(
    query: String, searchField: SearchField, onSearchFieldChange: (SearchField) -> Unit,
    onQueryChange: (String) -> Unit, onSettings: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val tablet = configuration.screenWidthDp >= 600
    var fieldMenuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = if (tablet) 14.dp else 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            OutlinedButton(onClick = { fieldMenuOpen = true }, modifier = Modifier.height(56.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("${searchField.label} ▾") }
            DropdownMenu(expanded = fieldMenuOpen, onDismissRequest = { fieldMenuOpen = false }) {
                SearchField.entries.forEach { field -> DropdownMenuItem(text = { Text(field.label) }, onClick = { onSearchFieldChange(field); fieldMenuOpen = false }) }
            }
        }
        OutlinedTextField(value = query, onValueChange = onQueryChange, modifier = Modifier.weight(1f), singleLine = true,
            placeholder = { Text("Search ${searchField.label.lowercase()}…") }, shape = RoundedCornerShape(7.dp))
        OutlinedButton(onClick = onSettings, modifier = Modifier.width(54.dp).height(56.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(7.dp)) { Text("⚙") }
    }
}

@Composable
private fun ToolsModal(
    songCount: Int,
    albumCount: Int,
    artistCount: Int,
    scanning: Boolean,
    serverConnected: Boolean,
    reconnecting: Boolean,
    status: String?,
    nowPlayingAvailable: Boolean,
    onNowPlaying: () -> Unit,
    onRandom100: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onArtworkSettings: () -> Unit,
    onAbout: () -> Unit,
    onReconnect: () -> Unit,
    onRefresh: () -> Unit,
    onChangeServer: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column {
                Text("Music")
                Text(
                    "$songCount songs • $albumCount releases • $artistCount artists",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Normal
                )
            }
        },
        text = {
            Column {
                if (!status.isNullOrBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                SideAction(
                    "Now Playing",
                    "View and manage the current playback queue",
                    onNowPlaying,
                    enabled = nowPlayingAvailable
                )
                SideAction("Random 100", "Create a temporary 100-track selection", onRandom100)
                SideAction("Create Playlist", "Make a new playlist for your library", onCreatePlaylist)
                SideAction("Artwork", "Caching and missing-cover options", onArtworkSettings)
                SideAction("About", "App information and library statistics", onAbout)

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                SideAction(
                    if (reconnecting) "Reconnecting…" else if (serverConnected) "Server Connected" else "Reconnect Server",
                    if (serverConnected) "Music server is available" else "Reconnect without rescanning the library",
                    onReconnect,
                    enabled = !reconnecting && !serverConnected
                )
                SideAction(
                    if (scanning) "Scanning…" else "Refresh Library",
                    "Update the local music catalogue",
                    onRefresh,
                    enabled = !scanning && serverConnected
                )
                SideAction("Music Server", "Choose a different DLNA server", onChangeServer)
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun SideAction(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(7.dp),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchOverlay(
    query: String,
    searchField: SearchField,
    onSearchFieldChange: (SearchField) -> Unit,
    albums: List<MusicAlbum>,
    artists: List<MusicArtist>,
    songs: List<MusicTrack>,
    onQueryChange: (String) -> Unit,
    onSelectAlbum: (MusicAlbum) -> Unit,
    onSelectArtist: (MusicArtist) -> Unit,
    database: MusicDatabase,
    onPlayTrack: (MusicTrack) -> Unit,
    onPlayNextItems: (List<MusicTrack>) -> Unit,
    onAddToNowPlaying: (List<MusicTrack>) -> Unit,
    onClose: () -> Unit
) {
    var menuTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var infoTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var playlistTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var menuAlbum by remember { mutableStateOf<MusicAlbum?>(null) }
    var infoAlbum by remember { mutableStateOf<MusicAlbum?>(null) }
    var playlistTracks by remember { mutableStateOf<List<MusicTrack>?>(null) }
    var bulkTracks by remember { mutableStateOf<List<MusicTrack>?>(null) }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Search", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }

            var fieldMenuOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    OutlinedButton(onClick = { fieldMenuOpen = true }) { Text("${searchField.label} ▾") }
                    DropdownMenu(expanded = fieldMenuOpen, onDismissRequest = { fieldMenuOpen = false }) {
                        SearchField.entries.forEach { field -> DropdownMenuItem(text = { Text(field.label) }, onClick = { onSearchFieldChange(field); fieldMenuOpen = false }) }
                    }
                }
                OutlinedTextField(value = query, onValueChange = onQueryChange, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Search ${searchField.label.lowercase()}") })
            }

            if (query.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Search your local music catalogue.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    if (artists.isNotEmpty()) {
                        item {
                            Text("Artists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        items(artists, key = { "artist:${it.name.lowercase()}" }) { artist ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { onSelectArtist(artist) }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(artist.name, fontWeight = FontWeight.SemiBold)
                                Text("${artist.albumCount} releases • ${artist.trackCount} tracks", style = MaterialTheme.typography.labelSmall)
                            }
                            HorizontalDivider()
                        }
                    }

                    if (albums.isNotEmpty()) {
                        item {
                            Text("Albums / Releases", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        items(albums, key = { "album:${it.title.lowercase()}|${it.artist.lowercase()}" }) { album ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .pointerInput(album) {
                                        detectTapGestures(
                                            onTap = { onSelectAlbum(album) },
                                            onLongPress = { menuAlbum = album }
                                        )
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DlnaAlbumArt(album.albumArtUrl, Modifier.size(46.dp).clip(RoundedCornerShape(4.dp)))
                                Column(Modifier.padding(start = 9.dp).weight(1f)) {
                                    Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text(album.artist, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            HorizontalDivider()
                        }
                    }

                    if (songs.isNotEmpty()) {
                        item {
                            Text("Songs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        items(songs, key = { "song:${it.resourceUrl}" }) { track ->
                            TrackRow(
                                track = track,
                                onClick = { onPlayTrack(track) },
                                onLongClick = { menuTrack = track }
                            )
                            HorizontalDivider()
                        }
                    }

                    if (artists.isEmpty() && albums.isEmpty() && songs.isEmpty()) {
                        item {
                            Text("No matches found.", modifier = Modifier.padding(top = 18.dp))
                        }
                    }
                }
            }
        }
    }

    menuTrack?.let { track ->
        ItemActionDialog(
            title = track.title,
            infoLabel = "Song Info",
            onDismiss = { menuTrack = null },
            onSelectAll = { bulkTracks = songs; menuTrack = null },
            onPlayNext = { onPlayNextItems(listOf(track)); menuTrack = null },
            onAddToNowPlaying = { onAddToNowPlaying(listOf(track)); menuTrack = null },
            onAddToPlaylist = { playlistTrack = track; menuTrack = null },
            onInfo = { infoTrack = track; menuTrack = null }
        )
    }
    infoTrack?.let { SongInfoDialog(it) { infoTrack = null } }
    playlistTrack?.let {
        PlaylistPickerDialog(database, listOf(it)) { playlistTrack = null }
    }

}

@Composable
private fun Random100Overlay(
    tracks: List<MusicTrack>,
    onPlayTrack: (MusicTrack) -> Unit,
    onShuffleAgain: () -> Unit,
    onClose: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Random 100", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${tracks.size} tracks • temporary queue", style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = { tracks.firstOrNull()?.let(onPlayTrack) },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) { Text("Play", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onShuffleAgain) { Text("Again") }
                TextButton(onClick = onClose) { Text("Close") }
            }
            SongList(tracks = tracks, onPlayTrack = onPlayTrack)
        }
    }
}

@Composable
private fun CreatePlaylistOverlay(
    database: MusicDatabase,
    onClose: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Create Playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Playlist name") }
            )
            Button(
                onClick = {
                    val clean = name.trim()
                    if (clean.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { database.createPlaylist(clean) }
                            onClose()
                        }
                    }
                },
                enabled = name.trim().isNotBlank(),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("Create")
            }
        }
    }
}

@Composable
private fun NowPlayingBar(
    track: MusicTrack,
    isPlaying: Boolean,
    isBuffering: Boolean,
    error: String?,
    onPrevious: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val tablet = configuration.screenWidthDp >= 600

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column {
            if (error != null) {
                Text(
                    error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (tablet) 18.dp else 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clickable { onOpen() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DlnaAlbumArt(track.albumArtUrl, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))

                    Column(Modifier.padding(start = 9.dp).weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isBuffering) {
                            Text("Buffering…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                TextButton(onClick = onPrevious) { Text("◀") }
                Button(
                    onClick = onTogglePlayback,
                    modifier = Modifier.height(38.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(if (isPlaying) "Pause" else "Play")
                }
                TextButton(onClick = onNext) { Text("▶") }
            }
        }
    }
}

@Composable
private fun NowPlayingOverlay(
    track: MusicTrack,
    isPlaying: Boolean,
    isBuffering: Boolean,
    error: String?,
    positionMs: Long,
    durationMs: Long,
    queueSize: Int,
    onPrevious: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    var scrubPosition by remember(track.resourceUrl) { mutableFloatStateOf(positionMs.toFloat()) }
    var scrubbing by remember { mutableStateOf(false) }

    LaunchedEffect(positionMs, scrubbing) {
        if (!scrubbing) scrubPosition = positionMs.toFloat()
    }

    Surface(Modifier.fillMaxSize(), tonalElevation = 8.dp) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("‹ Back") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenQueue) { Text("Queue ($queueSize)") }
            }

            Spacer(Modifier.height(8.dp))
            DlnaAlbumArt(
                track.albumArtUrl,
                Modifier
                    .fillMaxWidth(0.82f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.height(18.dp))
            Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (isBuffering) Text("Buffering…", style = MaterialTheme.typography.labelSmall)
            if (!error.isNullOrBlank()) Text(error, style = MaterialTheme.typography.labelSmall)

            Spacer(Modifier.height(12.dp))

            val safeDuration = durationMs.coerceAtLeast(1L)
            Slider(
                value = scrubPosition.coerceIn(0f, safeDuration.toFloat()),
                onValueChange = {
                    scrubbing = true
                    scrubPosition = it
                },
                onValueChangeFinished = {
                    onSeek(scrubPosition.toLong())
                    scrubbing = false
                },
                valueRange = 0f..safeDuration.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )

            Row(Modifier.fillMaxWidth()) {
                Text(formatMillis(scrubPosition.toLong()), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    if (durationMs > 0L) "-${formatMillis((durationMs - scrubPosition.toLong()).coerceAtLeast(0L))}" else "--:--",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrevious) { Text("◀ Previous") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onTogglePlayback, modifier = Modifier.height(48.dp)) {
                    Text(if (isPlaying) "Pause" else "Play")
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onNext) { Text("Next ▶") }
            }
        }
    }
}

@Composable
private fun NowPlayingQueueOverlay(
    queue: List<MusicTrack>,
    currentTrack: MusicTrack?,
    database: MusicDatabase,
    onJumpToIndex: (Int) -> Unit,
    onPlayNextItems: (List<MusicTrack>) -> Unit,
    onAddToPlaylist: (MusicTrack) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    var confirmClear by remember { mutableStateOf(false) }
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var infoTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var playlistTrack by remember { mutableStateOf<MusicTrack?>(null) }

    Surface(Modifier.fillMaxSize(), tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Now Playing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${queue.size} tracks in queue", style = MaterialTheme.typography.labelSmall)
                }
                if (queue.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                TextButton(onClick = onClose) { Text("‹ Back") }
            }
            HorizontalDivider()

            if (queue.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing is queued.")
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(queue.size) { index ->
                        val track = queue[index]
                        val current = currentTrack?.resourceUrl == track.resourceUrl
                        Surface(
                            tonalElevation = if (current) 4.dp else 0.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(track.resourceUrl, index) {
                                    detectTapGestures(
                                        onTap = { onJumpToIndex(index) },
                                        onLongPress = { menuIndex = index }
                                    )
                                }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (current) "▶" else "${index + 1}",
                                    modifier = Modifier.width(34.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        track.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • "),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(formatDuration(track.duration), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear Now Playing?") },
            text = { Text("This will stop playback and remove every track from the queue.") },
            confirmButton = { TextButton(onClick = { onClear(); confirmClear = false; onClose() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }

    menuIndex?.let { index ->
        val track = queue.getOrNull(index)
        if (track != null) {
            AlertDialog(
                onDismissRequest = { menuIndex = null },
                title = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column {
                        TextButton(
                            onClick = { onPlayNextItems(listOf(track)); menuIndex = null },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Play Next") }
                        TextButton(
                            onClick = { playlistTrack = track; menuIndex = null },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add to Playlist") }
                        TextButton(
                            onClick = { onRemoveIndex(index); menuIndex = null },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Remove from Now Playing") }
                        TextButton(
                            onClick = { infoTrack = track; menuIndex = null },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Song Info") }
                    }
                },
                confirmButton = { TextButton(onClick = { menuIndex = null }) { Text("Cancel") } }
            )
        }
    }

    infoTrack?.let { SongInfoDialog(it) { infoTrack = null } }
    playlistTrack?.let {
        PlaylistPickerDialog(database, listOf(it)) { playlistTrack = null }
    }
}

private fun formatMillis(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0:00"
    val totalSeconds = milliseconds / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun ArtworkSettingsOverlay(
    settings: AppSettingsStore,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var cacheArtwork by remember { mutableStateOf(settings.cacheArtwork) }
    var cacheBytes by remember { mutableLongStateOf(ArtworkCache.sizeBytes(context)) }
    var lookupMissing by remember { mutableStateOf(settings.lookupMissingArtwork) }
    var wifiOnly by remember { mutableStateOf(settings.artworkWifiOnly) }

    Surface(Modifier.fillMaxSize(), tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Artwork", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }

            Text(
                "Artwork remains under your control. The app will not replace server artwork automatically.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 14.dp)
            )

            SettingSwitch(
                title = "Cache artwork on this device",
                description = "Store artwork locally for faster browsing and availability when the music server is offline.",
                checked = cacheArtwork,
                onChecked = {
                    cacheArtwork = it
                    settings.cacheArtwork = it
                }
            )

            SettingSwitch(
                title = "Look up missing artwork",
                description = "Only releases with no artwork from your server will be eligible for external lookup.",
                checked = lookupMissing,
                onChecked = {
                    lookupMissing = it
                    settings.lookupMissingArtwork = it
                }
            )

            SettingSwitch(
                title = "Wi-Fi only for artwork downloads",
                description = "Avoid external artwork downloads over mobile data.",
                checked = wifiOnly,
                onChecked = {
                    wifiOnly = it
                    settings.artworkWifiOnly = it
                }
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text(
                "Artwork cache: ${formatCacheSize(cacheBytes)} / 100 MB. Covers are cached only as you encounter them; oldest covers are removed automatically when the limit is reached.",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "External artwork lookup is not connected yet. Server artwork is never replaced automatically.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )

            OutlinedButton(
                onClick = {
                    ArtworkCache.clear(context)
                    cacheBytes = 0L
                },
                enabled = cacheBytes > 0L,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("Clear artwork cache")
            }
        }
    }
}

private fun formatCacheSize(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb < 1.0) "${(bytes / 1024L).coerceAtLeast(0L)} KB" else String.format("%.1f MB", mb)
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SlimLibraryTabs(selected: LibraryTab, onSelected: (LibraryTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        SlimTab("Albums", selected == LibraryTab.ALBUMS, Modifier.weight(1f)) { onSelected(LibraryTab.ALBUMS) }
        SlimTab("Artists", selected == LibraryTab.ARTISTS, Modifier.weight(1f)) { onSelected(LibraryTab.ARTISTS) }
        SlimTab("Songs", selected == LibraryTab.SONGS, Modifier.weight(1f)) { onSelected(LibraryTab.SONGS) }
        SlimTab("Playlists", selected == LibraryTab.PLAYLISTS, Modifier.weight(1f)) { onSelected(LibraryTab.PLAYLISTS) }
    }
}

@Composable
private fun SlimTab(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            shape = RoundedCornerShape(5.dp)) {
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            shape = RoundedCornerShape(5.dp)) {
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<MusicAlbum>,
    database: MusicDatabase,
    onSelect: (MusicAlbum) -> Unit,
    onPlayQueue: (List<MusicTrack>, MusicTrack) -> Unit,
    onPlayNextItems: (List<MusicTrack>) -> Unit,
    onAddToNowPlaying: (List<MusicTrack>) -> Unit
) {
    val configuration = LocalConfiguration.current
    val minimumAlbumWidth = if (configuration.screenWidthDp >= 600) 180.dp else 145.dp
    val scope = rememberCoroutineScope()
    var menuAlbum by remember { mutableStateOf<MusicAlbum?>(null) }
    var infoAlbum by remember { mutableStateOf<MusicAlbum?>(null) }
    var playlistTracks by remember { mutableStateOf<List<MusicTrack>?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minimumAlbumWidth),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(albums, key = { "${it.title.lowercase()}|${it.artist.lowercase()}" }) { album ->
            Column(
                Modifier.pointerInput(album) {
                    detectTapGestures(
                        onTap = { onSelect(album) },
                        onLongPress = { menuAlbum = album }
                    )
                }
            ) {
                DlnaAlbumArt(album.albumArtUrl, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(5.dp)))
                Text(album.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                Text(album.artist, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    menuAlbum?.let { album ->
        ItemActionDialog(
            title = album.title,
            infoLabel = "Album Info",
            onDismiss = { menuAlbum = null },
            onPlayNow = {
                scope.launch {
                    val selected = withContext(Dispatchers.IO) { database.getTracksForAlbum(album) }
                    selected.firstOrNull()?.let { onPlayQueue(selected, it) }
                    menuAlbum = null
                }
            },
            onSelectAll = {
                scope.launch {
                    playlistTracks = withContext(Dispatchers.IO) { database.getTracksForAlbum(album) }
                    menuAlbum = null
                }
            },
            onPlayNext = {
                scope.launch {
                    onPlayNextItems(withContext(Dispatchers.IO) { database.getTracksForAlbum(album) })
                    menuAlbum = null
                }
            },
            onAddToNowPlaying = {
                scope.launch {
                    onAddToNowPlaying(withContext(Dispatchers.IO) { database.getTracksForAlbum(album) })
                    menuAlbum = null
                }
            },
            onAddToPlaylist = {
                scope.launch {
                    playlistTracks = withContext(Dispatchers.IO) { database.getTracksForAlbum(album) }
                    menuAlbum = null
                }
            },
            onInfo = {
                infoAlbum = album
                menuAlbum = null
            }
        )
    }

    infoAlbum?.let {
        AlbumInfoDialog(it) { infoAlbum = null }
    }

    playlistTracks?.let { selected ->
        PlaylistPickerDialog(
            database = database,
            tracks = selected,
            onDismiss = { playlistTracks = null }
        )
    }
}

@Composable
private fun ArtistList(
    artists: List<MusicArtist>,
    onSelect: (MusicArtist) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
        items(artists, key = { it.name.lowercase() }) { artist ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(artist) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DlnaAlbumArt(artist.artworkUrl, Modifier.size(48.dp).clip(RoundedCornerShape(5.dp)))
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(artist.name, fontWeight = FontWeight.Medium)
                    Text("${artist.albumCount} releases • ${artist.trackCount} tracks", style = MaterialTheme.typography.labelSmall)
                }
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ArtistDetail(
    artist: MusicArtist,
    albums: List<MusicAlbum>,
    tracks: List<MusicTrack>,
    database: MusicDatabase,
    onBack: () -> Unit,
    onSelectAlbum: (MusicAlbum) -> Unit,
    onPlayTrack: (MusicTrack) -> Unit,
    onPlayQueue: (List<MusicTrack>, MusicTrack) -> Unit,
    onPlayNextItems: (List<MusicTrack>) -> Unit,
    onAddToNowPlaying: (List<MusicTrack>) -> Unit
) {
    BackHandler { onBack() }
    var section by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            DlnaAlbumArt(artist.artworkUrl, Modifier.size(52.dp).clip(RoundedCornerShape(5.dp)))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(artist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${albums.size} releases • ${tracks.size} tracks", style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (section == 0) {
                Button(onClick = { section = 0 }, modifier = Modifier.weight(1f).height(34.dp)) { Text("Albums") }
                OutlinedButton(onClick = { section = 1 }, modifier = Modifier.weight(1f).height(34.dp)) { Text("Tracks") }
            } else {
                OutlinedButton(onClick = { section = 0 }, modifier = Modifier.weight(1f).height(34.dp)) { Text("Albums") }
                Button(onClick = { section = 1 }, modifier = Modifier.weight(1f).height(34.dp)) { Text("Tracks") }
            }
        }

        if (section == 0) {
            AlbumGrid(
                albums = albums,
                database = database,
                onSelect = onSelectAlbum,
                onPlayQueue = onPlayQueue,
                onPlayNextItems = onPlayNextItems,
                onAddToNowPlaying = onAddToNowPlaying
            )
        } else {
            SongList(
                tracks = tracks,
                database = database,
                onPlayTrack = onPlayTrack,
                onPlayNextItems = onPlayNextItems,
                onAddToNowPlaying = onAddToNowPlaying
            )
        }
    }
}

@Composable
private fun SongList(
    tracks: List<MusicTrack>,
    database: MusicDatabase? = null,
    onPlayTrack: (MusicTrack) -> Unit = {},
    onPlayNextItems: (List<MusicTrack>) -> Unit = {},
    onAddToNowPlaying: (List<MusicTrack>) -> Unit = {}
) {
    var menuTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var infoTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var playlistTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var playlistTracks by remember { mutableStateOf<List<MusicTrack>?>(null) }
    var bulkTracks by remember { mutableStateOf<List<MusicTrack>?>(null) }

    LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
        items(tracks, key = { it.resourceUrl }) { track ->
            TrackRow(
                track = track,
                onClick = { onPlayTrack(track) },
                onLongClick = { menuTrack = track }
            )
            HorizontalDivider()
        }
    }

    menuTrack?.let { track ->
        ItemActionDialog(
            title = track.title,
            infoLabel = "Song Info",
            onDismiss = { menuTrack = null },
            onSelectAll = { bulkTracks = tracks; menuTrack = null },
            onPlayNext = {
                onPlayNextItems(listOf(track))
                menuTrack = null
            },
            onAddToNowPlaying = {
                onAddToNowPlaying(listOf(track))
                menuTrack = null
            },
            onAddToPlaylist = {
                playlistTrack = track
                menuTrack = null
            },
            onInfo = {
                infoTrack = track
                menuTrack = null
            }
        )
    }

    infoTrack?.let {
        SongInfoDialog(it) { infoTrack = null }
    }

    if (database != null) {
        playlistTrack?.let {
            PlaylistPickerDialog(
                database = database,
                tracks = listOf(it),
                onDismiss = { playlistTrack = null }
            )
        }
    }

    if (database != null) {
        playlistTracks?.let { selected ->
            PlaylistPickerDialog(
                database = database,
                tracks = selected,
                onDismiss = { playlistTracks = null }
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: MusicTrack,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(track.resourceUrl) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • "),
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatDuration(track.duration), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AlbumDetail(
    album: MusicAlbum,
    tracks: List<MusicTrack>,
    database: MusicDatabase,
    onBack: () -> Unit,
    onPlayTrack: (MusicTrack) -> Unit,
    onPlayNextItems: (List<MusicTrack>) -> Unit,
    onAddToNowPlaying: (List<MusicTrack>) -> Unit
) {
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text("‹ Albums")
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            DlnaAlbumArt(album.albumArtUrl, Modifier.size(120.dp).clip(RoundedCornerShape(6.dp)))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(album.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(album.artist, style = MaterialTheme.typography.bodyMedium)
                val detail = listOf(album.year, album.genre).filter { it.isNotBlank() }.joinToString(" • ")
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.labelSmall)
                Text("${album.trackCount} tracks", style = MaterialTheme.typography.labelSmall)
                Button(onClick = { tracks.firstOrNull()?.let(onPlayTrack) }, modifier = Modifier.padding(top = 7.dp).height(32.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)) {
                    Text("Play", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
        SongList(
            tracks = tracks,
            database = database,
            onPlayTrack = onPlayTrack,
            onPlayNextItems = onPlayNextItems,
            onAddToNowPlaying = onAddToNowPlaying
        )
    }
}

@Composable
private fun BulkSelectionDialog(
    count: Int,
    onDismiss: () -> Unit,
    onPlayNow: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToNowPlaying: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$count items selected") },
        text = {
            Column {
                TextButton(onClick = onPlayNow, modifier = Modifier.fillMaxWidth()) { Text("Play Now") }
                TextButton(onClick = onPlayNext, modifier = Modifier.fillMaxWidth()) { Text("Play Next") }
                TextButton(onClick = onAddToNowPlaying, modifier = Modifier.fillMaxWidth()) { Text("Add to Now Playing") }
                TextButton(onClick = onAddToPlaylist, modifier = Modifier.fillMaxWidth()) { Text("Add to Playlist") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ItemActionDialog(
    title: String,
    infoLabel: String,
    onDismiss: () -> Unit,
    onPlayNow: (() -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null,
    onPlayNext: () -> Unit,
    onAddToNowPlaying: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onInfo: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (onPlayNow != null) {
                    TextButton(onClick = onPlayNow, modifier = Modifier.fillMaxWidth()) { Text("Play Now") }
                }
                if (onSelectAll != null) {
                    TextButton(onClick = onSelectAll, modifier = Modifier.fillMaxWidth()) { Text("Select All") }
                }
                TextButton(onClick = onPlayNext, modifier = Modifier.fillMaxWidth()) { Text("Play Next") }
                TextButton(onClick = onAddToNowPlaying, modifier = Modifier.fillMaxWidth()) { Text("Add to Now Playing") }
                TextButton(onClick = onAddToPlaylist, modifier = Modifier.fillMaxWidth()) { Text("Add to Playlist") }
                TextButton(onClick = onInfo, modifier = Modifier.fillMaxWidth()) { Text(infoLabel) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SongInfoDialog(track: MusicTrack, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song Info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                InfoLine("Title", track.title)
                InfoLine("Artist", track.artist)
                InfoLine("Release", track.album)
                InfoLine("Genre", track.genre)
                InfoLine("Year", track.year)
                InfoLine("Track", track.trackNumber?.toString() ?: "")
                InfoLine("Duration", formatDuration(track.duration))
                InfoLine("Format", track.mimeType)
                InfoLine("Server", track.serverName)
                InfoLine("DLNA ID", track.dlnaId)
                InfoLine("Source", track.resourceUrl)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun AlbumInfoDialog(album: MusicAlbum, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Album Info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                InfoLine("Release", album.title)
                InfoLine("Artist", album.artist)
                InfoLine("Year", album.year)
                InfoLine("Genre", album.genre)
                InfoLine("Tracks", album.trackCount.toString())
                InfoLine("Artwork", album.albumArtUrl)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isNotBlank()) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlaylistPickerDialog(
    database: MusicDatabase,
    tracks: List<MusicTrack>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<MusicPlaylist>>(emptyList()) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        playlists = withContext(Dispatchers.IO) { database.getPlaylists() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Playlist") },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text("No playlists yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    playlists.forEach { playlist ->
                        TextButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        database.addTracksToPlaylist(playlist.id, tracks)
                                    }
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${playlist.name}  (${playlist.trackCount})")
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("New playlist") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val clean = newName.trim()
                        if (clean.isNotBlank()) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val id = database.createPlaylist(clean)
                                    if (id > 0) database.addTracksToPlaylist(id, tracks)
                                }
                                onDismiss()
                            }
                        }
                    },
                    enabled = newName.trim().isNotBlank(),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text("Create and Add")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PlaylistList(playlists: List<MusicPlaylist>, onSelect: (MusicPlaylist) -> Unit, onCreate: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Playlists", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onCreate) { Text("+ New") }
        }
        if (playlists.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No playlists yet.") }
        else LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(playlist) }.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(playlist.name, fontWeight = FontWeight.SemiBold); Text("${playlist.trackCount} tracks", style = MaterialTheme.typography.labelSmall) }
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PlaylistDetail(playlist: MusicPlaylist, tracks: List<MusicTrack>, database: MusicDatabase, onBack: () -> Unit,
    onPlayQueue: (List<MusicTrack>, MusicTrack) -> Unit, onPlayNextItems: (List<MusicTrack>) -> Unit, onAddToNowPlaying: (List<MusicTrack>) -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Column(Modifier.weight(1f)) { Text(playlist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("${tracks.size} tracks", style = MaterialTheme.typography.labelSmall) }
            Button(onClick = { tracks.firstOrNull()?.let { onPlayQueue(tracks, it) } }, enabled = tracks.isNotEmpty()) { Text("Play") }
        }
        HorizontalDivider()
        SongList(tracks = tracks, database = database, onPlayTrack = { onPlayQueue(tracks, it) }, onPlayNextItems = onPlayNextItems, onAddToNowPlaying = onAddToNowPlaying)
    }
}

@Composable
private fun AboutOverlay(songCount: Int, albumCount: Int, artistCount: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() }
    BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize(), tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onClose) { Text("‹ Back") }; Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(18.dp))
            Text("DLNA Music", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("by STUDORIUM")
            Spacer(Modifier.height(14.dp))
            InfoLine("Version", packageInfo?.versionName ?: "Unknown")
            InfoLine("Library", "$songCount songs • $albumCount releases • $artistCount artists")
            Spacer(Modifier.height(14.dp))
            Text("A music-focused Android player for browsing and playing personal DLNA music libraries.")
        }
    }
}

@Composable
private fun ServerChooser(result: DlnaDiscoveryResult?, searching: Boolean, error: String?,
                          onSearch: () -> Unit, onSelect: (DlnaServer) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Choose Music Server", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Button(onClick = onSearch, enabled = !searching, modifier = Modifier.padding(vertical = 14.dp)) {
            Text(if (searching) "Searching…" else "Find Music Servers")
        }
        if (searching) CircularProgressIndicator()
        if (error != null) Text(error)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(result?.servers ?: emptyList()) { server ->
                Card(Modifier.fillMaxWidth().clickable { onSelect(server) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(server.friendlyName, fontWeight = FontWeight.Bold)
                        if (server.manufacturer.isNotBlank()) Text(server.manufacturer, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterLoading(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(message, Modifier.padding(top = 12.dp))
        }
    }
}

private fun formatDuration(duration: String): String {
    if (duration.isBlank()) return ""
    val p = duration.substringBefore(".").split(":")
    if (p.size != 3) return duration
    val h = p[0].toIntOrNull() ?: 0
    val m = p[1].toIntOrNull() ?: 0
    val sec = p[2].toIntOrNull() ?: 0
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

