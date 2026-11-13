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

#include "airwidgets.h"

#include "src/model/air/airmodel.h"
#include "src/widget/air/airstyle.h"

#include <QApplication>
#include <QCoreApplication>
#include <QEvent>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QLabel>
#include <QLineEdit>
#include <QMenu>
#include <QPainter>
#include <QPainterPath>
#include <QScrollArea>
#include <QScrollBar>
#include <QStyle>
#include <QTextLayout>
#include <QVariantAnimation>
#include <QtMath>

using AirTokens::Color;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;
namespace Radius = AirTokens::Radius;
namespace Stroke = AirTokens::Stroke;
using AirTokens::TextRole;

namespace {

const AirStyle& air()
{
    return AirStyle::instance();
}

bool showsKeyboardFocus(const QWidget* widget)
{
    return widget->hasFocus() && widget->window()->testAttribute(Qt::WA_KeyboardFocusChange);
}

/** A rounded rectangle whose hairline border sits inside rect. */
void roundedBox(QPainter& painter, const QRectF& rect, qreal radius, const QColor& fill,
                const QColor& border)
{
    const qreal half = Stroke::hairline / 2.0;
    const QRectF box = rect.adjusted(half, half, -half, -half);
    const qreal clamped = std::min(radius, std::min(box.width(), box.height()) / 2.0);
    painter.setPen(border.isValid() ? QPen(border, Stroke::hairline) : Qt::NoPen);
    painter.setBrush(fill.isValid() ? QBrush(fill) : Qt::NoBrush);
    painter.drawRoundedRect(box, clamped, clamped);
}

void keepRepainted(QWidget* widget)
{
    QObject::connect(&AirStyle::instance(), &AirStyle::changed, widget,
                     [widget] { widget->update(); });
}

} // namespace

void AirPaint::box(QPainter& painter, const QRectF& rect, qreal radius, const QColor& fill,
                   const QColor& border)
{
    roundedBox(painter, rect, radius, fill, border);
}

void AirPaint::followTheme(QWidget* widget)
{
    keepRepainted(widget);
}

void AirPaint::textColor(QWidget* widget, const QColor& color)
{
    QPalette palette = widget->palette();
    palette.setColor(QPalette::WindowText, color);
    palette.setColor(QPalette::Text, color);
    widget->setPalette(palette);
    widget->setStyleSheet(QStringLiteral("color: %1;").arg(color.name(QColor::HexArgb)));
}

bool AirPaint::keyboardFocus(const QWidget* widget)
{
    return showsKeyboardFocus(widget);
}

void AirPaint::scrollWhenLong(QMenu& menu)
{
    menu.setStyleSheet(QStringLiteral("QMenu { menu-scrollable: 1; }"));
    // A menu asks its style whether it scrolls when it is made, and again only when its style
    // changes. Under a window with a style sheet, its own sheet comes in only when it is polished,
    // and no style change follows; so it is polished here and then told.
    menu.ensurePolished();
    QEvent styleChange(QEvent::StyleChange);
    QCoreApplication::sendEvent(&menu, &styleChange);
}

void AirPaint::focusRing(QPainter& painter, const QRectF& rect, qreal radius)
{
    const qreal half = Stroke::focus / 2.0;
    const QRectF ring = rect.adjusted(half, half, -half, -half);
    painter.save();
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setPen(QPen(air().color(Color::Focus), Stroke::focus));
    painter.setBrush(Qt::NoBrush);
    const qreal clamped = std::min(radius, std::min(ring.width(), ring.height()) / 2.0);
    painter.drawRoundedRect(ring, clamped, clamped);
    painter.restore();
}

void AirPaint::rollingText(QPainter& painter, const QRect& rect, int flags, const QString& previous,
                           const QString& current, qreal progress)
{
    if (progress >= 1.0 || previous == current) {
        painter.drawText(rect, flags, current);
        return;
    }
    painter.save();
    painter.setClipRect(rect);
    const qreal opacity = painter.opacity();
    if (air().reducedMotion()) {
        painter.setOpacity(opacity * (1.0 - progress));
        painter.drawText(rect, flags, previous);
        painter.setOpacity(opacity * progress);
        painter.drawText(rect, flags, current);
    } else {
        const int travel = rect.height();
        painter.setOpacity(opacity * (1.0 - progress));
        painter.drawText(rect.translated(0, -qRound(travel * progress)), flags, previous);
        painter.setOpacity(opacity * progress);
        painter.drawText(rect.translated(0, qRound(travel * (1.0 - progress))), flags, current);
    }
    painter.restore();
}

QString AirPaint::countText(int count)
{
    return count > 99 ? QStringLiteral("99+") : QString::number(count);
}

QStringList AirPaint::linesOf(const QString& text, const QFont& font, int width, int maxLines)
{
    QString flat = text;
    for (QChar& character : flat) {
        if (character == QLatin1Char('\n') || character == QLatin1Char('\r')
            || character == QChar(QChar::LineSeparator)
            || character == QChar(QChar::ParagraphSeparator)) {
            character = QLatin1Char(' ');
        }
    }
    flat = flat.trimmed();
    QStringList lines;
    if (flat.isEmpty() || width <= 0 || maxLines <= 0) {
        return lines;
    }
    QTextLayout layout(flat, font);
    layout.beginLayout();
    for (;;) {
        QTextLine line = layout.createLine();
        if (!line.isValid()) {
            break;
        }
        line.setLineWidth(width);
        if (lines.size() == maxLines - 1) {
            lines.append(
                QFontMetrics(font).elidedText(flat.mid(line.textStart()), Qt::ElideRight, width));
            break;
        }
        lines.append(flat.mid(line.textStart(), line.textLength()).trimmed());
    }
    layout.endLayout();
    return lines;
}

Qt::LayoutDirection AirPaint::ownDirection(const QString& text)
{
    return text.isRightToLeft() ? Qt::RightToLeft : Qt::LeftToRight;
}

void AirPaint::ownText(QPainter& painter, const QRect& rect, int flags, const QString& text)
{
    const Qt::LayoutDirection interfaceDirection = painter.layoutDirection();
    painter.setLayoutDirection(ownDirection(text));
    painter.drawText(rect, flags, text);
    painter.setLayoutDirection(interfaceDirection);
}

// ---- AirFocus ----

QList<QWidget*> AirFocus::stops(const QWidget* root)
{
    QList<QWidget*> found;
    if (!root) {
        return found;
    }
    // The chain runs through the whole window and closes on itself.
    for (QWidget* widget = root->nextInFocusChain(); widget && widget != root;
         widget = widget->nextInFocusChain()) {
        if ((widget->focusPolicy() & Qt::TabFocus) && root->isAncestorOf(widget)) {
            found.append(widget);
        }
    }
    return found;
}

QList<QWidget*> AirFocus::made(const QWidget* root)
{
    QList<QWidget*> found;
    if (!root) {
        return found;
    }
    // Children come in the order they were made, each followed by its own.
    for (QWidget* const widget : root->findChildren<QWidget*>()) {
        if (widget->focusPolicy() & Qt::TabFocus) {
            found.append(widget);
        }
    }
    return found;
}

void AirFocus::chain(const QList<QWidget*>& order)
{
    for (int index = 1; index < order.size(); ++index) {
        if (order[index - 1] && order[index] && order[index - 1] != order[index]) {
            QWidget::setTabOrder(order[index - 1], order[index]);
        }
    }
}

void AirFocus::inPlace(QWidget* holder)
{
    if (!holder) {
        return;
    }
    // The holder itself stays where it was made; the stops follow the last one before it.
    QWidget* anchor = holder->previousInFocusChain();
    while (anchor && anchor != holder
           && (!(anchor->focusPolicy() & Qt::TabFocus) || holder->isAncestorOf(anchor))) {
        anchor = anchor->previousInFocusChain();
    }
    if (!anchor || anchor == holder) {
        return;
    }
    // In the order they were made: rows placed by an earlier call stand before the holder in the
    // chain, and new ones at the window's end.
    QList<QWidget*> order{anchor};
    order << made(holder);
    chain(order);
}

void AirFocus::bringIntoView(QScrollArea* area, QWidget* child, int margin)
{
    QWidget* const content = area ? area->widget() : nullptr;
    if (!content || !child || !content->isAncestorOf(child)) {
        return;
    }
    const QSize view = area->viewport()->size();
    const QRect shown(-content->pos(), view);
    const QRect wanted(child->mapTo(content, QPoint(0, 0)), child->size());
    if (shown.contains(wanted)) {
        return;
    }
    // Where the part to show starts along one axis, in the content's own coordinates, as Qt has
    // it: a child larger than the view stands in its middle, else the view moves as little as it
    // can.
    const auto start = [](int from, int length, int shownFrom, int shownLength) {
        if (length > shownLength) {
            return from + length / 2 - shownLength / 2;
        }
        if (from + length > shownFrom + shownLength) {
            return from + length - shownLength;
        }
        return from < shownFrom ? from : shownFrom;
    };
    const QRect around = wanted.adjusted(-margin, -margin, margin, margin);
    const int left = start(around.left(), around.width(), shown.left(), view.width());
    const int top = start(around.top(), around.height(), shown.top(), view.height());
    // A right-to-left area puts its content at the viewport's right edge while its bar stands at
    // 0 (QScrollAreaPrivate::updateWidgetPosition), so its bar counts from the right.
    area->horizontalScrollBar()->setValue(
        area->layoutDirection() == Qt::RightToLeft ? content->width() - view.width() - left : left);
    area->verticalScrollBar()->setValue(top);
}

void AirFocus::followIntoView()
{
    static bool following = false;
    if (following || !qApp) {
        return;
    }
    following = true;
    QObject::connect(qApp, &QApplication::focusChanged, qApp, [](QWidget*, QWidget* now) {
        if (!now) {
            return;
        }
        // The innermost area first, then the ones around it.
        for (QWidget* up = now->parentWidget(); up; up = up->parentWidget()) {
            auto* const area = qobject_cast<QScrollArea*>(up);
            if (area && area->widget() && area->widget()->isAncestorOf(now)) {
                bringIntoView(area, now, Space::s16);
            }
        }
    });
}

// ---- AirCoveredFocus ----

AirCoveredFocus::~AirCoveredFocus()
{
    uncover();
}

void AirCoveredFocus::cover(QWidget* covered, const QWidget* panel)
{
    uncover();
    if (!covered) {
        return;
    }
    QList<QWidget*> widgets = covered->findChildren<QWidget*>();
    widgets.prepend(covered);
    for (QWidget* const widget : widgets) {
        const Qt::FocusPolicy before = widget->focusPolicy();
        if (!(before & Qt::TabFocus) || widget == panel || (panel && panel->isAncestorOf(widget))) {
            continue;
        }
        // A click still gives the focus where a click can reach; Tab passes over.
        const Qt::FocusPolicy during = (before & Qt::ClickFocus) ? Qt::ClickFocus : Qt::NoFocus;
        entries.push_back(Entry{widget, before, during});
        widget->setFocusPolicy(during);
    }
}

void AirCoveredFocus::uncover()
{
    for (const Entry& entry : entries) {
        if (entry.widget && entry.widget->focusPolicy() == entry.during) {
            entry.widget->setFocusPolicy(entry.before);
        }
    }
    entries.clear();
}

// ---- AirIconButton ----

AirIconButton::AirIconButton(const QString& iconName_, QWidget* parent)
    : QAbstractButton(parent)
    , iconName{iconName_}
{
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::TabFocus);
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    keepRepainted(this);
}

void AirIconButton::setIconName(const QString& name)
{
    if (iconName != name) {
        iconName = name;
        update();
    }
}

void AirIconButton::setRotation(qreal degrees)
{
    if (!qFuzzyCompare(rotation, degrees)) {
        rotation = degrees;
        update();
    }
}

void AirIconButton::setDanger(bool danger_)
{
    if (danger != danger_) {
        danger = danger_;
        update();
    }
}

QSize AirIconButton::sizeHint() const
{
    return QSize(Size::control, Size::control);
}

void AirIconButton::enterEvent(QEvent* event)
{
    hovered = true;
    update();
    QAbstractButton::enterEvent(event);
}

void AirIconButton::leaveEvent(QEvent* event)
{
    hovered = false;
    update();
    QAbstractButton::leaveEvent(event);
}

void AirIconButton::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    const QRectF box = rect();

    QColor fill;
    if (isDown() || isChecked()) {
        fill = air().color(Color::Selected);
    } else if (hovered) {
        fill = air().color(Color::Sunken);
    }
    if (fill.isValid()) {
        roundedBox(painter, box, Radius::control, fill, QColor());
    }

    const QColor ink = danger && isEnabled() ? air().color(Color::Danger)
                       : (hovered || isChecked() || isDown()) && isEnabled() ? air().color(Color::Ink)
                       : isEnabled() ? air().color(Color::Ink2)
                                     : air().color(Color::Ink3);
    const QPixmap glyph = air().icon(iconName, ink, Size::icon, devicePixelRatioF());
    const QPointF centre = QRectF(rect()).center();
    painter.save();
    painter.setRenderHint(QPainter::SmoothPixmapTransform);
    painter.translate(centre);
    painter.rotate(rotation);
    painter.drawPixmap(QPointF(-Size::icon / 2.0, -Size::icon / 2.0), glyph);
    painter.restore();

    if (showsKeyboardFocus(this)) {
        AirPaint::focusRing(painter, box, Radius::control);
    }
}

// ---- AirButton ----

AirButton::AirButton(Kind kind_, QWidget* parent)
    : QAbstractButton(parent)
    , kind{kind_}
{
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::StrongFocus);
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    keepRepainted(this);
}

void AirButton::setOwnWords(bool ownWords_)
{
    ownWords = ownWords_;
    update();
}

void AirButton::setEmphasis(bool emphasis_)
{
    if (emphasis == emphasis_) {
        return;
    }
    emphasis = emphasis_;
    update();
}

void AirButton::drawLabel(QPainter& painter, const QRectF& box) const
{
    const int flags = Qt::AlignCenter | Qt::TextSingleLine;
    if (ownWords) {
        AirPaint::ownText(painter, box.toRect(), flags, text());
    } else {
        painter.drawText(box, flags, text());
    }
}

QSize AirButton::sizeHint() const
{
    const QFontMetrics metrics(air().font(TextRole::Label));
    if (kind == Kind::Retry) {
        return QSize(metrics.horizontalAdvance(text()) + 2 * Space::s2, Size::controlSmall);
    }
    if (kind == Kind::Quiet) {
        return QSize(metrics.horizontalAdvance(text()) + 2 * Space::s4, Size::controlSmall);
    }
    if (kind == Kind::OnInk) {
        QFont font = air().font(TextRole::Label);
        font.setWeight(QFont::DemiBold);
        return QSize(QFontMetrics(font).horizontalAdvance(text()) + 2 * Space::s6, Size::controlSmall);
    }
    return QSize(metrics.horizontalAdvance(text()) + 2 * Space::s16, Size::control);
}

QSize AirButton::minimumSizeHint() const
{
    return sizeHint();
}

void AirButton::enterEvent(QEvent* event)
{
    hovered = true;
    update();
    QAbstractButton::enterEvent(event);
}

void AirButton::leaveEvent(QEvent* event)
{
    hovered = false;
    update();
    QAbstractButton::leaveEvent(event);
}

void AirButton::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    QRectF box = rect();
    if (isDown()) {
        box.translate(0, 1);
    }

    QColor fill;
    QColor border;
    QColor ink;
    if (kind == Kind::OnInk) {
        QFont font = air().font(TextRole::Label);
        font.setWeight(QFont::DemiBold);
        font.setUnderline(true);
        painter.setFont(font);
        painter.setPen(air().color(Color::OnInk));
        drawLabel(painter, rect());
        if (showsKeyboardFocus(this)) {
            AirPaint::focusRing(painter, rect(), Radius::small);
        }
        return;
    }
    const bool quiet = kind == Kind::Quiet || kind == Kind::Retry;
    if (quiet) {
        // Underlined words without a frame; pressing does not move them.
        box = rect();
        ink = !isEnabled()                      ? air().color(Color::Ink3)
              : kind == Kind::Retry             ? air().color(Color::Danger)
              : hovered || isDown() || emphasis ? air().color(Color::Ink)
                                                : air().color(Color::Ink2);
        QFont font = air().font(TextRole::Label);
        font.setUnderline(true);
        painter.setFont(font);
        painter.setPen(ink);
        drawLabel(painter, box);
        if (showsKeyboardFocus(this)) {
            AirPaint::focusRing(painter, rect(), Radius::control);
        }
        return;
    }
    if (!isEnabled()) {
        fill = air().color(Color::Sunken);
        border = air().color(Color::Line);
        ink = air().color(Color::Ink3);
    } else if (kind == Kind::Ink) {
        fill = isDown()  ? air().color(Color::InkPressed)
               : hovered ? air().color(Color::InkHover)
                         : air().color(Color::Ink);
        border = fill;
        ink = air().color(Color::OnInk);
    } else {
        fill = isDown() ? air().color(Color::Sunken) : air().color(Color::Raised);
        border = hovered ? air().color(Color::Ink3) : air().color(Color::LineStrong);
        ink = kind == Kind::Danger ? air().color(Color::Danger) : air().color(Color::Ink);
    }
    roundedBox(painter, box, Radius::control, fill, border);

    painter.setFont(air().font(TextRole::Label));
    painter.setPen(ink);
    drawLabel(painter, box);

    if (showsKeyboardFocus(this)) {
        AirPaint::focusRing(painter, rect(), Radius::control);
    }
}

// ---- AirPill ----

AirPill::AirPill(Kind kind_, QWidget* parent)
    : QAbstractButton(parent)
    , kind{kind_}
{
    setCheckable(true);
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::TabFocus);
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    keepRepainted(this);
}

void AirPill::setCount(int count_)
{
    if (count_ == value) {
        return;
    }
    const bool resize = (count_ > 0) != (value > 0) || badgeWidth(count_) != badgeWidth(value);
    previousValue = value;
    value = count_;
    if (resize) {
        updateGeometry();
    }

    if (roll) {
        roll->stop();
        roll->deleteLater();
    }
    // A count appearing from nothing or vanishing simply shows; a changing one rolls.
    if (previousValue == 0 || value == 0 || !isVisible()) {
        progress = 1.0;
        update();
        return;
    }
    progress = 0.0;
    roll = new QVariantAnimation(this);
    roll->setStartValue(0.0);
    roll->setEndValue(1.0);
    roll->setDuration(air().duration(AirTokens::Motion::state));
    roll->setEasingCurve(air().easeStandard());
    connect(roll, &QVariantAnimation::valueChanged, this, [this](const QVariant& step) {
        progress = step.toReal();
        update();
    });
    roll->start(QAbstractAnimation::DeleteWhenStopped);
}

int AirPill::count() const
{
    return value;
}

int AirPill::badgeWidth(int number) const
{
    if (number <= 0) {
        return 0;
    }
    QFont font = air().font(TextRole::Caption);
    font.setWeight(QFont::DemiBold);
    const int textWidth = QFontMetrics(font).horizontalAdvance(AirPaint::countText(number));
    return std::max(Size::badge, textWidth + 2 * Space::s6);
}

QSize AirPill::sizeHint() const
{
    const QFontMetrics metrics(air().font(TextRole::Label));
    int width = 2 * Space::s12 + metrics.horizontalAdvance(text());
    const int badge = badgeWidth(value);
    if (badge > 0) {
        width += Space::s8 + badge;
    }
    return QSize(width, Size::chip);
}

QRect AirPill::badgeRect(const QRect& contents) const
{
    const int width = badgeWidth(value);
    const int top = contents.top() + (contents.height() - Size::badge) / 2;
    const QRect logical(contents.right() - Space::s6 - width + 1, top, width, Size::badge);
    return QStyle::visualRect(layoutDirection(), contents, logical);
}

void AirPill::enterEvent(QEvent* event)
{
    hovered = true;
    update();
    QAbstractButton::enterEvent(event);
}

void AirPill::leaveEvent(QEvent* event)
{
    hovered = false;
    update();
    QAbstractButton::leaveEvent(event);
}

void AirPill::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    const QRect box = rect();
    const bool on = isChecked();

    const QColor fill = on ? air().color(Color::Ink) : air().color(Color::Raised);
    const QColor border = on        ? air().color(Color::Ink)
                          : hovered ? air().color(Color::Ink3)
                                    : air().color(Color::LineStrong);
    roundedBox(painter, box, Radius::round, fill, border);

    QRect textArea = box.adjusted(Space::s12, 0, -Space::s12, 0);
    if (value > 0) {
        const QRect badge = badgeRect(box);
        QColor badgeFill = air().color(Color::Sunken);
        QColor badgeInk = air().color(Color::Ink);
        if (on) {
            badgeFill = air().color(Color::InkBadge);
            badgeInk = air().color(Color::OnInk);
        } else if (kind == Kind::Transit) {
            badgeFill = air().color(Color::TransitSoft);
            badgeInk = air().color(Color::Transit);
        }
        roundedBox(painter, badge, Radius::round, badgeFill, QColor());
        QFont font = air().font(TextRole::Caption);
        font.setWeight(QFont::DemiBold);
        painter.setFont(font);
        painter.setPen(badgeInk);
        AirPaint::rollingText(painter, badge, Qt::AlignCenter, AirPaint::countText(previousValue),
                              AirPaint::countText(value), progress);
        if (layoutDirection() == Qt::RightToLeft) {
            textArea.setLeft(badge.right() + Space::s8);
        } else {
            textArea.setRight(badge.left() - Space::s8);
        }
    }

    painter.setFont(air().font(TextRole::Label));
    painter.setPen(on ? air().color(Color::OnInk) : air().color(Color::Ink));
    painter.drawText(textArea, Qt::AlignVCenter | Qt::AlignLeading | Qt::TextSingleLine, text());

    if (showsKeyboardFocus(this)) {
        AirPaint::focusRing(painter, box, Radius::round);
    }
}

// ---- AirNetworkLine ----

AirNetworkLine::AirNetworkLine(QWidget* parent)
    : QAbstractButton(parent)
{
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::TabFocus);
    setAttribute(Qt::WA_Hover);
    keepRepainted(this);
}

void AirNetworkLine::setState(const QString& words, Dot dot_, bool crossFade)
{
    if (words == text() && dot_ == dot) {
        return;
    }
    if (fade) {
        fade->stop();
    }
    previousText = text();
    previousDot = dot;
    dot = dot_;
    setText(words);
    shown = 1.0;
    // The dot takes its colour at once and stays where it is; only the words cross-fade.
    if (crossFade && isVisible() && !previousText.isEmpty()) {
        shown = 0.0;
        fade = new QVariantAnimation(this);
        fade->setStartValue(0.0);
        fade->setEndValue(1.0);
        fade->setDuration(air().duration(AirTokens::Motion::state));
        connect(fade, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
            shown = value.toReal();
            update();
        });
        connect(fade, &QVariantAnimation::finished, this, [this] {
            shown = 1.0;
            emit wordsChanged();
            update();
        });
        fade->start(QAbstractAnimation::DeleteWhenStopped);
    }
    emit wordsChanged();
    update();
}

int AirNetworkLine::wordsWidth() const
{
    const QFontMetricsF metrics(air().font(TextRole::Caption));
    qreal words = metrics.horizontalAdvance(text());
    if (shown < 1.0) {
        // The old words keep their room until they have faded.
        words = std::max(words, metrics.horizontalAdvance(previousText));
    }
    return Space::s4 + Size::dot + Space::s6 + qCeil(words) + Space::s6;
}

void AirNetworkLine::enterEvent(QEvent* event)
{
    update();
    // The block around it gives up its own hover while the line has it.
    if (parentWidget()) {
        parentWidget()->update();
    }
    QAbstractButton::enterEvent(event);
}

void AirNetworkLine::leaveEvent(QEvent* event)
{
    update();
    if (parentWidget()) {
        parentWidget()->update();
    }
    QAbstractButton::leaveEvent(event);
}

void AirNetworkLine::paintWords(QPainter& painter, const QString& words, Dot wordsDot, qreal opacity)
{
    const int wordsStart = Space::s4 + Size::dot + Space::s6;
    const int room = width() - wordsStart - Space::s4;
    if (room <= 0 || words.isEmpty()) {
        return;
    }
    const QFont font = air().font(TextRole::Caption);
    painter.save();
    painter.setOpacity(opacity);
    painter.setFont(font);
    painter.setPen(wordsDot == Dot::Danger ? air().color(Color::Danger) : air().color(Color::Ink3));
    const QRect area =
        QStyle::visualRect(layoutDirection(), rect(), QRect(wordsStart, 0, room, height()));
    painter.drawText(area, Qt::AlignVCenter | Qt::AlignLeading | Qt::TextSingleLine,
                     QFontMetrics(font).elidedText(words, Qt::ElideRight, room));
    painter.restore();
}

void AirNetworkLine::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    if (underMouse() || isDown()) {
        roundedBox(painter, rect(), Radius::control, air().color(Color::Sunken), QColor());
    }

    // A still dot, never pulsing: green while messages leave, grey while the node looks for the
    // network, red only when there is no network at all.
    const QColor dotColor = dot == Dot::Live      ? air().color(Color::Live)
                            : dot == Dot::Waiting ? air().color(Color::Ink3)
                                                  : air().color(Color::Danger);
    const QRect dotRect =
        QStyle::visualRect(layoutDirection(), rect(),
                           QRect(Space::s4, (height() - Size::dot) / 2, Size::dot, Size::dot));
    painter.setPen(Qt::NoPen);
    painter.setBrush(dotColor);
    painter.drawEllipse(dotRect);

    if (shown < 1.0) {
        paintWords(painter, previousText, previousDot, 1.0 - shown);
    }
    paintWords(painter, text(), dot, shown);

    if (showsKeyboardFocus(this)) {
        AirPaint::focusRing(painter, rect(), Radius::control);
    }
}

// ---- AirSelfButton ----

AirSelfButton::AirSelfButton(QWidget* parent)
    : QAbstractButton(parent)
{
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::TabFocus);
    setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Fixed);
    keepRepainted(this);
    line = new AirNetworkLine(this);
    line->setObjectName(QStringLiteral("bandNetwork"));
    connect(line, &AirNetworkLine::wordsChanged, this, &AirSelfButton::placeLine);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirSelfButton::placeLine);
}

void AirSelfButton::setName(const QString& name_)
{
    name = name_;
    update();
}

void AirSelfButton::setAvatar(const QPixmap& avatar_)
{
    avatar = avatar_;
    update();
}

AirNetworkLine* AirSelfButton::networkLine() const
{
    return line;
}

QSize AirSelfButton::sizeHint() const
{
    // The width of the list column less the band's own padding, as in the prototype.
    return QSize(Size::listColumn - 2 * Space::s16, Size::field);
}

QSize AirSelfButton::minimumSizeHint() const
{
    return QSize(Size::tileDesk + Space::s10 + 120, Size::field);
}

QRect AirSelfButton::nameRect() const
{
    const int textStart = Space::s4 + Size::tileDesk + Space::s10;
    const int nameHeight = air().lineHeight(TextRole::Label);
    const int top = (height() - nameHeight - air().lineHeight(TextRole::Caption)) / 2;
    return QRect(textStart, top, std::max(0, width() - textStart - Space::s8), nameHeight);
}

QRect AirSelfButton::lineRect() const
{
    // The line takes the height under the name, its words where they always stood; its own
    // padding starts a little before the dot, so its hover ground does not touch the tile.
    const QRect nameBox = nameRect();
    const int left = nameBox.left() - Space::s4;
    const int top = nameBox.bottom() + 1 - Space::s2;
    const int room = std::max(0, width() - left - Space::s4);
    return QRect(left, top, std::min(room, line->wordsWidth()), height() - top);
}

void AirSelfButton::placeLine()
{
    line->setGeometry(QStyle::visualRect(layoutDirection(), rect(), lineRect()));
}

void AirSelfButton::resizeEvent(QResizeEvent* event)
{
    QAbstractButton::resizeEvent(event);
    placeLine();
}

void AirSelfButton::changeEvent(QEvent* event)
{
    QAbstractButton::changeEvent(event);
    if (event->type() == QEvent::LayoutDirectionChange || event->type() == QEvent::FontChange) {
        placeLine();
    }
}

void AirSelfButton::enterEvent(QEvent* event)
{
    hovered = true;
    update();
    QAbstractButton::enterEvent(event);
}

void AirSelfButton::leaveEvent(QEvent* event)
{
    hovered = false;
    update();
    QAbstractButton::leaveEvent(event);
}

void AirSelfButton::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    const QRect box = rect();
    const auto visual = [&](const QRect& logical) {
        return QStyle::visualRect(layoutDirection(), box, logical);
    };

    // The line under the name is a target of its own: while it has the pointer, the block does not
    // light up as if it would open You.
    if ((hovered && !line->underMouse()) || isDown()) {
        roundedBox(painter, box, Radius::control, air().color(Color::Sunken), QColor());
    }

    // Your own tile is ink; a photo, when you set one, fills it.
    const int tileTop = (box.height() - Size::tileDesk) / 2;
    const QRect tile = visual(QRect(Space::s4, tileTop, Size::tileDesk, Size::tileDesk));
    if (!avatar.isNull()) {
        QPainterPath clip;
        clip.addRoundedRect(tile, Radius::control, Radius::control);
        painter.save();
        painter.setClipPath(clip);
        painter.drawPixmap(tile, avatar);
        painter.restore();
    } else {
        roundedBox(painter, tile, Radius::control, air().color(Color::Ink), QColor());
        QFont initialsFont = air().font(TextRole::Caption);
        initialsFont.setWeight(QFont::DemiBold);
        painter.setFont(initialsFont);
        painter.setPen(air().color(Color::OnInk));
        painter.drawText(tile, Qt::AlignCenter, Air::initials(name));
    }

    const QRect nameBox = nameRect();
    if (nameBox.width() <= 0) {
        return;
    }
    QFont nameFont = air().font(TextRole::Label);
    nameFont.setWeight(QFont::DemiBold);
    painter.setFont(nameFont);
    painter.setPen(air().color(Color::Ink));
    const int nameFlags = QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter)
                          | Qt::TextSingleLine;
    AirPaint::ownText(painter, visual(nameBox), nameFlags,
                      QFontMetrics(nameFont).elidedText(name, Qt::ElideRight, nameBox.width()));

    if (showsKeyboardFocus(this)) {
        AirPaint::focusRing(painter, box, Radius::control);
    }
}

// ---- AirCommandField ----

namespace {
/** The command line in the Air band is up to 560 px wide (02-plan.md, E3 on the desktop). */
constexpr int commandFieldWidth = 560;
} // namespace

AirCommandField::AirCommandField(QWidget* parent)
    : QFrame(parent)
{
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    setMaximumWidth(commandFieldWidth);

    auto* layout = new QHBoxLayout(this);
    layout->setContentsMargins(Space::s10, 0, Space::s8, 0);
    layout->setSpacing(Space::s8);

    searchIcon = new QLabel(this);
    searchIcon->setFixedSize(Size::icon, Size::icon);
    layout->addWidget(searchIcon);

    field = new QLineEdit(this);
    field->setFrame(false);
    field->setClearButtonEnabled(false);
    field->setAttribute(Qt::WA_MacShowFocusRect, false);
    field->installEventFilter(this);
    layout->addWidget(field, 1);

    keyHint = new QLabel(QStringLiteral("/"), this);
    keyHint->setAlignment(Qt::AlignCenter);
    keyHint->setMinimumWidth(Size::badge);
    keyHint->setFixedHeight(Size::badge);
    layout->addWidget(keyHint);

    connect(field, &QLineEdit::textChanged, this, [this](const QString& text) {
        keyHint->setVisible(text.isEmpty() && !field->hasFocus());
    });
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirCommandField::restyle);
    restyle();
}

QLineEdit* AirCommandField::lineEdit() const
{
    return field;
}

void AirCommandField::setPlaceholder(const QString& text)
{
    field->setPlaceholderText(text);
    field->setAccessibleName(text);
}

void AirCommandField::setKeyHint(const QString& accessibleHint)
{
    keyHint->setToolTip(accessibleHint);
    field->setAccessibleDescription(accessibleHint);
}

QSize AirCommandField::sizeHint() const
{
    return QSize(commandFieldWidth, Size::field);
}

QSize AirCommandField::minimumSizeHint() const
{
    return QSize(160, Size::field);
}

void AirCommandField::restyle()
{
    const QColor ink3 = air().color(Color::Ink3);
    searchIcon->setPixmap(air().icon(QStringLiteral("search"), ink3, Size::icon, devicePixelRatioF()));

    field->setFont(air().font(TextRole::Body));
    QPalette palette = field->palette();
    palette.setColor(QPalette::Base, Qt::transparent);
    palette.setColor(QPalette::Text, air().color(Color::Ink));
    palette.setColor(QPalette::PlaceholderText, ink3);
    palette.setColor(QPalette::Highlight, air().color(Color::Selected));
    palette.setColor(QPalette::HighlightedText, air().color(Color::Ink));
    field->setPalette(palette);
    // The window's legacy style sheet styles every QLineEdit; a widget's own sheet wins over it.
    field->setStyleSheet(
        QStringLiteral("QLineEdit { background: transparent; border: 0; padding: 0;"
                       " margin: 0; color: %1; selection-background-color: %2;"
                       " selection-color: %1; }")
            .arg(air().color(Color::Ink).name(QColor::HexArgb),
                 air().color(Color::Selected).name(QColor::HexArgb)));

    keyHint->setFont(air().font(TextRole::Caption));
    keyHint->setStyleSheet(
        QStringLiteral("QLabel { color: %1; background: %2; border: %3px solid %4;"
                       " border-radius: %5px; padding: 0 %6px; }")
            .arg(ink3.name(), air().color(Color::Surface).name())
            .arg(Stroke::hairline)
            .arg(air().color(Color::LineStrong).name())
            .arg(Radius::small)
            .arg(Space::s6));
    update();
}

bool AirCommandField::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == field && (event->type() == QEvent::FocusIn || event->type() == QEvent::FocusOut)) {
        keyHint->setVisible(field->text().isEmpty() && event->type() == QEvent::FocusOut);
        update();
    }
    return QFrame::eventFilter(watched, event);
}

void AirCommandField::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    const bool focused = field->hasFocus();
    roundedBox(painter, rect(), Radius::field,
               focused ? air().color(Color::Raised) : air().color(Color::Sunken),
               focused ? air().color(Color::Ink) : air().color(Color::Line));
}
