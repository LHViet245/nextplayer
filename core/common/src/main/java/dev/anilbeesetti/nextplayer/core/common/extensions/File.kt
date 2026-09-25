package dev.anilbeesetti.nextplayer.core.common.extensions

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import dev.anilbeesetti.nextplayer.core.common.subtitles.SubtitleSiblings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun File.getSubtitles(): List<File> = withContext(Dispatchers.IO) {
    val parentDir = this@getSubtitles.parentFile ?: return@withContext emptyList()
    val videoName = this@getSubtitles.name

    parentDir.listFiles()
        ?.filter { it.isFile && SubtitleSiblings.match(videoName, it.name) != null }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()
}

suspend fun File.getLocalSubtitles(
    context: Context,
    excludeSubsList: List<Uri> = emptyList(),
): List<Uri> = withContext(Dispatchers.Default) {
    val excludeSubsPathSet = excludeSubsList.mapNotNull { context.getPath(it) }.toSet()

    getSubtitles().mapNotNull { file ->
        if (file.path !in excludeSubsPathSet) {
            file.toUri()
        } else {
            null
        }
    }
}

fun String.getThumbnail(): File? {
    val filePathWithoutExtension = this.substringBeforeLast(".")
    val imageExtensions = listOf("png", "jpg", "jpeg")
    for (imageExtension in imageExtensions) {
        val file = File("$filePathWithoutExtension.$imageExtension")
        if (file.exists()) return file
    }
    return null
}

fun File.isSubtitle(): Boolean {
    val subtitleExtensions = listOf("srt", "ssa", "ass", "vtt", "ttml")
    return extension.lowercase() in subtitleExtensions
}

fun File.deleteFiles() {
    try {
        listFiles()?.onEach {
            it.delete()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

val File.prettyName: String
    get() = this.name.takeIf { this.path != Environment.getExternalStorageDirectory()?.path } ?: "Internal Storage"
