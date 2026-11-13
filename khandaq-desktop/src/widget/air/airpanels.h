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

#include "src/widget/air/airwidgets.h"

#include <QPointer>
#include <QWidget>

class AirIconButton;
class QLabel;
class QScrollArea;
class QVBoxLayout;
class QVariantAnimation;

/**
 * The panel of a dossier: it unfolds from the chat header over the conversation column, top to
 * bottom over the reveal duration, as high as its page and no higher than leaves the conversation
 * in view, and dims the conversation under it without redrawing it. Esc, a click on the dimmed part
 * or the same click on the header closes it. With less motion it only fades, within 120 ms.
 */
class AirFoldPanel : public QWidget
{
    Q_OBJECT
public:
    explicit AirFoldPanel(QWidget* parent);

    bool isOpen() const;
    void open();
    void close();

signals:
    void openChanged(bool open);

protected:
    /** The page the subclass lays its sections out on, with the margins and spacing of a dossier. */
    QWidget* page() const;
    QVBoxLayout* pageLayout() const;
    /** Called before the page is measured for width, to arrange it (columns side by side). */
    virtual void arrange(int width);
    /** Called when the panel starts to fold away. */
    virtual void aboutToFold();
    /** Measures the page again and places the panel over the conversation. */
    void place();
    /** Shows the end of the page, once its layout has taken the last change in. */
    void scrollToEnd();

    void paintEvent(QPaintEvent* event) override;
    void mousePressEvent(QMouseEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    void restyle();
    void animate(bool opening);
    void applyProgress();

    bool opened = false;
    /** 0 folded away, 1 fully open. */
    qreal progress = 0;
    QPointer<QVariantAnimation> motion;
    QWidget* panel = nullptr;
    QScrollArea* scroller = nullptr;
    QWidget* content = nullptr;
    /** The conversation under the panel, out of the Tab chain while it is open. */
    AirCoveredFocus covered;
    /** Where the focus was when the panel opened: it goes back there when the panel folds away. */
    QPointer<QWidget> focusBefore;
};

/**
 * The QR code of a key in a small window over parent: the code, and under it one sentence on what
 * scanning it does. The dossiers of a person and of a group show their keys with it.
 */
void showAirQr(QWidget* parent, const QString& title, const QString& data, const QString& subtitle);

/**
 * The panel of New contact and New group: it drops from under the Air band over the list column,
 * with its title and a close button, as high as what it shows. It neither dims nor holds the rest
 * of the window: an ID can be copied from a conversation while it stays open. Esc or the close
 * button folds it away; with less motion it only fades.
 */
class AirDropPanel : public QWidget
{
    Q_OBJECT
public:
    /**
     * The panel lives in parent and covers column, the list column, from its top edge: the Air
     * band stands right above it. It follows the column as the window changes.
     */
    AirDropPanel(QWidget* column, QWidget* parent);
    ~AirDropPanel() override;

    bool isOpen() const;
    void close();

signals:
    void openChanged(bool open);

protected:
    /** Shows the panel when it is folded away; the subclass prepares its page first. */
    void unfold();
    void setTitle(const QString& title);
    QLabel* title() const;
    AirIconButton* closer() const;
    /** The page the subclass lays its form out on. */
    QWidget* page() const;
    QVBoxLayout* pageLayout() const;
    /** Called when the panel starts to fold away. */
    virtual void aboutToFold();
    void place();

    void paintEvent(QPaintEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    void restyle();
    void retranslate();
    void animate(bool opening);
    void applyProgress();

    QPointer<QWidget> column;
    /** The shadow under the panel: a sibling the pointer passes through. */
    QPointer<QWidget> shade;
    bool opened = false;
    /** 0 folded away, 1 fully open. */
    qreal progress = 0;
    QPointer<QVariantAnimation> motion;
    QLabel* titleLabel = nullptr;
    AirIconButton* closeButton = nullptr;
    QScrollArea* scroller = nullptr;
    QWidget* content = nullptr;
    /** The list under the panel, out of the Tab chain while it is open. */
    AirCoveredFocus covered;
    /** Where the focus was when the panel opened: it goes back there when the panel folds away. */
    QPointer<QWidget> focusBefore;
};
