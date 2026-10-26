package com.turing.thumbcard.data.ai

import androidx.annotation.StringRes
import com.turing.thumbcard.R

/**
 * 两种 AI 出图风格。
 *
 * `code` 会**写进数据库**（`word_card.aiStyle`）和**文件名**，
 * 一旦发布就不能再改 —— 改了就认不出用户已经生成过的图。
 */
enum class AiStyle(
    val code: String,
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
    /** 结果页角标用短名，列表/预览页角标也用这个 */
    @StringRes val badgeRes: Int
) {
    /** 低清原图 → 高清卡片（保留构图与版式，质感与细节重建） */
    HD("hd", R.string.ai_style_hd, R.string.ai_style_hd_desc, R.string.ai_badge_hd),

    /** 卡通插画化（矢量卡通形象 + 简化背景） */
    CARTOON("cartoon", R.string.ai_style_cartoon, R.string.ai_style_cartoon_desc, R.string.ai_badge_cartoon);

    companion object {
        fun fromCode(code: String?): AiStyle? =
            code?.let { c -> values().firstOrNull { it.code == c } }
    }
}

/**
 * 提示词模板。
 *
 * ⚠️ 下面的字句是**实测出来的**，不是照着模板抄的。改任何一句之前先看对应注释。
 *
 * 组成：风格段 + 主体段 + 收口段。
 */
object AiPrompts {

    /**
     * 风格一：低清原图 → 高清图（产品给的模板，实测调整两处）。
     *
     * 相对产品原模板的改动与理由：
     *  - 「重绘成精致高清**卡片**」→「精致高清**图像**」：
     *    实测写「卡片」时模型会自己画卡片边框/标题/词表（真的发生过，见下），
     *    而卡片的文字与版式是 App 绘制的，模型再画一遍就是重影。
     *  - 「保留原有**文字内容**」→「保留原有**构图与主体位置**」：
     *    相机原图里根本没有文字，这句只会诱导模型去「补」文字。
     *  - 其余（高级质感 / 干净排版 / 4K / 细节丰富 / 不要改动布局）一字未动。
     */
    private const val HD_STYLE =
        "参考这张图的构图、版式，重绘成精致高清图像，保留原有构图与主体位置，" +
            "高级质感，干净排版，4K，细节丰富，不要改动布局"

    /**
     * 风格二：重绘为精致卡通插画（本项目新增）。
     *
     * 每一句都对应一个失败模式：
     *  - 「矢量卡通形象重新绘制」而非「卡通化滤镜」—— 避免塑料质感的假 3D；
     *  - 「保留主体的外形特征与识别度」—— 卡通化最容易把物体化简成不可辨认的色块；
     *  - 「背景简化为柔和纯色渐变 + 留白」—— 原图背景是桌面线缆杂物，不简化会成一锅粥；
     *  - 「高端绘本插画」定调，压住「儿童贴纸」那种廉价感。
     *
     * ❌ 别写「单词卡片」「保留文字与版面位置」：实测模型会输出一张完整的卡片，
     *    还自己编标题（那次编出了「3D打印工坊」和四行词表），直接不可用。
     */
    private const val CARTOON_STYLE =
        "把这张照片重绘成精致卡通插画：主体用干净利落的矢量卡通形象重新绘制，" +
            "保留主体的外形特征与识别度；背景简化为柔和的纯色渐变，留出干净的留白；" +
            "配色明亮通透、层次分明；整体像高端绘本插画，细节丰富、质感高级"

    /** 收口段：堵住越界行为（加字、加边框、加水印） */
    private const val TAIL =
        "。只输出画面本身，不要出现任何文字、字母、标题、水印、边框、色卡或拼贴效果。"

    /**
     * 拼出最终 prompt。
     *
     * @param style   风格
     * @param english 英文名，用于锁定主体（可为空）
     * @param chinese 中文名，用于锁定主体（可为空）
     */
    fun build(style: AiStyle, english: String?, chinese: String?): String {
        val stylePart = when (style) {
            AiStyle.HD -> HD_STYLE
            AiStyle.CARTOON -> CARTOON_STYLE
        }
        val subject = subjectClause(english, chinese)

        return buildString {
            append(stylePart)
            append("。")
            if (subject != null) append(subject).append("。")
            append(TAIL.trimStart('。'))
        }
    }

    /**
     * 主体段 —— **这一句决定了成败**。
     *
     * 相机出图只有 160×80，模型在这个尺度上经常认错主体：
     * 实测一张「冰饮」被重画成了纸巾盒 + 黑色保温杯。
     * 而我们本来就**知道这张卡是什么词**，把名字写进提示词，主体立刻准确。
     * 这是「普通修图工具」和「单词卡相机」的差别所在，不要省。
     *
     * 只取中文与英文原名：名称语言可切到 17 种，但机译名喂给模型是噪声。
     */
    private fun subjectClause(english: String?, chinese: String?): String? {
        val en = english?.trim()?.takeIf { it.isNotBlank() }
        val cn = chinese?.trim()?.takeIf { it.isNotBlank() }
        return when {
            cn != null && en != null -> "画面主体是：$cn（$en），请让主体清晰可辨、外形与类别准确"
            cn != null -> "画面主体是：$cn，请让主体清晰可辨、外形与类别准确"
            en != null -> "画面主体是：$en，请让主体清晰可辨、外形与类别准确"
            else -> null
        }
    }
}
