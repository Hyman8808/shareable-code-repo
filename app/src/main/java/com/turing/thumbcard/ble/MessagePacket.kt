package com.turing.thumbcard.ble

/**
 * 协议帧。
 *
 * 结构：Cmd(1) + Sequence(1) + Flag(1) + NO(1) + PAYLOAD(N) + CRC(1)
 *
 * 移植自联盛德官方 Android SDK `MessagePacket`。
 */
class MessagePacket {

    var cmd: Byte = 0
    var seq: Byte = 0
    var flag: Byte = 0
    var no: Byte = 0
    var payload: ByteArray = ByteArray(0)

    /**
     * 组装后的完整帧字节。
     *
     * 注：Kotlin 会为其自动生成 `getPackageInfo()`，与官方 SDK 的 Java 调用风格一致，
     * 因此不再手写同名方法（否则 JVM 签名冲突）。
     */
    var packageInfo: ByteArray = ByteArray(0)
        private set

    /**
     * 组装完整帧。
     *
     * @param seq 序列号
     * @param ack 是否要求对方回复 ack
     */
    fun buildPacket(seq: Int, ack: Boolean) {
        // 帧长 = PAYLOAD + 5（Cmd/Seq/Flag/NO/CRC）
        val frame = ByteArray(payload.size + 5)
        frame[0] = cmd
        frame[1] = intToByte(seq)
        if (ack) {
            flag = (flag.toInt() or BleConstants.PACKET_FLAG_ACK_MASK).toByte()
        }
        frame[2] = flag
        frame[3] = no
        System.arraycopy(payload, 0, frame, 4, payload.size)
        // CRC 覆盖 Cmd..PAYLOAD
        frame[4 + payload.size] = Crc8.calc(frame, frame.size - 1, 0)
        packageInfo = frame
    }

    companion object {

        /**
         * 解析收到的帧。CRC 校验失败返回 null。
         *
         * 帧长下限：最小合法帧为「空 PAYLOAD」= Cmd(1)+Seq(1)+Flag(1)+NO(1)+CRC(1) = 5 字节。
         * 因此判定条件为 size < 5，而非 <= 5 —— 否则空 PAYLOAD 帧会被误丢弃。
         */
        fun parsePacket(data: ByteArray?): MessagePacket? {
            if (data == null || data.size < 5) return null
            val expectCrc = Crc8.calc(data, data.size - 1, 0)
            if (expectCrc != data[data.size - 1]) return null

            val packet = MessagePacket()
            packet.cmd = data[0]
            packet.seq = data[1]
            packet.flag = data[2]
            packet.no = data[3]
            packet.payload = ByteArray(data.size - 5)
            System.arraycopy(data, 4, packet.payload, 0, data.size - 5)
            return packet
        }

        fun intToByte(x: Int): Byte = (x and 0xFF).toByte()

        fun byteToInt(b: Byte): Int = b.toInt() and 0xFF
    }
}
