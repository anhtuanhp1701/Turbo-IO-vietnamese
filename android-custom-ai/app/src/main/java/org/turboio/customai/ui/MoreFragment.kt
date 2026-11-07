package org.turboio.customai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.turboio.customai.BuildConfig
import org.turboio.customai.R

/** Tools, diagnostics and the test suites. */
class MoreFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val ui = Column(requireContext())
        val main = activity as? MainActivity
        ui.header("Thêm", "Công cụ, bài thử và chẩn đoán")
        ui.section("Công cụ")
        ui.nav(R.drawable.ic_mic, "Phiên dịch 2 chiều", "Dịch tự động giữa bạn và người đối diện, hiện trên kính và đọc to") { main?.open(InterpreterFragment()) }
        ui.nav(R.drawable.ic_chat, "Hỏi AI", "Hỏi bằng chữ, trả lời trên kính") { main?.open(TestFragment()) }
        ui.nav(R.drawable.ic_prompter, "Máy nhắc chữ", "Dán kịch bản hoặc mở tệp .txt") { main?.open(TestFragment()) }
        ui.section("Bài thử và chẩn đoán")
        ui.nav(R.drawable.ic_flask, "Bộ kiểm tra tính năng", "Thử từng chức năng trên kính và xuất ra tệp") { main?.open(DiagnosticsFragment()) }
        ui.nav(R.drawable.ic_diag, "Chẩn đoán", "Trạng thái kết nối, log kỹ thuật, kiểm tra âm thanh") { main?.open(DiagnosticsFragment()) }
        ui.section("Giới thiệu")
        ui.hint("Turbo IO AI ${BuildConfig.VERSION_NAME} · app độc lập cho RayNeo iO dùng firmware gốc. " +
            "Không sửa firmware hay app chính thức.")
        return ui.scroll()
    }
}
