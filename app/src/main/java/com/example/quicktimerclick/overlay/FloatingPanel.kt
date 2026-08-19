package com.example.quicktimerclick.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.example.quicktimerclick.R
import android.widget.Button
import android.widget.TextView
import com.example.quicktimerclick.controller.CountdownController
import android.widget.EditText
import android.app.Service
import android.view.inputmethod.InputMethodManager
import com.example.quicktimerclick.service.AutoClickAccessibilityService
import com.example.quicktimerclick.model.ClickPoint
import android.view.MotionEvent
class FloatingPanel(private val context: Context) {
    companion object {
        var instance: FloatingPanel? = null
    }
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val countdown = CountdownController()
    private var panelView: View? = null
    private val markerView by lazy { MarkerView(context) }
    private lateinit var params: WindowManager.LayoutParams
    private var markerShowing = false

    // Lưu lại giá trị đã nhập để giữ nguyên khi ẩn/hiện panel lại
    private var lastTarget = ""
    private var lastClick = ""
    fun show() {
        instance = this
        if (panelView != null) return

        panelView = LayoutInflater.from(context)
            .inflate(R.layout.panel_layout, null)
        val btnSetPosition =
            panelView!!.findViewById<Button>(R.id.btnSetPosition)

        val txtPosition =
            panelView!!.findViewById<TextView>(R.id.txtPosition)
        val edtTargetPanel =
            panelView!!.findViewById<EditText>(R.id.edtTargetPanel)

        val edtClickPanel =
            panelView!!.findViewById<EditText>(R.id.edtClickPanel)

        val btnNextTime =
            panelView!!.findViewById<Button>(R.id.btnNextTime)

        // Nạp lại giá trị đã nhập lần trước (nếu có)
        edtTargetPanel.setText(lastTarget)
        edtClickPanel.setText(lastClick)

        val imm =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        edtTargetPanel.setOnTouchListener { v, event ->

            if (event.action == MotionEvent.ACTION_DOWN) {

                enableKeyboard()

                edtTargetPanel.requestFocus()

                v.post {
                    imm.showSoftInput(
                        edtTargetPanel,
                        InputMethodManager.SHOW_IMPLICIT
                    )
                }
            }

            false
        }

        edtClickPanel.setOnTouchListener { v, event ->

            if (event.action == MotionEvent.ACTION_DOWN) {

                enableKeyboard()

                edtClickPanel.requestFocus()

                v.post {
                    imm.showSoftInput(
                        edtClickPanel,
                        InputMethodManager.SHOW_IMPLICIT
                    )
                }
            }

            false
        }

        val btnExit =
            panelView!!.findViewById<Button>(R.id.btnExit)

        val txtRemain =
            panelView!!.findViewById<TextView>(R.id.txtRemain)

        val btnStart =
            panelView!!.findViewById<Button>(R.id.btnStart)

        btnNextTime.setOnClickListener {

            // Mở Cài đặt ngày giờ hệ thống và cố gắng tự điền giờ = hiện tại + 2 phút
            AutoClickAccessibilityService.instance
                ?.autoSetSystemTimePlusMinutes(2)

            if (AutoClickAccessibilityService.instance == null) {
                txtRemain.text = "Chưa bật Accessibility"
            }
        }

        btnExit.setOnClickListener {

            countdown.stop()

            // Ẩn panel
            hide()

            // Nếu marker đang hiện thì cũng ẩn
            if (markerShowing) {
                markerView.hide()
                markerShowing = false
            }

            // Dừng hẳn service -> gỡ luôn bong bóng, thoát toàn bộ overlay
            (context as? Service)?.stopSelf()
        }
        countdown.onTick = { remain ->

            txtRemain.text =
                String.format("%.1f",remain)

        }
        btnStart.setOnClickListener {
            disableKeyboard()
            // Ẩn bàn phím khi bấm Start
            clearInputFocus()

            var target = edtTargetPanel.text.toString().trim()

            val click = edtClickPanel.text.toString().trim().toDoubleOrNull()

            if (target.isEmpty()) {
                txtRemain.text = "Chưa nhập Target"
                return@setOnClickListener
            }

            // Cho phép gõ liền 6 số (VD: 024516) -> tự format thành 02:45:16
            val digitsOnly = target.replace(":", "")
            if (digitsOnly.length == 6 && digitsOnly.all { c -> c.isDigit() }) {
                target = "${digitsOnly.substring(0, 2)}:" +
                        "${digitsOnly.substring(2, 4)}:" +
                        digitsOnly.substring(4, 6)
                edtTargetPanel.setText(target)
            } else if (!target.matches(Regex("^\\d{2}:\\d{2}:\\d{2}$"))) {
                txtRemain.text = "Target sai định dạng (HH:mm:ss)"
                return@setOnClickListener
            }

            if (click == null) {
                txtRemain.text = "Chưa nhập Click At"
                return@setOnClickListener
            }

            // Lưu lại để giữ nguyên khi ẩn/hiện panel lại
            lastTarget = target
            lastClick = click.toString()

            countdown.start(target, click)
        }
        countdown.onFinish={

            txtRemain.text="CLICK"

        }
        countdown.onClickTime = {

            AutoClickAccessibilityService.instance?.click(
                ClickPoint.x,
                ClickPoint.y
            )

        }
        markerView.listener = { x, y ->

            txtPosition.text = "X : $x    Y : $y"

        }

        // Nếu đã có vị trí từ trước (đã set lần trước), hiển thị luôn cho đúng
        if (ClickPoint.x != 0 || ClickPoint.y != 0) {
            txtPosition.text = "X : ${ClickPoint.x}    Y : ${ClickPoint.y}"
        }

        // Đồng bộ lại nhãn nút theo đúng trạng thái marker hiện tại
        btnSetPosition.text = if (markerShowing) "HIDE POSITION" else "SET POSITION"

        btnSetPosition.setOnClickListener {

            if (!markerShowing) {

                markerView.show()

                markerShowing = true

                btnSetPosition.text = "HIDE POSITION"

            } else {

                markerView.hide()

                markerShowing = false

                btnSetPosition.text = "SET POSITION"

            }

        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,

            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,

            PixelFormat.TRANSLUCENT
        )

        params.softInputMode =
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE

        params.gravity = Gravity.TOP or Gravity.START

        params.x = 180
        params.y = 250

        windowManager.addView(panelView, params)



    }

    fun hide() {
        clearInputFocus()
        instance = null
        panelView?.let {

            val imm =
                context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
            disableKeyboard()
            windowManager.removeView(it)

            panelView = null

        }

    }
    fun clearInputFocus() {

        panelView?.let {

            val imm = context.getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager

            val edtTarget =
                it.findViewById<EditText>(R.id.edtTargetPanel)

            val edtClick =
                it.findViewById<EditText>(R.id.edtClickPanel)

            edtTarget.clearFocus()
            edtClick.clearFocus()

            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }
    fun performStartClick() {
        panelView?.findViewById<Button>(R.id.btnStart)?.performClick()
    }
    private fun enableKeyboard() {

        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        windowManager.updateViewLayout(panelView, params)
    }

    private fun disableKeyboard() {

        params.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        windowManager.updateViewLayout(panelView, params)
    }

}