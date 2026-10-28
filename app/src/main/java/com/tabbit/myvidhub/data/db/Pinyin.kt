package com.tabbit.myvidhub.data.db

import com.github.stuxuhai.jpinyin.PinyinHelper

/**
 * 拼音首字母提取器。
 *
 * 用 jpinyin 的离线词典：单字读音 + 常用词组多音字（如「重庆」→ cq）都由字典处理。
 * 之前那套 Unicode 区间估算会把「兰」算成 b，导致用户按正确拼音根本搜不到。
 */
object PinyinUtil {

    fun computeInitials(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        val initials = runCatching { PinyinHelper.getShortPinyin(trimmed) }
            .getOrElse { return trimmed.filter { it.isLetterOrDigit() }.lowercase() }
        return initials.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
    }
}

/** 字符串扩展：直接 "偷天换日".toPinyinInitials() */
fun String.toPinyinInitials(): String = PinyinUtil.computeInitials(this)
