package com.classschedule.app.data.backup

/**
 * 极简 JSON 解析器（只解析，不序列化）。
 *
 * 为什么不用 org.json：它在单元测试里是 android.jar 的桩（方法全部抛 "Stub!"），
 * 而备份文件的解析正确性必须用纯 JVM 单测钉死。为什么不用 moshi/gson：
 * 备份结构是固定的几张平铺表，一个百来行的解析器足够，且零新增依赖
 * （用户的网络环境是手机热点，能少下载一个依赖就少一个风险）。
 *
 * 支持：object / array / string / number / true / false / null。
 * 数字统一按 Long（整数）或 Double（小数）解析。
 * 序列化（stringify）曾服务于导出功能，0.10.0 导出移除后一并删除——
 * 撤销快照走内存 BackupData，不需要把数据写回 JSON 文本。
 */
class JsonException(message: String) : Exception(message)

object MiniJson {

    fun parse(text: String): Any? = Parser(text).parseDocument()

    // ── 解析 ────────────────────────────────────────────────

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseDocument(): Any? {
            skipWs()
            val v = parseValue()
            skipWs()
            if (pos < src.length) throw JsonException("位置 $pos 处有多余内容")
            return v
        }

        private fun parseValue(): Any? = when (val c = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> when {
                c == '-' || c.isDigit() -> parseNumber()
                else -> throw JsonException("位置 $pos 处是无法解析的字符 '$c'")
            }
        }

        private fun parseObject(): MutableMap<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                skipWs()
                map[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw JsonException("位置 $pos 处对象缺少 ',' 或 '}'")
                }
            }
        }

        private fun parseArray(): MutableList<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return list }
            while (true) {
                skipWs()
                list.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw JsonException("位置 $pos 处数组缺少 ',' 或 ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (pos + 4 > src.length) throw JsonException("\\u 转义不完整")
                            val hex = src.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw JsonException("位置 ${pos - 1} 处是无法识别的转义 '\\$e'")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E' || src[pos] == '+' || src[pos] == '-')) pos++
            val raw = src.substring(start, pos)
            return when {
                raw.isEmpty() -> throw JsonException("位置 $start 处数字为空")
                raw.contains('.') || raw.contains('e') || raw.contains('E') -> raw.toDoubleOrNull()
                    ?: throw JsonException("无法解析数字 '$raw'")
                else -> raw.toLongOrNull() ?: throw JsonException("无法解析数字 '$raw'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!src.startsWith(word, pos)) throw JsonException("位置 $pos 处不是 '$word'")
            pos += word.length
            return value
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            if (pos >= src.length) throw JsonException("内容在位置 $pos 处意外结束")
            return src[pos]
        }

        private fun next(): Char = peek().also { pos++ }

        private fun expect(c: Char) {
            if (peek() != c) throw JsonException("位置 $pos 处应为 '$c'，实际是 '${peek()}'")
            pos++
        }
    }
}
