# Khandaq Messenger Brand Identity & Production Asset Pack

**Author:** Manus AI  
**Project:** Khandaq Messenger  
**Asset basis:** The supplied Khandaq Messenger emblem was used as the starting point. The final production direction preserves the **shield**, **lock**, **fortress crown**, and **deep emerald security identity**, while removing text and excessive detail from the mobile icon system.

> The mobile icon should not carry the words “Khandaq” or “Messenger.” The app name is shown by the operating system, and text inside icons becomes too small to read reliably at launcher, notification, taskbar, and favicon sizes.

## Executive Direction

The recommended primary direction is **Concept B — Modern Security**. It is the strongest production choice because it keeps the recognizable security metaphor from the current emblem, but converts it into a cleaner shield-and-lock symbol that scales from **1024×1024** down to **16×16**. Apple’s Human Interface Guidelines emphasize that a unique, memorable app icon should express the app’s purpose at a glance, should remain simple, and should avoid nonessential text because text is often too small and redundant near the app name.[1] Google Play similarly requires a square 512×512 PNG asset and applies its own rounded mask and shadow, which means the uploaded asset should not include pre-rounded corners or external drop shadows.[2]

| Decision Area | Final Recommendation | Reasoning |
| --- | --- | --- |
| Primary icon concept | **Concept B — Modern Security** | Highest readability and store conversion potential because it has fewer shapes and no text. |
| Brand emblem use | Keep original-style medallion for website, investor decks, and marketing | The current emblem feels premium, but it is too detailed for small app surfaces. |
| Mobile app icon | Shield + fortress crown + lock, no words | This directly communicates security, encryption, protection, and private messaging. |
| Color system | Deep emerald, dark forest, graphite, gunmetal, optional premium gold | This retains the current brand’s serious, secure, high-end technology tone. |
| Small-size strategy | Simplified filled shapes, strong silhouette, high contrast | Fine metal texture, circular lettering, and bevel details disappear at favicon/taskbar sizes. |

## Product Icon Audit

The supplied logo is visually strong as a **brand emblem**, especially for dark website sections, launch screens, presentation covers, and marketing collateral. As a product icon, however, the circular wordmark and metal effects create scalability problems. At small sizes, the text ring becomes noise, the bevel texture compresses into dark pixels, and the outer circular badge competes with the central shield-lock metaphor.

| Surface | Current Emblem Suitability | Production Issue | Recommended Asset |
| --- | --- | --- | --- |
| App Store | Medium | Text ring and metallic detail reduce clarity after system masking | `Brand/iOS/AppIcon.appiconset/AppIcon-1024.png` |
| Google Play | Medium | Play dynamically handles mask and shadow; the asset should be clean and square | `Brand/Android/play-store-icon-512.png` |
| Android Launcher | Low to Medium | Adaptive icon needs foreground/background separation and safe-zone control | `Brand/Android/adaptive/foreground-432.png` and background assets |
| iOS Home Screen | Medium | The symbol must stay centered because the system applies the final rounded shape | `Brand/iOS/AppIcon.appiconset` |
| Windows Taskbar | Low | Circular text cannot read at 16–48 px | `Brand/Windows/khandaq.ico` |
| macOS Dock | Medium | Premium emblem works larger, simplified icon works better smaller | `Brand/MacOS/khandaq.icns` |
| Linux Desktop | Medium | Use PNG ladder with simplified icon | `Brand/Linux/*.png` |
| Browser Favicon | Low | Only the lock-shield silhouette should remain | `Brand/Favicons/favicon.ico` |

## What Will Be Lost at Small Sizes

| Size | Expected Loss From Current Emblem | Final Pack Response |
| --- | --- | --- |
| 48×48 | Ring text and bevel texture become weak; symbol remains partially clear | Uses simplified shield-lock PNG with strong contrast. |
| 32×32 | Outer circular structure becomes visual clutter | Uses simplified, centered shield-lock without text. |
| 24×24 | Lock and shield must dominate; fortress details become secondary | Keeps only bold crown arc, shield, and lock. |
| 16×16 | Only silhouette and contrast survive | Favicon uses a high-contrast simplified export. |

## Concept Evaluation

| Concept | Description | Strength | Weakness | Verdict |
| --- | --- | --- | --- | --- |
| **A — Conservative** | Keeps the badge/medallion feel and preserves more of the original logo’s premium visual language. | Best continuity with the current emblem. | Still too detailed for small icons. | Use for marketing and legacy brand recognition, not primary mobile icon. |
| **B — Modern Security** | Flat-to-subtle dimensional shield, fortress crown, and lock; no circular text. | Best recognizability, scalability, and product-store suitability. | Less ornamental than the original. | **Selected as primary direction.** |
| **C — Premium Fortress** | More formal, darker, premium variant with gold accent potential. | Strong for investor, banking/security, and enterprise positioning. | Gold should be restrained to avoid looking like a finance app rather than a messenger. | Use as campaign or premium brand variant. |

## Competitive Benchmark

The final selected icon is intentionally closer to the product clarity of **Signal**, **Session**, **Proton**, and **Bitwarden** than to a decorative medallion. It keeps a distinctive fortress-security association while avoiding generic chat bubbles, phone handsets, paper planes, or Discord-like shapes. Microsoft’s icon guidance also recommends a singular, simple metaphor and warns against diluting an icon with decorative elements that do not support the metaphor.[3]

| Competitor | Visual Lesson | Khandaq Response |
| --- | --- | --- |
| Signal | Simple recognizable symbol, high trust | Khandaq uses simple shield-lock, not a chat bubble. |
| Session | Privacy-first dark identity | Khandaq retains dark forest/emerald security tone. |
| Proton | Premium privacy design language | Khandaq uses polished restrained gradients and strong geometry. |
| Bitwarden | Shield metaphor for security | Khandaq differentiates through fortress crown and lock integration. |
| Telegram | High recognition but paper-plane metaphor | Khandaq explicitly avoids the paper-plane convention. |

## Color Palette

| Token | Hex | Use |
| --- | --- | --- |
| Deep Emerald Green | `#0B3D2E` | Primary background and brand anchor. |
| Dark Forest Green | `#061B16` | Dark UI, banners, website hero sections. |
| Dark Graphite | `#101416` | Neutral dark backgrounds and UI plates. |
| Gunmetal | `#243036` | Secondary surfaces and dark-mode cards. |
| Emerald Highlight | `#69C58F` | Icon strokes, security highlights, active states. |
| Premium Gold | `#C8A85A` | Optional accent for premium/enterprise materials only. |
| Soft White | `#F4FAF6` | Text on dark backgrounds. |

## Production Notes

Apple recommends producing unmasked square app icon layers and keeping primary content centered because the system applies the final mask and may adjust corners.[1] Google Play requires a 512×512, 32-bit PNG, sRGB icon with no external drop shadow and no manual rounded corners because Play dynamically applies its own mask and shadow.[2] Windows recommends including multiple icon sizes and notes that apps should provide at minimum 16×16, 24×24, 32×32, 48×48, and 256×256 to reduce scaling artifacts.[4]

| Folder | Contents |
| --- | --- |
| `Brand/Logo` | Source logo copies, full logo, horizontal logo, vertical logo, dark/light variants, monochrome assets, and editable SVG. |
| `Brand/Icons` | Concept A/B/C, final icon, and recognition-test exports from 1024 to 16 px. |
| `Brand/iOS` | App icon set PNGs including 1024×1024 master. |
| `Brand/Android` | Google Play icon, adaptive foreground/background, and launcher density exports. |
| `Brand/Windows` | ICO package and PNG ladder. |
| `Brand/MacOS` | ICNS package and PNG ladder. |
| `Brand/Linux` | PNG ladder for desktop environments. |
| `Brand/Favicons` | favicon ICO and PNG favicon assets. |
| `Brand/PWA` | PWA icons and manifest snippet. |
| `Brand/Store` | Google Play and Apple marketing templates. |
| `Brand/Website` | Hero banner, security/encryption illustrations, messenger/device mockups. |
| `Brand/Social` | Avatar and cover/header assets for Telegram, X/Twitter, YouTube, Instagram, and Facebook. |

## References

[1]: https://developer.apple.com/design/human-interface-guidelines/app-icons "Apple Developer Documentation — App icons"  
[2]: https://developer.android.com/distribute/google-play/resources/icon-design-specifications "Android Developers — Google Play icon design specifications"  
[3]: https://learn.microsoft.com/en-us/windows/apps/design/iconography/app-icon-design "Microsoft Learn — Design guidelines for Windows app icons"  
[4]: https://learn.microsoft.com/en-us/windows/apps/design/iconography/app-icon-construction "Microsoft Learn — Construct your Windows app's icon"
