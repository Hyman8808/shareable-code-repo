package com.turing.thumbcard.data.ai

import android.content.Context
import com.turing.thumbcard.BuildConfig
import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.data.image.ImageStore
import com.turing.thumbcard.data.repo.RecordRepository
import java.io.File

/**
 * AI 出图编排：额度校验 → 取原图 → 调方舟 → 落盘 → 记账 → 回写卡片。
 *
 * 把这一串放进仓库层而不是 Activity 里，是因为它有**严格的先后顺序**：
 *  ① 先扣额度再生成 → 生成失败钱就白扣了；
 *  ② 先生成再落盘再记账 → 中途任一环节失败，账面上不该少一张；
 *  ③ 余额不足必须**在发请求之前**就拦掉，别让用户白等 90 秒才被告知要付费。
 */
class AiRepository(context: Context) {

    private val appContext = context.applicationContext
    private val repo = RecordRepository(appContext)

    sealed class Outcome {
        /** 成功，[file] 已落盘，[pixels] 是服务端返回的尺寸（如 `2160x1080`） */
        data class Ok(val file: File, val pixels: String?) : Outcome()

        /** 余额不足（免费额度 + 已购张数都花完了） */
        data object NoQuota : Outcome()

        /** 拿不到原图（记录没图 / 下载失败 / 本地没有），没法做图生图 */
        data object NoSource : Outcome()

        /** 生成失败；[fail] 用于选文案 */
        data class Err(val fail: ArkImageClient.Fail, val detail: String? = null) : Outcome()
    }

    /**
     * 为一张卡生成指定风格的图。
     *
     * 成功后会自动把这张卡切到新图上（写入 `aiStyle`），
     * 也就是「生成完立刻生效」——用户不用再点一次「设为卡片图」。
     */
    suspend fun generate(card: WordCardEntity, style: AiStyle): Outcome {
        // 白名单设备（自研机/演示机）既不校验也不扣减 —— 见 AiPolicy
        val unlimited = AiPolicy.isUnlimited(card)
        if (!unlimited && !AiQuotaStore.canGenerate(appContext)) return Outcome.NoQuota

        // 原图必须在本地：送 base64 而不是 OSS 链接，保证送进去的就是用户此刻看到的那张
        val src = ImageStore.ensureOriginal(appContext, card.id, card.imageUrl)
            ?: return Outcome.NoSource

        val prompt = AiPrompts.build(style, card.nameEnglish, card.nameChinese)
        val res = ArkImageClient.generate(
            apiKey = BuildConfig.ARK_API_KEY,
            model = BuildConfig.ARK_MODEL,
            prompt = prompt,
            source = src,
            // "auto" → 按原图宽高比算具体尺寸（4:3 / 16:9 / 1:1 都能一比一还原）；
            // 配成 2K/1K 之类的档位则强制用档位，用于排查「是不是尺寸参数导致的出图异常」
            size = BuildConfig.ARK_SIZE.takeIf { it.isNotBlank() && !it.equals("auto", true) }
        )

        return when (res) {
            is ArkImageClient.Result.Err -> Outcome.Err(res.fail, res.detail)
            is ArkImageClient.Result.Ok -> {
                // 落盘会同时产出两张：全尺寸（看大图/存相册）+ 4:3 卡片用图（列表封面）
                val file = ImageStore.saveAi(appContext, card.id, style, res.bytes)
                    ?: return Outcome.Err(ArkImageClient.Fail.SERVER, "图片写入本地失败")

                // 到这一步才算真的产出，才扣额度。
                // 不限额设备不记账：否则压测几百张后账本变成负数，反而没法演示「余额不足」分支。
                if (!unlimited) AiQuotaStore.consume(appContext)

                // 🔴 「生成了但封面没换」的第一嫌疑人就是这一步：写库失败被 runCatching 吞掉。
                //    这里回读一次做断言 —— 只在日志里喊，不影响结果（图已经落盘、钱已经花了）。
                repo.setAiStyle(card.id, style.code)
                val persisted = runCatching { repo.findCard(card.id)?.aiStyle }.getOrNull()
                if (persisted != style.code) {
                    android.util.Log.w(
                        "AiRepository",
                        "aiStyle 未写回 card=${card.id} expect=${style.code} got=$persisted"
                    )
                }
                Outcome.Ok(file, res.size)
            }
        }
    }

    /** 卡片当前用哪种风格（null = 原图） */
    suspend fun currentStyle(cardId: Long): AiStyle? =
        AiStyle.fromCode(repo.findCard(cardId)?.aiStyle)

    /**
     * 切换这张卡显示的图：传 null 回退原图，传风格则切到该风格的 AI 图。
     *
     * 目标文件不存在时返回 false（例如换机后只同步了数据库、没同步文件）。
     */
    suspend fun switchTo(cardId: Long, style: AiStyle?): Boolean {
        if (style != null && ImageStore.ai(appContext, cardId, style) == null) return false
        repo.setAiStyle(cardId, style?.code)
        return true
    }

    /** 这张卡有没有已经生成过、还能用的 AI 图（用于详情页入口文案） */
    fun hasAiImage(cardId: Long, style: AiStyle): Boolean =
        ImageStore.ai(appContext, cardId, style) != null
}
