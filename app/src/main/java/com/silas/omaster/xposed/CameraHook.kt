package com.silas.omaster.xposed

import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CameraHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "OMaster-CameraHook"
        private const val CAMERA_PACKAGE = "com.oplus.camera"
        private const val OUTPUT_DIR = "/data/data/$CAMERA_PACKAGE/files"
        private const val OUTPUT_FILE = "omaster_filter_map.json"

        @Volatile
        private var hasCapturedData = false
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != CAMERA_PACKAGE) return

        XposedBridge.log("$TAG: OMaster CameraHook 已加载 - 版本 6.105.84")
        
        hookFilterMethods(lpparam.classLoader)
    }

    private fun hookFilterMethods(classLoader: ClassLoader) {
        val targetClasses = arrayOf(
            "com.oplus.camera.filter.FilterGroupManager",
            "com.oplus.camera.filter.FilterManager",
            "com.oneplus.camera.filter.FilterGroupManager",
            "com.oneplus.camera.filter.FilterManager"
        )

        val targetMethods = arrayOf("initFromIpu", "init", "loadFilters", "initialize")

        for (className in targetClasses) {
            for (methodName in targetMethods) {
                try {
                    XposedHelpers.findAndHookMethod(
                        className,
                        classLoader,
                        methodName,
                        Context::class.java,
                        HashMap::class.java,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                processFilterData(param)
                            }
                        }
                    )
                    XposedBridge.log("$TAG: Hooked $className.$methodName")
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun processFilterData(param: XC_MethodHook.MethodHookParam) {
        if (hasCapturedData) return

        try {
            val context = param.args[0] as? Context ?: return
            @Suppress("UNCHECKED_CAST")
            val filterMap = param.args[1] as? HashMap<String, List<HashMap<String, Any>>> ?: return

            if (filterMap.isEmpty()) return

            hasCapturedData = true
            XposedBridge.log("$TAG: Captured filter map with ${filterMap.size} modes")

            val json = parseFilterMap(context, filterMap)
            writeToFile(json)
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Error processing filter data: ${e.message}")
        }
    }

    private fun parseFilterMap(context: Context, filterMap: HashMap<String, List<HashMap<String, Any>>>): JSONObject {
        val root = JSONObject()
        root.put("captureTime", System.currentTimeMillis())
        root.put("cameraPackage", CAMERA_PACKAGE)
        root.put("version", "6.105.84")

        val modesObj = JSONObject()

        for ((mode, filterList) in filterMap) {
            val filtersArray = JSONArray()

            for ((index, filterEntry) in filterList.withIndex()) {
                val filterObj = JSONObject()
                filterObj.put("index", index)

                val lutFile = filterEntry["drawing_item_filter_type"]?.toString() ?: "unknown"
                filterObj.put("lutFile", lutFile)

                val resourceId = when (val nameVal = filterEntry["drawing_item_filter_name"]) {
                    is Number -> nameVal.toInt()
                    is String -> nameVal.toIntOrNull() ?: 0
                    else -> 0
                }
                filterObj.put("resourceId", resourceId)

                val displayName = try {
                    if (resourceId != 0) context.getString(resourceId) else "Unknown"
                } catch (e: Exception) {
                    "ID:$resourceId"
                }
                filterObj.put("name", displayName)

                val isMaster = when (val masterVal = filterEntry["drawing_item_is_master"]) {
                    is Number -> masterVal.toInt()
                    is String -> masterVal.toIntOrNull() ?: 0
                    else -> 0
                }
                filterObj.put("isMaster", isMaster)

                filtersArray.put(filterObj)
            }

            modesObj.put(mode, filtersArray)
        }

        root.put("modes", modesObj)
        return root
    }

    private fun writeToFile(json: JSONObject) {
        try {
            val dir = File(OUTPUT_DIR)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, OUTPUT_FILE)
            file.writeText(json.toString(2))
            file.setReadable(true, false)

            XposedBridge.log("$TAG: Filter map written to ${file.absolutePath}")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Failed to write file: ${e.message}")
        }
    }
}