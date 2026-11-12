package io.github.jeongmingang.hdapp

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var vehicle: Vehicle
    private lateinit var statusText: TextView
    private val logLines = StringBuilder()
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusText = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        setContentView(statusText)

        show("1. Vehicle SDK 초기화 중...")
        vehicle = Vehicle(applicationContext)
        try {
            vehicle.initialize()
            show("2. initialize() 호출 완료")
        } catch (e: Exception) {
            show("초기화 실패: ${e.message}")
            return
        }

        // 서비스 연결 여유 시간 (예방 차원)
        Handler(Looper.getMainLooper()).postDelayed({ checkAndReadDoor() }, 1000)
    }

    private fun checkAndReadDoor() {
        val door = vehicle.getDoor()
        door.checkDoorPositionCapabilities(
            onComplete = { config, areaBounds ->
                val bounds = areaBounds[driverDoor]
                show("3. 지원 확인 성공 (access=${config.accessType}, range=${bounds?.minValue}..${bounds?.maxValue})")
                door.getCurrentDoorPosition(
                    area = driverDoor,
                    onComplete = { position ->
                        show("4. 운전석 문 위치 = $position  (0 = 닫힘)")
                    },
                    onFailed = { e -> show("조회 실패: ${e.message}") }
                )
            },
            onFailed = { e -> show("지원 확인 실패: ${e.message}") }
        )
    }

    private fun show(line: String) {
        runOnUiThread {
            logLines.appendLine(line)
            statusText.text = logLines
        }
    }

    override fun onDestroy() {
        try {
            vehicle.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}