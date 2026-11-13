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

#include "airlabel.h"

#include "src/persistence/smileypack.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"

#include <QFontMetricsF>
#include <QGuiApplication>
#include <QPainter>
#include <QStyle>
#include <QStyleOptionGraphicsItem>
#include <QWidget>

#include <cmath>
#include <tuple>

using AirTokens::Color;

namespace {
AirStyle& air()
{
    return AirStyle::instance();
}
} // namespace

AirLabel::AirLabel(const QString& text_, AirTokens::TextRole role_, Ink ink_, int weight_,
                   bool copied_, int tone_)
    : text{text_}
    , role{role_}
    , ink{ink_}
    , weight{weight_}
    , copied{copied_}
    , tone{tone_}
{
}

void AirLabel::setText(const QString& text_)
{
    if (text == text_) {
        return;
    }
    prepareGeometryChange();
    text = text_;
    update();
}

QFont AirLabel::font() const
{
    QFont result = air().font(role);
    if (weight > 0) {
        result.setWeight(weight);
    }
    return result;
}

qreal AirLabel::naturalWidth() const
{
    return QFontMetricsF(font()).horizontalAdvance(text);
}

void AirLabel::setWidth(float width_)
{
    prepareGeometryChange();
    width = width_;
}

QRectF AirLabel::boundingRect() const
{
    return QRectF(0, 0, width, air().lineHeight(role));
}

void AirLabel::paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget)
{
    std::ignore = option;
    std::ignore = widget;

    QColor color;
    switch (ink) {
    case Ink::Ink:
        color = air().color(Color::Ink);
        break;
    case Ink::Ink2:
        color = air().color(Color::Ink2);
        break;
    case Ink::Ink3:
        color = air().color(Color::Ink3);
        break;
    case Ink::Tone:
        color = air().tone(tone);
        break;
    }

    const QFont labelFont = font();
    painter->setFont(labelFont);
    painter->setPen(color);
    const QRectF box = boundingRect();
    const QString shown = QFontMetricsF(labelFont).elidedText(text, Qt::ElideRight, box.width());
    // As the names in the list: at the start edge of the interface, written in the label's own
    // direction. The alignment is absolute, so the view's painter does not mirror it once more.
    const Qt::Alignment start =
        QStyle::visualAlignment(QGuiApplication::layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter);
    AirPaint::ownText(*painter, box.toRect(), static_cast<int>(start) | Qt::TextSingleLine, shown);
}

QString AirLabel::getText() const
{
    return copied ? text : QString();
}

QString AirLabel::getSpokenText() const
{
    return text;
}

qreal AirLabel::getAscent() const
{
    return QFontMetricsF(font()).ascent();
}

AirMark::AirMark(Air::EntryStatus status_)
    : status{status_}
{
}

void AirMark::setStatus(Air::EntryStatus status_)
{
    if (status == status_) {
        return;
    }
    status = status_;
    update();
}

Air::EntryStatus AirMark::getStatus() const
{
    return status;
}

void AirMark::setWidth(float width)
{
    std::ignore = width;
}

QRectF AirMark::boundingRect() const
{
    return QRectF(0, 0, AirTokens::Size::mark, AirTokens::Size::mark);
}

void AirMark::paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget)
{
    std::ignore = option;
    const QString icon = Air::statusIconName(status);
    if (icon.isEmpty()) {
        return;
    }

    QColor color = air().color(Color::Ink3);
    qreal opacity = 1.0;
    switch (status) {
    case Air::EntryStatus::Transit:
        color = air().color(Color::Transit);
        break;
    case Air::EntryStatus::Sending:
        // kq-st-sending: the faint clock of a message leaving right now.
        opacity = 0.45;
        break;
    case Air::EntryStatus::Delivered:
        color = air().color(Color::Ink);
        break;
    case Air::EntryStatus::Failed:
        color = air().color(Color::Danger);
        break;
    case Air::EntryStatus::None:
    case Air::EntryStatus::Sent:
        break;
    }

    const qreal dpr = widget ? widget->devicePixelRatioF() : qApp->devicePixelRatio();
    const int size = AirTokens::Size::iconSmall;
    const QPixmap glyph = air().icon(icon, color, size, dpr);
    const qreal inset = (AirTokens::Size::mark - size) / 2.0;
    painter->save();
    painter->setOpacity(painter->opacity() * opacity);
    painter->drawPixmap(QPointF(inset, inset), glyph);
    painter->restore();
}

AirCallLine::AirCallLine(const QString& text_, bool answered_)
    : text{text_}
    , answered{answered_}
{
}

void AirCallLine::setText(const QString& text_)
{
    if (text == text_) {
        return;
    }
    text = text_;
    update();
}

void AirCallLine::setWidth(float width_)
{
    prepareGeometryChange();
    width = width_;
}

QRectF AirCallLine::boundingRect() const
{
    return QRectF(0, 0, width,
                  qMax<qreal>(AirTokens::Size::icon, air().lineHeight(AirTokens::TextRole::Body)));
}

void AirCallLine::paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget)
{
    std::ignore = option;
    const QRectF box = boundingRect();
    const bool rightToLeft = QGuiApplication::layoutDirection() == Qt::RightToLeft;
    const int glyph = AirTokens::Size::icon;
    const qreal gap = AirTokens::Space::s8;
    const qreal dpr = widget ? widget->devicePixelRatioF() : qApp->devicePixelRatio();
    const qreal glyphX = rightToLeft ? box.right() - glyph : box.left();
    painter->drawPixmap(QPointF(glyphX, box.top() + (box.height() - glyph) / 2.0),
                        air().icon(QStringLiteral("phone"), air().color(Color::Ink2), glyph, dpr));

    const QFont font = air().font(AirTokens::TextRole::Body);
    painter->setFont(font);
    painter->setPen(air().color(answered ? Color::Ink : Color::Ink2));
    const QRectF words =
        rightToLeft
            ? QRectF(box.left(), box.top(), box.width() - glyph - gap, box.height())
            : QRectF(box.left() + glyph + gap, box.top(), box.width() - glyph - gap, box.height());
    const QString shown = QFontMetricsF(font).elidedText(text, Qt::ElideRight, words.width());
    const Qt::Alignment start =
        QStyle::visualAlignment(QGuiApplication::layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter);
    painter->drawText(words, static_cast<int>(start) | Qt::TextSingleLine, shown);
}

QString AirCallLine::getText() const
{
    return text;
}

qreal AirCallLine::getAscent() const
{
    return QFontMetricsF(air().font(AirTokens::TextRole::Body)).ascent();
}

namespace {
/** The start edge of the interface as an absolute alignment, so the view does not mirror it again. */
Qt::Alignment startEdge()
{
    return QStyle::visualAlignment(QGuiApplication::layoutDirection(),
                                   Qt::AlignLeft | Qt::AlignVCenter);
}

QFont quoteNameFont()
{
    QFont font = air().font(AirTokens::TextRole::Caption);
    font.setWeight(QFont::DemiBold);
    return font;
}
} // namespace

AirQuote::AirQuote(const QString& name_, const QString& preview_)
    : name{name_}
    , preview{preview_}
{
}

bool AirQuote::setQuote(const QString& name_, const QString& preview_)
{
    if (name == name_ && preview == preview_) {
        return false;
    }
    const QRectF before = boundingRect();
    prepareGeometryChange();
    name = name_;
    preview = preview_;
    fit();
    update();
    return boundingRect() != before;
}

void AirQuote::fit()
{
    using namespace AirTokens;
    lines.clear();
    plateWidth = 0;
    if (preview.isEmpty() || width <= 2 * Space::s8) {
        return;
    }
    const int room = static_cast<int>(width) - 2 * Space::s8;
    const QFont words = air().font(TextRole::Caption);
    lines = AirPaint::linesOf(preview, words, room, 2);
    const QFontMetricsF wordsMetrics(words);
    qreal widest = QFontMetricsF(quoteNameFont()).horizontalAdvance(AirStrings::replyName(name));
    for (const QString& line : lines) {
        widest = qMax(widest, wordsMetrics.horizontalAdvance(line));
    }
    plateWidth = qMin<qreal>(width, std::ceil(widest) + 2 * Space::s8);
}

void AirQuote::setWidth(float width_)
{
    prepareGeometryChange();
    width = width_;
    fit();
}

QRectF AirQuote::boundingRect() const
{
    using namespace AirTokens;
    if (lines.isEmpty()) {
        return QRectF();
    }
    const qreal caption = air().lineHeight(TextRole::Caption);
    return QRectF(0, 0, plateWidth,
                  Space::s4 + caption + Space::s2 + lines.size() * caption + Space::s4);
}

void AirQuote::paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget)
{
    using namespace AirTokens;
    std::ignore = option;
    std::ignore = widget;
    if (lines.isEmpty()) {
        return;
    }
    const QRectF plate = boundingRect();
    painter->save();
    painter->setRenderHint(QPainter::Antialiasing);
    AirPaint::box(*painter, plate, Radius::small, air().color(Color::Sunken), QColor());
    painter->restore();

    const qreal caption = air().lineHeight(TextRole::Caption);
    const int room = static_cast<int>(plate.width()) - 2 * Space::s8;
    const int flags = static_cast<int>(startEdge()) | Qt::TextSingleLine;
    painter->setPen(air().color(Color::Ink2));

    const QFont nameFont = quoteNameFont();
    painter->setFont(nameFont);
    const QString shownName =
        QFontMetricsF(nameFont).elidedText(AirStrings::replyName(name), Qt::ElideRight, room);
    qreal y = Space::s4;
    AirPaint::ownText(*painter, QRect(Space::s8, static_cast<int>(y), room, static_cast<int>(caption)),
                      flags, shownName);

    painter->setFont(air().font(TextRole::Caption));
    y += caption + Space::s2;
    for (const QString& line : lines) {
        AirPaint::ownText(*painter,
                          QRect(Space::s8, static_cast<int>(y), room, static_cast<int>(caption)),
                          flags, line);
        y += caption;
    }
}

QString AirQuote::getSpokenText() const
{
    return preview.isEmpty() ? QString() : AirStrings::spokenQuote(name, preview);
}

qreal AirQuote::getAscent() const
{
    return QFontMetricsF(air().font(AirTokens::TextRole::Caption)).ascent();
}

AirEntryNotes::AirEntryNotes(const Air::EntryMarks& marks, const SmileyPack* smileys_)
    : smileys{smileys_}
{
    setNotes(marks);
}

bool AirEntryNotes::setNotes(const Air::EntryMarks& marks)
{
    if (edited == marks.edited && reactions == marks.reactions) {
        return false;
    }
    const qreal before = height();
    prepareGeometryChange();
    edited = marks.edited;
    reactions = marks.reactions;
    shown.clear();
    for (const Air::Reaction& reaction : reactions) {
        shown.append({reaction.emoji, pictureOf(reaction.emoji), reaction.actors.size()});
    }
    update();
    return height() != before;
}

QIcon AirEntryNotes::pictureOf(const QString& emoji) const
{
    if (!smileys) {
        return QIcon();
    }
    std::shared_ptr<QIcon> picture = smileys->getAsIcon(emoji);
    // The pack knows an emoji without the selector that asks for its colour picture ("❤️" is "❤"
    // there), as messages find it.
    const QChar selector(0xFE0F);
    if ((!picture || picture->isNull()) && emoji.contains(selector)) {
        QString bare = emoji;
        bare.remove(selector);
        picture = smileys->getAsIcon(bare);
    }
    return picture ? *picture : QIcon();
}

qreal AirEntryNotes::height() const
{
    using namespace AirTokens;
    qreal total = 0;
    if (!shown.isEmpty()) {
        total += air().lineHeight(TextRole::Label);
    }
    if (edited) {
        total += 1 + air().lineHeight(TextRole::Caption);
    }
    return total;
}

void AirEntryNotes::setWidth(float width_)
{
    prepareGeometryChange();
    width = width_;
}

QRectF AirEntryNotes::boundingRect() const
{
    const qreal total = height();
    return total > 0 ? QRectF(0, 0, width, total) : QRectF();
}

void AirEntryNotes::paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget)
{
    using namespace AirTokens;
    std::ignore = option;
    std::ignore = widget;
    const int flags = static_cast<int>(startEdge()) | Qt::TextSingleLine;
    const int room = static_cast<int>(width);
    qreal y = 0;
    if (!shown.isEmpty()) {
        QFont font = air().font(TextRole::Label);
        font.setWeight(QFont::Normal);
        painter->setFont(font);
        painter->setPen(air().color(Color::Ink2));
        const QFontMetricsF metrics(font);
        const qreal line = air().lineHeight(TextRole::Label);
        // A picture as tall as the text; the reactions follow each other from the start edge, the
        // count after its emoji, and stop where the column ends. Every place is worked out here,
        // so the text keeps to the left of its own place in both directions.
        const int lineHeight = static_cast<int>(line);
        const int side = qMin(lineHeight, static_cast<int>(std::round(metrics.height())));
        const bool rightToLeft = QGuiApplication::layoutDirection() == Qt::RightToLeft;
        const int textFlags = Qt::AlignLeft | Qt::AlignAbsolute | Qt::AlignVCenter | Qt::TextSingleLine;
        qreal taken = 0;
        for (const Shown& reaction : shown) {
            const QString count = reaction.count > 1 ? QString::number(reaction.count) : QString();
            const qreal emojiWidth = reaction.picture.isNull()
                                         ? std::ceil(metrics.horizontalAdvance(reaction.emoji))
                                         : side;
            const qreal countWidth =
                count.isEmpty() ? 0 : Space::s4 + std::ceil(metrics.horizontalAdvance(count));
            const qreal reactionWidth = emojiWidth + countWidth;
            if (taken + reactionWidth > width) {
                break;
            }
            const qreal start = rightToLeft ? width - taken - reactionWidth : taken;
            const int emojiX = static_cast<int>(rightToLeft ? start + countWidth : start);
            if (reaction.picture.isNull()) {
                AirPaint::ownText(*painter, QRect(emojiX, 0, static_cast<int>(emojiWidth), lineHeight),
                                  textFlags, reaction.emoji);
            } else {
                reaction.picture.paint(painter, QRect(emojiX, (lineHeight - side) / 2, side, side));
            }
            if (!count.isEmpty()) {
                const int countX =
                    static_cast<int>(rightToLeft ? start : start + emojiWidth + Space::s4);
                AirPaint::ownText(*painter, QRect(countX, 0, static_cast<int>(countWidth), lineHeight),
                                  textFlags, count);
            }
            taken += reactionWidth + Space::s12;
        }
        y += line;
    }
    if (edited) {
        const QFont font = air().font(TextRole::Caption);
        painter->setFont(font);
        painter->setPen(air().color(Color::Ink3));
        const qreal line = air().lineHeight(TextRole::Caption);
        y += 1;
        AirPaint::ownText(*painter, QRect(0, static_cast<int>(y), room, static_cast<int>(line)),
                          flags, AirStrings::text(AirStrings::Text::MessageEdited));
    }
}

QString AirEntryNotes::getSpokenText() const
{
    Air::EntryMarks marks;
    marks.reactions = reactions;
    return AirStrings::spokenNotes(edited, marks.reactionsText());
}

qreal AirEntryNotes::getAscent() const
{
    return QFontMetricsF(air().font(AirTokens::TextRole::Label)).ascent();
}
