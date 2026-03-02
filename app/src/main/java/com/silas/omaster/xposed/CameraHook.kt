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

/**
 * Xposed 模块入口，运行在 com.oplus.camera 进程中
 *
 * 核心功能：
 * 1. Hook 多个可能的滤镜管理类和方法以捕获滤镜列表
 * 2. 解析 HashMap<String, List<HashMap<String, Object>>> 为结构化数据
 * 3. 将滤镜映射写入 JSON 文件供 OMaster 主进程读取
 *
 * 版本兼容性：支持一加/OPPO/Realme 相机版本 6.x+
 */
class CameraHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "OMaster-CameraHook"
        private const val CAMERA_PACKAGE = "com.oplus.camera"

        // 输出文件路径（写入相机 data 目录，OMaster 通过 Root 读取）
        private const val OUTPUT_DIR = "/data/data/$CAMERA_PACKAGE/files"
        private const val OUTPUT_FILE = "omaster_filter_map.json"

        // HashMap 中滤镜条目的 key（多种可能的 key 名）
        private val KEY_FILTER_TYPES = listOf(
            "drawing_item_filter_type",
            "filter_type",
            "filterType",
            "type",
            "lut_file",
            "lutFile"
        )
        private val KEY_FILTER_NAMES = listOf(
            "drawing_item_filter_name",
            "filter_name",
            "filterName",
            "name",
            "res_id",
            "resId"
        )
        private val KEY_IS_MASTERS = listOf(
            "drawing_item_is_master",
            "is_master",
            "isMaster",
            "master",
            "group"
        )

        // 标记是否已成功捕获数据
        @Volatile
        private var hasCapturedData = false
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != CAMERA_PACKAGE) return

        XposedBridge.log("$TAG: ============================================")
        XposedBridge.log("$TAG: OMaster CameraHook 已加载")
        XposedBridge.log("$TAG: 目标包: ${lpparam.packageName}")
        XposedBridge.log("$TAG: 版本: 6.105.84 兼容版")
        XposedBridge.log("$TAG: ============================================")

        // 策略1: 尝试 hook 已知的特定类
        hookKnownClasses(lpparam.classLoader)

        // 策略2: 尝试通过包名 hook 所有 filter 相关类
        hookAllFilterClasses(lpparam.classLoader)

        // 策略3: 尝试 hook 可能的初始化方法
        hookInitMethods(lpparam.classLoader)
    }

    /**
     * 策略1: Hook 已知的特定类
     */
    private fun hookKnownClasses(classLoader: ClassLoader) {
        val knownClasses = listOf(
            "com.oplus.camera.filter.FilterGroupManager",
            "com.oplus.camera.filter.FilterManager",
            "com.oplus.camera.filter.FilterConfig",
            "com.oplus.camera.filter.FilterLoader",
            "com.oplus.camera.filter.MasterFilterManager",
            "com.oplus.camera.filter.FilterDataManager",
            "com.oplus.camera.filter.FilterRepository",
            "com.coloros.camera.filter.FilterGroupManager",
            "com.coloros.camera.filter.FilterManager",
            "com.oneplus.camera.filter.FilterGroupManager",
            "com.oneplus.camera.filter.FilterManager"
        )

        val knownMethods = listOf(
            "initFromIpu",
            "init",
            "loadFilters",
            "initFilters",
            "loadFromConfig",
            "initialize",
            "setupFilters",
            "loadFilterData",
            "parseFilters",
            "setFilterData",
            "updateFilters",
            "refreshFilters"
        )

        for (className in knownClasses) {
            for (methodName in knownMethods) {
                tryHookMethod(classLoader, className, methodName)
            }
        }
    }

    /**
     * 策略2: 尝试 hook 所有 filter 包下的类
     */
    private fun hookAllFilterClasses(classLoader: ClassLoader) {
        val filterPackages = listOf(
            "com.oplus.camera.filter",
            "com.coloros.camera.filter",
            "com.oneplus.camera.filter"
        )

        for (packageName in filterPackages) {
            try {
                // 尝试获取包下所有类
                val packageObj = XposedHelpers.callMethod(
                    classLoader,
                    "getPackage",
                    packageName.replace(".", "/")
                )

                if (packageObj != null) {
                    XposedBridge.log("$TAG: 发现包 $packageName")
                }
            } catch (e: Exception) {
                // 忽略错误
            }
        }
    }

    /**
     * 策略3: Hook 可能的初始化方法
     */
    private fun hookInitMethods(classLoader: ClassLoader) {
        // 尝试 hook Application 类的 onCreate
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG: Application.onCreate 被调用，开始扫描滤镜类...")
                        scanForFilterClasses(param.thisObject.javaClass.classLoader)
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Hook Application.onCreate 失败: ${e.message}")
        }
    }

    /**
     * 扫描并 hook 可能的滤镜类
     */
    private fun scanForFilterClasses(classLoader: ClassLoader?) {
        if (classLoader == null) return

        // 尝试查找并 hook 包含 "Filter" 的类
        val possibleClasses = listOf(
            "com.oplus.camera.module.BaseModule",
            "com.oplus.camera.module.Module",
            "com.oplus.camera.common.CameraApp",
            "com.oplus.camera.CameraApp"
        )

        for (className in possibleClasses) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                XposedBridge.log("$TAG: 发现类 $className")

                // Hook 所有方法
                val methods = clazz.declaredMethods
                for (method in methods) {
                    val methodName = method.name
                    if (methodName.contains("filter", ignoreCase = true) ||
                        methodName.contains("init", ignoreCase = true)) {
                        try {
                            XposedHelpers.findAndHookMethod(
                                clazz,
                                methodName,
                                *method.parameterTypes,
                                object : XC_MethodHook() {
                                    override fun afterHookedMethod(param: MethodHookParam) {
                                        processAnyMethodResult(param, className, methodName)
                                    }
                                }
                            )
                            XposedBridge.log("$TAG: 成功 Hook $className.$methodName")
                        } catch (e: Exception) {
                            // 忽略
                        }
                    }
                }
            } catch (e: Exception) {
                // 类不存在，忽略
            }
        }
    }

    /**
     * 尝试 hook 指定类的方法
     */
    private fun tryHookMethod(classLoader: ClassLoader, className: String, methodName: String) {
        val paramCombinations = listOf(
            arrayOf(Context::class.java, HashMap::class.java),
            arrayOf(HashMap::class.java),
            arrayOf(Context::class.java),
            arrayOf()
        )

        for (params in paramCombinations) {
            try {
                XposedHelpers.findAndHookMethod(
                    className,
                    classLoader,
                    methodName,
                    *params,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                processHookResult(param, className, methodName)
                            } catch (e: Exception) {
                                XposedBridge.log("$TAG: $className.$methodName afterHookedMethod 异常: ${e.message}")
                            }
                        }
                    }
                )
                XposedBridge.log("$TAG: 成功 Hook $className.$methodName")
                return
            } catch (e: Exception) {
                // 继续尝试下一个参数组合
            }
        }
    }

    /**
     * 处理 Hook 结果
     */
    private fun processHookResult(param: MethodHookParam, className: String, methodName: String) {
        if (hasCapturedData) return  // 如果已经捕获过数据，不再处理

        var context: Context? = null
        var filterMap: HashMap<String, List<HashMap<String, Any>>>? = null

        // 从参数中查找
        for (arg in param.args) {
            when {
                arg is Context && context == null -> context = arg
                arg is HashMap<*, *> && filterMap == null -> {
                    filterMap = tryCastToFilterMap(arg)
                }
            }
        }

        // 从返回值中查找
        if (filterMap == null && param.result != null) {
            val result = param.result
            if (result is HashMap<*, *>) {
                filterMap = tryCastToFilterMap(result)
            }
        }

        if (filterMap != null && context != null && filterMap.isNotEmpty()) {
            hasCapturedData = true
            XposedBridge.log("$TAG: ✓ 从 $className.$methodName 捕获到滤镜映射，共 ${filterMap.size} 个模式")
            val jsonResult = parseFilterMap(context, filterMap)
            writeFilterMapToFile(jsonResult)
        }
    }

    /**
     * 处理任意方法的结果（用于扫描模式）
     */
    private fun processAnyMethodResult(param: MethodHookParam, className: String, methodName: String) {
        if (hasCapturedData) return

        // 检查参数中是否有 HashMap
        for (arg in param.args) {
            if (arg is HashMap<*, *>) {
                val filterMap = tryCastToFilterMap(arg)
                if (filterMap != null && filterMap.isNotEmpty()) {
                    // 尝试获取 Context
                    val context = param.args.find { it is Context } as? Context
                    if (context != null) {
                        hasCapturedData = true
                        XposedBridge.log("$TAG: ✓ 从 $className.$methodName 捕获到滤镜映射（扫描模式）")
                        val jsonResult = parseFilterMap(context, filterMap)
                        writeFilterMapToFile(jsonResult)
                        return
                    }
                }
            }
        }
    }

    /**
     * 安全地尝试将 HashMap 转换为我们需要的类型
     */
    @Suppress("UNCHECKED_CAST")
    private fun tryCastToFilterMap(map: HashMap<*, *>): HashMap<String, List<HashMap<String, Any>>>? {
        return try {
            val result = HashMap<String, List<HashMap<String, Any>>>()
            for ((key, value) in map) {
                if (key is String && value is List<*>) {
                    val filters = value.mapNotNull { item ->
                        if (item is HashMap<*, *>) {
                            item as? HashMap<String, Any>
                        } else {
                            null
                        }
                    }
                    if (filters.isNotEmpty()) {
                        result[key] = filters
                    }
                }
            }
            if (result.isNotEmpty()) result else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 HashMap 为 JSON 结构
     */
    private fun parseFilterMap(
        context: Context,
        filterMap: HashMap<String, List<HashMap<String, Any>>>
    ): JSONObject {
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

                // 尝试多种可能的 key 来获取 LUT 文件名
                val lutFile = findValueByKeys(filterEntry, KEY_FILTER_TYPES)?.toString() ?: "unknown"
                filterObj.put("lutFile", lutFile)

                // 尝试多种可能的 key 来获取资源 ID
                val resourceId = when (val nameVal = findValueByKeys(filterEntry, KEY_FILTER_NAMES)) {
                    is Number -> nameVal.toInt()
                    is String -> nameVal.toIntOrNull() ?: 0
                    else -> 0
                }
                filterObj.put("resourceId", resourceId)

                val displayName = resolveFilterName(context, resourceId)
                filterObj.put("name", displayName)

                // 尝试多种可能的 key 来获取分组标记
                val isMaster = when (val masterVal = findValueByKeys(filterEntry, KEY_IS_MASTERS)) {
                    is Number -> masterVal.toInt()
                    is String -> masterVal.toIntOrNull() ?: 0
                    else -> 0
                }
                filterObj.put("isMaster", isMaster)

                filtersArray.put(filterObj)
            }

            modesObj.put(mode, filtersArray)
            XposedBridge.log("$TAG: 模式 [$mode] 共 ${filterList.size} 个滤镜")
        }

        root.put("modes", modesObj)
        return root
    }

    /**
     * 从 HashMap 中尝试多种 key 查找值
     */
    private fun findValueByKeys(map: HashMap<String, Any>, keys: List<String>): Any? {
        for (key in keys) {
            val value = map[key]
            if (value != null) {
                return value
            }
        }
        return null
    }

    /**
     * 通过资源 ID 解析滤镜中文名
     */
    private fun resolveFilterName(context: Context, resourceId: Int): String {
        if (resourceId == 0) return "未知"
        return try {
            context.getString(resourceId)
        } catch (e: Exception) {
            XposedBridge.log("$TAG: 资源 ID $resourceId 解析失败: ${e.message}")
            "ID:$resourceId"
        }
    }

    /**
     * 将 JSON 写入文件
     */
    private fun writeFilterMapToFile(json: JSONObject) {
        try {
            val dir = File(OUTPUT_DIR)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, OUTPUT_FILE)
            file.writeText(json.toString(2))

            // 设置文件权限为全局可读
            file.setReadable(true, false)

            XposedBridge.log("$TAG: ✓ 滤镜映射已写入 ${file.absolutePath} (${file.length()} bytes)")
            XposedBridge.log("$TAG: ✓ 数据捕获完成！OMaster 现在应该可以读取滤镜数据了。")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: ✗ 写入文件失败: ${e.message}")
            e.printStackTrace()
        }
    }
}