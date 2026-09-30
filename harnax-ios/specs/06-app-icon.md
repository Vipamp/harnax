# Harnax iOS — App 图标规格

范围：iPhone app icon 本体与工程接线。webui 的 logo / favicon、App 内的 brand mark、营销物料都不在本规格内（`TARGETED_DEVICE_FAMILY = 1` 只落在 iPhone，两个 target configuration 各一处，`harnax-ios/Harnax.xcodeproj/project.pbxproj:206`、`:230`）。

---

## 1. 母题与构图

**缰绳卡扣 + 字母 H。** Harnax 是 agent 智能体平台，图形的语义是「把一群 agent 束住、缰在手」：H 的两根竖柱做成两条皮带立柱，横档是一条皮带，正中央穿过一枚实心黄铜卡扣（方孔环）。卡扣是唯一的注意力焦点，也是这个 mark 与「随便一个 H 字母」的区别所在。

艺术处理上的既有约定：

- **不等宽、不纯几何。** 皮带有厚度感和端头收口，立柱边缘有虚线缝线（dashed hairline），卡扣有实心高光与落在皮带上的投影。整体构图带轻微不对称，避免机械感。
- **满幅方形（full bleed）。** 1024×1024 的整张画布都是内容，**不预裁圆角**——iOS 自己套 squircle mask，预先裁圆角会在蒙版下露出边框。
- **符号有安全边距。** 实测：把象牙白 + 黄铜这类亮部像素按亮度阈值扫出来，包围盒是 511×593，即画布的 **0.50 × 0.58**，四边留白左 257 / 上 205 / 右 256 / 下 226 px，符号实占画布 14.0%。Apple 要求关键内容落在内圈约 80% 里，这一版留得比那条线宽得多。
- **无 alpha 通道。** 交付 PNG 必须不透明（`sips -g hasAlpha` 返回 `no`），带 alpha 的图标在 App Store 校验里被拒。

## 2. 配色：pine / brass（墨绿 · 象牙白 · 黄铜）

图标不是单色底 + 单色符号，而是一张有亮度的画。所有色由**亮度映射**确定：取母版每个像素的加权亮度，逐像素查这张表换色，形状与明暗关系原样保留。停点如下：

| 亮度档 | 颜色 | 落点 |
|---|---|---|
| 0.00 | `#0B1712` | 最深阴影、皮带底面、卡扣投影 |
| 0.22 | `#14352A` | 背景主体（墨绿） |
| 0.40 | `#1E4D3C` | 背景亮部、皮带的受光面 |
| 0.58 | `#C9A227` | 黄铜——卡扣销针与扣环 |
| 0.78 | `#E8DCC0` | 象牙白与黄铜之间的过渡 |
| 1.00 | `#F7F3E9` | 符号最亮——H 主体 |

对比度（WCAG 相对亮度比，实测）：

- 象牙白符号 `#F7F3E9` 对背景主体 `#14352A` = **12.05**；对最深阴影 `#0B1712` = 16.54；对亮部 `#1E4D3C` = 8.69。符号在任何一档背景上都读得清。
- 黄铜 `#C9A227` 对墨绿 = 5.52，对最深阴影 = 7.58。黄铜只用在细件上，靠的是色相差而不是亮度差。
- 背景主体 `#14352A` 对浅壁纸 `#F2F4F7` = 12.12——在浅色桌面上墨绿自己就是图标的边界。
- 同一档墨绿对深壁纸 `#0E1014` 只有 1.43，缩样实测确认：深色桌面上图标的轮廓由内部的象牙白 H 撑开，底色与壁纸几乎融在一起，符号仍然完整可读。

**这套颜色不进 App 的主题色板。** `Palette.brand`（light `0x2A5FE8`，`harnax-ios/Sources/HarnaxKit/Theme/Palette.swift:45`；dark `0x5C8BFF`，`harnax-ios/Sources/HarnaxKit/Theme/Palette.swift:63`）继续管 App 内的品牌色，图标配色与它互不影响、互不引用。

## 3. 交付物与工程接线

| 项 | 位置 |
|---|---|
| 成品图标（构建输入） | `harnax-ios/App/Assets.xcassets/AppIcon.appiconset/icon-1024.png`（1024×1024、sRGB、无 alpha） |
| iconset 描述 | `harnax-ios/App/Assets.xcassets/AppIcon.appiconset/Contents.json` |
| 目录信封 | `harnax-ios/App/Assets.xcassets/Contents.json` |
| 形状母版（不参与构建） | `harnax-ios/brand/master-flat-tension.png` |
| 资产名开关 | `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;`，target 级 Debug / Release 各一行 |

**单尺寸 universal，不手写多套。** `Contents.json` 只有一条 image：`idiom: universal` + `platform: ios` + `size: 1024x1024`，这是本工程（部署目标 iOS 17.0，`harnax-ios/Harnax.xcodeproj/project.pbxproj:158`、`:177`）唯一需要提供的图。iOS 18 起的 Dark / Tinted 两种主屏外观由系统从这一个尺寸派生，不需要额外文件。

**工程自动收录。** `App/` 是 `PBXFileSystemSynchronizedRootGroup`（`harnax-ios/Harnax.xcodeproj/project.pbxproj:21` 的 `path = App`，被 `:70` 的 `fileSystemSynchronizedGroups` 挂上），xcassets 放进 `App/` 就进构建，不用往 pbxproj 里加任何 file reference。需要手工加的只有上表的资产名开关那一行（现落在 `:189` 与 `:213`）。

**产物级验收判据**（两条都要过）：

```
<derivedData>/Build/Products/Debug-iphoneos/Harnax.app/Assets.car   存在
xcrun assetutil --info .../Harnax.app/Assets.car | grep -i appicon   有 AppIcon 条目
```

## 4. 小尺寸实测行为

29 / 60 / 120 三档真尺寸降采样，浅壁纸 `#F2F4F7` 与深壁纸 `#0E1014` 各一排，外加 ×3 最近邻放大：

- **120**：全部细节保留——三层绿底渐变、黄铜销针、皮带缝线。
- **60**：H 轮廓与卡扣方孔干净；销针退化成一段短粗的黄铜点，仍读得出金属。
- **29**：H 与卡扣这两个主形存活；缝线完全消失，销针只剩约 2px。

**不为 29 单独出精修版。** 判据：主屏实际显示 60pt 见方（@2x 即 120 像素、@3x 即 180 像素），29pt 那一档落在设置列表、聚焦模式这类次要位置，本来就不承载细节。如果将来非做不可，改动方向是「撤掉全部缝线 + 把销针加粗到 3px 以上」，而不是重新画。

## 5. 再生成管线

母版是一张生图产物，形状源文件入库在 `harnax-ios/brand/master-flat-tension.png`；交付的配色由 §2 的亮度映射脚本重刷得到，结果就是 `AppIcon.appiconset/icon-1024.png`。**用映射而不是重新生成，是为了换色时形状逐像素不变**——重新出图必然带来构图漂移。

映射脚本本身不入库，重跑需要的全部事实就是 §2 那张停点表：取每像素 gamma 编码亮度 `l = 0.2126R + 0.7152G + 0.0722B`，按相邻停点线性插值取色，输出 8-bit RGBA 再展平为无 alpha。

## 6. 明确不在范围内

- webui 的 logo / favicon / 登录页品牌图
- App 内界面元素（导航栏、空态插画、启动图）——仍走 `Palette`
- iPad / macOS 专属图标
- 三套主屏外观的手工版本（Dark / Tinted 交系统派生）
