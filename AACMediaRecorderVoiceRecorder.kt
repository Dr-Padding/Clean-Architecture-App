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