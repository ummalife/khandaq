/*
    Copyright © 2014-2019 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "chatline.h"
#include "chatlinecontent.h"
#include "chatlinecontentproxy.h"
#include "content/airlabel.h"
#include "content/text.h"

#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"

#include <QDebug>
#include <QGraphicsRectItem>
#include <QGraphicsScene>
#include <QPen>
#include <QGuiApplication>
#include <QStringList>
#include <QWidget>

#include <cmath>

ChatLine::ChatLine()
{
}

ChatLine::~ChatLine()
{
    for (ChatLineContent* c : content) {
        if (c->scene())
            c->scene()->removeItem(c);

        delete c;
    }
    if (ground) {
        if (ground->scene()) {
            ground->scene()->removeItem(ground);
        }
        delete ground;
    }
}

void ChatLine::visibilityChanged(bool visible)
{
    if (isVisible != visible) {
        for (ChatLineContent* c : content)
            c->visibilityChanged(visible);
    }

    isVisible = visible;
}

ChatLineContent* ChatLine::getContent(int col) const
{
    if (col < content.size() && col >= 0)
        return content[col];

    return nullptr;
}

ChatLineContent* ChatLine::getContent(QPointF scenePos) const
{
    for (ChatLineContent* c : content) {
        if (c->sceneBoundingRect().contains(scenePos))
            return c;
    }

    return nullptr;
}

void ChatLine::removeFromScene()
{
    for (ChatLineContent* c : content) {
        if (c->scene())
            c->scene()->removeItem(c);
    }
    if (ground && ground->scene()) {
        ground->scene()->removeItem(ground);
    }
}

void ChatLine::addToScene(QGraphicsScene* scene)
{
    if (!scene)
        return;

    for (ChatLineContent* c : content)
        scene->addItem(c);
    if (ground) {
        scene->addItem(ground);
    }
}

void ChatLine::setVisible(bool visible)
{
    for (ChatLineContent* c : content)
        c->setVisible(visible);
    if (ground) {
        ground->setVisible(visible && !collapsed);
    }
}

void ChatLine::selectionCleared()
{
    for (ChatLineContent* c : content)
        c->selectionCleared();
}

void ChatLine::selectionFocusChanged(bool focusIn)
{
    for (ChatLineContent* c : content)
        c->selectionFocusChanged(focusIn);
}

void ChatLine::fontChanged(const QFont& font)
{
    for (ChatLineContent* c : content)
        c->fontChanged(font);
}

void ChatLine::reloadTheme()
{
    for (ChatLineContent* c : content) {
        c->reloadTheme();
    }
    if (ground) {
        ground->setBrush(AirStyle::instance().color(AirTokens::Color::PrivateSoft));
    }
}

int ChatLine::getColumnCount()
{
    return content.size();
}

QString ChatLine::spokenText() const
{
    QStringList words;
    for (const ChatLineContent* const column : content) {
        const QString text = column->getSpokenText().simplified();
        if (!text.isEmpty()) {
            words.append(text);
        }
    }
    return words.join(QStringLiteral(", "));
}

void ChatLine::updateBBox()
{
    bbox.setHeight(0);
    bbox.setWidth(width);

    for (ChatLineContent* c : content)
        bbox.setHeight(qMax(c->sceneBoundingRect().top() - bbox.top() + c->sceneBoundingRect().height(),
                            bbox.height()));
}

QRectF ChatLine::sceneBoundingRect() const
{
    return bbox;
}

void ChatLine::addColumn(ChatLineContent* item, ColumnFormat fmt)
{
    if (!item)
        return;

    format.push_back(fmt);
    content.push_back(item);
    item->setIndex(0, content.size() -1 );
}

void ChatLine::setEntryParts(int quote, int notes)
{
    quoteColumn = quote;
    notesColumn = notes;
}

ChatLineContent* ChatLine::quotePart() const
{
    return quoteColumn >= 0 ? getContent(quoteColumn) : nullptr;
}

ChatLineContent* ChatLine::notesPart() const
{
    return notesColumn >= 0 ? getContent(notesColumn) : nullptr;
}

void ChatLine::setPrivatePart(int note)
{
    privateColumn = note;
    if (note < 0 || ground) {
        return;
    }
    // Behind the selection (-1) and the contents: it takes no clicks and draws only the ground.
    ground = new QGraphicsRectItem();
    ground->setPen(Qt::NoPen);
    ground->setBrush(AirStyle::instance().color(AirTokens::Color::PrivateSoft));
    ground->setZValue(-2.0);
    ground->setAcceptedMouseButtons(Qt::NoButton);
    ground->setAcceptHoverEvents(false);
    if (!content.isEmpty() && content.first()->scene()) {
        content.first()->scene()->addItem(ground);
    }
}

ChatLineContent* ChatLine::privatePart() const
{
    return privateColumn >= 0 ? getContent(privateColumn) : nullptr;
}

void ChatLine::replaceContent(int col, ChatLineContent* lineContent)
{
    if (col >= 0 && col < content.size() && lineContent) {
        QGraphicsScene* scene = content[col]->scene();
        delete content[col];

        content[col] = lineContent;
        lineContent->setIndex(row, col);

        if (scene)
            scene->addItem(content[col]);

        layout(width, bbox.topLeft());
        content[col]->visibilityChanged(isVisible);
        content[col]->update();
    }
}

void ChatLine::setLayout(Layout layout_)
{
    lineLayout = layout_;
}

ChatLine::Layout ChatLine::getLayout() const
{
    return lineLayout;
}

bool ChatLine::setAuthorShown(bool shown)
{
    if (authorShown == shown) {
        return false;
    }
    authorShown = shown;
    return true;
}

bool ChatLine::isAuthorShown() const
{
    return authorShown;
}

bool ChatLine::setCollapsed(bool collapsed_)
{
    if (collapsed == collapsed_) {
        return false;
    }
    collapsed = collapsed_;
    return true;
}

bool ChatLine::isCollapsed() const
{
    return collapsed;
}

/**
 * @brief Places an entry or a day mark of the transcript. The line runs from its start edge, the
 * left one in left-to-right languages: 16 px of padding, the text at most 760 px wide and 16 px of
 * padding at the far end. Wider lines keep the rest as margin. In the text column the author stands
 * above the quote of a reply, 4 px over the text, and the notes of the entry under the text.
 *
 * KHANDAQ (owner, 28.11.2026): no time column before the text and no column for the state mark
 * after it any more. The meta of an entry, "edited" and the time (content 2) and then the mark of
 * ours (content 3) 4 px apart, stands at the far end of the text column: on the last line of the
 * text, on its baseline, when that line leaves 8 px and the meta free at its end; else on a line
 * of its own right under the text. A body that is not words (a file, a call, a place) keeps 8 px
 * clear of the meta, which stands at its foot.
 */
void ChatLine::layoutAir(QPointF scenePos)
{
    using namespace AirTokens;
    const AirStyle& air = AirStyle::instance();
    const bool rightToLeft = QGuiApplication::layoutDirection() == Qt::RightToLeft;

    bbox.setTopLeft(scenePos);
    bbox.setWidth(width);

    if (collapsed) {
        for (ChatLineContent* c : content) {
            c->setVisible(false);
            c->setPos(scenePos);
        }
        if (ground) {
            ground->setVisible(false);
        }
        bbox.setHeight(0);
        return;
    }

    // start: distance from the start edge; top: from the top of the line.
    const auto place = [&](ChatLineContent* c, qreal start, qreal top, qreal itemWidth) {
        c->setVisible(true);
        c->setWidth(static_cast<float>(itemWidth));
        // A file card may come out narrower than its column; it stands at the start edge.
        const qreal x = rightToLeft ? scenePos.x() + width - start - c->boundingRect().width()
                                    : scenePos.x() + start;
        c->setPos(x, scenePos.y() + top);
    };

    const qreal edge = Space::s16;
    const qreal textStart = edge;
    const qreal textWidth = qBound<qreal>(0.0, width - 2 * edge, Size::textMeasure);

    if (lineLayout == Layout::AirDay) {
        place(content[0], textStart, Space::s12, textWidth);
        for (int i = 1; i < content.size(); ++i) {
            content[i]->setVisible(false);
        }
        bbox.setHeight(Space::s12 + air.lineHeight(TextRole::Caption) + Space::s4);
        return;
    }

    // KHANDAQ (beta ideas 2026-11-20): the room iOS keeps over the entry for its unread mark, 8
    // above the words and 4 below.
    if (lineLayout == Layout::AirUnread) {
        place(content[0], textStart, Space::s8, textWidth);
        for (int i = 1; i < content.size(); ++i) {
            content[i]->setVisible(false);
        }
        bbox.setHeight(Space::s8 + air.lineHeight(TextRole::Caption) + Space::s4);
        return;
    }

    const qreal top = Space::s4;
    qreal y = top;
    if (authorShown) {
        // KHANDAQ (0.2.60): the 4 px the comment above always promised: without them the name read as a
        // line of the text.
        place(content[0], textStart, y, textWidth);
        y += content[0]->boundingRect().height() + Space::s4;
    } else {
        content[0]->setVisible(false);
    }

    // A part that takes no room is left out, with its gap.
    const auto placePart = [&](ChatLineContent* part, qreal gap) {
        place(part, textStart, y, textWidth);
        const qreal height = part->boundingRect().height();
        if (height > 0) {
            y += height + gap;
        } else {
            part->setVisible(false);
        }
    };

    // KHANDAQ (26.11.2026): who a private message goes to, before anything of the message.
    ChatLineContent* const privateNote = privatePart();
    if (privateNote) {
        placePart(privateNote, Space::s4);
    }

    ChatLineContent* const quote = quotePart();
    if (quote) {
        placePart(quote, Space::s4);
    }

    // The meta: the time with "edited" before it, then the mark of ours while it shows a glyph.
    auto* const time = qobject_cast<AirLabel*>(content[2]);
    auto* const mark = qobject_cast<AirMark*>(getContent(3));
    const bool markShown = mark && mark->isShown();
    const qreal timeWidth = time ? std::ceil(time->naturalWidth()) : 0.0;
    const qreal timeHeight = time ? time->boundingRect().height() : 0.0;
    const qreal markGap = timeWidth > 0 && markShown ? Space::s4 : 0.0;
    const qreal metaWidth = timeWidth + markGap + (markShown ? Size::mark : 0);
    const qreal metaHeight = qMax<qreal>(timeHeight, markShown ? Size::mark : 0);
    const qreal metaRoom = metaWidth > 0 ? Space::s8 + metaWidth : 0.0;

    ChatLineContent* const body = content[1];
    const Text* const words = qobject_cast<const Text*>(body);
    place(body, textStart, y, words ? textWidth : qMax<qreal>(0.0, textWidth - metaRoom));
    const qreal bodyTop = y;
    const QRectF bodyBox = body->boundingRect();
    y += bodyBox.height();

    // The top of the meta's own box, metaHeight tall: the time and the mark stand in its middle.
    qreal metaTop = y;
    bool metaOnItsLine = true;
    QRectF lastLine;
    qreal lastBaseline = 0.0;
    if (words && words->lastTextLine(&lastLine, &lastBaseline)) {
        // The room the last line leaves at the end of the column, which is its right in a
        // left-to-right interface and its left in a right-to-left one: the item is not mirrored.
        const qreal room = rightToLeft ? lastLine.left() - (bodyBox.width() - textWidth)
                                       : textWidth - lastLine.right();
        if (room >= metaRoom) {
            metaOnItsLine = false;
            const qreal timeTop = time && timeWidth > 0
                                      ? bodyTop + lastBaseline - time->baseline()
                                      : bodyTop + lastLine.center().y() - timeHeight / 2;
            metaTop = timeTop - (metaHeight - timeHeight) / 2;
        }
    } else if (!words && bodyBox.width() <= textWidth - metaRoom) {
        // At the foot of the body, which kept clear of it. A body that could not (a file card at
        // its least width in a narrow window) has the meta on a line of its own under it. A card
        // stands in its proxy with some room under it, which is not the card's.
        const auto* const card = qobject_cast<const ChatLineContentProxy*>(body);
        const qreal foot = card && card->getWidget()
                               ? qMin(y, bodyTop + card->getWidget()->height())
                               : y;
        metaOnItsLine = false;
        metaTop = qMax(bodyTop, foot - metaHeight);
    }
    if (metaOnItsLine) {
        y += metaHeight;
    }
    y = qMax(y, metaTop + metaHeight);

    const qreal metaStart = textStart + textWidth - metaWidth;
    if (time) {
        place(time, metaStart, metaTop + (metaHeight - timeHeight) / 2, timeWidth);
    }
    if (markShown) {
        place(mark, metaStart + timeWidth + markGap, metaTop + (metaHeight - Size::mark) / 2,
              Size::mark);
    } else if (mark) {
        mark->setVisible(false);
    }

    ChatLineContent* const notes = notesPart();
    if (notes) {
        placePart(notes, 0);
    }

    bbox.setHeight(y + Space::s4);
    if (ground) {
        // The scene's own coordinates: the item stays at the origin and takes the entry's box.
        ground->setPos(0, 0);
        ground->setRect(bbox);
        ground->setVisible(true);
    }
}

void ChatLine::layout(qreal w, QPointF scenePos)
{
    if (!content.size())
        return;

    width = w;
    if (lineLayout != Layout::Columns) {
        layoutAir(scenePos);
        return;
    }

    bbox.setTopLeft(scenePos);

    qreal fixedWidth = (content.size() - 1) * columnSpacing;
    qreal varWidth = 0.0; // used for normalisation

    for (int i = 0; i < format.size(); ++i) {
        if (format[i].policy == ColumnFormat::FixedSize)
            fixedWidth += format[i].size;
        else
            varWidth += format[i].size;
    }

    if (varWidth == 0.0)
        varWidth = 1.0;

    qreal leftover = qMax(0.0, width - fixedWidth);

    qreal maxVOffset = 0.0;
    qreal xOffset = 0.0;
    QVector<qreal> xPos(content.size());

    for (int i = 0; i < content.size(); ++i) {
        // calculate the effective width of the current column
        qreal contentWidth;
        if (format[i].policy == ColumnFormat::FixedSize)
            contentWidth = format[i].size;
        else
            contentWidth = format[i].size / varWidth * leftover;

        // set the width of the current column
        content[i]->setWidth(contentWidth);

        // calculate horizontal alignment
        qreal xAlign = 0.0;

        switch (format[i].hAlign) {
        case ColumnFormat::Left:
            break;
        case ColumnFormat::Right:
            xAlign = contentWidth - content[i]->boundingRect().width();
            break;
        case ColumnFormat::Center:
            xAlign = (contentWidth - content[i]->boundingRect().width()) / 2.0;
            break;
        }

        // reposition
        xPos[i] = scenePos.x() + xOffset + xAlign;

        xOffset += contentWidth + columnSpacing;
        maxVOffset = qMax(maxVOffset, content[i]->getAscent());
    }

    for (int i = 0; i < content.size(); ++i) {
        // calculate vertical alignment
        // vertical alignment may depend on width, so we do it in a second pass
        qreal yOffset = maxVOffset - content[i]->getAscent();

        // reposition
        content[i]->setPos(xPos[i], scenePos.y() + yOffset);
    }

    updateBBox();
}

void ChatLine::moveBy(qreal deltaY)
{
    // reposition only
    for (ChatLineContent* c : content)
        c->moveBy(0, deltaY);
    if (ground) {
        ground->moveBy(0, deltaY);
    }

    bbox.moveTop(bbox.top() + deltaY);
}

bool ChatLine::lessThanBSRectTop(const ChatLine::Ptr& lhs, const qreal& rhs)
{
    return lhs->sceneBoundingRect().top() < rhs;
}

bool ChatLine::lessThanBSRectBottom(const ChatLine::Ptr& lhs, const qreal& rhs)
{
    return lhs->sceneBoundingRect().bottom() < rhs;
}
