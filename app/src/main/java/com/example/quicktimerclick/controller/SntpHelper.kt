package com.example.quicktimerclick.utils

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object SntpHelper {

    // Chênh lệch giữa NTP epoch (1900-01-01) và Unix epoch (1970-01-01), tính bằng giây
    private const val NTP_EPOCH_DIFF = 2208988800L

    /**
     * Lấy offset (ms) = giờ NTP server - giờ điện thoại, đo qua giao thức SNTP (UDP, port 123).
     * Lấy nhiều mẫu (sampleCount lần), chọn mẫu có round-trip (độ trễ mạng) THẤP NHẤT.
     * Trả về null nếu tất cả các lần đo đều thất bại.
     */
    fun getNtpOffset(
        ntpHost: String = "time.google.com",
        sampleCount: Int = 4,
        timeoutMs: Int = 3000
    ): Long? {

        var bestOffset: Long? = null
        var bestRoundTrip = Long.MAX_VALUE

        repeat(sampleCount) {
            val result = querySingleSample(ntpHost, timeoutMs)
            if (result != null) {
                val (offset, roundTrip) = result
                if (roundTrip < bestRoundTrip) {
                    bestRoundTrip = roundTrip
                    bestOffset = offset
                }
                android.util.Log.d(
                    "SNTP",
                    "Mẫu đo: offset=${offset}ms, roundTrip=${roundTrip}ms"
                )
            }
        }

        if (bestOffset != null) {
            android.util.Log.d(
                "SNTP",
                "Chọn mẫu tốt nhất: offset=${bestOffset}ms, roundTrip=${bestRoundTrip}ms"
            )
        } else {
            android.util.Log.e("SNTP", "Tất cả $sampleCount mẫu đều thất bại")
        }

        return bestOffset
    }

    private fun querySingleSample(ntpHost: String, timeoutMs: Int): Pair<Long, Long>? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs

            val address = InetAddress.getByName(ntpHost)
            val buffer = ByteArray(48)

            // Byte đầu tiên: LI=0, VN=3 (NTP version 3), Mode=3 (client)
            buffer[0] = 0x1B

            val requestPacket = DatagramPacket(buffer, buffer.size, address, 123)

            val requestTime = System.currentTimeMillis()
            socket.send(requestPacket)

            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket)

            val responseTime = System.currentTimeMillis()
            val roundTrip = responseTime - requestTime

            // "Transmit Timestamp" nằm ở byte 40-43 (giây nguyên) và 44-47 (phần thập phân)
            val seconds = readUInt32(buffer, 40)
            val fraction = readUInt32(buffer, 44)

            val serverMillisSince1900 = seconds * 1000L + (fraction * 1000L / 0x100000000L)
            val serverMillisUnix = serverMillisSince1900 - NTP_EPOCH_DIFF * 1000L

            val estimatedServerNow = serverMillisUnix + (roundTrip / 2)
            val offset = estimatedServerNow - responseTime

            Pair(offset, roundTrip)

        } catch (e: Exception) {
            android.util.Log.e("SNTP", "Lỗi truy vấn $ntpHost: ${e.javaClass.simpleName} - ${e.message}")
            null
        } finally {
            socket?.close()
        }
    }

    private fun readUInt32(buffer: ByteArray, offset: Int): Long {
        return ((buffer[offset].toLong() and 0xFF) shl 24) or
                ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
                ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
                (buffer[offset + 3].toLong() and 0xFF)
    }
}