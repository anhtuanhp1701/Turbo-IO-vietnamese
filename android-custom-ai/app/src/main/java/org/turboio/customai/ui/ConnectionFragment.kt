package org.turboio.customai.ui

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.turboio.customai.BuildConfig
import org.turboio.customai.TurboApp
import org.turboio.customai.ble.GlassesScanner
import org.turboio.customai.ble.Phase
import org.turboio.customai.ble.PrefsPeerStore
import org.turboio.customai.ble.SessionStatus
import org.turboio.customai.ble.bluetoothAdapter

class ConnectionFragment : Fragment() {
    private val app get() = TurboApp.instance
    private lateinit var phase: TextView
    private lateinit var note: TextView
    private lateinit var details: TextView
    private lateinit var bluetooth: TextView
    private lateinit var candidates: LinearLayout
    private lateinit var scanStatus: TextView
    private var scanner: GlassesScanner? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        ui.header("Kính", "Kết nối, thông báo và cài đặt")
        val status = ui.card()
        status.title("Trạng thái kính")
        phase = status.text(size = 24f).apply { typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL) }
        note = status.text()
        details = status.text(size = 13f)
        bluetooth = status.text(size = 13f)

        ui.section("Kết nối")
        val g1 = ui.card()
        g1.hint("Trước lần kết nối đầu tiên: (1) trong app RayNeo chính thức, hủy liên kết kính; (2) trong Cài đặt > Bluetooth của Android, " +
            "quên thiết bị RayNeo; (3) giữ nút trên kính khoảng 5 giây đến khi đèn LED xanh nhấp nháy. " +
            "Firmware không bị thay đổi ở bất kỳ bước nào.")
        g1.button("Tìm kính (15 giây)") { scan() }
        scanStatus = g1.hint("")
        candidates = g1.row().apply { orientation = LinearLayout.VERTICAL }
        g1.buttons("Kết nối lại" to {
            if (permitted() && !app.connectSaved()) toast("Chưa lưu kính nào; hãy tìm và chọn kính trước")
        }, "Ngắt kết nối" to { app.disconnect() })
        g1.button("Quên kính (quay lại app chính thức)", filled = false) {
            AlertDialog.Builder(requireContext()).setTitle("Quên kính?")
                .setMessage("Định danh đã lưu sẽ bị xóa. Để quay lại app RayNeo chính thức: xóa ghép đôi trong Cài đặt > Bluetooth, " +
                    "đưa kính vào chế độ ghép đôi và liên kết lại trong app chính thức.")
                .setPositiveButton("Quên") { _, _ -> app.session.forget(); org.turboio.customai.service.ConnectionService.stop(requireContext()) }
                .setNegativeButton("Hủy", null).show()
        }
        g1.switch("Giữ kết nối khi tắt màn hình (dịch vụ nền)", app.settings.keepConnected) { app.settings.keepConnected = it }

        glassesCard(ui)
        ui.section("Phiên bản")
        val g2 = ui.card()
        g2.text(versionText(), size = 13f)
        return ui.scroll()
    }

    private lateinit var settingsText: TextView
    private var height = 0
    private var distance = 0
    private lateinit var heightSpinner: android.widget.Spinner
    private lateinit var distanceSpinner: android.widget.Spinner
    private var heightChoices: List<Int> = emptyList()
    private var distanceChoices: List<Int> = emptyList()
    private var displayRead: Pair<Int?, Int?>? = null
    private lateinit var wakeSpinner: android.widget.Spinner
    private var wakeChoices: List<Int> = emptyList()
    private var wakeDegree = 20

    /** Settings the official app changes, limited to evidence-backed values (docs/AJUSTES_DOS_OCULOS.md). */
    private fun glassesCard(ui: Column) {
        val c = ui.card()
        c.title("Cài đặt màn hình của kính")
        c.hint("Cùng các cài đặt và giới hạn như app chính thức (độ cao 1–6, độ sâu 1–3). App đọc giá trị hiện tại trước khi đổi, " +
            "kiểm tra lại sau đó và lưu giá trị gốc để khôi phục.")
        settingsText = c.text("", size = 13f)
        fun done(msg: String?) = view?.post { android.widget.Toast.makeText(requireContext(), msg ?: "Xong — hãy kiểm tra trên kính", android.widget.Toast.LENGTH_SHORT).show() }
        c.button("Đọc cài đặt kính", filled = false) { app.glassesSettings.read { done(it ?: "Đã đọc cài đặt") } }
        c.text("Độ cao chữ", size = 13f)
        heightSpinner = c.spinner(listOf(0), 0, { "" }) {}
        c.text("Độ sâu (khoảng cách)", size = 13f)
        distanceSpinner = c.spinner(listOf(0), 0, { "" }) {}
        refreshDisplayChoices()
        c.button("Áp dụng độ cao và khoảng cách") { app.glassesSettings.setDisplay(height, distance) { done(it) } }
        val actions = org.turboio.customai.device.SettingsWire.CROWN_ACTIONS.entries.toList()
        val longActions = org.turboio.customai.device.SettingsWire.LONG_PRESS_ACTIONS.entries.toList()
        var doubleAction = app.glassesSettings.state.value.config.crownDouble ?: 8
        var longAction = app.glassesSettings.state.value.config.crownLongPress ?: 0
        c.text("Nút (núm xoay): chạm hai lần", size = 13f)
        c.spinner(actions, actions.indexOfFirst { it.key == doubleAction }.coerceAtLeast(0), { it.value }) { doubleAction = it.key }
        c.button("Áp dụng chạm hai lần", filled = false) { app.glassesSettings.setCrown(double = doubleAction) { done(it) } }
        c.text("Nút (núm xoay): giữ", size = 13f)
        c.spinner(longActions, longActions.indexOfFirst { it.key == longAction }.coerceAtLeast(0), { it.value }) { longAction = it.key }
        c.button("Áp dụng giữ", filled = false) { app.glassesSettings.setCrown(longPress = longAction) { done(it) } }
        val directions = org.turboio.customai.device.SettingsWire.CROWN_DIRECTIONS.entries.toList()
        var direction = app.glassesSettings.state.value.config.crownDirection ?: 0
        c.text("Chiều xoay của núm", size = 13f)
        c.spinner(directions, directions.indexOfFirst { it.key == direction }.coerceAtLeast(0), { it.value.replaceFirstChar { ch -> ch.uppercase() } }) { direction = it.key }
        c.button("Áp dụng chiều xoay", filled = false) { app.glassesSettings.setCrown(direction = direction) { done(it) } }
        c.text("Sáng màn hình khi ngẩng đầu", size = 13f)
        c.buttons("Bật" to { app.glassesSettings.setWake(headup = true) { done(it ?: "Đã bật — ngẩng đầu để sáng màn hình") } },
            "Tắt" to { app.glassesSettings.setWake(headup = false) { done(it ?: "Đã tắt") } })
        wakeSpinner = c.spinner(listOf(0), 0, { "" }) {}
        c.button("Áp dụng góc", filled = false) { app.glassesSettings.setWake(degree = wakeDegree) { done(it ?: "Đã áp dụng góc $wakeDegree° — hãy ngẩng đầu để kiểm tra") } }
        val crownWake = org.turboio.customai.device.SettingsWire.CROWN_WAKE.entries.toList()
        var crownWakeValue = app.glassesSettings.state.value.config.wakeCrown ?: 0
        c.text("Sáng màn hình khi xoay núm", size = 13f)
        c.spinner(crownWake, crownWake.indexOfFirst { it.key == crownWakeValue }.coerceAtLeast(0), { it.value.replaceFirstChar { ch -> ch.uppercase() } }) { crownWakeValue = it.key }
        c.button("Áp dụng", filled = false) { app.glassesSettings.setWake(crown = crownWakeValue) { done(it) } }
        c.hint("Cùng nhóm cài đặt mà kính báo về (wakeupConfig). App đọc lại sau khi đổi; nếu kính không áp dụng sẽ báo cho bạn. " +
            "“Khôi phục cài đặt gốc” cũng trả lại các giá trị này.")
        c.text("Cử chỉ đầu (gật/lắc)", size = 13f)
        c.buttons("Bật" to { app.glassesSettings.setHeadGestures(true) { done(it) } },
            "Tắt" to { app.glassesSettings.setHeadGestures(false) { done(it) } })
        c.button("Khôi phục cài đặt gốc", filled = false) {
            AlertDialog.Builder(requireContext()).setTitle("Khôi phục?").setMessage("Trả độ cao, độ sâu, nút và “ngẩng đầu” về giá trị đầu tiên đọc từ kính.")
                .setPositiveButton("Khôi phục") { _, _ -> app.glassesSettings.restore { done(it ?: "Đã khôi phục") } }.setNegativeButton("Hủy", null).show()
        }
        c.title("Thông báo điện thoại trên kính (thử nghiệm)")
        c.hint("Hiện trên kính thông báo của các app bạn chọn (thẻ thông báo gốc của kính). Nội dung không được lưu " +
            "hay ghi log. Cần cấp quyền đọc thông báo cho Turbo IO trong cài đặt Android.")
        c.button("Cấp quyền đọc thông báo", filled = false) {
            runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        c.switch("Gửi thông báo của các app đã chọn", app.settings.mirrorOn) { app.settings.mirrorOn = it }
        c.switch("Chỉ đường Google Maps trên kính", app.settings.navOn) { app.settings.navOn = it }
        c.button("Chọn app", filled = false) { chooseApps() }
        c.title("Thời tiết trên màn hình chính của kính (thử nghiệm)")
        val city = c.field("Thành phố (vd: Hà Nội)", app.settings.weatherCity)
        c.switch("Cập nhật mỗi giờ khi kính được kết nối", app.settings.weatherOn) { on ->
            app.settings.weatherCity = city.text.toString(); app.settings.weatherOn = on
            org.turboio.customai.device.WeatherWorker.schedule(requireContext(), on)
        }
        c.button("Gửi thời tiết ngay", filled = false) {
            app.settings.weatherCity = city.text.toString()
            val name = app.settings.weatherCity
            if (name.isBlank()) { done("Hãy nhập thành phố"); return@button }
            viewLifecycleOwner.lifecycleScope.launch {
                val r = try {
                    val w = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { org.turboio.customai.device.OpenMeteo.now(name) }
                    app.weather.send(w)
                } catch (e: Exception) { "Thất bại: ${e.message ?: e.javaClass.simpleName}" }
                done(r)
            }
        }
        c.hint("Dữ liệu từ Open-Meteo (miễn phí, không cần khóa). Biểu tượng theo cách đánh số của các app khác; hãy kiểm tra trên kính xem có khớp thời tiết không.")
        c.title("Bảng điều khiển của kính (thử nghiệm)")
        c.hint("Đọc bảng điều khiển, chỉ đổi những gì bạn yêu cầu rồi đọc lại để kiểm tra. Thẻ của app tối đa 20 ký tự " +
            "(vd: “3 việc · 15:00 Gọi”). Kiểu bảng được đổi bằng cách gửi lại cấu hình đã đọc với simple_mode thay đổi.")
        lateinit var boardStatus: TextView
        fun board(action: suspend () -> String) {
            boardStatus.text = "Đang chờ kính…"
            viewLifecycleOwner.lifecycleScope.launch { boardStatus.text = action() }
        }
        c.buttons("Đọc bảng điều khiển" to { board { app.dashboard.read().let { (snap, why) -> snap?.describe() ?: why } } },
            "Hiện thẻ" to { board { app.refreshDashboardCard(force = true) } },
            "Gỡ thẻ" to { board { app.dashboard.removeCard() } })
        c.switch("Cập nhật thẻ khi danh sách việc thay đổi", app.settings.dashboardCard) { on ->
            app.settings.dashboardCard = on; if (on) app.memoryChanged()
        }
        c.buttons("Bảng đơn giản" to { board { app.dashboard.setSimpleMode(true) { if (app.settings.dashboardSimpleOriginal == null) app.settings.dashboardSimpleOriginal = it } } },
            "Bảng đầy đủ" to { board { app.dashboard.setSimpleMode(false) { if (app.settings.dashboardSimpleOriginal == null) app.settings.dashboardSimpleOriginal = it } } },
            "Khôi phục kiểu" to {
                val original = app.settings.dashboardSimpleOriginal
                if (original == null) done("App chưa đổi kiểu bảng điều khiển")
                else board { app.dashboard.setSimpleMode(original).also { r -> if (r.startsWith("Bảng điều khiển giờ") || r.startsWith("Bảng điều khiển đã")) app.settings.dashboardSimpleOriginal = null } }
            })
        c.button("Chọn thẻ cho bảng điều khiển", filled = false) {
            val all = org.turboio.customai.device.DashboardWire.NATIVE.entries.toList()
            viewLifecycleOwner.lifecycleScope.launch {
                val (snap, why) = app.dashboard.read()
                if (snap == null) { boardStatus.text = why; return@launch }
                val current = snap.widgets.mapNotNull { it.id.toIntOrNull() }
                val chosen = BooleanArray(all.size) { all[it].key in current }
                AlertDialog.Builder(requireContext()).setTitle("Các thẻ trên bảng điều khiển")
                    .setMultiChoiceItems(all.map { it.value }.toTypedArray(), chosen) { _, i, on -> chosen[i] = on }
                    .setPositiveButton("Áp dụng") { _, _ ->
                        // Keep the current order for cards already shown, new ones at the end.
                        val picked = all.filterIndexed { i, _ -> chosen[i] }.map { it.key }
                        val ids = current.filter { it in picked } + picked.filter { it !in current }
                        board { app.dashboard.setWidgets(ids) }
                    }.setNegativeButton("Hủy", null).show()
            }
        }
        c.hint("Cổ phiếu, email và đồng hồ thế giới cần dữ liệu do app chính thức gửi; app này hiện chỉ gửi thời tiết.")
        boardStatus = c.text(size = 13f)
        c.title("“Hey RayNeo”")
        c.hint("Bật hoặc tắt từ đánh thức bằng cùng lệnh với app chính thức. Khi nói “Hey RayNeo”, trợ lý của kính " +
            "sẽ do app này trả lời (bằng AI của bạn). Khi bật có thể tốn pin hơn.")
        c.buttons("Bật" to { app.glassesSettings.setWakeWord(true) { done(it ?: "Đã bật — hãy nói “Hey RayNeo”") } },
            "Tắt" to { app.glassesSettings.setWakeWord(false) { done(it ?: "Đã tắt") } })
        c.title("Cảnh báo trên kính")
        c.switch("Lời nhắc và thông báo dạng thông báo gốc (thử nghiệm)", app.settings.nativeAlerts) { app.settings.nativeAlerts = it }
        c.hint("Dùng thẻ thông báo của chính kính (bật công tắc thông báo của kính). Nếu kính không phản hồi, " +
            "cảnh báo sẽ hiện dạng chữ trên kính. Khi không đeo hoặc ở chế độ “không làm phiền”, sẽ không hiện gì.")
        c.button("Thử thông báo gốc", filled = false) {
            viewLifecycleOwner.lifecycleScope.launch {
                val r = app.nativeNotifier.show("Thử", "Turbo IO: thông báo gốc đang hoạt động.")
                done("Kết quả: ${r.label}")
            }
        }
    }

    private fun chooseApps() {
        val pm = requireContext().packageManager
        fun installed(pkg: String) = runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
        val Policy = org.turboio.customai.device.MirrorPolicy
        val rows = Policy.choices(::installed, app.settings.notifyingApps, app.settings.mirrorApps)
        if (rows.isEmpty()) {
            AlertDialog.Builder(requireContext()).setTitle("Không tìm thấy app nào")
                .setMessage("Chưa cài app quen thuộc nào. Hãy cấp quyền đọc thông báo và chờ có thông báo tới; các app sẽ hiện ở đây.")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = rows.map { r ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(r.pkg, 0)).toString() }.getOrNull()
            "${Policy.name(r.pkg, label)} · ${r.category.label}"
        }
        val initial = if (app.settings.mirrorAppsChosen) app.settings.mirrorApps else Policy.defaults(rows)
        val chosen = BooleanArray(rows.size) { rows[it].pkg in initial }
        AlertDialog.Builder(requireContext()).setTitle(if (app.settings.mirrorAppsChosen) "Các app được gửi lên kính" else "Các app được gửi lên kính (gợi ý)")
            .setMultiChoiceItems(labels.toTypedArray(), chosen) { _, i, on -> chosen[i] = on }
            .setPositiveButton("Lưu") { _, _ -> app.settings.mirrorApps = rows.filterIndexed { i, _ -> chosen[i] }.map { it.pkg }.toSet() }
            .setNegativeButton("Hủy", null).show()
    }

    /** Height/distance lists (official ranges) and look-up angles. */
    private fun refreshDisplayChoices() {
        val gs = app.glassesSettings; val c = gs.state.value.config
        val heights = gs.heights()
        val distances = gs.distances()
        val degrees = (org.turboio.customai.device.SettingsWire.WAKE_DEGREES + listOfNotNull(c.wakeDegree)).distinct().sorted()
        if (::wakeSpinner.isInitialized && degrees != wakeChoices) {
            wakeChoices = degrees
            wakeSpinner.adapter = darkAdapter(requireContext(), degrees.map { "Góc $it°" + if (it == c.wakeDegree) " (hiện tại)" else "" })
            wakeSpinner.setSelection(degrees.indexOf(c.wakeDegree ?: 20).coerceAtLeast(0))
            wakeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { wakeDegree = degrees[pos] }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        if (heights == heightChoices && distances == distanceChoices && displayRead == (c.height to c.distance)) return
        heightChoices = heights; distanceChoices = distances; displayRead = c.height to c.distance
        fun bind(spinner: android.widget.Spinner, items: List<Int>, label: (Int) -> String, current: Int?, set: (Int) -> Unit) {
            spinner.adapter = darkAdapter(requireContext(), items.map(label))
            spinner.setSelection(items.indexOf(current).coerceAtLeast(0))
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = set(items[pos])
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        bind(heightSpinner, heights, { "Độ cao $it" + if (it == c.height) " (hiện tại)" else "" }, c.height) { height = it }
        bind(distanceSpinner, distances, { d -> "Độ sâu $d" + when (d) { 1 -> " (gần)"; 3 -> " (xa)"; else -> "" } + if (d == c.distance) " (hiện tại)" else "" }, c.distance) { distance = it }
    }

    private fun renderSettings(s: org.turboio.customai.device.SettingsState) {
        if (!::settingsText.isInitialized) return
        refreshDisplayChoices()
        val c = s.config
        settingsText.text = buildString {
            if (c.readAt > 0) append("Từ đánh thức: ${when (c.wakeup) { true -> "ligada"; false -> "desligada"; null -> "não informada" }}\n")
            val actions = org.turboio.customai.device.SettingsWire.CROWN_ACTIONS
            append(if (c.readAt > 0) "Hiện tại: độ cao ${c.height ?: "?"} · độ sâu ${c.distance ?: "?"} · chạm hai lần: ${c.crownDouble?.let { actions[it] ?: "ação $it" } ?: "?"}" +
                " · giữ: ${c.crownLongPress?.let { actions[it] ?: "ação $it" } ?: "?"}"
                else "Chưa đọc")
            if (c.hasWake) append("\nNgẩng đầu sáng màn hình: ${if (c.wakeHeadup == 1) "đang bật" else "đã tắt"} · góc ${c.wakeDegree}° · " +
                "nút: ${org.turboio.customai.device.SettingsWire.CROWN_WAKE[c.wakeCrown] ?: "modo ${c.wakeCrown}"}")
            if (c.headEnabled != null) append("\nCử chỉ đầu: ${if (c.headEnabled == true) "ligados" else "desligados"} (chế độ ${c.headMode ?: "?"})")
            if (c.identity.isNotEmpty()) append("\nĐịnh danh: ${c.identity}")
            if (s.details.isNotEmpty()) append("\nGiá trị đọc được: ${s.details}")
            s.original?.let { o -> append("\nGốc: độ cao ${o.height ?: "?"} · khoảng cách ${o.distance ?: "?"} · chạm hai lần ${o.crownDouble ?: "?"}") }
            append("\n").append(s.note)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.glassesSettings.state.collect { renderSettings(it) } }
                app.status.collect { render(it) }
            }
        }
    }

    override fun onStop() { scanner?.stop(); super.onStop() }

    private fun render(s: SessionStatus) {
        val ok = when (s.phase) { Phase.READY -> true; Phase.FAILED -> false; else -> null }
        phase.text = phaseLabel(s.phase); phase.setTextColor(statusColor(ok))
        note.text = s.note
        details.text = buildString {
            append("Pin: ${if (s.battery >= 0) "${s.battery}%" else "—"} · MTU: ${s.mtu}")
            if (s.protocolVersion >= 0) append(" · Giao thức: v${s.protocolVersion}")
            if (s.glassesVersions.isNotEmpty()) append("\nKính: ${s.glassesVersions}")
            if (s.readySince > 0) append("\nĐã kết nối ${(android.os.SystemClock.elapsedRealtime() - s.readySince) / 1000} giây")
            if (s.lastReason.isNotEmpty()) append("\nSự kiện gần nhất: ${s.lastReason}")
            val saved = PrefsPeerStore(requireContext()).load()
            append("\nKính đã lưu: ${saved?.name ?: "không có"}")
        }
        val adapter = bluetoothAdapter(requireContext())
        bluetooth.text = "Bluetooth của điện thoại: " + when {
            adapter == null -> "không khả dụng"
            !adapter.isEnabled -> "ĐANG TẮT"
            else -> "đang bật"
        } + if (!(activity as MainActivity).bluetoothPermitted()) " · còn thiếu quyền" else ""
    }

    private fun permitted(): Boolean {
        val activity = activity as MainActivity
        if (!activity.bluetoothPermitted()) { activity.requestPermissionsIfNeeded(); toast("Hãy cấp quyền Bluetooth"); return false }
        return true
    }

    private fun scan() {
        if (!permitted()) return
        candidates.removeAllViews()
        val s = scanner ?: GlassesScanner(requireContext()).also { scanner = it }
        val error = s.start { list, running ->
            if (!isAdded) return@start
            scanStatus.text = if (running) "Đang tìm… ${list.size} kính" else "Đã tìm xong · ${list.size} kính (chưa xác thực)"
            candidates.removeAllViews()
            val column = Column(requireContext())
            list.sortedByDescending { it.rssi }.forEach { c ->
                val other = c.deviceType != null && !org.turboio.customai.ble.RayNeoModel.isIo(c.deviceType)
                column.button("${c.name} · ${c.model}${if (other) " — este app é só para o iO" else ""} · sinal ${c.rssi} dBm", filled = false) {
                    if (other) { android.widget.Toast.makeText(requireContext(), "App này chỉ dành cho RayNeo iO; không hỗ trợ ${c.model}", android.widget.Toast.LENGTH_LONG).show(); return@button }
                    s.stop(); app.connect(c.address, c.name)
                }
            }
            candidates.addView(column.root)
        }
        if (error != null) scanStatus.text = error
    }

    private fun versionText(): String {
        val official = try {
            val info = requireContext().packageManager.getPackageInfo("com.rayneo.venus", 0)
            "đã cài · phiên bản ${info.versionName}"
        } catch (_: Exception) { "không tìm thấy (com.rayneo.venus)" }
        return "Turbo IO IA: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "App RayNeo chính thức: $official\n" +
            "Firmware của kính: app này không bao giờ sửa đổi (không OTA)."
    }

    private fun toast(text: String) = android.widget.Toast.makeText(requireContext(), text, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        fun phaseLabel(p: Phase) = when (p) {
            Phase.IDLE -> "Chưa kết nối"
            Phase.CONNECTING -> "Đang kết nối…"
            Phase.AUTHENTICATING -> "Đang xác thực…"
            Phase.PAIRING -> "Đang ghép đôi…"
            Phase.INITIALIZING -> "Đang khởi tạo…"
            Phase.READY -> "Đã kết nối"
            Phase.RETRYING -> "Đang kết nối lại…"
            Phase.FAILED -> "Lỗi"
        }
    }
}
