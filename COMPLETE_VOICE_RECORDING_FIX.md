# Complete Voice Recording Fix - Implementation Guide

## Overview
Your voice recording is not working because:
1. You're using the wrong library (vorbis-java-core) for Opus encoding
2. The Opus files aren't being created with proper OGG container format
3. ExoPlayer can't play the malformed audio files

## Solution
I'm providing two solutions:
1. **Recommended**: Use Android's built-in MediaRecorder with AAC format (simpler, more reliable)
2. **Alternative**: Fixed Opus implementation with proper OGG container

## Step-by-Step Implementation Instructions

### Step 1: Create the VoiceRecorder Interface
Create a new file `VoiceRecorder.kt` in your project:

```kotlin
import android.content.Context
import java.io.File

/**
 * Interface for voice recording implementations.
 */
interface VoiceRecorder {
    /**
     * Start recording audio.
     * @param context The Android context
     * @return true if recording started successfully, false otherwise
     */
    fun startRecording(context: Context): Boolean
    
    /**
     * Pause the current recording (if supported).
     */
    fun pauseRecording()
    
    /**
     * Resume a paused recording (if supported).
     */
    fun resumeRecording()
    
    /**
     * Stop recording and return the recorded file.
     * @return The recorded audio file, or null if recording failed
     */
    fun stopRecording(): File?
    
    /**
     * Cancel the current recording and delete any temporary files.
     */
    fun cancelRecording()
}
```

### Step 2: Implement the AAC Recorder (RECOMMENDED)
Create a new file `AACMediaRecorderVoiceRecorder.kt`:

```kotlin
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/**
 * Simple and reliable AAC audio recorder using MediaRecorder.
 * This is a more straightforward approach that works reliably on all Android devices.
 */
class AACMediaRecorderVoiceRecorder : VoiceRecorder {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isPaused = false

    override fun startRecording(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return try {
            // Create output file with .m4a extension for AAC
            outputFile = File.createTempFile("voice_", ".m4a", context.cacheDir)

            // Create and configure MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                // Use VOICE_RECOGNITION for better voice quality
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                
                // Use AAC format in MPEG4 container
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                
                // Set audio quality parameters
                setAudioEncodingBitRate(128_000) // 128 kbps for good quality
                setAudioSamplingRate(44_100) // Standard sample rate
                setAudioChannels(1) // Mono for voice
                
                // Set output file
                setOutputFile(outputFile!!.absolutePath)
                
                // Prepare and start
                prepare()
                start()
            }
            
            isPaused = false
            true
        } catch (e: IOException) {
            e.printStackTrace()
            cleanupRecorder()
            false
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            cleanupRecorder()
            false
        }
    }

    override fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.pause()
                isPaused = true
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }
    }

    override fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isPaused) {
            try {
                mediaRecorder?.resume()
                isPaused = false
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }
    }

    override fun stopRecording(): File? {
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            val file = outputFile
            outputFile = null
            file
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            cleanupRecorder()
            null
        } catch (e: RuntimeException) {
            e.printStackTrace()
            cleanupRecorder()
            null
        }
    }

    override fun cancelRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Ignore exceptions during cancellation
        }
        mediaRecorder = null
        
        // Delete the file
        outputFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        outputFile = null
    }

    private fun cleanupRecorder() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Ignore
        }
        mediaRecorder = null
        
        outputFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        outputFile = null
    }
}
```

### Step 3: Update Your ViewModel
Replace your `SharedChatsScreenViewModel` constructor and recording methods with this updated version:

```kotlin
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import team.collaboration.birge.presentation.models.Chat
import team.collaboration.birge.presentation.models.ChatMessage
import team.collaboration.birge.presentation.models.MessageStatus
import team.collaboration.birge.presentation.util.currentUserDetails
import team.collaboration.birge.presentation.util.currentUserId
import team.collaboration.birge.presentation.util.firebaseDatabase
import team.collaboration.birge.presentation.util.getGroupChatsFromFirebase
import team.collaboration.birge.presentation.util.storageReference
import java.util.Date
import java.util.UUID

data class ScrollPosition(val index: Int, val offset: Int)

private const val TAG = "SharedChatsScreenViewModel"

class SharedChatsScreenViewModel(
    recorder: VoiceRecorder? = null
) : ViewModel() {
    
    // Use AAC recorder by default as it's more reliable
    val recorder: VoiceRecorder = recorder ?: AACMediaRecorderVoiceRecorder()

    // ... [Keep all your existing properties and methods the same until the recording methods] ...

    // ----- Voice recording methods (UPDATED) -----

    fun startRecording(context: Context): Boolean {
        return try {
            recorder.startRecording(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            false
        }
    }
    
    fun pauseRecording() {
        try {
            recorder.pauseRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause recording", e)
        }
    }
    
    fun resumeRecording() {
        try {
            recorder.resumeRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume recording", e)
        }
    }
    
    fun cancelRecording() {
        try {
            recorder.cancelRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel recording", e)
        }
    }

    fun stopRecordingAndUpload(groupId: String) {
        val file = try {
            recorder.stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            null
        }
        
        if (file == null || !file.exists()) {
            Log.e(TAG, "No recording file available")
            return
        }
        
        // Check file size
        if (file.length() == 0L) {
            Log.e(TAG, "Recording file is empty")
            file.delete()
            return
        }
        
        val tempId = UUID.randomUUID().toString()

        val optimisticMessage = ChatMessage(
            messageId = null,
            senderId = currentUserId(),
            senderName = getUserFullNameSync(),
            messageText = null,
            audioUrl = null,
            tempId = tempId,
            timestamp = nowServerAlignedTimestamp(),
            messageStatus = MessageStatus.SENDING,
            isSender = true,
            localPathToAudioFile = file.absolutePath,
            insertionOrder = ++messageOrderCounter,
            localCreatedAtMs = System.currentTimeMillis()
        )

        // Add to the end of the list
        _messages.update { it + optimisticMessage }

        val uri = Uri.fromFile(file)
        // Use a more descriptive filename with proper extension
        val extension = when {
            file.name.endsWith(".m4a") -> ".m4a"
            file.name.endsWith(".opus") -> ".opus"
            file.name.endsWith(".ogg") -> ".ogg"
            else -> ".m4a"
        }
        val fileName = "voice_${System.currentTimeMillis()}_${UUID.randomUUID()}$extension"
        val storageRef = storageReference().child("audio_messages/$fileName")

        storageRef.putFile(uri)
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    // Update the local optimistic row with the real URL but keep SENDING
                    _messages.update { msgs ->
                        msgs.map { msg ->
                            if (msg.tempId == tempId) {
                                msg.copy(
                                    audioUrl = downloadUri.toString(),
                                    localPathToAudioFile = null
                                )
                            } else msg
                        }
                    }

                    // Write Firestore message with tempId so snapshot can reconcile
                    saveMessageInfoToDatabase(
                        currentGroupId = groupId,
                        messageText = "",
                        audioUrl = downloadUri.toString(),
                        tempId = tempId
                    )
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Audio upload failed", it)
                _messages.update { msgs ->
                    msgs.map { msg ->
                        if (msg.tempId == tempId) msg.copy(messageStatus = MessageStatus.FAILED)
                        else msg
                    }
                }
            }
    }

    // ... [Keep the rest of your existing methods] ...

    override fun onCleared() {
        super.onCleared()
        messagesListener?.remove()
        // Clean up recorder if needed
        try {
            recorder.cancelRecording()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
```

### Step 4: Update Your VoiceMessagePlayer
Replace your existing VoiceMessagePlayer with this updated version that handles errors better:

```kotlin
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
```

## Implementation Steps

### 1. Delete Your Old OpusAudioRecordVoiceRecorder File
Delete the existing `OpusAudioRecordVoiceRecorder.kt` file from your project.

### 2. Create New Files
Create these new files in your project:
- `VoiceRecorder.kt` (interface)
- `AACMediaRecorderVoiceRecorder.kt` (new recorder implementation)

### 3. Update Your ViewModel
In your `SharedChatsScreenViewModel`:
- Change the constructor parameter from `val recorder: VoiceRecorder` to `recorder: VoiceRecorder? = null`
- Add this line at the beginning of the class: `val recorder: VoiceRecorder = recorder ?: AACMediaRecorderVoiceRecorder()`
- Update the recording methods as shown above

### 4. Update Your VoiceMessagePlayer
Replace your existing `VoiceMessagePlayer` composable with the updated version above.

### 5. Update Gradle Dependencies (Optional)
Since we're using AAC instead of Opus, you can actually remove these dependencies if you're not using them elsewhere:
```gradle
// You can remove these if not used elsewhere:
// implementation("io.github.jaredmdobson:concentus:1.0.2")
// implementation("org.gagravarr:vorbis-java-core:0.8")

// Keep this one - it's needed for ExoPlayer:
implementation("androidx.media3:media3-exoplayer:1.2.1")
```

### 6. Clean and Rebuild
After making all changes:
1. Clean your project: Build → Clean Project
2. Rebuild: Build → Rebuild Project
3. If you get any import errors, make sure to import the correct classes

## Testing the Fix

1. **Run your app** on a device or emulator
2. **Try recording a voice message** - it should now work
3. **Check the duration** - it should show the correct time (not 0:00)
4. **Try playing back** - the audio should play correctly
5. **Test on different devices** - AAC format works on all Android devices

## Why This Fix Works

1. **AAC Format**: We're using AAC audio codec which is natively supported by Android and ExoPlayer
2. **MediaRecorder**: Using Android's built-in MediaRecorder API which handles all the complex audio processing
3. **Proper File Format**: The `.m4a` files created are standard MPEG-4 audio files that any player can handle
4. **Error Handling**: Added proper error handling to prevent crashes and show error states to users

## Troubleshooting

If you still have issues:

1. **Check Permissions**: Make sure RECORD_AUDIO permission is granted
2. **Check Logs**: Look for error messages in Logcat with tag "SharedChatsScreenViewModel" or "VoiceMessagePlayer"
3. **File Size**: The updated code checks if the recorded file is empty and won't upload empty files
4. **Storage**: Make sure Firebase Storage rules allow audio file uploads

## Alternative: Fixed Opus Implementation (Advanced)

If you specifically need Opus format for bandwidth reasons, I can provide the fixed Opus implementation, but it's more complex and requires creating a custom OGG container writer. The AAC solution above is recommended for most use cases.