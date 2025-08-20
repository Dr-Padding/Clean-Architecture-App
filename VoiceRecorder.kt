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