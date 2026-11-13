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

#pragma once

#include "src/model/air/airmodel.h"

#include <QAbstractScrollArea>
#include <QEasingCurve>
#include <QElapsedTimer>
#include <QHash>
#include <QPixmap>
#include <QStringList>

#include <functional>

class AirButton;
class QTimer;

/**
 * The list column of the Air screen: request cards, then the sections with their counts, the rows
 * and, while searching, the matching messages. Painted from an Air::Snapshot.
 *
 * Rows keep their identity from one snapshot to the next, so a row that changes place slides there
 * and one that changes only its words is simply repainted: nothing blinks. Counts roll, rows come
 * and go with a short fade, and only the very first list arrives one row after another. When the
 * system asks for less motion, nothing slides: changes show with fades of at most 120 ms.
 */
class AirListView : public QAbstractScrollArea
{
    Q_OBJECT
public:
    using AvatarProvider = std::function<QPixmap(const QString& photoKey)>;

    explicit AirListView(QWidget* parent = nullptr);
    ~AirListView() override;

    void setAvatarProvider(AvatarProvider provider);
    void setSnapshot(const Air::Snapshot& snapshot, const QString& query);
    void setCompact(bool compact);
    /** The conversation open on the right: its row stays highlighted. */
    void setOpenEntry(const QString& entryId);
    /** The conversation after (or before) entryId in the order of the list, for Ctrl+Tab. */
    QString entryNextTo(const QString& entryId, bool forward) const;
    /**
     * Moves the keyboard cursor over the rows that can be opened. The request cards take the
     * cursor only while the list itself has the keyboard: their buttons answer a person, so Enter
     * in the command line never presses them.
     */
    void moveCursor(int step);
    /** Opens the row under the keyboard cursor, or the first one while searching. */
    void activateCursor();
    /**
     * The Requests filter (E12): the list goes to the cards, which stand first, and their ground
     * lights up once for a moment. With the id of a card, that card alone.
     */
    void highlightRequests(const QString& requestId = QString());
    void forgetAvatar(const QString& photoKey);

    /** One line as a screen reader takes it (AirAccessible). */
    struct SpokenLine
    {
        QString id;
        Air::Row::Type type = Air::Row::Type::Header;
        /** What it says: a heading with its count, a card, a row or a message found. */
        QString name;
        /** A card: its two buttons, Accept or Join, then Decline. */
        QString acceptName;
        QString declineName;
        /** Where it stands, in the coordinates of the viewport. */
        QRect rect;
        QRect acceptRect;
        QRect declineRect;
        /** The conversation open on the right. */
        bool open = false;
        /** The keyboard cursor stands on it. */
        bool cursor = false;
    };
    enum class SpokenPress
    {
        /** Opens a row or a message found. */
        Line,
        Accept,
        Decline,
    };
    /** The ids of the lines in the order they stand, without those on their way out. */
    QStringList spokenIds() const;
    /** Whether a line with this id still stands in the list and is not on its way out. */
    bool hasSpokenLine(const QString& id) const;
    /** The line with this id as a screen reader takes it; false once no line has the id. */
    bool spokenLine(const QString& id, SpokenLine* line) const;
    /** Every line, in order: spokenIds() with spokenLine() for each. */
    QVector<SpokenLine> spokenLines() const;
    /** What a screen reader presses on the line with this id; a line gone meanwhile does nothing. */
    void pressSpoken(const QString& id, SpokenPress press);

signals:
    void entryActivated(const QString& entryId);
    void messageActivated(const QString& chatId);
    void requestAccepted(const QString& requestId);
    void requestDeclined(const QString& requestId);
    void favoriteToggled(const QString& entryId, bool favorite);
    void entryMenuRequested(const QString& entryId, const QPoint& globalPos);
    void backgroundMenuRequested(const QPoint& globalPos);
    void emptyActionTriggered(Air::EmptyState state);

protected:
    void paintEvent(QPaintEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    void showEvent(QShowEvent* event) override;
    void mouseMoveEvent(QMouseEvent* event) override;
    void mousePressEvent(QMouseEvent* event) override;
    void mouseReleaseEvent(QMouseEvent* event) override;
    void leaveEvent(QEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;
    void contextMenuEvent(QContextMenuEvent* event) override;
    void focusInEvent(QFocusEvent* event) override;
    void focusOutEvent(QFocusEvent* event) override;
    bool viewportEvent(QEvent* event) override;
    void scrollContentsBy(int dx, int dy) override;

private:
    /** One animated value: where it starts, where it goes and how. */
    struct Track
    {
        qreal from = 0;
        qreal to = 0;
        qint64 start = 0;
        int duration = 0;
        int delay = 0;
        QEasingCurve curve;

        qreal at(qint64 now) const;
        bool running(qint64 now) const;
        void jump(qreal value);
        void animate(qreal target, qint64 now, int milliseconds, const QEasingCurve& easing,
                     int delayMilliseconds = 0);
    };

    /** One painted line: a heading, a request card, a row or a matching message. */
    struct Line
    {
        QString id;
        Air::Row row;
        int height = 0;
        int targetTop = 0;
        Track top;
        Track opacity;
        Track rise;
        Track roll;
        int previousCount = 0;
        bool leaving = false;
    };

    enum class Spot
    {
        None,
        Row,
        Favorite,
        Menu,
        Accept,
        Decline,
    };

    struct Hit
    {
        int line = -1;
        Spot spot = Spot::None;
    };

    qint64 now() const;
    void relayout(bool animate, bool firstLoad);
    int heightOf(const Air::Row& row) const;
    int requestCardHeight(const Air::Request& request) const;
    int contentHeight() const;
    void updateScrollRange();
    void startFrames();
    void onFrame();
    void restyle();
    /**
     * The empty state goes under the rows as painted words, not labels: the window's legacy style
     * sheet would recolour labels. Only its action is a widget.
     */
    struct EmptyLayout
    {
        QRect title;
        QRect body;
        QRect action;
        int height = 0;
    };
    EmptyLayout emptyLayoutAt(int top) const;
    int rowsBottom() const;
    void placeEmptyPanel();
    void paintEmpty(QPainter& painter) const;
    void ensureVisible(int lineIndex);
    int lineIndexOf(const QString& id) const;
    /** Rows to open, and the request cards too when cards is true. */
    bool isNavigable(const Line& line, bool cards) const;
    void moveCursorAmong(int step, bool cards);
    void activateLine(int lineIndex);
    /** A button of a request card: Accept (Join) or Decline. */
    void pressRequest(const Line& line, Spot spot);
    Hit hitAt(const QPoint& viewportPos) const;
    void hoverAt(const QPoint& viewportPos);
    /** Hover follows the rows when they move or change under a still pointer. */
    void refreshHover();
    QRect lineRect(const Line& line) const;
    QRect visual(const QRect& logical) const;
    QRect favoriteRect(const QRect& row) const;
    QRect menuRect(const QRect& row) const;
    QRect acceptRect(const Line& line, const QRect& row) const;
    QRect declineRect(const Line& line, const QRect& row) const;

    void paintHeader(QPainter& painter, const Line& line, const QRect& row, qint64 time) const;
    void paintRequest(QPainter& painter, const Line& line, const QRect& row) const;
    void paintEntry(QPainter& painter, const Line& line, const QRect& row) const;
    void paintMessage(QPainter& painter, const Line& line, const QRect& row) const;
    void paintTile(QPainter& painter, const QRect& tile, const QString& title, int tone,
                   Air::EntryKind kind, bool pending, const QString& photoKey) const;
    int paintFlags(QPainter& painter, const Air::Entry& entry, const QRect& area) const;
    void paintActionButton(QPainter& painter, const QRect& rect, const QString& icon, bool hot) const;
    void paintTextButton(QPainter& painter, const QRect& rect, const QString& text, bool ink,
                         bool hot) const;
    /** Tells a screen reader, when one listens, that the lines changed or the cursor moved. */
    void announceLines();
    void announceCursor();

    Air::Snapshot snapshot;
    QString query;
    QVector<Line> lines;
    QHash<QString, int> lineIndex;
    bool compact = false;
    bool shownRows = false;
    QString openEntry;
    QString cursorId;
    bool cursorVisible = false;
    /** On a request card the keyboard cursor stands on one of its buttons. */
    Spot cursorSpot = Spot::Accept;
    /** How lit the ground of the request cards is: 1 right after the Requests filter, then 0. */
    Track glow;
    /** The one card the glow is for, or none for every card. */
    QString glowId;
    Hit hovered;
    Hit pressed;
    AvatarProvider avatars;
    mutable QHash<QString, QPixmap> tileCache;

    QElapsedTimer clock;
    QTimer* frames = nullptr;

    AirButton* emptyAction = nullptr;
};
