package com.tabbit.myvidhub.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.components.PosterArt
import com.tabbit.myvidhub.ui.components.PosterFallback
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.TvText

/**
 * 电视端专用组件（十英尺界面）。
 *
 * 与手机端组件完全分开实现，理由很实在：
 *   · 电视没有触摸，所有可交互元素必须是「可聚焦 + 有明显焦点态」，尺寸要按观看距离放大；
 *   · 手机端组件靠涟漪和阴影表达状态，这些在电视上隔着三米根本看不见，必须用白边和放大。
 *
 * 字号与文字颜色都不在这里写死：
 *   · 字号取 MaterialTheme.typography 的档位，电视端实际大小由 Theme 的 TvTypography 决定；
 *   · 文字颜色取 TvText 的层级（Title / Section / Body / Meta / Muted / Hairline），
 *     整屏只用白→灰一条轴拉开层级，品牌色只留给按钮和进度条这类「可操作 / 进行中」的元素。
 *
 * 聚焦的元件一律先 .zIndex(1f) 再 .graphicsLayer 放大：
 * 抬高绘制层级，放大后的白边才不会被同排的兄弟节点压住。
 *
 * 焦点路由的硬约束见本文件下面的「焦点路由规范 R1-R8」；
 * 新增可聚焦元件一律走 tvFocusRoute / tvMoveFocus / tvClaimFocusOnEnter，不要手拼。
 */

/* ========================================================
   焦点路由规范（TV 端硬约束，R1-R8）
   ========================================================
   这八条是从 8 个真机故障里归纳出来的，评审新页面就按它们打回：

   R1 显式路由优先。同一功能区里每颗可聚焦元件的 up/down 必须显式给 FocusRequester；
      默认几何搜索只允许用于同一行内的左右移动。跨功能区（顶栏 ↔ Hero、控制条 ↔ 选集网格、
      Header ↔ 选集）禁止依赖几何搜索 —— 它只在「当前视口内的可聚焦节点」里找，
      可达性被 Hero 高度这类无关参数决定，用户看到的就是「按了没反应」。
   R2 按键拦截挂在元件自己身上（modifier 级 onPreviewKeyEvent）。
      禁止「顶层容器 + 页面级布尔位」表示焦点在哪颗按钮上：一个布尔覆盖不了一片按钮，
      结果就是「播放键的 ↑ 有效，旁边详情键的 ↑ 无效」。状态位只用于视觉高亮。
   R3 不许用状态位校验跳转结果。成败只看 requestFocus 是否抛异常，跳完即止；
      降级顺序固定：同区备用锚 → moveFocus(方向) → 什么都不做。
      「跳完读状态位、读不到再跳一次」会把已经落对的焦点拽走。
   R4 焦点离开哪一层就留在哪一层。在控制条上完成的动作（换源/换季/翻页）本来就站在控制条上，
      所以数据回填后「什么都不做」才是对的；只有用户主动按下才进网格。
      「内容一变就抢焦回网格首行」这类无来源的抢焦一律禁止 —— 按钮还在组合里没消失，
      焦点不会掉，加一次恢复反而是把高亮从刚按下的按钮拽走。
   R5 弹窗与列表的默认焦点 = 当前选中项，不是第 0 项；没有选中项才回退第 0。
   R6 不允许焦点死区。任何占视口高 >40% 的不可聚焦区域必须自带至少一颗可聚焦 CTA，
      首屏必须存在一条「能往下走」的路。空态比满态更容易犯这条。
   R7 可见即恢复。Activity resume（进出播放器）与上级页 pop 回来时，把焦点交回该页声明的
      入口锚点；恢复焦点要与恢复构图（滚动位置）成对做，只靠 bring-into-view 的最小滚动
      回不到首屏大图。1.6.8 的 FocusManager 既不暴露 hasFocus 也不暴露 currentFocus，
      查不到「当前有没有焦点」，所以只能统一重抢一次；入口锚点已经持有焦点时重复请求是空操作。
   R8 引用别的子树或条件分支里的 requester 前，先确认它这一帧真的挂载了。
      淡出即摘除的元件不能当路由目标；跨分支 requestFocus 一律走 tvMoveFocus（内部已兜异常）。
      1.6.8 的 requestFocus() 返回 Unit，成功与否读不到，所以入口抢焦要补抢一次（见 tvClaimFocusOnEnter）。
   ======================================================== */

/**
 * R1：声明一颗可聚焦元件的上/下落点。
 * 传 null 表示这一向不设规则，交给默认几何搜索 —— 那只允许用于同一行内的左右移动。
 */
fun Modifier.tvFocusRoute(
    up: FocusRequester? = null,
    down: FocusRequester? = null,
): Modifier = focusProperties {
    if (up != null) this.up = up
    if (down != null) this.down = down
}

/**
 * R3：跳焦点的唯一入口。
 * 按顺序试各个落点，[FocusRequester.requestFocus] 没抛异常就算到位并立即返回；
 * 全部没挂载时才按 [direction] 降级到默认几何搜索。绝不读 onFocusChanged 的状态位再校验一遍。
 */
fun tvMoveFocus(
    vararg targets: FocusRequester,
    focusManager: FocusManager? = null,
    direction: FocusDirection? = null,
): Boolean {
    for (target in targets) {
        if (runCatching { target.requestFocus() }.isSuccess) return true
    }
    if (focusManager != null && direction != null) {
        runCatching { focusManager.moveFocus(direction) }
    }
    return false
}

/** 每次界面重新回到前台 +1（进播放器再退出来也会走一次 ON_RESUME）。 */
@Composable
fun tvResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}

/**
 * R7：入口抢焦。[tick] 每次变化（首次组合 + 每次界面回到前台）都把焦点交给第一个已挂载的落点。
 *
 * 为什么不在这里判断「当前是不是已经有焦点」：1.6.8 的 FocusManager 不暴露焦点查询，
 * 而跨 Activity 回来时（进播放器再退出）恰恰是「焦点状态还在、视觉选中框没了」和
 * 「焦点彻底丢了」两种情况混在一起，只能统一重抢一次来保证一定看得见选中态。
 * 代价是从播放器回来会把焦点放回该页入口锚点（而不是你离开时那颗卡片），
 * 这比「按了没反应」可预期；以后要做「记住离开位置」，把锚点表换成按层级记录的表即可。
 */
@Composable
fun tvClaimFocusOnEnter(
    tick: Any,
    vararg anchors: FocusRequester,
    scrollToTop: suspend () -> Unit = {},
    settleMillis: Long = 0L,
) {
    tvClaimFocusOnEnter(tick, { anchors.toList() }, scrollToTop, settleMillis)
}

/**
 * [tvClaimFocusOnEnter] 的按需取锚点版本：落点由子树里的卡片在组合/拿焦点时才交出来，
 * 页面组合那一刻还读不到，所以这里传的是取值函数，效果启动时才读。
 */
@Composable
fun tvClaimFocusOnEnter(
    tick: Any,
    anchorsProvider: () -> List<FocusRequester>,
    scrollToTop: suspend () -> Unit = {},
    settleMillis: Long = 0L,
) {
    LaunchedEffect(tick) {
        runCatching { scrollToTop() }
        if (settleMillis > 0) delay(settleMillis)
        // 等一帧：requester 要等节点组合完成才挂得上
        withFrameNanos { }
        tvMoveFocus(*anchorsProvider().toTypedArray())
        // 再补一次：路由淡入淡出、刚从播放器 resume 回来时，锚点节点这一帧可能还没挂上，
        // 而 requestFocus 是否成功在 1.6.8 里读不到返回值。对已经持有焦点的节点重复请求是空操作，
        // 所以补一次只会「把没抢到的焦点抢上」，不会把用户已经走到位的焦点拽回来。
        delay(120)
        tvMoveFocus(*anchorsProvider().toTypedArray())
    }
}

/**
 * 电视端按钮的尺寸档位。
 *
 * 分档只动内边距，字号一律 titleMedium —— 这是 Netflix 的规矩：可操作元件靠
 * 「实心 / 幽灵 + 图标」分主次，不靠字号和尺寸分主次。一旦让次要按钮掉到 label 字号，
 * 就会出现「分区标题旁边一颗大胶囊、Hero 上一对小胶囊」的倒挂，两边都显得不对。
 *
 * 三档算上文字行高（23dp）大致是 35 / 43 / 49dp：
 *   · [Sm] 顶栏入口条（横向预算最紧）；
 *   · [Md] 页内次操作、分区「查看全部」、选集控制条；
 *   · [Lg] Hero 与详情页的主操作 —— 全页最大的可点元素。
 */
enum class TvButtonSize { Sm, Md, Lg }

private fun tvButtonPadding(size: TvButtonSize): PaddingValues = when (size) {
    TvButtonSize.Sm -> PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    TvButtonSize.Md -> PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    TvButtonSize.Lg -> PaddingValues(horizontal = 26.dp, vertical = 13.dp)
}

/** 电视上的主按钮：品牌色实底（主操作）或品牌色淡底（次操作），聚焦时加粗白边并提到更亮的一档 */
@Composable
fun TvPillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    size: TvButtonSize = TvButtonSize.Md,
    onClick: () -> Unit
) {
    // 焦点态统一由 interactionSource 驱动：clickable 与 focusable 共用同一个 source。
    // 之前用 Modifier.onFocusChanged 里的 isFocused 判断，在「遥控器跨子树跳焦点」
    // （Hero 播放键 → 顶栏同步键）时会出现逻辑焦点已移走、画面高亮却停在原按钮上的错位。
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "pill")
    val brand = AppColors.Brand
    val brandSoft = AppColors.BrandSoft

    Box(
        modifier
            // 放大后要把绘制层级抬起来，否则同排右侧的兄弟节点会压在白边上面
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(
                // 填充整颗跟着主题色走，聚焦态不再刷纯白：
                // 十英尺下焦点是常态（进页第一个落点就是主按钮），
                // 白填充等于把主题色在屏幕上唯一的大色块抹掉，看上去就是「按钮没适配主题」。
                // 层级靠「浅色实底 → 品牌色 → 品牌色淡底」三档加白边拉开，不再靠白/黑两套色相。
                when {
                    primary && focused -> brandSoft
                    primary -> brand
                    focused -> brand.copy(alpha = 0.46f)
                    else -> brand.copy(alpha = 0.18f)
                }
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interaction)
            .padding(tvButtonPadding(size))
    ) {
        Text(
            text,
            color = when {
                // 主按钮聚焦压在浅色上，黑字最稳；其余态都在深色上，用白字
                primary && focused -> Color.Black
                size == TvButtonSize.Sm -> TvText.Body
                else -> TvText.Title
            },
            // 三档共用同一个字号，层级交给填充与高度
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1
        )
    }
}

/**
 * 播放主按钮：在 TvPillButton 基础上左侧加一个播放图标，
 * 视觉上和截图的「▶ 开始观看 S05E01」一致。
 */
@Composable
fun TvPlayButton(
    text: String,
    modifier: Modifier = Modifier,
    size: TvButtonSize = TvButtonSize.Md,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "play_pill")

    Row(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) AppColors.BrandSoft else AppColors.Brand)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(tvButtonPadding(size)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = if (focused) Color.Black else Color.White,
            // 图标跟按钮同档缩放，避免出现「小胶囊里塞个标准大的三角」
            modifier = Modifier.size(
                when (size) {
                    TvButtonSize.Sm -> 18.dp
                    TvButtonSize.Md -> 22.dp
                    TvButtonSize.Lg -> 24.dp
                }
            )
        )
        Text(
            text,
            color = if (focused) Color.Black else TvText.Title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1
        )
    }
}

/**
 * 圆形图标按钮：电视端的次操作（收藏等）用图标而不是长条文字胶囊。
 * 圆形视觉更轻、横向只占一个按钮宽度，把省下来的空间留给海报；
 * 聚焦时放大并加白边，符合十英尺界面的可辨识要求。
 */
@Composable
fun TvIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    contentDescription: String? = null,
    /**
     * 直径。放进标题行时贴着大标题站，46dp 会显得比字还抢眼，
     * 所以允许调用方收小一档；操作区里的默认值仍是 46dp。
     */
    sizeDp: Dp = 46.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(140), label = "tv_icon")

    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(sizeDp)
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> Color.White
                    active -> Color.White.copy(alpha = 0.22f)
                    else -> Color.White.copy(alpha = 0.12f)
                }
            )
            .then(
                if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(50))
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(50))
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (focused) Color.Black else TvText.Title,
            // 图标随按钮等比缩，收小后不会出现「大图标塞在小圆里」
            modifier = Modifier.size(sizeDp * 0.48f)
        )
    }
}

/**
 * 电视端海报卡：2:3 竖版，比手机大一圈，聚焦时放大并加白色描边。
 * 有播放进度的条目底部带一条进度线，方便一眼看出「这片看了一半」。
 */
@Composable
fun TvPosterCard(
    item: MediaEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Prefs.posterSize.tvDp.dp,
    /**
     * 网格模式：铺满格子宽度并按 2:3 自适应高度。
     * 固定 [width] 原本只适合首页横排，放进 LazyVerticalGrid 会溢出格子或留白，
     * 所以网格页（分类/查看全部/搜索）统一用这个模式。
     */
    fillCell: Boolean = false,
    /**
     * 这一行/列的第一张卡：放大时把左缘补回原位。
     *
     * 默认绕中心放大，首卡会往左吃掉 5% 卡宽的左边距，于是它比下面各行、
     * 各列的首卡都更靠左 —— 焦点一走到行首就「越界、对不齐」。
     */
    anchorStart: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // 不用 by 解包：放大动画每帧都变，解包等于每帧重组整张卡（含图片和文字）。
    // 保留 State 在 graphicsLayer 的 draw phase 读，动画只重绘这一层。
    val scale = animateFloatAsState(if (focused) 1.10f else 1f, tween(150), label = "card")
    val cardShape = remember { RoundedCornerShape(10.dp) }

    val progress = if (item.durationMs > 0L) {
        (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier
            .then(if (fillCell) Modifier.fillMaxWidth() else Modifier.width(width))
            // 放大 10% 后要抬高绘制层级，否则描边会被同行后面的卡片压住
            .zIndex(if (focused) 1f else 0f)
            // 缩放和圆角裁剪合成同一个 layer：原来 graphicsLayer + clip 是两个 layer，
            // 滚动时每张卡都多一层离屏缓冲。
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                // 首卡往右扩：绕中心放大 10% 会把左缘往左推掉 5% 卡宽，于是行首卡比
                // 下面各行更靠左，左轴第二条就出现了。
                // 这里用 translationX 精确补回这半个增量，而不是改 transformOrigin ——
                // 平移是 layer 最基础的一项，任何 API 档位都确定生效；真机上上一版改
                // transformOrigin 后仍报「超出」，换成补偿就不依赖那条属性的实现了。
                if (anchorStart) {
                    translationX = (scale.value - 1f) * size.width / 2f
                }
                shape = cardShape
                clip = true
            }
            .background(AppColors.Surface)
            .then(if (focused) Modifier.border(2.5.dp, Color.White, cardShape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(0.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fillCell) Modifier.aspectRatio(2f / 3f) else Modifier.height(width * 1.5f))
                .background(AppColors.SurfaceHi)
        ) {
            // 无海报（TVBox 的 vod_pic 常是空串）或图是 404 死链时，用按片名哈希的渐变封面顶上，
            // 不再出现一整排空灰块。
            PosterArt(
                model = item.posterUrl.ifBlank { item.backdropUrl },
                contentDescription = item.title
            ) {
                PosterFallback(
                    title = item.title,
                    subtitle = posterMetaLine(item),
                    titleFontSize = MaterialTheme.typography.titleMedium.fontSize,
                    subtitleFontSize = MaterialTheme.typography.bodySmall.fontSize
                )
            }
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(AppColors.Brand)
                    )
                }
            }
        }
        Text(
            item.title,
            // 卡片标题不靠粗体压海报，靠 88% 白 + Medium 保持和正文同族
            color = TvText.Section,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
        )
    }
}

/**
 * 兜底封面上那行小字：年份 + 首个分类标签。
 * TVBox 的 vod_year 常常是空或 "0000"，取不到就只剩分类，两个都没有就返回空串不占位。
 */
private fun posterMetaLine(item: MediaEntity): String {
    val genre = item.genres.split(TvGenreSplit).firstOrNull { it.isNotBlank() }?.trim()
    return listOfNotNull(
        item.year.takeIf { it > 0 }?.toString(),
        genre?.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
}

private val TvGenreSplit = Regex("[,，、/\\s]+")

/** 分区标题：电视上字要大，右侧可挂「更多」 */
@Composable
fun TvSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    // 默认走页面左轴；详情页等信息列更宽的区域由调用方覆写。
    // 上下留白从 22/10 收到 13/6：首页一屏最多叠 7 个分类行，原来单行的间距合计 52dp，
    // 七行就是半屏多的纯空白，标题和它下面那排海报被推得像是两个分区。
    // 行内左右仍保持页面左轴不动，只收纵向。
    contentPadding: PaddingValues = PaddingValues(
        start = TvPagePadding, end = TvPagePadding, top = 13.dp, bottom = 6.dp
    ),
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            // 分区标题比 Hero 低一档：titleLarge + Section 级白，靠字号分层而不是靠粗体
            color = TvText.Section,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** 电视端的「更多」入口：和分区标题同排，取中档 —— 比它旁边的 20sp 标题略小，不抢字 */
@Composable
fun TvMoreButton(onClick: () -> Unit) {
    TvPillButton(text = "查看全部", primary = false, size = TvButtonSize.Md, onClick = onClick)
}

/**
 * 功能菜单里的单行选项：可聚焦，聚焦时反白高亮。
 * 来源页的操作面板和首页「继续观看」长按菜单共用这一种行样式，
 * 菜单类弹窗不要再另造一套按钮视觉。
 */
@Composable
fun TvMenuRow(
    text: String,
    hint: String? = null,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Color.White
                    danger -> Color(0xFFE5484D).copy(alpha = 0.10f)
                    else -> AppColors.SurfaceHi.copy(alpha = 0.35f)
                }
            )
            .then(
                if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp))
                else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = when {
                    focused && danger -> Color(0xFFE5484D)
                    focused -> Color.Black
                    danger -> Color(0xFFFF8A65)
                    else -> TvText.Title
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (focused) Color.Black.copy(alpha = 0.6f) else TvText.Meta
                )
            }
        }
    }
}

/** 空状态提示 —— 主文 headlineSmall，下面补一条更弱的 hint；可选挂一个可聚焦操作（如重试） */
@Composable
fun TvEmptyState(
    text: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            color = TvText.Section,
            style = MaterialTheme.typography.headlineSmall
        )
        if (hint != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = hint,
                // hint 比主文低两档，别抢注意力
                color = TvText.Meta,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/**
 * TV 端元信息行：年份 · 类型 · 时长，中间用圆点分隔。
 * 评分不在这里显示 —— 海报背景上已带 IMDb 分，重复出现会打架。
 * 权重介于标题与徽章之间：bodyMedium + Meta 级白。
 */
@Composable
fun TvMetaRow(item: MediaEntity, modifier: Modifier = Modifier) {
    val bits = buildList {
        if (item.year > 0) add(item.year.toString())
        if (item.genres.isNotBlank()) add(item.genres.split(",", "/", " ", "，").first().trim())
        if (item.durationMs > 0) add("${item.durationMs / 60_000} 分钟")
    }.filter { it.isNotBlank() }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        bits.forEachIndexed { index, s ->
            if (index > 0) {
                Text(
                    text = "  ·  ",
                    color = TvText.Hairline,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = s,
                color = TvText.Meta,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** 电视端返回按钮（左上角），可聚焦 */
@Composable
fun TvBackButton(onBack: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(start = TvPagePadding, top = 20.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else Color.Black.copy(alpha = 0.45f))
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack
            )
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            "← 返回",
            // 返回按钮用 titleSmall + Body 级白：够用但不抢焦点
            color = if (focused) Color.Black else TvText.Body,
            style = MaterialTheme.typography.titleSmall
        )
    }
}

/**
 * 页面左右边距：电视端所有页面共用这一条左轴。
 *
 * 取 40dp（1080p 电视上约等于屏宽的 4~5%），和 Netflix 电视端的页面留白同一量级：
 * 元素不贴屏边，屏幕不被塞满，聚焦描边也天然有处可放。
 * Hero 文案、顶栏、行标题、海报行、网格页都从这里取值，
 * 任何一处单独写死就会立刻出现第二条左轴。
 */
internal val TvPagePadding = 40.dp

/**
 * 电视端网格列数：跟随设置页「缩略图大小」。
 * 所有 TV 网格页（分类 / 查看全部 / 搜索 / 来源）都取这个值，避免各处各写一套。
 */
fun tvGridColumns(): Int = when (Prefs.posterSize) {
    Prefs.PosterSize.SMALL -> 8
    Prefs.PosterSize.MEDIUM -> 6
    Prefs.PosterSize.LARGE -> 4
}

/** 电视端可聚焦的小尺寸标签（题材筛选等） */
@Composable
fun TvFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> AppColors.Brand
                    else -> AppColors.SurfaceHi
                }
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(20.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            color = when {
                focused -> Color.Black
                selected -> TvText.Title
                else -> TvText.Body
            },
            // 徽章级：labelMedium，比正文轻一档
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}
