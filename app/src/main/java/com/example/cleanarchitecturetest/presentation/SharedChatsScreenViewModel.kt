private const val TAG = "SharedChatsScreenViewModel"

class SharedChatsScreenViewModel(
    private val recorder: VoiceRecorder = MediaRecorderVoiceRecorder()
) : ViewModel() {

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

        messagesListener = messagesRef.addSnapshotListener { snapshot, exception ->
            if (exception != null || snapshot == null) {
                Log.e(TAG, "Exception when querying messages", exception)
                return@addSnapshotListener
            }

            val remoteMessages = snapshot.documents.mapNotNull { document ->
                document.toObject(ChatMessage::class.java)?.apply {
                    messageId = document.id
                    isSender = senderId == me
                    // Normalize remote status to at least SENT
                    if (messageStatus == MessageStatus.SENDING) {
                        messageStatus = MessageStatus.SENT
                    }
                }
            }

            _messages.update { current ->
                // For each remote message with tempId, update the existing optimistic message
                val updatedMessages = current.map { existing ->
                    if (existing.messageId == null && existing.tempId != null) {
                        // Find matching remote message for this optimistic one
                        val remoteMatch = remoteMessages.find { it.tempId == existing.tempId }
                        if (remoteMatch != null) {
                            // Update with server data but preserve insertion order
                            existing.copy(
                                messageId = remoteMatch.messageId,
                                timestamp = remoteMatch.timestamp,
                                messageStatus = remoteMatch.messageStatus,
                                audioUrl = remoteMatch.audioUrl ?: existing.audioUrl,
                                localPathToAudioFile = if (remoteMatch.audioUrl != null) null else existing.localPathToAudioFile
                                // Keep the original insertionOrder from optimistic message
                            )
                        } else {
                            existing // Keep as-is if no remote match yet
                        }
                    } else {
                        existing // Keep existing messages as-is
                    }
                }

                // Add any new remote messages that don't have corresponding optimistic messages
                val newRemoteMessages = remoteMessages.filter { remote ->
                    remote.tempId == null || updatedMessages.none { it.tempId == remote.tempId }
                }

                val allMessages = (updatedMessages + newRemoteMessages)
                    .distinctBy { it.uniqueKey() }

                // Sort messages chronologically, not by remote/optimistic status
                allMessages.sortedWith(
                    compareBy<ChatMessage> { message ->
                        when {
                            // For messages with timestamps, use the timestamp
                            message.timestamp != null -> message.timestamp!!.toDate().time
                            // For optimistic messages without timestamps, use insertion order
                            // to maintain their position relative to when they were sent
                            else -> message.insertionOrder
                        }
                    }.thenBy { it.tempId ?: it.messageId ?: "" } // deterministic tie-break
                )
            }
        }
    }

    // ----- Voice recording -----

    fun startRecording(context: Context) = recorder.startRecording(context)
    fun cancelRecording() = recorder.cancelRecording()

    fun stopRecordingAndUpload(groupId: String) {
        val file = recorder.stopRecording() ?: return
        val tempId = UUID.randomUUID().toString()

        val optimisticMessage = ChatMessage(
            messageId = null,
            senderId = currentUserId(),
            senderName = getUserFullNameSync(),
            messageText = null,
            audioUrl = null,
            tempId = tempId,
            timestamp = Timestamp.now(),               // Add local timestamp for better ordering
            messageStatus = MessageStatus.SENDING,
            isSender = true,
            localPathToAudioFile = file.absolutePath,
            insertionOrder = ++messageOrderCounter    // Use insertion order as fallback
        )

        // Add to the end of the list
        _messages.update { it + optimisticMessage }

        val uri = Uri.fromFile(file)
        val storageRef = storageReference().child("audio_messages/${file.name}")

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
                timestamp = Timestamp.now(),           // Add local timestamp for better ordering
                messageStatus = MessageStatus.SENDING,
                isSender = true,
                localPathToAudioFile = null,
                insertionOrder = ++messageOrderCounter // Use insertion order as fallback
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
    }
}