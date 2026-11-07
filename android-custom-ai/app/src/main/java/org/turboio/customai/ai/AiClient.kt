package org.turboio.customai.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.turboio.customai.diag.DiagnosticLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** HttpURLConnection transport: HTTPS only (cleartext is also blocked by the network security config). */
class UrlConnectionTransport : HttpTransport {
    override fun post(request: HttpRequestSpec, timeoutMs: Int): HttpResult {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs.coerceAtMost(15000)
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { input ->
                val bytes = input.readNBytesCompat(2 * 1024 * 1024)
                String(bytes, Charsets.UTF_8)
            } ?: ""
            return HttpResult(status, body)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        if (out.size() + n > limit) throw IOException("response-too-large")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** Provider-agnostic chat client with an absolute deadline ([AiConfig.timeoutSeconds]). */
class AiClient(private val transport: HttpTransport = UrlConnectionTransport()) {
    suspend fun ask(config: AiConfig, apiKey: String, history: List<ChatMessage>, question: String, audioWav: ByteArray? = null): AiAnswer {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) throw AiException("Hãy nhập hoặc nói một câu hỏi")
        if (trimmed.length > 4000) throw AiException("Câu hỏi quá dài (tối đa 4000 ký tự)")
        ProviderCodec.validate(config, apiKey)?.let { throw AiException(it) }
        if (audioWav != null && config.provider != ProviderKind.GEMINI) throw AiException("Chỉ Google Gemini mới nhận câu hỏi bằng âm thanh")
        val request = ProviderCodec.build(config, apiKey, history, trimmed, audioWav)
        val timeoutMs = config.timeoutSeconds * 1000
        val started = System.nanoTime()
        DiagnosticLog.i("AI", "requisição ${config.provider.id} modelo=${config.model} → ${request.describe()}")
        val result = try {
            withTimeout(timeoutMs.toLong()) {
                runInterruptible(Dispatchers.IO) { transport.post(request, timeoutMs) }
            }
        } catch (_: TimeoutCancellationException) {
            DiagnosticLog.w("AI", "tempo máximo de ${config.timeoutSeconds}s esgotado")
            throw AiException("Hết thời gian chờ phản hồi (${config.timeoutSeconds}s)")
        } catch (_: SocketTimeoutException) {
            throw AiException("Hết thời gian chờ phản hồi (${config.timeoutSeconds}s)")
        } catch (_: UnknownHostException) {
            throw AiException("Không có internet hoặc không kết nối được máy chủ")
        } catch (e: IOException) {
            DiagnosticLog.w("AI", "erro de rede: ${e.javaClass.simpleName}")
            throw AiException("Lỗi mạng khi gọi nhà cung cấp (${e.javaClass.simpleName})")
        }
        val latency = (System.nanoTime() - started) / 1_000_000
        DiagnosticLog.i("AI", "HTTP ${result.status} em ${latency} ms, ${result.body.length} caracteres")
        val parsed = try { ProviderCodec.parse(config.provider, result) } catch (e: AiException) {
            DiagnosticLog.w("AI", e.message ?: "lỗi"); throw e
        }
        return AiAnswer(parsed.text, latency, config.provider, config.model, parsed.truncated)
    }
}
