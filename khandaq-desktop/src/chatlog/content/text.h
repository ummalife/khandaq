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

#include "../chatlinecontent.h"
#include "src/widget/style.h"

#include <QFont>

class QTextDocument;
class DocumentCache;
class Settings;

class Text : public ChatLineContent
{
    Q_OBJECT

public:
    enum TextType
    {
        NORMAL,
        ACTION,
        CUSTOM
    };

    Text(DocumentCache& documentCache, Settings& settings, Style& style, const QColor& custom,
        const QString& txt = "", const QFont& font = QFont(),
        bool enableElide = false, const QString& rawText = QString(),
        const TextType& type = NORMAL);
    Text(DocumentCache& documentCache, Settings& settings, Style& style, const QString& txt = "", const QFont& font = QFont(),
        bool enableElide = false, const QString& rawText = QString(),
        const TextType& type = NORMAL);
    virtual ~Text();

    void setText(const QString& txt);
    void selectText(const QString& txt, const std::pair<int, int>& point);
    void selectText(const QRegularExpression& exp, const std::pair<int, int>& point);
    void deselectText();

    void setWidth(float width) final;
    /** Room around the text inside its box; the transcript sets 0 so the text starts where the
     * author's name does. */
    void setDocumentMargin(qreal margin);
    /** Sets the lines at the start edge of the interface, whatever the direction of their own
     * words; the service lines of the transcript have them so. */
    void setStartAligned(bool startAligned);
    /**
     * KHANDAQ (owner, 28.11.2026): sets each paragraph at the start of its own direction, as the
     * phones do, and one without a letter of either direction (figures, emoji) as the interface
     * runs; the words of a message have them so since the time column before them went.
     */
    void setOwnDirection(bool ownDirection);

    void selectionMouseMove(QPointF scenePos) final;
    void selectionStarted(QPointF scenePos) final;
    void selectionCleared() final;
    void selectionDoubleClick(QPointF scenePos) final;
    void selectionTripleClick(QPointF scenePos) final;
    void selectionFocusChanged(bool focusIn) final;
    bool isOverSelection(QPointF scenePos) const final;
    QString getSelectedText() const final;
    void fontChanged(const QFont& font) final;

    QRectF boundingRect() const final;
    void paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget) final;

    void visibilityChanged(bool visible) final;
    void reloadTheme() final;

    qreal getAscent() const final;
    /**
     * KHANDAQ (owner, 28.11.2026): the last line as it is laid out, for the time and the mark of
     * an entry to stand on (ChatLine::layoutAir): box is the room its words take (where it starts
     * and ends by its alignment), baseline the height of its baseline, both in the coordinates of
     * this item. False while the text has not been laid out.
     */
    bool lastTextLine(QRectF* box, qreal* baseline) const;
    void mousePressEvent(QGraphicsSceneMouseEvent* event) final;
    void mouseReleaseEvent(QGraphicsSceneMouseEvent* event) final;
    void hoverMoveEvent(QGraphicsSceneHoverEvent* event) final;

    QString getText() const final;
    QString getLinkAt(QPointF scenePos) const;
    /**
     * KHANDAQ (26.11.2026): the target of the link right under scenePos, as the pointer finds it;
     * empty where there is none.
     */
    QString anchorAt(QPointF scenePos) const;

protected:
    // dynamic resource management
    void regenerate();
    void freeResources();

    virtual QSizeF idealSize();
    int cursorFromPos(QPointF scenePos, bool fuzzy = true) const;
    int getSelectionEnd() const;
    int getSelectionStart() const;
    bool hasSelection() const;
    QString extractSanitizedText(int from, int to) const;
    QString extractImgTooltip(int pos) const;

    QTextDocument* doc = nullptr;
    QSizeF size;
    qreal width = 0.0;

private:
    void selectText(QTextCursor& cursor, const std::pair<int, int>& point);
    QColor textColor() const;

    QString text;
    QString rawText;
    QString selectedText;
    bool keepInMemory = false;
    bool elide = false;
    bool dirty = false;
    bool selectionHasFocus = true;
    int selectionEnd = -1;
    int selectionAnchor = -1;
    qreal ascent = 0.0;
    /**
     * The last line as the document laid it out, kept as the size is: the document goes back to
     * the cache while the line is out of view, and the transcript lays out lines it does not show.
     */
    QRectF lastLineBox;
    qreal lastLineBaseline = 0.0;
    /** QTextDocument's own default. */
    qreal documentMargin = 4.0;
    /** Off: each paragraph stands by its own direction, as QTextOption has it. */
    bool startAligned = false;
    bool ownDirection = false;
    QFont defFont;
    TextType textType;
    QColor color;
    QColor customColor;
    DocumentCache& documentCache;
    Settings& settings;
    QString defStyleSheet;
    Style& style;
};
