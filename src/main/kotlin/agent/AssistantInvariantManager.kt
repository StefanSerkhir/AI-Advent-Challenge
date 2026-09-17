package org.example.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*

const val DEFAULT_ASSISTANT_INVARIANTS_FILE_NAME = ".llm-assistant-invariants.json"
const val MAX_ASSISTANT_INVARIANT_LENGTH = 16_384

enum class AssistantInvariantCategory {
    ARCHITECTURE,
    TECH_DECISION,
    STACK,
    BUSINESS_RULE,
    OTHER;

    companion object {
        fun from(value: String): AssistantInvariantCategory? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

data class AssistantInvariant(
    val id: String,
    val category: AssistantInvariantCategory,
    val text: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
)

data class AssistantInvariantState(
    val version: Long = 0,
    val invariants: List<AssistantInvariant> = emptyList(),
)

data class AssistantInvariantDraft(
    val category: AssistantInvariantCategory,
    val text: String,
)

data class AssistantInvariantDiagnostics(
    val applied: Boolean,
    val stateVersion: Long,
    val appliedCount: Int,
    val appliedInvariantIds: List<String>,
    val responseBlocked: Boolean = false,
)

interface AssistantInvariantStore {
    fun load(): AssistantInvariantState
    fun save(state: AssistantInvariantState)
}

class InMemoryAssistantInvariantStore(
    initial: AssistantInvariantState = AssistantInvariantState(),
) : AssistantInvariantStore {
    private var state = initial

    @Synchronized
    override fun load(): AssistantInvariantState = state

    @Synchronized
    override fun save(state: AssistantInvariantState) {
        this.state = state
    }
}

class AssistantInvariantPersistenceException(cause: Exception) :
    IOException("Не удалось сохранить инварианты ассистента.", cause)

class JsonAssistantInvariantStore(
    private val file: Path = Path.of(DEFAULT_ASSISTANT_INVARIANTS_FILE_NAME),
) : AssistantInvariantStore {
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }

    @Synchronized
    override fun load(): AssistantInvariantState {
        if (!Files.exists(file)) return AssistantInvariantState()
        val document = json.decodeFromString<StoredAssistantInvariantDocument>(
            Files.readString(file, StandardCharsets.UTF_8),
        )
        require(document.formatVersion == ASSISTANT_INVARIANTS_FORMAT_VERSION) {
            "Unsupported assistant invariants format version"
        }
        return document.toDomain().also(::validateStoredState)
    }

    @Synchronized
    override fun save(state: AssistantInvariantState) {
        validateStoredState(state)
        val target = file.toAbsolutePath()
        try {
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, ".llm-assistant-invariants-", ".tmp")
            try {
                Files.writeString(
                    temporary,
                    json.encodeToString(StoredAssistantInvariantDocument.fromDomain(state)),
                    StandardCharsets.UTF_8,
                )
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (error: Exception) {
            if (error is AssistantInvariantPersistenceException) throw error
            throw AssistantInvariantPersistenceException(error)
        }
    }
}

class AssistantInvariantManager(
    private val store: AssistantInvariantStore = InMemoryAssistantInvariantStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val sensitiveText: (String) -> Boolean = { false },
) {
    val loadWarning: String?
    private var current: AssistantInvariantState

    init {
        val loaded = runCatching {
            store.load().also { state ->
                validateStoredState(state)
                state.invariants.forEach { invariant ->
                    require(!sensitiveText(invariant.text)) {
                        "Инварианты содержат настроенный API-ключ."
                    }
                }
            }
        }
        current = loaded.getOrDefault(AssistantInvariantState())
        loadWarning = loaded.exceptionOrNull()?.let {
            "Не удалось восстановить инварианты ассистента: файл повреждён, недоступен или имеет неподдерживаемую версию. Используется пустое состояние."
        }
    }

    @Synchronized
    fun state(): AssistantInvariantState = current

    @Synchronized
    fun containsSensitiveValue(value: String): Boolean =
        current.invariants.any { invariant -> value in invariant.text }

    @Synchronized
    fun add(draft: AssistantInvariantDraft): AssistantInvariant {
        val now = validTimestamp(clock())
        val id = validateInvariantId(idFactory())
        require(current.invariants.none { it.id == id }) { "Не удалось создать уникальный ID инварианта." }
        val invariant = AssistantInvariant(
            id = id,
            category = draft.category,
            text = validateInvariantText(draft.text),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        persist(current.copy(version = current.version + 1, invariants = current.invariants + invariant))
        return invariant
    }

    @Synchronized
    fun update(id: String, draft: AssistantInvariantDraft): AssistantInvariant {
        val validId = validateInvariantId(id)
        require(current.invariants.any { it.id == validId }) { "Неизвестный ID инварианта." }
        val normalized = validateInvariantText(draft.text)
        val updated = current.invariants.map { invariant ->
            if (invariant.id == validId) invariant.copy(
                category = draft.category,
                text = normalized,
                updatedAtEpochMillis = maxOf(validTimestamp(clock()), invariant.updatedAtEpochMillis),
            ) else invariant
        }
        persist(current.copy(version = current.version + 1, invariants = updated))
        return updated.first { it.id == validId }
    }

    @Synchronized
    fun delete(id: String) {
        val validId = validateInvariantId(id)
        require(current.invariants.any { it.id == validId }) { "Неизвестный ID инварианта." }
        persist(current.copy(
            version = current.version + 1,
            invariants = current.invariants.filterNot { it.id == validId },
        ))
    }

    private fun validateInvariantText(value: String): String = normalizeInvariantText(value).also {
        require(!sensitiveText(it)) { "Инвариант не может содержать настроенный API-ключ." }
    }

    private fun persist(candidate: AssistantInvariantState) {
        try {
            store.save(candidate)
        } catch (error: Exception) {
            if (error is AssistantInvariantPersistenceException) throw error
            throw AssistantInvariantPersistenceException(error)
        }
        current = candidate
    }
}

private fun normalizeInvariantText(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n').trim().also {
        require(it.isNotEmpty()) { "Текст инварианта не может быть пустым." }
        require(it.length <= MAX_ASSISTANT_INVARIANT_LENGTH) {
            "Текст инварианта слишком длинный: максимум $MAX_ASSISTANT_INVARIANT_LENGTH символов."
        }
        require(it.none { char -> char.isISOControl() && char != '\n' && char != '\t' }) {
            "Текст инварианта содержит недопустимые управляющие символы."
        }
    }

private fun validateInvariantId(value: String): String = value.trim().also {
    require(it.isNotEmpty() && it.length <= 200 && it.none(Char::isISOControl)) {
        "Некорректный ID инварианта."
    }
}

private fun validTimestamp(value: Long): Long = value.also {
    require(it >= 0) { "Некорректное время изменения инварианта." }
}

private fun validateStoredState(state: AssistantInvariantState) {
    require(state.version >= 0) { "Invalid assistant invariant state version" }
    require(state.invariants.map(AssistantInvariant::id).distinct().size == state.invariants.size) {
        "Duplicate assistant invariant id"
    }
    state.invariants.forEach { invariant ->
        validateInvariantId(invariant.id)
        require(normalizeInvariantText(invariant.text) == invariant.text) { "Invalid stored invariant text" }
        require(invariant.createdAtEpochMillis >= 0 && invariant.updatedAtEpochMillis >= invariant.createdAtEpochMillis) {
            "Invalid assistant invariant timestamps"
        }
    }
}

private const val ASSISTANT_INVARIANTS_FORMAT_VERSION = 1

@Serializable
private data class StoredAssistantInvariantDocument(
    val formatVersion: Int = ASSISTANT_INVARIANTS_FORMAT_VERSION,
    val stateVersion: Long = 0,
    val invariants: List<StoredAssistantInvariant> = emptyList(),
) {
    fun toDomain() = AssistantInvariantState(stateVersion, invariants.map(StoredAssistantInvariant::toDomain))

    companion object {
        fun fromDomain(state: AssistantInvariantState) = StoredAssistantInvariantDocument(
            stateVersion = state.version,
            invariants = state.invariants.map(StoredAssistantInvariant::fromDomain),
        )
    }
}

@Serializable
private data class StoredAssistantInvariant(
    val id: String,
    val category: String,
    val text: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun toDomain() = AssistantInvariant(
        id = id,
        category = runCatching { AssistantInvariantCategory.valueOf(category) }
            .getOrElse { throw IllegalArgumentException("Unknown assistant invariant category") },
        text = text,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    companion object {
        fun fromDomain(value: AssistantInvariant) = StoredAssistantInvariant(
            value.id,
            value.category.name,
            value.text,
            value.createdAtEpochMillis,
            value.updatedAtEpochMillis,
        )
    }
}
