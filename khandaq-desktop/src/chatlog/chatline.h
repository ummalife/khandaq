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

#pragma once

#include <QPointF>
#include <QString>
#include <QRectF>
#include <QVector>
#include <memory>

class ChatWidget;
class ChatLineContent;
class QGraphicsScene;
class QStyleOptionGraphicsItem;
class QFont;

struct ColumnFormat
{
    enum Policy
    {
        FixedSize,
        VariableSize,
    };

    enum Align
    {
        Left,
        Center,
        Right,
    };

    ColumnFormat()
    {
    }
    ColumnFormat(qreal s, Policy p, Align halign = Left)
        : size(s)
        , policy(p)
        , hAlign(halign)
    {
    }

    qreal size = 1.0;
    Policy policy = VariableSize;
    Align hAlign = Left;
};

using ColumnFormats = QVector<ColumnFormat>;

class ChatLine
{
public:
    using Ptr = std::shared_ptr<ChatLine>;

    /** How the contents of the line are placed. */
    enum class Layout
    {
        /** The classic row of columns, sized by their ColumnFormat. */
        Columns,
        /**
         * An entry of the transcript (design/system kq-entry): the time at the start, the author
         * above the text, the state mark after the text. Contents: 0 the author, 1 the text, 2 the
         * time, and 3 the state mark when there is one; then the parts setEntryParts names.
         */
        AirEntry,
        /** A day mark, set where the text of the entries starts: content 0. */
        AirDay,
    };

    ChatLine();
    virtual ~ChatLine();

    QRectF sceneBoundingRect() const;

    void replaceContent(int col, ChatLineContent* lineContent);
    void layout(qreal width, QPointF scenePos);
    void setLayout(Layout layout);
    Layout getLayout() const;
    /** An entry shows its author only where a run of one author starts. Returns whether it changed. */
    bool setAuthorShown(bool shown);
    bool isAuthorShown() const;
    /**
     * A collapsed line takes no height and shows nothing: an item the log does not show. Returns
     * whether it changed.
     */
    bool setCollapsed(bool collapsed);
    bool isCollapsed() const;
    void moveBy(qreal deltaY);
    void removeFromScene();
    void addToScene(QGraphicsScene* scene);
    void setVisible(bool visible);
    void selectionCleared();
    void selectionFocusChanged(bool focusIn);
    void fontChanged(const QFont& font);
    void reloadTheme();

    int getColumnCount();

    ChatLineContent* getContent(int col) const;
    ChatLineContent* getContent(QPointF scenePos) const;
    /** What a screen reader says for the line: here the words of its contents, in order. */
    virtual QString spokenText() const;

    bool isOverSelection(QPointF scenePos);

    // comparators
    static bool lessThanBSRectTop(const ChatLine::Ptr& lhs, const qreal& rhs);
    static bool lessThanBSRectBottom(const ChatLine::Ptr& lhs, const qreal& rhs);

protected:
    friend class ChatWidget;

    QPointF mapToContent(ChatLineContent* c, QPointF pos);

    void addColumn(ChatLineContent* item, ColumnFormat fmt);
    /**
     * Where an entry keeps what it shows besides its text (stage 3, item 10): the quote of the
     * message it answers, above the text, and its notes (the reactions, "edited") under it. -1 for
     * a part it does not have; a part that takes no room is left out.
     */
    void setEntryParts(int quote, int notes);
    ChatLineContent* quotePart() const;
    ChatLineContent* notesPart() const;
    void updateBBox();
    void layoutAir(QPointF scenePos);
    void visibilityChanged(bool visible);

private:
    int row = -1;
    QVector<ChatLineContent*> content;
    QVector<ColumnFormat> format;
    qreal width = 100.0;
    qreal columnSpacing = 15.0;
    QRectF bbox;
    bool isVisible = false;
    Layout lineLayout = Layout::Columns;
    bool authorShown = true;
    bool collapsed = false;
    int quoteColumn = -1;
    int notesColumn = -1;
};
