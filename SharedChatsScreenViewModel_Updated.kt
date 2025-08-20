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

    private val chatScrollPositions = mutableMapOf<String, ScrollPosition>()

    fun saveListScrollPosition(groupId: String, index: Int, offset: Int) {
        chatScrollPositions[groupId] = ScrollPosition(index, offset)
    }

    fun getListScrollPosition(groupId: String): ScrollPosition? = chatScrollPositions[groupId]

    // Indicates the first snapshot for the current group has been received (to avoid empty-state flicker)
    private val _messagesLoaded = MutableStateFlow(false)
    val messagesLoaded = _messagesLoaded.asStateFlow()

    // In-memory per-group cache to show messages instantly on re-entry
    private val messagesCache = mutableMapOf<String, List<ChatMessage>>()

    private val _groupName = MutableStateFlow("")
    val groupName = _groupName.asStateFlow()

    private val _groupId = MutableStateFlow<String?>(null)
    val groupId = _groupId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _listOfGroupChats = MutableStateFlow<MutableList<Chat>>(mutableListOf())
    val listOfGroupChats = _listOfGroupChats.asStateFlow()

    private var messagesListener: ListenerRegistration? = null

    // Cache for current user's full name
    private var cachedUserFullName: String = ""

    private var serverTimeOffsetMs: Long = 0L
    private var lastKnownServerMs: Long = Long.MIN_VALUE

    private fun nowServerAlignedTimestamp(): Timestamp {
        val nowMs = System.currentTimeMillis() + serverTimeOffsetMs
        val adjustedMs = if (lastKnownServerMs == Long.MIN_VALUE) nowMs else maxOf(nowMs, lastKnownServerMs + 1)
        return Timestamp(Date(adjustedMs))
    }

    // --- Playback coordination ---
    private val _currentlyPlayingId = MutableStateFlow<String?>(null)
    val currentlyPlayingId: StateFlow<String?> = _currentlyPlayingId

    fun startPlayback(id: String) {
        // Stop any currently playing message before starting new one
        _currentlyPlayingId.value = id
    }

    fun stopPlayback(id: String) {
        if (_currentlyPlayingId.value == id) {
            _currentlyPlayingId.value = null
        }
    }

    init {
        prefetchUserFullName()
        observeGroupChats()
    }

    fun setChatGroupNameToSpecificChatScreenAppBar(groupName: String) {
        viewModelScope.launch {
            _groupName.value = groupName
        }
    }

    // ----- User identity -----

    private fun prefetchUserFullName() {
        val userDocRef = currentUserDetails() ?: return
        userDocRef.get()
            .addOnSuccessListener { document ->
                if (document != null) {
                    val firstName = document.getString("firstName") ?: ""
                    val lastName = document.getString("lastName") ?: ""
                    cachedUserFullName = "$firstName $lastName".trim()
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to fetch user full name", it)
            }
    }

    private fun getUserFullNameSync(): String {
        return cachedUserFullName.ifBlank { "You" }
    }

    // ----- Groups -----

    private fun observeGroupChats() {
        viewModelScope.launch {
            val userId = currentUserId()
            val groupChatsReference = getGroupChatsFromFirebase(userId)
            groupChatsReference.addSnapshotListener { snapshot, exception ->
                if (exception != null || snapshot == null) {
                    Log.e(TAG, "Exception when querying group chats", exception)
                    return@addSnapshotListener
                }

                val groupChatList = snapshot.documents.mapNotNull { document ->
                    document.toObject(Chat::class.java)?.apply {
                        groupChatId = document.id
                    }
                }
                _listOfGroupChats.value = groupChatList.toMutableList()
            }
        }
    }

    fun setChatGroupId(groupChatId: String) {
        _groupId.value = groupChatId
        // Show cached messages immediately (if any)
        _messages.value = messagesCache[groupChatId] ?: emptyList()
        observeMessages(groupChatId)
    }

    // ----- Messages -----

    private fun ChatMessage.uniqueKey(): String? = tempId ?: messageId

    // Track insertion order for optimistic messages
    private var messageOrderCounter = 0L

    private fun observeMessages(groupChatId: String) {
        messagesListener?.remove()
        val me = currentUserId()

        val messagesRef = firebaseDatabase().collection("groups")
            .document(groupChatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)

        // Include local metadata so cached results can arrive instantly
        messagesListener = messagesRef.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, exception ->
            if (exception != null || snapshot == null) {
                Log.e(TAG, "Exception when querying messages", exception)
                return@addSnapshotListener
            }

            val remoteMessages = snapshot.documents.mapNotNull { document ->
                document.toObject(ChatMessage::class.java)?.apply {
                    messageId = document.id
                    isSender = senderId == me
                    if (messageStatus == MessageStatus.SENDING) {
                        messageStatus = MessageStatus.SENT
                    }
                }
            }

            // Update server-time offset based on latest remote message
            val latestRemoteMs = remoteMessages.maxOfOrNull { it.timestamp?.toDate()?.time ?: Long.MIN_VALUE }
            if (latestRemoteMs != null && latestRemoteMs != Long.MIN_VALUE) {
                lastKnownServerMs = maxOf(lastKnownServerMs, latestRemoteMs)
                serverTimeOffsetMs = latestRemoteMs - System.currentTimeMillis()
            }

            // Reconcile optimistic messages with remote ones
            val current = _messages.value

            val updatedMessages = current.map { existing ->
                if (existing.messageId == null && existing.tempId != null) {
                    val remoteMatch = remoteMessages.find { it.tempId == existing.tempId }
                    if (remoteMatch != null) {
                        existing.copy(
                            messageId = remoteMatch.messageId,
                            timestamp = remoteMatch.timestamp ?: existing.timestamp,
                            messageStatus = remoteMatch.messageStatus,
                            audioUrl = remoteMatch.audioUrl ?: existing.audioUrl,
                            localPathToAudioFile = if (remoteMatch.audioUrl != null) null else existing.localPathToAudioFile
                        )
                    } else {
                        existing
                    }
                } else {
                    existing
                }
            }

            val newRemoteMessages = remoteMessages.filter { remote ->
                remote.tempId == null || updatedMessages.none { it.tempId == remote.tempId }
            }

            val allMessages = (updatedMessages + newRemoteMessages)
                .distinctBy { it.uniqueKey() }
                .sortedWith(
                    compareBy<ChatMessage>(
                        { it.timestamp?.toDate()?.time ?: Long.MAX_VALUE },
                        { it.insertionOrder }
                    ).thenBy { it.tempId ?: it.messageId ?: "" }
                )

            // Publish and cache
            _messages.value = allMessages
            messagesCache[groupChatId] = allMessages
            _messagesLoaded.value = true
        }
    }

    // Warm current group from Firestore local disk cache (no network)
    private fun warmFromLocalCache(groupId: String, limit: Long = 200) {
        val me = currentUserId()
        val ref = firebaseDatabase()
            .collection("groups").document(groupId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .limit(limit)

        ref.get(Source.CACHE)
            .addOnSuccessListener { snap ->
                val msgs = snap.documents.mapNotNull { d ->
                    d.toObject(ChatMessage::class.java)?.apply {
                        messageId = d.id
                        isSender = senderId == me
                    }
                }
                if (msgs.isNotEmpty()) {
                    messagesCache[groupId] = msgs
                    if (_groupId.value == groupId && _messages.value.isEmpty()) {
                        _messages.value = msgs
                    }
                }
            }
    }
    
    // ----- Voice recording -----

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

    /**
     * Write message to Firestore, including tempId for reconciliation.
     * For text messages, create optimistic message immediately.
     */
    fun saveMessageInfoToDatabase(
        currentGroupId: String,
        messageText: String = "",
        audioUrl: String = "",
        tempId: String? = null
    ) {
        if (messageText.isEmpty() && audioUrl.isEmpty()) return

        val uid = currentUserId() ?: ""
        val senderName = getUserFullNameSync()

        // For text messages (not voice), create optimistic message
        if (messageText.isNotEmpty() && tempId == null) {
            val newTempId = UUID.randomUUID().toString()
            val optimisticMessage = ChatMessage(
                messageId = null,
                senderId = uid,
                senderName = senderName,
                messageText = messageText,
                audioUrl = null,
                tempId = newTempId,
                timestamp = nowServerAlignedTimestamp(),
                messageStatus = MessageStatus.SENDING,
                isSender = true,
                localPathToAudioFile = null,
                insertionOrder = ++messageOrderCounter,
                localCreatedAtMs = System.currentTimeMillis()
            )

            _messages.update { it + optimisticMessage }

            // Now save to Firestore with the tempId
            saveToFirestore(currentGroupId, messageText, audioUrl, newTempId, uid, senderName)
        } else {
            // For voice messages or updates, use existing tempId
            saveToFirestore(currentGroupId, messageText, audioUrl, tempId, uid, senderName)
        }
    }

    private fun saveToFirestore(
        currentGroupId: String,
        messageText: String,
        audioUrl: String,
        tempId: String?,
        uid: String,
        senderName: String
    ) {
        val utcTimestamp = FieldValue.serverTimestamp()

        val data = hashMapOf(
            "senderId" to uid,
            "senderName" to senderName,
            "timestamp" to utcTimestamp
        ).apply {
            if (messageText.isNotEmpty()) put("messageText", messageText)
            if (audioUrl.isNotEmpty()) put("audioUrl", audioUrl)
            if (!tempId.isNullOrBlank()) put("tempId", tempId)
        }

        firebaseDatabase()
            .collection("groups")
            .document(currentGroupId)
            .collection("messages")
            .add(data)
            .addOnSuccessListener { docRef ->
                Log.d(TAG, "Message added to Firestore: ${docRef.id}")
                // Flip optimistic to SENT and attach the real messageId
                if (!tempId.isNullOrBlank()) {
                    _messages.update { msgs ->
                        msgs.map { msg ->
                            if (msg.tempId == tempId) {
                                msg.copy(
                                    messageId = docRef.id,
                                    messageStatus = MessageStatus.SENT
                                )
                            } else msg
                        }
                    }
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Error adding message", it)
                if (!tempId.isNullOrBlank()) {
                    _messages.update { msgs ->
                        msgs.map { msg ->
                            if (msg.tempId == tempId) {
                                msg.copy(messageStatus = MessageStatus.FAILED)
                            } else msg
                        }
                    }
                }
            }
    }

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