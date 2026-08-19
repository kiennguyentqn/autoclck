package com.example.quicktimerclick.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.accessibilityservice.AccessibilityService.GestureResultCallback
class AutoClickAccessibilityService : AccessibilityService() {

    companion object {

        var instance: AutoClickAccessibilityService? = null

    }

    private val timeSettingsAutomator by lazy { TimeSettingsAutomator(this) }

    /**
     * Mở Cài đặt > Ngày & giờ và tự động điền giờ = giờ hiện tại + soCongPhut.
     * Best-effort, có thể không hoạt động đúng trên mọi hãng máy.
     */
    fun autoSetSystemTimePlusMinutes(soCongPhut: Int) {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MINUTE, soCongPhut)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        timeSettingsAutomator.startAddMinutes(1)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        android.util.Log.d("AUTOCLICK", "Accessibility Connected")
    }

    override fun onInterrupt() {
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

    }

    fun click(x: Int, y: Int) {

        try {
            val holdDuration = (60..120).random().toLong()

            // Bấm thẳng đúng 1 điểm (x, y), không di chuyển đường chạm.
            // Trước đây có cho đường chạm "rung" nhẹ vài pixel quanh điểm để mô
            // phỏng vùng tiếp xúc tự nhiên hơn, nhưng cách đó khiến hệ thống dễ
            // hiểu nhầm thành cử chỉ vuốt/kéo nhẹ thay vì bấm, làm điểm click bị
            // lệch khỏi toạ độ đã đặt. Giữ đường chạm đứng yên để click luôn
            // trúng chính xác toạ độ đã chọn.
            val safeX = x.coerceAtLeast(0)
            val safeY = y.coerceAtLeast(0)

            android.util.Log.d("AUTOCLICK", "Click at $safeX,$safeY hold=${holdDuration}ms")

            val path = Path()
            path.moveTo(safeX.toFloat(), safeY.toFloat())

            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        holdDuration
                    )
                )
                .build()

            dispatchGesture(
                gesture,
                object : GestureResultCallback() {

                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        android.util.Log.d("AUTOCLICK", "Gesture Completed")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        android.util.Log.d("AUTOCLICK", "Gesture Cancelled")
                    }

                },
                null
            )
        } catch (e: Exception) {
            android.util.Log.e("AUTOCLICK", "Click failed: ${e.message}")
        }
    }

}