package io.github.jeongmingang.hdapp

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.app.Activity
import android.app.Dialog
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAR_SPEED = 100
        private const val PERM_CAR_SPEED = "android.car.permission.CAR_SPEED"
        private const val HARD_BRAKE = 3.0f   // m/s², 앱 자체 기준값
        private const val HARD_ACCEL = 3.0f   // m/s², 앱 자체 기준값
    }

    // ---- 차량 SDK (문·트렁크) ----
    private lateinit var vehicle: Vehicle
    private var vehicleReady = false
    private val trunkArea = DoorArea(DoorValue.REAR)
    private val driverDoorArea = DoorArea(DoorValue.ROW_1_LEFT)
    private var trunkRegistered = false
    private var driverRegistered = false

    // ---- AAOS 속도 ----
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var lastSpeed: Float? = null
    private var lastSpeedTimeNs = 0L
    private var lastShakeTimeNs = 0L
    private var currentSpeedKmh: Float? = null
    private var hardBrakeCount = 0
    private var hardAccelCount = 0

    // ---- 상태 ----
    private var trunkOpen: Boolean? = null
    private var driverDoorOpen: Boolean? = null
    private val items = mutableListOf<String>()
    private var reminderDialog: Dialog? = null

    // ---- 글꼴 (res/font에 Pretendard가 있으면 사용, 없으면 기본 글꼴) ----
    private val fontRegular by lazy { loadFont("pretendard_regular", Typeface.DEFAULT) }
    private val fontBold by lazy { loadFont("pretendard_bold", Typeface.DEFAULT_BOLD) }

    // ---- 색상 ----
    private val cDeep = Color.parseColor("#2F6B5F")
    private val cMint = Color.parseColor("#5FAE9C")
    private val cText = Color.parseColor("#2E3B38")
    private val cSub = Color.parseColor("#7D918C")
    private val cCoral = Color.parseColor("#B5523B")
    private val cAmber = Color.parseColor("#A16207")

    private val cRed = Color.parseColor("#C62828")
    private val cRedSoft = Color.parseColor("#FFE3E1")

    // ---- 화면 ----
    private lateinit var sdkStatusText: TextView
    private lateinit var trunkCard: LinearLayout
    private lateinit var trunkStateText: TextView
    private lateinit var trunkCountText: TextView
    private lateinit var doorCard: LinearLayout
    private lateinit var doorStateText: TextView
    private lateinit var shakeCard: LinearLayout
    private lateinit var shakeStateText: TextView
    private lateinit var shakeDetailText: TextView
    private lateinit var guideText: TextView
    private lateinit var itemCountBadge: TextView
    private lateinit var gridLayout: LinearLayout
    private lateinit var itemInput: EditText

    private val presetItems = listOf(
        "장바구니", "냉장·냉동식품", "노트북 가방", "유모차", "캐리어", "운동 가방", "택배 상자", "반려동물"
    )

    private val livingKeywords = listOf(
        "반려", "강아지", "고양이", "햄스터", "토끼", "물고기", "거북", "앵무", "애완"
    )

    private fun isLiving(name: String) = livingKeywords.any { name.contains(it) }

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

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val speed = value.value as? Float ?: return
            val timeNs = value.timestamp
            runOnUiThread { onSpeedSample(speed, timeNs) }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) {
            runOnUiThread { shakeDetailText.text = "속도 신호를 받을 수 없어요" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadItems()
        buildUi()
        renderStatus()
        renderShake()
        renderGrid()
        startVehicle()
        startSpeedMonitoring()
    }

    // ================= 차량 연결: 문·트렁크 =================

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
                    sdkStatusText.text = if (trunkSupported && driverSupported) {
                        "●  차량 연결됨"
                    } else {
                        "트렁크 ${if (trunkSupported) "지원" else "미지원"} · 운전석 문 ${if (driverSupported) "지원" else "미지원"}"
                    }
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

    // ================= 차량 연결: 속도 (AAOS 표준) =================

    private fun startSpeedMonitoring() {
        if (checkSelfPermission(PERM_CAR_SPEED) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(PERM_CAR_SPEED), REQ_CAR_SPEED)
            return
        }
        try {
            val connected = Car.createCar(this)
            car = connected
            propertyManager = connected?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            val ok = propertyManager?.registerCallback(
                speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL
            ) ?: false
            if (!ok) shakeDetailText.text = "속도 신호를 구독하지 못했어요"
        } catch (e: Exception) {
            shakeDetailText.text = "속도 연결 실패: ${e.message}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAR_SPEED) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSpeedMonitoring()
            } else {
                shakeDetailText.text = "속도 권한이 없어 흔들림을 추정할 수 없어요"
            }
        }
    }

    private fun onSpeedSample(speedMs: Float, timeNs: Long) {
        currentSpeedKmh = speedMs * 3.6f
        val prevSpeed = lastSpeed
        val prevTime = lastSpeedTimeNs
        lastSpeed = speedMs
        lastSpeedTimeNs = timeNs
        if (prevSpeed != null && timeNs > prevTime) {
            val dt = (timeNs - prevTime) / 1_000_000_000f
            if (dt in 0.01f..3f && timeNs - lastShakeTimeNs > 2_000_000_000L) {
                val accel = (speedMs - prevSpeed) / dt
                if (accel <= -HARD_BRAKE) {
                    lastShakeTimeNs = timeNs
                    recordShake(isBrake = true)
                } else if (accel >= HARD_ACCEL) {
                    lastShakeTimeNs = timeNs
                    recordShake(isBrake = false)
                }
            }
        }
        renderShake()
    }

    private fun recordShake(isBrake: Boolean) {
        if (isBrake) hardBrakeCount++ else hardAccelCount++
        if (items.isNotEmpty()) {
            guideText.text = if (isBrake) {
                "급감속이 감지됐어요.\n트렁크 짐이 앞으로 쏠렸을 수 있어요."
            } else {
                "급가속이 감지됐어요.\n트렁크 짐이 뒤로 밀렸을 수 있어요."
            }
        }
        renderShake()
    }

    private fun resetShake() {
        hardBrakeCount = 0
        hardAccelCount = 0
        renderShake()
    }

    // ================= 상태 변화 처리 =================

    private fun onTrunkChanged(pos: Int?, initial: Boolean = false) {
        val nowOpen = pos?.let { it > 0 }
        val wasOpen = trunkOpen
        trunkOpen = nowOpen
        if (!initial) {
            if (wasOpen != true && nowOpen == true) {
                resetShake()
                guideText.text = "트렁크가 열렸어요.\n넣은 짐은 그림을 눌러 선택하고, 꺼낸 짐은 다시 눌러 주세요."
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

    // ================= 하차 리마인드 팝업 =================

    private fun showLeaveReminder() {
        reminderDialog?.dismiss()
        val living = items.filter { isLiving(it) }
        val strong = living.isNotEmpty()
        val titleColor = if (strong) Color.WHITE else cRed
        val bodyColor = if (strong) Color.WHITE else Color.parseColor("#8A4B47")

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(!strong)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (strong) redPanel() else glass(32, cRedSoft, strong = true)
            setPadding(dp(36), dp(32), dp(36), dp(28))
        }

        if (strong) {
            content.addView(label("살아있는 생물이 트렁크에 있어요", 17f, titleColor, bold = true))
            content.addView(label("${living.joinToString(", ")} 꼭 데리고 내리세요", 28f, titleColor, bold = true), withTop(4))
        } else {
            content.addView(label("내리기 전에 확인하세요", 26f, titleColor, bold = true))
            content.addView(label("트렁크에 짐 ${items.size}개가 있어요.", 18f, bodyColor), withTop(6))
        }

        val shakeTotal = hardBrakeCount + hardAccelCount
        if (shakeTotal > 0) {
            content.addView(label(
                "주행 중 급감속 ${hardBrakeCount}회 · 급가속 ${hardAccelCount}회가 있었어요.\n짐이 쏠렸을 수 있으니 트렁크를 열 때 주의하세요.",
                16f, cRed, bold = true
            ).apply {
                background = glass(16, Color.WHITE, strong = true)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }, withTop(14))
        }

        val shown = if (strong) living else items
        val iconRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shown.take(5).forEach { name ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            tile.addView(ItemIconView(this, kindOf(name), false), LinearLayout.LayoutParams(dp(72), dp(72)))
            tile.addView(label(name, 14f, bodyColor, bold = strong).apply { gravity = Gravity.CENTER }, withTop(6))
            iconRow.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(iconRow, withTop(20))
        if (shown.size > 5) {
            content.addView(label("외 ${shown.size - 5}개", 15f, bodyColor), withTop(6))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        if (strong) {
            buttonRow.addView(glassButton("데리고 내렸어요", primary = false, danger = true) {
                items.removeAll(living)
                saveItems()
                renderGrid()
                renderStatus()
                dialog.dismiss()
                if (items.isNotEmpty()) showLeaveReminder()
            })
        } else {
            buttonRow.addView(glassButton("두고 갈게요", primary = false, danger = true) {
                dialog.dismiss()
            }, wrapWithEnd(12))
            buttonRow.addView(glassButton("모두 꺼냈어요", primary = true, danger = true) {
                items.clear()
                saveItems()
                resetShake()
                renderGrid()
                renderStatus()
                guideText.text = "트렁크 짐을 모두 비웠어요."
                dialog.dismiss()
            })
        }
        content.addView(buttonRow, withTop(24))

        dialog.setContentView(content, ViewGroup.LayoutParams(dp(620), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
        reminderDialog = dialog
    }

    private fun showLivingNotice(name: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = redPanel()
            setPadding(dp(36), dp(32), dp(36), dp(28))
        }
        content.addView(label("트렁크는 생물에게 위험할 수 있어요", 24f, Color.WHITE, bold = true))
        content.addView(label(
            "트렁크는 환기와 온도 조절이 어려워 ${name}에게 위험할 수 있어요.\n" +
                    "가능하면 뒷좌석에 이동장으로 태워 주세요.\n" +
                    "내릴 때는 일반 짐보다 강하게 알려드릴게요.",
            17f, Color.WHITE
        ).apply { setLineSpacing(0f, 1.2f) }, withTop(12))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(glassButton("알겠어요", primary = false, danger = true) { dialog.dismiss() })
        content.addView(row, withTop(22))
        dialog.setContentView(content, ViewGroup.LayoutParams(dp(600), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
    }

    // ================= 짐 목록 =================

    private fun toggleItem(name: String) {
        if (items.contains(name)) {
            items.remove(name)
        } else {
            items.add(name)
            if (isLiving(name)) showLivingNotice(name)
        }
        saveItems()
        renderGrid()
        renderStatus()
    }

    private fun addCustomItem(raw: String) {
        val name = raw.trim()
        if (name.isEmpty() || items.contains(name)) return
        items.add(name)
        if (isLiving(name)) showLivingNotice(name)
        saveItems()
        renderGrid()
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

    // ================= 화면 갱신 =================

    private fun renderStatus() {
        trunkCard.background = glass(28, tintFor(trunkOpen))
        trunkStateText.text = stateText(trunkOpen)
        trunkStateText.setTextColor(stateTextColor(trunkOpen))
        trunkCountText.text = "짐 ${items.size}개"

        doorCard.background = glass(28, tintFor(driverDoorOpen))
        doorStateText.text = stateText(driverDoorOpen)
        doorStateText.setTextColor(stateTextColor(driverDoorOpen))

        itemCountBadge.text = "${items.size}개 선택"
    }

    private fun renderShake() {
        val total = hardBrakeCount + hardAccelCount
        val (level, tint, color) = when {
            total == 0 -> Triple("안정", Color.parseColor("#D6EFE7"), cDeep)
            total <= 2 -> Triple("보통", Color.parseColor("#FFF1CC"), cAmber)
            else -> Triple("주의", Color.parseColor("#FBE1D8"), cCoral)
        }
        shakeCard.background = glass(28, tint)
        shakeStateText.text = level
        shakeStateText.setTextColor(color)
        val speedText = currentSpeedKmh?.let { " · 현재 ${it.toInt()} km/h" } ?: ""
        shakeDetailText.text = "급감속 ${hardBrakeCount}회 · 급가속 ${hardAccelCount}회$speedText"
    }

    private fun renderGrid() {
        gridLayout.removeAllViews()
        val candidates = presetItems + items.filter { it !in presetItems }
        candidates.chunked(4).forEach { rowNames ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowNames.forEach { name ->
                row.addView(itemTile(name, items.contains(name)),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            repeat(4 - rowNames.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            gridLayout.addView(row, withTop(6))
        }
    }

    private fun itemTile(name: String, selected: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(10), dp(4), dp(10))
        addView(ItemIconView(this@MainActivity, kindOf(name), selected),
            LinearLayout.LayoutParams(dp(96), dp(96)))
        addView(label(name, 16f, if (selected) cDeep else cText, bold = selected).apply {
            gravity = Gravity.CENTER
        }, withTop(8))
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(22).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 95, 174, 156)), null, mask)
        isClickable = true
        setOnClickListener { toggleItem(name) }
    }

    // ================= 화면 구성 =================

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(28), dp(32), dp(32))
        }

        // 헤더
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(label("TrunkChecker", 30f, cDeep, bold = true).apply { letterSpacing = 0.02f })
        titleBox.addView(label("트렁크에 짐을 두고 내리지 않도록", 17f, cSub), withTop(2))
        header.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sdkStatusText = label("차량 연결 준비 중", 15f, cDeep, bold = true).apply {
            background = glass(999, Color.WHITE)
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        header.addView(sdkStatusText)
        root.addView(header)

        // 좌우 2단
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        columns.addView(left,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 4f).apply { marginEnd = dp(24) })
        columns.addView(right,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 6f))
        root.addView(columns, withTop(24))

        // 왼쪽: 트렁크
        trunkCard = card()
        trunkCard.addView(label("트렁크", 17f, cSub, bold = true))
        trunkStateText = label("확인 중", 46f, cText, bold = true)
        trunkCard.addView(trunkStateText, withTop(2))
        trunkCountText = label("짐 0개", 19f, cText)
        trunkCard.addView(trunkCountText, withTop(2))
        left.addView(trunkCard)

        // 왼쪽: 운전석 문 + 주행 흔들림 (가로 2칸)
        val smallRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        doorCard = card()
        doorCard.addView(label("운전석 문", 17f, cSub, bold = true))
        doorStateText = label("확인 중", 30f, cText, bold = true)
        doorCard.addView(doorStateText, withTop(2))
        smallRow.addView(doorCard,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(14) })

        shakeCard = card()
        shakeCard.addView(label("주행 흔들림 (추정)", 17f, cSub, bold = true))
        shakeStateText = label("안정", 30f, cDeep, bold = true)
        shakeCard.addView(shakeStateText, withTop(2))
        shakeDetailText = label("속도 신호 대기 중", 14f, cSub)
        shakeCard.addView(shakeDetailText, withTop(4))
        smallRow.addView(shakeCard,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        left.addView(smallRow, withTop(18))

        // 왼쪽: 안내
        val guideCard = card()
        guideCard.addView(label("안내", 15f, cMint, bold = true))
        guideText = label("트렁크를 열고, 넣은 짐을 그림으로 선택해 주세요.", 18f, cText).apply {
            setLineSpacing(0f, 1.15f)
        }
        guideCard.addView(guideText, withTop(6))
        left.addView(guideCard, withTop(18))

        // 왼쪽: 시연용
        val demoCard = card()
        demoCard.addView(label("시연용 시뮬레이션 · 실제 차량 신호 아님", 14f, cSub, bold = true))
        val demoRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        demoRow1.addView(glassButton("트렁크 열기/닫기", primary = false, small = true) {
            onTrunkChanged(if (trunkOpen == true) 0 else 1)
        }, wrapWithEnd(10))
        demoRow1.addView(glassButton("운전석 문 열기/닫기", primary = false, small = true) {
            onDriverDoorChanged(if (driverDoorOpen == true) 0 else 1)
        })
        demoCard.addView(demoRow1, withTop(12))
        val demoRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        demoRow2.addView(glassButton("급감속 발생", primary = false, small = true) {
            recordShake(isBrake = true)
        }, wrapWithEnd(10))
        demoRow2.addView(glassButton("급가속 발생", primary = false, small = true) {
            recordShake(isBrake = false)
        })
        demoCard.addView(demoRow2, withTop(10))
        left.addView(demoCard, withTop(18))

        // 오른쪽: 짐 선택
        val itemCard = card()
        val itemHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val itemTitleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        itemTitleBox.addView(label("트렁크 짐", 22f, cText, bold = true))
        itemTitleBox.addView(label("그림을 눌러 선택 · 다시 누르면 꺼냄", 15f, cSub), withTop(2))
        itemHeader.addView(itemTitleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        itemCountBadge = label("0개 선택", 15f, Color.WHITE, bold = true).apply {
            background = gradientPill()
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        itemHeader.addView(itemCountBadge)
        itemCard.addView(itemHeader)

        gridLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        itemCard.addView(gridLayout, withTop(10))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        itemInput = EditText(this).apply {
            hint = "목록에 없는 짐 직접 입력 (예: 우산)"
            textSize = 17f
            typeface = fontRegular
            setSingleLine()
            setTextColor(cText)
            setHintTextColor(cSub)
            background = glass(18, Color.WHITE)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        inputRow.addView(itemInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        inputRow.addView(glassButton("추가", primary = true) {
            addCustomItem(itemInput.text.toString())
            itemInput.setText("")
        })
        itemCard.addView(inputRow, withTop(18))
        right.addView(itemCard)

        setContentView(ScrollView(this).apply {
            background = backgroundDrawable()
            isFillViewport = true
            addView(root)
        })
    }

    // ================= 디자인 도우미 =================

    private fun loadFont(name: String, fallback: Typeface): Typeface {
        val id = resources.getIdentifier(name, "font", packageName)
        if (id == 0) return fallback
        return try {
            resources.getFont(id)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun stateText(open: Boolean?) = when (open) {
        true -> "열림"
        false -> "닫힘"
        null -> "확인 중"
    }

    private fun stateTextColor(open: Boolean?) = when (open) {
        true -> cCoral
        false -> cDeep
        null -> cSub
    }

    private fun tintFor(open: Boolean?) = when (open) {
        true -> Color.parseColor("#FBE1D8")
        false -> Color.parseColor("#D6EFE7")
        null -> Color.WHITE
    }

    /** 반투명 + 그라데이션 + 흰 테두리 + 위쪽 광택 = 글래스 효과 */
    private fun glass(radiusDp: Int, tint: Int, strong: Boolean = false): Drawable {
        val radius = dp(radiusDp).toFloat()
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(withAlpha(tint, if (strong) 250 else 200), withAlpha(tint, if (strong) 225 else 140))
        ).apply {
            cornerRadius = radius
            setStroke(px(1.5f), Color.argb(235, 255, 255, 255))
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255))
        ).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(base, gloss))
    }

    private fun gradientPill(): Drawable {
        val radius = dp(999).toFloat()
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#A6DCCF"), Color.parseColor("#5FAE9C"))
        ).apply {
            cornerRadius = radius
            setStroke(px(1.5f), Color.argb(200, 255, 255, 255))
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255))
        ).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(base, gloss))
    }

    private fun glassButton(
        text: String, primary: Boolean, small: Boolean = false, danger: Boolean = false, onClick: () -> Unit
    ) = TextView(this).apply {
        this.text = text
        textSize = if (small) 15f else 18f
        gravity = Gravity.CENTER
        typeface = fontBold
        minHeight = dp(if (small) 44 else 54)
        setPadding(dp(if (small) 18 else 26), dp(10), dp(if (small) 18 else 26), dp(10))
        setTextColor(
            when {
                primary -> Color.WHITE
                danger -> cRed
                else -> cDeep
            }
        )
        val shape = when {
            primary && danger -> redPill()
            primary -> gradientPill()
            else -> glass(999, Color.WHITE, strong = danger)
        }
        val ripple = if (danger) Color.argb(60, 198, 40, 40) else Color.argb(60, 95, 174, 156)
        background = RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** 진한 빨강 그라데이션 + 광택 (생물 경고용) */
    private fun redPanel(): Drawable {
        val radius = dp(32).toFloat()
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#EF6B63"), Color.parseColor("#C62828"))
        ).apply {
            cornerRadius = radius
            setStroke(px(1.5f), Color.argb(200, 255, 255, 255))
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255))
        ).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(base, gloss))
    }

    private fun redPill(): Drawable {
        val radius = dp(999).toFloat()
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#F28B82"), Color.parseColor("#D93B3B"))
        ).apply {
            cornerRadius = radius
            setStroke(px(1.5f), Color.argb(200, 255, 255, 255))
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255))
        ).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(base, gloss))
    }

    private fun backgroundDrawable(): Drawable {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#E3F1EC"), Color.parseColor("#F6F4EE"))
        )
        fun blob(color: Int, cx: Float, cy: Float, radiusDp: Int) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(color, Color.TRANSPARENT)
            gradientRadius = dp(radiusDp).toFloat()
            setGradientCenter(cx, cy)
        }
        return LayerDrawable(arrayOf(
            base,
            blob(Color.argb(150, 168, 214, 200), 0.12f, 0.10f, 700),
            blob(Color.argb(90, 200, 230, 222), 0.80f, 0.12f, 500),
            blob(Color.argb(110, 246, 214, 208), 0.92f, 0.90f, 600)
        ))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(28, Color.WHITE)
        setPadding(dp(28), dp(24), dp(28), dp(24))
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) fontBold else fontRegular
    }

    private fun kindOf(name: String) = when (name) {
        "장바구니" -> 0
        "냉장·냉동식품" -> 1
        "노트북 가방" -> 2
        "유모차" -> 3
        "캐리어" -> 4
        "운동 가방" -> 5
        "택배 상자" -> 6
        "반려동물" -> 8
        else -> if (isLiving(name)) 8 else 7
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun withTop(topDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    private fun wrapWithEnd(endDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = dp(endDp) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun px(value: Float) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        reminderDialog?.dismiss()
        try {
            propertyManager?.unregisterCallback(speedCallback)
            car?.disconnect()
        } catch (_: Exception) {
        }
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

/** 짐 아이콘: 원형 글래스 배경 위에 선 그림을 코드로 직접 그림 (이미지 파일 불필요) */
class ItemIconView(
    context: Context,
    private val kind: Int,
    private val selected: Boolean
) : View(context) {

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 47, 107, 95) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        canvas.scale(s / 100f, s / 100f)

        canvas.drawCircle(50f, 51.5f, 47f, shadowPaint)

        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, 100f,
            if (selected) Color.parseColor("#A6DCCF") else Color.argb(245, 255, 255, 255),
            if (selected) Color.parseColor("#5FAE9C") else Color.parseColor("#DCEFEA"),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(50f, 50f, 47f, fillPaint)

        glossPaint.shader = LinearGradient(
            0f, 5f, 0f, 52f, Color.argb(140, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        canvas.drawOval(RectF(16f, 5f, 84f, 52f), glossPaint)

        ringPaint.strokeWidth = 2.2f
        ringPaint.color = Color.argb(235, 255, 255, 255)
        canvas.drawCircle(50f, 50f, 47f, ringPaint)

        linePaint.strokeWidth = 3.4f
        linePaint.color = if (selected) Color.WHITE else Color.parseColor("#4E8F81")
        drawIcon(canvas, linePaint)

        if (selected) {
            fillPaint.shader = null
            fillPaint.color = Color.WHITE
            canvas.drawCircle(82f, 18f, 11f, fillPaint)
            linePaint.color = Color.parseColor("#5FAE9C")
            linePaint.strokeWidth = 3f
            canvas.drawPath(Path().apply {
                moveTo(76.5f, 18f); lineTo(80.5f, 22f); lineTo(87.5f, 14f)
            }, linePaint)
        }
        canvas.restore()
    }

    private fun drawIcon(c: Canvas, p: Paint) {
        when (kind) {
            0 -> { // 장바구니
                c.drawArc(RectF(37f, 24f, 63f, 50f), 180f, 180f, false, p)
                c.drawPath(Path().apply {
                    moveTo(27f, 44f); lineTo(73f, 44f); lineTo(66f, 72f); lineTo(34f, 72f); close()
                }, p)
                c.drawLine(43f, 52f, 44f, 64f, p)
                c.drawLine(50f, 52f, 50f, 64f, p)
                c.drawLine(57f, 52f, 56f, 64f, p)
            }
            1 -> { // 냉장·냉동식품 (눈송이)
                for (i in 0 until 6) {
                    val a = Math.toRadians(i * 60.0)
                    c.drawLine(50f, 50f, 50f + 23f * cos(a).toFloat(), 50f + 23f * sin(a).toFloat(), p)
                    val bx = 50f + 14f * cos(a).toFloat()
                    val by = 50f + 14f * sin(a).toFloat()
                    for (d in listOf(-40.0, 40.0)) {
                        val b = a + Math.toRadians(d)
                        c.drawLine(bx, by, bx + 7f * cos(b).toFloat(), by + 7f * sin(b).toFloat(), p)
                    }
                }
            }
            2 -> { // 노트북 가방
                c.drawRoundRect(RectF(27f, 38f, 73f, 70f), 5f, 5f, p)
                c.drawPath(Path().apply {
                    moveTo(42f, 38f); lineTo(42f, 31f); lineTo(58f, 31f); lineTo(58f, 38f)
                }, p)
                c.drawLine(27f, 52f, 73f, 52f, p)
                c.drawRoundRect(RectF(46f, 48f, 54f, 56f), 2f, 2f, p)
            }
            3 -> { // 유모차
                c.drawArc(RectF(30f, 30f, 62f, 62f), 180f, 90f, true, p)
                c.drawPath(Path().apply {
                    moveTo(30f, 46f); lineTo(70f, 46f)
                    quadTo(68f, 62f, 56f, 62f); lineTo(42f, 62f)
                    quadTo(32f, 62f, 30f, 46f)
                }, p)
                c.drawLine(70f, 46f, 77f, 34f, p)
                c.drawCircle(39f, 70f, 5f, p)
                c.drawCircle(61f, 70f, 5f, p)
            }
            4 -> { // 캐리어
                c.drawRoundRect(RectF(34f, 36f, 66f, 73f), 6f, 6f, p)
                c.drawPath(Path().apply {
                    moveTo(44f, 36f); lineTo(44f, 28f); lineTo(56f, 28f); lineTo(56f, 36f)
                }, p)
                c.drawLine(43f, 44f, 43f, 65f, p)
                c.drawLine(57f, 44f, 57f, 65f, p)
                c.drawCircle(41f, 77f, 2f, p)
                c.drawCircle(59f, 77f, 2f, p)
            }
            5 -> { // 운동 가방 (덤벨)
                c.drawLine(38f, 50f, 62f, 50f, p)
                c.drawRoundRect(RectF(26f, 39f, 33f, 61f), 2.5f, 2.5f, p)
                c.drawRoundRect(RectF(67f, 39f, 74f, 61f), 2.5f, 2.5f, p)
                c.drawRoundRect(RectF(33f, 44f, 38f, 56f), 2f, 2f, p)
                c.drawRoundRect(RectF(62f, 44f, 67f, 56f), 2f, 2f, p)
            }
            6 -> { // 택배 상자
                c.drawRoundRect(RectF(28f, 36f, 72f, 72f), 4f, 4f, p)
                c.drawLine(28f, 47f, 72f, 47f, p)
                c.drawLine(45f, 36f, 45f, 55f, p)
                c.drawLine(55f, 36f, 55f, 55f, p)
                c.drawLine(45f, 55f, 55f, 55f, p)
            }
            8 -> { // 반려동물 (발바닥)
                c.drawOval(RectF(38f, 48f, 62f, 70f), p)
                c.drawOval(RectF(28f, 38f, 37f, 50f), p)
                c.drawOval(RectF(38f, 28f, 47f, 41f), p)
                c.drawOval(RectF(53f, 28f, 62f, 41f), p)
                c.drawOval(RectF(63f, 38f, 72f, 50f), p)
            }
            else -> { // 직접 입력한 짐 (태그)
                c.drawPath(Path().apply {
                    moveTo(30f, 32f); lineTo(53f, 32f); lineTo(72f, 51f); lineTo(51f, 72f); lineTo(30f, 51f); close()
                }, p)
                c.drawCircle(40f, 42f, 3.5f, p)
            }
        }
    }
}