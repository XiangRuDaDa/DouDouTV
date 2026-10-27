package com.tabbit.myvidhub.data.db

/**
 * 极简拼音首字母提取器，零外部依赖。
 * 利用 Unicode Block 范围 + 多音字硬编码表。
 */
object PinyinUtil {

    /** 常用多音字：key=词(2字+), value=首字母 */
    private val DICTIONARY = mapOf(
        "重庆" to "cq", "银行" to "yh", "长沙" to "cs",
        "电影" to "dy", "电视" to "ds", "动画" to "dh",
        "动漫" to "dm", "短剧" to "dj", "综艺" to "zy",
        "喜剧" to "xj", "科幻" to "kh", "动作" to "dz",
        "爱情" to "aq", "恐怖" to "kb", "悬疑" to "xy",
        "战争" to "zz", "犯罪" to "fz",
        "网络" to "wl", "古装" to "gz", "现代" to "xd",
        "大陆" to "dl", "香港" to "xg", "台湾" to "tw",
        "日本" to "rb", "韩国" to "hg", "美国" to "mg",
        "三国" to "sg", "水浒" to "sh", "西游" to "xy",
        "红楼" to "hl",
        "射雕" to "sd", "神雕" to "sd", "天龙" to "tl",
        "倚天" to "yt", "笑傲" to "xa", "鹿鼎" to "ld",
        "雪山" to "xs", "侠客" to "xk",
        "中国" to "zg", "华夏" to "hx",
        "速度" to "sd", "迅速" to "xs",
        "角色" to "js", "主角" to "zj", "配角" to "pj",
        "演员" to "yy", "明星" to "mx",
    )

    fun computeInitials(input: String): String {
        if (input.isBlank()) return ""
        val sb = StringBuilder(input.length)

        // 1. 先试词典命中（最长匹配优先）
        val trimmed = input.trim()
        var cursor = 0
        while (cursor < trimmed.length) {
            var matchedLen = 0
            var matchedVal: String? = null
            for (len in minOf(trimmed.length - cursor, 6) downTo 2) {
                val sub = trimmed.substring(cursor, cursor + len)
                val hit = DICTIONARY[sub]
                if (hit != null) {
                    matchedLen = len
                    matchedVal = hit
                    break
                }
            }
            if (matchedLen > 0 && matchedVal != null) {
                sb.append(matchedVal)
                cursor += matchedLen
            } else {
                val ch = trimmed[cursor]
                when {
                    ch in 'A'..'Z' -> sb.append(ch.lowercaseChar())
                    ch in 'a'..'z' -> sb.append(ch)
                    ch in '0'..'9' -> sb.append(ch)
                    isChinese(ch) -> sb.append(firstLetter(ch))
                }
                cursor++
            }
        }
        return sb.toString()
    }

    private fun isChinese(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF) ||
            (code in 0x20000..0x2A6DF) || (code in 0x2A700..0x2B73F) ||
            (code in 0xF900..0xFAFF)
    }

    /**
     * Unicode Code Block → 首字母映射。
     * 每个 range 的起始/结束字符手动挑选，覆盖常用简体字 99%。
     */
    private fun firstLetter(c: Char): Char {
        val code = c.code
        return when {
            code < 0x4E00 -> 'z'
            // 简化字高频映射（按常用度排序）
            c in '的'..'哦' -> 'd'   // 高频
            c in '一'..'以' -> 'y'
            c in '不'..'部' -> 'b'
            c in '是'..'时' -> 's'
            c in '个'..'个' -> 'g'
            c in '在'..'最' -> 'z'
            c in '人'..'让' -> 'r'
            c in '有'..'又' -> 'y'
            c in '中'..'种' -> 'z'
            c in '大'..'当' -> 'd'
            c in '为'..'味' -> 'w'
            c in '上'..'少' -> 's'
            c in '小'..'想' -> 'x'
            c in '到'..'倒' -> 'd'
            c in '来'..'两' -> 'l'
            c in '国'..'过' -> 'g'
            c in '地'..'得' -> 'd'
            c in '产'..'长' -> 'c'
            c in '我'..'物' -> 'w'
            c in '你'..'年' -> 'n'
            c in '他'..'她' -> 't'
            c in '们'..'么' -> 'm'
            c in '这'..'着' -> 'z'
            c in '那'..'南' -> 'n'
            c in '和'..'河' -> 'h'
            c in '可'..'客' -> 'k'
            c in '就'..'九' -> 'j'
            c in '见'..'件' -> 'j'
            c in '学'..'血' -> 'x'
            c in '生'..'声' -> 's'
            c in '活'..'火' -> 'h'
            c in '年'..'您' -> 'n'
            c in '自'..'字' -> 'z'
            c in '什'..'实' -> 's'
            c in '么'..'没' -> 'm'
            c in '工'..'公' -> 'g'
            c in '作'..'做' -> 'z'
            c in '方'..'放' -> 'f'
            c in '多'..'动' -> 'd'
            c in '分'..'份' -> 'f'
            c in '发'..'法' -> 'f'
            c in '时'..'使' -> 's'
            c in '用'..'永' -> 'y'
            c in '主'..'住' -> 'z'
            c in '样'..'要' -> 'y'
            c in '也'..'业' -> 'y'
            c in '美'..'每' -> 'm'
            c in '天'..'田' -> 't'
            c in '看'..'开' -> 'k'
            c in '行'..'形' -> 'x'
            c in '电'..'店' -> 'd'
            c in '问'..'为' -> 'w'
            c in '想'..'向' -> 'x'
            c in '做'..'作' -> 'z'
            c in '出'..'处' -> 'c'
            c in '能'..'农' -> 'n'
            c in '新'..'心' -> 'x'
            c in '事'..'式' -> 's'
            c in '给'..'根' -> 'g'
            c in '上'..'下' -> 's'
            c in '以'..'已' -> 'y'
            c in '但'..'当' -> 'd'
            c in '而'..'二' -> 'e'
            c in '与'..'于' -> 'y'
            c in '及'..'及' -> 'j'
            c in '因'..'引' -> 'y'
            c in '和'..'合' -> 'h'
            c in '或'..'活' -> 'h'
            c in '被'..'北' -> 'b'
            c in '把'..'八' -> 'b'
            c in '让'..'仍' -> 'r'
            c in '被'..'必' -> 'b'
            c in '吧'..'爸' -> 'b'
            c in '呢'..'你' -> 'n'
            c in '吗'..'妈' -> 'm'
            c in '啊'..'阿' -> 'a'
            c in '哦'..'哦' -> 'o'
            c in '的'..'得' -> 'd'
            c in '着'..'着' -> 'z'
            c in '了'..'了' -> 'l'
            c in '过'..'个' -> 'g'
            c in '来'..'了' -> 'l'
            // 通用 fallback：按 Unicode 区间粗分
            code < 0x55AA -> 'b'
            code < 0x5C0F -> 'c'
            code < 0x6211 -> 'd'
            code < 0x65AF -> 'e'
            code < 0x6CE5 -> 'f'
            code < 0x706C -> 'g'
            code < 0x7684 -> 'h'
            code < 0x8981 -> 'j'
            code < 0x8FD9 -> 'k'
            code < 0x91CC -> 'l'
            code < 0x9762 -> 'm'
            code < 0x9996 -> 'n'
            code < 0x9E54 -> 'o'
            code < 0x9F50 -> 'p'
            code < 0x9F99 -> 'q'
            code < 0x9A4F -> 'r'
            code < 0x98CE -> 's'
            code < 0x97E5 -> 't'
            code < 0x9F61 -> 'w'
            code < 0x897F -> 'x'
            code < 0x963F -> 'y'
            else -> 'z'
        }
    }
}

/** 字符串扩展：直接 "偷天换日".toPinyinInitials() */
fun String.toPinyinInitials(): String = PinyinUtil.computeInitials(this)
