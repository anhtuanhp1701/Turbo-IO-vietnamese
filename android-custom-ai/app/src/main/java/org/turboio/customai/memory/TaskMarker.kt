package org.turboio.customai.memory

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Lets the configured assistant (Gemini, OpenAI, Claude…) turn free phrasing (“preciso comprar pão amanhã”) into an item:
 * the system prompt asks for a one-line marker at the start of the answer; the app removes it and saves the item.
 * Provider-agnostic (plain text, no tool-calling API).
 */
object TaskMarker {
    data class Found(val task: String, val due: String, val rest: String)

    private val REGEX = Regex("^\\s*\\[\\[\\s*TAREFA\\s*:\\s*(.+?)\\s*\\|\\s*QUANDO\\s*:\\s*([^\\]]*?)\\s*]]\\s*", RegexOption.IGNORE_CASE)

    fun instruction(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = Instant.ofEpochMilli(now).atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")))
        return "\n\nBây giờ: $stamp. Nếu người dùng nhờ nhắc, ghi lại, lên lịch hoặc đừng quên điều gì, hoặc nói điều cần " +
            "làm sau (vd: “mai tôi cần mua bánh mì”), hãy bắt đầu câu trả lời bằng MỘT dòng đúng như sau: " +
            "[[TAREFA: động từ + đối tượng (viết bằng ngôn ngữ của người dùng) | QUANDO: AAAA-MM-DDTHH:MM hoặc để trống]] rồi xác nhận bằng một câu ngắn. " +
            "Dùng ngày giờ hiện tại ở trên để tính hạn. Trong mọi trường hợp khác, không bao giờ dùng ký hiệu này."
    }

    fun find(answer: String): Found? {
        val m = REGEX.find(answer) ?: return null
        return Found(m.groupValues[1].trim(), m.groupValues[2].trim(), answer.substring(m.range.last + 1).trim())
    }
}
