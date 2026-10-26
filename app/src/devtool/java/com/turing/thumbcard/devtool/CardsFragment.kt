package com.turing.thumbcard.devtool

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.turing.thumbcard.BuildConfig
import com.turing.thumbcard.databinding.DevFragmentCardsBinding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 记录面板：直接打老云端 `api/record/list`，把 V3.1 的三个新字段摊开给人看。
 *
 * 为什么不用项目里的 Retrofit + Room：联调工具要能随时改 apikey / deviceId / 地址，
 * 而且不该往数据库里写任何东西。这里一个 HttpURLConnection 就够了。
 */
class CardsFragment : Fragment() {

    private var _b: DevFragmentCardsBinding? = null
    private val b get() = _b!!

    private val adapter = DevRowAdapter { row -> showRaw(row.tag as? String) }
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = DevFragmentCardsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.rvCards.layoutManager = LinearLayoutManager(requireContext())
        b.rvCards.adapter = adapter

        b.etApiKey.setText(BuildConfig.DEFAULT_API_KEY)
        b.etBase.setText(BuildConfig.RECORD_BASE_URL)
        b.btnPull.setOnClickListener { pull() }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private fun pull() {
        val binding = _b ?: return
        val key = binding.etApiKey.text.toString().trim()
        val deviceId = binding.etDeviceId.text.toString().trim()
        val base = binding.etBase.text.toString().trim().ifBlank { BuildConfig.RECORD_BASE_URL }
        val lang = binding.etLang.text.toString().trim()

        if (key.isBlank()) {
            binding.tvResult.text = "apikey 不能为空"
            return
        }
        if (deviceId.isBlank()) {
            binding.tvResult.text = "deviceId 不能为空"
            return
        }

        binding.tvResult.text = "拉取中…"
        DevLog.step("拉记录 deviceId=$deviceId lang=${if (lang.isBlank()) "(不传)" else lang}")

        io.execute {
            val result = runCatching { fetch(base, key, deviceId, lang) }
            val act = activity ?: return@execute
            act.runOnUiThread {
                val bb = _b ?: return@runOnUiThread
                result.onSuccess { (rows, raw) ->
                    adapter.submit(rows)
                    bb.tvResult.text = if (rows.isEmpty()) "0 条（设备还没拍，或 deviceId 不对）" else "${rows.size} 条"
                    if (rows.isEmpty()) {
                        DevLog.step("拉记录成功但列表为空")
                    } else {
                        val withLocalized = rows.count { it.sub.contains("localized=") }
                        DevLog.step("拉记录成功：${rows.size} 条，其中带本地化名称 $withLocalized 条")
                    }
                    raw.firstOrNull()?.let { DevLog.step("首条原文：${it.take(400)}") }
                }.onFailure { e ->
                    bb.tvResult.text = "失败：${e.message}"
                    DevLog.error("拉记录失败：${e.message}")
                }
            }
        }
    }

    /** @return rows 用于列表展示，raw 保留原始 JSON 片段供点击查看 */
    private fun fetch(
        base: String, key: String, deviceId: String, lang: String
    ): Pair<List<DevRow>, List<String>> {
        val url = buildString {
            append(base.trimEnd('/'))
            append("/api/record/list?key=").append(Uri.encode(key))
            append("&deviceId=").append(Uri.encode(deviceId))
            append("&page=1&size=20")
            if (lang.isNotBlank()) append("&lang=").append(Uri.encode(lang))
        }
        val body = httpGet(url)
        val root = JSONObject(body)
        val code = root.optInt("code", -1)
        if (code != 200) {
            throw IllegalStateException("code=$code ${root.optString("message")}")
        }
        val arr = root.optJSONObject("data")?.optJSONArray("list")
            ?: return Pair(emptyList<DevRow>(), emptyList<String>())
        val rows = ArrayList<DevRow>(arr.length())
        val raws = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val chinese = o.optString("name_chinese", "")
            val english = o.optString("name_english", "")
            val localized = o.optString("name_localized", "")
            val localizedLang = o.optString("name_localized_lang", "")
            val id = o.optLong("id", 0L)
            val time = o.optString("create_time", "")

            val title = localized.ifBlank { chinese.ifBlank { "(无名)" } }
            val sub = buildString {
                append("EN ").append(english.ifBlank { "-" })
                append("   zh ").append(chinese.ifBlank { "-" })
                if (localized.isNotBlank()) {
                    append("   localized=").append(localized).append('(').append(localizedLang).append(')')
                }
                append("   id=").append(id)
            }
            rows.add(DevRow(title = title, sub = sub, trail = time.takeLast(8), tag = o.toString(2)))
            raws.add(o.toString(2))
        }
        return rows to raws
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code $text")
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun showRaw(json: String?) {
        if (json.isNullOrBlank()) return
        val ctx = context ?: return
        val tv = TextView(ctx).apply {
            text = json
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(40, 40, 40, 40)
            setTextIsSelectable(true)
        }
        val sv = ScrollView(ctx).apply { addView(tv) }
        AlertDialog.Builder(ctx)
            .setTitle("原始返回")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show()
    }
}
