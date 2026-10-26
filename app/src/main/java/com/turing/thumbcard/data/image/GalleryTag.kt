package com.turing.thumbcard.data.image

import com.turing.thumbcard.data.ai.AiStyle

/**
 * 存进系统相册的图的「身份标识」—— 回答「这张图是哪张卡、哪种风格生成的」。
 *
 * ## 为什么需要它
 * AI 出图是按张付费的（免费 2 张之后每张都要扣额度，且单张要 60–110 秒）。
 * 而生成结果只存在应用私有目录（`filesDir/cards/ai/`），
 * **卸载、清数据、换机、系统清理缓存都会没**；
 * 用户在相册里存下来的那份反而更长寿。
 * 有了标识，重装后可以直接从相册把已经花过钱的图捞回来，不用重新生成。
 *
 * ## 锚点为什么是 cardId
 * [com.turing.thumbcard.data.db.WordCardEntity.id] 是**云端记录 id**（不是本地自增），
 * 所以重新配网绑定同一台相机、甚至换一台手机重新登录同账号拉记录，
 * 同一张卡拿到的 id 完全一致。这是整个找回功能能「精确对应」的前提，
 * 换成任何本地生成的序号都会在重装后失效。
 *
 * ## 双通道
 * | 通道 | 位置 | 优点 | 缺点 |
 * |---|---|---|---|
 * | EXIF | `ImageDescription` + `UserComment` 双写 | 改名、移动文件夹都跟着图走 | 需要重写一次 JPEG 字节 |
 * | 文件名 | `..._TC{id}_{日期}.jpg` | 零成本，MediaStore 一次查询就能筛 | 用户改名就断 |
 *
 * 两个都写：EXIF 兜「改了名」，文件名兜「EXIF 被修图 App 洗掉了」。
 * 用户口头承诺「不改名」，但真实世界里「微信发给自己再存一遍」就会改名，
 * 所以不能只依赖其中任何一个。
 *
 * ⚠️ 下面这些字面量一旦发布就不能再改（同 [AiStyle.code]）：
 * 改了就等于把老用户相册里的图全部变成「认不出的陌生图」。
 */
object GalleryTag {

    /** 标识串前缀。用来和别的 App 写进 EXIF 的内容区分 */
    private const val PREFIX = "ThumbCard"

    /** 标识格式版本。将来加字段（例如加了生成时间）靠它区分 */
    private const val VERSION = 1

    private const val SEP = "|"

    /** 文件名里的标记段前缀，如 `Iced_Drink_高清_TC10037_20261022.jpg` */
    private const val NAME_MARK = "TC"

    /**
     * 从文件名里捞 cardId。
     *
     * 只认 `_TC数字_` 这种带标记的形态：老版本的文件名末尾也是 8 位数字
     * （存盘时间戳 `takeLast(8)`），如果按「全是数字就当 id」去猜，
     * 每张老图都会被安上一个假 id，比认不出更糟。
     */
    private val NAME_TAG = Regex("""[_\-\s]TC(\d{1,12})(?:[_\-\s.]|$)""")

    /**
     * 老图（没有标识的那批）判定卡通风格的线索词。
     *
     * 三种界面语言的说法都列上：用户存图时的界面语言决定了当时写进文件名的
     * 是「卡通」「Cartoon」还是「卡通插畫」。
     */
    private val CARTOON_HINTS = listOf("卡通", "cartoon", "插畫", "插画")

    /** 一张相册图对应的卡片与风格 */
    data class Meta(val cardId: Long, val style: AiStyle)

    // ==================== 编解码 ====================

    /** 编成写进 EXIF 的字符串 */
    fun encode(meta: Meta): String =
        "$PREFIX${SEP}v=$VERSION${SEP}id=${meta.cardId}${SEP}style=${meta.style.code}"

    /**
     * 从 EXIF 字符串解出标识。
     *
     * 容错到「只要串里出现 PREFIX 就试着解」：EXIF 的 `UserComment` 规范带 8 字节
     * 字符集前缀（`ASCII\0\0\0`），不同厂商的读写实现不一定替我们剥掉，
     * 严格 `startsWith` 会漏掉一半机型。
     */
    fun parse(raw: String?): Meta? {
        val s = raw ?: return null
        val at = s.indexOf(PREFIX)
        if (at < 0) return null

        var id: Long? = null
        var style: AiStyle? = null
        for (part in s.substring(at).split(SEP)) {
            val kv = part.split('=', limit = 2)
            if (kv.size != 2) continue
            when (kv[0].trim()) {
                "id" -> id = kv[1].trim().toLongOrNull()
                "style" -> style = AiStyle.fromCode(kv[1].trim())
            }
        }
        val cid = id ?: return null
        return Meta(cid, style ?: AiStyle.HD)
    }

    /**
     * 从文件名解出标识（新版存图才有）。
     *
     * 风格仍靠 [CARTOON_HINTS] 判断：文件名里的风格段是**本地化文案**
     * （高清 / 高畫質 / HD…），穷举下去会没完，而卡通是唯一需要区分的另一档。
     */
    fun parseFromName(displayName: String?): Meta? {
        val name = displayName ?: return null
        val id = NAME_TAG.find(name)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return Meta(id, styleOfName(name))
    }

    /** 文件名里嵌的标记段 */
    fun nameTag(cardId: Long): String = "$NAME_MARK$cardId"

    /** 老图猜风格：命中卡通线索词就是卡通，否则按高清 */
    fun styleOfName(name: String): AiStyle {
        val lower = name.lowercase()
        return if (CARTOON_HINTS.any { lower.contains(it) }) AiStyle.CARTOON else AiStyle.HD
    }
}
