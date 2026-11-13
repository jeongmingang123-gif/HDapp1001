package io.github.jeongmingang.hdapp

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    // ---- 차량 SDK ----
    private lateinit var vehicle: Vehicle
    private var vehicleReady = false
    private val trunkArea = DoorArea(DoorValue.REAR)
    private val driverDoorArea = DoorArea(DoorValue.ROW_1_LEFT)
    private var trunkRegistered = false
    private var driverRegistered = false

    // ---- 상태 ----
    private var trunkOpen: Boolean? = null
    private var driverDoorOpen: Boolean? = null
    private val items = mutableListOf<String>()
    private var reminderDialog: AlertDialog? = null

    // ---- 화면 ----
    private lateinit var sdkStatusText: TextView
    private lateinit var trunkStatusText: TextView
    private lateinit var doorStatusText: TextView
    private lateinit var guideText: TextView
    private lateinit var itemListLayout: LinearLayout
    private lateinit var itemInput: EditText

    private val presetItems = listOf(
        "장바구니", "냉장·냉동식품", "노트북 가방", "유모차", "캐리어", "운동 가방", "택배 상자"
    )

    private val trunkListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            runOnUiThread { onTrunkChanged(pos) }
        }
        override fun onFailed(exception: Exception) {
            runOnUiThread { sdkStatusText.text = "트렁크 알림 오류: ${exception.message}" }
        }
    }

    private val driverDoorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            runOnUiThread { onDriverDoorChanged(pos) }
        }
        override fun onFailed(exception: Exception) {
            runOnUiThread { sdkStatusText.text = "운전석 문 알림 오류: ${exception.message}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadItems()
        buildUi()
        renderStatus()
        renderItems()
        startVehicle()
    }

    // ================= 차량 연결 =================

    private fun startVehicle() {
        vehicle = Vehicle(applicationContext)
        try {
            vehicle.initialize()
            vehicleReady = true
        } catch (e: Exception) {
            sdkStatusText.text = "차량 SDK 초기화 실패: ${e.message}"
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ setupDoorMonitoring() }, 1000)
    }

    private fun setupDoorMonitoring() {
        val door = vehicle.getDoor()
        door.checkDoorPositionCapabilities(
            onComplete = { _, areaBounds ->
                runOnUiThread {
                    val trunkSupported = areaBounds.containsKey(trunkArea)
                    val driverSupported = areaBounds.containsKey(driverDoorArea)
                    sdkStatusText.text = "차량 연결됨 · 트렁크 ${if (trunkSupported) "지원" else "미지원"}" +
                            " · 운전석 문 ${if (driverSupported) "지원" else "미지원"}"

                    if (trunkSupported) {
                        door.getCurrentDoorPosition(
                            area = trunkArea,
                            onComplete = { pos -> runOnUiThread { onTrunkChanged(pos, initial = true) } },
                            onFailed = { e -> runOnUiThread { sdkStatusText.text = "트렁크 조회 실패: ${e.message}" } }
                        )
                        door.registerDoorPosition(trunkArea, trunkListener)
                        trunkRegistered = true
                    }
                    if (driverSupported) {
                        door.getCurrentDoorPosition(
                            area = driverDoorArea,
                            onComplete = { pos -> runOnUiThread { onDriverDoorChanged(pos, initial = true) } },
                            onFailed = { e -> runOnUiThread { sdkStatusText.text = "운전석 문 조회 실패: ${e.message}" } }
                        )
                        door.registerDoorPosition(driverDoorArea, driverDoorListener)
                        driverRegistered = true
                    }
                }
            },
            onFailed = { e -> runOnUiThread { sdkStatusText.text = "지원 확인 실패: ${e.message}" } }
        )
    }

    // ================= 상태 변화 처리 =================

    private fun onTrunkChanged(pos: Int?, initial: Boolean = false) {
        val nowOpen = pos?.let { it > 0 }
        val wasOpen = trunkOpen
        trunkOpen = nowOpen
        if (!initial) {
            if (wasOpen != true && nowOpen == true) {
                guideText.text = "트렁크가 열렸어요.\n넣은 짐은 아래에서 추가하고, 꺼낸 짐은 '꺼냄'을 눌러 주세요."
            } else if (wasOpen == true && nowOpen == false) {
                guideText.text = "트렁크가 닫혔어요. 현재 트렁크 짐 ${items.size}개"
            }
        }
        renderStatus()
    }

    private fun onDriverDoorChanged(pos: Int?, initial: Boolean = false) {
        val nowOpen = pos?.let { it > 0 }
        val wasOpen = driverDoorOpen
        driverDoorOpen = nowOpen
        if (!initial && wasOpen != true && nowOpen == true && items.isNotEmpty()) {
            showLeaveReminder()
        }
        renderStatus()
    }

    private fun showLeaveReminder() {
        reminderDialog?.dismiss()
        val list = items.joinToString("\n") { "• $it" }
        reminderDialog = AlertDialog.Builder(this)
            .setTitle("내리기 전에 확인하세요")
            .setMessage("트렁크에 짐 ${items.size}개가 있어요.\n\n$list")
            .setPositiveButton("모두 꺼냈어요") { _, _ ->
                items.clear()
                saveItems()
                renderItems()
                renderStatus()
                guideText.text = "트렁크 짐 목록을 비웠어요."
            }
            .setNegativeButton("두고 갈게요", null)
            .show()
    }

    // ================= 짐 목록 =================

    private fun addItem(raw: String) {
        val name = raw.trim()
        if (name.isEmpty() || items.contains(name)) return
        items.add(name)
        saveItems()
        renderItems()
        renderStatus()
    }

    private fun removeItem(name: String) {
        items.remove(name)
        saveItems()
        renderItems()
        renderStatus()
    }

    private fun loadItems() {
        val saved = getSharedPreferences("trunk", MODE_PRIVATE).getString("items", "") ?: ""
        items.clear()
        items.addAll(saved.split("\n").filter { it.isNotBlank() })
    }

    private fun saveItems() {
        getSharedPreferences("trunk", MODE_PRIVATE).edit()
            .putString("items", items.joinToString("\n"))
            .apply()
    }

    // ================= 화면 그리기 =================

    private fun renderStatus() {
        trunkStatusText.text = "트렁크: ${stateText(trunkOpen)}\n짐 ${items.size}개"
        trunkStatusText.setBackgroundColor(stateColor(trunkOpen))
        doorStatusText.text = "운전석 문: ${stateText(driverDoorOpen)}"
        doorStatusText.setBackgroundColor(stateColor(driverDoorOpen))
    }

    private fun renderItems() {
        itemListLayout.removeAllViews()
        if (items.isEmpty()) {
            itemListLayout.addView(label("트렁크가 비어 있어요.", 20f).apply { setTextColor(Color.GRAY) })
            return
        }
        items.forEach { name ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(label("• $name", 22f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(makeButton("꺼냄") { removeItem(name) })
            itemListLayout.addView(row, withTop(4))
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(32))
            setBackgroundColor(Color.WHITE)
        }

        root.addView(label("TrunkChecker", 32f, bold = true))
        root.addView(label("트렁크에 짐을 두고 내리지 않도록 알려드려요", 18f))
        sdkStatusText = label("차량 연결 준비 중...", 14f).apply { setTextColor(Color.GRAY) }
        root.addView(sdkStatusText, withTop(4))

        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        trunkStatusText = statusBox()
        doorStatusText = statusBox()
        statusRow.addView(trunkStatusText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        statusRow.addView(doorStatusText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(statusRow, withTop(16))

        guideText = label("트렁크에 짐을 넣으면 아래에서 추가해 주세요.", 18f).apply {
            setBackgroundColor(Color.parseColor("#FFF8E1"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(guideText, withTop(16))

        root.addView(label("트렁크 짐 목록", 22f, bold = true), withTop(24))
        itemListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemListLayout, withTop(8))

        root.addView(label("짐 추가", 22f, bold = true), withTop(24))
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presetItems.forEach { name -> presetRow.addView(makeButton(name) { addItem(name) }) }
        root.addView(HorizontalScrollView(this).apply { addView(presetRow) }, withTop(8))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        itemInput = EditText(this).apply {
            hint = "직접 입력 (예: 우산)"
            textSize = 18f
            setSingleLine()
            setTextColor(Color.parseColor("#222222"))
        }
        inputRow.addView(itemInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(makeButton("추가") {
            addItem(itemInput.text.toString())
            itemInput.setText("")
        })
        root.addView(inputRow, withTop(8))

        root.addView(label("시연용 시뮬레이션 (실제 차량 신호 아님)", 16f, bold = true).apply {
            setTextColor(Color.GRAY)
        }, withTop(32))
        val demoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        demoRow.addView(makeButton("트렁크 열기/닫기") { onTrunkChanged(if (trunkOpen == true) 0 else 1) })
        demoRow.addView(makeButton("운전석 문 열기/닫기") { onDriverDoorChanged(if (driverDoorOpen == true) 0 else 1) })
        root.addView(demoRow, withTop(8))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        })
    }

    // ================= 작은 도우미 =================

    private fun stateText(open: Boolean?) = when (open) {
        true -> "열림"
        false -> "닫힘"
        null -> "확인 중"
    }

    private fun stateColor(open: Boolean?) = Color.parseColor(
        when (open) {
            true -> "#FFE0B2"
            false -> "#E8F5E9"
            null -> "#EEEEEE"
        }
    )

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.parseColor("#222222"))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun statusBox() = label("", 22f, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(20), dp(16), dp(20))
    }

    private fun makeButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 18f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun withTop(topDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        reminderDialog?.dismiss()
        if (vehicleReady) {
            try {
                val door = vehicle.getDoor()
                if (trunkRegistered) door.unregisterDoorPosition(trunkArea, trunkListener)
                if (driverRegistered) door.unregisterDoorPosition(driverDoorArea, driverDoorListener)
            } catch (_: Exception) {
            }
            try {
                vehicle.release()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}