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

        private val KEY_FILTER_TYPES = listOf(
            "drawing_item_filter_type", "filter_type", "filterType", "type", "lut_file", "lutFile"
        )
        private val KEY_FILTER_NAMES = listOf(
            "drawing_item_filter_name", "filter_name", "filterName", "name", "res_id", "resId"
        )
        private val KEY_IS_MASTERS = listOf(
            "drawing_item_is_master", "is_master", "isMaster", "master", "group"
        )

        @Volatile
        private var hasCapturedData = false
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != CAMERA_PACKAGE) return

        XposedBridge.log("$TAG: OMaster CameraHook 已加载 - 6.105.84 兼容版")
        hookKnownClasses(lpparam.classLoader)
        hookInitMethods(lpparam.classLoader)
    }

    private fun hookKnownClasses(classLoader: ClassLoader) {
        val knownClasses = listOf(
            "com.oplus.camera.filter.FilterGroupManager",
            "com.oplus.camera.filter.FilterManager", 
            "com.oplus.camera.filter.FilterConfig",
            "com.oplus.camera.filter.MasterFilterManager",
            "com.oneplus.camera.filter.FilterGroupManager",
            "com.oneplus.camera.filter.FilterManager"
        )

        val knownMethods = listOf(
            "initFromIpu", "init", "loadFilters", "initialize", 
            "setupFilters", "loadFilterData"
        )

        for (className in knownClasses) {
            for (methodName in knownMethods) {
                tryHookMethod(classLoader, className, methodName)
            }
        }
    }

    private fun hookInitMethods(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        scanForFilterClasses(param.thisObject.javaClass.classLoader)
                    }
                }
            )
        } catch (e: Exception) {}
    }

    private fun scanForFilterClasses(classLoader: ClassLoader?) {
        if (classLoader == null) return
        val possibleClasses = listOf(
            "com.oplus.camera.module.BaseModule",
            "com.oplus.camera.CameraApp"
        )
        // ... 扫描逻辑
    }

    private fun tryHookMethod(classLoader: ClassLoader, className: String, methodName: String) {
        val paramCombinations = listOf(
            arrayOf(Context::class.java, HashMap::class.java),
            arrayOf(HashMap::class.java),
            arrayOf(Context::class.java),
            arrayOf()
        )
        // ... hook 逻辑
    }

    private fun processHookResult(param: MethodHookParam, className: String, methodName: String) {
        if (hasCapturedData) return
        // ... 处理逻辑
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryCastToFilterMap(map: HashMap<*, *>): HashMap<String, List<HashMap<String, Any>>>? {
        // ... 转换逻辑
        return null
    }

    private fun parseFilterMap(context: Context, filterMap: HashMap<String, List<HashMap<String, Any>>>): JSONObject {
        // ... 解析逻辑
        return JSONObject()
    }

    private fun findValueByKeys(map: HashMap<String, Any>, keys: List<String>): Any? {
        for (key in keys) {
            val value = map[key]
            if (value != null) return value
        }
        return null
    }

    private fun resolveFilterName(context: Context, resourceId: Int): String {
        if (resourceId == 0) return "未知"
        return try {
            context.getString(resourceId)
        } catch (e: Exception) {
            "ID:$resourceId"
        }
    }

    private fun writeFilterMapToFile(json: JSONObject) {
        try {
            val dir = File(OUTPUT_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, OUTPUT_FILE)
            file.writeText(json.toString(2))
            file.setReadable(true, false)
            XposedBridge.log("$TAG: ✓ 滤镜映射已写入")
        } catch (e: Exception) {}
    }
}
