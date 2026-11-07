package org.turboio.customai.ai

enum class ProviderKind(
    val id: String,
    val label: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val suggestedModels: List<String>,
    val endpointEditable: Boolean,
) {
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-5-mini",
        listOf("gpt-5-mini", "gpt-5", "gpt-4.1-mini", "gpt-4o-mini"), false),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.8-flash",
        listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.6-flash"), false),
    ANTHROPIC("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1/messages", "claude-opus-5-5",
        listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"), false),
    OPENAI_COMPATIBLE("compatible", "Tương thích OpenAI (DeepSeek, Groq, OpenRouter, Mistral, máy chủ riêng…)", "", "",
        listOf("deepseek-chat", "llama-3.3-70b-versatile", "mistral-small-latest"), true);

    companion object {
        fun fromId(id: String?): ProviderKind = entries.firstOrNull { it.id == id } ?: OPENAI
    }
}

const val DEFAULT_SYSTEM_PROMPT = """Bạn là trợ lý giọng nói hiển thị trên kính RayNeo iO; câu trả lời cũng được đọc to qua tai nghe.
Luôn trả lời bằng tiếng Việt, trừ khi người dùng hỏi bằng ngôn ngữ khác hoặc yêu cầu trả lời bằng ngôn ngữ khác (ví dụ: dịch, luyện tiếng Anh); khi đó dùng đúng ngôn ngữ được yêu cầu.
Màn hình rất nhỏ: trả lời ngắn gọn, đi thẳng vào ý chính, tối đa 2–3 câu (khoảng 250 ký tự).
Chỉ dùng văn bản thuần: không Markdown, không bảng, không danh sách dài, không emoji, không đường link.
Viết câu tự nhiên để đọc to nghe trôi chảy; viết số và đơn vị theo cách đọc thông thường.
Nếu người dùng yêu cầu chi tiết hơn ("chi tiết", "giải thích kỹ", "trả lời đầy đủ"), hãy trả lời đầy đủ hơn, vẫn bằng văn bản thuần và đoạn ngắn.
Nếu không biết hoặc cần dữ liệu thời gian thực mà bạn không có, hãy nói rõ trong một câu."""

data class AiConfig(
    val provider: ProviderKind = ProviderKind.OPENAI,
    val model: String = provider.defaultModel,
    val endpoint: String = provider.defaultEndpoint,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val maxTokens: Int = 800,
    val timeoutSeconds: Int = 30,
    val historyTurns: Int = 3,
    /** Lets the model search the web when it needs current facts (Gemini: Google Search; Claude: web search tool). */
    val webSearch: Boolean = false,
)

data class ChatMessage(val role: String, val content: String) {
    init { require(role == "user" || role == "assistant") }
}

data class AiAnswer(val text: String, val latencyMs: Long, val provider: ProviderKind, val model: String, val truncated: Boolean)

/** User-facing failure. [message] is user-facing text and already safe to display/log (no secrets). */
class AiException(message: String, val httpStatus: Int = 0) : Exception(message)

class HttpRequestSpec(val url: String, val headers: Map<String, String>, val body: String) {
    /** For diagnostics only: URL without query and header names without values. */
    fun describe(): String = url.substringBefore('?') + " headers=" + headers.keys.sorted().joinToString(",")
}

class HttpResult(val status: Int, val body: String)

interface HttpTransport {
    /** Blocking POST. Throws [java.io.IOException] on network errors / timeouts. */
    fun post(request: HttpRequestSpec, timeoutMs: Int): HttpResult
}
