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

#include "src/model/air/airconversation.h"

#include <QPointer>
#include <QVector>
#include <QWidget>

class AirButton;
class AirIconButton;
class QLabel;
class QScrollArea;
class QVBoxLayout;

/** One of our messages that has not reached the person, as the tray and the failed rows show it. */
struct AirQueueItem
{
    qint64 id = -1;
    /** "9:41": hours and minutes. */
    QString time;
    QString text;

    bool operator==(const AirQueueItem& other) const;
};

/**
 * A row laid out like an entry of the transcript (kq-entry): the time, the text and the state mark.
 * A click or the context menu offers what can still be done with the message.
 */
class AirQueueRow : public QWidget
{
    Q_OBJECT
public:
    AirQueueRow(const AirQueueItem& item, Air::EntryStatus status, QWidget* parent = nullptr);
    ~AirQueueRow() override;

    qint64 id() const;
    /** A failed message gets the red "Not sent. Retry" under its text. */
    void addRetry();

signals:
    void actionsRequested(qint64 id, const QPoint& globalPos);
    void retryRequested(qint64 id);

protected:
    void mouseReleaseEvent(QMouseEvent* event) override;
    void contextMenuEvent(QContextMenuEvent* event) override;
    /** Enter, Space or the menu key open the actions a click opens. */
    void keyPressEvent(QKeyEvent* event) override;
    void focusInEvent(QFocusEvent* event) override;
    void focusOutEvent(QFocusEvent* event) override;
    void paintEvent(QPaintEvent* event) override;

private:
    void restyle();
    void retranslate();

    qint64 itemId;
    Air::EntryStatus status;
    QString time;
    QString text;
    QLabel* timeLabel = nullptr;
    QLabel* textLabel = nullptr;
    QLabel* mark = nullptr;
    QVBoxLayout* textColumn = nullptr;
    AirButton* retryButton = nullptr;
};

/**
 * kq-queue, the "In transit" tray between the transcript and the field: our messages waiting for a
 * person who is offline, oldest first. The heading says how many and when they go; each message can
 * still be edited or cancelled. When the person comes online the messages leave one by one, 90 ms
 * apart, and the tray folds away; with less motion they fade together.
 */
class AirTray : public QWidget
{
    Q_OBJECT
public:
    explicit AirTray(QWidget* parent = nullptr);
    ~AirTray() override;

    /**
     * personName is the short name the detail line uses. Emptying the tray while the person is
     * live plays the departure; otherwise the tray just hides.
     */
    void setItems(const QVector<AirQueueItem>& items, const QString& personName, bool personLive);
    /** The most the list of messages may take, so the transcript stays in view. */
    void setMaximumItemsHeight(int height);
    /**
     * Keeps the list within share of reference's height as reference is resized, and within the
     * room that what else stands in reference leaves it.
     */
    void limitTo(QWidget* reference, qreal share);
    bool isEmpty() const;

signals:
    void editRequested(qint64 id);
    void cancelRequested(qint64 id);

protected:
    void paintEvent(QPaintEvent* event) override;
    void mouseReleaseEvent(QMouseEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    void rebuild(const QVector<AirQueueItem>& items);
    void fitItems();
    /** Sets the most the list may take from the height of the reference and what stands in it. */
    void followReference();
    void restyle();
    void retranslate();
    void setExpanded(bool expanded);
    void depart();
    void showActions(qint64 id, const QPoint& globalPos);

    QLabel* title = nullptr;
    QLabel* detail = nullptr;
    AirIconButton* chevron = nullptr;
    QScrollArea* scroller = nullptr;
    QWidget* list = nullptr;
    QVBoxLayout* listLayout = nullptr;
    QVector<AirQueueRow*> rows;
    QVector<AirQueueItem> shown;
    bool expanded = true;
    bool departing = false;
    /** Bumped by every change, so a departure that was overtaken stops where it is. */
    int generation = 0;
    int itemsHeightLimit = 0;
    QPointer<QWidget> heightReference;
    qreal heightShare = 0;
};

/**
 * The messages the network gave up on, each in its own row above the tray: the text with a red
 * mark and "Not sent. Retry" under it. Retry sends the same text again; the menu also edits it or
 * takes it back.
 */
class AirFailedList : public QWidget
{
    Q_OBJECT
public:
    explicit AirFailedList(QWidget* parent = nullptr);

    void setItems(const QVector<AirQueueItem>& items);

signals:
    void retryRequested(qint64 id);
    void editRequested(qint64 id);
    void cancelRequested(qint64 id);

private:
    void showActions(qint64 id, const QPoint& globalPos);

    QVBoxLayout* listLayout = nullptr;
    QVector<AirQueueRow*> rows;
    QVector<AirQueueItem> shown;
};
