import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import java.io.File

private fun generateStableWaveformSamples(seedKey: String, size: Int = 70): List<Float> {
    val seed = seedKey.hashCode().toLong()
    val rnd = kotlin.random.Random(seed)
    return List(size) { 0.2f + rnd.nextFloat() * 0.8f }
}

@OptIn(UnstableApi::class)
@Composable
fun VoiceMessagePlayer(
    message: ChatMessage,
    messageId: String,
    currentlyPlayingId: String?,
    onStartPlayback: (String) -> Unit,
    onStopPlayback: (String) -> Unit,
    onHeard: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    iconColor: Color,
    onPlaybackEnded: () -> Unit = {}
) {
    val context = LocalContext.current
    val updatedOnHeard by rememberUpdatedState(onHeard)
    val updatedOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)

    val sourceUri = remember(message.localPathToAudioFile, message.audioUrl) {
        when {
            !message.localPathToAudioFile.isNullOrEmpty() -> {
                val file = File(message.localPathToAudioFile)
                if (file.exists()) Uri.fromFile(file) else null
            }
            !message.audioUrl.isNullOrEmpty() -> Uri.parse(message.audioUrl)
            else -> null
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var shouldResumeAfterSeek by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }

    val renderersFactory = remember(context) {
        androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            )
    }

    val player = remember(sourceUri, messageId) {
        if (sourceUri == null) {
            hasError = true
            null
        } else {
            try {
                androidx.media3.exoplayer.ExoPlayer.Builder(context)
                    .setRenderersFactory(renderersFactory)
                    .build().apply {
                        setAudioAttributes(
                            androidx.media3.common.AudioAttributes.Builder()
                                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
                                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                                .build(),
                            true
                        )
                        repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
                        
                        // Determine MIME type based on file extension or URL
                        val mimeType = when {
                            sourceUri.toString().endsWith(".opus", ignoreCase = true) -> 
                                androidx.media3.common.MimeTypes.AUDIO_OGG
                            sourceUri.toString().endsWith(".ogg", ignoreCase = true) -> 
                                androidx.media3.common.MimeTypes.AUDIO_OGG
                            sourceUri.toString().endsWith(".m4a", ignoreCase = true) -> 
                                androidx.media3.common.MimeTypes.AUDIO_MP4
                            sourceUri.toString().endsWith(".aac", ignoreCase = true) -> 
                                androidx.media3.common.MimeTypes.AUDIO_AAC
                            sourceUri.toString().endsWith(".mp3", ignoreCase = true) -> 
                                androidx.media3.common.MimeTypes.AUDIO_MPEG
                            else -> null // Let ExoPlayer detect
                        }
                        
                        val mediaItem = if (mimeType != null) {
                            androidx.media3.common.MediaItem.Builder()
                                .setUri(sourceUri)
                                .setMimeType(mimeType)
                                .build()
                        } else {
                            androidx.media3.common.MediaItem.fromUri(sourceUri)
                        }
                        
                        setMediaItem(mediaItem)
                        prepare()
                    }
            } catch (e: Exception) {
                Log.e("VoiceMessagePlayer", "Failed to create player", e)
                hasError = true
                null
            }
        }
    }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}

        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    androidx.media3.common.Player.STATE_READY -> {
                        val d = player.duration
                        if (d != androidx.media3.common.C.TIME_UNSET && d > 0) {
                            durationMs = d
                            hasError = false
                        }
                    }
                    androidx.media3.common.Player.STATE_ENDED -> {
                        player.pause()
                        player.playWhenReady = false
                        isPlaying = false
                        positionMs = 0L
                        player.seekTo(0L)
                        updatedOnPlaybackEnded()
                        updatedOnHeard(messageId)
                        onStopPlayback(messageId)
                    }
                    androidx.media3.common.Player.STATE_IDLE -> {
                        // Player is idle, might indicate an error
                        if (player.playerError != null) {
                            hasError = true
                        }
                    }
                    androidx.media3.common.Player.STATE_BUFFERING -> {
                        // Player is buffering
                    }
                }
            }

            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("VoiceMessagePlayer", "Playback error: ${error.message}", error)
                hasError = true
                isPlaying = false
                
                // Try to recover by resetting the player
                try {
                    player.stop()
                    player.clearMediaItems()
                } catch (e: Exception) {
                    Log.e("VoiceMessagePlayer", "Failed to reset player", e)
                }
            }
        }

        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Update position periodically
    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            if (isPlaying && !isScrubbing) {
                val pos = player.currentPosition
                if (pos >= 0) {
                    positionMs = pos
                }
            }
            delay(50)
        }
    }

    // Handle playback coordination
    LaunchedEffect(currentlyPlayingId, messageId, player) {
        if (player == null) return@LaunchedEffect
        if (currentlyPlayingId != messageId) {
            if (player.isPlaying) {
                player.pause()
                player.seekTo(0L)
                positionMs = 0L
            }
            isPlaying = false
        }
    }

    Row(
        modifier = modifier
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val enabled = player != null && !hasError && sourceUri != null
        
        IconButton(
            onClick = {
                if (player == null || hasError) return@IconButton
                if (currentlyPlayingId != messageId) {
                    onStartPlayback(messageId)
                    player.play()
                } else {
                    if (player.isPlaying) player.pause() else player.play()
                }
            },
            enabled = enabled
        ) {
            Icon(
                painter = painterResource(
                    when {
                        hasError -> R.drawable.ic_error
                        isPlaying && currentlyPlayingId == messageId -> R.drawable.ic_pause
                        else -> R.drawable.ic_play_arrow
                    }
                ),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (hasError) Color.Red else iconColor.copy(alpha = if (enabled) 1f else 0.4f)
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .weight(0.6f)
                    .pointerInput(durationMs, player, messageId, enabled) {
                        if (player == null || durationMs == 0L || !enabled) return@pointerInput
                        detectDragGestures(
                            onDragStart = { offset ->
                                isScrubbing = true
                                shouldResumeAfterSeek = player.isPlaying
                                if (player.isPlaying) player.pause()
                                val frac = offset.x.coerceIn(0f, size.width.toFloat()) / size.width
                                val newPos = (frac * durationMs).toLong()
                                player.seekTo(newPos)
                                positionMs = newPos
                            },
                            onDrag = { change, _ ->
                                val x = change.position.x.coerceIn(0f, size.width.toFloat())
                                val frac = x / size.width
                                val newPos = (frac * durationMs).toLong()
                                player.seekTo(newPos)
                                positionMs = newPos
                                if (change.positionChange() != Offset.Zero) change.consume()
                            },
                            onDragEnd = {
                                isScrubbing = false
                                if (shouldResumeAfterSeek) player.play()
                            },
                            onDragCancel = {
                                isScrubbing = false
                                if (shouldResumeAfterSeek) player.play()
                            }
                        )
                    }
            ) {
                val waveformKey = message.tempId
                    ?: message.messageId
                    ?: message.audioUrl
                    ?: message.localPathToAudioFile
                    ?: messageId

                val stableWaveformSamples = remember(waveformKey) {
                    generateStableWaveformSamples(waveformKey)
                }

                WaveformView(
                    samples = stableWaveformSamples,
                    progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    modifier = Modifier.fillMaxSize(),
                    playedColor = if (hasError) Color.Red else iconColor,
                    enabled = enabled && !hasError
                )
            }

            val timeText = when {
                hasError -> "Error"
                !isPlaying && !isScrubbing && positionMs == 0L && durationMs > 0L -> formatTime(durationMs)
                durationMs > 0 -> "${formatTime(positionMs)} / ${formatTime(durationMs)}"
                else -> formatTime(positionMs)
            }
            
            Text(
                text = timeText,
                fontFamily = PoppinsLight,
                style = MaterialTheme.typography.body2,
                color = if (hasError) Color.Red else Color.Unspecified
            )
        }

        if (message.isSender) {
            when (message.messageStatus) {
                MessageStatus.SENDING -> Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_clock),
                    contentDescription = null,
                    tint = Color.Gray
                )
                MessageStatus.SENT -> Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_single_check),
                    contentDescription = null,
                    tint = Color.Gray
                )
                MessageStatus.DELIVERED -> Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_done),
                    contentDescription = null,
                    tint = Color.Gray
                )
                MessageStatus.READ -> Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_done),
                    contentDescription = null,
                    tint = Color.Blue
                )
                MessageStatus.FAILED -> Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_error),
                    contentDescription = null,
                    tint = Color.Red
                )
            }
        }
    }
}

@Composable
fun WaveformView(
    samples: List<Float>,
    progress: Float,
    modifier: Modifier = Modifier,
    lineColor: Color = Color.Gray,
    playedColor: Color = MaterialTheme.colors.primary,
    enabled: Boolean = true
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val barWidth = w / samples.size
        samples.forEachIndexed { i, amp ->
            val x = i * barWidth
            val barHeight = amp * h
            val y1 = (h - barHeight) / 2
            val y2 = y1 + barHeight
            val frac = i / samples.lastIndex.toFloat()
            val color = when {
                !enabled -> lineColor.copy(alpha = 0.3f)
                frac <= progress -> playedColor
                else -> lineColor
            }
            drawLine(
                color = color,
                start = Offset(x + barWidth / 2, y1),
                end = Offset(x + barWidth / 2, y2),
                strokeWidth = barWidth * 0.6f,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSecs = ms / 1000
    val m = totalSecs / 60
    val s = totalSecs % 60
    return "%d:%02d".format(m, s)
}