package com.pengxh.daily.protocol

import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type

/**
 * PacketValue 是 sealed 抽象类，Gson 默认无法将其 JSON（`{"b":true}` / `{"i":60}` / `{"s":"x"}`）
 * 反序列化为具体子类，会直接抛异常导致远程指令静默失效。
 *
 * 此适配器按「字段存在性」判定具体子类，与控制端 Gson 序列化产物（BooleanValue→b /
 * IntValue→i / StringValue→s）严格对应。仅用于反序列化；序列化仍走 Gson 默认行为。
 */
object PacketValueAdapter : JsonDeserializer<PacketValue> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: com.google.gson.JsonDeserializationContext
    ): PacketValue {
        if (json.isJsonNull || !json.isJsonObject) {
            throw JsonParseException("PacketValue 期望 JSON 对象，实际: $json")
        }
        val obj = json.asJsonObject
        return when {
            obj.has("b") -> PacketValue.BooleanValue(obj["b"].asBoolean)
            obj.has("i") -> PacketValue.IntValue(obj["i"].asInt)
            obj.has("s") -> PacketValue.StringValue(obj["s"].asString)
            else -> throw JsonParseException("无法识别的 PacketValue 变体: $obj")
        }
    }
}
