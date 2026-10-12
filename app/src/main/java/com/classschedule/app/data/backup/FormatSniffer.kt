package com.classschedule.app.data.backup

/**
 * 文件格式嗅探：按内容判断，不信任扩展名。
 *
 * 用户从微信/QQ 收到的备份文件扩展名经常丢或改错，内容才是可靠的：
 *   - JSON 备份以 `{` 开头且含 format 字段（由 BackupJson 校验）
 *   - ICS 含 BEGIN:VCALENDAR
 *   - 其余按 CSV 尝试（CSV 是容错率最高的兜底格式）
 * 粘贴文本不走这里——入口本身就是"粘贴"，格式已知。
 */
object FormatSniffer {

    enum class FileFormat { JSON, ICS, CSV }

    fun sniff(text: String): FileFormat {
        val head = text.trimStart()
        return when {
            head.startsWith("{") -> FileFormat.JSON
            head.contains("BEGIN:VCALENDAR", ignoreCase = true) -> FileFormat.ICS
            else -> FileFormat.CSV
        }
    }
}
