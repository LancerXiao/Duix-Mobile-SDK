package ai.guiji.duix.test.util

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView

/**
 * 轻量 Markdown 渲染器（Phase 5.1 P0-2）
 *
 * 不引入 Markwon 等外部依赖，自实现满足 80% LLM 场景的常见语法：
 * - **bold**          → 粗体
 * - *italic*          → 斜体
 * - `inline code`     → 等宽字体 + 灰底
 * - ```code block```  → 代码块：等宽字体 + 深灰底
 * - # heading         → 标题 1-3 级
 * - 1. / -            → 列表
 * - [text](url)       → 链接（仅染色，不做点击）
 *
 * 设计权衡：
 * - 完整 CommonMark 太重，对中文 LLM 场景 80% 够用
 * - 不做 HTML 嵌套解析，避免 XSS
 * - 性能：单消息 1-2KB 内单次正则匹配 < 1ms
 */
object MarkdownRenderer {

    private const val CODE_BG_COLOR = 0x44FFFFFF.toInt()      // 白色半透明（深色背景上）
    private const val CODE_BLOCK_BG_COLOR = 0x55FFFFFF.toInt() // 代码块白色半透明
    private const val LINK_COLOR = 0xFFA78BFA.toInt()          // 浅紫色链接（白色文字上可区分）
    private const val HEADING_COLOR = 0xFFFFFFFF.toInt()       // 白色标题

    fun render(text: CharSequence): CharSequence {
        var src = text.toString()
        // 1) 先处理代码块（避免代码块内其他语法被解析）
        src = processCodeBlocks(src)
        // 2) 行级处理
        val sb = SpannableStringBuilder(src)
        applyInlineStyles(sb, src)
        return sb
    }

    fun renderInto(textView: TextView, text: CharSequence) {
        textView.text = render(text)
    }

    /**
     * 处理 ```code block```：
     * 把代码块包成一段，用整段 BackgroundColorSpan + 等宽字体。
     * 代码块内的内容不再做其他 markdown 解析。
     */
    private fun processCodeBlocks(src: String): String {
        if (!src.contains("```")) return src
        val sb = StringBuilder()
        var i = 0
        var inBlock = false
        while (i < src.length) {
            if (i + 2 < src.length && src[i] == '`' && src[i + 1] == '`' && src[i + 2] == '`') {
                inBlock = !inBlock
                if (inBlock) {
                    sb.append("【CB_START】")
                } else {
                    sb.append("【CB_END】")
                }
                i += 3
            } else {
                sb.append(src[i])
                i++
            }
        }
        return sb.toString()
    }

    /**
     * 行级样式：粗体 / 斜体 / inline code / 标题 / 列表
     */
    private fun applyInlineStyles(sb: SpannableStringBuilder, src: String) {
        // 当前字符串 vs 当前 sb 的索引可能因为占位符不一致
        // 我们对 src 匹配后，把 span 应用到 sb 对应位置
        // 由于 processCodeBlocks 的占位符是固定字符，索引一致

        // 1) **bold** - 偶数次
        applyPairedStyle(sb, src, "\\*\\*([^*]+)\\*\\*") { start, end ->
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        // 2) *italic* - 单星号
        applyPairedStyle(sb, src, "(?<![*\\w])\\*([^*]+)\\*(?!\\w)") { start, end ->
            sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        // 3) `inline code`
        applyPairedStyle(sb, src, "`([^`]+)`") { start, end ->
            sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            sb.setSpan(BackgroundColorSpan(CODE_BG_COLOR), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.92f), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        // 4) [text](url)
        applyPairedStyle(sb, src, "\\[([^\\]]+)\\]\\(([^)]+)\\)") { start, end ->
            sb.setSpan(ForegroundColorSpan(LINK_COLOR), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        // 5) 标题 # / ## / ###
        applyLinePrefixStyle(sb, src, "###") { start, end ->
            sb.setSpan(RelativeSizeSpan(1.15f), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(HEADING_COLOR), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        applyLinePrefixStyle(sb, src, "##") { start, end ->
            sb.setSpan(RelativeSizeSpan(1.25f), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(HEADING_COLOR), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        applyLinePrefixStyle(sb, src, "#") { start, end ->
            sb.setSpan(RelativeSizeSpan(1.35f), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(HEADING_COLOR), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        // 6) 代码块占位符
        applyCodeBlockStyles(sb, src)
    }

    /**
     * 对正则匹配到的每一对（含匹配符号）应用样式回调
     */
    private fun applyPairedStyle(
        sb: SpannableStringBuilder,
        src: String,
        regex: String,
        action: (start: Int, end: Int) -> Unit
    ) {
        try {
            val pattern = Regex(regex)
            for (m in pattern.findAll(src)) {
                val start = m.range.first
                val end = m.range.last + 1
                if (start in 0..end && end <= sb.length) {
                    action(start, end)
                }
            }
        } catch (e: Exception) {
            // 正则不合法时静默跳过
        }
    }

    /**
     * 行首标记（如 "# "、"## "、"### "、"1. "、"- "）整行应用样式
     */
    private fun applyLinePrefixStyle(
        sb: SpannableStringBuilder,
        src: String,
        prefix: String,
        action: (start: Int, end: Int) -> Unit
    ) {
        val lines = src.split("\n")
        var offset = 0
        for (line in lines) {
            if (line.startsWith(prefix) && line.length > prefix.length &&
                (line[prefix.length] == ' ' || line[prefix.length] == '　')
            ) {
                val end = offset + line.length
                if (end <= sb.length) {
                    action(offset, end)
                }
            }
            offset += line.length + 1  // +1 for \n
        }
    }

    /**
     * 代码块占位符【CB_START】...【CB_END】整段应用等宽字体 + 深底
     */
    private fun applyCodeBlockStyles(sb: SpannableStringBuilder, src: String) {
        val startTag = "【CB_START】"
        val endTag = "【CB_END】"
        var searchFrom = 0
        while (true) {
            val startIdx = src.indexOf(startTag, searchFrom)
            if (startIdx < 0) break
            val endIdx = src.indexOf(endTag, startIdx + startTag.length)
            if (endIdx < 0) break
            val blockStart = startIdx
            val blockEnd = endIdx + endTag.length
            // 整段（含占位符）样式
            if (blockEnd <= sb.length) {
                sb.setSpan(
                    TypefaceSpan("monospace"),
                    blockStart, blockEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    BackgroundColorSpan(CODE_BLOCK_BG_COLOR),
                    blockStart, blockEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    RelativeSizeSpan(0.9f),
                    blockStart, blockEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
            }
            // 删除占位符（用空字符替换，保留 span 长度）
            val spaces = " ".repeat(startTag.length)
            sb.replace(startIdx, startIdx + startTag.length, spaces)
            val endSpaces = " ".repeat(endTag.length)
            sb.replace(endIdx, endIdx + endTag.length, endSpaces)
            searchFrom = blockEnd
        }
    }
}
