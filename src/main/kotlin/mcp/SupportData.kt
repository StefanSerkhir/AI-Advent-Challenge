package org.example.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private const val SUPPORT_FORMAT_VERSION = 1
private const val MAX_SUPPORT_RECORDS = 1_000
private const val MAX_SUPPORT_TEXT = 2_000
private const val MAX_SUPPORT_EVENT_TEXT = 500
private const val MAX_SUPPORT_EVENTS = 10

val SUPPORT_TICKET_ID_REGEX = Regex("^TCK-[1-9][0-9]*$")
private val SUPPORT_USER_ID_REGEX = Regex("^USR-[1-9][0-9]*$")
private val SUPPORT_LOCALE_REGEX = Regex("^[a-z]{2}(?:-[A-Z]{2})?$")
private val SUPPORT_REGION_REGEX = Regex("^[A-Z]{2}$")
private val SUPPORT_PRODUCT_VERSION_REGEX = Regex("^[0-9]+(?:\\.[0-9]+){1,3}$")
private val SUPPORT_ERROR_CODE_REGEX = Regex("^[A-Z][A-Z0-9_]{2,63}$")

@Serializable
data class SupportUsersDocument(
    val formatVersion: Int,
    val users: List<SupportUser>,
)

@Serializable
data class SupportTicketsDocument(
    val formatVersion: Int,
    val tickets: List<SupportTicket>,
)

@Serializable
data class SupportUser(
    val id: String,
    val plan: String,
    val locale: String,
    val region: String,
    val accountStatus: String,
    val mfaEnabled: Boolean,
)

@Serializable
data class SupportTicketEvent(
    val type: String,
    val occurredAt: String,
    val details: String,
)

@Serializable
data class SupportTicket(
    val id: String,
    val userId: String,
    val status: String,
    val category: String,
    val subject: String,
    val description: String,
    val errorCode: String,
    val channel: String,
    val productVersion: String,
    val createdAt: String,
    val recentEvents: List<SupportTicketEvent>,
)

@Serializable
data class SupportTicketContext(
    val ticket: SupportTicket,
    val user: SupportUser,
)

/** Strict, fully validated support data boundary used only inside the support MCP process. */
class SupportDataRepository private constructor(
    private val users: Map<String, SupportUser>,
    private val tickets: Map<String, SupportTicket>,
) {
    fun getTicketContext(ticketId: String): SupportTicketContext? {
        require(SUPPORT_TICKET_ID_REGEX.matches(ticketId)) { "ticketId имеет неверный формат." }
        val ticket = tickets[ticketId] ?: return null
        return SupportTicketContext(ticket, checkNotNull(users[ticket.userId]))
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = true
        }

        fun fromResources(classLoader: ClassLoader = SupportDataRepository::class.java.classLoader): SupportDataRepository {
            fun read(name: String): String = classLoader.getResourceAsStream(name)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalArgumentException("Ресурс данных поддержки недоступен.")
            return fromJson(read("support/users.json"), read("support/tickets.json"))
        }

        fun fromDirectory(directory: Path): SupportDataRepository {
            require(Files.isDirectory(directory)) { "Каталог данных поддержки недоступен." }
            return fromJson(
                Files.readString(directory.resolve("users.json"), Charsets.UTF_8),
                Files.readString(directory.resolve("tickets.json"), Charsets.UTF_8),
            )
        }

        fun fromJson(usersJson: String, ticketsJson: String): SupportDataRepository {
            val usersDocument = json.decodeFromString<SupportUsersDocument>(usersJson)
            val ticketsDocument = json.decodeFromString<SupportTicketsDocument>(ticketsJson)
            require(usersDocument.formatVersion == SUPPORT_FORMAT_VERSION) { "Неподдерживаемая версия users.json." }
            require(ticketsDocument.formatVersion == SUPPORT_FORMAT_VERSION) { "Неподдерживаемая версия tickets.json." }
            require(usersDocument.users.size in 1..MAX_SUPPORT_RECORDS) { "Некорректное число пользователей поддержки." }
            require(ticketsDocument.tickets.size in 1..MAX_SUPPORT_RECORDS) { "Некорректное число тикетов поддержки." }
            usersDocument.users.forEach(::validateUser)
            ticketsDocument.tickets.forEach(::validateTicket)
            val users = usersDocument.users.associateBy(SupportUser::id)
            val tickets = ticketsDocument.tickets.associateBy(SupportTicket::id)
            require(users.size == usersDocument.users.size) { "users.json содержит повторяющиеся id." }
            require(tickets.size == ticketsDocument.tickets.size) { "tickets.json содержит повторяющиеся id." }
            tickets.values.forEach { require(it.userId in users) { "Тикет ссылается на неизвестного пользователя." } }
            return SupportDataRepository(users, tickets)
        }

        fun parseContext(contextJson: String): SupportTicketContext {
            val context = json.decodeFromString<SupportTicketContext>(contextJson)
            validateUser(context.user)
            validateTicket(context.ticket)
            require(context.ticket.userId == context.user.id) { "Контекст тикета содержит несвязанного пользователя." }
            return context
        }

        private fun validateUser(user: SupportUser) {
            require(SUPPORT_USER_ID_REGEX.matches(user.id)) { "Некорректный id пользователя поддержки." }
            requireSafeText(user.plan, 64, "plan")
            require(SUPPORT_LOCALE_REGEX.matches(user.locale)) { "Некорректный locale пользователя поддержки." }
            require(SUPPORT_REGION_REGEX.matches(user.region)) { "Некорректный region пользователя поддержки." }
            require(user.accountStatus in setOf("ACTIVE", "SUSPENDED", "LOCKED")) { "Некорректный accountStatus." }
        }

        private fun validateTicket(ticket: SupportTicket) {
            require(SUPPORT_TICKET_ID_REGEX.matches(ticket.id)) { "Некорректный id тикета поддержки." }
            require(SUPPORT_USER_ID_REGEX.matches(ticket.userId)) { "Некорректный userId тикета поддержки." }
            require(ticket.status in setOf("OPEN", "PENDING", "RESOLVED", "CLOSED")) { "Некорректный status тикета." }
            requireSafeText(ticket.category, 80, "category")
            requireSafeText(ticket.subject, 300, "subject")
            requireSafeText(ticket.description, MAX_SUPPORT_TEXT, "description", multiline = true)
            require(SUPPORT_ERROR_CODE_REGEX.matches(ticket.errorCode)) { "Некорректный errorCode тикета." }
            requireSafeText(ticket.channel, 32, "channel")
            require(SUPPORT_PRODUCT_VERSION_REGEX.matches(ticket.productVersion)) { "Некорректный productVersion тикета." }
            requireInstant(ticket.createdAt, "createdAt")
            require(ticket.recentEvents.size <= MAX_SUPPORT_EVENTS) { "Слишком много recentEvents." }
            ticket.recentEvents.forEach { event ->
                requireSafeText(event.type, 80, "event.type")
                requireInstant(event.occurredAt, "event.occurredAt")
                requireSafeText(event.details, MAX_SUPPORT_EVENT_TEXT, "event.details", multiline = true)
            }
        }

        private fun requireSafeText(value: String, maxLength: Int, name: String, multiline: Boolean = false) {
            require(value.isNotBlank() && value.length <= maxLength) { "$name имеет недопустимую длину." }
            require(value.all { !it.isISOControl() || (multiline && it in setOf('\n', '\r', '\t')) }) {
                "$name содержит недопустимые управляющие символы."
            }
        }

        private fun requireInstant(value: String, name: String) {
            require(runCatching { Instant.parse(value) }.isSuccess) { "$name должен быть ISO-8601 UTC timestamp." }
        }
    }
}

fun SupportTicketContext.toSafeJson(): JsonObject = buildJsonObject {
    put("ticket", buildJsonObject {
        put("id", ticket.id)
        put("userId", ticket.userId)
        put("status", ticket.status)
        put("category", ticket.category)
        put("subject", ticket.subject)
        put("description", ticket.description)
        put("errorCode", ticket.errorCode)
        put("channel", ticket.channel)
        put("productVersion", ticket.productVersion)
        put("createdAt", ticket.createdAt)
        putJsonArray("recentEvents") {
            ticket.recentEvents.take(MAX_SUPPORT_EVENTS).forEach { event ->
                add(buildJsonObject {
                    put("type", event.type)
                    put("occurredAt", event.occurredAt)
                    put("details", event.details.take(MAX_SUPPORT_EVENT_TEXT))
                })
            }
        }
    })
    put("user", buildJsonObject {
        put("id", user.id)
        put("plan", user.plan)
        put("locale", user.locale)
        put("region", user.region)
        put("accountStatus", user.accountStatus)
        put("mfaEnabled", user.mfaEnabled)
    })
}
