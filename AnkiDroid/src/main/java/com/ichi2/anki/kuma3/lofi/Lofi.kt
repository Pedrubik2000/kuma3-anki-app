// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kuma3.lofi

import android.animation.ValueAnimator
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ichi2.anki.AnkiDroidApp
import timber.log.Timber
import java.io.File
import java.net.URLDecoder

/**
 * The desktop "Lofi" add-on (anki-lofi) for kuma3: lofi music while you study, from the Chillhop
 * and Lofi Girl tracklists of lowfi (streamed), from tracks downloaded once for offline use, or
 * from a folder on the phone. It keeps playing in the background ([LofiService] shows the media
 * notification), and fades down while anything else plays (a card's [sound:] audio, its own
 * <audio>/<video>, TTS), like the add-on's ducking.
 *
 * The button is [LofiButton] (deck list and reviewer, Settings > kuma3 > Lofi button).
 */
object Lofi {
    enum class Source(
        val label: String,
    ) {
        CHILLHOP("Chillhop (stream)"),
        LOFIGIRL("Lofi Girl (stream)"),
        DOWNLOADED("Downloaded (offline)"),
        FOLDER("My folder"),
    }

    /** One playlist at a time: this many random tracks, then a new random set. */
    private const val PLAYLIST_SIZE = 50
    private const val DUCK = 0.2f // the add-on's duck_percent
    private const val DUCK_FADE_MS = 250L
    private const val UNDUCK_FADE_MS = 800L
    private const val UNDUCK_DELAY_MS = 500L
    private const val SKIPS_ON_ERROR = 3

    private val context: Context get() = AnkiDroidApp.instance
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("kuma3_lofi", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    var source: Source
        get() = Source.entries.getOrElse(prefs.getInt("source", 0)) { Source.CHILLHOP }
        set(value) {
            prefs.edit { putInt("source", value.ordinal) }
            reload()
        }

    /** 0–100, the player's own volume (under the phone's media volume). */
    var volume: Int
        get() = prefs.getInt("volume", 70)
        set(value) {
            prefs.edit { putInt("volume", value.coerceIn(0, 100)) }
            applyVolume()
        }

    var folder: Uri?
        get() = prefs.getString("folder", null)?.let(Uri::parse)
        set(value) {
            prefs.edit { putString("folder", value?.toString()) }
        }

    /** Listeners for the buttons (playing state, track). Called on the main thread. */
    val listeners = mutableListOf<() -> Unit>()

    private var playerOrNull: ExoPlayer? = null
    val player: ExoPlayer
        get() = playerOrNull ?: createPlayer().also { playerOrNull = it }

    val isPlaying: Boolean get() = playerOrNull?.isPlaying == true

    val trackTitle: String?
        get() = playerOrNull?.mediaMetadata?.title?.toString()

    fun toggle() {
        if (isPlaying) {
            player.pause()
        } else {
            play()
        }
    }

    fun play() {
        if (player.mediaItemCount == 0) reload()
        if (player.mediaItemCount == 0) return
        context.startService(Intent(context, LofiService::class.java))
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else reload(playAfter = true)
    }

    /** A new random playlist from the current source. */
    private fun reload(playAfter: Boolean = isPlaying) {
        val items = tracks(source).shuffled().take(PLAYLIST_SIZE)
        player.setMediaItems(items)
        player.prepare()
        if (playAfter && items.isNotEmpty()) play()
        notifyListeners()
    }

    /** All tracks of [source] (may be empty: nothing downloaded, no folder chosen). */
    fun tracks(source: Source): List<MediaItem> =
        when (source) {
            Source.CHILLHOP -> tracklist("chillhop", "Chillhop")
            Source.LOFIGIRL -> tracklist("lofigirl", "Lofi Girl")
            Source.DOWNLOADED ->
                downloadDir()
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "mp3" }
                    .map {
                        item(Uri.fromFile(it), it.nameWithoutExtension, "Downloaded")
                    }.toList()
            Source.FOLDER -> folderTracks()
        }

    /**
     * lowfi's tracklist format (assets/lofi/<name>.txt, from the add-on): the first line is the
     * base URL, then one track per line, `path` or `path!Title`.
     */
    private fun tracklist(
        name: String,
        artist: String,
    ): List<MediaItem> {
        val lines =
            context.assets
                .open("lofi/$name.txt")
                .bufferedReader()
                .readLines()
                .filter { it.isNotBlank() }
        val base = lines.first().trim()
        return lines.drop(1).map { line ->
            val path = line.substringBefore('!').trim()
            val title = line.substringAfter('!', "").ifBlank { titleFromPath(path) }
            item(Uri.parse(base + path), title, artist)
        }
    }

    private fun titleFromPath(path: String) =
        URLDecoder
            .decode(path.substringAfterLast('/'), "UTF-8")
            .substringBeforeLast('.')
            .replace(Regex("^\\d+\\s*"), "")

    private fun item(
        uri: Uri,
        title: String,
        artist: String,
    ) = MediaItem
        .Builder()
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setArtist(artist)
                .build(),
        ).build()

    // ------------------------------------------------------------------ player

    private fun createPlayer(): ExoPlayer =
        ExoPlayer.Builder(context).build().apply {
            // no audio focus: card audio must not pause the music, it ducks it (see [Ducking])
            setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,
            )
            setHandleAudioBecomingNoisy(true) // headphones out: pause
            repeatMode = Player.REPEAT_MODE_ALL
            addListener(
                object : Player.Listener {
                    private var errors = 0

                    override fun onEvents(
                        player: Player,
                        events: Player.Events,
                    ) = notifyListeners()

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) errors = 0
                        Ducking.watch(isPlaying)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Timber.w(error, "lofi: track failed")
                        // a dead link or no internet: try a few more, then stop
                        if (++errors <= SKIPS_ON_ERROR && hasNextMediaItem()) {
                            seekToNextMediaItem()
                            prepare()
                            play()
                        }
                    }
                },
            )
            volume = this@Lofi.volume / 100f
        }

    private var duckFactor = 1f

    private fun applyVolume() {
        playerOrNull?.volume = volume / 100f * duckFactor
    }

    private fun notifyListeners() = listeners.toList().forEach { it() }

    /** Fades the music under any other audio playing on the phone (this app's or another's). */
    private object Ducking {
        private var animator: ValueAnimator? = null
        private var callback: Any? = null // AudioManager.AudioPlaybackCallback (API 26+)
        private val unduck = Runnable { fade(1f, UNDUCK_FADE_MS) }

        fun watch(playing: Boolean) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val audio = context.getSystemService(AudioManager::class.java)
            if (playing && callback == null) {
                val cb =
                    object : AudioManager.AudioPlaybackCallback() {
                        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = onChanged(configs.size)
                    }
                audio.registerAudioPlaybackCallback(cb, main)
                callback = cb
                onChanged(audio.activePlaybackConfigurations.size)
            } else if (!playing && callback != null) {
                audio.unregisterAudioPlaybackCallback(callback as AudioManager.AudioPlaybackCallback)
                callback = null
                main.removeCallbacks(unduck)
                fade(1f, 0)
            }
        }

        /** [active] counts every playing stream, the music's own included. */
        private fun onChanged(active: Int) {
            main.removeCallbacks(unduck)
            if (active > 1) {
                fade(DUCK, DUCK_FADE_MS)
            } else if (duckFactor < 1f) {
                main.postDelayed(unduck, UNDUCK_DELAY_MS)
            }
        }

        private fun fade(
            to: Float,
            ms: Long,
        ) {
            animator?.cancel()
            animator =
                ValueAnimator.ofFloat(duckFactor, to).apply {
                    duration = ms
                    addUpdateListener {
                        duckFactor = it.animatedValue as Float
                        applyVolume()
                    }
                    start()
                }
        }
    }

    // ------------------------------------------------------------------ offline downloads

    /** App-specific storage: no permission needed, removed with the app. */
    private fun downloadDir() = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "lofi").apply { mkdirs() }

    fun downloadedCount() = downloadDir().walkTopDown().count { it.isFile && it.extension == "mp3" }

    /**
     * Queues [count] random tracks of [from] (not downloaded yet) in Android's download manager,
     * which shows its own progress notification and carries on in the background.
     */
    fun download(
        from: Source,
        count: Int,
    ): Int {
        val manager = context.getSystemService(DownloadManager::class.java)
        val dir = File(downloadDir(), from.name.lowercase()).apply { mkdirs() }
        val have = dir.list().orEmpty().toSet()
        val picks =
            tracks(from)
                .shuffled()
                .map { it to fileName(it) }
                .filter { (_, name) -> name !in have }
                .take(count)
        for ((item, name) in picks) {
            val request =
                DownloadManager
                    .Request(item.localConfiguration!!.uri)
                    .setTitle("kuma3 lofi: ${item.mediaMetadata.title}")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationUri(Uri.fromFile(File(dir, name)))
            manager.enqueue(request)
        }
        return picks.size
    }

    private fun fileName(item: MediaItem): String {
        val title =
            item.mediaMetadata.title
                .toString()
                .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
                .take(80)
        return "${title}_${item.localConfiguration!!.uri.toString().hashCode().toUInt()}.mp3"
    }

    fun deleteDownloads() {
        downloadDir().deleteRecursively()
        if (source == Source.DOWNLOADED) reload()
    }

    // ------------------------------------------------------------------ own folder

    /** Audio files in the chosen folder and its subfolders (Storage Access Framework). */
    private fun folderTracks(): List<MediaItem> {
        val tree = folder ?: return emptyList()
        val out = mutableListOf<MediaItem>()
        val resolver = context.contentResolver

        fun walk(docId: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val columns =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1)
                    val mime = c.getString(2).orEmpty()
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> walk(id)
                        mime.startsWith("audio/") ->
                            out += item(DocumentsContract.buildDocumentUriUsingTree(tree, id), name.substringBeforeLast('.'), "My folder")
                    }
                }
            }
        }
        try {
            walk(DocumentsContract.getTreeDocumentId(tree))
        } catch (e: Exception) {
            Timber.w(e, "lofi: folder unreadable")
        }
        return out
    }

    /** After choosing a folder: keep access across restarts, and play from it. */
    fun useFolder(tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        folder = tree
        source = Source.FOLDER
    }

    /** The service is gone (swiped away while paused): free the player. */
    fun release() {
        Ducking.watch(false)
        playerOrNull?.release()
        playerOrNull = null
        notifyListeners()
    }
}
