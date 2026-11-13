/*
    Copyright © 2026 by The Khandaq Project

    This file is part of Khandaq, a Qt-based graphical interface for Tox.

    Khandaq is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Khandaq is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Khandaq.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "airnewcontact.h"

#include "src/widget/air/airchatparts.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"

#include <QAccessible>
#include <QApplication>
#include <QBoxLayout>
#include <QClipboard>
#include <QFontMetricsF>
#include <QGraphicsOpacityEffect>
#include <QKeyEvent>
#include <QLabel>
#include <QLinearGradient>
#include <QMimeData>
#include <QPainter>
#include <QPainterPath>
#include <QScopedPointer>
#include <QScrollArea>
#include <QStyle>
#include <QTextDocument>
#include <QTextEdit>
#include <QTimer>
#include <QVariantAnimation>
#include <QtMath>

#include <algorithm>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Elevation = AirTokens::Elevation;
namespace Motion = AirTokens::Motion;
namespace Radius = AirTokens::Radius;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;
namespace Stroke = AirTokens::Stroke;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

QLabel* makeLabel(TextRole role, Color color, QWidget* parent)
{
    auto* const label = new QLabel(parent);
    label->setTextFormat(Qt::PlainText);
    label->setWordWrap(true);
    label->setFont(air().font(role));
    AirPaint::textColor(label, air().color(color));
    return label;
}

void alert(QWidget* widget)
{
    QAccessibleEvent event(widget, QAccessible::Alert);
    QAccessible::updateAccessibility(&event);
}

/**
 * The text edit inside AirTextField: Enter submits, the ID and key kinds keep their groups of four
 * and the line kind keeps to one line.
 */
class FieldEdit : public QTextEdit
{
public:
    FieldEdit(AirTextField::Kind kind_, QWidget* parent)
        : QTextEdit(parent)
        , kind{kind_}
    {
        connect(this, &QTextEdit::textChanged, this, [this] { onTextChanged(); });
    }

    std::function<void()> onSubmit;
    std::function<void()> onUserChange;

    /** Puts text in as if pasted, without onUserChange. */
    void put(const QString& text)
    {
        quiet = true;
        if (isHex()) {
            apply(Air::IdFieldState::regroup(text, text.size(), hexLength()));
        } else {
            setPlainText(kind == AirTextField::Kind::Line ? oneLine(text) : text);
            moveCursor(QTextCursor::End);
        }
        quiet = false;
    }

protected:
    void keyPressEvent(QKeyEvent* event) override
    {
        const bool enter = event->key() == Qt::Key_Return || event->key() == Qt::Key_Enter;
        if (enter && !(event->modifiers() & Qt::ShiftModifier)) {
            event->accept();
            if (onSubmit) {
                onSubmit();
            }
            return;
        }
        if (enter && kind != AirTextField::Kind::Message) {
            // An ID, a key or a name has no line breaks.
            event->accept();
            return;
        }
        if (isHex()) {
            const bool backwards = event->key() == Qt::Key_Backspace;
            const bool forwards = event->key() == Qt::Key_Delete;
            const Qt::KeyboardModifiers modifiers = event->modifiers() & ~Qt::KeypadModifier;
            if ((backwards || forwards) && modifiers == Qt::NoModifier && !textCursor().hasSelection()) {
                event->accept();
                apply(Air::IdFieldState::erase(toPlainText(), textCursor().position(), forwards,
                                               hexLength()));
                changedByUser();
                return;
            }
        }
        QTextEdit::keyPressEvent(event);
    }

    void insertFromMimeData(const QMimeData* source) override
    {
        if (isHex() && source->hasText()) {
            const QString hex = Air::cleanHex(source->text(), hexLength());
            if (hex.size() == hexLength()) {
                // A whole ID or key takes the place of whatever was there.
                apply(Air::IdFieldState::regroup(hex, hex.size(), hexLength()));
                changedByUser();
                return;
            }
        }
        if (kind == AirTextField::Kind::Line && source->hasText()) {
            insertPlainText(oneLine(source->text()));
            return;
        }
        // Plain text only: a message goes as words, part of an ID goes in where the caret is
        // and the field puts it into groups.
        QTextEdit::insertFromMimeData(source);
    }

private:
    bool isHex() const
    {
        return kind == AirTextField::Kind::Id || kind == AirTextField::Kind::Key;
    }

    int hexLength() const
    {
        return kind == AirTextField::Kind::Key ? Air::toxKeyHexLength : Air::toxAddressHexLength;
    }

    static QString oneLine(QString text)
    {
        text.replace(QLatin1String("\r\n"), QLatin1String(" "));
        for (const QChar lineBreak : {QChar('\n'), QChar('\r'), QChar(0x2028), QChar(0x2029)}) {
            text.replace(lineBreak, QLatin1Char(' '));
        }
        return text;
    }

    void onTextChanged()
    {
        if (regrouping || quiet) {
            return;
        }
        if (isHex()) {
            apply(Air::IdFieldState::regroup(toPlainText(), textCursor().position(), hexLength()));
        }
        changedByUser();
    }

    void changedByUser()
    {
        if (onUserChange) {
            onUserChange();
        }
    }

    void apply(const Air::IdFieldState& state)
    {
        regrouping = true;
        if (toPlainText() != state.text) {
            setPlainText(state.text);
        }
        QTextCursor cursor = textCursor();
        cursor.setPosition(qBound(0, state.caret, document()->characterCount() - 1));
        setTextCursor(cursor);
        regrouping = false;
    }

    AirTextField::Kind kind;
    bool regrouping = false;
    bool quiet = false;
};

/** The shadow under the panel, over the list; the pointer passes through it. */
/** The parts of the ruler weigh 64 : 16 : 12, as in the design system. */
constexpr qreal rulerWeights[] = {64, 16, 12};
constexpr qreal rulerWeightSum = 64 + 16 + 12;
/** The names of the parts may shrink to this share of the caption size to fit, as on the phone. */
constexpr qreal rulerSmallestName = 0.75;
} // namespace

// ---- AirTextField ----

AirTextField::AirTextField(Kind kind_, QWidget* parent)
    : QWidget(parent)
    , kind{kind_}
{
    // The height follows the width, as a wrapped label's does.
    QSizePolicy policy(QSizePolicy::Expanding, QSizePolicy::Preferred);
    policy.setHeightForWidth(true);
    setSizePolicy(policy);
    setCursor(Qt::IBeamCursor);
    auto* const edit = new FieldEdit(kind, this);
    field = edit;
    edit->onSubmit = [this] { emit submitted(); };
    edit->onUserChange = [this] { emit changed(); };
    edit->setFrameStyle(QFrame::NoFrame);
    edit->setAcceptRichText(false);
    edit->setTabChangesFocus(true);
    edit->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    const bool hex = kind == Kind::Id || kind == Kind::Key;
    edit->setVerticalScrollBarPolicy(hex ? Qt::ScrollBarAlwaysOff : Qt::ScrollBarAsNeeded);
    edit->setLineWrapMode(QTextEdit::WidgetWidth);
    edit->document()->setDocumentMargin(0);
    if (hex) {
        // An ID reads left to right in every language, broken between its groups of four.
        edit->setLayoutDirection(Qt::LeftToRight);
        QTextOption option = edit->document()->defaultTextOption();
        option.setTextDirection(Qt::LeftToRight);
        option.setAlignment(Qt::AlignLeft | Qt::AlignAbsolute);
        edit->document()->setDefaultTextOption(option);
        edit->setWordWrapMode(QTextOption::WordWrap);
        edit->setInputMethodHints(Qt::ImhPreferUppercase | Qt::ImhNoPredictiveText | Qt::ImhLatinOnly);
    } else {
        edit->setWordWrapMode(QTextOption::WrapAtWordBoundaryOrAnywhere);
    }

    edit->installEventFilter(this);
    connect(edit, &QTextEdit::textChanged, this, [this] {
        remeasure();
        update();
    });
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirTextField::restyle);
    restyle();
}

QTextEdit* AirTextField::edit() const
{
    return field;
}

QString AirTextField::text() const
{
    return field->toPlainText();
}

void AirTextField::setText(const QString& text)
{
    static_cast<FieldEdit*>(field)->put(text);
    remeasure();
    update();
}

void AirTextField::setPlaceholder(const QString& text)
{
    if (placeholder != text) {
        placeholder = text;
        remeasure();
        update();
    }
}

void AirTextField::setInvalid(bool invalid_)
{
    if (invalid != invalid_) {
        invalid = invalid_;
        update();
    }
}

void AirTextField::restyle()
{
    field->setFont(
        air().font(kind == Kind::Id || kind == Kind::Key ? TextRole::MonoEntry : TextRole::Body));
    const QColor ink = air().color(Color::Ink);
    const QColor selected = air().color(Color::Selected);
    QPalette palette = field->palette();
    palette.setColor(QPalette::Base, Qt::transparent);
    palette.setColor(QPalette::Text, ink);
    palette.setColor(QPalette::Highlight, selected);
    palette.setColor(QPalette::HighlightedText, ink);
    field->setPalette(palette);
    // The window's legacy style sheet frames and paints every QTextEdit; the field's own wins.
    field->setStyleSheet(QStringLiteral("QTextEdit { background: transparent; border: none;"
                                        " padding: 0; margin: 0; color: %1;"
                                        " selection-background-color: %2; selection-color: %1; }")
                             .arg(ink.name(QColor::HexArgb), selected.name(QColor::HexArgb)));
    remeasure();
    update();
}

void AirTextField::remeasure()
{
    measuredWidth = -1;
    updateGeometry();
}

bool AirTextField::hasHeightForWidth() const
{
    return true;
}

int AirTextField::heightForWidth(int width) const
{
    if (width == measuredWidth) {
        return measuredHeight;
    }
    const int textWidth = std::max(1, width - 2 * Space::s12);
    const QFontMetrics metrics(field->font());
    int textHeight = metrics.lineSpacing();
    if (!field->document()->isEmpty()) {
        // The text as it would wrap at that width, measured on a copy of it.
        const QScopedPointer<QTextDocument> copy(field->document()->clone());
        copy->setTextWidth(textWidth);
        textHeight = qCeil(copy->size().height());
    } else if (!placeholder.isEmpty()) {
        textHeight =
            metrics
                .boundingRect(QRect(0, 0, textWidth, QWIDGETSIZE_MAX), Qt::TextWordWrap, placeholder)
                .height();
    }
    const int minimum = kind == Kind::Id ? Size::idField : Size::field;
    const int maximum = kind == Kind::Id || kind == Kind::Key ? QWIDGETSIZE_MAX : Size::composeMax;
    measuredWidth = width;
    measuredHeight =
        qBound(minimum, std::max(textHeight, metrics.lineSpacing()) + 2 * Space::s10, maximum);
    return measuredHeight;
}

QSize AirTextField::sizeHint() const
{
    return QSize(Size::listColumn, heightForWidth(Size::listColumn));
}

QSize AirTextField::minimumSizeHint() const
{
    return QSize(Space::s64, kind == Kind::Id ? Size::idField : Size::field);
}

void AirTextField::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    // kq-input: red while what it holds cannot go, ink with the focus, darker under the pointer.
    QColor border = air().color(Color::LineStrong);
    if (invalid) {
        border = air().color(Color::Danger);
    } else if (field->hasFocus()) {
        border = air().color(Color::Ink);
    } else if (hovered) {
        border = air().color(Color::Ink3);
    }
    AirPaint::box(painter, QRectF(rect()), Radius::field, air().color(Color::Surface), border);

    if (field->document()->isEmpty() && !placeholder.isEmpty()) {
        // The placeholder is words of the interface: it stands at the start edge of the
        // interface, also over the ID, which itself always starts on the left.
        painter.setFont(field->font());
        painter.setPen(air().color(Color::Ink3));
        const int flags =
            static_cast<int>(QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignTop))
            | Qt::TextWordWrap;
        painter.drawText(field->geometry(), flags, placeholder);
    }
    if (AirPaint::keyboardFocus(field)) {
        AirPaint::focusRing(painter, QRectF(rect()), Radius::field);
    }
}

void AirTextField::mousePressEvent(QMouseEvent* event)
{
    // The padding round the text belongs to the field too.
    field->setFocus(Qt::MouseFocusReason);
    QWidget::mousePressEvent(event);
}

void AirTextField::enterEvent(QEvent* event)
{
    hovered = true;
    update();
    QWidget::enterEvent(event);
}

void AirTextField::leaveEvent(QEvent* event)
{
    hovered = false;
    update();
    QWidget::leaveEvent(event);
}

void AirTextField::resizeEvent(QResizeEvent* event)
{
    QWidget::resizeEvent(event);
    field->setGeometry(rect().adjusted(Space::s12, Space::s10, -Space::s12, -Space::s10));
}

bool AirTextField::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == field && (event->type() == QEvent::FocusIn || event->type() == QEvent::FocusOut)) {
        update();
    }
    return QWidget::eventFilter(watched, event);
}

// ---- AirIdRuler ----

AirIdRuler::AirIdRuler(QWidget* parent)
    : QWidget(parent)
    , parts(Air::RulerPart::parts(0))
{
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    // The parts follow the ID they measure, which reads left to right in every language.
    setLayoutDirection(Qt::LeftToRight);
    setFocusPolicy(Qt::NoFocus);
    AirPaint::followTheme(this);
    describe();
}

QSize AirIdRuler::sizeHint() const
{
    return QSize(Size::listColumn, Size::bar + Space::s6 + 2 * air().lineHeight(TextRole::Caption));
}

QSize AirIdRuler::minimumSizeHint() const
{
    return QSize(0, sizeHint().height());
}

void AirIdRuler::setState(int count, Air::IdVerdict::Check check_, bool animate)
{
    const QVector<Air::RulerPart> next = Air::RulerPart::parts(count);
    const bool same = next == parts && check_ == check;
    parts = next;
    check = check_;
    describe();
    if (same) {
        return;
    }
    for (int i = 0; i < 3; ++i) {
        from[i] = shown[i];
        to[i] = parts.at(i).size > 0 ? static_cast<qreal>(parts.at(i).filled) / parts.at(i).size : 0;
    }
    if (fill) {
        fill->stop();
    }
    if (!animate || air().reducedMotion() || !isVisible()) {
        std::copy(std::begin(to), std::end(to), std::begin(shown));
        update();
        return;
    }
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(0.0);
    animation->setEndValue(1.0);
    animation->setDuration(air().duration(Motion::layout));
    animation->setEasingCurve(air().easeOut());
    connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        const qreal t = value.toReal();
        for (int i = 0; i < 3; ++i) {
            shown[i] = from[i] + (to[i] - from[i]) * t;
        }
        update();
    });
    connect(animation, &QVariantAnimation::finished, this, [this] {
        std::copy(std::begin(to), std::end(to), std::begin(shown));
        update();
    });
    fill = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirIdRuler::shakeCheck()
{
    if (air().reducedMotion() || !isVisible()) {
        return;
    }
    if (shaking) {
        shaking->stop();
    }
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(0.0);
    animation->setEndValue(1.0);
    animation->setDuration(air().duration(Motion::layout));
    animation->setEasingCurve(air().easeOut());
    connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        // Back, forth, a little back and home: the steps of the phone's shake.
        static const qreal times[] = {0, 0.3, 0.6, 0.85, 1};
        static const qreal offsets[] = {0, -Space::s4, Space::s4, -Space::s2, 0};
        const qreal t = value.toReal();
        int step = 0;
        while (step < 3 && t > times[step + 1]) {
            ++step;
        }
        const qreal span = times[step + 1] - times[step];
        const qreal within = span > 0 ? qBound<qreal>(0, (t - times[step]) / span, 1) : 1;
        shake = offsets[step] + (offsets[step + 1] - offsets[step]) * within;
        update();
    });
    connect(animation, &QVariantAnimation::finished, this, [this] {
        shake = 0;
        update();
    });
    shaking = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirIdRuler::describe()
{
    // One line a part for a screen reader: "key: 64 of 64".
    const Text names[] = {Text::NcRulerKey, Text::NcRulerAntispam, Text::NcRulerCheck};
    QStringList lines;
    for (int i = 0; i < 3 && i < parts.size(); ++i) {
        lines << AirStrings::text(Text::NcRulerPart)
                     .arg(AirStrings::text(names[i]))
                     .arg(parts.at(i).filled)
                     .arg(parts.at(i).size);
    }
    setAccessibleName(lines.join(QLatin1Char('\n')));
}

void AirIdRuler::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    const qreal gap = Space::s6;
    const qreal unit = std::max<qreal>(0, width() - 2 * gap) / rulerWeightSum;
    const int count = std::min(3, parts.size());

    // The bars: the sunken track of each part, and the filled share of it from its left end.
    QRectF bars[3];
    qreal x = 0;
    for (int i = 0; i < count; ++i) {
        const qreal partWidth = rulerWeights[i] * unit;
        bars[i] = QRectF(x + (i == 2 ? shake : 0), 0, partWidth, Size::bar);
        x += partWidth + gap;
        QColor fillColor = air().color(Color::Ink);
        if (i == 2 && check == Air::IdVerdict::Check::Good) {
            fillColor = air().color(Color::Live);
        } else if (i == 2 && check == Air::IdVerdict::Check::Bad) {
            fillColor = air().color(Color::Danger);
        }
        QPainterPath track;
        track.addRoundedRect(bars[i], Size::bar / 2.0, Size::bar / 2.0);
        painter.fillPath(track, air().color(Color::Sunken));
        if (shown[i] > 0) {
            painter.save();
            painter.setClipPath(track);
            painter.fillRect(QRectF(bars[i].left(), bars[i].top(), bars[i].width() * shown[i],
                                    bars[i].height()),
                             fillColor);
            painter.restore();
        }
    }
    if (count < 3) {
        return;
    }

    // The names over their counts. The key's stand under its part. Antispam and check share the
    // stretch from the antispam part to the end of the ruler, the check's flush with that end, so a
    // name longer than its short part borrows room from its neighbour instead of being cut. All
    // three take one size, down to three quarters of the caption as on the phone; what still does
    // not fit is cut, each name in its share of the stretch.
    const Text nameKeys[] = {Text::NcRulerKey, Text::NcRulerAntispam, Text::NcRulerCheck};
    QString names[3];
    qreal natural[3];
    const QFont caption = air().font(TextRole::Caption);
    const QFontMetricsF captionMetrics(caption);
    for (int i = 0; i < 3; ++i) {
        names[i] = AirStrings::text(nameKeys[i]);
        natural[i] = captionMetrics.horizontalAdvance(names[i]);
    }
    const qreal keyRoom = bars[0].width();
    const qreal shared = std::max<qreal>(0, width() - bars[1].left() - gap);
    qreal scale = 1;
    if (natural[0] > keyRoom) {
        scale = std::min(scale, keyRoom / natural[0]);
    }
    if (natural[1] + natural[2] > shared) {
        scale = std::min(scale, shared / (natural[1] + natural[2]));
    }
    scale = std::max(rulerSmallestName, scale);
    QFont nameFont = caption;
    if (scale < 1) {
        nameFont.setPointSizeF(caption.pointSizeF() * scale);
    }
    const QFontMetricsF nameMetrics(nameFont);
    qreal antispamRoom = nameMetrics.horizontalAdvance(names[1]);
    qreal checkRoom = nameMetrics.horizontalAdvance(names[2]);
    if (antispamRoom + checkRoom > shared && antispamRoom + checkRoom > 0) {
        const qreal both = antispamRoom + checkRoom;
        antispamRoom = shared * antispamRoom / both;
        checkRoom = shared - antispamRoom;
    }
    // The check's column moves with its part while it shakes.
    const qreal end = bars[2].right();
    const int captionLine = air().lineHeight(TextRole::Caption);
    const qreal nameTop = Size::bar + Space::s6;
    const qreal countTop = nameTop + captionLine;
    const QRectF nameBoxes[] = {
        QRectF(bars[0].left(), nameTop, keyRoom, captionLine),
        QRectF(bars[1].left(), nameTop, antispamRoom, captionLine),
        QRectF(end - checkRoom, nameTop, checkRoom, captionLine),
    };
    const QRectF countBoxes[] = {
        QRectF(bars[0].left(), countTop, bars[0].width(), captionLine),
        QRectF(bars[1].left(), countTop, bars[1].width(), captionLine),
        QRectF(end - bars[2].width(), countTop, bars[2].width(), captionLine),
    };
    QFont countFont = caption;
    countFont.setWeight(QFont::Medium);
    for (int i = 0; i < 3; ++i) {
        const bool bad = i == 2 && check == Air::IdVerdict::Check::Bad;
        const Qt::Alignment align =
            (i == 2 ? Qt::AlignRight : Qt::AlignLeft) | Qt::AlignAbsolute | Qt::AlignVCenter;
        painter.setPen(bad ? air().color(Color::Danger) : air().color(Color::Ink3));
        painter.setFont(nameFont);
        painter.drawText(nameBoxes[i], align,
                         nameMetrics.elidedText(names[i], Qt::ElideRight, nameBoxes[i].width()));
        painter.setFont(countFont);
        painter.drawText(countBoxes[i], align,
                         QStringLiteral("%1/%2").arg(parts.at(i).filled).arg(parts.at(i).size));
    }
}

// ---- AirCommandIdRow ----

AirCommandIdRow::AirCommandIdRow(QWidget* parent)
    : QAbstractButton(parent)
{
    setObjectName(QStringLiteral("commandNewContact"));
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::TabFocus);
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    AirPaint::followTheme(this);
    Translator::registerHandler(std::bind(&AirCommandIdRow::retranslate, this), this);
    retranslate();
}

AirCommandIdRow::~AirCommandIdRow()
{
    Translator::unregister(this);
}

QString AirCommandIdRow::noteFor(const Air::IdVerdict& verdict, int hexCount)
{
    switch (verdict.kind) {
    case Air::IdVerdict::Kind::Empty:
    case Air::IdVerdict::Kind::Partial:
        return AirStrings::text(Text::CmdIdLeft, std::max(0, Air::toxAddressHexLength - hexCount));
    case Air::IdVerdict::Kind::BadCheck:
        return AirStrings::text(Text::CmdIdBad);
    case Air::IdVerdict::Kind::Own:
        return AirStrings::text(Text::CmdIdOwn);
    case Air::IdVerdict::Kind::Contact:
        return AirStrings::text(Text::CmdIdContact);
    case Air::IdVerdict::Kind::TheyAsked:
    case Air::IdVerdict::Kind::Blocked:
    case Air::IdVerdict::Kind::Ok:
        break;
    }
    return AirStrings::text(Text::CmdIdOk);
}

void AirCommandIdRow::setIdStart(const QString& hex_, const Air::IdVerdict& verdict_)
{
    hex = hex_;
    verdict = verdict_;
    retranslate();
}

QString AirCommandIdRow::idStart() const
{
    return hex;
}

void AirCommandIdRow::retranslate()
{
    setText(AirStrings::text(Text::CmdNewContact));
    setAccessibleName(text());
    setAccessibleDescription(noteFor(verdict, hex.size()));
    update();
}

QSize AirCommandIdRow::sizeHint() const
{
    return QSize(Size::listColumn, Size::rowDesk);
}

void AirCommandIdRow::enterEvent(QEvent* event)
{
    hovered = true;
    update();
    QAbstractButton::enterEvent(event);
}

void AirCommandIdRow::leaveEvent(QEvent* event)
{
    hovered = false;
    update();
    QAbstractButton::leaveEvent(event);
}

void AirCommandIdRow::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    QColor ground = air().color(Color::Surface);
    if (isDown()) {
        ground = air().color(Color::Selected);
    } else if (hovered) {
        ground = air().color(Color::Sunken);
    }
    painter.fillRect(rect(), ground);
    if (AirPaint::keyboardFocus(this)) {
        AirPaint::focusRing(painter, QRectF(rect()).adjusted(Space::s4, 1, -Space::s4, -1),
                            Radius::control);
    }
    const auto visual = [this](const QRect& logical) {
        return QStyle::visualRect(layoutDirection(), rect(), logical);
    };

    // The dashed tile of a contact still to come, with the add-person icon in it.
    const int tileSize = Size::tileDesk;
    const QRect tile = visual(QRect(Space::s16, (height() - tileSize) / 2, tileSize, tileSize));
    QPen dashed(air().color(Color::LineStrong), Stroke::hairline);
    dashed.setStyle(Qt::DashLine);
    painter.setPen(dashed);
    painter.setBrush(Qt::NoBrush);
    painter.drawRoundedRect(QRectF(tile).adjusted(0.5, 0.5, -0.5, -0.5), Radius::control,
                            Radius::control);
    const int glyph = Size::iconSmall;
    painter.drawPixmap(QPointF(tile.left() + (tile.width() - glyph) / 2.0,
                               tile.top() + (tile.height() - glyph) / 2.0),
                       air().icon(QStringLiteral("user-plus"), air().color(Color::Ink3), glyph,
                                  devicePixelRatioF()));

    // "New contact with this ID" over what is missing or wrong so far.
    const int textLeft = Space::s16 + tileSize + Space::s12;
    const int textWidth = std::max(0, width() - textLeft - Space::s16);
    const int bodyHeight = air().lineHeight(TextRole::Body);
    const int captionHeight = air().lineHeight(TextRole::Caption);
    const int top = (height() - bodyHeight - Space::s2 - captionHeight) / 2;
    const int align =
        static_cast<int>(QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter));
    QFont title = air().font(TextRole::Body);
    title.setWeight(QFont::Medium);
    painter.setFont(title);
    painter.setPen(air().color(Color::Ink));
    painter.drawText(visual(QRect(textLeft, top, textWidth, bodyHeight)), align,
                     QFontMetrics(title).elidedText(text(), Qt::ElideRight, textWidth));
    const QFont caption = air().font(TextRole::Caption);
    painter.setFont(caption);
    painter.setPen(air().color(Color::Ink3));
    painter.drawText(visual(QRect(textLeft, top + bodyHeight + Space::s2, textWidth, captionHeight)),
                     align,
                     QFontMetrics(caption).elidedText(noteFor(verdict, hex.size()), Qt::ElideRight,
                                                      textWidth));
}

// ---- AirNewContact ----

AirNewContact::AirNewContact(Lookup lookup_, QWidget* column_, QWidget* parent)
    : AirDropPanel(column_, parent)
    , lookup{std::move(lookup_)}
{
    setObjectName(QStringLiteral("newContact"));
    closer()->setObjectName(QStringLiteral("newContactClose"));
    QWidget* const pageWidget = AirDropPanel::page();
    QVBoxLayout* const sheet = pageLayout();

    // ---- The form ----
    formPage = new QWidget(pageWidget);
    auto* const form = new QVBoxLayout(formPage);
    form->setContentsMargins(0, 0, 0, 0);
    form->setSpacing(Space::s20);
    sheet->addWidget(formPage);

    // Their ID: the field, Paste or Clear under it, the ruler and the verdict.
    auto* const idBox = new QVBoxLayout();
    idBox->setSpacing(Space::s8);
    idLabel = makeLabel(TextRole::Label, Color::Ink2, formPage);
    idField = new AirTextField(AirTextField::Kind::Id, formPage);
    idField->setObjectName(QStringLiteral("newContactId"));
    auto* const fieldActions = new QHBoxLayout();
    fieldActions->setSpacing(Space::s12);
    fieldActions->addStretch(1);
    pasteButton = new AirButton(AirButton::Kind::Quiet, formPage);
    pasteButton->setObjectName(QStringLiteral("newContactPaste"));
    clearButton = new AirButton(AirButton::Kind::Quiet, formPage);
    clearButton->setObjectName(QStringLiteral("newContactClear"));
    fieldActions->addWidget(pasteButton);
    fieldActions->addWidget(clearButton);
    ruler = new AirIdRuler(formPage);
    ruler->setObjectName(QStringLiteral("newContactRuler"));
    verdictLabel = makeLabel(TextRole::Caption, Color::Ink2, formPage);
    verdictLabel->setObjectName(QStringLiteral("newContactVerdict"));
    verdictLink = new AirButton(AirButton::Kind::Quiet, formPage);
    verdictLink->setObjectName(QStringLiteral("newContactVerdictLink"));
    idBox->addWidget(idLabel);
    idBox->addWidget(idField);
    idBox->addLayout(fieldActions);
    idBox->addWidget(ruler);
    idBox->addWidget(verdictLabel);
    idBox->addWidget(verdictLink, 0, Qt::AlignLeading);
    form->addLayout(idBox);

    // The message that goes with the request.
    auto* const messageBox = new QVBoxLayout();
    messageBox->setSpacing(Space::s6);
    messageLabel = makeLabel(TextRole::Label, Color::Ink2, formPage);
    messageField = new AirTextField(AirTextField::Kind::Message, formPage);
    messageField->setObjectName(QStringLiteral("newContactMessage"));
    messageError = makeLabel(TextRole::Caption, Color::Danger, formPage);
    messageNote = makeLabel(TextRole::Caption, Color::Ink3, formPage);
    messageBox->addWidget(messageLabel);
    messageBox->addWidget(messageField);
    messageBox->addWidget(messageError);
    messageBox->addWidget(messageNote);
    form->addLayout(messageBox);

    // "Send request", and the line that says why it is off while it is.
    auto* const sendBox = new QVBoxLayout();
    sendBox->setSpacing(Space::s6);
    sendError = makeLabel(TextRole::Caption, Color::Danger, formPage);
    sendError->setObjectName(QStringLiteral("newContactError"));
    sendButton = new AirButton(AirButton::Kind::Ink, formPage);
    sendButton->setObjectName(QStringLiteral("newContactSend"));
    whyLabel = makeLabel(TextRole::Caption, Color::Ink3, formPage);
    whyLabel->setObjectName(QStringLiteral("newContactWhy"));
    sendBox->addWidget(sendError);
    sendBox->addWidget(sendButton, 0, Qt::AlignLeading);
    sendBox->addWidget(whyLabel);
    form->addLayout(sendBox);

    // Your own ID, the one to give to others, with Copy.
    auto* const ownBox = new QVBoxLayout();
    ownBox->setSpacing(Space::s6);
    ownTitle = makeLabel(TextRole::Label, Color::Ink2, formPage);
    ownKey = makeLabel(TextRole::Mono, Color::Ink2, formPage);
    ownKey->setObjectName(QStringLiteral("newContactOwnId"));
    ownKey->setWordWrap(false);
    ownKey->setTextInteractionFlags(Qt::TextSelectableByMouse);
    // The lines of an ID start on the left in every language; the block stands at the start edge.
    ownKey->setLayoutDirection(Qt::LeftToRight);
    ownKey->setSizePolicy(QSizePolicy::Maximum, QSizePolicy::Preferred);
    copyButton = new AirButton(AirButton::Kind::Plain, formPage);
    copyButton->setObjectName(QStringLiteral("newContactCopy"));
    ownNote = makeLabel(TextRole::Caption, Color::Ink3, formPage);
    ownBox->addWidget(ownTitle);
    ownBox->addWidget(ownKey, 0, Qt::AlignLeading);
    ownBox->addSpacing(Space::s2);
    ownBox->addWidget(copyButton, 0, Qt::AlignLeading);
    ownBox->addWidget(ownNote);
    form->addLayout(ownBox);

    importButton = new AirButton(AirButton::Kind::Quiet, formPage);
    importButton->setObjectName(QStringLiteral("newContactImport"));
    form->addWidget(importButton, 0, Qt::AlignLeading);

    // ---- Request on its way ----
    sentPage = new QWidget(pageWidget);
    auto* const sent = new QVBoxLayout(sentPage);
    sent->setContentsMargins(0, Space::s8, 0, 0);
    sent->setSpacing(Space::s12);
    path = new AirPathPicture(sentPage);
    path->setObjectName(QStringLiteral("newContactPath"));
    sentTitle = makeLabel(TextRole::Heading, Color::Ink, sentPage);
    sentTitle->setObjectName(QStringLiteral("newContactSentTitle"));
    sentText = makeLabel(TextRole::Body, Color::Ink2, sentPage);
    doneButton = new AirButton(AirButton::Kind::Ink, sentPage);
    doneButton->setObjectName(QStringLiteral("newContactDone"));
    sent->addWidget(path);
    sent->addWidget(sentTitle);
    sent->addWidget(sentText);
    sent->addSpacing(Space::s4);
    sent->addWidget(doneButton, 0, Qt::AlignLeading);
    sentPage->hide();
    sheet->addWidget(sentPage);
    sheet->addStretch(1);

    connect(idField, &AirTextField::changed, this, [this] { refresh(true); });
    connect(idField, &AirTextField::submitted, this, &AirNewContact::send);
    connect(messageField, &AirTextField::changed, this, [this] { refresh(false); });
    connect(messageField, &AirTextField::submitted, this, &AirNewContact::send);
    connect(pasteButton, &AirButton::clicked, this, [this] {
        const QString raw = QApplication::clipboard()->text();
        if (!raw.isEmpty()) {
            setId(raw, true);
        }
        idField->edit()->setFocus(Qt::OtherFocusReason);
    });
    connect(clearButton, &AirButton::clicked, this, [this] {
        setId(QString(), false);
        idField->edit()->setFocus(Qt::OtherFocusReason);
    });
    connect(verdictLink, &AirButton::clicked, this, [this] {
        if (verdict.kind == Air::IdVerdict::Kind::Contact) {
            const QString key = verdict.value;
            close();
            emit openChatRequested(key);
        } else if (verdict.kind == Air::IdVerdict::Kind::TheyAsked) {
            close();
            emit openRequestsRequested();
        }
    });
    connect(sendButton, &AirButton::clicked, this, &AirNewContact::send);
    connect(copyButton, &AirButton::clicked, this, [this] {
        QApplication::clipboard()->setText(Air::cleanToxId(lookup.ownAddress()));
        AirToast::post(this, AirStrings::text(Text::NcCopied));
    });
    connect(importButton, &AirButton::clicked, this, [this] {
        close();
        emit importRequested();
    });
    connect(doneButton, &AirButton::clicked, this, &AirNewContact::close);

    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirNewContact::restyle);
    Translator::registerHandler(std::bind(&AirNewContact::retranslate, this), this);
    restyle();
    retranslate();
    resetForm();
}

AirNewContact::~AirNewContact()
{
    Translator::unregister(this);
}

void AirNewContact::open(const QString& text)
{
    // Every opening starts afresh; an ID handed to the open form replaces only the ID, so the
    // message written so far stays.
    if (!isOpen() || (!text.isEmpty() && page != Page::Form)) {
        resetForm();
    }
    if (!text.isEmpty()) {
        setId(text, true);
    }
    unfold();
    focusFirst();
}

void AirNewContact::aboutToFold()
{
    path->setRunning(false);
}

Air::IdVerdict AirNewContact::commandVerdict(const QString& hex) const
{
    return Air::IdVerdict::of(hex, lookup.ownAddress ? lookup.ownAddress() : QString(),
                              lookup.isContact, nullptr, nullptr);
}

QString AirNewContact::awaitedKey() const
{
    if (page != Page::Sending && page != Page::Sent) {
        return QString();
    }
    return sentKey;
}

void AirNewContact::requestSent(const QString& key)
{
    if (page != Page::Sending || key.compare(sentKey, Qt::CaseInsensitive) != 0) {
        return;
    }
    showSent();
}

void AirNewContact::requestFailed(const QString& key)
{
    if (page != Page::Sending || key.compare(sentKey, Qt::CaseInsensitive) != 0) {
        return;
    }
    // Said where the action is, under the button (E21). With the panel folded away there is no
    // such place left, and an error is never a toast: the window says it.
    page = Page::Form;
    sentKey.clear();
    if (!isOpen()) {
        resetForm();
        AirDialog::notice(parentWidget(), AirStrings::text(Text::ErrorAddContactTitle),
                          AirStrings::text(Text::NcSendFailed));
        return;
    }
    sendError->show();
    refresh(false);
    alert(sendError);
}

void AirNewContact::personAccepted(const QString& name, Air::LinkState link)
{
    if (page != Page::Sent) {
        return;
    }
    const QString trimmed = name.trimmed();
    if (accepted && trimmed == acceptedName && link == acceptedLink) {
        return;
    }
    const bool first = !accepted;
    accepted = true;
    acceptedName = trimmed;
    acceptedLink = link;
    path->setRunning(false);
    sayWhereItIs();
    if (first) {
        alert(sentTitle);
    }
}

void AirNewContact::retranslate()
{
    setTitle(AirStrings::text(Text::NewContact));
    idLabel->setText(AirStrings::text(Text::NcIdLabel));
    idField->setPlaceholder(AirStrings::text(Text::NcIdPlaceholder));
    idField->edit()->setAccessibleName(AirStrings::text(Text::NcIdLabel));
    pasteButton->setText(AirStrings::text(Text::NcPaste));
    clearButton->setText(AirStrings::text(Text::NcClear));
    messageLabel->setText(AirStrings::text(Text::NcMessageLabel));
    messageField->setPlaceholder(AirStrings::text(Text::NcGreeting));
    messageField->edit()->setAccessibleName(AirStrings::text(Text::NcMessageLabel));
    messageField->edit()->setAccessibleDescription(AirStrings::text(Text::NcMessageNote));
    messageError->setText(AirStrings::text(Text::NcMessageTooLong));
    messageNote->setText(AirStrings::text(Text::NcMessageNote));
    sendError->setText(AirStrings::text(Text::NcSendFailed));
    sendButton->setText(AirStrings::text(Text::NcSend));
    ownTitle->setText(AirStrings::text(Text::NcOwnTitle));
    copyButton->setText(AirStrings::text(Text::NcCopy));
    ownNote->setText(AirStrings::text(Text::NcOwnNote));
    importButton->setText(AirStrings::text(Text::NcImport));
    doneButton->setText(AirStrings::text(Text::NcDone));
    for (AirButton* const button :
         {pasteButton, clearButton, verdictLink, sendButton, copyButton, importButton, doneButton}) {
        button->updateGeometry();
    }

    const QString own = Air::cleanToxId(lookup.ownAddress ? lookup.ownAddress() : QString());
    ownKey->setText(Air::keyLines(own));
    ownKey->setAccessibleName(AirStrings::text(Text::NcOwnTitle));
    ownKey->setAccessibleDescription(Air::keyGroups(own).join(QLatin1Char(' ')));

    // Words stand at the start edge of the interface, whatever script they are in; the lines of
    // the ID inside their block start on the left.
    const Qt::Alignment start =
        QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter);
    for (QLabel* const label : AirDropPanel::page()->findChildren<QLabel*>()) {
        label->setAlignment(label == ownKey ? Qt::AlignLeft | Qt::AlignAbsolute | Qt::AlignVCenter
                                            : start);
    }

    if (page == Page::Sent) {
        sayWhereItIs();
    }
    refresh(false);
}

void AirNewContact::restyle()
{
    const struct
    {
        // Every member is set by the braces that make it: an aggregate, which an initializer here
        // would stop it being in C++11.
        // cppcheck-suppress uninitMemberVarNoCtor
        QLabel* label;
        TextRole role;
        Color color;
    } texts[] = {
        {idLabel, TextRole::Label, Color::Ink2},
        {messageLabel, TextRole::Label, Color::Ink2},
        {messageError, TextRole::Caption, Color::Danger},
        {messageNote, TextRole::Caption, Color::Ink3},
        {sendError, TextRole::Caption, Color::Danger},
        {whyLabel, TextRole::Caption, Color::Ink3},
        {ownTitle, TextRole::Label, Color::Ink2},
        {ownKey, TextRole::Mono, Color::Ink2},
        {ownNote, TextRole::Caption, Color::Ink3},
        {sentTitle, TextRole::Heading, Color::Ink},
        {sentText, TextRole::Body, Color::Ink2},
    };
    for (const auto& text : texts) {
        text.label->setFont(air().font(text.role));
        AirPaint::textColor(text.label, air().color(text.color));
    }
    verdictLabel->setFont(air().font(TextRole::Caption));
    refresh(false);
    update();
}

void AirNewContact::resetForm()
{
    page = Page::Form;
    sentKey.clear();
    accepted = false;
    acceptedName.clear();
    acceptedLink = Air::LinkState::None;
    path->setRunning(false);
    sentPage->hide();
    formPage->show();
    idField->setText(QString());
    messageField->setText(QString());
    sendError->hide();
    verdict = Air::IdVerdict();
    refresh(false);
}

void AirNewContact::setId(const QString& raw, bool fromUser)
{
    idField->setText(Air::cleanToxId(raw));
    refresh(fromUser);
}

void AirNewContact::refresh(bool fromUser)
{
    using Kind = Air::IdVerdict::Kind;
    const QString hex = Air::cleanToxId(idField->text());
    const Air::IdVerdict previous = verdict;
    verdict = Air::IdVerdict::of(hex, lookup.ownAddress ? lookup.ownAddress() : QString(),
                                 lookup.isContact, lookup.hasRequest, lookup.isBlocked);

    pasteButton->setVisible(hex.isEmpty());
    clearButton->setVisible(!hex.isEmpty());
    ruler->setState(hex.size(), verdict.check(), isVisible());
    if (fromUser && verdict.kind == Kind::BadCheck && previous.kind != Kind::BadCheck) {
        ruler->shakeCheck();
    }

    QString words;
    QString link;
    Color color = Color::Ink2;
    switch (verdict.kind) {
    case Kind::Empty:
        words = AirStrings::text(Text::NcVerdictEmpty);
        color = Color::Ink3;
        break;
    case Kind::Partial:
        words = AirStrings::text(Text::NcVerdictLeft, verdict.left);
        break;
    case Kind::BadCheck:
        words = AirStrings::text(Text::NcVerdictBad);
        color = Color::Danger;
        break;
    case Kind::Own:
        words = AirStrings::text(Text::NcVerdictOwn);
        color = Color::Danger;
        break;
    case Kind::Contact: {
        const QString name =
            lookup.contactName ? lookup.contactName(verdict.value).trimmed() : QString();
        words = AirStrings::text(Text::NcVerdictContact)
                    .arg(name.isEmpty() ? AirStrings::text(Text::NcThem) : name);
        link = AirStrings::text(Text::NcOpenChat);
        break;
    }
    case Kind::TheyAsked:
        words = AirStrings::text(Text::NcVerdictTheyAsked);
        link = AirStrings::text(Text::NcOpenRequests);
        break;
    case Kind::Blocked:
        words = AirStrings::text(Text::NcVerdictBlocked);
        break;
    case Kind::Ok:
        words = AirStrings::text(Text::NcVerdictOk);
        color = Color::Live;
        break;
    }
    verdictLabel->setText(words);
    AirPaint::textColor(verdictLabel, air().color(color));
    verdictLink->setVisible(!link.isEmpty());
    if (!link.isEmpty() && verdictLink->text() != link) {
        verdictLink->setText(link);
        verdictLink->updateGeometry();
    }

    const bool tooLong = messageField->text().trimmed().toUtf8().size() > Air::friendRequestMaxBytes;
    messageError->setVisible(tooLong);
    messageField->setInvalid(tooLong);
    const bool sending = page == Page::Sending;
    const bool canSend = !verdict.address().isNull() && !tooLong && !sending;
    sendButton->setEnabled(canSend);
    QString why;
    switch (verdict.kind) {
    case Kind::Empty:
    case Kind::Partial:
        why = AirStrings::text(Text::NcWhyWhole);
        break;
    case Kind::BadCheck:
        why = AirStrings::text(Text::NcWhyFix);
        break;
    case Kind::Own:
        why = AirStrings::text(Text::NcWhyOwn);
        break;
    case Kind::Contact:
        why = AirStrings::text(Text::NcWhyContact);
        break;
    case Kind::TheyAsked:
        why = AirStrings::text(Text::NcWhyTheyAsked);
        break;
    case Kind::Blocked:
    case Kind::Ok:
        why = tooLong ? AirStrings::text(Text::NcMessageTooLong) : QString();
        break;
    }
    whyLabel->setText(why);
    whyLabel->setVisible(!canSend && !sending && !why.isEmpty());
    sendButton->setAccessibleDescription(canSend ? QString() : why);

    // A screen reader hears the verdict when it turns into another kind, not at every character.
    const auto group = [](Kind kind) {
        switch (kind) {
        case Kind::Empty:
        case Kind::Partial:
            return 0;
        case Kind::BadCheck:
            return 1;
        case Kind::Own:
            return 2;
        case Kind::Contact:
        case Kind::TheyAsked:
            return 3;
        case Kind::Blocked:
        case Kind::Ok:
            break;
        }
        return 4;
    };
    if (fromUser && group(previous.kind) != group(verdict.kind) && group(verdict.kind) != 0) {
        alert(verdictLabel);
    }
}

void AirNewContact::send()
{
    if (page != Page::Form) {
        return;
    }
    refresh(false);
    const QString address = verdict.address();
    if (address.isNull() || !sendButton->isEnabled()) {
        return;
    }
    const QString typed = messageField->text().trimmed();
    const QString message = typed.isEmpty() ? AirStrings::text(Text::NcGreeting) : typed;
    sentKey = address.left(Air::toxKeyHexLength);
    page = Page::Sending;
    sendError->hide();
    refresh(false);
    emit sendRequested(address, message);
}

void AirNewContact::showSent()
{
    page = Page::Sent;
    accepted = false;
    acceptedName.clear();
    acceptedLink = Air::LinkState::None;
    sayWhereItIs();
    formPage->hide();
    sentPage->show();
    if (!isOpen()) {
        return;
    }
    // The panel turns into the new page, which comes in with a fade; then its dashes run.
    auto* const effect = new QGraphicsOpacityEffect(sentPage);
    effect->setOpacity(0);
    sentPage->setGraphicsEffect(effect);
    auto* const fadeIn = new QVariantAnimation(sentPage);
    fadeIn->setStartValue(0.0);
    fadeIn->setEndValue(1.0);
    fadeIn->setDuration(air().reducedMotion() ? air().duration(Motion::reducedMaxDuration)
                                              : air().duration(Motion::state));
    fadeIn->setEasingCurve(air().easeOut());
    connect(fadeIn, &QVariantAnimation::valueChanged, effect,
            [effect](const QVariant& value) { effect->setOpacity(value.toReal()); });
    connect(fadeIn, &QVariantAnimation::finished, sentPage,
            [this] { sentPage->setGraphicsEffect(nullptr); });
    fadeIn->start(QAbstractAnimation::DeleteWhenStopped);
    place();
    path->setRunning(true);
    doneButton->setFocus(Qt::OtherFocusReason);
    alert(sentTitle);
}

void AirNewContact::sayWhereItIs()
{
    const QString them = AirStrings::text(Text::NcThem);
    if (!accepted) {
        path->setPerson(them, QStringLiteral("?"), Air::LinkState::None);
        path->setAccessibleName(AirStrings::text(Text::NcPathWaiting).arg(them));
        sentTitle->setText(AirStrings::text(Text::NcSentTitle));
        sentText->setText(AirStrings::text(Text::NcSentText));
        return;
    }
    const QString name = acceptedName.isEmpty() ? them : acceptedName;
    path->setPerson(name, acceptedName.isEmpty() ? QStringLiteral("?") : Air::initials(name),
                    acceptedLink);
    path->setAccessibleName(AirStrings::text(Text::NcPathLive).arg(name));
    sentTitle->setText(AirStrings::text(Text::NcAcceptedTitle));
    sentText->setText(AirStrings::text(Text::NcAcceptedText).arg(name));
}

void AirNewContact::focusFirst()
{
    if (page == Page::Sent) {
        doneButton->setFocus(Qt::OtherFocusReason);
        return;
    }
    if (!verdict.address().isNull()) {
        messageField->edit()->setFocus(Qt::OtherFocusReason);
        return;
    }
    idField->edit()->setFocus(Qt::OtherFocusReason);
    idField->edit()->moveCursor(QTextCursor::End);
}
