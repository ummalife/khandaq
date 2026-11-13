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
#include "src/model/air/airmodel.h"
#include "src/widget/air/airpanels.h"

#include <QAbstractButton>
#include <QPointer>
#include <QVector>
#include <QWidget>

#include <functional>

class AirButton;
class AirIconButton;
class AirPathPicture;
class QLabel;
class QTextEdit;
class QVariantAnimation;

/**
 * kq-input and kq-id-input: a framed field on the surface, with a strong hairline that turns ink
 * while it has the focus and red while what it holds cannot go. Its height follows its width: it
 * grows with its text, from one line (the height of three for an ID) up to five, then scrolls; a
 * placeholder of two lines makes it taller instead of running out of it. Enter without Shift
 * submits.
 *
 * The ID kind takes only the hex of an ID, in groups of four and left to right in every language,
 * whatever is typed, pasted or dropped into it (Air::IdFieldState); a paste that holds a whole ID
 * replaces what was there. The key kind does the same for the 64 characters of a group key, from
 * one line on. The line kind holds one line of words: a name or a search, where a pasted line
 * break becomes a space.
 */
class AirTextField : public QWidget
{
    Q_OBJECT
public:
    enum class Kind
    {
        Id,
        Key,
        Message,
        Line,
    };

    explicit AirTextField(Kind kind, QWidget* parent = nullptr);

    QTextEdit* edit() const;
    QString text() const;
    /** Puts text in as if pasted, without changed(): whoever sets it decides what follows. */
    void setText(const QString& text);
    void setPlaceholder(const QString& text);
    void setInvalid(bool invalid);

    bool hasHeightForWidth() const override;
    int heightForWidth(int width) const override;
    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

signals:
    /** The person changed the text: typed, deleted, pasted or dropped something. */
    void changed();
    void submitted();

protected:
    void paintEvent(QPaintEvent* event) override;
    void mousePressEvent(QMouseEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    void restyle();
    /** The text, its font or the placeholder changed: the height is measured again. */
    void remeasure();

    Kind kind;
    QTextEdit* field = nullptr;
    QString placeholder;
    bool invalid = false;
    bool hovered = false;
    /** The last height measured, and for which width. */
    mutable int measuredWidth = -1;
    mutable int measuredHeight = 0;
};

/**
 * kq-ruler: the three parts of an ID under its field, key 64, antispam 8 and check 4, in the
 * proportions 64 : 16 : 12, each with its name and its count. They stand left to right in every
 * language, like the ID itself. The parts fill as characters come; the check part turns green when
 * the check matches and red when it does not, and then shakes once. With less motion the parts
 * fill at once and the check part only turns red.
 */
class AirIdRuler : public QWidget
{
    Q_OBJECT
public:
    explicit AirIdRuler(QWidget* parent = nullptr);

    void setState(int count, Air::IdVerdict::Check check, bool animate);
    void shakeCheck();

    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;

private:
    void describe();

    QVector<Air::RulerPart> parts;
    Air::IdVerdict::Check check = Air::IdVerdict::Check::Filling;
    /** The share of each part drawn filled now, and where the fill runs from and to. */
    qreal shown[3] = {0, 0, 0};
    qreal from[3] = {0, 0, 0};
    qreal to[3] = {0, 0, 0};
    /** How far the check part stands off its place while it shakes. */
    qreal shake = 0;
    QPointer<QVariantAnimation> fill;
    QPointer<QVariantAnimation> shaking;
};

/**
 * The row over the Air list while the command line holds the start of an ID (E3): a dashed tile
 * with the add-person icon, "New contact with this ID" and what is missing or wrong so far. A click
 * on it, or Enter in the command line, opens New contact with those characters.
 */
class AirCommandIdRow : public QAbstractButton
{
    Q_OBJECT
public:
    explicit AirCommandIdRow(QWidget* parent = nullptr);
    ~AirCommandIdRow() override;

    /** The note under the title for what the command line holds, in New contact's words. */
    static QString noteFor(const Air::IdVerdict& verdict, int hexCount);

    /** The hex the command line holds so far, and the verdict New contact would give it. */
    void setIdStart(const QString& hex, const Air::IdVerdict& verdict);
    QString idStart() const;

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    void retranslate();

    QString hex;
    Air::IdVerdict verdict;
    bool hovered = false;
};

/**
 * New contact (screen 11, desktop): a panel that drops from under the Air band over the list
 * column. The ID field with its ruler and the real check, the verdict in words before anything is
 * sent, the message that goes with the request and "Send request"; your own ID with Copy at the
 * foot, and the old form for importing a file of IDs behind a quiet link. After sending, the panel
 * turns into "Request on its way" with the path picture, and into "Request accepted" once the
 * person comes online while it is open.
 *
 * It neither dims nor holds the rest of the window: an ID can be copied from a conversation while
 * it stays open. Esc or the close button folds it away; Enter in its fields sends.
 */
class AirNewContact : public AirDropPanel
{
    Q_OBJECT
public:
    /** What the panel asks about the profile; Widget answers. Public keys come in upper case. */
    struct Lookup
    {
        std::function<QString()> ownAddress;
        Air::IdVerdict::KeyTest isContact;
        Air::IdVerdict::KeyTest hasRequest;
        Air::IdVerdict::KeyTest isBlocked;
        /** The contact's name as the list shows it, or empty. */
        std::function<QString(const QString& publicKey)> contactName;
    };

    /**
     * The panel lives in parent and covers column, the list column, from its top edge: the Air
     * band stands right above it. It follows the column as the window changes.
     */
    AirNewContact(Lookup lookup, QWidget* column, QWidget* parent);
    ~AirNewContact() override;

    /**
     * Opens the panel on an empty form; a non-empty text goes into the ID field as if pasted.
     * When the form is open already, only the ID changes.
     */
    void open(const QString& text = QString());

    /**
     * The verdict on the start of an ID in the command line, for its row: there only your own ID
     * and a contact stand out, the rest is told in the panel.
     */
    Air::IdVerdict commandVerdict(const QString& hex) const;

    /** The key whose request the panel waits on: Core's answer first, then the person. */
    QString awaitedKey() const;
    /** Core sent the request to key. */
    void requestSent(const QString& key);
    /** Core could not send the request to key. */
    void requestFailed(const QString& key);
    /**
     * The person the request went to came online, so they accepted it: the path turns solid. name
     * is what the list calls them, empty while their name is not known yet.
     */
    void personAccepted(const QString& name, Air::LinkState link);

signals:
    /** "Send request": the whole address, and the message, the greeting when none was written. */
    void sendRequested(const QString& address, const QString& message);
    void openChatRequested(const QString& publicKey);
    void openRequestsRequested();
    void importRequested();

protected:
    void aboutToFold() override;

private:
    enum class Page
    {
        Form,
        /** "Send request" was pressed; Core has not answered yet. */
        Sending,
        Sent,
    };

    void retranslate();
    void restyle();
    void resetForm();
    void setId(const QString& raw, bool fromUser);
    void refresh(bool fromUser);
    void send();
    void showSent();
    /** The words and the picture of the page after sending, waiting or accepted. */
    void sayWhereItIs();
    void focusFirst();

    Lookup lookup;
    Page page = Page::Form;
    Air::IdVerdict verdict;
    QString sentKey;
    /** The accepted person's name as the list shows it; empty while it is not known. */
    QString acceptedName;
    Air::LinkState acceptedLink = Air::LinkState::None;
    bool accepted = false;

    QWidget* formPage = nullptr;
    QLabel* idLabel = nullptr;
    AirTextField* idField = nullptr;
    AirButton* pasteButton = nullptr;
    AirButton* clearButton = nullptr;
    AirIdRuler* ruler = nullptr;
    QLabel* verdictLabel = nullptr;
    AirButton* verdictLink = nullptr;
    QLabel* messageLabel = nullptr;
    AirTextField* messageField = nullptr;
    QLabel* messageError = nullptr;
    QLabel* messageNote = nullptr;
    QLabel* sendError = nullptr;
    AirButton* sendButton = nullptr;
    QLabel* whyLabel = nullptr;
    QLabel* ownTitle = nullptr;
    QLabel* ownKey = nullptr;
    AirButton* copyButton = nullptr;
    QLabel* ownNote = nullptr;
    AirButton* importButton = nullptr;

    QWidget* sentPage = nullptr;
    AirPathPicture* path = nullptr;
    QLabel* sentTitle = nullptr;
    QLabel* sentText = nullptr;
    AirButton* doneButton = nullptr;
};
