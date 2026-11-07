package org.turboio.customai.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.turboio.customai.ble.BusinessListener
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.ble.Randomness
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.protocol.EnvelopeCodec
import org.turboio.customai.protocol.Json
import kotlin.coroutines.resume

/**
 * The glasses' home-screen dashboard (launcher 15/18), as driven by this repository's HarmonyOS client (text card seen
 * on the lens) and the official-app add-on (read → change → read back, no retry). Requests carry a sequence and mode 1;
 * the glasses answer 15/19 with the same sequence.
 */
object DashboardWire {
    const val CARD_ID = "turbo_ui_io"
    const val CARD_MAX = 20
    private const val CATALOG = "https://rayneo.com/a2ui/catalogs/glasses-base/v1/catalog.json"

    const val QUERY = """{"cmd":"dashboard_config","payload":{"version":1,"value":0}}"""

    /** One short Text widget (HarmonyOS installTextWidget format). */
    fun install(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        require(t.isNotEmpty() && t.length <= CARD_MAX) { "card-text" }
        val component = "{\"id\":\"root\",\"component\":\"Text\",\"text\":${Json.quote(t)},\"variant\":\"body\"}"
        val extra = "{\"widgetId\":\"$CARD_ID\",\"name\":\"IO\",\"uiContent\":{\"createSurface\":{\"surfaceId\":\"$CARD_ID\",\"catalogId\":\"$CATALOG\"}," +
            "\"updateComponents\":{\"surfaceId\":\"$CARD_ID\",\"components\":[$component]}}}"
        return "{\"cmd\":\"widget_install\",\"payload\":{\"data\":{\"type\":\"a2ui\",\"id\":\"$CARD_ID\",\"name\":\"IO\",\"extras\":${Json.quote(extra)}}}}"
    }

    /** "3 pend · 15:00 Ligar…" (≤ 20 characters): open count and the next item due today. */
    fun summary(open: Int, nextDue: Long?, nextText: String, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        if (open == 0) return "Không có việc nào"
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val due = nextDue?.let { java.time.Instant.ofEpochMilli(it).atZone(zone) }
        if (due == null || due.toLocalDate() != today) return if (open == 1) "1 việc" else "$open việc".take(CARD_MAX)
        val head = "$open việc · %02d:%02d".format(due.hour, due.minute)
        val room = CARD_MAX - head.length - 1
        val word = nextText.trim().replace(Regex("\\s+"), " ")
        return if (room < 3 || word.isEmpty()) head else head + " " + (if (word.length <= room) word else word.take(room - 1).trimEnd() + "…")
    }

    const val UNINSTALL = """{"cmd":"widget_uninstall","payload":{"data":{"id":"$CARD_ID"}}}"""

    /** A full snapshot (value 0) built from a fresh read. */
    fun update(config: JSONObject): String =
        "{\"cmd\":\"dashboard_update\",\"payload\":{\"version\":1,\"value\":0,\"data\":${Json.quote(config.toString())}}}"

    /** The configuration inside a dashboard_config reply (data may be a JSON string or an object). */
    fun config(reply: JSONObject): JSONObject? {
        if (reply.optString("cmd") != "dashboard_config") return null
        val raw = reply.optJSONObject("payload")?.opt("data") ?: return null
        val c = when (raw) { is JSONObject -> raw; is String -> runCatching { JSONObject(raw) }.getOrNull(); else -> null } ?: return null
        return if (widgets(c) != null) c else null
    }

    data class Widget(val id: String, val type: String, val name: String)

    /**
     * Dashboard items. Two formats are known: `widgets_v2` (rows with id/type, official-app add-on) and, on Strix OS
     * 1.0.5.5, `widgets` as a list of numbers with `widgets_data` (feature test, 2026-10-17).
     */
    fun widgets(c: JSONObject): List<Widget>? {
        val v2 = c.optJSONArray("widgets_v2")
        if (v2 == null) {
            val ids = c.optJSONArray("widgets") ?: return null
            if (ids.length() > 32) return null
            return (0 until ids.length()).map { i -> ids.opt(i).toString().let { Widget(it, "nativo", NATIVE_NAMES[it] ?: "") } }
        }
        val rows = v2
        if (rows.length() > 32) return null
        val out = ArrayList<Widget>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: return null
            val id = r.optString("id"); val type = r.optString("type")
            if (id.isEmpty() || id.length > 128 || type.isEmpty() || out.any { it.id == id }) return null
            out.add(Widget(id, type, r.optString("name")))
        }
        return out
    }

    /** Numeric widget ids (official app 1.0.7 DashboardWidgetType). */
    val NATIVE = linkedMapOf(1 to "Thời tiết", 2 to "To-do", 3 to "Lịch", 4 to "Thao tác", 5 to "E-mail", 6 to "Đồng hồ thế giới")
    private val NATIVE_NAMES = NATIVE.mapKeys { it.key.toString() }

    /** Same configuration with the numeric widget list replaced (Strix OS 1.0.5.5 format); everything else kept. */
    fun withWidgets(c: JSONObject, ids: List<Int>): JSONObject = JSONObject(c.toString()).put("widgets", JSONArray(ids))

    /** simple_mode as stored by the glasses (boolean or 0/1); null when absent. */
    fun simpleMode(c: JSONObject): Boolean? = when (val v = c.opt("simple_mode")) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> null
    }

    /** Same configuration with simple_mode flipped, keeping its original JSON type. */
    fun withSimpleMode(c: JSONObject, on: Boolean): JSONObject {
        val copy = JSONObject(c.toString())
        when (c.opt("simple_mode")) {
            is Number -> copy.put("simple_mode", if (on) 1 else 0)
            else -> copy.put("simple_mode", on)
        }
        return copy
    }

    private fun others(c: JSONObject, ownId: String): String {
        val rows = c.optJSONArray("widgets_v2") ?: JSONArray()
        val kept = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter { it.optString("id") != ownId }
        val rest = JSONObject(c.toString()); rest.remove("widgets_v2")
        return canonical(rest) + "|" + kept.joinToString(",") { canonical(it) }
    }

    /** Everything except our own card (and [ignoring] keys) is unchanged. */
    fun preserves(before: JSONObject, after: JSONObject, ownId: String = CARD_ID, ignoring: Set<String> = emptySet()): Boolean {
        fun strip(c: JSONObject) = JSONObject(c.toString()).also { x -> ignoring.forEach { x.remove(it) } }
        return others(strip(before), ownId) == others(strip(after), ownId)
    }

    /** Key-sorted rendering, so a re-serialised but equal configuration compares equal. */
    fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> Json.quote(k) + ":" + canonical(v.get(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> Json.quote(v)
        JSONObject.NULL, null -> "null"
        else -> v.toString()
    }
}

class Dashboard(private val port: GlassesPort, private val random: Randomness, private val timeoutMs: Long = 20_000) : BusinessListener {
    companion object { const val NOT_SUPPORTED = "Kính này không nhận thẻ riêng trên bảng điều khiển (firmware không có widgets_v2); chưa gửi gì" }
    private val lock = Mutex()
    @Volatile private var pendingSeq = 0L
    @Volatile private var pendingCmd = ""
    @Volatile private var reply: CompletableDeferred<JSONObject>? = null

    init { port.addBusinessListener(this) }

    override fun onBusiness(business: Int, envelope: Envelope) {
        if (business != Business.LAUNCHER || envelope.type != 19 || pendingSeq == 0L) return
        val j = runCatching { JSONObject(envelope.jsonText()) }.getOrNull() ?: return
        val cmd = j.optString("cmd")
        val matches = envelope.sequence == pendingSeq ||
            (envelope.sequence == 0L && (cmd == pendingCmd || (cmd.isEmpty() && j.has("code") && pendingCmd.startsWith("widget_"))))
        if (matches) reply?.complete(j)
    }

    private fun sequence(): Long {
        val b = random.bytes(4); var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xff)
        return 0x40000000L + Math.floorMod(v, 0x3fffffffL)
    }

    /** One request and its correlated reply; null = no reply in time. */
    private suspend fun request(cmd: String, json: String): Pair<JSONObject?, String?> {
        val seq = sequence()
        val waiter = CompletableDeferred<JSONObject>()
        reply = waiter; pendingSeq = seq; pendingCmd = cmd
        try {
            val bytes = try { EnvelopeCodec.encode(18, json, seq, 1) } catch (e: Exception) { return null to "Tin nhắn không hợp lệ" }
            val error = suspendCancellableCoroutine<String?> { cont -> port.send(Business.LAUNCHER, bytes) { e -> if (cont.isActive) cont.resume(e) } }
            if (error != null) return null to error
            return withTimeoutOrNull(timeoutMs) { waiter.await() } to null
        } finally { reply = null; pendingSeq = 0; pendingCmd = "" }
    }

    data class Snapshot(val config: JSONObject) {
        val widgets get() = DashboardWire.widgets(config).orEmpty()
        val simple get() = DashboardWire.simpleMode(config)
        val hasCard get() = widgets.any { it.id == DashboardWire.CARD_ID }
        fun describe(): String = widgets.mapIndexed { i, w -> "${i + 1}. ${w.name.ifBlank { "cartão ${w.id}" }} [${w.type}]" }.joinToString("\n").ifEmpty { "(không có thẻ)" } +
            "\nKiểu: " + when (simple) { true -> "simples"; false -> "đầy đủ"; null -> "kính không cho biết" }
    }

    private suspend fun readLocked(): Pair<Snapshot?, String> {
        if (!port.isReady) return null to "Hãy kết nối kính"
        val (j, error) = request("dashboard_config", DashboardWire.QUERY)
        if (error != null) return null to "Gửi thất bại: $error"
        if (j == null) return null to "Kính không phản hồi khi đọc bảng điều khiển"
        val c = DashboardWire.config(j) ?: return null to "Không nhận ra phản hồi của bảng điều khiển"
        return Snapshot(c) to "lido"
    }

    suspend fun read(): Pair<Snapshot?, String> = lock.withLock { readLocked() }

    /** Installs (or replaces) our one text card; other cards are verified unchanged by reading back. */
    suspend fun showCard(text: String): String = lock.withLock {
        val install = runCatching { DashboardWire.install(text) }.getOrElse { return@withLock "Nội dung thẻ: 1 đến ${DashboardWire.CARD_MAX} ký tự" }
        val (before, why) = readLocked(); before ?: return@withLock why
        // Strix OS 1.0.5.5 lists numeric widgets only and never answered widget_install (feature test, 2026-10-17).
        if (!before.config.has("widgets_v2")) return@withLock NOT_SUPPORTED
        before.widgets.firstOrNull { it.id == DashboardWire.CARD_ID }?.let { if (it.type != "a2ui") return@withLock "Đã có một thẻ cùng id không thuộc app này; không thay đổi gì" }
        val (r, error) = request("widget_install", install)
        if (error != null) return@withLock "Gửi thất bại: $error"
        if (r == null) return@withLock "Không có phản hồi khi cài thẻ; hãy xem bảng điều khiển trên kính (không gửi lại)"
        if (r.optInt("code", -1) != 0) return@withLock "Kính từ chối thẻ (mã ${r.opt("code")})"
        val (after, why2) = readLocked(); after ?: return@withLock "Thẻ đã được nhận nhưng đọc lại thất bại: $why2"
        val own = after.widgets.firstOrNull { it.id == DashboardWire.CARD_ID }
        val outcome = when {
            own == null || own.type != "a2ui" -> "Thẻ đã được nhận nhưng không thấy khi đọc lại"
            !DashboardWire.preserves(before.config, after.config) -> "Đã cài thẻ nhưng các mục khác trên bảng đã thay đổi; hãy kiểm tra trên kính"
            else -> "Thẻ trên bảng, các thẻ khác được giữ nguyên (xem trên kính)"
        }
        DiagnosticLog.i("PAINEL", outcome); outcome
    }

    suspend fun removeCard(): String = lock.withLock {
        val (before, why) = readLocked(); before ?: return@withLock why
        if (!before.hasCard) return@withLock "Thẻ của app không có trên bảng"
        val (r, error) = request("widget_uninstall", DashboardWire.UNINSTALL)
        if (error != null) return@withLock "Gửi thất bại: $error"
        if (r == null) return@withLock "Không có phản hồi khi gỡ thẻ; hãy xem bảng điều khiển trên kính"
        if (r.optInt("code", -1) != 0) return@withLock "Kính từ chối gỡ thẻ (mã ${r.opt("code")})"
        val (after, why2) = readLocked(); after ?: return@withLock "Đã nhận lệnh gỡ nhưng đọc lại thất bại: $why2"
        val outcome = if (!after.hasCard && DashboardWire.preserves(before.config, after.config)) "Đã gỡ thẻ; các thẻ khác được giữ nguyên"
            else "Đã nhận lệnh gỡ nhưng đọc lại không khớp; hãy kiểm tra trên kính"
        DiagnosticLog.i("PAINEL", outcome); outcome
    }

    /**
     * Dashboard type (simple/complete): sends the fresh configuration back with only simple_mode changed, then reads
     * back. [remember] receives the value read before the first change, for "restore".
     */
    suspend fun setSimpleMode(on: Boolean, remember: (Boolean) -> Unit = {}): String = lock.withLock {
        val (before, why) = readLocked(); before ?: return@withLock why
        val current = before.simple ?: return@withLock "Kính không cho biết kiểu bảng điều khiển (simple_mode); không thay đổi gì"
        if (current == on) return@withLock "Bảng điều khiển đã ở kiểu ${if (on) "simples" else "đầy đủ"}"
        remember(current)
        val (r, error) = request("dashboard_update", DashboardWire.update(DashboardWire.withSimpleMode(before.config, on)))
        if (error != null) return@withLock "Gửi thất bại: $error"
        if (r == null) return@withLock "Không có phản hồi khi đổi; hãy xem bảng điều khiển trên kính"
        val value = r.optJSONObject("payload")?.optInt("value", -1) ?: r.optInt("code", -1)
        if (value != 0) return@withLock "Kính từ chối thay đổi (giá trị $value)"
        val (after, why2) = readLocked(); after ?: return@withLock "Đã nhận thay đổi nhưng đọc lại thất bại: $why2"
        val outcome = when {
            after.simple != on -> "Kính đã nhận nhưng kiểu đọc lại vẫn là ${if (current) "simples" else "đầy đủ"}"
            !DashboardWire.preserves(before.config, after.config, ignoring = setOf("simple_mode")) -> "Đã đổi kiểu nhưng các mục khác trên bảng đã thay đổi; hãy kiểm tra trên kính"
            else -> "Bảng điều khiển giờ ở kiểu ${if (on) "simples" else "đầy đủ"} (xem trên kính)"
        }
        DiagnosticLog.i("PAINEL", outcome); outcome
    }

    /** Native cards shown on the dashboard, in this order (ids of [DashboardWire.NATIVE]); read back to verify. */
    suspend fun setWidgets(ids: List<Int>): String = lock.withLock {
        if (ids.isEmpty() || ids.size > DashboardWire.NATIVE.size || ids.distinct() != ids || ids.any { it !in DashboardWire.NATIVE }) return@withLock "Hãy chọn ít nhất một thẻ"
        val (before, why) = readLocked(); before ?: return@withLock why
        if (before.config.has("widgets_v2") || before.config.optJSONArray("widgets") == null) return@withLock "Kính này không dùng danh sách thẻ dạng số; không thay đổi gì"
        val (r, error) = request("dashboard_update", DashboardWire.update(DashboardWire.withWidgets(before.config, ids)))
        if (error != null) return@withLock "Gửi thất bại: $error"
        if (r == null) return@withLock "Không có phản hồi khi đổi; hãy xem bảng điều khiển trên kính"
        val value = r.optJSONObject("payload")?.optInt("value", -1) ?: r.optInt("code", -1)
        if (value != 0) return@withLock "Kính từ chối thay đổi (giá trị $value)"
        val (after, why2) = readLocked(); after ?: return@withLock "Đã nhận thay đổi nhưng đọc lại thất bại: $why2"
        val now = after.widgets.map { it.id }
        val outcome = if (now == ids.map { it.toString() }) "Bảng điều khiển gồm: ${ids.joinToString(", ") { DashboardWire.NATIVE[it]!! }} (xem trên kính)"
            else "Kính đã nhận nhưng danh sách đọc lại là ${now.joinToString(", ")}"
        DiagnosticLog.i("PAINEL", outcome); outcome
    }
}
