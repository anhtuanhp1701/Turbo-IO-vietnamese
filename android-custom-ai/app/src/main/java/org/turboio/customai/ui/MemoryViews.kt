package org.turboio.customai.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.turboio.customai.TurboApp
import org.turboio.customai.journal.Days
import org.turboio.customai.journal.Entry
import org.turboio.customai.memory.Categories
import org.turboio.customai.memory.CategoryPolicy
import org.turboio.customai.memory.Item
import org.turboio.customai.memory.ItemStatus
import org.turboio.customai.memory.MemorySchedule
import org.turboio.customai.memory.Person
import org.turboio.customai.memory.Reminders

/** The memory sections of the Diário tab: Pendências, Pessoas, Semana (docs/MEMORIA_E_ASSISTENTE.md). */
class MemoryViews(private val context: Context, private val scope: () -> LifecycleCoroutineScope) {
    private val app get() = TurboApp.instance
    private val pending = Column(context)
    private val people = Column(context)
    private val week = Column(context)
    val pendingView: ScrollView = pending.scroll()
    val peopleView: ScrollView = people.scroll()
    val weekView: ScrollView = week.scroll()

    private fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    private fun io(block: suspend () -> Unit): kotlinx.coroutines.Job = scope().launch { try { block() } catch (e: Exception) { toast("✗ " + TurboApp.friendly(e)) } }

    // ------------------------------------------------------------------ Pendências
    fun loadPending(): kotlinx.coroutines.Job = io {
        val now = System.currentTimeMillis()
        val (open, done) = withContext(Dispatchers.IO) { app.memoryStore.openItems() to app.memoryStore.items(ItemStatus.DONE, 10) }
        val lines = withContext(Dispatchers.IO) { (open + done).associate { it.id to app.memoryQueries.line(it, now) } }
        val names = withContext(Dispatchers.IO) { app.memoryStore.people().associate { it.id to it.name } }
        pending.clear()
        pending.title("Việc cần làm")
        pending.hint("Được tạo tự động từ các cuộc trò chuyện và câu hỏi của bạn. Mỗi việc ghi rõ nguồn gốc. " +
            "Chạm để đánh dấu xong, bỏ hoặc sửa.")
        pending.button("Phân tích trò chuyện ngay") { analyseNow() }
        pending.buttons("Hiện trên kính" to { io { app.showPendingOnLens()?.let { toast(it) } } }, "Thêm" to { add() })
        if (open.isEmpty()) pending.text("Không có việc nào đang mở.", size = 14f)
        val overdue = open.filter { (it.dueAt ?: Long.MAX_VALUE) < now }
        if (overdue.isNotEmpty()) pending.text("⚠ ${overdue.size} việc quá hạn", size = 13f).setTextColor(statusColor(false))
        for (i in open) itemCard(i, lines.getValue(i.id), names[i.personId])
        if (done.isNotEmpty()) {
            pending.title("Mới hoàn thành")
            for (i in done) pending.text("✓ " + lines.getValue(i.id) + (if (i.doneEvidence.isNotBlank()) "\n   “${i.doneEvidence}”" else ""), size = 13f).apply {
                alpha = 0.7f; setOnClickListener { setStatus(i, ItemStatus.OPEN) }
            }
            pending.hint("Chạm vào một việc đã xong để mở lại.")
        }
        settings()
    }

    private fun itemCard(i: Item, line: String, person: String?) {
        val c = pending.card()
        val head = c.text("${i.kind.label}${if (i.owner == "outro") " (do outro)" else ""}", size = 12f)
        head.alpha = 0.7f
        c.text(line, size = 15f).setTypeface(null, Typeface.BOLD)
        val origin = buildString {
            if (i.quote.isNotBlank()) append("“").append(i.quote).append("”")
            if (i.sourceTs > 0) append(if (isEmpty()) "" else " — ").append(Days.label(Days.of(i.sourceTs))).append(" ").append(Days.time(i.sourceTs))
            if (person != null && !line.contains(person)) append(" · ").append(person)
        }
        if (origin.isNotBlank()) c.hint(origin)
        c.buttons("Xong" to { setStatus(i, ItemStatus.DONE) }, "Bỏ" to { setStatus(i, ItemStatus.DISMISSED) }, "Sửa" to { edit(i) })
    }

    private fun setStatus(i: Item, s: ItemStatus): kotlinx.coroutines.Job = io {
        withContext(Dispatchers.IO) { app.memoryStore.setStatus(i.id, s, System.currentTimeMillis(), if (s == ItemStatus.DONE) "đã đánh dấu trong app" else "") }
        if (s == ItemStatus.DISMISSED) toast("Đã bỏ — sẽ không gợi ý lại")
        app.memoryChanged()
        loadPending()
    }

    private fun edit(i: Item) {
        val field = EditText(context).apply { setText(i.text) }
        AlertDialog.Builder(context).setTitle("Sửa việc").setView(field)
            .setPositiveButton("Lưu") { _, _ -> io {
                withContext(Dispatchers.IO) { app.memoryStore.updateItem(i.copy(text = field.text.toString().trim().ifEmpty { i.text }, updatedAt = System.currentTimeMillis())) }
                app.memoryChanged(); loadPending()
            } }.setNegativeButton("Hủy", null).show()
    }

    private fun add() {
        val field = EditText(context).apply { hint = "Vd: gọi cho anh Nam thứ Sáu lúc 10 giờ" }
        AlertDialog.Builder(context).setTitle("Việc mới").setView(field).setPositiveButton("Thêm") { _, _ ->
            val text = field.text.toString().trim()
            if (text.length >= 3) io {
                val item = withContext(Dispatchers.IO) { app.memory.quickItem(text, System.currentTimeMillis()).also { Reminders.sync(app) } }
                toast("Đã ghi: ${item.text}"); app.memoryChanged(); loadPending()
            }
        }.setNegativeButton("Hủy", null).show()
    }

    private fun analyseNow(): kotlinx.coroutines.Job = io {
        toast("Đang phân tích trò chuyện…")
        val now = System.currentTimeMillis(); val today = Days.of(now)
        val run = withContext(Dispatchers.IO) { app.memory.process(listOf(Days.shift(today, -1), today), now) }
        withContext(Dispatchers.IO) { Reminders.sync(app) }
        app.memoryChanged()
        toast("Đã phân tích ${run.blocks} cuộc trò chuyện · ${run.newItems.size} việc mới" + if (run.failed > 0) " · ${run.failed} lỗi" else "")
        loadPending()
    }

    private fun settings() {
        val s = app.settings.memorySettings()
        fun save(change: (org.turboio.customai.memory.MemorySettings) -> org.turboio.customai.memory.MemorySettings) {
            app.settings.saveMemory(change(app.settings.memorySettings())); MemorySchedule.all(context)
        }
        pending.title("Cài đặt bộ nhớ")
        pending.switch("Phân tích trò chuyện và tự động tạo việc cần làm", s.enabled) { v -> save { it.copy(enabled = v) } }
        pending.switch("Báo khi có việc mới", s.notifyNew) { v -> save { it.copy(notifyNew = v) } }
        pending.switch("Hiện việc mới trên kính", s.lensNew) { v -> save { it.copy(lensNew = v) } }
        pending.switch("Gửi việc cần làm vào app To-do của kính (thử nghiệm)", s.glassesTodo) { v -> save { it.copy(glassesTodo = v) }; if (v) app.memoryChanged() }
        pending.hint("Khi kính được kết nối, các việc đang mở (tối đa 30) hiện trong To-do gốc của kính và trong tiện ích trên bảng điều khiển, " +
            "nếu nó có trên bảng của bạn. Hoàn thành ở đó thì cũng hoàn thành ở đây. Không thể xóa mục qua Bluetooth: việc đã xong và đã bỏ được đánh dấu là xong.")
        pending.switch("Xác nhận việc mới trên kính (gật = có, lắc = không; thử nghiệm)", s.nodConfirm) { v -> save { it.copy(nodConfirm = v) } }
        pending.hint("Với mỗi việc tìm thấy trong trò chuyện (tối đa 3 mỗi lần), kính hiện một thẻ. Không trả lời trong 30 giây thì việc được giữ lại.")
        pending.switch("Nhắc trước hạn (thông báo và trên kính)", s.reminders) { v -> save { it.copy(reminders = v) } }
        pending.switch("Tóm tắt buổi sáng", s.morning) { v -> save { it.copy(morning = v) } }
        val morning = pending.field("Giờ tóm tắt buổi sáng (HH:MM)", "%02d:%02d".format(s.morningHour, s.morningMinute))
        pending.switch("Tổng kết tuần (Chủ nhật)", s.weekly) { v -> save { it.copy(weekly = v) } }
        pending.button("Lưu giờ", filled = false) {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").find(morning.text.toString().trim())
            val h = m?.groupValues?.get(1)?.toIntOrNull(); val mi = m?.groupValues?.get(2)?.toIntOrNull()
            if (h == null || mi == null || h > 23 || mi > 59) toast("Dùng HH:MM") else { save { it.copy(morningHour = h, morningMinute = mi) }; toast("Đã lưu") }
        }
        pending.title("Quyền riêng tư theo loại trò chuyện")
        pending.hint("“Bỏ qua” giữ cuộc trò chuyện trong nhật ký nhưng không đưa vào việc cần làm và tóm tắt. “Không lưu” xóa các câu nói " +
            "của cuộc trò chuyện đó khỏi nhật ký sau khi phân loại.")
        for (cat in Categories.ALL) {
            pending.text(Categories.LABELS.getValue(cat), size = 13f)
            val options = CategoryPolicy.entries
            pending.spinner(options, options.indexOf(s.policy(cat)), { it.label }) { p ->
                if (app.settings.memorySettings().policy(cat) != p) save { it.copy(policies = it.policies + (cat to p)) }
            }
        }
    }

    // ------------------------------------------------------------------ Pessoas
    fun loadPeople(): kotlinx.coroutines.Job = io {
        val list = withContext(Dispatchers.IO) { app.memoryStore.people().map { it to app.memoryStore.itemsOf(it.id).size } }
        people.clear()
        people.title("Mọi người")
        people.hint("Tên được phát hiện từ ngữ cảnh trò chuyện (chỉ khi có bằng chứng rõ ràng) hoặc do bạn chỉ định. " +
            "Để cho biết “Người A” là ai, hãy giữ lâu câu nói của họ trên dòng thời gian trong ngày.")
        if (list.isEmpty()) people.text("Chưa xác định được ai.", size = 14f)
        for ((p, open) in list) {
            val c = people.card()
            c.text(p.name, size = 16f).setTypeface(null, Typeface.BOLD)
            c.hint(listOfNotNull(p.relation.ifBlank { null }, "${p.conversations} cuộc trò chuyện",
                if (p.lastSeen > 0) "gần nhất: ${Days.label(Days.of(p.lastSeen))}" else null, if (open > 0) "$open việc" else null).joinToString(" · "))
            c.root.setOnClickListener { showPerson(p) }
            c.buttons("Xem" to { showPerson(p) }, "Đổi tên" to { rename(p) }, "Gộp" to { merge(p) })
        }
    }

    private fun showPerson(p: Person): kotlinx.coroutines.Job = io {
        val card = withContext(Dispatchers.IO) { app.memoryQueries.personCard(p, System.currentTimeMillis()) }
        AlertDialog.Builder(context).setTitle(p.name).setMessage(card)
            .setPositiveButton("Đóng", null)
            .setNeutralButton("Xóa người này") { _, _ -> AlertDialog.Builder(context).setTitle("Xóa ${p.name}?")
                .setMessage("Xóa hồ sơ. Các câu nói trong nhật ký vẫn còn; các việc sẽ không gắn với ai.")
                .setPositiveButton("Xóa") { _, _ -> io { withContext(Dispatchers.IO) { app.memoryStore.deletePerson(p.id) }; loadPeople() } }
                .setNegativeButton("Hủy", null).show() }
            .show()
    }

    private fun rename(p: Person) {
        val field = EditText(context).apply { setText(p.name) }
        AlertDialog.Builder(context).setTitle("Đổi tên").setView(field).setPositiveButton("Lưu") { _, _ ->
            val name = field.text.toString().trim()
            if (name.isNotEmpty()) io { withContext(Dispatchers.IO) { app.memoryStore.renamePerson(p.id, name) }; loadPeople() }
        }.setNegativeButton("Hủy", null).show()
    }

    private fun merge(p: Person): kotlinx.coroutines.Job = io {
        val others = withContext(Dispatchers.IO) { app.memoryStore.people().filter { it.id != p.id } }
        if (others.isEmpty()) { toast("Không có người nào khác để gộp"); return@io }
        AlertDialog.Builder(context).setTitle("${p.name} là cùng một người với…").setItems(others.map { it.name }.toTypedArray()) { _, which ->
            io { withContext(Dispatchers.IO) { app.memoryStore.mergePeople(p.id, others[which].id) }; toast("Đã gộp vào ${others[which].name}"); loadPeople() }
        }.setNegativeButton("Hủy", null).show()
    }

    /** Timeline long-press on an ambient utterance: who is speaking? */
    fun assignSpeaker(entry: Entry, after: () -> Unit) = io {
        val known = withContext(Dispatchers.IO) { app.memoryStore.people() }
        val names = known.map { it.name } + "Tên khác…"
        AlertDialog.Builder(context).setTitle("Ai đang nói?").setItems(names.toTypedArray()) { _, which ->
            if (which < known.size) apply(entry, known[which].name, after)
            else {
                val field = EditText(context).apply { hint = "Tên" }
                AlertDialog.Builder(context).setTitle("Tên người nói").setView(field)
                    .setPositiveButton("Lưu") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { apply(entry, it, after) } }
                    .setNegativeButton("Hủy", null).show()
            }
        }.setNegativeButton("Hủy", null).show()
    }

    private fun apply(entry: Entry, name: String, after: () -> Unit) = io {
        val p = withContext(Dispatchers.IO) { app.memory.assignSpeaker(entry, name) }
        toast(if (p != null) "Các câu nói trong đoạn này được gán cho ${p.name}" else "Câu nói này không có nhãn người nói")
        after()
    }

    // ------------------------------------------------------------------ Semana
    fun loadWeek(): kotlinx.coroutines.Job = io {
        val now = System.currentTimeMillis()
        val weekStart = app.memoryQueries.weekStart(now).toString()
        val stored = withContext(Dispatchers.IO) { app.journal.latestInsight(weekStart, "weekly") }
        week.clear()
        week.title("Tuần bắt đầu ${Days.label(weekStart)}")
        val text = week.text(stored?.text ?: "Chưa tạo tổng kết cho tuần này.", size = 14f)
        week.button("Tạo / cập nhật tổng kết tuần") {
            text.text = "Đang tạo…"
            io { val w = app.weeklyReview(force = true); text.text = w?.text ?: "Tuần này chưa có đủ nội dung." }
        }
        week.title("Tóm tắt buổi sáng")
        val brief = week.text("", size = 14f)
        week.buttons("Xem ngay" to { io { brief.text = withContext(Dispatchers.IO) { app.memoryQueries.morningBrief(System.currentTimeMillis()) } ?: "Không có việc nào." } },
            "Trên kính" to { io {
                val t = withContext(Dispatchers.IO) { app.memoryQueries.morningBrief(System.currentTimeMillis()) } ?: "Không có việc nào."
                if (app.session.isReady) app.display.show(t, app.settings.displaySettings()) else toast("Hãy kết nối kính")
            } })
        week.title("Hỏi nhật ký")
        week.hint("Theo ý nghĩa và thời gian, vd: “tuần trước tôi nói gì về chi phí?”, “tôi đã hẹn gì với Lan?”")
        val q = week.field("Câu hỏi")
        val answer = week.text("", size = 14f)
        week.button("Hỏi") {
            val question = q.text.toString().trim()
            if (question.length < 4) { toast("Hãy viết câu hỏi"); return@button }
            answer.text = "Đang tìm…"
            io {
                val a = app.journalAnswer(question, org.turboio.customai.journal.EntrySource.TEXT)
                    ?: withContext(Dispatchers.IO) { app.memoryQueries.search(question, System.currentTimeMillis()) }
                answer.text = a
            }
        }
    }
}
