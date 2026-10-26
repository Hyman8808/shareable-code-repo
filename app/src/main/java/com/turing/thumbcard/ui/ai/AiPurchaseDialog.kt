package com.turing.thumbcard.ui.ai

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.turing.thumbcard.R
import com.turing.thumbcard.data.ai.AiQuotaStore
import com.turing.thumbcard.databinding.DialogAiPurchaseBinding
import com.turing.thumbcard.databinding.ItemPurchaseTierBinding

/**
 * 「买张数」弹窗。
 *
 * 档位由产品定价（见技术方案 V3.0 §6.2）：
 * 10 张 ¥5 / 50 张 ¥20 / 100 张 ¥30 / 500 张 ¥100，单价从 0.5 元降到 0.2 元。
 *
 * **每一档都配一句单价 + 场景**，因为纯数字用户无法判断该买哪个：
 * 「100 张」到底是一次活动的量还是一个学年的量，只有写出来才知道。
 *
 * ⚠️ 支付通道尚未接入：确认后只在**本地账本**加张数，不发起支付、不扣款。
 * 所以按钮与说明必须诚实标注「模拟」——
 * 这种「看起来像付了钱其实没有」的按钮，文案一旦含糊，
 * 测试同事会以为真买了，用户也会投诉。
 * 正式价格已经写进 UI（用户看到的就是最终价），接入支付后只换确认按钮的动作。
 */
object AiPurchaseDialog {

    /** 一档：张数 / 价格（元）/ 场景说明 */
    private data class Tier(val count: Int, val priceYuan: Int, val hintRes: Int)

    /** 默认落在 50 张：中间档，别一上来就推最贵的 */
    private const val DEFAULT_TIER = 50

    /** 定价：10/5、50/20、100/30、500/100 */
    private val TIERS = listOf(
        Tier(10, 5, R.string.ai_tier_hint_10),
        Tier(50, 20, R.string.ai_tier_hint_50),
        Tier(100, 30, R.string.ai_tier_hint_100),
        Tier(500, 100, R.string.ai_tier_hint_500)
    )

    /**
     * @param onPurchased 购买成功后回调（张数已加到本地账本）
     */
    fun show(context: Context, onPurchased: (Int) -> Unit) {
        val b = DialogAiPurchaseBinding.inflate(LayoutInflater.from(context))
        val dialog = AlertDialog.Builder(context).setView(b.root).create()

        b.tvPurchaseSub.text = context.getString(
            R.string.ai_buy_sub,
            AiQuotaStore.remaining(context),
            AiQuotaStore.freeQuota
        )

        var selected = DEFAULT_TIER

        val rows = TIERS.map { tier ->
            val row = ItemPurchaseTierBinding.inflate(
                LayoutInflater.from(context), b.groupTiers, false
            )
            row.tvTierCount.text = context.getString(R.string.ai_tier_count_fmt, tier.count)
            row.tvTierHint.text = context.getString(tier.hintRes)
            row.tvTierPrice.text = context.getString(R.string.ai_tier_price_fmt, tier.priceYuan)
            b.groupTiers.addView(row.root)
            tier to row
        }

        fun render() {
            rows.forEach { (tier, row) ->
                val on = tier.count == selected
                row.ivTierCheck.visibility = if (on) View.VISIBLE else View.INVISIBLE
                row.root.setBackgroundResource(
                    if (on) R.drawable.bg_style_tile_on else R.drawable.bg_style_tile
                )
            }
            // 确认按钮带上当前档价格：让用户在按下去之前就看清要付多少
            val price = rows.firstOrNull { it.first.count == selected }?.first?.priceYuan ?: 0
            b.btnPurchaseConfirm.text =
                context.getString(R.string.ai_buy_confirm_price_fmt, price)
        }

        rows.forEach { (tier, row) ->
            row.root.setOnClickListener {
                selected = tier.count
                render()
            }
        }
        render()

        b.btnPurchaseConfirm.setOnClickListener {
            AiQuotaStore.addCredits(context, selected)
            dialog.dismiss()
            onPurchased(selected)
        }
        b.btnPurchaseCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}
