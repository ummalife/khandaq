# Общий набор иконок Khandaq

Одни и те же глифы на iOS и Android. Источник истины — **Figma / Material Symbols Outlined**, а не
SF Symbols: лицензия Apple разрешает SF Symbols только на своих платформах, поэтому общим набором
может быть только Material, и iOS подстраивается под него.

## Что где лежит

| Экран | Android | iOS |
|---|---|---|
| Таб-бар | `res/drawable/ic_tab_*.xml` | `Images.xcassets/TabBar/tab-icon-*.imageset` (SVG) |
| Панель ввода | `ic_add_24` / `i_emoji_*` / `baseline_keyboard_voice_24` + `ChatInputBarHelper.makeCircularSendIcon` | SF `plus` / `mic.fill` / `paperplane.circle.fill` |
| Шапка чата | `ic_header_call/video/attach` (`ChatBubbleUiHelper.set_chat_header_action_icon`) | `Images.xcassets/ChatActions/chat-header-*` |
| Действия над сообщением | `res/drawable/ic_msg_action_*.xml` | `Images.xcassets/ChatActions/msg-action-*.imageset` |

Порядок кнопок в панели ввода одинаковый: **`+` (вложения) · поле · эмодзи · микрофон/отправка**.
Кнопка отправки — белый самолётик на круге `#029B7D` на обеих платформах.

## Конвертеры

```sh
# Android VectorDrawable -> SVG (для ассетов iOS)
python3 vd2svg.py path/to/ic_tab_chats.xml out_dir/

# Material Symbols SVG -> Android VectorDrawable
python3 svg2vd.py path/to/reply_24px.svg out_dir/
```

Иконки берутся из [google/material-design-icons](https://github.com/google/material-design-icons)
(Apache 2.0):
`symbols/web/<name>/materialsymbolsoutlined/<name>_24px.svg`. «Pin» там называется `keep`.

## Грабли

* Часть иконок Khandaq нарисована **обводкой** (`fillColor=transparent` + `strokeColor`/`strokeWidth`) —
  конвертер обязан переносить stroke, иначе контурная шестерня становится залитой.
* Material Symbols используют `viewBox="0 -960 960 960"`; VectorDrawable не умеет отрицательный
  origin, поэтому пути заворачиваются в `<group android:translateY="960">`.
* `app:tint` на `ImageButton` красит **весь** drawable: цветная кнопка отправки превращается в серый
  диск. Тинт снимается в режиме отправки (`ChatInputBarHelper.applyIconTint`).
* iOS-ассеты создаются с `preserves-vector-representation: true` и
  `template-rendering-intent: template`, чтобы тема могла их перекрашивать.
