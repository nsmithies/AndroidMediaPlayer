package com.studorium.androidmediaplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MusicScanProgress(
    val foldersScanned: Int,
    val tracksFound: Int,
    val currentFolder: String
)

data class MusicScanResult(
    val foldersScanned: Int,
    val tracksFound: Int,
    val foldersSkipped: Int
)

class MusicLibraryScanner(
    private val database: MusicDatabase
) {

    suspend fun scan(
        server: DlnaServer,
        onProgress: suspend (MusicScanProgress) -> Unit = {}
    ): MusicScanResult = withContext(Dispatchers.IO) {

        val browser = DlnaBrowser()

        var foldersScanned = 0
        var tracksFound = 0
        var foldersSkipped = 0

        suspend fun report(folder: String) {
            onProgress(
                MusicScanProgress(
                    foldersScanned = foldersScanned,
                    tracksFound = tracksFound,
                    currentFolder = folder
                )
            )
        }

        suspend fun browseFolder(
            objectId: String,
            folderName: String
        ): DlnaBrowseResult? {
            foldersScanned++
            report(folderName)

            return try {
                browser.browse(
                    server = server,
                    objectId = objectId
                )
            } catch (_: Exception) {
                null
            }
        }

        suspend fun storeTracks(
            items: List<DlnaMediaObject>,
            folderName: String
        ) {
            for (item in items) {
                if (!item.isAudioTrack) {
                    continue
                }

                if (item.resourceUrl.isBlank()) {
                    continue
                }

                database.insertTrack(
                    MusicTrack(
                        dlnaId = item.id,
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        genre = item.genre,
                        trackNumber = item.trackNumber,
                        year = item.year,
                        duration = item.duration,
                        albumArtUrl = item.albumArtUrl,
                        resourceUrl = item.resourceUrl,
                        mimeType = item.mimeType,
                        serverName = server.friendlyName
                    )
                )

                tracksFound++

                if (tracksFound % PROGRESS_TRACK_INTERVAL == 0) {
                    report(folderName)
                }
            }
        }

        /*
         * Start clean only once we know the selected server can actually
         * answer a Browse request. This prevents an offline server from
         * immediately destroying the user's existing local catalogue.
         */
        val root = browseFolder(
            objectId = "0",
            folderName = server.friendlyName
        ) ?: throw Exception(
            "The music server could not be reached."
        )

        val rootContainers =
            root.items.filter { it.isContainer }

        val musicRoot =
            chooseMusicRoot(rootContainers)

        val firstMusicResult: DlnaBrowseResult
        val firstMusicName: String
        val firstMusicObjectId: String

        if (musicRoot != null) {
            firstMusicName = musicRoot.title
            firstMusicObjectId = musicRoot.id

            firstMusicResult =
                browseFolder(
                    objectId = musicRoot.id,
                    folderName = musicRoot.title
                ) ?: throw Exception(
                    "The server's music library could not be opened."
                )
        } else {
            firstMusicName = server.friendlyName
            firstMusicObjectId = "0"
            firstMusicResult = root
        }

        /*
         * Prefer a single flat/canonical track branch. Twonky commonly
         * exposes the same library simultaneously as Albums, Artists,
         * Genres, Folders, etc. Crawling every one of those branches can
         * multiply a 40,000-track library into thousands of unnecessary
         * network requests.
         */
        val canonicalTrackBranch =
            chooseCanonicalTrackBranch(
                firstMusicResult.items.filter { it.isContainer }
            )

        database.clearTracks()
        tracksFound = 0

        if (canonicalTrackBranch != null) {
            val result =
                browseFolder(
                    objectId = canonicalTrackBranch.id,
                    folderName = canonicalTrackBranch.title
                ) ?: throw Exception(
                    "The server's song list could not be opened."
                )

            storeTracks(
                items = result.items,
                folderName = canonicalTrackBranch.title
            )

            /*
             * Some servers label a branch "Songs" or "All Music" but still
             * divide it into subfolders. If so, recurse only inside this
             * chosen canonical branch rather than across all virtual views.
             */
            val childContainers =
                result.items.filter { it.isContainer }

            if (childContainers.isNotEmpty()) {
                val visited = mutableSetOf<String>()
                visited.add(canonicalTrackBranch.id)

                suspend fun scanCanonicalSubtree(
                    objectId: String,
                    folderName: String,
                    depth: Int
                ) {
                    if (depth > MAX_CANONICAL_DEPTH) {
                        return
                    }

                    if (!visited.add(objectId)) {
                        return
                    }

                    if (foldersScanned >= MAX_FOLDERS) {
                        throw Exception(
                            "Scan stopped after $MAX_FOLDERS folders. " +
                                "The server appears to contain a recursive or unusually large folder tree."
                        )
                    }

                    val childResult =
                        browseFolder(
                            objectId = objectId,
                            folderName = folderName
                        ) ?: return

                    storeTracks(
                        items = childResult.items,
                        folderName = folderName
                    )

                    for (child in childResult.items) {
                        if (
                            child.isContainer &&
                            !shouldSkipFolder(child.title)
                        ) {
                            scanCanonicalSubtree(
                                objectId = child.id,
                                folderName = child.title,
                                depth = depth + 1
                            )
                        }
                    }
                }

                for (child in childContainers) {
                    if (!shouldSkipFolder(child.title)) {
                        scanCanonicalSubtree(
                            objectId = child.id,
                            folderName = child.title,
                            depth = 1
                        )
                    } else {
                        foldersSkipped++
                    }
                }
            }
        } else {
            /*
             * No obvious flat song branch exists. Fall back to one preferred
             * physical/folder view rather than Artists + Albums + Genres.
             */
            val preferredFolderBranch =
                chooseFolderBranch(
                    firstMusicResult.items.filter { it.isContainer }
                )

            val scanStartId =
                preferredFolderBranch?.id ?: firstMusicObjectId

            val scanStartName =
                preferredFolderBranch?.title ?: firstMusicName

            val scanStartResult =
                if (preferredFolderBranch != null) {
                    browseFolder(
                        objectId = preferredFolderBranch.id,
                        folderName = preferredFolderBranch.title
                    )
                } else {
                    firstMusicResult
                }

            if (scanStartResult == null) {
                throw Exception(
                    "The music folder could not be opened."
                )
            }

            val visited = mutableSetOf<String>()
            visited.add(scanStartId)

            storeTracks(
                items = scanStartResult.items,
                folderName = scanStartName
            )

            suspend fun scanFolderTree(
                objectId: String,
                folderName: String,
                depth: Int
            ) {
                if (depth > MAX_FALLBACK_DEPTH) {
                    return
                }

                if (!visited.add(objectId)) {
                    return
                }

                if (foldersScanned >= MAX_FOLDERS) {
                    throw Exception(
                        "Scan stopped after $MAX_FOLDERS folders. " +
                            "The server appears to contain a recursive or unusually large folder tree."
                    )
                }

                val result =
                    browseFolder(
                        objectId = objectId,
                        folderName = folderName
                    ) ?: return

                storeTracks(
                    items = result.items,
                    folderName = folderName
                )

                for (child in result.items) {
                    if (!child.isContainer) {
                        continue
                    }

                    if (shouldSkipFolder(child.title)) {
                        foldersSkipped++
                        continue
                    }

                    if (isRedundantVirtualView(child.title)) {
                        foldersSkipped++
                        continue
                    }

                    scanFolderTree(
                        objectId = child.id,
                        folderName = child.title,
                        depth = depth + 1
                    )
                }
            }

            for (child in scanStartResult.items) {
                if (!child.isContainer) {
                    continue
                }

                if (shouldSkipFolder(child.title)) {
                    foldersSkipped++
                    continue
                }

                if (
                    preferredFolderBranch == null &&
                    isRedundantVirtualView(child.title)
                ) {
                    foldersSkipped++
                    continue
                }

                scanFolderTree(
                    objectId = child.id,
                    folderName = child.title,
                    depth = 1
                )
            }
        }

        val finalTrackCount =
            database.getTrackCount()

        onProgress(
            MusicScanProgress(
                foldersScanned = foldersScanned,
                tracksFound = finalTrackCount,
                currentFolder = "Complete"
            )
        )

        MusicScanResult(
            foldersScanned = foldersScanned,
            tracksFound = finalTrackCount,
            foldersSkipped = foldersSkipped
        )
    }

    private fun chooseMusicRoot(
        containers: List<DlnaMediaObject>
    ): DlnaMediaObject? {
        val rankedNames =
            listOf(
                "music",
                "audio",
                "my music",
                "music library"
            )

        return findByRankedName(
            containers = containers,
            rankedNames = rankedNames
        )
    }

    private fun chooseCanonicalTrackBranch(
        containers: List<DlnaMediaObject>
    ): DlnaMediaObject? {
        val rankedNames =
            listOf(
                "all music",
                "all songs",
                "all tracks",
                "songs",
                "tracks",
                "music tracks",
                "audio tracks"
            )

        return findByRankedName(
            containers = containers,
            rankedNames = rankedNames
        )
    }

    private fun chooseFolderBranch(
        containers: List<DlnaMediaObject>
    ): DlnaMediaObject? {
        val rankedNames =
            listOf(
                "folders",
                "folder",
                "music folders",
                "music folder",
                "directories",
                "directory"
            )

        return findByRankedName(
            containers = containers,
            rankedNames = rankedNames
        )
    }

    private fun findByRankedName(
        containers: List<DlnaMediaObject>,
        rankedNames: List<String>
    ): DlnaMediaObject? {
        for (target in rankedNames) {
            val match =
                containers.firstOrNull {
                    normalizeName(it.title) == target
                }

            if (match != null) {
                return match
            }
        }

        return null
    }

    private fun isRedundantVirtualView(
        folderName: String
    ): Boolean {
        val name =
            normalizeName(folderName)

        val redundantNames =
            setOf(
                "artists",
                "artist",
                "albums",
                "album",
                "genres",
                "genre",
                "composers",
                "composer",
                "years",
                "year",
                "decades",
                "ratings",
                "rating",
                "recently added",
                "recently played",
                "most played",
                "playlists",
                "playlist"
            )

        return name in redundantNames
    }

    private fun shouldSkipFolder(
        folderName: String
    ): Boolean {
        val normalized =
            normalizeName(folderName)

        if (normalized.isBlank()) {
            return false
        }

        val words =
            normalized
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .toSet()

        val blockedWords =
            setOf(
                "photo",
                "photos",
                "photograph",
                "photographs",
                "picture",
                "pictures",
                "image",
                "images",
                "video",
                "videos",
                "movie",
                "movies",
                "film",
                "films"
            )

        return words.any {
            it in blockedWords
        }
    }

    private fun normalizeName(
        value: String
    ): String {
        return value
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    companion object {
        private const val PROGRESS_TRACK_INTERVAL = 250
        private const val MAX_CANONICAL_DEPTH = 10
        private const val MAX_FALLBACK_DEPTH = 30
        private const val MAX_FOLDERS = 10_000
    }
}
