package org.turboio.customai.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.turboio.customai.ble.BusinessListener
import org.turboio.customai.ble.GlassesPort
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.display.sendEnvelope
import org.turboio.customai.protocol.Business
import org.turboio.customai.protocol.Envelope
import org.turboio.customai.protocol.Json

/**
 * Glasses settings the official app changes, limited to commands and value ranges with evidence in this repository
 * (apps/RayNeoCompanion CompanionDeviceFeatures.swift): display height {1,3,5} and distance {1,2}, and the crown
 * double-tap action (8 = open To-do). Every write first reads the current values, sends the untouched keys back as
 * they were, and the first values ever read are kept so they can be restored.
 */
object SettingsWire {
    const val READ = """{"cmd":"request_general_settings","payload":{"value":0,"mode":0,"data":""}}"""
    fun display(height: Int, distance: Int) = command("display_config", """{"height":$height,"distance":$distance}""")
    /** Official app 1.0.7: the rotation [direction] goes in payload.value (sent back exactly as read), the actions in data. */
    fun crown(double: Int, longPress: Int, direction: Int? = null) = command("crown_config", """{"double":$double,"longPress":$longPress}""", value = direction ?: 0)
    /** Screen wake: read as camelCase (wakeupConfig), written in snake_case like the official app; all three keys always sent. */
    fun wake(headup: Int, degree: Int, crown: Int) = command("wakeup_config", """{"headup_switch":$headup,"headup_degree":$degree,"crown_switch":$crown}""")
    /** Look-up angles offered (degrees); the value read from the glasses is always offered too. */
    val WAKE_DEGREES = listOf(10, 15, 20, 25, 30, 35, 40, 50, 60)
    /** The official setter clamps the angle to 0–90. */
    val WAKE_DEGREE_RANGE = 0..90
    /** crown_switch of wakeup_config: the official "wake on crown rotation" page offers off, marble (3) and poke (2). */
    val CROWN_WAKE = linkedMapOf(0 to "đã tắt", 3 to "chế độ marble", 2 to "chế độ poke")
    /** Rotation direction of the crown (payload.value of crown_config): standard 0, natural 1. */
    val CROWN_DIRECTIONS = linkedMapOf(0 to "mặc định", 1 to "natural")

    /** Head gestures on/off keeping the mode read from the glasses (value + mode, empty data). */
    fun head(enabled: Boolean, mode: Int) = "{\"cmd\":\"head_gestures\",\"payload\":{\"value\":${if (enabled) 1 else 0},\"mode\":$mode,\"data\":\"\"}}"

    /** Glasses clock (15/5 sync_time): Unix seconds, UTC offset in seconds and the time-zone id, as the official app sends. */
    fun syncTime(nowMs: Long, zone: java.time.ZoneId): String {
        val offset = zone.rules.getOffset(java.time.Instant.ofEpochMilli(nowMs)).totalSeconds
        return command("sync_time", "{\"utc\":${nowMs / 1000},\"offset\":$offset,\"timezone\":${Json.quote(zone.id)}}")
    }

    private fun command(cmd: String, data: String, value: Int = 0) = "{\"cmd\":\"$cmd\",\"payload\":{\"value\":$value,\"mode\":0,\"data\":${Json.quote(data)}}}"

    /** Ranges of the official setter (display_config: height 1–6, distance 1–3). */
    val HEIGHT_RANGE = 1..6
    val DISTANCE_RANGE = 1..3
    val HEIGHTS = HEIGHT_RANGE.toList()
    val DISTANCES = DISTANCE_RANGE.toList()

    /**
     * Crown actions of the official app's common screen (CrownDoubleTapAction / CrownLongPressAction). Double-tap
     * LifeLog (6) exists in the enum but is not confirmed for the iO, so it is not offered (a value read is kept).
     */
    val CROWN_ACTIONS = linkedMapOf(0 to "Hỏi AI", 1 to "Bảng điều khiển", 2 to "Tập trung", 3 to "Máy ghi âm", 4 to "Phụ đề", 5 to "Máy nhắc chữ",
        8 to "To-do", 9 to "Gợi ý trực tiếp", 7 to "Không có")
    val LONG_PRESS_ACTIONS = CROWN_ACTIONS
}

/** Height/distance values these glasses reported themselves (e.g. after the user picked a level in the official app). */
interface LearnedDisplay {
    fun heights(): Set<Int>
    fun distances(): Set<Int>
    fun add(height: Int?, distance: Int?)
    object None : LearnedDisplay { override fun heights() = emptySet<Int>(); override fun distances() = emptySet<Int>(); override fun add(height: Int?, distance: Int?) {} }
}

data class GlassesConfig(val height: Int? = null, val distance: Int? = null, val crownDouble: Int? = null, val crownLongPress: Int? = null, val readAt: Long = 0,
                         /** Voice wake-up (“Hey RayNeo”) if the glasses report it; key name not documented, so several are tried. */
                         val wakeup: Boolean? = null, val keys: String = "",
                         /** Head gestures (screen on when looking up): on/off and mode, as the glasses report them. */
                         val headEnabled: Boolean? = null, val headMode: Int? = null,
                         /** Screen wake (Strix OS 1.0.5.5 "wakeupConfig"): look up to wake, its angle in degrees, button press wakes. */
                         val wakeHeadup: Int? = null, val wakeDegree: Int? = null, val wakeCrown: Int? = null,
                         val crownDirection: Int? = null,
                         /** Identity fields of generalSettings (firmwareVersion, deviceType, osVersion, productModel, productCode). */
                         val identity: String = "") {
    val hasWake get() = wakeHeadup != null && wakeDegree != null && wakeCrown != null
    val hasDisplay get() = height != null && distance != null
    val hasCrown get() = crownDouble != null && crownLongPress != null

    companion object {
        private fun int(o: JSONObject?, key: String): Int? = o?.opt(key)?.let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() }
        private fun obj(root: JSONObject, vararg keys: String): JSONObject? = keys.firstNotNullOfOrNull { k ->
            root.optJSONObject(k) ?: root.optString(k).takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        }

        /** Reply to request_general_settings (15/4): {"generalSettings":{…}} or the settings at the root. */
        fun parse(json: JSONObject, at: Long): GlassesConfig {
            val root = json.optJSONObject("generalSettings") ?: json
            val display = obj(root, "displayConfig", "display_config")
            val crown = obj(root, "crownConfig", "crown_config")
            val head = obj(root, "headGestures", "head_gestures")
            val wake = obj(root, "wakeupConfig", "wakeup_config")
            return GlassesConfig(int(display, "height"), int(display, "distance"), int(crown, "double"), int(crown, "longPress"), at,
                wakeup(root), root.keys().asSequence().toList().sorted().joinToString(","),
                int(head, "enabled")?.let { it != 0 }, int(head, "mode"),
                int(wake, "headupSwitch"), int(wake, "headupDegree"), int(wake, "crownSwitch"), int(crown, "direction"),
                IDENTITY.mapNotNull { k -> root.optString(k).takeIf { it.isNotBlank() }?.let { "$k=${it.take(40)}" } }.joinToString(" · "))
        }

        private val IDENTITY = listOf("productModel", "productCode", "deviceType", "osVersion", "firmwareVersion")
        private val WAKE_KEYS = setOf("voiceWakeup", "voice_wakeup", "ai_voice_wakeup", "voiceWakeUp", "aiVoiceWakeup", "wakeup", "wakeUp")

        /** First boolean-ish value under a wake-up key, searched one level deep. */
        fun wakeup(o: JSONObject): Boolean? {
            for (k in o.keys()) {
                val v = o.opt(k)
                if (k in WAKE_KEYS) when (v) { is Boolean -> return v; is Number -> return v.toInt() != 0 }
                // "wakeupConfig" is the SCREEN wake (look up / button), not the voice wake word.
                if (v is JSONObject && k != "wakeupConfig" && k != "headGestures") wakeup(v)?.let { return it }
            }
            return null
        }
    }
}

private val DETAIL_KEYS = listOf("displayConfig", "crownConfig", "headGestures", "wakeupConfig", "privacyConfig", "autoLockTime", "language")

/** Where the first values read from the glasses are kept (for "restore"). */
interface OriginalSettings { fun load(): GlassesConfig?; fun save(c: GlassesConfig) }

data class SettingsState(val config: GlassesConfig = GlassesConfig(), val original: GlassesConfig? = null, val note: String = "Bấm “Đọc cài đặt kính”", val busy: Boolean = false,
                         /** Raw setting groups as read (no serial numbers), for diagnosis. */
                         val details: String = "")

class GlassesSettings(
    private val port: GlassesPort,
    private val scope: CoroutineScope,
    private val originals: OriginalSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val learned: LearnedDisplay = LearnedDisplay.None,
) : BusinessListener {
    /** Selectable values: the official app's ranges. */
    fun heights(): List<Int> = SettingsWire.HEIGHTS
    fun distances(): List<Int> = SettingsWire.DISTANCES

    private val _state = MutableStateFlow(SettingsState(original = originals.load()))
    val state: StateFlow<SettingsState> = _state
    private var settingsReply: CompletableDeferred<JSONObject>? = null
    private var commandReply: CompletableDeferred<Pair<Int, JSONObject>>? = null
    private var pendingCmd = ""

    init { port.addBusinessListener(this) }

    override fun onBusiness(business: Int, envelope: Envelope) {
        if (business != Business.LAUNCHER) return
        val j = runCatching { JSONObject(envelope.jsonText()) }.getOrNull() ?: return
        if (envelope.type == 4) { settingsReply?.complete(j); return }
        if (envelope.type in setOf(3, 6, 16, 17, 19) && j.optString("cmd") == pendingCmd && pendingCmd.isNotEmpty()) commandReply?.complete(envelope.type to j)
        if (envelope.type == 17) DiagnosticLog.i("AJUSTES", "15/17: ${j.toString().take(200)}")
    }

    private fun note(text: String, busy: Boolean = false) { _state.value = _state.value.copy(note = text, busy = busy); DiagnosticLog.i("AJUSTES", text) }

    fun read(done: (String?) -> Unit = {}) = scope.launch { done(readNow()) }

    private suspend fun readNow(): String? {
        if (!port.isReady) return "Hãy kết nối kính".also { note(it) }
        note("Đang đọc cài đặt…", busy = true)
        val waiter = CompletableDeferred<JSONObject>().also { settingsReply = it }
        port.sendEnvelope(Business.LAUNCHER, 4, SettingsWire.READ)?.let { settingsReply = null; return "Gửi thất bại: $it".also { m -> note(m) } }
        val reply = withTimeoutOrNull(6000) { waiter.await() }
        settingsReply = null
        if (reply == null) return "Kính không phản hồi (6 giây)".also { note(it) }
        val config = GlassesConfig.parse(reply, clock())
        if (config.hasDisplay) learned.add(config.height?.takeIf { it in SettingsWire.HEIGHT_RANGE }, config.distance?.takeIf { it in SettingsWire.DISTANCE_RANGE })
        var original = _state.value.original
        if (original == null || (!original.hasDisplay && config.hasDisplay) || (!original.hasCrown && config.hasCrown) || (!original.hasWake && config.hasWake)) {
            original = GlassesConfig(original?.height ?: config.height, original?.distance ?: config.distance,
                original?.crownDouble ?: config.crownDouble, original?.crownLongPress ?: config.crownLongPress, clock(),
                wakeHeadup = original?.wakeHeadup ?: config.wakeHeadup, wakeDegree = original?.wakeDegree ?: config.wakeDegree,
                wakeCrown = original?.wakeCrown ?: config.wakeCrown)
            originals.save(original)
        }
        val root = reply.optJSONObject("generalSettings") ?: reply
        val details = DETAIL_KEYS.mapNotNull { k -> root.opt(k)?.let { "$k=$it" } }.joinToString(" · ").take(800)
        _state.value = _state.value.copy(config = config, original = original, details = details)
        DiagnosticLog.i("AJUSTES", "chaves dos ajustes: ${config.keys.take(300)}")
        DiagnosticLog.i("AJUSTES", "valores: $details")
        if (config.identity.isNotEmpty()) DiagnosticLog.i("AJUSTES", "identificação: ${config.identity}")
        note(if (config.hasDisplay || config.hasCrown) "Đã đọc cài đặt" else "Kính không cho biết độ cao/khoảng cách hay chạm hai lần")
        return null
    }

    /**
     * Height/distance: always both keys, the one not being changed sent back exactly as read. The result is read back;
     * if the glasses did not apply it, the previous values are written again.
     */
    fun setDisplay(height: Int? = null, distance: Int? = null, done: (String?) -> Unit = {}) = scope.launch {
        var before: GlassesConfig? = null
        val error = write("display_config") { c ->
            if (!c.hasDisplay) return@write null
            before = c
            val h = height ?: c.height!!; val d = distance ?: c.distance!!
            if (h !in SettingsWire.HEIGHT_RANGE || d !in SettingsWire.DISTANCE_RANGE) null else SettingsWire.display(h, d)
        }
        if (error != null) return@launch done(error)
        val after = _state.value.config; val prev = before
        val applied = (height == null || after.height == height) && (distance == null || after.distance == distance)
        if (applied || prev == null) {
            learned.add(height, distance)
            return@launch done(null)
        }
        var msg = "Kính không áp dụng giá trị này (đọc lại: độ cao ${after.height ?: "?"}, khoảng cách ${after.distance ?: "?"})"
        if (after.hasDisplay && (after.height != prev.height || after.distance != prev.distance)) {
            val back = write("display_config") { c -> if (c.hasDisplay) SettingsWire.display(prev.height!!, prev.distance!!) else null }
            msg += if (back == null) "; đã khôi phục giá trị trước đó" else "; không khôi phục được: $back"
        }
        note(msg); done(msg)
    }

    /** Crown double-tap opens the native To-do (8); off restores the double-tap action read originally (or none, 7). */
    fun setDoubleTapTodo(on: Boolean, done: (String?) -> Unit = {}) = scope.launch {
        val original = _state.value.original?.crownDouble
        done(writeCrown(if (on) 8 else original?.takeIf { it != 8 } ?: 7, null))
    }

    /** Crown actions (double tap / long press) and rotation direction; what is not changed is sent back as read. */
    fun setCrown(double: Int? = null, longPress: Int? = null, direction: Int? = null, done: (String?) -> Unit = {}) =
        scope.launch { done(writeCrown(double, longPress, direction)) }

    private suspend fun writeCrown(double: Int?, longPress: Int?, direction: Int? = null): String? {
        if (double != null && double !in SettingsWire.CROWN_ACTIONS) return "Thao tác không hợp lệ"
        if (longPress != null && longPress !in SettingsWire.LONG_PRESS_ACTIONS) return "Thao tác không hợp lệ"
        if (direction != null && direction !in SettingsWire.CROWN_DIRECTIONS) return "Hướng không hợp lệ"
        val error = write("crown_config") { c ->
            if (!c.hasCrown) return@write null
            SettingsWire.crown(double ?: c.crownDouble!!, longPress ?: c.crownLongPress!!, direction ?: c.crownDirection)
        }
        if (error != null) return error
        val c = _state.value.config
        val ok = (double == null || c.crownDouble == double) && (longPress == null || c.crownLongPress == longPress) &&
            (direction == null || c.crownDirection == direction)
        return if (ok) null else "Kính không áp dụng (đọc lại: chạm hai lần ${c.crownDouble ?: "?"}, giữ ${c.crownLongPress ?: "?"})".also { note(it) }
    }

    /**
     * Screen wake (look up to wake, angle, button press). Experimental write of the group the glasses report
     * ("wakeupConfig" → "wakeup_config", same pattern as display_config/crown_config); read back to verify.
     */
    fun setWake(headup: Boolean? = null, degree: Int? = null, crown: Int? = null, done: (String?) -> Unit = {}) = scope.launch {
        val error = write("wakeup_config") { c ->
            if (!c.hasWake) return@write null
            val h = headup?.let { if (it) 1 else 0 } ?: c.wakeHeadup!!
            val d = degree ?: c.wakeDegree!!
            val k = crown ?: c.wakeCrown!!
            if (d !in SettingsWire.WAKE_DEGREE_RANGE || h !in 0..1 || k !in SettingsWire.CROWN_WAKE) null else SettingsWire.wake(h, d, k)
        }
        if (error != null) return@launch done(error)
        val c = _state.value.config
        val ok = (headup == null || c.wakeHeadup == (if (headup) 1 else 0)) && (degree == null || c.wakeDegree == degree) &&
            (crown == null || c.wakeCrown == crown)
        done(if (ok) null else "Kính không áp dụng (đọc lại: ngẩng đầu ${c.wakeHeadup ?: "?"}, góc ${c.wakeDegree ?: "?"}°, nút ${c.wakeCrown ?: "?"})".also { note(it) })
    }

    /** Head gestures (headGestures group; NOT the look-up screen wake). The mode is sent back exactly as read; the result is read back. */
    fun setHeadGestures(enabled: Boolean, done: (String?) -> Unit = {}) = scope.launch {
        val error = write("head_gestures") { c ->
            if (c.headEnabled == null) return@write null
            SettingsWire.head(enabled, c.headMode ?: 0)
        }
        if (error != null) return@launch done(error)
        val after = _state.value.config.headEnabled
        done(if (after == enabled) null else "Kính không áp dụng (đọc lại: ${when (after) { true -> "đang bật"; false -> "đã tắt"; null -> "não informado" }})".also { note(it) })
    }

    /** “Hey RayNeo” on/off: 15/16 set_ai_voice_wakeup with mode 1 (on) or 0 (off), the official app's command; read back afterwards. */
    fun setWakeWord(on: Boolean, done: (String?) -> Unit = {}) = scope.launch {
        if (!port.isReady) return@launch done("Hãy kết nối kính")
        note(if (on) "Đang bật từ đánh thức…" else "Đang tắt từ đánh thức…", busy = true)
        pendingCmd = "set_ai_voice_wakeup"
        val waiter = CompletableDeferred<Pair<Int, JSONObject>>().also { commandReply = it }
        port.sendEnvelope(Business.LAUNCHER, 16, if (on) Business.WAKEUP_ON else Business.WAKEUP_OFF)?.let { pendingCmd = ""; return@launch done("Gửi thất bại: $it".also { m -> note(m) }) }
        val reply = withTimeoutOrNull(6000) { waiter.await() }
        pendingCmd = ""; commandReply = null
        DiagnosticLog.i("AJUSTES", "palavra de ativação ${if (on) "ligar" else "desligar"}: resposta ${reply?.let { "${it.first} ${it.second.toString().take(160)}" } ?: "nenhuma"}")
        readNow()
        val now = _state.value.config.wakeup
        val msg = when {
            now == on -> null
            reply == null -> if (on) "Đã gửi; kính không phản hồi. Hãy nói “Hey RayNeo” để thử" else "Đã gửi; kính không phản hồi"
            else -> null
        }
        note(if (on) "Từ đánh thức: ${if (now == true) "ligada" else "pedido enviado"}" else "Từ đánh thức: ${if (now == false) "desligada" else "pedido enviado"}")
        done(msg)
    }

    fun enableWakeWord(done: (String?) -> Unit = {}) = setWakeWord(true, done)

    /** Glasses clock from the phone (sent after each connection, like the official app on time changes). */
    fun syncTime(done: (String?) -> Unit = {}) = scope.launch {
        if (!port.isReady) return@launch done("Hãy kết nối kính")
        val error = port.sendEnvelope(Business.LAUNCHER, 5, SettingsWire.syncTime(clock(), java.time.ZoneId.systemDefault()))
        DiagnosticLog.i("AJUSTES", if (error == null) "hora enviada aos óculos" else "hora não enviada: $error")
        done(error)
    }

    fun restore(done: (String?) -> Unit = {}) = scope.launch {
        val o = _state.value.original ?: return@launch done("Chưa có gì để khôi phục: chưa đọc cài đặt gốc")
        var error: String? = null
        if (o.hasDisplay && o.height in SettingsWire.HEIGHT_RANGE && o.distance in SettingsWire.DISTANCE_RANGE)
            error = write("display_config") { c -> if (c.hasDisplay) SettingsWire.display(o.height!!, o.distance!!) else null }
        if (error == null && o.hasWake && o.wakeDegree in SettingsWire.WAKE_DEGREE_RANGE)
            error = write("wakeup_config") { c -> if (c.hasWake) SettingsWire.wake(o.wakeHeadup!!, o.wakeDegree!!, o.wakeCrown!!) else null }
        if (error == null && o.hasCrown && (o.crownDouble in SettingsWire.CROWN_ACTIONS || o.crownDouble == 6) && o.crownLongPress in SettingsWire.LONG_PRESS_ACTIONS)
            error = write("crown_config") { c -> if (c.hasCrown) SettingsWire.crown(o.crownDouble!!, o.crownLongPress!!, c.crownDirection) else null }
        done(error)
    }

    /** Fresh read → build from current values → send → wait for the glasses' reply → read back. */
    private suspend fun write(cmd: String, build: (GlassesConfig) -> String?): String? {
        readNow()?.let { return it }
        val json = build(_state.value.config) ?: return "Kính không cho biết cài đặt này; không thay đổi gì".also { note(it) }
        note("Đang gửi $cmd…", busy = true)
        pendingCmd = cmd
        val waiter = CompletableDeferred<Pair<Int, JSONObject>>().also { commandReply = it }
        port.sendEnvelope(Business.LAUNCHER, 5, json)?.let { pendingCmd = ""; return "Gửi thất bại: $it".also { m -> note(m) } }
        val reply = withTimeoutOrNull(6000) { waiter.await() }
        pendingCmd = ""; commandReply = null
        readNow()
        return if (reply == null) "Kính không xác nhận (6 giây); giá trị đọc lại ở trên".also { note(it) }
        else null.also { note("Kính đã xác nhận $cmd; hãy xem kết quả trên kính") }
    }
}
