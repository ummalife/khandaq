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

#include <QAbstractButton>
#include <QFrame>
#include <QPixmap>
#include <QList>
#include <QPointer>
#include <QStringList>

#include <vector>

class QLabel;
class QLineEdit;
class QMenu;
class QPainter;
class QScrollArea;
class QVariantAnimation;

/**
 * The controls of the design system (design/system/site/system.css) as painted Qt buttons: every
 * colour, size and radius comes from the tokens through AirStyle, in both themes.
 */
namespace AirPaint {

/** The focus ring: 2 px of the focus colour, drawn just inside rect. */
void focusRing(QPainter& painter, const QRectF& rect, qreal radius);

/** A rounded rectangle whose hairline border sits inside rect; an invalid colour draws nothing. */
void box(QPainter& painter, const QRectF& rect, qreal radius, const QColor& fill, const QColor& border);

/** Repaints widget whenever the theme changes. */
void followTheme(QWidget* widget);

/**
 * Gives a label its text colour. The window's legacy style sheet colours every QLabel and a sheet
 * wins over the palette, so the colour goes into the widget's own sheet as well, which wins over the
 * window's. Widget::reloadTheme clears every sheet before AirStyle::changed, so call it on restyle.
 */
void textColor(QWidget* widget, const QColor& color);

/** Whether widget shows a focus ring: it has the focus and got it from the keyboard. */
bool keyboardFocus(const QWidget* widget);

/**
 * Lets menu scroll when it is longer than the screen. Fusion and the Windows styles spread such a
 * menu over columns instead, and the columns run off the edge of the screen. Call it before the
 * menu shows.
 */
void scrollWhenLong(QMenu& menu);

/**
 * A number that rolls: while progress runs from 0 to 1 the previous text slides up and out and the
 * current one comes in from below. With less motion the two only cross-fade.
 */
void rollingText(QPainter& painter, const QRect& rect, int flags, const QString& previous,
                 const QString& current, qreal progress);

/** The counter text: "99+" above 99. */
QString countText(int count);

/**
 * The words in lines no wider than width, at most maxLines, with line breaks read as spaces; the
 * last line is cut short when the words go on.
 */
QStringList linesOf(const QString& text, const QFont& font, int width, int maxLines);

/** The direction of a person's own words, set by their first letter whatever the interface is. */
Qt::LayoutDirection ownDirection(const QString& text);

/**
 * Draws a person's own words (a name, a message) in their own direction, so a Russian preview in
 * the Arabic interface keeps its full stop and its ellipsis at its own end. The flags are those of
 * QPainter::drawText; the alignment stays the interface's, so it must be an absolute one, from
 * QStyle::visualAlignment.
 */
void ownText(QPainter& painter, const QRect& rect, int flags, const QString& text);

} // namespace AirPaint

/** The order Tab takes through the Air screens. */
namespace AirFocus {

/** The widgets inside root that Tab stops on, in the order of the window's focus chain. */
QList<QWidget*> stops(const QWidget* root);

/**
 * The widgets inside root that Tab stops on, in the order they were made. For rows a list makes
 * afresh this is the order they stand in, where the chain has the new ones at the window's end.
 */
QList<QWidget*> made(const QWidget* root);

/** Puts order into the focus chain one after another, the rest of the chain kept around them. */
void chain(const QList<QWidget*>& order);

/**
 * Puts the stops inside holder, in the order they were made, where holder itself stands in the
 * chain. Qt adds a widget made later at the end of the window's chain, so the rows a list makes
 * afresh would come after everything else in the window; a list calls this after each rebuild.
 */
void inPlace(QWidget* holder);

/**
 * Scrolls area so that child stands in view with margin around it, as
 * QScrollArea::ensureWidgetVisible does. That one counts the sideways bar from the left, where a
 * right-to-left area counts it from the right, so in Arabic it scrolled away from the child.
 */
void bringIntoView(QScrollArea* area, QWidget* child, int margin);

/**
 * From now on a control that takes the focus inside a scroll area comes into view with its ring,
 * also when the focus comes in from outside the area, which Qt leaves where it was. Called once
 * for the whole application; a second call does nothing.
 */
void followIntoView();

} // namespace AirFocus

/**
 * Takes what a panel covers out of the Tab chain while the panel is open, and gives each widget its
 * focus policy back when it folds away: Tab never lands on a control the person cannot see.
 */
class AirCoveredFocus
{
public:
    AirCoveredFocus() = default;
    ~AirCoveredFocus();
    AirCoveredFocus(const AirCoveredFocus&) = delete;
    AirCoveredFocus& operator=(const AirCoveredFocus&) = delete;

    /** Every widget in covered, itself included and those inside panel left out, stops taking Tab. */
    void cover(QWidget* covered, const QWidget* panel);
    /** Gives the widgets their policy back, but not to one whose policy changed meanwhile. */
    void uncover();

private:
    struct Entry
    {
        QPointer<QWidget> widget;
        Qt::FocusPolicy before;
        Qt::FocusPolicy during;
    };
    std::vector<Entry> entries;
};

/** kq-icon: a 36 px square with one line icon; the name doubles as its tooltip. */
class AirIconButton : public QAbstractButton
{
    Q_OBJECT
public:
    explicit AirIconButton(const QString& iconName, QWidget* parent = nullptr);

    void setIconName(const QString& name);
    /** Turns the icon by degrees around its centre: the chevron of the dossier. */
    void setRotation(qreal degrees);
    /** The icon in the danger colour: a control that ends something, like a call. */
    void setDanger(bool danger);

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    QString iconName;
    qreal rotation = 0;
    bool danger = false;
    bool hovered = false;
};

/**
 * kq-play: the round button of a voice note, as in the phone apps and Telegram: an ink disc with a
 * play triangle, or two bars while the note plays. Its words (Listen, Pause) are not drawn: set
 * them with setText, they are its accessible name, and as its tooltip.
 */
class AirPlayButton : public QAbstractButton
{
    Q_OBJECT
public:
    explicit AirPlayButton(QWidget* parent = nullptr);

    void setPlaying(bool playing);
    bool isPlaying() const;

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    bool playing = false;
    bool hovered = false;
};

/** kq-btn: a text button. Ink for the one main action, plain otherwise. */
class AirButton : public QAbstractButton
{
    Q_OBJECT
public:
    enum class Kind
    {
        Plain,
        Ink,
        /** kq-btn-quiet: underlined text without a frame, 28 px high, its words 4 px off the
         * edges, so it lines up with the text around it as on the phones. */
        Quiet,
        /** kq-btn-danger: a framed button with red text, for actions that remove something. */
        Danger,
        /** kq-state-retry: quiet, red and 28 px high, beside a message that was not sent. */
        Retry,
        /** kq-toast button: underlined, semibold words on the ink of a toast, 28 px high. */
        OnInk,
    };

    explicit AirButton(Kind kind, QWidget* parent = nullptr);

    /** The text is someone's own words, such as a file name: it keeps its own direction in either
     * interface, as the names in the list do. */
    void setOwnWords(bool ownWords);
    /** A quiet button that is the action its row waits for (Accept, Send again): in ink, not ink-2. */
    void setEmphasis(bool emphasis);

    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    void drawLabel(QPainter& painter, const QRectF& box) const;

    Kind kind;
    bool ownWords = false;
    bool emphasis = false;
    bool hovered = false;
};

/**
 * kq-pill: a filter chip with an optional count. Checked means the filter is on; pressing it again
 * turns it off. The count rolls when it changes.
 */
class AirPill : public QAbstractButton
{
    Q_OBJECT
public:
    enum class Kind
    {
        Plain,
        /** The count is drawn in the transit colours: something is on its way. */
        Transit,
    };

    explicit AirPill(Kind kind, QWidget* parent = nullptr);

    void setCount(int count);
    int count() const;

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    QRect badgeRect(const QRect& contents) const;
    int badgeWidth(int value) const;

    Kind kind;
    int value = 0;
    int previousValue = 0;
    qreal progress = 1.0;
    bool hovered = false;
    QPointer<QVariantAnimation> roll;
};

/**
 * The line under your name: a still dot and the state of your node in words. It is a target of its
 * own and opens the state of the network; a new state cross-fades the words while the dot stays
 * where it is (DESIGN.md, "Network state on the phone").
 */
class AirNetworkLine : public QAbstractButton
{
    Q_OBJECT
public:
    enum class Dot
    {
        Live,
        /** Looking for the network: the still grey of waiting. */
        Waiting,
        Danger,
    };

    explicit AirNetworkLine(QWidget* parent = nullptr);

    /** crossFade is for a new state; a count that moves only changes the words. */
    void setState(const QString& text, Dot dot, bool crossFade);
    /** The width the dot and its words take, the padding round them included. */
    int wordsWidth() const;

signals:
    /** The words changed, or the old ones finished fading: the room the line needs may differ. */
    void wordsChanged();

protected:
    void paintEvent(QPaintEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    void paintWords(QPainter& painter, const QString& words, Dot wordsDot, qreal opacity);

    Dot dot = Dot::Waiting;
    QString previousText;
    Dot previousDot = Dot::Waiting;
    /** How far the cross-fade has come: 1 once the new words stand alone. */
    qreal shown = 1.0;
    QPointer<QVariantAnimation> fade;
};

/**
 * The block with your own tile and name, and the line about your node under the name. Clicking the
 * block opens You; the line is a button of its own.
 */
class AirSelfButton : public QAbstractButton
{
    Q_OBJECT
public:
    explicit AirSelfButton(QWidget* parent = nullptr);

    void setName(const QString& name);
    void setAvatar(const QPixmap& avatar);
    /** The line under the name, which opens the state of the network. */
    AirNetworkLine* networkLine() const;

    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    void changeEvent(QEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    /** Where the name stands and where the line under it does. */
    QRect nameRect() const;
    QRect lineRect() const;
    void placeLine();

    QString name;
    QPixmap avatar;
    AirNetworkLine* line = nullptr;
    bool hovered = false;
};

/**
 * kq-cmd: the command line. A search icon, the field and the "/" key that reaches it; the frame
 * turns ink while the field has focus.
 */
class AirCommandField : public QFrame
{
    Q_OBJECT
public:
    explicit AirCommandField(QWidget* parent = nullptr);

    QLineEdit* lineEdit() const;
    void setPlaceholder(const QString& text);
    void setKeyHint(const QString& accessibleHint);

    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    void restyle();

    QLabel* searchIcon = nullptr;
    QLineEdit* field = nullptr;
    QLabel* keyHint = nullptr;
};
