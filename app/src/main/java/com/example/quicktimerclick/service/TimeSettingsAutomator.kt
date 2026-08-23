package com.example.quicktimerclick.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.widget.Button
import com.example.quicktimerclick.R
import java.text.Normalizer
import com.example.quicktimerclick.overlay.FloatingPanel
/**
 * Cố gắng mở màn hình Cài đặt > Ngày & giờ của hệ thống rồi tự bấm/điền
 * để đổi giờ máy thành (giờ hiện tại + soCongPhut).
 *
 * LƯU Ý QUAN TRỌNG (One UI / các bản Settings mới dựng bằng Jetpack Compose):
 * Cột "giờ" / "phút" trong bộ chọn KHÔNG phải là android.widget.NumberPicker
 * như bản Settings cũ. Nó là 1 node Compose tuỳ biến, thường có className
 * null (in ra "?") và text dạng "15giờBạn đang dùng bộ chọn. Hãy vuốt lên
 * hoặc xuống để chọn giá trị." Vì vậy KHÔNG được lọc theo class, mà phải
 * lọc theo pattern text (số + "giờ"/"phút" ở đầu chuỗi).
 *
 * Ngoài ra hint accessibility nói rõ "vuốt lên/xuống" -> node này có thể
 * KHÔNG hỗ trợ performAction(ACTION_SCROLL_FORWARD/BACKWARD). Nên luồng
 * cuộn sẽ: (1) thử performAction trước nếu node có khai báo action đó
 * trong actionList, (2) nếu không có thì fallback gửi gesture vuốt thật
 * bằng AccessibilityService.dispatchGesture trên toạ độ của node.
 *
 * LƯU Ý CHUNG: Giao diện Settings khác nhau tùy hãng máy / phiên bản
 * Android, nên đây là giải pháp "dò tìm" (best-effort), KHÔNG đảm bảo
 * đúng 100% trên mọi thiết bị.
 */
class TimeSettingsAutomator(private val service: AutoClickAccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val TAG = "TIME_AUTOMATE"

    private var targetHour = 0
    private var targetMinute = 0
    private var retryCount = 0
    private val maxRetryPerStep = 20   // ~20 x 400ms = 8s chờ mỗi bước
    private val retryDelayMs = 400L



    // ====== Chế độ "cộng thêm N phút bằng cách cuộn" ======
    private var addMinutesMode = false
    private var minutesToAdd = 0
    private val scrollStepDelayMs = 500L   // chờ giữa mỗi nấc cuộn/vuốt để UI cập nhật kịp
    // (tăng lên vì mỗi gesture giờ mất 900ms)

    // ====== Tinh chỉnh gesture vuốt để CHỈ đi đúng 1 nấc mỗi lần ======
    // Vuốt càng NHANH (quãng đường dài / thời gian ngắn) càng dễ bị hệ thống coi là
    // "fling" -> picker trôi qua nhiều giá trị thay vì dừng đúng 1 nấc. Nên dùng
    // quãng đường NGẮN + thời gian DÀI hơn (vuốt chậm, có kiểm soát).
    private val gestureStrokeDurationMs = 900L   // vuốt CHẬM hẳn để hạ vận tốc, tránh bị
    // hệ thống coi là "fling" (vuốt nhanh
    // rồi buông tay) khiến picker trôi theo
    // quán tính qua nhiều giá trị. (test
    // trước: 90dp/350ms ~257dp/s -> fling
    // trôi ~3.3 phút/lần thay vì đúng 1)
    private val swipeDistanceDp = 100f            // quãng đường vuốt cố định theo dp.
    // (test trước: 24dp -> chưa vượt touch
    // slop nên 0 hiệu ứng; 90dp -> đủ xa
    // nhưng kèm vận tốc cao gây fling. Giờ
    // giữ khoảng cách vừa phải ~55dp, dồn
    // việc "làm chậm" sang duration thay vì
    // giảm distance, để vẫn đủ vượt touch
    // slop + dịch chuyển ~1 dòng giá trị)
    // Vận tốc ước tính ~55dp / 0.9s ≈ 61 dp/s -> thấp hơn nhiều so với ~257dp/s
    // (trường hợp bị fling ở lần test trước), nên nhiều khả năng sẽ không kích
    // hoạt fling nữa.

    // Nhãn công tắc "đặt tự động" theo nhiều ngôn ngữ, phòng máy đổi ngôn ngữ khác
    private val autoLabels = listOf(
        "Đặt tự động", "Tự động xác định", "Tự động", "Đặt thời gian tự động",
        "Automatic date & time", "Automatic", "Set automatically"
    )

    private val okLabels = listOf("OK", "Đặt", "Xong", "Done", "Set")

    /**
     * Nhận diện node picker "giờ"/"phút" kiểu Compose (One UI mới):
     * text bắt đầu bằng 1-2 chữ số rồi tới "giờ" hoặc "phút" (tiếng Việt),
     * hoặc "hour"/"minute" (tiếng Anh), có thể dính liền không có khoảng
     * trắng (VD: "15giờBạn đang dùng..."). KHÔNG match chuỗi múi giờ kiểu
     * "giờ Hà Nội GMT+07:00" vì chuỗi đó không bắt đầu bằng chữ số.
     */
    private val pickerValueRegex = Regex(
        "^\\d{1,2}\\s*(giờ|phút|hour|minute)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Chuẩn hoá Unicode (NFC) + xoá khoảng trắng thừa trước khi so sánh chuỗi
     * tiếng Việt có dấu. Một số Settings app dựng chữ có dấu ở dạng tổ hợp
     * (NFD) khác với dạng dựng sẵn (NFC) trong code nguồn, khiến so sánh ==
     * bị sai dù nhìn giống hệt nhau.
     */
    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).trim()

    // Package name của app đang chạy (panel nổi) -> cần loại trừ khi tìm cửa sổ Settings
    private val ownPackage = service.packageName

    /**
     * QUAN TRỌNG: rootInActiveWindow có thể trả về cửa sổ panel nổi của
     * chính app mình (vì nó đang hiển thị đè lên & giữ focus), KHÔNG phải
     * cửa sổ Settings vừa mở. Nên phải duyệt qua danh sách windows để tìm
     * đúng cửa sổ thuộc app Settings.
     */


    private fun getSettingsRoot(): AccessibilityNodeInfo? {
        val windows = service.windows
        if (windows.isNullOrEmpty()) {
            return service.rootInActiveWindow
        }

        // Ưu tiên cửa sổ có package chứa "settings"
        for (w in windows) {
            val r = w.root ?: continue
            val pkg = r.packageName?.toString()
            if (pkg != null && pkg.contains("settings", ignoreCase = true)) {
                return r
            }
        }

        // Không thấy -> lấy cửa sổ bất kỳ không phải của chính app mình
        // và không phải systemui (status bar / navigation bar)
        for (w in windows) {
            val r = w.root ?: continue
            val pkg = r.packageName?.toString()
            if (pkg != null && pkg != ownPackage && !pkg.contains("systemui")) {
                return r
            }
        }

        return service.rootInActiveWindow
    }

    /**
     * Luồng CŨ: set thẳng giờ:phút tuyệt đối bằng ACTION_SET_TEXT.
     * (Chỉ hoạt động trên máy còn dùng android.widget.NumberPicker/EditText
     * kiểu Settings cũ; máy One UI mới dùng picker Compose sẽ không set được
     * theo cách này -> nên dùng startAddMinutes() thay thế.)
     */
    fun start(targetHour: Int, targetMinute: Int) {
        this.addMinutesMode = false
        this.targetHour = targetHour
        this.targetMinute = targetMinute
        this.retryCount = 0

        openDateSettingsAndBegin()
    }

    /**
     * Luồng MỚI: mở Settings, tìm 2 cột Giờ/Phút (dò theo text, không dò
     * theo class), rồi CUỘN/VUỐT cột Phút thêm đúng [minutesToAdd] nấc
     * (giữ nguyên giờ, không set tuyệt đối).
     *
     * Ví dụ: startAddMinutes(3) -> cộng thêm 3 phút vào giá trị đang hiển thị.
     */
    fun startAddMinutes(minutesToAdd: Int) {
        this.addMinutesMode = true
        this.minutesToAdd = minutesToAdd
        this.retryCount = 0

        openDateSettingsAndBegin()
    }

    private fun openDateSettingsAndBegin() {
        val intent = Intent(Settings.ACTION_DATE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            service.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Không mở được màn hình cài đặt ngày giờ: ${e.message}")
            return
        }

        handler.postDelayed({ step1_turnOffAutoIfNeeded() }, 700)
    }

    // ---------- Bước 1: nếu "Đặt tự động" đang BẬT thì tắt đi ----------
    private fun step1_turnOffAutoIfNeeded() {
        val root = getSettingsRoot()
        if (root == null) {
            retryOrGiveUp("step1", ::step1_turnOffAutoIfNeeded)
            return
        }

        val switchNode = findSwitchNearAnyLabel(root, autoLabels)

        if (switchNode == null) {
            // Danh sách trong Settings (RecyclerView) có thể chưa load xong -> thử lại
            if (retryCount < maxRetryPerStep) {
                retryCount++
                handler.postDelayed({ step1_turnOffAutoIfNeeded() }, retryDelayMs)
            } else {
                Log.d(TAG, "Không tìm thấy công tắc 'Đặt tự động' sau nhiều lần thử -> coi như đã tắt sẵn")
                retryCount = 0
                step2_findTimePickersInline()
            }
            return
        }

        Log.d(TAG, "Tìm thấy công tắc 'Đặt tự động', checked=${switchNode.isChecked}")

        if (switchNode.isChecked) {
            Log.d(TAG, "Đặt tự động đang BẬT -> tắt đi")
            clickNode(switchNode)
            retryCount = 0
            // Chờ UI hiện thêm mục Ngày/Thời gian rồi mới tìm picker
            handler.postDelayed({ step2_findTimePickersInline() }, 700)
        } else {
            retryCount = 0
            step2_findTimePickersInline()
        }
    }

    // ---------- Bước 2: tìm 2 cột số Giờ/Phút ngay trong trang ----------
    private fun step2_findTimePickersInline() {
        val root = getSettingsRoot()
        if (root == null) {
            retryOrGiveUp("step2", ::step2_findTimePickersInline)
            return
        }

        val pickers = ArrayList<AccessibilityNodeInfo>()
        collectPickerCandidates(root, pickers)

        if (pickers.size >= 2) {
            Log.d(TAG, "Tìm thấy ${pickers.size} node picker ngay trong trang (không cần bấm mở)")
            handlePickersFound(inDialog = false)
            return
        }

        // Chưa thấy trực tiếp -> thử bấm vào dòng "Thời gian" (dạng HH:mm)
        // để mở/bung ra 2 cột chọn (có thể mở popup, hoặc chỉ bung inline
        // ngay trong trang tuỳ máy)
        val timeRow = findNodeMatchingTimePattern(root)
        if (timeRow != null) {
            Log.d(TAG, "Chưa thấy picker, thử bấm dòng Thời gian để mở ra")
            clickNode(timeRow)
            retryCount = 0
            handler.postDelayed({ step3_findTimePickersAfterOpen() }, 700)
            return
        }

        retryOrGiveUp("step2", ::step2_findTimePickersInline)
    }

    // ---------- Bước 3 (dự phòng): tìm picker sau khi đã bấm mở ----------
    private fun step3_findTimePickersAfterOpen() {
        val root = getSettingsRoot()
        if (root == null) {
            retryOrGiveUp("step3", ::step3_findTimePickersAfterOpen)
            return
        }

        val pickers = ArrayList<AccessibilityNodeInfo>()
        collectPickerCandidates(root, pickers)

        if (pickers.size >= 2) {
            Log.d(TAG, "Tìm thấy ${pickers.size} node picker sau khi bấm mở")
            handlePickersFound(inDialog = true)
            return
        }

        // Chỉ áp dụng set tuyệt đối bằng EditText cho luồng CŨ (không hỗ trợ
        // cộng dồn bằng cuộn nếu máy chỉ có EditText thuần, không có picker)
        if (!addMinutesMode) {
            val editTexts = ArrayList<AccessibilityNodeInfo>()
            collectByClassName(root, "android.widget.EditText", editTexts)
            if (editTexts.size >= 2) {
                setEditTextValue(editTexts[0], targetHour.toString())
                setEditTextValue(editTexts[1], String.format("%02d", targetMinute))
                retryCount = 0
                handler.postDelayed({ step4_clickOkIfExists() }, 500)
                return
            }
        }

        retryOrGiveUp("step3", ::step3_findTimePickersAfterOpen)
    }
    private fun step5_turnOnAuto() {
        val root = getSettingsRoot() ?: return

        val switchNode = findSwitchNearAnyLabel(root, autoLabels)

        if (switchNode != null && !switchNode.isChecked) {
            Log.d(TAG, "Bật lại Đặt tự động")
            clickNode(switchNode)
        } else {
            Log.d(TAG, "Đặt tự động đã bật sẵn hoặc không tìm thấy")
        }
    }
    // ---------- Bước 4 (dự phòng, chỉ áp dụng nếu có popup + nút OK riêng) ----------
    private fun step4_clickOkIfExists() {
        val root = getSettingsRoot() ?: return
        val okNode = findClickableWithAnyText(root, okLabels)
        if (okNode != null) {
            Log.d(TAG, "Bấm OK để xác nhận giờ mới")
            clickNode(okNode)
        } else {
            Log.d(TAG, "Không có nút OK riêng, coi như giá trị đã tự áp dụng ngay khi cuộn")
        }
    }

    // ================= Xử lý khi đã tìm thấy đủ picker =================

    /**
     * Điều hướng theo chế độ đang chạy:
     * - addMinutesMode = true  -> cuộn/vuốt cột phút thêm [minutesToAdd] nấc
     * - addMinutesMode = false -> set thẳng giờ/phút tuyệt đối (luồng cũ,
     *   chỉ hoạt động nếu picker là android.widget.NumberPicker thật có
     *   EditText con)
     */
    private fun handlePickersFound(inDialog: Boolean) {
        if (addMinutesMode) {
            Log.d(TAG, "Bắt đầu cuộn/vuốt cột Phút thêm $minutesToAdd nấc")
            scrollPickerBySteps(
                pickerIndex = 1, // 0 = giờ, 1 = phút (theo thứ tự xuất hiện trong cây UI)
                times = minutesToAdd,
                forward = true
            ) {
                Log.d(TAG, "Đã cuộn/vuốt xong, cộng thêm $minutesToAdd phút")
                if (inDialog) {
//                    handler.postDelayed({ step4_clickOkIfExists() }, 300)
                    handler.postDelayed({
                        step5_turnOnAuto()
                    }, 1500)

                    handler.postDelayed({
                        FloatingPanel.instance?.clearInputFocus()
                        backToPreviousApp()
                    }, 2500)

                    handler.postDelayed({
                        FloatingPanel.instance?.performStartClick()
                    }, 4000)
                }
            }
        } else {
            val root = getSettingsRoot() ?: return
            val numberPickers = ArrayList<AccessibilityNodeInfo>()
            collectByClassName(root, "android.widget.NumberPicker", numberPickers)
            if (numberPickers.size >= 2) {
                applyPickerValues(numberPickers)
                if (inDialog) {
                    retryCount = 0
                    handler.postDelayed({ step4_clickOkIfExists() }, 500)
                }
            } else {
                Log.d(TAG, "Máy này không dùng android.widget.NumberPicker thật -> " +
                        "không set tuyệt đối được, hãy dùng startAddMinutes() thay thế")
            }
        }
    }

    /**
     * Cuộn/vuốt picker [times] nấc theo hướng [forward] (true = tăng, false = giảm).
     *
     * Ưu tiên performAction(ACTION_SCROLL_FORWARD/BACKWARD) NẾU node có khai
     * báo hỗ trợ action đó (kiểm tra qua actionList). Nếu không hỗ trợ (như
     * các picker Compose kiểu One UI mới chỉ nhận gesture), fallback gửi
     * gesture vuốt thật lên toạ độ của node bằng dispatchGesture.
     *
     * QUAN TRỌNG: sau mỗi nấc, node cũ có thể bị Android invalidate (stale)
     * do UI vừa cập nhật giá trị. Nên MỖI BƯỚC đều lấy lại root -> danh sách
     * picker -> đúng node theo [pickerIndex], KHÔNG tái sử dụng node cũ
     * xuyên suốt vòng lặp.
     */
    private fun scrollPickerBySteps(
        pickerIndex: Int,
        times: Int,
        forward: Boolean,
        onDone: () -> Unit
    ) {
        if (times <= 0) {
            onDone()
            return
        }

        val root = getSettingsRoot()
        if (root == null) {
            Log.d(TAG, "scrollPickerBySteps: chưa lấy được root, thử lại sau ${retryDelayMs}ms")
            handler.postDelayed({ scrollPickerBySteps(pickerIndex, times, forward, onDone) }, retryDelayMs)
            return
        }

        val pickers = ArrayList<AccessibilityNodeInfo>()
        collectPickerCandidates(root, pickers)

        val picker = pickers.getOrNull(pickerIndex)
        if (picker == null) {
            Log.d(TAG, "scrollPickerBySteps: không tìm lại được picker index=$pickerIndex, dừng")
            onDone()
            return
        }

        val scrollActionId = if (forward)
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        val supportsScrollAction = picker.actionList?.any { it.id == scrollActionId } == true

        if (supportsScrollAction) {
            val ok = picker.performAction(scrollActionId)
            Log.d(TAG, "performAction scroll picker[$pickerIndex] forward=$forward -> $ok (còn ${times - 1} nấc)")
            handler.postDelayed({
                scrollPickerBySteps(pickerIndex, times - 1, forward, onDone)
            }, scrollStepDelayMs)
        } else {
            Log.d(TAG, "Node picker[$pickerIndex] không hỗ trợ ACTION_SCROLL -> chuyển sang gửi gesture vuốt")
            performSwipeOnNode(picker, forward) {
                onDone()
            }
        }
    }

    /**
     * Gửi 1 gesture vuốt dọc (swipe) trên toạ độ màn hình của [node].
     *
     * forward = true (muốn TĂNG giá trị) -> vuốt từ dưới lên trên: ngón tay
     * đặt gần đáy node rồi kéo lên gần đỉnh node, giống thao tác "kéo danh
     * sách lên" để giá trị lớn hơn cuộn vào giữa.
     * forward = false (muốn GIẢM giá trị) -> vuốt ngược lại, từ trên xuống dưới.
     *
     * LƯU Ý: nếu chạy thực tế thấy giá trị chạy NGƯỢC hướng mong muốn
     * (bấm tăng nhưng số lại giảm), chỉ cần đảo ngược 2 nhánh if/else bên
     * dưới (swap startY/endY) — hướng vuốt tăng/giảm có thể khác nhau tuỳ
     * cách máy implement picker này.
     */
    private fun performSwipeOnNode(
        node: AccessibilityNodeInfo,
        forward: Boolean,
        onDone: () -> Unit
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() <= 0 || rect.height() <= 0) {
            Log.d(TAG, "performSwipeOnNode: bounds của node rỗng, không vuốt được")
            onDone()
            return
        }

        val centerX = rect.exactCenterX()
        val centerY = rect.exactCenterY()

        // Quãng đường vuốt CỐ ĐỊNH theo dp (đổi ra px theo mật độ màn hình),
        // không phụ thuộc chiều cao node -> tránh vuốt quá dài gây nhảy nhiều nấc.
        val density = service.resources.displayMetrics.density
        val halfDistancePx = (swipeDistanceDp * density) / 2f

        val startY: Float
        val endY: Float
        if (forward) {
            // vuốt lên: điểm bắt đầu ở dưới tâm 1 đoạn ngắn, kết thúc ở trên tâm
            startY = centerY + halfDistancePx
            endY = centerY - halfDistancePx
        } else {
            // vuốt xuống: ngược lại
            startY = centerY - halfDistancePx
            endY = centerY + halfDistancePx
        }

        Log.d(TAG, "Vuốt picker: centerY=$centerY, startY=$startY, endY=$endY, " +
                "distancePx=${halfDistancePx * 2}, durationMs=$gestureStrokeDurationMs")

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, gestureStrokeDurationMs))
            .build()

        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Vuốt picker hoàn tất")
                    handler.postDelayed(onDone, 120)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Vuốt picker bị huỷ giữa chừng")
                    handler.postDelayed(onDone, 120)
                }
            },
            handler
        )

        if (!dispatched) {
            Log.d(TAG, "dispatchGesture trả về false, không gửi được thao tác vuốt")
            onDone()
        }
    }

    // ================= Helper: nhận diện node picker theo text =================

    /**
     * Duyệt cây, thu thập các node clickable có text khớp [pickerValueRegex]
     * (VD: "15giờBạn đang dùng bộ chọn...", "00 phútBạn đang dùng..."),
     * theo đúng thứ tự xuất hiện trong cây (pre-order) -> index 0 thường là
     * giờ, index 1 thường là phút (đúng theo thứ tự hiển thị trái->phải /
     * trên->dưới trong dump thực tế).
     */
    private fun collectPickerCandidates(
        node: AccessibilityNodeInfo,
        out: ArrayList<AccessibilityNodeInfo>
    ) {
        val text = node.text?.toString()
        if (node.isClickable && text != null && pickerValueRegex.containsMatchIn(text)) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectPickerCandidates(child, out)
        }
    }

    // ================= Helper dùng chung (luồng cũ - set tuyệt đối) =================

    private fun applyPickerValues(numberPickers: ArrayList<AccessibilityNodeInfo>) {
        // Giả định thứ tự: [0] = giờ, [1] = phút, [2] (nếu có) = AM/PM
        setNumberPickerValue(numberPickers[0], targetHour.toString())
        setNumberPickerValue(numberPickers[1], targetMinute.toString())

        if (numberPickers.size >= 3) {
            val shouldBePM = targetHour >= 12
            val ampmNode = numberPickers[2]
            val currentText = ampmNode.text?.toString()?.uppercase() ?: ""
            val currentlyPM = currentText.contains("PM") || currentText.contains("CH")
            if (shouldBePM != currentlyPM) {
                clickNode(ampmNode)
            }
        }
    }

    private fun findNodeMatchingTimePattern(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val regex = Regex("^\\d{1,2}:\\d{2}(\\s?(AM|PM|CH|SA))?$", RegexOption.IGNORE_CASE)
        val text = node.text?.toString()?.trim()
        if (text != null && regex.matches(text)) {
            // Text khớp giờ có thể nằm ở 1 TextView con KHÔNG clickable,
            // trong khi cả dòng (LinearLayout cha) mới clickable=true.
            // Leo lên tìm node cha gần nhất clickable để bấm.
            var n: AccessibilityNodeInfo? = node
            while (n != null) {
                if (n.isClickable) return n
                n = n.parent
            }
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeMatchingTimePattern(child)
            if (found != null) return found
        }
        return null
    }

    private fun collectByClassName(
        node: AccessibilityNodeInfo,
        className: String,
        out: ArrayList<AccessibilityNodeInfo>
    ) {
        if (node.className?.toString() == className) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectByClassName(child, className, out)
        }
    }

    private fun findClickableWithAnyText(
        node: AccessibilityNodeInfo,
        candidates: List<String>
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.let { normalize(it) }
        val normalizedCandidates = candidates.map { normalize(it) }
        if (node.isClickable && text != null &&
            normalizedCandidates.any { it.equals(text, ignoreCase = true) }
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableWithAnyText(child, candidates)
            if (found != null) return found
        }
        return null
    }

    /**
     * Tìm công tắc (Switch/Checkable) nằm gần một TextView có text khớp
     * một trong các nhãn cho trước (cùng nằm trong 1 khối cha gần nhất).
     */
    private fun findSwitchNearAnyLabel(
        node: AccessibilityNodeInfo,
        labels: List<String>
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.let { normalize(it) }
        val normalizedLabels = labels.map { normalize(it) }
        if (text != null && normalizedLabels.any { it.equals(text, ignoreCase = true) }) {
            // Đi lên vài cấp cha để tìm khối chứa cả label lẫn công tắc
            var container = node.parent
            var levelsUp = 0
            while (container != null && levelsUp < 3) {
                val switchNode = findFirstCheckable(container)
                if (switchNode != null) return switchNode
                container = container.parent
                levelsUp++
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSwitchNearAnyLabel(child, labels)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstCheckable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isCheckable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstCheckable(child)
            if (found != null) return found
        }
        return null
    }

    private fun setNumberPickerValue(picker: AccessibilityNodeInfo, value: String) {
        val editChild = findFirstEditText(picker)
        if (editChild != null) {
            setEditTextValue(editChild, value)
        } else {
            Log.d(TAG, "NumberPicker không có EditText con để set trực tiếp")
        }
    }

    private fun findFirstEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString() == "android.widget.EditText") return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditText(child)
            if (found != null) return found
        }
        return null
    }

    private fun setEditTextValue(node: AccessibilityNodeInfo, value: String) {
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE.toString(),
            value
        )
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "Set text '$value' -> $ok")
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        // Nếu bản thân node không clickable (VD: Switch bên trong 1 dòng list),
        // ưu tiên bấm vào node cha gần nhất có clickable=true (thường là cả dòng).
        if (!node.isClickable) {
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
                parent = parent.parent
            }
        }

        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    break
                }
                parent = parent.parent
            }
        }
    }

    private fun retryOrGiveUp(stepName: String, retryFn: () -> Unit) {
        retryCount++
        if (retryCount <= maxRetryPerStep) {
            handler.postDelayed(retryFn, retryDelayMs)
        } else {
            Log.d(TAG, "$stepName: hết số lần thử, dừng tự động, cần chỉnh tay")
            val root = getSettingsRoot()
            if (root != null) {
                Log.d(TAG, "===== DUMP CÂY GIAO DIỆN HIỆN TẠI =====")
                dumpTree(root, 0)
                Log.d(TAG, "===== HẾT DUMP =====")
            } else {
                Log.d(TAG, "Không lấy được cửa sổ Settings để dump")
            }
            retryCount = 0
        }
    }

    /** In ra toàn bộ cây node (class, text, resource-id, clickable/checkable) để chẩn đoán UI thật */
    private fun dumpTree(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val cls = node.className ?: "?"
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val resId = node.viewIdResourceName
        Log.d(
            TAG,
            "$indent[$cls] text='${text}' desc='${desc}' resId='${resId}' " +
                    "clickable=${node.isClickable} checkable=${node.isCheckable} checked=${node.isChecked}"
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpTree(child, depth + 1)
        }
    }

    private fun backToPreviousApp() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }
//    private fun backToPreviousApp() {
//        service.performGlobalAction(
//            AccessibilityService.GLOBAL_ACTION_RECENTS
//        )
//
//        handler.postDelayed({
//            clickTikTokInRecents()
//        }, 800)
//    }
//    private fun findNodeByText(
//        node: AccessibilityNodeInfo,
//        text: String
//    ): AccessibilityNodeInfo? {
//
//        val nodeText = node.text?.toString() ?: ""
//        val desc = node.contentDescription?.toString() ?: ""
//
//        if (nodeText.contains(text, ignoreCase = true) ||
//            desc.contains(text, ignoreCase = true)
//        ) {
//            return node
//        }
//
//        for (i in 0 until node.childCount) {
//            val child = node.getChild(i) ?: continue
//            val result = findNodeByText(child, text)
//            if (result != null) return result
//        }
//
//        return null
//    }
//    private fun clickTikTokInRecents() {
//        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
//
//        handler.postDelayed({
//
//            val root = service.rootInActiveWindow ?: return@postDelayed
//
//            val node = findNodeByText(root, "TikTok")
//            if (node != null) {
//                clickNode(node)
//
//                // Đợi Recents đóng rồi tìm lại
//                handler.postDelayed({
//
//                    val root2 = service.rootInActiveWindow ?: return@postDelayed
//                    val node2 = findNodeByText(root2, "TikTok")
//
//                    if (node2 != null) {
//                        clickNode(node2)
//                    }
//
//                }, 500)
//
//            }
//
//        }, 700)
//    }
}