package com.turing.thumbcard.ui.card

import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.data.dict.DictCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网格卡第二行文字的**唯一来源**：音标优先，退到拼音。
 *
 * 首页「今天拍的」和收藏页都用卡片，两页的第二行必须是同一套判据 ——
 * 所以抽到这里，而不是各自写一份。
 *
 * 🔴 缓存键的 `dicts` 必须与详情页一致（`listOf("en_word", "en_phrase")`）。
 *    不一致的话，同一个词在详情页和卡片上会命中两份不同的缓存文件，
 *    其中一份永远是空的 —— 表现就是「详情页有音标，卡片上没有」。
 *
 * 只读本地 [DictCache]（文件），**不发网络请求**：收藏几十张卡也不会有一次请求。
 * 调用方必须在 IO 线程一次性算完整表再交给列表，不要在 `onBindViewHolder` 里调 ——
 * 那里每滚动一次就调一次，读文件会掉帧。
 *
 * 查不到就返回 null，由 [WordCardGridAdapter] 用 INVISIBLE 占位
 * （同一行的卡要等高等宽，GONE 会让网格参差）。
 */
internal suspend fun buildCardGlosses(
    cards: List<WordCardEntity>
): Map<Long, WordCardGridAdapter.Gloss> = withContext(Dispatchers.IO) {
    val out = HashMap<Long, WordCardGridAdapter.Gloss>(cards.size)
    for (c in cards) {
        val en = c.nameEnglish?.trim()?.takeIf { it.isNotBlank() }
        if (en != null) {
            val ipa = runCatching {
                DictCache.read(DictCache.keyOf(en, listOf("en_word", "en_phrase")))
                    ?.entry?.phoneticUsDisplay
            }.getOrNull()
            if (!ipa.isNullOrBlank()) {
                out[c.id] = WordCardGridAdapter.Gloss(ipa)
                continue
            }
        }
        // 音标没有（从没开过这个词的详情页）→ 退到拼音，至少给一个读音线索
        val py = c.pinyin?.trim()?.takeIf { it.isNotBlank() }
        if (py != null) out[c.id] = WordCardGridAdapter.Gloss(py)
    }
    out
}
