package com.example.quicktimerclick.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.example.quicktimerclick.model.ClickPoint

class MarkerView(private val context: Context) {
    var listener: ((Int, Int) -> Unit)? = null

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var marker: View? = null

    // Nhớ vị trí lần cuối để không bị reset / tạo trùng khi ẩn-hiện panel lại
    private var lastX = 400
    private var lastY = 500

    // Kích thước hình tròn đánh dấu (điểm nhấn) - đã thu nhỏ lại
    private val markerSize = 84

    // View tự vẽ hình tròn + dấu cộng đúng chính giữa bằng Canvas,
    // KHÔNG dùng TextView vì chữ "+" bị lệch lên do font padding.
    private inner class MarkerCircleView(context: Context) : View(context) {

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33000000")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f
            val cy = height / 2f
            val radius = (minOf(width, height) / 2f) - 2f

            // Hình tròn nền
            canvas.drawCircle(cx, cy, radius, circlePaint)
            canvas.drawCircle(cx, cy, radius, borderPaint)

            // Dấu cộng vẽ đúng tâm hình học (cx, cy) - đây cũng chính là
            // toạ độ ClickPoint thực tế sẽ click vào
            val armLen = radius * 0.5f
            canvas.drawLine(cx - armLen, cy, cx + armLen, cy, crossPaint)
            canvas.drawLine(cx, cy - armLen, cx, cy + armLen, crossPaint)
        }
    }

    fun show() {

        // Đã có marker đang hiện rồi thì không tạo thêm cái mới
        if (marker != null) return

        val newMarker = MarkerCircleView(context)

        val params = WindowManager.LayoutParams(
            markerSize,
            markerSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        // Dùng lại vị trí lần trước thay vì luôn reset về mặc định
        params.x = lastX
        params.y = lastY

        val halfWidth = markerSize / 2
        val halfHeight = markerSize / 2

        // Lưu lại: vị trí marker + toạ độ chạm ngay tại thời điểm ACTION_DOWN,
        // để khi kéo chỉ cộng thêm ĐÚNG phần ngón tay đã di chuyển (delta),
        // thay vì ép tâm marker snap về giữa ngón tay -> hết bị "tự động dịch"
        // ngay khi vừa chạm vào.
        var startTouchX = 0f
        var startTouchY = 0f
        var startParamsX = 0
        var startParamsY = 0

        newMarker.setOnTouchListener { _, event ->

            when (event.action) {

                // Phải bắt và "nhận" (trả về true) ACTION_DOWN thì mới nhận được
                // ACTION_MOVE / ACTION_UP tiếp theo của cùng cử chỉ kéo.
                MotionEvent.ACTION_DOWN -> {

                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    startParamsX = params.x
                    startParamsY = params.y

                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    // Marker di chuyển đúng bằng độ dịch chuyển thật của ngón tay
                    // kể từ lúc chạm xuống (delta), không snap về giữa ngón tay.
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()

                    params.x = startParamsX + dx
                    params.y = startParamsY + dy

                    lastX = params.x
                    lastY = params.y

                    // ClickPoint = tâm chính xác của hình tròn đang hiển thị
                    ClickPoint.x = params.x + halfWidth
                    ClickPoint.y = params.y + halfHeight

                    listener?.invoke(
                        ClickPoint.x,
                        ClickPoint.y
                    )
                    windowManager.updateViewLayout(newMarker, params)

                    true
                }

                else -> false
            }

        }

        windowManager.addView(newMarker, params)
        marker = newMarker

        // Báo ngay vị trí hiện tại cho panel biết (kể cả khi chưa kéo lần nào)
        ClickPoint.x = params.x + halfWidth
        ClickPoint.y = params.y + halfHeight
        listener?.invoke(ClickPoint.x, ClickPoint.y)

    }

    fun hide() {

        marker?.let {

            windowManager.removeView(it)

            marker = null

        }

    }

}
