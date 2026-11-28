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
#include <QGraphicsSceneHoverEvent>
#include <QGuiApplication>
#include <QPainter>
#include <QStyle>
#include <QStyleOptionGraphicsItem>
#include <QWidget>

#include <algorithm>
#include <cmath>
#include <tuple>

using AirTokens::Color;

namespace {
AirStyle& air()
{
    return AirStyle::instance();
}

/** A reaction's chip, as Android draws it: 28 px high, 8 px of padding at either end, 4 px apart. */
constexpr int reactionChipHeight = AirTokens::Size::controlSmall;
constexpr int reactionChipPadding = AirTokens::Space::s8;
constexpr int reactionChipGap = AirTokens::Space::s4;
/** The room between the text of the entry and its chips. */
constexpr int reactionChipsTop = AirTokens::Space::s4;
/** The emoji of a chip: a picture of the pack, or the emoji font at this size, as tall as a line of
 * the caption the count is set in. */
constexpr int reactionEmojiSide = AirTokens::Size::iconSmall;

QFont reactionCountFont()
{
    return air().font(AirTokens::TextRole::Caption);
}

QFont reactionEmojiFont()
{
    QFont font(SmileyPack::emojiFontFamily());
    font.setPixelSize(reactionEmojiSide);
    return font;
}

/** The room of a reaction's emoji: the picture, or what the emoji font takes, at least as wide. */
qreal reactionEmojiWidth(const QString& emoji, bool picture)
{
    if (picture) {
        return reactionEmojiSide;
    }
    return qMax<qreal>(reactionEmojiSide,
                       std::ceil(QFontMetricsF(reactionEmojiFont()).horizontalAdvance(emoji)));
}

/** The room of a reaction's count with the gap before it; none for a reaction of one person. */
qreal reactionCountWidth(int count)
{
    if (count <= 1) {
        return 0;
    }
    return AirTokens::Space::s4
           + std::ceil(QFontMetricsF(reactionCountFont()).horizontalAdvance(QString::number(count)));
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

bool AirLabel::setLead(const QString& lead_)
{
    if (lead == lead_) {
        return false;
    }
    prepareGeometryChange();
    lead = lead_;
    update();
    return true;
}

QString AirLabel::getLead() const
{
    return lead;
}

qreal AirLabel::naturalWidth() const
{
    const QFontMetricsF metrics(font());
    const qreal own = metrics.horizontalAdvance(text);
    return lead.isEmpty() ? own : metrics.horizontalAdvance(lead) + AirTokens::Space::s4 + own;
}

qreal AirLabel::baseline() const
{
    // QPainter::drawText centres one line by its ascent and descent, without the leading.
    const QFontMetricsF metrics(font());
    return (boundingRect().height() - metrics.ascent() - metrics.descent()) / 2.0 + metrics.ascent();
}

QRectF AirLabel::textRect() const
{
    const QRectF box = boundingRect();
    const qreal shown = std::min(naturalWidth(), box.width());
    // At the start edge, as paint() aligns it.
    const bool rightToLeft = QGuiApplication::layoutDirection() == Qt::RightToLeft;
    return QRectF(rightToLeft ? box.right() - shown : box.left(), box.top(), shown, box.height());
}

void AirLabel::setOpensMenu(bool opens_)
{
    if (opens == opens_) {
        return;
    }
    opens = opens_;
    setAcceptHoverEvents(opens);
    if (!opens) {
        unsetCursor();
    }
}

bool AirLabel::opensMenu() const
{
    return opens;
}

void AirLabel::hoverMoveEvent(QGraphicsSceneHoverEvent* event)
{
    // A hand over the words only: the rest of the line is the text's to select.
    if (opens && textRect().contains(event->pos())) {
        setCursor(Qt::PointingHandCursor);
    } else {
        unsetCursor();
    }
}

void AirLabel::hoverLeaveEvent(QGraphicsSceneHoverEvent* event)
{
    std::ignore = event;
    unsetCursor();
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
    const QFontMetricsF metrics(labelFont);
    painter->setFont(labelFont);
    painter->setPen(color);
    // As the names in the list: at the start edge of the interface, written in the label's own
    // direction. The alignment is absolute, so the view's painter does not mirror it once more.
    const Qt::Alignment start =
        QStyle::visualAlignment(QGuiApplication::layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter);
    const int flags = static_cast<int>(start) | Qt::TextSingleLine;
    QRectF box = boundingRect();
    if (!lead.isEmpty()) {
        // The lead at the start edge, the text after it and the gap.
        const bool rightToLeft = QGuiApplication::layoutDirection() == Qt::RightToLeft;
        const qreal leadRoom =
            std::min(metrics.horizontalAdvance(lead) + AirTokens::Space::s4, box.width());
        const QRectF leadBox(rightToLeft ? box.right() - leadRoom : box.left(), box.top(), leadRoom,
                             box.height());
        AirPaint::ownText(*painter, leadBox.toRect(), flags, lead);
        box.setWidth(box.width() - leadRoom);
        if (!rightToLeft) {
            box.moveLeft(leadBox.right());
        }
    }
    const QString shown = metrics.elidedText(text, Qt::ElideRight, box.width());
    AirPaint::ownText(*painter, box.toRect(), flags, shown);
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

bool AirMark::isShown() const
{
    return !Air::statusIconName(status).isEmpty();
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
    case Air::EntryStatus::Seen:
        color = air().seenColor();
        break;
    case Air::EntryStatus::Failed:
        color = air().color(Color::Danger);
        break;
    case Air::EntryStatus::None:
    case Air::EntryStatus::Sent:
    case Air::EntryStatus::Delivered:
        // KHANDAQ (0.2.57): sent and delivered in the ink of the time beside them, as on the
        // phones, so that only the teal of a message seen stands out. Only our messages to a
        // person reach these: a text in a group shows a clock or an alert at most, as before
        // (Air::groupTextStatus).
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

namespace {
/** The lock before the note of a private entry: as tall as a line of the caption it stands in. */
constexpr int privateLockSide = AirTokens::Size::iconSmall;
} // namespace

AirPrivateNote::AirPrivateNote(const QString& note_)
    : note{note_}
{
}

void AirPrivateNote::fit()
{
    using namespace AirTokens;
    const int room = static_cast<int>(width) - privateLockSide - Space::s4;
    lines = room > 0 ? AirPaint::linesOf(note, air().font(TextRole::Caption), room, 2)
                     : QStringList();
}

void AirPrivateNote::setWidth(float width_)
{
    prepareGeometryChange();
    width = width_;
    fit();
}

QRectF AirPrivateNote::boundingRect() const
{
    if (lines.isEmpty()) {
        return QRectF();
    }
    const qreal caption = air().lineHeight(AirTokens::TextRole::Caption);
    return QRectF(0, 0, width, qMax<qreal>(privateLockSide, lines.size() * caption));
}

void AirPrivateNote::paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget)
{
    using namespace AirTokens;
    std::ignore = option;
    if (lines.isEmpty()) {
        return;
    }
    const bool rightToLeft = QGuiApplication::layoutDirection() == Qt::RightToLeft;
    const QColor tint = air().color(Color::Private);
    const qreal dpr = widget ? widget->devicePixelRatioF() : qApp->devicePixelRatio();
    const qreal caption = air().lineHeight(TextRole::Caption);
    const qreal lockX = rightToLeft ? width - privateLockSide : 0;
    painter->drawPixmap(QPointF(lockX, (caption - privateLockSide) / 2.0),
                        air().icon(QStringLiteral("lock"), tint, privateLockSide, dpr));

    painter->setFont(air().font(TextRole::Caption));
    painter->setPen(tint);
    const int textStart = privateLockSide + Space::s4;
    const int room = static_cast<int>(width) - textStart;
    const int flags = static_cast<int>(startEdge()) | Qt::TextSingleLine;
    qreal y = 0;
    for (const QString& line : lines) {
        const QRect box(rightToLeft ? 0 : textStart, static_cast<int>(y), room,
                        static_cast<int>(caption));
        AirPaint::ownText(*painter, box, flags, line);
        y += caption;
    }
}

QString AirPrivateNote::getText() const
{
    return note;
}

QString AirPrivateNote::getSpokenText() const
{
    return note;
}

qreal AirPrivateNote::getAscent() const
{
    return QFontMetricsF(air().font(AirTokens::TextRole::Caption)).ascent();
}

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
    // KHANDAQ (28.11.2026): the quote of a place, as every client sends it, shows its name and
    // coordinates.
    lines = AirPaint::linesOf(AirStrings::shownWords(preview), words, room, 2);
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
    return preview.isEmpty() ? QString()
                             : AirStrings::spokenQuote(name, AirStrings::shownWords(preview));
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
    // KHANDAQ (owner, 28.11.2026): "edited" takes no line here any more: it stands before the
    // time, at the end of the entry.
    return shown.isEmpty() ? 0 : reactionChipsTop + reactionChipHeight;
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

QVector<QRectF> AirEntryNotes::plates() const
{
    QVector<QRectF> result;
    const bool rightToLeft = QGuiApplication::layoutDirection() == Qt::RightToLeft;
    qreal taken = 0;
    for (const Shown& reaction : shown) {
        const qreal plate = reactionChipPadding + reactionEmojiWidth(reaction.emoji, !reaction.picture.isNull())
                            + reactionCountWidth(reaction.count) + reactionChipPadding;
        if (taken + plate > width) {
            break;
        }
        const qreal start = rightToLeft ? width - taken - plate : taken;
        result.append(QRectF(start, reactionChipsTop, plate, reactionChipHeight));
        taken += plate + reactionChipGap;
    }
    return result;
}

void AirEntryNotes::paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget)
{
    using namespace AirTokens;
    std::ignore = option;
    std::ignore = widget;
    if (!shown.isEmpty()) {
        const bool rightToLeft = QGuiApplication::layoutDirection() == Qt::RightToLeft;
        const QColor ink = air().color(Color::Ink);
        const QVector<QRectF> rects = plates();
        painter->save();
        painter->setRenderHint(QPainter::Antialiasing, true);
        for (int i = 0; i < rects.size(); ++i) {
            const Shown& reaction = shown.at(i);
            const QRectF plate = rects.at(i);
            AirPaint::box(*painter, plate, plate.height() / 2, air().color(Color::Raised),
                          air().color(Color::Line));
            // The emoji at the start edge of the plate, the count after it.
            const qreal emojiWidth = reactionEmojiWidth(reaction.emoji, !reaction.picture.isNull());
            const qreal countWidth = reactionCountWidth(reaction.count);
            const qreal emojiX = rightToLeft ? plate.right() - reactionChipPadding - emojiWidth
                                             : plate.left() + reactionChipPadding;
            const QRectF emojiBox(emojiX, plate.top(), emojiWidth, plate.height());
            if (!reaction.picture.isNull()) {
                const QRect pictureBox(qRound(emojiBox.center().x() - reactionEmojiSide / 2.0),
                                       qRound(plate.center().y() - reactionEmojiSide / 2.0),
                                       reactionEmojiSide, reactionEmojiSide);
                reaction.picture.paint(painter, pictureBox);
            } else {
                // A colour glyph takes no pen; a font without one draws it in ink.
                painter->setFont(reactionEmojiFont());
                painter->setPen(ink);
                painter->drawText(emojiBox, Qt::AlignCenter | Qt::TextSingleLine, reaction.emoji);
            }
            if (countWidth > 0) {
                const qreal numberWidth = countWidth - Space::s4;
                const qreal countX = rightToLeft ? emojiBox.left() - countWidth : emojiBox.right() + Space::s4;
                painter->setFont(reactionCountFont());
                painter->setPen(ink);
                painter->drawText(QRectF(countX, plate.top(), numberWidth, plate.height()),
                                  Qt::AlignAbsolute | Qt::AlignLeft | Qt::AlignVCenter | Qt::TextSingleLine,
                                  QString::number(reaction.count));
            }
        }
        painter->restore();
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
