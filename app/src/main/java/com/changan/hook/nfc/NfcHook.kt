package com.changan.hook.nfc

import android.content.Context
import android.content.Intent
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook

class NfcHook : XposedModule() {

    companion object {
        private const val TAG = "KCHook"
    }

    private var appContext: Context? = null
    private val pendingActions = mutableListOf<() -> Unit>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        android.util.Log.e(TAG, "nfc: load")
        EzXposed.initOnModuleLoaded(this, param)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        android.util.Log.e(TAG, "pkg: ${param.packageName}")
        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        android.util.Log.e(TAG, "ready: ${param.packageName}")
        EzXposed.initOnPackageReady(param)

        initContext()

        if (param.packageName == "com.android.nfc") {
            android.util.Log.e(TAG, "nfc: target ready")
            hookNfcDirectDispatch(param.classLoader)
            hookNfcLockscreenDispatch(param.classLoader)
        } else {
            android.util.Log.e(TAG, "skip: ${param.packageName}")
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean =
        EzXposed.handleHotReloading(param)

    override fun onHotReloaded(param: HotReloadedParam) {
        EzXposed.handleHotReloadedWithTargetReady(this, param, { })
    }

    private fun initContext() {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            val application = currentApplicationMethod.invoke(null) as? android.app.Application
            application?.let {
                appContext = it
                android.util.Log.e(TAG, "ctx ok")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "ctx fail: ${e.message}")
        }
    }

    private fun ensureContextThen(action: () -> Unit) {
        if (appContext != null) {
            action()
            return
        }
        synchronized(pendingActions) { pendingActions.add(action) }
        try {
            Class.forName("android.app.Application").findMethod {
                name("onCreate")
                paramCount(0)
            }.createHook {
                after { param ->
                    val app = param.thisObject as? android.app.Application ?: return@after
                    appContext = app
                    android.util.Log.e(TAG, "ctx ok: onCreate")
                    val actions = synchronized(pendingActions) {
                        val list = pendingActions.toList()
                        pendingActions.clear()
                        list
                    }
                    actions.forEach { runCatching { it() } }
                }
            }
            android.util.Log.e(TAG, "ctx pending")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "app hook fail: ${e.message}")
        }
    }

    private fun hookNfcLockscreenDispatch(classLoader: ClassLoader) {
        try {
            val kmClass = classLoader.loadClass("android.app.KeyguardManager")
            var ok = 0
            listOf("isKeyguardLocked", "isDeviceLocked").forEach { name ->
                runCatching {
                    kmClass.findMethod {
                        name(name)
                        paramCount(0)
                    }.createHook {
                        after { param ->
                            try {
                                param.result = false
                            } catch (_: Exception) {}
                        }
                    }
                    ok++
                }
            }
            var listenerOk = 0
            for (i in 1..40) {
                runCatching {
                    val cls = classLoader.loadClass("com.android.nfc.NfcService\$$i")
                    val isListener = cls.interfaces.any {
                        it.name.endsWith("KeyguardLockedStateListener")
                    }
                    if (!isListener) return@runCatching
                    cls.findMethod {
                        name("onKeyguardLockedStateChanged")
                        paramCount(1)
                    }.createHook {
                        before { param ->
                            try {
                                param.args[0] = false
                            } catch (_: Exception) {}
                        }
                    }
                    listenerOk++
                }
            }
            android.util.Log.e(TAG, "km: $ok/2 $listenerOk")
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "km hook fail: ${t.message}")
        }
    }

    @Volatile private var kcNfcDispatchWindowUntil = 0L

    @Volatile private var kcScreenOffFlagCacheAt = 0L
    @Volatile private var kcScreenOffFlagCache = false

    @Volatile private var kcRealScreenOff = false

    @Volatile private var kcLastRealScreenArg = 1

    @Volatile private var kcSelfApply = false

    @Volatile private var kcLastFlagState: Boolean? = null

    private fun kcScreenOffFlagOn(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - kcScreenOffFlagCacheAt > 2000) {
            kcScreenOffFlagCache = runCatching {
                java.io.File("/sdcard/Download/kc_nfc_screenoff.flag").readText().trim() == "1"
            }.getOrDefault(false)
            kcScreenOffFlagCacheAt = now
        }
        return kcScreenOffFlagCache
    }

    private fun hookNfcDirectDispatch(classLoader: ClassLoader) {
        val KC_NFC_MIME = "application/vnd.kc.tool"
        try {
            val ntfClass = classLoader.loadClass("com.android.nfc.NotificationDispatcher")
            ntfClass.findMethod {
                name("parseNtfType")
                paramCount(1)
            }.createHook {
                after { param ->
                    try {
                        val intent = param.args[0] as? Intent ?: return@after
                        val launchIntent = intent.getParcelableExtra<Intent>("launchIntent")
                        val mime = launchIntent?.type ?: intent.type
                        if (mime == KC_NFC_MIME) {
                            android.util.Log.e(TAG, "mime hit: $mime")
                            param.result = 64
                            kcNfcDispatchWindowUntil = android.os.SystemClock.elapsedRealtime() + 5000L
                        }
                    } catch (_: Exception) {}
                }
            }
            android.util.Log.e(TAG, "ntf hook ok")

            val dispClass = classLoader.loadClass("com.android.nfc.NfcDispatcher")
            dispClass.findMethod {
                name("isMatchAdditionalActivityFilters")
                paramCount(1)
            }.createHook {
                after { param ->
                    try {
                        val ri = param.args[0] as? android.content.pm.ResolveInfo ?: return@after
                        if (ri.activityInfo?.packageName == "changan.kc.tool") {
                            android.util.Log.e(TAG, "filter pass")
                            param.result = true
                        }
                    } catch (_: Exception) {}
                }
            }
            android.util.Log.e(TAG, "filter hook ok")

            val dispTagClass = classLoader.loadClass("com.android.nfc.NfcDispatcher")
            dispTagClass.findMethod {
                name("dispatchTag")
                paramCount(1)
            }.createHook {
                before { param ->
                    try {
                        val tag = param.args[0] as? android.nfc.Tag ?: return@before
                        val ndef = android.nfc.tech.Ndef.get(tag) ?: return@before
                        val msg = ndef.cachedNdefMessage ?: return@before
                        val hit = msg.records.any {
                            it.tnf == android.nfc.NdefRecord.TNF_MIME_MEDIA &&
                                String(it.type, Charsets.US_ASCII) == KC_NFC_MIME
                        }
                        if (hit) {
                            kcNfcDispatchWindowUntil = android.os.SystemClock.elapsedRealtime() + 5000L
                            android.util.Log.e(TAG, "win on")
                        } else if (kcRealScreenOff) {
                            param.result = -1
                            android.util.Log.e(TAG, "tag skip")
                        }
                    } catch (_: Exception) {}
                }
            }

            classLoader.loadClass("com.android.nfc.NfcService").findMethod {
                name("applyScreenState")
                paramCount(1)
            }.createHook {
                before { param ->
                    try {
                        val arg = param.args[0] as? Int ?: return@before
                        if (!kcSelfApply) {
                            kcLastRealScreenArg = arg
                            kcRealScreenOff = arg in 1..3
                        }
                        if (arg in 1..3 && kcScreenOffFlagOn()) {
                            param.args[0] = 8
                            android.util.Log.e(TAG, "screen $arg->8")
                        }
                    } catch (_: Exception) {}
                }
            }

            fun applyScreenOffFlagNow(reason: String) {
                try {
                    val enabled = runCatching {
                        java.io.File("/sdcard/Download/kc_nfc_screenoff.flag").readText().trim() == "1"
                    }.getOrDefault(false)
                    if (enabled == kcLastFlagState) return
                    kcLastFlagState = enabled
                    val svc = runCatching {
                        classLoader.loadClass("com.android.nfc.NfcService")
                            .getField("sService").get(null)
                    }.getOrNull()
                    if (svc == null) {
                        android.util.Log.e(TAG, "flag $reason: no svc")
                        return
                    }
                    val target = if (enabled) 8 else kcLastRealScreenArg
                    kcSelfApply = true
                    try {
                        svc.javaClass.getMethod("applyScreenState", Int::class.javaPrimitiveType)
                            .invoke(svc, target)
                        android.util.Log.e(TAG, "flag $reason: $target $enabled")
                    } catch (t: Throwable) {
                        android.util.Log.e(TAG, "flag apply fail: ${t.message}")
                    } finally {
                        kcSelfApply = false
                    }
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "flag err: ${t.message}")
                }
            }
            ensureContextThen {
                applyScreenOffFlagNow("init")
                runCatching {
                    val receiver = object : android.content.BroadcastReceiver() {
                        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                            applyScreenOffFlagNow("bc")
                        }
                    }
                    val filter = android.content.IntentFilter("changan.kc.tool.NFC_SCREENOFF_SYNC")
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        appContext?.registerReceiver(
                            receiver, filter, android.content.Context.RECEIVER_EXPORTED
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        appContext?.registerReceiver(receiver, filter)
                    }
                    android.util.Log.e(TAG, "receiver ok")
                }.onFailure {
                    android.util.Log.e(TAG, "receiver fail: ${it.message}")
                }
            }

            classLoader.loadClass("com.android.nfc.ScreenStateHelper").findMethod {
                name("checkScreenState")
                paramCount(1)
            }.createHook {
                after { param ->
                    try {
                        val r = param.result as? Int ?: return@after
                        if (r < 8 && android.os.SystemClock.elapsedRealtime() < kcNfcDispatchWindowUntil) {
                            android.util.Log.e(TAG, "state $r->8")
                            param.result = 8
                        }
                    } catch (_: Exception) {}
                }
            }
            android.util.Log.e(TAG, "dispatch hook ok")
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "hook fail: ${t.message}")
        }
    }
}
