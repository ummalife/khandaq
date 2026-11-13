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

#include <QElapsedTimer>
#include <QPixmap>
#include <QPointer>
#include <QTimer>
#include <QVector>
#include <QWidget>

#include <functional>

class AirButton;
class AirTile;
class QBoxLayout;
class QLabel;
class QVariantAnimation;

/**
 * kq-dialog: a question over the whole window, which dims under the scrim. It belongs to the
 * window: it follows its size, takes the keyboard while open (Tab stays inside, Esc closes) and
 * gives the focus back when it closes. The consequence is said in one sentence and the button that
 * acts is the verb.
 */
class AirDialog : public QWidget
{
    Q_OBJECT
public:
    enum class Verb
    {
        Ink,
        /** The verb removes something: danger colours, and the focus starts on Cancel. */
        Danger,
    };

    /**
     * Asks over the window of anchor. The verb runs onConfirm and closes; Cancel, Esc or a click on
     * the dimmed window close without it.
     */
    static AirDialog* confirm(QWidget* anchor, const QString& title, const QString& message,
                              const QString& verb, Verb kind, std::function<void()> onConfirm);

    /**
     * Says that an operation failed: the operation as the title ("The file wasn't sent"), what to
     * do as the message, and OK. A code for support, when there is one, waits under Details with
     * Copy and never enters the words.
     */
    static AirDialog* notice(QWidget* anchor, const QString& title, const QString& message,
                             const QString& details = QString());

    /**
     * One more way on: a plain button between Cancel and the verb ("Call without video"). It runs
     * onChoice and closes the dialog.
     */
    void addChoice(const QString& text, std::function<void()> onChoice);

    ~AirDialog() override;

signals:
    void finished(bool confirmed);

protected:
    void paintEvent(QPaintEvent* event) override;
    void mousePressEvent(QMouseEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;
    bool focusNextPrevChild(bool next) override;

private:
    AirDialog(QWidget* window, Verb kind);
    void restyle();
    void place();
    /**
     * The buttons stand in a row while they fit in room at their size; else one under another as
     * wide as the card, the verb on top and Cancel last (three ways on, a long translation).
     */
    void stackActions(int room);
    void animateIn();
    void finish(bool confirmed);
    void showDetails();
    /** The buttons the keyboard goes through, in their order on screen. */
    QVector<AirButton*> focusOrder() const;

    Verb kind;
    QWidget* card = nullptr;
    QLabel* titleLabel = nullptr;
    QLabel* messageLabel = nullptr;
    QWidget* detailsRow = nullptr;
    QLabel* detailsLabel = nullptr;
    AirButton* copyButton = nullptr;
    AirButton* detailsButton = nullptr;
    QBoxLayout* actionsLayout = nullptr;
    AirButton* cancelButton = nullptr;
    QVector<AirButton*> choiceButtons;
    AirButton* verbButton = nullptr;
    QString details;
    std::function<void()> onConfirm;
    QPointer<QWidget> previousFocus;
    QPointer<QVariantAnimation> motion;
    /** 0 before it shows, 1 once it is in place. */
    qreal progress = 1;
    bool done = false;
    bool stacked = false;
};

/**
 * kq-toast: a short line at the foot of the window that confirms what just happened ("Key
 * unblocked"), or offers to take it back ("Request declined. Undo"). It goes by itself and stays
 * while the pointer is over it. One toast at a time per window: a new one takes the place of the
 * old, which ends as if its time had run out.
 */
class AirToast : public QWidget
{
    Q_OBJECT
public:
    /**
     * Shows text over the window of anchor. With an action, its button runs onAction and closes the
     * toast, which then stays five seconds (ten while a screen reader runs); onExpire runs when the
     * toast goes without the action, whatever makes it go.
     */
    static AirToast* post(QWidget* anchor, const QString& text, const QString& action = QString(),
                          std::function<void()> onAction = nullptr,
                          std::function<void()> onExpire = nullptr);
    /** Ends the toast of the window of anchor, if one is showing, as if its time ran out. */
    static void dismiss(QWidget* anchor);

    ~AirToast() override;

    QString text() const;

protected:
    void paintEvent(QPaintEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    AirToast(QWidget* window, const QString& text, const QString& action);
    void restyle();
    void place();
    void animate(bool in);
    void expire();
    void act();
    void close(bool acted);

    QString words;
    QLabel* label = nullptr;
    QWidget* card = nullptr;
    AirButton* actionButton = nullptr;
    std::function<void()> onAction;
    std::function<void()> onExpire;
    QTimer timer;
    QElapsedTimer shown;
    int remainingMs = 0;
    QPointer<QVariantAnimation> motion;
    qreal progress = 1;
    bool closing = false;
};

/**
 * The band a message from another chat, a request or an invitation comes as while the window is in
 * front (E22): at the top of the conversation column, under the Air band, with the tile the list
 * shows for the chat, then who it comes from and what it says. A click opens what it tells of;
 * dragged up it goes away; it leaves by itself after four and a half seconds, later while the
 * pointer is over it. One band at a time per window: a new one takes the place of the one up now.
 * It never takes the keyboard, so typing goes on under it.
 */
class AirBanner : public QWidget
{
    Q_OBJECT
public:
    enum class Tile
    {
        Person,
        Group,
        /** A request: the dashed tile with "add contact", as the row of a request. */
        Request,
    };

    struct Content
    {
        /** The sender, the chat, or what came ("Incoming request"): one line. */
        QString title;
        /** What it says, two lines at most; may be empty. */
        QString body;
        Tile tile = Tile::Person;
        /** The name the initials of the tile come from, and the tone of the tile. */
        QString tileName;
        int tone = 0;
        /** The person's photo when they chose one. */
        QPixmap photo;
        /** What a click opens, for a screen reader ("Opens the chat"). */
        QString hint;
    };

    /** How long a band stays when nothing holds it. */
    static constexpr int stayMs = 4500;

    /** Shows content over the window of anchor in place of the band up there now. */
    static AirBanner* post(QWidget* anchor, const Content& content, std::function<void()> onOpen);
    /** Takes away the band of the window of anchor, if one is up. */
    static void dismiss(QWidget* anchor);
    /** The band up in the window of anchor, or null. */
    static AirBanner* current(QWidget* anchor);

    ~AirBanner() override;

    const Content& content() const;

protected:
    void paintEvent(QPaintEvent* event) override;
    void mousePressEvent(QMouseEvent* event) override;
    void mouseMoveEvent(QMouseEvent* event) override;
    void mouseReleaseEvent(QMouseEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    AirBanner(QWidget* window, const Content& content);
    void restyle();
    /** Size and place under the Air band, lifted by the motion and by a drag. */
    void place();
    void animate(bool in);
    void close(bool opened);

    Content data;
    QWidget* card = nullptr;
    AirTile* tile = nullptr;
    std::function<void()> onOpen;
    QTimer timer;
    QElapsedTimer shown;
    int remainingMs = stayMs;
    QPointer<QVariantAnimation> motion;
    /** 0 above the column, out of sight; 1 in place. */
    qreal progress = 1;
    /** How far a drag has lifted it. */
    int dragLift = 0;
    QPoint pressedAt;
    bool pressed = false;
    bool closing = false;
};
