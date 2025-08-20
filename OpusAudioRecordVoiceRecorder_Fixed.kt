import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Fixed Opus audio recorder that creates a proper OGG/Opus container.
 * This implementation manually creates the OGG container format since
 * vorbis-java-core doesn't support Opus.
 */
class OpusAudioRecordVoiceRecorder : VoiceRecorder {
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var isRecording = AtomicBoolean(false)
    private var isPaused = AtomicBoolean(false)

    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null

    private var outputFile: File? = null

    override fun startRecording(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false

        val sampleRate = 48_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) return false

        // Prefer VOICE_RECOGNITION for cleaner speech path; fallback to MIC
        val source = runCatching { MediaRecorder.AudioSource.VOICE_RECOGNITION }
            .getOrDefault(MediaRecorder.AudioSource.MIC)

        val frameSizeSamples = 960 // 20 ms @ 48 kHz
        val bytesPerSample = 2
        val readBufferBytes = maxOf(minBuf, frameSizeSamples * bytesPerSample * 4)

        val ar = AudioRecord(
            source,
            sampleRate,
            channelConfig,
            audioFormat,
            readBufferBytes
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return false
        }
        audioRecord = ar

        // Enable built-in DSP if present
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(ar.audioSessionId)
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(ar.audioSessionId)
        if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(ar.audioSessionId)
        ns?.enabled = true
        aec?.enabled = true
        agc?.enabled = true

        // Prepare output .opus (Ogg/Opus)
        outputFile = File.createTempFile("voice_", ".opus", context.cacheDir)

        // Opus encoder
        val encoder = OpusEncoder(sampleRate, 1, OpusApplication.OPUS_APPLICATION_AUDIO).apply {
            setBitrate(32_000)
            // Try to set VBR if method exists
            runCatching { 
                this::class.java.getMethod("setUseVBR", Boolean::class.java).invoke(this, true)
            }
            setComplexity(10)
            signalType = OpusSignal.OPUS_SIGNAL_VOICE
        }

        isRecording.set(true)
        isPaused.set(false)

        recordThread = Thread {
            val pcmShorts = ShortArray(readBufferBytes / bytesPerSample)
            var carry = ShortArray(0)
            val encoded = ByteArray(4096)
            
            // Create OGG/Opus writer
            val oggWriter = SimpleOggOpusWriter(outputFile!!, sampleRate, 1)

            try {
                ar.startRecording()
                
                // Write OGG/Opus headers
                oggWriter.writeHeaders()
                
                var granulePosition = 0L
                
                while (isRecording.get()) {
                    val read = ar.read(pcmShorts, 0, pcmShorts.size)
                    if (read <= 0) continue
                    if (isPaused.get()) continue

                    // Merge carry + newly read
                    val pending = if (carry.isNotEmpty()) {
                        val merged = ShortArray(carry.size + read)
                        System.arraycopy(carry, 0, merged, 0, carry.size)
                        System.arraycopy(pcmShorts, 0, merged, carry.size, read)
                        carry = ShortArray(0)
                        merged
                    } else {
                        pcmShorts.copyOf(read)
                    }

                    // Encode in 20 ms frames
                    var idx = 0
                    while (pending.size - idx >= frameSizeSamples) {
                        val n = encoder.encode(pending, idx, frameSizeSamples, encoded, 0, encoded.size)
                        if (n > 0) {
                            granulePosition += frameSizeSamples
                            oggWriter.writeAudioPacket(encoded.copyOf(n), granulePosition)
                        }
                        idx += frameSizeSamples
                    }

                    // Save leftover for next loop
                    if (idx < pending.size) {
                        val left = pending.size - idx
                        carry = ShortArray(left)
                        System.arraycopy(pending, idx, carry, 0, left)
                    }
                }
                
                // Flush any remaining samples
                if (carry.isNotEmpty()) {
                    val padded = ShortArray(frameSizeSamples)
                    System.arraycopy(carry, 0, padded, 0, min(carry.size, frameSizeSamples))
                    val n = encoder.encode(padded, 0, frameSizeSamples, encoded, 0, encoded.size)
                    if (n > 0) {
                        granulePosition += frameSizeSamples
                        oggWriter.writeAudioPacket(encoded.copyOf(n), granulePosition, isLast = true)
                    }
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                runCatching { oggWriter.close() }
                runCatching {
                    ar.stop()
                    ar.release()
                }
                ns?.release(); ns = null
                aec?.release(); aec = null
                agc?.release(); agc = null
                audioRecord = null
            }
        }.apply { start() }

        return true
    }

    override fun pauseRecording() {
        isPaused.set(true)
    }

    override fun resumeRecording() {
        isPaused.set(false)
    }

    override fun stopRecording(): File? {
        isRecording.set(false)
        recordThread?.join(1500)
        recordThread = null
        return outputFile.also { outputFile = null }
    }

    override fun cancelRecording() {
        val f = outputFile
        isRecording.set(false)
        recordThread?.join(1500)
        recordThread = null
        if (f?.exists() == true) runCatching { f.delete() }
        outputFile = null
    }
}

/**
 * Simple OGG/Opus writer that creates a valid OGG container with Opus streams.
 * This is a minimal implementation that creates playable OGG/Opus files.
 */
class SimpleOggOpusWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int
) {
    private val fos = FileOutputStream(file)
    private var pageSequenceNumber = 0
    private val streamSerialNumber = System.currentTimeMillis().toInt()
    
    fun writeHeaders() {
        // Write Opus ID Header
        val idHeader = createOpusIdHeader()
        writeOggPage(idHeader, 0, isFirst = true, isContinued = false, isLast = false)
        
        // Write Opus Comment Header
        val commentHeader = createOpusCommentHeader()
        writeOggPage(commentHeader, 0, isFirst = false, isContinued = false, isLast = false)
    }
    
    fun writeAudioPacket(opusData: ByteArray, granulePosition: Long, isLast: Boolean = false) {
        writeOggPage(opusData, granulePosition, isFirst = false, isContinued = false, isLast = isLast)
    }
    
    private fun createOpusIdHeader(): ByteArray {
        val buffer = ByteBuffer.allocate(19)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // Magic signature "OpusHead"
        buffer.put("OpusHead".toByteArray())
        // Version (1)
        buffer.put(1)
        // Channel count
        buffer.put(channels.toByte())
        // Pre-skip (3840 samples is typical)
        buffer.putShort(3840)
        // Input sample rate
        buffer.putInt(sampleRate)
        // Output gain (0)
        buffer.putShort(0)
        // Channel mapping family (0 = mono/stereo)
        buffer.put(0)
        
        return buffer.array()
    }
    
    private fun createOpusCommentHeader(): ByteArray {
        val vendor = "OpusAudioRecorder"
        val buffer = ByteBuffer.allocate(8 + 4 + vendor.length + 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // Magic signature "OpusTags"
        buffer.put("OpusTags".toByteArray())
        // Vendor string length
        buffer.putInt(vendor.length)
        // Vendor string
        buffer.put(vendor.toByteArray())
        // User comment list length (0 = no comments)
        buffer.putInt(0)
        
        return buffer.array()
    }
    
    private fun writeOggPage(
        data: ByteArray,
        granulePosition: Long,
        isFirst: Boolean,
        isContinued: Boolean,
        isLast: Boolean
    ) {
        // Calculate segments
        val segments = mutableListOf<Byte>()
        var remaining = data.size
        var offset = 0
        
        while (remaining > 0) {
            val segmentSize = min(remaining, 255)
            segments.add(segmentSize.toByte())
            remaining -= segmentSize
        }
        
        // If last segment is exactly 255, add a zero-length segment
        if (segments.isNotEmpty() && segments.last() == 255.toByte()) {
            segments.add(0)
        }
        
        val headerSize = 27 + segments.size
        val pageSize = headerSize + data.size
        
        val page = ByteBuffer.allocate(pageSize)
        page.order(ByteOrder.LITTLE_ENDIAN)
        
        // OGG page header
        page.put("OggS".toByteArray()) // Capture pattern
        page.put(0) // Version
        
        // Header type flags
        var headerType = 0
        if (isContinued) headerType = headerType or 0x01
        if (isFirst) headerType = headerType or 0x02
        if (isLast) headerType = headerType or 0x04
        page.put(headerType.toByte())
        
        // Granule position
        page.putLong(granulePosition)
        
        // Stream serial number
        page.putInt(streamSerialNumber)
        
        // Page sequence number
        page.putInt(pageSequenceNumber++)
        
        // Checksum (will be calculated later)
        val checksumPosition = page.position()
        page.putInt(0)
        
        // Number of segments
        page.put(segments.size.toByte())
        
        // Segment table
        segments.forEach { page.put(it) }
        
        // Page data
        page.put(data)
        
        // Calculate and update CRC32 checksum
        val pageArray = page.array()
        val checksum = calculateOggCrc32(pageArray)
        page.putInt(checksumPosition, checksum)
        
        // Write to file
        fos.write(pageArray)
    }
    
    private fun calculateOggCrc32(data: ByteArray): Int {
        val crcTable = IntArray(256)
        for (i in 0..255) {
            var crc = i shl 24
            for (j in 0..7) {
                crc = if ((crc and 0x80000000.toInt()) != 0) {
                    (crc shl 1) xor 0x04c11db7
                } else {
                    crc shl 1
                }
            }
            crcTable[i] = crc
        }
        
        var crc = 0
        for (byte in data) {
            val index = ((crc ushr 24) xor (byte.toInt() and 0xff)) and 0xff
            crc = (crc shl 8) xor crcTable[index]
        }
        
        return crc
    }
    
    fun close() {
        fos.close()
    }
}