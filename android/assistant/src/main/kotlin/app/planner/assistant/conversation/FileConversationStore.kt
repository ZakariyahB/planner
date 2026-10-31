package app.planner.assistant.conversation

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps the conversation in one JSON file (the app passes a file in its private files directory). Writes go to a
 * temporary file that replaces the old one, so a crash mid-write never leaves half a conversation.
 */
class FileConversationStore(private val file: File) : ConversationStore {
    override suspend fun load(): Conversation = withContext(Dispatchers.IO) {
        val text = try {
            if (file.exists()) file.readText() else null
        } catch (e: IOException) {
            null
        }
        ConversationCodec.decode(text)
    }

    override suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ConversationCodec.encode(conversation))
        if (!tmp.renameTo(file)) {
            // Some file systems will not rename over an existing file.
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not save the conversation")
        }
    }
}
