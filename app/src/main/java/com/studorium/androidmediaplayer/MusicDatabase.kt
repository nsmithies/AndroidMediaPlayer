package com.studorium.androidmediaplayer

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class MusicTrack(
    val databaseId: Long = 0,
    val dlnaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val trackNumber: Int?,
    val year: String,
    val duration: String,
    val albumArtUrl: String,
    val resourceUrl: String,
    val mimeType: String,
    val serverName: String
)

data class MusicAlbum(
    val title: String,
    val artist: String,
    val year: String,
    val genre: String,
    val albumArtUrl: String,
    val trackCount: Int
)

data class MusicArtist(
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val artworkUrl: String
)

data class MusicPlaylist(
    val id: Long,
    val name: String,
    val trackCount: Int
)

class MusicDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION
) {
    override fun onCreate(database: SQLiteDatabase) {
        createTracks(database)
        createPlaylists(database)
    }

    private fun createTracks(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dlna_id TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL DEFAULT '',
                album TEXT NOT NULL DEFAULT '',
                genre TEXT NOT NULL DEFAULT '',
                track_number INTEGER,
                year TEXT NOT NULL DEFAULT '',
                duration TEXT NOT NULL DEFAULT '',
                album_art_url TEXT NOT NULL DEFAULT '',
                resource_url TEXT NOT NULL,
                mime_type TEXT NOT NULL DEFAULT '',
                server_name TEXT NOT NULL DEFAULT ''
            )""".trimIndent()
        )
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tracks_resource_url ON tracks(resource_url)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_album ON tracks(album)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_artist ON tracks(artist)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_genre ON tracks(genre)")
    }

    private fun createPlaylists(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS playlist_tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlist_id INTEGER NOT NULL,
                resource_url TEXT NOT NULL,
                position INTEGER NOT NULL,
                FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE
            )""".trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_playlist ON playlist_tracks(playlist_id)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createPlaylists(database)
    }

    fun clearTracks() { writableDatabase.delete("tracks", null, null) }

    fun insertTrack(track: MusicTrack): Long {
        if (track.resourceUrl.isBlank()) return -1L
        val values = ContentValues().apply {
            put("dlna_id", track.dlnaId); put("title", track.title); put("artist", track.artist)
            put("album", track.album); put("genre", track.genre)
            if (track.trackNumber != null) put("track_number", track.trackNumber) else putNull("track_number")
            put("year", track.year); put("duration", track.duration); put("album_art_url", track.albumArtUrl)
            put("resource_url", track.resourceUrl); put("mime_type", track.mimeType); put("server_name", track.serverName)
        }
        val db = writableDatabase
        val updated = db.update("tracks", values, "resource_url = ?", arrayOf(track.resourceUrl))
        return if (updated > 0) {
            db.rawQuery("SELECT id FROM tracks WHERE resource_url = ?", arrayOf(track.resourceUrl)).use {
                if (it.moveToFirst()) it.getLong(0) else -1L
            }
        } else db.insert("tracks", null, values)
    }

    fun getTrackCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM tracks", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun getAllTracks(): List<MusicTrack> = queryTracks(
        """SELECT id,dlna_id,title,artist,album,genre,track_number,year,duration,album_art_url,resource_url,mime_type,server_name
           FROM tracks ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE,
           CASE WHEN track_number IS NULL THEN 1 ELSE 0 END, track_number, title COLLATE NOCASE""",
        emptyArray()
    )

    fun getAlbums(): List<MusicAlbum> {
        val result = mutableListOf<MusicAlbum>()
        readableDatabase.rawQuery(
            """SELECT CASE WHEN TRIM(album)='' THEN 'Unknown Release' ELSE album END,
                      CASE WHEN TRIM(artist)='' THEN 'Unknown Artist' ELSE artist END,
                      MAX(year), MAX(genre),
                      MAX(CASE WHEN TRIM(album_art_url)!='' THEN album_art_url ELSE '' END),
                      COUNT(*)
               FROM tracks GROUP BY LOWER(TRIM(album)), LOWER(TRIM(artist))
               ORDER BY 1 COLLATE NOCASE, 2 COLLATE NOCASE""", null
        ).use { c ->
            while (c.moveToNext()) result += MusicAlbum(c.getString(0), c.getString(1), c.getString(2) ?: "",
                c.getString(3) ?: "", c.getString(4) ?: "", c.getInt(5))
        }
        return result
    }

    fun getArtists(): List<MusicArtist> {
        val result = mutableListOf<MusicArtist>()
        readableDatabase.rawQuery(
            """SELECT CASE WHEN TRIM(artist)='' THEN 'Unknown Artist' ELSE artist END,
                      COUNT(DISTINCT LOWER(TRIM(album))), COUNT(*),
                      MAX(CASE WHEN TRIM(album_art_url)!='' THEN album_art_url ELSE '' END)
               FROM tracks GROUP BY LOWER(TRIM(artist)) ORDER BY 1 COLLATE NOCASE""", null
        ).use { c ->
            while (c.moveToNext()) result += MusicArtist(c.getString(0), c.getInt(1), c.getInt(2), c.getString(3) ?: "")
        }
        return result
    }

    fun getTracksForAlbum(album: MusicAlbum): List<MusicTrack> {
        val a = if (album.title == "Unknown Release") "" else album.title
        val ar = if (album.artist == "Unknown Artist") "" else album.artist
        return queryTracks(
            """SELECT id,dlna_id,title,artist,album,genre,track_number,year,duration,album_art_url,resource_url,mime_type,server_name
               FROM tracks WHERE LOWER(TRIM(album))=LOWER(TRIM(?)) AND LOWER(TRIM(artist))=LOWER(TRIM(?))
               ORDER BY CASE WHEN track_number IS NULL THEN 1 ELSE 0 END, track_number, title COLLATE NOCASE""",
            arrayOf(a, ar)
        )
    }

    fun getRandomTracks(count: Int = 100): List<MusicTrack> = queryTracks(
        """SELECT id,dlna_id,title,artist,album,genre,track_number,year,duration,album_art_url,resource_url,mime_type,server_name
           FROM tracks ORDER BY RANDOM() LIMIT ?""", arrayOf(count.toString())
    )

    fun createPlaylist(name: String): Long {
        val clean = name.trim()
        if (clean.isBlank()) return -1L
        val values = ContentValues().apply {
            put("name", clean)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("playlists", null, values)
    }

    fun getPlaylists(): List<MusicPlaylist> {
        val result = mutableListOf<MusicPlaylist>()
        readableDatabase.rawQuery(
            """SELECT p.id, p.name, COUNT(pt.id)
               FROM playlists p
               LEFT JOIN playlist_tracks pt ON pt.playlist_id = p.id
               GROUP BY p.id, p.name
               ORDER BY p.name COLLATE NOCASE""", null
        ).use { c ->
            while (c.moveToNext()) result += MusicPlaylist(c.getLong(0), c.getString(1), c.getInt(2))
        }
        return result
    }

    fun addTracksToPlaylist(playlistId: Long, tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            var position = db.rawQuery(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = ?",
                arrayOf(playlistId.toString())
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

            tracks.forEach { track ->
                if (track.resourceUrl.isNotBlank()) {
                    val values = ContentValues().apply {
                        put("playlist_id", playlistId)
                        put("resource_url", track.resourceUrl)
                        put("position", position++)
                    }
                    db.insert("playlist_tracks", null, values)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getPlaylistTracks(playlistId: Long): List<MusicTrack> = queryTracks(
        """SELECT t.id,t.dlna_id,t.title,t.artist,t.album,t.genre,t.track_number,t.year,t.duration,
                  t.album_art_url,t.resource_url,t.mime_type,t.server_name
           FROM playlist_tracks pt
           JOIN tracks t ON t.resource_url = pt.resource_url
           WHERE pt.playlist_id = ?
           ORDER BY pt.position""",
        arrayOf(playlistId.toString())
    )

    private fun queryTracks(sql: String, args: Array<String>): List<MusicTrack> {
        val result = mutableListOf<MusicTrack>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) result += c.toMusicTrack() }
        return result
    }

    private fun Cursor.toMusicTrack() = MusicTrack(
        getLong(0), getString(1), getString(2), getString(3), getString(4), getString(5),
        if (isNull(6)) null else getInt(6), getString(7), getString(8), getString(9),
        getString(10), getString(11), getString(12)
    )

    companion object {
        private const val DATABASE_NAME = "music_library.db"
        private const val DATABASE_VERSION = 2
    }
}
