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

#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"

#include <QDebug>
#include <QGraphicsScene>
#include <QGuiApplication>
#include <QStringList>

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
}

void ChatLine::addToScene(QGraphicsScene* scene)
{
    if (!scene)
        return;

    for (ChatLineContent* c : content)
        scene->addItem(c);
}

void ChatLine::setVisible(bool visible)
{
    for (ChatLineContent* c : content)
        c->setVisible(visible);
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
 * left one in left-to-right languages: 16 px of padding, the time column, a gap of 10, the text at
 * most 760 px wide, a gap of 10 and the 18 px state mark. Wider lines keep the rest as margin. In
 * the text column the author stands above the quote of a reply, 4 px over the text, and the notes
 * of the entry under the text.
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
    const qreal textStart = edge + Size::timeColumn + Space::s10;
    const qreal markRoom = Space::s10 + Size::mark;
    const qreal textWidth = qBound<qreal>(0.0, width - textStart - edge - markRoom, Size::textMeasure);

    if (lineLayout == Layout::AirDay) {
        place(content[0], textStart, Space::s12, textWidth);
        for (int i = 1; i < content.size(); ++i) {
            content[i]->setVisible(false);
        }
        bbox.setHeight(Space::s12 + air.lineHeight(TextRole::Caption) + Space::s4);
        return;
    }

    const qreal top = Space::s4;
    qreal y = top;
    if (authorShown) {
        place(content[0], textStart, y, textWidth);
        y += content[0]->boundingRect().height();
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

    ChatLineContent* const quote = quotePart();
    if (quote) {
        placePart(quote, Space::s4);
    }

    place(content[1], textStart, y, textWidth);
    y += content[1]->boundingRect().height();

    ChatLineContent* const notes = notesPart();
    if (notes) {
        placePart(notes, 0);
    }

    place(content[2], edge, top + Space::s2, Size::timeColumn);

    if (content.size() > 3 && quoteColumn != 3 && notesColumn != 3) {
        place(content[3], textStart + textWidth + Space::s10, top, Size::mark);
    }

    bbox.setHeight(y + Space::s4);
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
