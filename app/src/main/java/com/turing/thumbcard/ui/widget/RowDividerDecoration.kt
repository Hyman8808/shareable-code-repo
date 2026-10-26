package com.turing.thumbcard.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.turing.thumbcard.R

/**
 * 词典行之间的 1dp 发丝线（首页 / 收藏 Tab / 全部照片页三处共用）。
 *
 * 为什么必须有：行式列表**没有卡底**，88dp 的行紧挨着、左边一张 64dp 缩略图，
 * 没有这道线时两行会读成「一个两行高的块」——「词条之间的细线」是「墨标」的签名之一。
 *
 * 几何（**行内坐标系**，三处列表完全相同）：
 *      inset = 20(行内 paddingStart) + 64(缩略图) + 14(图文间距) = **98dp**
 *   不要用「页面坐标系」去算：`rvRecent` 带 `marginStart=-20dp` 只是把整个
 *   RecyclerView 左移 20dp，**行内部的内边距不受影响**；而另外两处列表
 *   （fragment_cards / activity_card_list）的 RV 本来就在页面 x=0 且
 *   `paddingHorizontal=0dp`。所以三处 inset 都是 98dp，不要按 -20 去减成 78。
 *
 * 画法：`drawRect` 画在行的**底部内侧**（y-1dp ~ y），`getItemOffsets` 返回 0 →
 *   **不改变行高**，88dp 步进不变。最后一条数据不画（否则列表底部多一条无主细线）；
 *   判「最后一条」用 adapter 的 itemCount，而不是 childCount（后者是「最后可见项」，
 *   滚动时每行轮流变成它，线会一条条闪掉）。
 */
class RowDividerDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.divider)
        strokeWidth = context.resources.displayMetrics.density
    }

    /** 行内 20dp padding + 64dp 缩略图 + 14dp 图文间距 */
    private val inset = context.resources.displayMetrics.density * 98f

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val last = parent.adapter?.itemCount?.minus(1) ?: return
        if (last < 0) return
        val right = (parent.width - parent.paddingRight).toFloat()
        val left = parent.paddingLeft + inset
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION || pos == last) continue
            val y = child.bottom.toFloat()
            c.drawRect(left, y - paint.strokeWidth, right, y, paint)
        }
    }
}
