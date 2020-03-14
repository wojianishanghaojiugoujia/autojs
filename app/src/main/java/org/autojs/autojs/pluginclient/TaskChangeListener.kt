package org.autojs.autojs.pluginclient

import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.execution.ScriptExecutionListener
import com.stardust.util.MapBuilder
import org.autojs.autojs.pluginclient.Utils.val2json
import java.util.*

class TaskChangeListener internal constructor(private val service: DevPluginService) : ScriptExecutionListener {
    override fun onStart(execution: ScriptExecution) {
        val data = serializeScriptExecution(execution)
        data.addProperty("state", SCRIPT_STATE_START)
        service.sendCommand(CMD_SCRIPT_CHANGED, data)
    }

    override fun onSuccess(execution: ScriptExecution, result: Any) {
        val data = serializeScriptExecution(execution)
        data.addProperty("state", SCRIPT_STATE_END)
        service.sendCommand(CMD_SCRIPT_CHANGED, data)
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    override fun onException(execution: ScriptExecution, e: Throwable) {
        val data = serializeScriptExecution(execution)
        val traces = ArrayList<String?>()
        traces.add(e.message)
        for (ste in e.stackTrace) {
            traces.add(ste.toString())
        }
        data.add("stack_trace", val2json(traces))
        data.addProperty("state", SCRIPT_STATE_ERROR)
        service.sendCommand(CMD_SCRIPT_CHANGED, data)
    }

    private fun serializeScriptExecution(execution: ScriptExecution): JsonObject {
        return val2json(mapOf<String, Any>(
                "id" to execution.id,
                "sourceName" to execution.source.name,
                "executionConfig" to execution.config,
                "argv" to execution.config.arguments
        )) as JsonObject
    }

    companion object {
        const val CMD_SCRIPT_CHANGED = "script:changed"
        private val SCRIPT_STATE_UNKNOWN: Number = -1 // 未开始
        private val SCRIPT_STATE_READY: Number = 0 // 未开始
        private val SCRIPT_STATE_START: Number = 1 // 开始
        private val SCRIPT_STATE_END: Number = 2 // 结束
        private val SCRIPT_STATE_ERROR: Number = 3 // 错误
    }
}