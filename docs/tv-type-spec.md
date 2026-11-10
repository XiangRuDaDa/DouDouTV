# TV 端版式规范（Netflix 布局）

适用范围：TV 端只有 Netflix 版一套在渲染。

**经典版 CLASSIC 已冻结：不改、不删、不再讨论。** 界面切换入口已下线，`Prefs.tvLayout` 的 getter 恒返回 `NETFLIX`（`core/Prefs.kt:50`），`TvApp.kt:208` 的 `nfLayout` 因此恒为 true，classic 分支（`ui/tv/TvHomeScreen.kt` 等）源码仍在、仍被编译，但**运行期不可达**，电视上永远看不到。存储键与枚举保留只为兼容老数据。

由此得到两条推论，本规范全程按此执行：

- 改根主题表（`Theme.kt` 的 `TvTypography`）**没有任何真机观感风险** —— classic 不渲染，改了也看不到。
- classic 源码保持原样。不要以「清理死代码」为名删改它，那属于未获批准的额外动作。

状态：**规范，未实施**。本文不改任何 `.kt`，落地前所有页面维持现状。

---

## 一、字号档位（有效值）

电视端的真实字阶是两张表合成后的结果：`ui/theme/Theme.kt` 的 `TvTypography` 定基表，`ui/tv/TvApp.kt` 在根上覆盖 label/body 小字四档。下表是**覆盖后的最终值**，实现时以它为准。

| 档位 | 字号 | 行高 | 字距 | 定稿字重 | 用途 | 基表值 → 有效值 |
|---|---|---|---|---|---|---|
| displayLarge | 44sp | 52sp | −0.4sp* | SemiBold | Hero 片名 | 44 不变 |
| displayMedium | 34sp | 42sp | −0.3sp | SemiBold | 预留（当前零调用） | 34 不变 |
| displaySmall | 28sp | 36sp | 0 | SemiBold | 弹层大标题 | 28 不变 |
| headlineMedium | 24sp | 31sp | 0 | SemiBold | 抽屉标题 | 24 不变 |
| headlineSmall | 21sp | 27sp | 0 | Medium | 页面级标题 | 21 不变 |
| titleLarge | 20sp | 26sp | 0 | **SemiBold** | 分区标题 | 20 不变 |
| titleMedium | 17sp | 23sp | 0 | Medium | 卡片标题（焦点态） | 17 不变 |
| titleSmall | 15sp | 20sp | 0 | Medium | 行内次级标签 | 15 不变 |
| bodyLarge | 17sp | 25sp | 0 | Normal | 简介正文 | 17 不变 |
| bodyMedium | 15sp | 22sp | 0 | Normal | 元信息、卡标题当前值 | 15 不变 |
| bodySmall | 14sp | 20sp | 0 | Normal | 小正文 | 13 → **14** |
| labelLarge | 16sp | 22sp | 0.2sp | Medium | 按钮与强调文字 | 15 → **16** |
| labelMedium | 15sp | 20sp | 0.2sp | Medium | 徽章、标签 | 13 → **15** |
| labelSmall | 14sp | 18sp | 0.5sp | Medium | 计数、时间戳 | 12 → **14** |
| headlineLarge | **未定义** | — | — | — | 见「三、死档」 | 落到 M3 默认 57sp/Regular |

\* 负字距见「四、两条待决」。

**小字下限：14sp。** `Theme.kt:178` 那句「最小的一档也保住 12sp」作废，以 `TvApp.kt:213` 的 14sp 为唯一下限；注释要一起改，否则下一个人还是照 12sp 写。

**台阶现状（已确认保留，不重排）：** 20sp 以下是 20 / 17 / 16 / 15 / 14，相邻差 1.05–1.07 倍，三米外单看字号分辨不出来。这一带的层级**由字重 + 行高 + 色阶承担**，档位分工如下，不允许再用「同一字号不同字重」去临时造台阶：

| 有效字号 | 允许的字重组合 | 对应档位 |
|---|---|---|
| 20sp | SemiBold | titleLarge |
| 17sp | Medium / Normal | titleMedium（可聚焦卡） / bodyLarge（正文） |
| 16sp | Medium | labelLarge（按钮） |
| 15sp | Medium / Normal | titleSmall、labelMedium（标签徽章） / bodyMedium（元信息、卡标题） |
| 14sp | Medium / Normal | labelSmall（计数，行高 18 + 字距 0.5） / bodySmall（小正文，行高 20） |

---

## 二、字重归属（本规范的核心条款）

现状：全站 270 处引用档位，其中 **46 处在拿到档位后又就地写了一遍 `fontWeight`**；`nf/` 目录内 6 处。同一档位在不同页面的字重由调用点各自决定，没有任何一处统一。典型分裂是分区标题——经典版 `TvComponents.kt:768` 不加字重（档位 Medium），Netflix 版 `NfComponents.kt:624` 就地压 SemiBold。（这一处分叉现在只存在于源码里，classic 不渲染，电视上看不出来；但它恰恰说明字重确实没有归属。）

条款：

1. **字重由档位定义，调用点禁止写 `fontWeight`。** 需要更重的一档就新建档位，不在调用点覆盖。
2. `titleLarge` 定稿 SemiBold，写进 `Theme.kt` 的 `TvTypography`。classic 已不渲染（见开头「适用范围」），改基表没有观感风险，**不需要**为它单独开一套 typography —— 那会多出一张长期更难收口的表。
3. 落地顺序建议：先把 `nf/` 目录 6 处就地字重删掉、把值上收到档位表；`TvSourcesScreen.kt` 11 处、`SourcesScreen.kt` 4 处属手机端/来源页，不在 NF 范围，单列后做。

---

## 三、死档与越线小字（实现时一并处理）

| 位置 | 问题 | 处理 |
|---|---|---|
| `TvComponents.kt:710-711` | `headlineLarge` 在两张表里都没定义，取到的是 Material3 默认 **57sp/Regular**。当前无调用点（`compact` 参数没人传），所以不显示；注释写「文字再收一档」，实际从 44sp 往上跳到 57sp | 要么按意图改成 `displayMedium`（34sp）并删掉这行注释，要么给 `headlineLarge` 补定义；**别保留未定义档位** |
| `Theme.kt:188-192` | `displayMedium` 定义了但全站零调用 | 与上一条绑定：若 compact 改走 34sp，它就有了调用点；否则删除 |
| `FormatBadges.kt:157` | 9sp Black 的 "VISION" | 列入**标识例外白名单**：它是 Dolby 图形的组成部分，不受 14sp 下限约束，但不得被抄去当正文 |
| `Theme.kt:165` | 手机端 `bodyMedium = 13.5sp`，半整数在 px 取整时不可控 | 本轮范围外（手机端），记为待归位为 13 或 14 |

---

## 四、两条待决（规范不代答）

1. **中文负字距。** `displayLarge` 带 `letterSpacing = −0.4sp`。负字距是拉丁大标题的手法，中文压负后字与字咬在一起，读起来廉价而不是紧凑。Netflix 中文片名不做负字距。建议对中文正文与中文标题取消负字距，仅保留在纯拉丁场景。
2. **两端同一角色不同字重。** 手机端 display 档用 Bold（`Theme.kt:156-158`），电视端用 SemiBold（`Theme.kt:182`）。不违反任何规则，但「两端是一个产品」的口径要定：要么手机 display 收到 SemiBold，要么明确两端就该不同。

---

## 五、间距模数（仅 NF 目录）

现状：`ui/tv/nf/*.kt` 里出现 18 种 padding 字面量 —— 2 / 4 / 5 / 6 / 8 / 10 / 11 / 12 / 13 / 14 / 16 / 18 / 20 / 22 / 24 / 26 / 28 / 30 / 40。相邻的 `NfCardGap = 16`、`NfRowVerticalPadding = 14`、`NfGridTopPadding = 18` 不是同一模数，11 / 13 / 26 / 30 属于肉眼看不出、但让纵向节奏一直对不齐的那类值。「页面松散」就是这么攒出来的。

模数取 **4**，允许档：`4 / 6 / 8 / 12 / 16 / 20 / 24 / 32 / 40`（6 保留给描边与徽章内间距）。

归位表按既有决策方向走 —— 首页分类行间距是刻意收紧过的（22/10 → 13/6 → 8/4，见 `TvComponents.kt:747-752`），所以归位一律**往紧的方向靠**，不许借归位之名把纵向重新撑开：

| 现值 | 归位 | 说明 |
|---|---|---|
| 5dp | 4dp | |
| 10dp、11dp、13dp | 12dp | |
| 14dp（`NfRowVerticalPadding`） | 12dp | 收紧，符合上面那条决策 |
| 18dp（`NfGridTopPadding`） | 16dp | 收紧 |
| 22dp | 20dp | |
| 26dp | 24dp | |
| 28dp、30dp | 32dp | |
| 8 / 12 / 16 / 20 / 24 / 40 | 不动 | 已在网格内 |

**必须真机确认的两处**：14→12 会让每行少 4dp 纵向留白，一屏 7 行合计少 56dp，可能多露半行海报；18→16 影响网格首行与顶栏的接缝。这两条不属于「等值归位」，改完要在电视上看，不看不能算完成。

---

## 六、落地时的验收口径

规范不动代码，所以本身零回归。实现轮开工时按这四条自查：

1. `nf/` 目录内 `fontWeight` 就地覆盖计数应为 **0**（当前 6）。
2. 电视端出现任何 `<14sp` 的中文文本即为违规，白名单仅 Dolby 标识。
3. `ui/tv/nf/**` 内 padding 字面量取值应全部落在模数档内（当前 18 种 → 目标 ≤ 8 种）。
4. TV 端所有分区标题的字号必须同为 20sp、字重同为 SemiBold，且实现上不得出现第二处 `fontWeight` 覆盖。
