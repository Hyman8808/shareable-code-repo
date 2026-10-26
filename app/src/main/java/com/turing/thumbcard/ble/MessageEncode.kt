package com.turing.thumbcard.ble

import com.turing.thumbcard.crypto.AesUtils
import java.io.ByteArrayOutputStream

/**
 * TLV 编解码 + 分片。
 *
 * 移植自联盛德官方 Android SDK `MessageEncode`。
 *
 * 编码流程：
 *   TLV 拼装 -> [AES 加密] -> 按 14 字节分片 -> 每片包装成 MessagePacket
 *
 * 解码流程：
 *   合并分片 PAYLOAD -> [AES 解密] -> 按 Type/Length 拆 TLV -> 存入 map
 */
class MessageEncode {

    /** 编码后的帧列表 */
    private val messagePacketList = ArrayList<MessagePacket>()

    /** 解码后的 TLV 集合 */
    private val tlvMap = HashMap<Int, ByteArray>()

    private val packDataList = ArrayList<ByteArray>()

    // ==================== 编码 ====================

    fun putStringValue(type: Int, value: String) {
        putBytesValue(type, value.toByteArray(Charsets.UTF_8))
    }

    fun putBytesValue(type: Int, value: ByteArray) {
        tlvMap[type] = value
    }

    fun putByteValue(type: Int, value: Int) {
        putBytesValue(type, byteArrayOf((value and 0xFF).toByte()))
    }

    /**
     * 组装 TLV 流并分片，生成帧列表。
     *
     * @param cmd     命令码
     * @param encrypt 是否加密
     * @param aesKey  会话密钥（encrypt=true 时必填）
     */
    fun encode(cmd: Byte, encrypt: Boolean, aesKey: ByteArray?) {
        serialize(encrypt, aesKey)

        packDataList.forEachIndexed { index, bytes ->
            val packet = MessagePacket()
            packet.cmd = cmd
            packet.seq = 0
            // 非末片置"有后续"标志
            packet.flag = if (index + 1 < packDataList.size) {
                BleConstants.PACKET_FLAG_LAST_PACKET_MASK.toByte()
            } else {
                0
            }
            // 注：官方 SDK 中此处逻辑为「非末片置位」，与协议文档描述相反，
            // 但经三端 Demo 验证以官方实现为准。
            if (encrypt) {
                packet.flag = (packet.flag.toInt() or BleConstants.PACKET_FLAG_ENCRYPT_MASK).toByte()
            }
            packet.no = MessagePacket.intToByte(index)
            packet.payload = bytes
            messagePacketList.add(packet)
        }
    }

    /**
     * 将 TLV 集合序列化为字节流，可选加密，然后按 14 字节分片。
     */
    private fun serialize(encrypt: Boolean, aesKey: ByteArray?) {
        if (tlvMap.isEmpty()) return
        packDataList.clear()

        // 1. 拼 TLV
        val out = ByteArrayOutputStream()
        tlvMap.forEach { (type, value) ->
            out.write(type and 0xFF)
            out.write(value.size and 0xFF)
            out.write(value)
        }
        var data = out.toByteArray()

        // 2. 加密
        if (encrypt) {
            data = AesUtils.encrypt(data, aesKey) ?: return
        }

        // 3. 分片
        var offset = 0
        var remaining = data.size
        while (remaining > 0) {
            val len = if (remaining > BleConstants.PACKET_MAX_LENGTH_800) {
                BleConstants.PACKET_MAX_LENGTH_800
            } else {
                remaining
            }
            packDataList.add(data.copyOfRange(offset, offset + len))
            offset += len
            remaining -= len
        }
    }

    fun getMessagePacketList(): List<MessagePacket> = messagePacketList

    /**
     * 直接以**裸 PAYLOAD** 组装单帧，不走 TLV。
     *
     * 仅用于 `0x10` Ack —— 按协议它的 PAYLOAD 是「1 个字节的 Sequence」，
     * 不是 TLV（不是 `Type+Length+Value`）。用 [encode] 会多塞 2 个字节，
     * 设备侧解析出的 Seq 就错了。
     */
    fun encodeRaw(cmd: Byte, payload: ByteArray, encrypt: Boolean, aesKey: ByteArray?) {
        var data = payload
        if (encrypt) {
            data = AesUtils.encrypt(data, aesKey) ?: return
        }
        val packet = MessagePacket()
        packet.cmd = cmd
        packet.seq = 0
        packet.no = 0
        packet.flag = if (encrypt) BleConstants.PACKET_FLAG_ENCRYPT_MASK.toByte() else 0
        packet.payload = data
        messagePacketList.add(packet)
    }


    // ==================== 解码 ====================

    /**
     * 合并收到的分片并解码为 TLV 集合。
     *
     * @param aesKey 会话密钥。若收到的帧未加密，可传 null
     * @return 解析出的 TLV 集合；失败返回 null
     */
    fun decode(aesKey: ByteArray?): Map<Int, ByteArray>? {
        if (messagePacketList.isEmpty()) return null

        var flag: Byte = 0
        val buffer = ByteArrayOutputStream()
        try {
            for (packet in messagePacketList) {
                buffer.write(packet.payload)
                flag = packet.flag
            }
        } catch (e: Exception) {
            return null
        }

        var data = buffer.toByteArray()

        // 解密
        if ((flag.toInt() and BleConstants.PACKET_FLAG_ENCRYPT_MASK) != 0) {
            data = AesUtils.decrypt(data, aesKey) ?: return null
        }

        // 按 TLV 拆解
        tlvMap.clear()
        var i = 0
        while (i < data.size) {
            if (i + 2 > data.size) return null
            val type = data[i].toInt() and 0xFF
            val length = data[i + 1].toInt() and 0xFF
            i += 2
            if (i + length > data.size) return null
            tlvMap[type] = data.copyOfRange(i, i + length)
            i += length
        }
        // 返回副本：避免调用方持有的引用在下次 decode() 时被 clear() 清空
        return HashMap(tlvMap)
    }

    /** 加入一个收到的帧（用于多帧合并） */
    fun addMessagePacket(packet: MessagePacket?) {
        if (packet != null) messagePacketList.add(packet)
    }

    /** 清空接收缓冲，准备下一次接收 */
    fun resetRecv() {
        messagePacketList.clear()
    }
}
