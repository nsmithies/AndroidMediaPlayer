package com.studorium.androidmediaplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun DlnaAlbumArt(imageUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var bitmap by remember(imageUrl) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(imageUrl) { mutableStateOf(imageUrl.isNotBlank()) }
    var failed by remember(imageUrl) { mutableStateOf(false) }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isBlank()) {
            loading = false
            failed = true
            return@LaunchedEffect
        }

        loading = true
        failed = false

        val cached = withContext(Dispatchers.IO) { ArtworkCache.load(context, imageUrl) }
        if (cached != null) {
            bitmap = cached
            loading = false
            return@LaunchedEffect
        }

        val downloaded = loadDlnaBitmap(imageUrl)
        bitmap = downloaded
        loading = false
        failed = downloaded == null

        if (downloaded != null && AppSettingsStore(context).cacheArtwork) {
            withContext(Dispatchers.IO) { ArtworkCache.store(context, imageUrl, downloaded) }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Album artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            loading -> CircularProgressIndicator()
            failed -> Text("♫", style = MaterialTheme.typography.displayMedium)
        }
    }
}

private suspend fun loadDlnaBitmap(imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = URL(imageUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 4000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "image/*")
        connection.setRequestProperty("Connection", "close")
        if (connection.responseCode !in 200..299) return@withContext null
        connection.inputStream.use(BitmapFactory::decodeStream)
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}
