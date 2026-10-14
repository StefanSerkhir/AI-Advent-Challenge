package org.example.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SupportDataTest {
    @Test
    fun `packaged support data is strict versioned validated and linked`() {
        val context = requireNotNull(SupportDataRepository.fromResources().getTicketContext("TCK-1001"))
        assertEquals("USR-1001", context.user.id)
        assertEquals("ACTIVE", context.user.accountStatus)
        assertTrue(context.user.mfaEnabled)
        assertEquals("AUTH_CLOCK_SKEW", context.ticket.errorCode)
        assertEquals("3.8.1", context.ticket.productVersion)
        assertContains(context.ticket.recentEvents.single().details, "742")
        assertFails { SupportDataRepository.fromResources().getTicketContext("TCK-0") }
    }

    @Test
    fun `unknown fields versions invalid ids and broken relations fail closed`() {
        val users = """{"formatVersion":1,"users":[{"id":"USR-1001","plan":"PRO","locale":"ru-RU","region":"RU","accountStatus":"ACTIVE","mfaEnabled":true}]}"""
        val tickets = """{"formatVersion":1,"tickets":[{"id":"TCK-1001","userId":"USR-1001","status":"OPEN","category":"AUTHORIZATION","subject":"Вход","description":"Описание","errorCode":"AUTH_CLOCK_SKEW","channel":"WEB","productVersion":"3.8.1","createdAt":"2026-10-12T09:15:00Z","recentEvents":[]}]}"""
        assertFails { SupportDataRepository.fromJson(users.replace("\"plan\":\"PRO\"", "\"plan\":\"PRO\",\"email\":\"x@example.invalid\""), tickets) }
        assertFails { SupportDataRepository.fromJson(users.replace("\"formatVersion\":1", "\"formatVersion\":2"), tickets) }
        assertFails { SupportDataRepository.fromJson(users, tickets.replace("TCK-1001", "bad")) }
        assertFails { SupportDataRepository.fromJson(users, tickets.replace("USR-1001", "USR-9999")) }
    }
}
