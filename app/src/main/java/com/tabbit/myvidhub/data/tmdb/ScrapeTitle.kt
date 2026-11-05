package com.tabbit.myvidhub.data.tmdb

import android.util.Log

// TMDB 检索用的片名清洗。
//
// TVBox 源站把运营信息直接拼进了片名：「嫁金钗（臻彩）」「无可替代（真彩）」
// 「鬼谷小郡主第五季（140集）」「某某 4K 1.1GB 豆瓣9.6」这类后缀。它们喂给 TMDB 的
// query 基本必挂 —— TMDB 的条目名不带这些，相关度排序一下掉到几十名开外，
// 而我们取的是结果第一条，于是整页刮空。
//
// 只清洗「发给 TMDB 的那一个字符串」：不动库里的 title、不动详情页展示，
// 也不动 FormatProbe 读到的原始文件名（画质徽章那套口径依赖原串）。
//
// 判据顺序按库里 shell 片名的真实词频排（越靠前越常遇）：
// 画质品牌（臻彩/真彩/至臻/帧享/原画）> 集数与连载状态（140集/167集全/无限）>
// 年份 > 体积（1.1GB/39G）> 分辨率（4K/1080P/2160p）> 字幕配音 > 站点话术 > 评分 > 类型标签。
// 反过来「第二季」「动画版」「特别篇」、括号里的英文原名要留着 —— 那些是片名本身的成分。
object ScrapeTitle {

    private const val TAG = "ScrapeDiag"

    /** 括号对：全角圆括号、方头括号，半角圆括号、方括号 */
    private val BRACKET_PAIRS = listOf('（' to '）', '【' to '】', '(' to ')', '[' to ']')

    /** 分季/版本一类是真片名，不清洗 */
    private val KEEP_GROUP = Regex(
        """^(第\s*[0-9一二三四五六七八九十百千]+\s*[季部]\s*\d*|[Ss]eason\s*\d+|[Pp]art\s*\d+|""" +
            """特别篇.*|.*特别篇|动画版|剧场版|TV版|OVA|未删减版|导演剪辑版)$"""
    )

    // ---- 括号内内容算「追加信息」的判据 ----

    // 画质品牌与压制标识：库里最高频的一档
    private val QUALITY = Regex(
        """(?i).*(臻彩|真彩|至臻|帧享|原画|超清|高清版?|高码率?|杜比|视界|音质|""" +
            """max\s*plus|maxplus|vivid|dolby|hdr|sdr|hevc|x26[45]|h\.?26[45]|av1|""" +
            """remux|原盘|蓝光|bd-?rip|web-?dl|web-?rip|amzn|""" +
            """(?<![a-z0-9])(4k|8k|2160p|1440p|1080p|720p|480p|10bit|8bit)(?![a-z0-9])).*"""
    )

    // 体积
    private val SIZE = Regex("""(?i)^\d+(\.\d+)?\s*(gb|mb|tb|g|m|t)$""")

    // 年份 / 年份区间
    private val YEAR = Regex("""^\d{4}(\s*[-~—]\s*\d{4})?\s*年?$""")

    // 集数：`140集`、`167集全`、`全170集`、`001-100集`、`共30集`
    private val EPISODE_COUNT = Regex("""^[全共]?\s*[\d\s.一二三四五六七八九十百千万wW~\-]+\s*集\s*[全量部话期完结更新中]*$""")
    private val SERIAL_STATE = Regex("""^(无限|连载(中)?|更新中|完结(中)?|更完|更新至\d+.*)$""")
    // 卷名 + 集数区间：`【凡人修仙传 1-17】`、`【魔道争锋 22-46】`
    private val ARC_RANGE = Regex("""^.*\s\d+\s*[-~]\s*\d+\s*$""")
    // 组尾带年份：`[主线 2026]`
    private val YEAR_TAIL = Regex(""".*\s\d{4}$""")
    // 加映内容标注：`[附第1部]`
    private val EXTRA_NOTE = Regex("""^附.*""")

    // 字幕 / 配音（单字「国」「粤」会误伤片名，只认成词的）
    private val SUBTITLE = Regex(
        """(?i).*(字幕|内封|简中|繁中|中字|双语|无参|熟肉|生肉|配音|音轨|国语|粤语|闽南语|韩语|日语|英语|泰语).*"""
    )

    // 站点运营话术
    private val OPS = Regex(
        """(?i).*(广告|勿信|谨防|上当|点击换线|换线|卡线|备用|线路|重要勿删|勿删|""" +
            """夸克|阿里云盘|迅雷|网盘|百度|百du|115|\bapp\b|资源|地址|首页|最新|热播|大全集).*"""
    )

    // 评分
    private val RATING = Regex("""(?i).*(豆瓣|评分|imdb|烂番茄|口碑)\s*\d*\.?\d*.*""")

    // 类型标签：`【韩剧】`、`[短剧]`
    private val GENRE_TAG = Regex("""^(国产(剧|电影)?|港剧|台剧|韩剧|美剧|日剧|泰剧|英剧|动漫|动画|综艺|短剧|电影|电视剧|纪录片|港台|大陆|欧美|韩国|日本|泰国|美国|中国)$""")

    // 括号里纯人名 + 「饰」：这类其实来自简介列，真出现在片名里也是角色标注
    private val CAST_NOTE = Regex("""^[\w\s·.\u4e00-\u9fa5]{1,20}\s*饰\s*$""")

    // 裸文件名后缀
    private val FILE_EXT = Regex("""(?i)\.(mp4|mkv|avi|mov|wmv|flv|rmvb|ts|m4v|mkv\.mp4|mp4\.mp4)$""")

    // 不带括号直接挂在尾巴上的画质词与分辨率：`某某 臻彩`、`某某4K`
    private val TRAILING_TOKEN = Regex(
        """(?i)[\s._\-|·]*(臻彩|真彩|至臻|帧享|原画|超清|高码率?|无限更新|清晰版""" +
            """|(?<![a-z0-9])(4k|8k|2160p|1080p|720p)(?![a-z0-9]))[\s._\-|·]*$"""
    )

    /** 单个括号组：true = 运营追加内容，可以整段丢掉 */
    private fun isJunkGroup(inner: String): Boolean {
        val s = inner.trim()
        if (s.isEmpty()) return true
        if (KEEP_GROUP.matches(s)) return false
        // 英文原名/别名（`(US Marshals)`、`(无法阻挡的High Kick)`）留给 TMDB 当线索
        if (s.first().let { it.isLetter() } && s.none { it.code in 0x4E00..0x9FFF } &&
            !QUALITY.matches(s) && !OPS.matches(s) && !SUBTITLE.matches(s) && !RATING.matches(s)
        ) return false
        return QUALITY.matches(s) || SIZE.matches(s) || YEAR.matches(s) || YEAR_TAIL.matches(s) ||
            EPISODE_COUNT.matches(s) || SERIAL_STATE.matches(s) || ARC_RANGE.matches(s) ||
            EXTRA_NOTE.matches(s) ||
            SUBTITLE.matches(s) || OPS.matches(s) || RATING.matches(s) ||
            GENRE_TAG.matches(s) || CAST_NOTE.matches(s)
    }

    /**
     * 清洗一个片名，只用于检索。
     * 清洗后一无剩下（整个标题就是一串括号）时返回原串 —— 挂不上是次要的，
     * 拿空串去查是肯定挂不上。
     */
    fun forSearch(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return raw

        // 逐个括号组从后往前判定并删除；不嵌套，源站没有嵌套写法
        for ((open, close) in BRACKET_PAIRS) {
            var from = 0
            while (true) {
                val l = s.indexOf(open, from)
                if (l < 0) break
                val r = s.indexOf(close, l + 1)
                if (r < 0) break
                if (isJunkGroup(s.substring(l + 1, r))) {
                    s = s.removeRange(l, r + 1)
                } else {
                    // 保留的组不再看，避免后面把整串标题当组删掉
                    from = r + 1
                }
            }
        }

        // 没括号的尾巴：`某某 臻彩`、`某某 4K`、`某某.mp4`、分隔符收尾
        s = FILE_EXT.replace(s, "")
        var prev: String
        do {
            prev = s
            s = TRAILING_TOKEN.replace(s, "")
        } while (s != prev)

        // 括号删完留下的空洞与收尾标点
        s = s.replace(Regex("""\s{2,}"""), " ").trim().trimEnd('-', '，', ',', '、', '：', ':', '·', '.', '_', '|', '丨')

        val clean = s.trim()
        if (clean != raw.trim() && clean.isNotBlank()) {
            Log.i(TAG, "片名清洗 \"${raw.trim()}\" → \"$clean\"")
        }
        return if (clean.isBlank()) raw.trim() else clean
    }
}
