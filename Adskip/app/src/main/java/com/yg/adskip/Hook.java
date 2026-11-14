package com.yg.adskip;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 慧生活798 (com.cloudora.android) —— 「观看视频，领取积分」免播放，直接让 App 自己发加分请求。
 *
 * 真实业务代码被 AntSafe 加固壳在运行时解密进内存 dex（自定义/Path ClassLoader），
 * handleLoadPackage 阶段 lp.classLoader 里找不到 MediationAdHelper，
 * 所以先挂 ClassLoader#loadClass 观察者，目标类一被加载就在它自己的 ClassLoader 上装 hook。
 *
 * 命中后替换 loadAndShow：
 *   不加载广告 -> dismiss mLoadingDialog -> listener.onAdLoaded()
 *   -> REWARD_DELAY_MS 后 listener.onAdRewardArrived(true)
 * App 自己的 BaseTaskFragment 随后会调 CommonDS.o1/p1 发 /api/v1/acc/score/score-send。
 *
 * 2026-11-14 真机踩坑记录：
 * 1) 不能在静态字段里 new Handler(Looper.getMainLooper())：模块 <clinit> 早于主线程 Looper prepare，
 *    getMainLooper()=null -> NPE -> ExceptionInInitializerError -> 整个模块加载失败。改用 mainHandler() 懒建。
 * 2) App 经过 R8 混淆，用 XposedHelpers.findClass("kotlin.coroutines.Continuation") 会拼成
 *    kotlin$coroutines$Continuation 而失败；因此这里一律不用「按参数类型精确找方法」，
 *    改成遍历 getDeclaredMethods() 用名字/首参类型名匹配，再用 XposedBridge.hookMethod 挂。
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "AdSkip";
    private static final String PKG = "com.cloudora.android";

    // ---- 目标类（都在运行时内存 dex 里）----
    private static final String C_MAH      = "com.ilife.lib.common.ad.util.MediationAdHelper";
    private static final String C_DS       = "com.ilife.lib.coremodel.http.datasource.CommonDS";
    private static final String C_PARM     = "com.ilife.lib.coremodel.data.parm.AddScoreParm";
    private static final String C_BTF      = "com.ilife.module.home.view.fragment.BaseTaskFragment";
    private static final String C_FWF      = "com.ilife.module.home.view.fragment.FreeWaterFragment";

    /** 关掉当前页的「加载中」再回调 */
    private static final boolean DISMISS_DIALOG = true;
    /** onAdLoaded 到 onAdRewardArrived 的间隔（ms） */
    private static final int REWARD_DELAY_MS = 800;
    /** 调试：只给模拟器用（真机保持 false），让 native 设备指纹 ice() 直接返回空串 */
    private static final boolean EMULATOR_DEBUG = false;
    /** 调试：把 com.ilife.* 的类加载都打出来 */
    private static final boolean LOG_CLASSLOAD = false;
    /** 调试：把目标类的成员（方法签名/字段）打出来 */
    private static final boolean DUMP_MEMBERS = false;
    /** 调试：reward 回调之后把 BaseTaskFragment 所有方法调用打出来（定位卡点用，验证完改回 false） */
    private static final boolean TRACE_BTF_ALL = false;
    /** 调试：无条件追踪广告包 / 任务 Fragment 的所有方法（定位 +10 任务走的另一条链路） */
    private static final boolean TRACE_AD_ALL = false;

    private static volatile Handler sMainHandler;
    private static volatile Object  sListener;          // 跨调用持有
    private static volatile Class<?> sBtfClass;
    private static volatile Object  sLastFrag;           // 最近一次出现的 BaseTaskFragment 实例
    private static volatile Object  sLastParm;           // 最近一次构造的 AddScoreParm
    private static volatile long    sLastParmAt = 0L;
    private static volatile boolean sParm45Hooked, sFancyHooked;
    private static volatile long    sLastDsAt = 0L;      // App 自己最近一次发加分请求的时间
    private static volatile long    sLastSubmitAt = 0L;  // 我们最近一次主动提交的时间         // BaseTaskFragment 实际类（用于 isInstance 判定）
    private static volatile long    sRewardFiredAt = 0L;
    private static volatile boolean sMahHooked;
    private static volatile boolean sDsHooked;
    private static volatile boolean sBtfHooked;
    private static volatile boolean sWatcherHooked;

    /** 已处理过的类，避免重复安装/重复 dump */
    private static final Set<Class<?>> sSeen = Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PKG.equals(lp.packageName)) return;
        log("loaded in " + lp.packageName + " process=" + lp.processName
                + " sdk=" + android.os.Build.VERSION.SDK_INT);

        hookClassLoaderWatcher();
        hookApplicationOnCreate(lp.classLoader);
        tryInstallFrom(lp.classLoader);
    }

    // =====================================================================
    // 主线程调度（懒建，绝不放在 <clinit> 里）
    // =====================================================================
    private static Handler mainHandler() {
        Handler h = sMainHandler;
        if (h != null) return h;
        Looper l = Looper.getMainLooper();
        if (l == null) return null;
        try {
            h = new Handler(l);
            sMainHandler = h;
            return h;
        } catch (Throwable t) {
            log("new Handler failed: " + t);
            return null;
        }
    }

    private static void runOnMain(Runnable r) {
        Handler h = mainHandler();
        if (h != null) {
            h.post(r);
        } else {
            try { r.run(); } catch (Throwable t) { log("inline run fail: " + t); }
        }
    }

    private static void runOnMainDelayed(Runnable r, long delayMs) {
        Handler h = mainHandler();
        if (h != null) {
            h.postDelayed(r, delayMs);
        } else {
            try { r.run(); } catch (Throwable t) { log("inline delayed run fail: " + t); }
        }
    }

    // =====================================================================
    // 类加载观察者
    // =====================================================================
    private void hookClassLoaderWatcher() {
        if (sWatcherHooked) return;
        sWatcherHooked = true;
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass",
                    String.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (p.hasThrowable()) return;
                            Object r = p.getResult();
                            if (r instanceof Class) onClassLoaded((Class<?>) r);
                        }
                    });
            log("ClassLoader.loadClass watcher installed");

        } catch (Throwable t) {
            sWatcherHooked = false;
            log("hook ClassLoader.loadClass failed: " + t);
        }
    }

    /** 统一处理：任何 loader 交出来的类都进这里（ClassLoader.loadClass / BaseDexClassLoader.findClass） */
    private void onClassLoaded(final Class<?> c) {
        if (c == null || !sSeen.add(c)) return;
        final String n = c.getName();
        try {
            if (LOG_CLASSLOAD && n.startsWith("com.ilife")) log("cls " + n);
            if (C_MAH.equals(n))       ensureMah(c);
            else if (C_FWF.equals(n))  ensureTraceAll("fwf", c);
            else if (n.startsWith("com.ilife.lib.coremodel.http.vm")) ensureTraceAll("vm", c);
            else if (C_DS.equals(n))   ensureDs(c);
            else if (C_PARM.equals(n)) hookParm(c.getClassLoader());
            else if (C_BTF.equals(n))  ensureBtf(c);
            else if (TRACE_AD_ALL && n.startsWith("com.ilife.lib.common.ad")) ensureTraceAlways("ad:" + shortName(n), c);
            else if (TRACE_AD_ALL && n.startsWith("com.ilife.module.home.view.fragment")) ensureTraceAlways("frag:" + shortName(n), c);
            else if (TRACE_AD_ALL && n.startsWith("com.fancy.")) ensureTraceAlways("fancy:" + shortName(n), c);
        } catch (Throwable t) {
            log("onClassLoaded(" + n + ") failed: " + t);
        }
    }

    private static ClassLoader mAppCl() {
        try { return ClassLoader.getSystemClassLoader(); } catch (Throwable t) { return null; }
    }

    private void hookApplicationOnCreate(final ClassLoader appCl) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            log("Application.onCreate reached, retry resolve");
                            tryInstallFrom(appCl);
                            for (final int d : new int[]{2000, 5000, 10000}) {
                                runOnMainDelayed(new Runnable() {
                                    @Override public void run() { tryInstallFrom(appCl); }
                                }, d);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hook Application.onCreate failed: " + t);
        }
    }

    private void tryInstallFrom(ClassLoader cl) {
        if (cl == null) return;
        if (EMULATOR_DEBUG) hookIce(cl);
        if (!sMahHooked) {
            try {
                ensureMah(XposedHelpers.findClass(C_MAH, cl));
            } catch (Throwable ignore) { }
        }
        if (!sDsHooked) {
            try {
                ensureDs(XposedHelpers.findClass(C_DS, cl));
            } catch (Throwable ignore) { }
        }
        if (!sBtfHooked) {
            try {
                ensureBtf(XposedHelpers.findClass(C_BTF, cl));
            } catch (Throwable ignore) { }
        }
        hookInstrumentation();
        hookDialog();
        hookParm(cl);
        hookFancy(cl);
    }

    // =====================================================================
    // 1. MediationAdHelper: setListener 捕获 + loadAndShow 全替换
    // =====================================================================
    private void onClassLoadedTraceMah(final Class<?> mah) {
        if (!TRACE_AD_ALL) return;
        for (final Method m : mah.getDeclaredMethods()) {
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        log("[mah] " + m.getName() + " enter args=" + args(p.args));
                    }
                });
            } catch (Throwable ignore) { }
        }
        log("[mah] all " + mah.getDeclaredMethods().length + " methods traced");
    }

    private void ensureMah(final Class<?> mah) {
        if (sMahHooked) return;
        sMahHooked = true;
        log("MediationAdHelper resolved via " + safeLoaderName(mah));
        try { onClassLoadedTraceMah(mah); } catch (Throwable ignore) { }
        if (DUMP_MEMBERS) dumpMembers("mah", mah);

        boolean installed = false;
        try {
            for (final Method m : mah.getDeclaredMethods()) {
                String nm = m.getName();
                if (!nm.toLowerCase().contains("loadandshow")) continue;
                XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam p) {
                        return doSkipAd(p, m);
                    }
                });
                log("hooked " + sig(m));
                installed = true;
            }
            if (!installed) log("!! no method whose name contains loadAndShow on MediationAdHelper");

            for (final Method m : mah.getDeclaredMethods()) {
                if (!"setListener".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        sListener = p.args.length > 0 ? p.args[0] : null;
                        log("setListener captured: " + safeClass(sListener));
                    }
                });
                log("hooked " + sig(m));
            }
        } catch (Throwable t) {
            log("install MediationAdHelper hooks failed: " + t);
        }
        if (!installed) sMahHooked = false;
    }

    /** loadAndShow 的实际替换体 */
    private Object doSkipAd(XC_MethodHook.MethodHookParam p, Method m) {
        final Object self = p.thisObject;
        Object lst = sListener;
        if (lst == null) {
            try { lst = XposedHelpers.getObjectField(self, "listener"); } catch (Throwable ignore) { }
        }
        if (lst == null) {
            log("loadAndShow: listener=null -> fallback to original (ad will really load)");
            try { return XposedBridge.invokeOriginalMethod(m, self, p.args); }
            catch (Throwable t) { return null; }
        }
        final Object L = lst;
        log("loadAndShow intercepted (" + sig(m) + "), listener=" + safeClass(L));

        runOnMain(new Runnable() {
            @Override public void run() {
                if (DISMISS_DIALOG) {
                    try {
                        Object dlg = XposedHelpers.getObjectField(self, "mLoadingDialog");
                        if (dlg != null) {
                            XposedHelpers.callMethod(dlg, "dismiss");
                            log("mLoadingDialog dismissed");
                        } else {
                            log("mLoadingDialog=null (skip dismiss)");
                        }
                    } catch (Throwable t) { log("dismiss fail: " + t); }
                }
                try {
                    XposedHelpers.callMethod(L, "onAdLoaded");
                    log("-> onAdLoaded() sent");
                } catch (Throwable t) { log("onAdLoaded fail: " + t); }
            }
        });

        runOnMainDelayed(new Runnable() {
            @Override public void run() {
                try {
                    // 先打时间戳再回调：App 的 reward handler 在 callMethod 内部同步跑，
                    // 只有这样它后续调用的方法才会被 [btf]/[vm]/[ds] 追踪到
                    sRewardFiredAt = System.currentTimeMillis();
                    XposedHelpers.callMethod(L, "onAdRewardArrived", true);
                    log("-> onAdRewardArrived(true) sent");
                    tryForceSubmit(L);
                } catch (Throwable t) { log("onAdRewardArrived fail: " + t); }
            }
        }, REWARD_DELAY_MS);

        return defaultValue(m.getReturnType());
    }

    private static boolean isBtf(Class<?> c) {
        Class<?> b = sBtfClass;
        if (b != null) {
            try { if (b.isAssignableFrom(c)) return true; } catch (Throwable ignore) { }
        }
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().contains("BaseTaskFragment")) return true;
        }
        return false;
    }
    // =====================================================================
    // 1d. 全局 Activity 拦截：任何广告 SDK 的 Activity（fancy/mpsdk/穿山甲/图酷…）直接 finish
    //     不依赖目标 Class 对象，走 Instrumentation，命中率最高
    // =====================================================================
    private volatile boolean sInstHooked;

    private void hookInstrumentation() {
        if (sInstHooked) return;
        sInstHooked = true;
        try {
            XposedHelpers.findAndHookMethod(android.app.Instrumentation.class, "callActivityOnCreate",
                    android.app.Activity.class, android.os.Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (p.args == null || p.args.length == 0 || p.args[0] == null) return;
                                final android.app.Activity a = (android.app.Activity) p.args[0];
                                final String cn = a.getClass().getName();
                                if (!isAdActivity(cn)) return;
                                log("[act] ad activity " + cn + " -> finish + submit");
                                try { a.finish(); } catch (Throwable ignore) { }
                                runOnMainDelayed(new Runnable() {
                                    @Override public void run() { submitFromLast("act"); }
                                }, 2200);
                                runOnMainDelayed(new Runnable() {
                                    @Override public void run() { dismissLoading(); }
                                }, 3200);
                            } catch (Throwable t) { log("[act] hook body fail: " + t); }
                        }
                    });
            log("[act] Instrumentation.callActivityOnCreate hooked");
        } catch (Throwable t) {
            sInstHooked = false;
            log("[act] hook failed: " + t);
        }
    }

    private static boolean isAdActivity(String cn) {
        if (cn == null) return false;
        String s = cn.toLowerCase();
        return s.startsWith("com.fancy.") || s.contains(".mpsdk.") || s.contains("ttad")
                || s.contains("fullscreenvideo") || s.contains("rewardvideo") || s.contains("taku")
                || s.contains("interstitial") || s.contains("ptg");
    }
    // =====================================================================
    // 1c. AddScoreParm 构造捕获（任何任务都会造它）+ fancy 互动广告拦截
    // =====================================================================
    private void hookParm(final ClassLoader cl) {
        if (sParm45Hooked) return;
        try {
            final Class<?> pc = XposedHelpers.findClass(C_PARM, cl);
            sParm45Hooked = true;
            for (java.lang.reflect.Constructor<?> c : pc.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(c, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (p.thisObject == null) return;
                            sLastParm = p.thisObject;
                            sLastParmAt = System.currentTimeMillis();
                            try { log("[parm] built " + parm(sLastParm)); } catch (Throwable ignore) { }
                        }
                    });
                } catch (Throwable ignore) { }
            }
            log("[parm] AddScoreParm ctor hooked (" + pc.getDeclaredConstructors().length + ")");
        } catch (Throwable t) {
            log("[parm] hook failed: " + t);
        }
    }

    private void hookFancy(final ClassLoader cl) {
        if (sFancyHooked) return;
        try {
            final Class<?> ac = XposedHelpers.findClass("com.fancy.mpsdk.activity.PtgInteractionPortraitActivity", cl);
            sFancyHooked = true;
            Method onCreate = null;
            for (Method m : ac.getDeclaredMethods()) {
                if ("onCreate".equals(m.getName())) { onCreate = m; break; }
            }
            if (onCreate == null) { log("[fancy] no onCreate"); return; }
            XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    log("[fancy] PtgInteractionPortraitActivity shown -> finish + fake reward");
                    try { ((android.app.Activity) p.thisObject).finish(); } catch (Throwable ignore) { }
                    runOnMainDelayed(new Runnable() {
                        @Override public void run() { submitFromLast("fancy"); }
                    }, 2400);
                }
            });
            log("[fancy] PtgInteractionPortraitActivity.onCreate hooked");
        } catch (Throwable t) {
            log("[fancy] hook failed: " + t);
        }
    }

    /** 用最近捕获的 fragment + parm 提交 */
    /** 关掉 BaseTaskFragment 上那个一直转圈的 LoadingDialog（字段 u） */
    private volatile boolean sDlgHooked;

    /** 兜底：任何类名带 Loading 的 Dialog 弹出后 4s 自动关闭，避免卡在「加载中」 */
    private void hookDialog() {
        if (sDlgHooked) return;
        sDlgHooked = true;
        try {
            XposedHelpers.findAndHookMethod(android.app.Dialog.class, "show", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object d = p.thisObject;
                        if (d == null) return;
                        final String cn = d.getClass().getName();
                        if (!cn.toLowerCase().contains("loading")) return;
                        log("[dlg] loading dialog shown: " + cn + " -> auto dismiss in 4s");
                        final Object dlg = d;
                        runOnMainDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (dlg instanceof android.app.Dialog && ((android.app.Dialog) dlg).isShowing()) {
                                        ((android.app.Dialog) dlg).dismiss();
                                        log("[dlg] dismissed " + cn);
                                    }
                                } catch (Throwable ignore) { }
                            }
                        }, 4000);
                    } catch (Throwable ignore) { }
                }
            });
            log("[dlg] Dialog.show hooked");
        } catch (Throwable t) {
            sDlgHooked = false;
            log("[dlg] hook failed: " + t);
        }
    }

    private static void dismissLoading() {
        try {
            Object f = sLastFrag;
            if (f == null) { log("[act] dismissLoading: no fragment"); return; }
            Object d = XposedHelpers.getObjectField(f, "u");
            if (d == null) { log("[act] dismissLoading: dialog null"); return; }
            XposedHelpers.callMethod(d, "dismiss");
            log("[act] dismissLoading: dismissed");
        } catch (Throwable t) { log("[act] dismissLoading fail: " + t); }
    }

    private static void submitFromLast(String why) {
        try {
            long now = System.currentTimeMillis();
            if (now - sLastDsAt < 2500) {
                log("[submit:" + why + "] skip, App submitted itself " + (now - sLastDsAt) + "ms ago");
                return;
            }
            if (now - sLastSubmitAt < 6000) {
                log("[submit:" + why + "] skip, we submitted " + (now - sLastSubmitAt) + "ms ago");
                return;
            }
            Object frag = sLastFrag;
            Object parm = sLastParm;
            if (frag == null) { log("[submit:" + why + "] no fragment captured"); return; }
            if (parm == null)  { log("[submit:" + why + "] no parm captured"); return; }
            sLastSubmitAt = System.currentTimeMillis();
            log("[submit:" + why + "] frag=" + frag.getClass().getName() + " parm " + parm(parm)
                    + " age=" + (System.currentTimeMillis() - sLastParmAt) + "ms");
            XposedHelpers.callMethod(frag, "U", parm);
            log("[submit:" + why + "] U(AddScoreParm) invoked");
        } catch (Throwable t) { log("[submit:" + why + "] fail: " + t); }
    }
    // =====================================================================
    // 1b. 主动提交加分：从 listener 反查 BaseTaskFragment -> U(AddScoreParm)
    // =====================================================================
    private static void tryForceSubmit(final Object L) {
        runOnMainDelayed(new Runnable() {
            @Override public void run() {
                try {
                    Class<?> lc = L.getClass();
                    Field[] fs = lc.getDeclaredFields();
                    for (Field f : fs) {
                        f.setAccessible(true);
                        try {
                            log("[submit] listener field " + f.getType().getName() + " " + f.getName()
                                    + " = " + safeClass(f.get(L)));
                        } catch (Throwable ignore) { }
                    }
                    Object frag = null;
                    for (Field f : fs) {
                        f.setAccessible(true);
                        Object v;
                        try { v = f.get(L); } catch (Throwable t) { continue; }
                        if (v != null && (isBtf(v.getClass()))) { frag = v; break; }
                    }
                    if (frag == null) { log("[submit] no fragment ref in listener, abort"); return; }
                    log("[submit] fragment=" + frag.getClass().getName());
                    Object parm = null;
                    try { parm = XposedHelpers.callMethod(frag, "q"); }
                    catch (Throwable t) { log("[submit] q() fail: " + t); }
                    if (parm == null) {
                        try { parm = XposedHelpers.getObjectField(frag, "w"); } catch (Throwable ignore) { }
                    }
                    if (parm == null) { log("[submit] parm=null, abort"); return; }
                    log("[submit] parm " + parm(parm));
                    XposedHelpers.callMethod(frag, "U", parm);
                    log("[submit] U(AddScoreParm) invoked");
                } catch (Throwable t) { log("[submit] fail: " + t); }
            }
        }, REWARD_DELAY_MS + 1200);
    }
    // =====================================================================
    // 2. 观测加分链路：CommonDS.o1 / p1（首参 AddScoreParm，不依赖 Continuation 类型）
    // =====================================================================
    private void ensureDs(final Class<?> ds) {
        if (sDsHooked) return;
        sDsHooked = true;
        log("CommonDS resolved via " + safeLoaderName(ds));
        if (DUMP_MEMBERS) dumpMembers("ds", ds);

        boolean any = false;
        try {
            for (final Method m : ds.getDeclaredMethods()) {
                String nm = m.getName();
                boolean named = nm.matches("[a-z]1") || "o1".equals(nm) || "p1".equals(nm);
                boolean parmFirst = firstParamIs(m, C_PARM);
                if (!named && !parmFirst) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (firstParamIs(m, C_PARM)) sLastDsAt = System.currentTimeMillis();
                        log("[ds] " + m.getName() + " ENTER args=" + args(p.args)
                                + (sRewardFiredAt > 0
                                    ? " (+" + (System.currentTimeMillis() - sRewardFiredAt) + "ms after reward)" : ""));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (p.hasThrowable()) log("[ds] " + m.getName() + " THROW " + p.getThrowable());
                        else log("[ds] " + m.getName() + " RETURN " + safeClass(p.getResult()));
                    }
                });
                log("[ds] observer installed: " + sig(m));
                any = true;
            }
        } catch (Throwable t) {
            log("[ds] install failed: " + t);
        }
        if (!any) {
            log("[ds] no o1/p1(AddScoreParm,...) found -> dump only");
            sDsHooked = false;
        }
    }

    // =====================================================================
    // 3. 观测 BaseTaskFragment.O(AddScoreParm) / A(...)
    // =====================================================================
    private void ensureBtf(final Class<?> btf) {
        if (sBtfHooked) return;
        sBtfHooked = true;
        log("BaseTaskFragment resolved via " + safeLoaderName(btf));
        sBtfClass = btf;
        if (DUMP_MEMBERS) dumpMembers("btf", btf);

        boolean any = false;
        try {
            for (final Method m : btf.getDeclaredMethods()) {
                String nm = m.getName();
                boolean isO = ("O".equals(nm) || "C".equals(nm) || "p".equals(nm)) && firstParamIs(m, C_PARM);
                boolean isA = "A".equals(nm);
                // TRACE 模式：所有方法都挂上，reward 之后再打日志（用来定位提交卡在哪一步）
                boolean traceAll = TRACE_BTF_ALL;
                if (!isO && !isA && !traceAll) continue;
                try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.thisObject != null && isBtf(p.thisObject.getClass())) sLastFrag = p.thisObject;
                        if ("O".equals(m.getName()) || "C".equals(m.getName())) {
                            log("[btf] " + m.getName() + "(AddScoreParm) " + parm(p.args.length > 1 ? p.args[1] : (p.args.length > 0 ? p.args[0] : null))
                                    + " caller=" + stack());
                        } else {
                            if (sRewardFiredAt > 0) {
                                log("[btf] " + m.getName() + " enter args=" + args(p.args)
                                        + " (+" + (System.currentTimeMillis() - sRewardFiredAt) + "ms after reward)");
                            }
                        }
                    }
                });
                } catch (Throwable ignore) { continue; }
                log("[btf] observer installed: " + sig(m));
                any = true;
            }
        } catch (Throwable t) {
            log("[btf] install failed: " + t);
        }
        if (!any) sBtfHooked = false;

        // 同时挂 BaseTaskFragment$d（监听器实现类），看回调到底走到哪
        try {
            Class<?> d = XposedHelpers.findClass("com.ilife.module.home.view.fragment.BaseTaskFragment$d",
                    btf.getClassLoader());
            if (DUMP_MEMBERS) dumpMembers("btf$d", d);
            for (final Method m : d.getDeclaredMethods()) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        log("[listener] " + m.getName() + "(" + args(p.args) + ")");
                    }
                });
                log("[listener] hooked " + sig(m));
            }
        } catch (Throwable t2) {
            log("[listener] hook failed: " + t2);
        }
    }

    private static int sTraceCount = 0;
    private static final int TRACE_CAP = 2500;

    private static String shortName(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }

    /** 无条件追踪（不看 reward 时间戳），用于定位另一条广告链路 */
    private void ensureTraceAlways(final String tag, final Class<?> c) {
        try {
            if (DUMP_MEMBERS) dumpMembers(tag, c);
            Method[] ms = c.getDeclaredMethods();
            for (final Method m : ms) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (sTraceCount++ > TRACE_CAP) return;
                            log("[" + tag + "] " + m.getName() + " enter args=" + args(p.args));
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (p.hasThrowable()) log("[" + tag + "] " + m.getName() + " THROW " + p.getThrowable());
                        }
                    });
                } catch (Throwable ignore) { }
            }
            log("[" + tag + "] traced " + ms.length + " methods");
        } catch (Throwable t) {
            log("[" + tag + "] trace failed: " + t);
        }
    }
    // =====================================================================
    // 3b. 通用追踪：把某个类的所有方法都挂上（reward 之后才打日志）
    // =====================================================================
    private void ensureTraceAll(final String tag, final Class<?> c) {
        try {
            if (DUMP_MEMBERS) dumpMembers(tag, c);
            for (final Method m : c.getDeclaredMethods()) {
                final String nm = m.getName();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (sRewardFiredAt == 0) return;
                        log("[" + tag + "] " + nm + " enter args=" + args(p.args)
                                + " (+" + (System.currentTimeMillis() - sRewardFiredAt) + "ms after reward)");
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (sRewardFiredAt == 0) return;
                        if (p.hasThrowable()) log("[" + tag + "] " + nm + " THROW " + p.getThrowable());
                    }
                });
            }
            log("[" + tag + "] traced all " + c.getDeclaredMethods().length + " methods");
        } catch (Throwable t) {
            log("[" + tag + "] trace failed: " + t);
        }
    }

    // =====================================================================
    // 4. 模拟器调试：WatermelonUtil.ice -> ""
    // =====================================================================
    private volatile boolean sIceHooked;

    private void hookIce(ClassLoader cl) {
        if (sIceHooked) return;
        try {
            for (final Method m : XposedHelpers.findClass("com.ilife.lib.common.util.WatermelonUtil", cl).getDeclaredMethods()) {
                if (!"ice".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                    @Override protected Object replaceHookedMethod(MethodHookParam p) { return ""; }
                });
                sIceHooked = true;
                log("WatermelonUtil.ice -> \"\" (EMULATOR_DEBUG) " + sig(m));
            }
        } catch (Throwable t) {
            log("hook ice failed: " + t);
        }
    }

    // =====================================================================
    // utils
    // =====================================================================
    private static boolean firstParamIs(Method m, String className) {
        Class<?>[] ps = m.getParameterTypes();
        return ps.length > 0 && className.equals(ps[0].getName());
    }

    private static Object defaultValue(Class<?> ret) {
        if (ret == null || ret == void.class) return null;
        if (!ret.isPrimitive()) return null;
        if (ret == boolean.class) return Boolean.FALSE;
        if (ret == int.class) return 0;
        if (ret == long.class) return 0L;
        if (ret == float.class) return 0f;
        if (ret == double.class) return 0d;
        if (ret == short.class) return (short) 0;
        if (ret == byte.class) return (byte) 0;
        if (ret == char.class) return (char) 0;
        return null;
    }

    private static String sig(Member m) {
        if (!(m instanceof Method)) return String.valueOf(m);
        Method mm = (Method) m;
        StringBuilder sb = new StringBuilder(mm.getDeclaringClass().getSimpleName())
                .append('#').append(mm.getName()).append('(');
        Class<?>[] ps = mm.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(ps[i].getName());
        }
        return sb.append("):").append(mm.getReturnType().getName()).toString();
    }

    private static void dumpMembers(String tag, Class<?> c) {
        if (!sSeen.add(c)) return;
        try {
            StringBuilder sb = new StringBuilder("[" + tag + "] members of " + c.getName() + " loader=" + safeLoaderName(c));
            for (Field f : c.getDeclaredFields()) {
                sb.append("\n  F ").append(f.getType().getName()).append(' ').append(f.getName());
            }
            for (Method m : c.getDeclaredMethods()) {
                sb.append("\n  M ").append(sig(m));
            }
            for (String line : sb.toString().split("\n")) log(line);
            log("[" + tag + "] fields=" + c.getDeclaredFields().length + " methods=" + c.getDeclaredMethods().length);
        } catch (Throwable t) {
            log("[" + tag + "] dump failed: " + t);
        }
    }

    /** 取调用栈（只保留 com.ilife / 关键帧），用来定位是谁触发的 */
    private static String stack() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.startsWith("android.os") || cn.startsWith("de.robv") || cn.startsWith("java.lang")
                        || cn.contains("XposedBridge") || cn.contains("com.yg.adskip")
                        || cn.startsWith("java.lang.reflect")) continue;
                sb.append("\n      at ").append(cn).append('.').append(e.getMethodName())
                  .append(':').append(e.getLineNumber());
                if (++shown >= 8) break;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String args(Object[] a) {
        if (a == null || a.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(", ");
            Object v = a[i];
            if (v == null) { sb.append("null"); continue; }
            String cn = v.getClass().getName();
            if (v instanceof Number || v instanceof Boolean || v instanceof String) {
                sb.append(cn).append('=').append(v);
            } else if (cn.startsWith("com.ilife.lib.coremodel.data.parm")) {
                sb.append(parm(v));
            } else {
                sb.append(cn);
            }
        }
        return sb.append("]").toString();
    }

    private static String parm(Object parm) {
        if (parm == null) return "parm=null";
        try {
            return "adId=" + XposedHelpers.callMethod(parm, "getAdId")
                    + " addScore=" + XposedHelpers.callMethod(parm, "getAddScore")
                    + " addScoreType=" + XposedHelpers.callMethod(parm, "getAddScoreType")
                    + " type=" + XposedHelpers.callMethod(parm, "getType")
                    + " weekday=" + XposedHelpers.callMethod(parm, "getWeekday");
        } catch (Throwable t) {
            return "parm=" + parm + " (field read fail: " + t + ")";
        }
    }

    private static String safeClass(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static String safeLoaderName(Class<?> c) {
        try {
            ClassLoader l = c.getClassLoader();
            return l == null ? "boot" : l.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(l));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }
}
