package com.twig.app.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * 查看器/编辑器正文用的 EditText:`wrap_content` 测出来的宽度上再多留一个光标的位置。
 *
 * ★ 不多留的话,**最宽那一行行尾的光标会压在最后一个字上**:框架的
 * `Editor.clampHorizontalPosition` 一旦发现光标 x 顶到了文本区右边界,就把它整体往左
 * 拉一个光标宽度塞回可见区(本意是"别让光标被裁掉"),而 wrap_content 的文本区宽度
 * **恰好等于最长行的宽度**——光标走到那一行行尾必然触发夹取。多给几个像素,夹取的
 * 条件就不再成立,光标回到它该在的位置。
 *
 * 加宽只发生在非 EXACTLY 测量下(本来就是被 HorizontalScrollView 按 UNSPECIFIED 量的);
 * 自动换行那条路要把 `maxWidth` 相应减掉 [cursorPad],否则加完正好比视口宽出这几像素、
 * 平白多出一段横向滚动(见 `TextViewerActivity.applyWrap`)。
 */
class CodeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** 给光标预留的宽度(px)。光标画笔通常 2px 上下,给 3dp 留足余量。 */
    val cursorPad: Int = (resources.displayMetrics.density * 3f).toInt().coerceAtLeast(2)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
            setMeasuredDimension(measuredWidth + cursorPad, measuredHeight)
        }
    }
}
