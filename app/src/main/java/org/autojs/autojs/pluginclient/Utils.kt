package org.autojs.autojs.pluginclient

import android.os.Build
import androidx.annotation.AnyThread
import com.google.gson.*
import com.stardust.app.GlobalAppContext
import com.stardust.autojs.runtime.api.Device
import com.stardust.util.MapBuilder
import org.autojs.autojs.BuildConfig

object Utils {
    private val gson: Gson = Gson()
    private val device: Device = Device(GlobalAppContext.get())

    @JvmStatic
    val IMEIs: ArrayList<String>
        get() = device.imeIs

    @JvmStatic
    val IMEI: String?
        get() = device.imei

    @JvmStatic
    val MAC: String
        get() {
            var mac = ""
            try {
                mac = device.macAddress
            } catch (ignored: Exception) {
            }
            return mac
        }

    @JvmStatic
    val deviceInfo: Map<String, Any?>
        get() {
            val imeis = IMEIs
            return MapBuilder<String, Any?>()
                    .put("CPU_ABI", Build.CPU_ABI)
                    .put("CPU_ABI2", Build.CPU_ABI2)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("host", Build.HOST)
                    .put("build_display", Device.buildDisplay)
                    .put("product", Device.product)
                    .put("device", Device.device)
                    .put("board", Device.board)
                    .put("brand", Device.brand)
                    .put("model", Device.model)
                    .put("bootloader", Device.bootloader)
                    .put("hardware", Device.hardware)
                    .put("incremental", Device.incremental)
                    .put("release", Device.release)
                    .put("build_id", Device.buildId)
                    .put("width", Device.width)
                    .put("height", Device.height)
                    .put("base_OS", Device.baseOS)
                    .put("security_patch", Device.securityPatch)
                    .put("fingerprint", Device.fingerprint)
                    .put("sdk_int", Device.sdkInt)
                    .put("codename", Device.codename)
                    .put("serial", device.serial)
                    .put("IMEIs", imeis)
                    .put("IMEI", device.imei)
                    .put("mac", MAC)
                    .put("android_id", device.androidId)
                    .put("device_name", Build.BRAND + " " + Build.MODEL)
                    .put("device_id", device.imei)
                    .put("client_version", DevPluginService.CLIENT_VERSION)
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("app_version_code", BuildConfig.VERSION_CODE)
                    .build()
        }

    @JvmStatic
    @AnyThread
    fun json2val(json: JsonElement): Any? {
        return when (json) {
            is JsonObject -> json2map(json)
            is JsonArray -> json2arr(json)
            is JsonPrimitive ->
                when {
                    json.isBoolean -> json.asBoolean
                    json.isNumber -> json.asNumber
                    json.isString -> json.asString
                    else -> null
                }
            else -> null
        }
    }

    @JvmStatic
    @AnyThread
    fun val2json(v: Any?): JsonElement {
        return when (v) {
            is Boolean -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Char -> JsonPrimitive(v)
            is Map<*, *> -> map2json(v)
            is List<*> -> arr2json(v)
            else -> JsonNull.INSTANCE
        }
    }

    private fun json2arr(json: JsonArray): List<*> {
        val list = ArrayList<Any?>()
        for (el in json) {
            list.add(json2val(el))
        }
        return list
    }

    private fun json2map(json: JsonObject): Map<String, *> {
        val map = MapBuilder<String, Any?>()

        for (key in json.keySet()) {
            val item = json[key]
            when {
                item.isJsonArray -> {
                    map.put(key, json2arr(item as JsonArray))
                }
                item.isJsonObject -> {
                    map.put(key, json2map(item as JsonObject))
                }
                item.isJsonNull -> {
                    map.put(key, null)
                }
                else -> {
                    map.put(key, json2val(item))
                }
            }
            map.put(key, item)
        }

        return map.build();
    }

    private fun arr2json(arr: List<*>): JsonArray {
        val jarr = JsonArray()
        for (value in arr) {
            when (value) {
                is String -> jarr.add(value)
                is Char -> jarr.add(value)
                is Number -> jarr.add(value)
                is Boolean -> jarr.add(value)
                is JsonElement -> jarr.add(value)
                else -> jarr.add(val2json(value))
            }
        }
        return jarr
    }

    private fun map2json(map: Map<*, *>): JsonObject {
        val data = JsonObject()
        for ((key, value1) in map) {
            when (val value = value1!!) {
                is String -> data.addProperty(key.toString(), value)
                is Char -> data.addProperty(key.toString(), value)
                is Number -> data.addProperty(key.toString(), value)
                is Boolean -> data.addProperty(key.toString(), value)
                else -> data.add(key.toString(), val2json(value))
            }
        }
        return data
    }
}