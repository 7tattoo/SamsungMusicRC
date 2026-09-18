package com.spotify.music.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.spotify.music.core.tags.CoverLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 从音频文件路径加载内嵌封面（带缓存），无封面显示占位 */
@Composable
fun AlbumArt(
    path: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable ((Modifier) -> Unit)? = null,
) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path == null) {
            bitmap = null
        } else {
            val bmp: Bitmap? = withContext(Dispatchers.IO) { CoverLoader.load(path) }
            bitmap = bmp?.asImageBitmap()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        if (placeholder != null) {
            placeholder(modifier)
        } else {
            Image(
                bitmap = defaultCoverBitmap(),
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private var cachedDefault: ImageBitmap? = null

private fun defaultCoverBitmap(): ImageBitmap {
    cachedDefault?.let { return it }
    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(android.graphics.Color.rgb(0xEB, 0xEB, 0xF0))
    cachedDefault = bmp.asImageBitmap()
    return cachedDefault!!
}
