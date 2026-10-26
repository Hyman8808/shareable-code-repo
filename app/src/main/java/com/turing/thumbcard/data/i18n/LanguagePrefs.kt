package com.turing.thumbcard.data.i18n

import android.content.Context
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「物品名称语言」的持久化与变更通知。
 *
 * 为什么不放 Room：Room 当前 version=1 且启用 destructive migration，
 * 加表/加列会**清空 bound_device**，用户需要重新配网——代价远大于收益。
 * 一个单选值放 SharedPreferences 最稳妥。
 *
 * 变更通知：首页 / 单词卡页是 Fragment，切 Tab 时走 `onHiddenChanged` 而不是
 * `onResume`，靠生命周期刷新不可靠。所以用监听器 + 各页在 `onViewCreated` 注册，
 * 用户一改语言，已创建的页面立即重渲染。
 */
object LanguagePrefs {

    private const val TAG = "LanguagePrefs"
    private const val PREF = "thumbcard_name_lang"
    private const val KEY = "current_lang"

    private val listeners = CopyOnWriteArrayList<(NameLanguage) -> Unit>()

    /** 进程内缓存，避免每次绑定 item 都读一次 SP */
    @Volatile
    private var cached: NameLanguage? = null

    fun current(context: Context): NameLanguage {
        cached?.let { return it }
        val lang = try {
            val sp = context.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            NameLanguage.fromCode(sp.getString(KEY, null))
        } catch (t: Throwable) {
            // 存储不可用（卓易通等容器）→ 用默认值，不抛异常
            Log.w(TAG, "read failed: ${t.javaClass.simpleName}")
            NameLanguage.DEFAULT
        }
        cached = lang
        return lang
    }

    /**
     * 设置语言并通知所有已注册页面。
     *
     * @return 是否发生了变化（false 表示选的还是原来那个）
     */
    fun set(context: Context, lang: NameLanguage): Boolean {
        val old = current(context)
        cached = lang
        val saved = try {
            context.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, lang.code)
                .commit()
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: ${t.javaClass.simpleName}")
            false
        }
        // 即便落盘失败也通知界面：本次会话内仍然生效
        if (!saved) Log.w(TAG, "persist failed, in-memory only")
        if (old != lang) {
            listeners.forEach { l -> runCatching { l(lang) } }
            return true
        }
        return false
    }

    fun addListener(l: (NameLanguage) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: (NameLanguage) -> Unit) {
        listeners.remove(l)
    }
}
