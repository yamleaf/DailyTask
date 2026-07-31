package com.pengxh.daily.protocol

import androidx.annotation.Keep

@Keep
sealed class PacketValue {
    data class BooleanValue(val b: Boolean) : PacketValue()
    data class IntValue(val i: Int) : PacketValue()
    data class StringValue(val s: String) : PacketValue()

    fun toBooleanStrict(): Boolean = (this as BooleanValue).b
    fun toInt(): Int = (this as IntValue).i
    fun toStringValue(): String = (this as StringValue).s
}

@Keep
data class MqttPacket(
    val c: String,          // S, U, A, N, B
    val f: String,          // ps, pm, tm, ta, ax, cx
    val v: PacketValue?,
    val rid: String,
    val ts: Long,
    val sign: String
)
