package org.turboio.customai.ui

import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import org.turboio.customai.R

/** JVM screenshots of the redesigned screens (sample data; no glasses or Android device needed). */
class SmokeShot {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, theme = "Theme.TurboIO")

    private val none = HomeActions({}, {}, {}, {})

    private fun tiles(connected: Boolean) = listOf(
        Tile(R.drawable.ic_mic, "Trợ lý giọng nói", "Ligado · Gemini", true) {},
        Tile(R.drawable.ic_record, "Ghi lại ngày", "Đã dừng", false) {},
        Tile(R.drawable.ic_prompter, "Máy nhắc chữ", "Pronto", false) {},
        Tile(R.drawable.ic_bell, "Thông báo", if (connected) "WhatsApp e mais 4" else "Đã tắt", connected) {},
        Tile(R.drawable.ic_todo, "To-do trên kính", "Bật", true) {},
        Tile(R.drawable.ic_lookup, "Ngẩng đầu", "Ligado · 20°", true) {},
        Tile(R.drawable.ic_weather, "Thời tiết", "Belo Horizonte 25°", false) {},
        Tile(R.drawable.ic_flask, "Bộ kiểm tra", "12 de 20", false) {},
    )

    @Test fun homeConnected() = paparazzi.snapshot(HomeView.build(paparazzi.context, HomeModel("RayNeo iO", true, "Đã kết nối", 78, "Strix OS 1.0.5.5",
        tiles(true), "3 pendências", listOf("Ligar para a Marina · hoje 15:00", "Enviar proposta · amanhã")), none))

    @Test fun homeDisconnected() = paparazzi.snapshot(HomeView.build(paparazzi.context, HomeModel("RayNeo iO", false, "Chưa kết nối", null, "Đã lưu kính · bấm Kết nối",
        tiles(false), "Không có việc nào", listOf("Bộ nhớ tạo việc cần làm từ các cuộc trò chuyện của bạn.")), none))

    @Test fun bottomBar() {
        val nav = MainActivity.bottomNav(paparazzi.context).apply { selectedItemId = R.id.nav_home }
        paparazzi.snapshot(android.widget.LinearLayout(paparazzi.context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(HomeView.build(paparazzi.context, HomeModel("RayNeo iO", true, "Đã kết nối", 78, "Strix OS 1.0.5.5", tiles(true), "3 pendências",
                listOf("Ligar para a Marina · hoje 15:00")), none), android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            addView(nav, android.widget.LinearLayout.LayoutParams(-1, -2))
        })
    }

    @Test fun components() {
        val ui = Column(paparazzi.context)
        ui.header("Kính", "Conexão e ajustes da tela")
        ui.section("Tela")
        val c = ui.card()
        c.title("Altura e distância")
        c.hint("O app lê os valores atuais antes de mudar e confere depois.")
        c.spinner(listOf(1, 3, 5), 0, { "Độ cao $it" }) {}
        c.buttons("Bật" to {}, "Tắt" to {})
        c.button("Áp dụng") {}
        ui.section("Conexão")
        ui.nav(R.drawable.ic_glasses_line, "Procurar óculos", "Modo de pareamento: segure o botão 5 s") {}
        ui.nav(R.drawable.ic_diag, "Chẩn đoán", "Log, bateria de testes") {}
        val f = ui.card()
        f.field("Chave da API (vazio = manter)", password = true)
        f.switch("Manter conexão com a tela desligada", true) {}
        f.chip("Đã kết nối", true)
        paparazzi.snapshot(FrameLayout(paparazzi.context).apply { addView(ui.scroll()) })
    }
}
