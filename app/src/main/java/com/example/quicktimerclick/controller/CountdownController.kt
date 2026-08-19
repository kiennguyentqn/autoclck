package com.example.quicktimerclick.controller

import android.os.Handler
import android.os.Looper
import com.example.quicktimerclick.utils.SntpHelper
import java.text.SimpleDateFormat
import java.util.*

class CountdownController {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var clicked = false
    var onTick: ((Double) -> Unit)? = null

    var onFinish: (() -> Unit)? = null
    var onClickTime: (() -> Unit)? = null

    // offset = giờ NTP - giờ điện thoại (ms). Đo 1 lần lúc start(), dùng cố định suốt phiên đếm.
    private var timeOffsetMillis: Long = 0L

    /** Thời gian hiện tại đã hiệu chỉnh theo NTP. Dùng hàm này thay cho System.currentTimeMillis(). */
    private fun nowInternetMillis(): Long {
        return System.currentTimeMillis() + timeOffsetMillis
    }

    fun start(targetTime: String, clickAt: Double) {

        running = true
        clicked = false

        // Lấy offset NTP ở thread riêng (network/UDP không được chạy trên main thread)
        Thread {
            timeOffsetMillis = SntpHelper.getNtpOffset(
                ntpHost = "time.google.com",
                sampleCount = 4
            ) ?: 0L

//            android.util.Log.d(
//                "AUTOCLICK_TIME",
//                "Offset NTP cuối cùng = ${timeOffsetMillis}ms (${timeOffsetMillis / 1000.0}s)"
//            )

            // Quay lại main thread để bắt đầu đếm ngược
            handler.post {
                val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                val now = Calendar.getInstance()
                now.timeInMillis = nowInternetMillis()

                val target = Calendar.getInstance()
                target.time = format.parse(targetTime)!!

                target.set(Calendar.YEAR, now.get(Calendar.YEAR))
                target.set(Calendar.MONTH, now.get(Calendar.MONTH))
                target.set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))

                tick(target.timeInMillis, clickAt)
            }
        }.start()
    }

    private fun tick(targetMillis: Long, clickAt: Double) {

        if (!running) return

        val internetNow = nowInternetMillis()
        val remain = (targetMillis - internetNow) / 1000.0

        if (!clicked && remain <= clickAt) {
            clicked = true
            onClickTime?.invoke()
        }
        onTick?.invoke(remain)

        if (remain <= clickAt) {
            running = false
            onFinish?.invoke()
            return
        }

        handler.postDelayed({
            tick(targetMillis, clickAt)
        }, 5)
    }

    fun stop() {
        running = false
    }
}