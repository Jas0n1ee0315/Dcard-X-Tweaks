package com.jasonlee.dcardxtweaks

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Properties

class DcardAdBlockModule : XposedModule() {

    private var processName: String = ""

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != DCARD_PKG || !param.isFirstPackage) return
        if (processName != DCARD_PKG) {
            detach()
            return
        }
        log(Log.INFO, TAG, "Dcard X 主行程已載入，framework=$frameworkName $frameworkVersion, api=$apiVersion")

        val cl = param.classLoader
        val apkPath = param.applicationInfo.sourceDir
        val cacheDir = File(param.applicationInfo.dataDir, "cache")
        val targets = resolveTargets(cl, apkPath!!, cacheDir)

        hookAdNetwork(cl, targets)
        hookOpenAdDecision(cl, targets)
        hookOpenAdActivity(cl)
        hookRemoteConfig(cl, targets)
        hookAdItemLoader(cl)
        hookAdViewContainer(cl)
        hookThrottledComments(cl)
    }

    // ───────────────────────── 廣告 ─────────────────────────

    private fun findAdLoadMethods(cl: ClassLoader): List<Method> {
        val network = cl.loadClass(CLS_AD_NETWORK)
        val continuation = cl.loadClass("kotlin.coroutines.Continuation")
        return network.declaredMethods.filter { m ->
            val p = m.parameterTypes
            !Modifier.isStatic(m.modifiers) && !m.isSynthetic && p.size >= 3 &&
                p.first() == Context::class.java &&
                continuation.isAssignableFrom(p.last()) &&
                m.returnType == Any::class.java
        }
    }

    private fun hookAdNetwork(cl: ClassLoader, targets: Targets) {
        try {
            val methods = findAdLoadMethods(cl)
            if (methods.isEmpty()) {
                log(Log.ERROR, TAG, "$CLS_AD_NETWORK 內沒有符合形狀的廣告載入方法")
                return
            }
            for (method in methods) {
                val failCtor = targets.failureClassByMethod[methodKey(method)]?.let { findFailureCtor(cl, it) }
                if (failCtor == null) {
                    log(Log.WARN, TAG, "無法判定 ${method.name}() 的失敗回傳型別，略過（仍有 AdItemLoader / View 備援）")
                    continue
                }
                try {
                    hook(method)
                        .setId("ad_network_${method.name}")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { _ -> failCtor.newInstance(Exception(BLOCK_MESSAGE)) }
                    log(Log.INFO, TAG, "已攔截廣告載入 DcardAdNetwork.${method.name}() → ${failCtor.declaringClass.name}")
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "Hook DcardAdNetwork.${method.name}() 失敗", t)
                }
            }
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "找不到 $CLS_AD_NETWORK，此版本 Dcard X 可能已改架構", t)
        }
    }

    private fun methodKey(m: Method): String =
        m.name + "(" + m.parameterTypes.joinToString(",") { it.name } + ")"

    private fun findFailureCtor(cl: ClassLoader, className: String): Constructor<*>? = try {
        cl.loadClass(className).declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(Exception::class.java)
        }?.apply { isAccessible = true }
    } catch (_: Throwable) {
        null
    }

    private fun hookOpenAdDecision(cl: ClassLoader, targets: Targets) {
        val className = targets.openAdLambda
        if (className == null) {
            log(Log.WARN, TAG, "找不到開屏廣告判斷 lambda，僅依賴 OpenAdPlayerActivity 備援")
            return
        }
        try {
            val method = cl.loadClass(className).getDeclaredMethod("invokeSuspend", Any::class.java)
            hook(method)
                .setId("open_ad_decision")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { _ -> false }
            log(Log.INFO, TAG, "已停用開屏廣告判斷 $className.invokeSuspend()")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Hook 開屏廣告判斷 $className 失敗", t)
        }
    }

    private fun hookOpenAdActivity(cl: ClassLoader) {
        try {
            val activity = cl.loadClass(CLS_OPEN_AD_ACTIVITY)
            val onCreate = activity.getDeclaredMethod("onCreate", Bundle::class.java)
            hook(onCreate)
                .setId("open_ad_activity")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? Activity)?.let {
                        log(Log.INFO, TAG, "偵測到開屏廣告頁，直接關閉")
                        it.finish()
                        @Suppress("DEPRECATION")
                        it.overridePendingTransition(0, 0)
                    }
                    result
                }
            log(Log.INFO, TAG, "已 Hook $CLS_OPEN_AD_ACTIVITY.onCreate()")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "Hook OpenAdPlayerActivity 失敗（此版本可能沒有獨立的開屏頁）", t)
        }
    }

    private fun hookRemoteConfig(cl: ClassLoader, targets: Targets) {
        val desc = targets.remoteConfigGetter
        if (desc == null) {
            log(Log.WARN, TAG, "找不到 Remote Config getBoolean 方法，Mercury 區塊不處理")
            return
        }
        try {
            val method = DexMethod(desc).getMethodInstance(cl)
            hook(method)
                .setId("rc_disable_mercury")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (chain.args.firstOrNull() == RC_DISABLE_MERCURY) true else chain.proceed()
                }
            log(Log.INFO, TAG, "已強制 Remote Config $RC_DISABLE_MERCURY=true → $desc")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Hook Remote Config getter 失敗", t)
        }
    }

    private fun hookAdItemLoader(cl: ClassLoader) {
        try {
            val loader = cl.loadClass(CLS_AD_ITEM_LOADER)
            loader.declaredMethods
                .filter { it.name == "load" && !Modifier.isStatic(it.modifiers) }
                .forEach { m ->
                    hook(m)
                        .setId("ad_item_loader_load")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { _ -> null }
                    log(Log.INFO, TAG, "已攔截 AdItemLoader.load()")
                }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "Hook AdItemLoader 失敗", t)
        }
    }

    private fun hookAdViewContainer(cl: ClassLoader) {
        try {
            val container = cl.loadClass(CLS_AD_VIEW_CONTAINER)
            val onAttached = container.getDeclaredMethod("onAttachedToWindow")
            hook(onAttached)
                .setId("ad_view_container_attached")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { collapseView(it) }
                    result
                }
            log(Log.INFO, TAG, "已 Hook DcardAdViewContainer.onAttachedToWindow()")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "Hook DcardAdViewContainer 失敗", t)
        }
    }

    private fun collapseView(view: View) {
        view.visibility = View.GONE
        val lp = view.layoutParams
        if (lp != null && lp.height != 0) {
            lp.height = 0
            view.layoutParams = lp
        }
    }

    // ───────────────────── 系統隱藏留言 ─────────────────────
    //
    // Dcard 會把被「負面品質標記」的留言（API 回傳 isThrottled=true）從留言串摺疊掉，
    // 只在最底部顯示「系統已經隱藏部分留言，查看更多」，巢狀回覆則顯示「隱藏留言」。
    // 所有判斷都走 Comment 上一個 ()Z 方法：!currentMember && !hidden && isThrottled。
    // 方法名稱被混淆（8.241.2 為 m()），因此於執行期以 toString() 反推 isThrottled 欄位，
    // 再找出「只靠 isThrottled 就回傳 true」的 ()Z 方法並強制回傳 false。

    private fun hookThrottledComments(cl: ClassLoader) {
        try {
            val comment = cl.loadClass(CLS_COMMENT)
            val instance = allocateInstance(comment)
            val boolFields = comment.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && it.type == Boolean::class.javaPrimitiveType }
                .onEach { it.isAccessible = true }

            fun resetAll() = boolFields.forEach { it.setBoolean(instance, false) }

            val throttledField: Field = boolFields.firstOrNull { f ->
                resetAll()
                f.setBoolean(instance, true)
                instance.toString().contains("isThrottled=true")
            } ?: run {
                log(Log.ERROR, TAG, "找不到 Comment.isThrottled 欄位，系統隱藏留言功能未啟用")
                return
            }

            val candidates = comment.declaredMethods.filter {
                !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            val targets = candidates.filter { m ->
                runCatching {
                    m.isAccessible = true
                    resetAll()
                    val baseline = m.invoke(instance) as Boolean
                    throttledField.setBoolean(instance, true)
                    val throttled = m.invoke(instance) as Boolean
                    throttled && !baseline
                }.getOrDefault(false)
            }
            resetAll()

            if (targets.isEmpty()) {
                log(Log.ERROR, TAG, "找不到判斷「系統隱藏留言」的 Comment 方法（isThrottled=${throttledField.name}）")
                return
            }
            for (m in targets) {
                hook(m)
                    .setId("comment_throttled_${m.name}")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { _ -> false }
                log(Log.INFO, TAG, "已停用系統隱藏留言：Comment.${m.name}()（isThrottled=${throttledField.name}）")
            }
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Hook 系統隱藏留言失敗", t)
        }
    }

    private fun allocateInstance(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, clazz)!!
    }

    // ───────────────────────── DexKit ─────────────────────────

    private class Targets(
        val failureClassByMethod: Map<String, String>,
        val openAdLambda: String?,
        val remoteConfigGetter: String?,
    )

    private fun resolveTargets(cl: ClassLoader, apkPath: String, cacheDir: File): Targets {
        val apkFile = File(apkPath)
        val fingerprint = "$CACHE_VERSION|$apkPath|${apkFile.lastModified()}|${apkFile.length()}"
        val cacheFile = File(cacheDir, CACHE_FILE)
        readCache(cacheFile, fingerprint)?.let {
            log(Log.DEBUG, TAG, "使用 DexKit 快取結果")
            return it
        }
        return try {
            val scanned = scanWithDexKit(cl, apkPath)
            if (scanned.failureClassByMethod.isNotEmpty()) writeCache(cacheFile, fingerprint, scanned)
            scanned
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "DexKit 掃描失敗，僅啟用不需 DexKit 的 Hook", t)
            Targets(emptyMap(), null, null)
        }
    }

    private fun scanWithDexKit(cl: ClassLoader, apkPath: String): Targets {
        System.loadLibrary("dexkit")
        DexKitBridge.create(apkPath).use { bridge ->
            // 1) 廣告載入方法的失敗回傳型別
            val failureByMethod = LinkedHashMap<String, String>()
            val methods = runCatching { findAdLoadMethods(cl) }.getOrDefault(emptyList())
            for (method in methods) {
                val data = bridge.getMethodData(method) ?: continue
                val candidates = data.invokes.filter { inv ->
                    inv.isConstructor &&
                        !inv.className.startsWith("java.") &&
                        inv.paramTypeNames.size == 1 &&
                        inv.paramTypeNames[0] in setOf("java.lang.Exception", "java.lang.Throwable")
                }.map { it.className }.distinct()
                if (candidates.size == 1) failureByMethod[methodKey(method)] = candidates[0]
                log(Log.INFO, TAG, "DexKit：${method.name}() 失敗型別候選 = $candidates")
            }

            // 2) 開屏廣告判斷 lambda
            val lambdas = bridge.findClass(
                FindClass().matcher(
                    ClassMatcher().addField(FieldMatcher().name("\$mode").type(CLS_AD_APP_LAUNCH_TYPE))
                )
            ).filter { cls ->
                "\$context" !in cls.fields.map { it.name } &&
                    cls.methods.any { it.name == "invokeSuspend" }
            }.map { it.name }
            log(Log.INFO, TAG, "DexKit：開屏判斷 lambda 候選 = $lambdas")

            // 3) Remote Config getBoolean
            val rcGetters = bridge.findMethod(
                FindMethod().matcher(MethodMatcher().usingEqStrings(RC_DISABLE_MERCURY))
            ).flatMap { caller ->
                caller.invokes.filter {
                    it.paramTypeNames == listOf("java.lang.String") && it.returnTypeName == "boolean"
                }
            }.map { it.descriptor }.distinct()
            log(Log.INFO, TAG, "DexKit：Remote Config getter 候選 = $rcGetters")

            return Targets(failureByMethod, lambdas.singleOrNull(), rcGetters.singleOrNull())
        }
    }

    private fun readCache(cacheFile: File, fingerprint: String): Targets? = try {
        if (!cacheFile.isFile) {
            null
        } else {
            val props = Properties()
            FileInputStream(cacheFile).use { props.load(it) }
            if (props.getProperty(KEY_FINGERPRINT) != fingerprint) {
                null
            } else {
                val failMap = props.stringPropertyNames()
                    .filter { it.startsWith(KEY_FAIL_PREFIX) }
                    .associate { it.removePrefix(KEY_FAIL_PREFIX) to props.getProperty(it) }
                Targets(
                    failMap,
                    props.getProperty(KEY_OPEN_AD_LAMBDA)?.takeIf { it.isNotEmpty() },
                    props.getProperty(KEY_RC_GETTER)?.takeIf { it.isNotEmpty() },
                )
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun writeCache(cacheFile: File, fingerprint: String, targets: Targets) {
        try {
            cacheFile.parentFile?.mkdirs()
            val props = Properties()
            props.setProperty(KEY_FINGERPRINT, fingerprint)
            for ((k, v) in targets.failureClassByMethod) props.setProperty(KEY_FAIL_PREFIX + k, v)
            props.setProperty(KEY_OPEN_AD_LAMBDA, targets.openAdLambda ?: "")
            props.setProperty(KEY_RC_GETTER, targets.remoteConfigGetter ?: "")
            FileOutputStream(cacheFile).use { props.store(it, "DcardXTweaks DexKit cache") }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "寫入快取失敗", t)
        }
    }

    private object Log {
        const val DEBUG = 3
        const val INFO = 4
        const val WARN = 5
        const val ERROR = 6
    }

    companion object {
        private const val TAG = "DcardXTweaks"
        private const val DCARD_PKG = "com.dcard.freedom"
        private const val BLOCK_MESSAGE = "Blocked by DcardXTweaks"

        private const val CLS_AD_NETWORK = "dcard.features.ad.DcardAdNetwork"
        private const val CLS_AD_ITEM_LOADER = "dcard.features.ad.core.operator.AdItemLoader"
        private const val CLS_AD_VIEW_CONTAINER = "dcard.features.ad.natives.dcard.DcardAdViewContainer"
        private const val CLS_AD_APP_LAUNCH_TYPE = "dcard.commons.component.AdAppLaunchType"
        private const val CLS_OPEN_AD_ACTIVITY = "com.sparkslab.dcardreader.screen.open_ads.OpenAdPlayerActivity"
        private const val CLS_COMMENT = "dcard.commons.model.model.forum.Comment"

        private const val RC_DISABLE_MERCURY = "disable_mercury_on_freedom"

        private const val CACHE_FILE = "dcard_x_tweaks_dexkit.properties"
        private const val CACHE_VERSION = "1"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_FAIL_PREFIX = "fail."
        private const val KEY_OPEN_AD_LAMBDA = "openAdLambda"
        private const val KEY_RC_GETTER = "rcGetter"
    }
}
