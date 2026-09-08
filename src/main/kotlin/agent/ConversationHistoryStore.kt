package org.example.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.llm.LlmMessage
import org.example.llm.LlmRole
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

const val DEFAULT_HISTORY_FILE_NAME = ".llm-history.json"
private const val HISTORY_FORMAT_VERSION = 1

typealias ConversationHistorySnapshot = Map<String, List<LlmMessage>>

/** Persistence boundary for dialogue history. Callers never depend on files or JSON. */
interface ConversationHistoryStore {
    fun load(): ConversationHistorySnapshot
    fun save(history: ConversationHistorySnapshot)
    fun clear() = save(emptyMap())
}

/** Default for tests and embedded uses which did not opt into persistent storage. */
object NoOpConversationHistoryStore : ConversationHistoryStore {
    override fun load(): ConversationHistorySnapshot = emptyMap()
    override fun save(history: ConversationHistorySnapshot) = Unit
}

class HistoryPersistenceException(cause: Exception) :
    IOException("Не удалось сохранить историю диалога.", cause)

/** Versioned UTF-8 JSON storage with replace-on-success writes. */
class JsonConversationHistoryStore(
    private val historyFile: Path = Path.of(DEFAULT_HISTORY_FILE_NAME),
    private val replaceFile: (temporaryFile: Path, targetFile: Path) -> Unit = ::replaceAtomically,
) : ConversationHistoryStore {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    @Synchronized
    override fun load(): ConversationHistorySnapshot {
        if (!Files.exists(historyFile)) return emptyMap()

        val stored = json.decodeFromString<StoredHistory>(
            Files.readString(historyFile, StandardCharsets.UTF_8),
        )
        require(stored.version == HISTORY_FORMAT_VERSION) {
            "Unsupported history format version"
        }
        require(stored.branches.map(StoredBranch::id).distinct().size == stored.branches.size) {
            "Duplicate history branch"
        }
        require(stored.branches.none { it.id.isBlank() }) { "Blank history branch id" }

        return stored.branches.associate { branch ->
            branch.id to branch.messages.map { message ->
                LlmMessage(message.role.toLlmRole(), message.text)
            }
        }
    }

    @Synchronized
    override fun save(history: ConversationHistorySnapshot) {
        val stored = StoredHistory(
            branches = history.map { (id, messages) ->
                require(id.isNotBlank()) { "Blank history branch id" }
                StoredBranch(
                    id = id,
                    messages = messages.map { message ->
                        StoredMessage(StoredRole.from(message.role), message.content)
                    },
                )
            },
        )
        val bytes = json.encodeToString(stored).toByteArray(StandardCharsets.UTF_8)
        val absoluteFile = historyFile.toAbsolutePath()
        val parent = absoluteFile.parent

        try {
            Files.createDirectories(parent)
            val temporaryFile = Files.createTempFile(parent, ".llm-history-", ".tmp")
            try {
                Files.write(temporaryFile, bytes)
                replaceFile(temporaryFile, absoluteFile)
            } finally {
                Files.deleteIfExists(temporaryFile)
            }
        } catch (error: Exception) {
            if (error is HistoryPersistenceException) throw error
            throw HistoryPersistenceException(error)
        }
    }
}

private fun replaceAtomically(temporaryFile: Path, targetFile: Path) {
    try {
        Files.move(
            temporaryFile,
            targetFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporaryFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
    }
}

@Serializable
private data class StoredHistory(
    val version: Int = HISTORY_FORMAT_VERSION,
    val branches: List<StoredBranch>,
)

@Serializable
private data class StoredBranch(
    val id: String,
    val messages: List<StoredMessage>,
)

@Serializable
private data class StoredMessage(
    val role: StoredRole,
    val text: String,
)

@Serializable
private enum class StoredRole {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT;

    fun toLlmRole(): LlmRole = when (this) {
        USER -> LlmRole.USER
        ASSISTANT -> LlmRole.ASSISTANT
    }

    companion object {
        fun from(role: LlmRole): StoredRole = when (role) {
            LlmRole.USER -> USER
            LlmRole.ASSISTANT -> ASSISTANT
        }
    }
}
