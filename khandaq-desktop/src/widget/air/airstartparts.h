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

#include <QPointer>
#include <QStringList>
#include <QVector>
#include <QWidget>

class AirButton;
class AirIconButton;
class QLabel;
class QLineEdit;
class QVariantAnimation;

// ---- The parts of the start window: first launch, sign-in and moving a profile in (E19, E20) ----

/**
 * The progress of first launch: one bar a step on the sunken ground, the steps done in ink. The bar
 * of the current step fills from its start edge once its page is in, or is full at once with less
 * motion (khandaq-ios AirStepProgressView).
 */
class AirStepProgress : public QWidget
{
    Q_OBJECT
public:
    static constexpr int stepCount = 3;

    explicit AirStepProgress(QWidget* parent = nullptr);

    /** The bars before step are full; the bar of step waits for fillCurrent(). */
    void setStep(int step);
    int step() const;
    void fillCurrent(bool animated);

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void changeEvent(QEvent* event) override;

private:
    void retranslate();

    int current = 1;
    /** How far the bar of the current step is filled, 0 to 1. */
    qreal filled = 0;
    QPointer<QVariantAnimation> motion;
};

/**
 * The line under a field (E21): a quiet hint, a rule the typed text does not meet yet in the
 * colour of the words, or an error in red with Details when it has a code for support. Room for two
 * lines is kept, so what comes does not push what stands under it; a new line fades in from 4 px
 * lower, or is there at once with less motion.
 */
class AirFieldNote : public QWidget
{
    Q_OBJECT
public:
    explicit AirFieldNote(QWidget* parent = nullptr);

    /** What the note says while there is nothing else to say; empty for nothing. */
    void setHint(const QString& hint);
    void showRefusal(const QString& text);
    void showError(const QString& text, const QString& details = QString());
    /** Back to the hint. */
    void clear();

    QString text() const;
    bool showsError() const;

    bool hasHeightForWidth() const override;
    int heightForWidth(int width) const override;
    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

signals:
    void detailsRequested(const QString& details);

protected:
    void resizeEvent(QResizeEvent* event) override;

private:
    enum class Kind
    {
        Hint,
        Refusal,
        Error,
    };

    void set(Kind kind, const QString& text, const QString& details, bool animated);
    void restyle();
    /** Puts the words and Details where the width of the note has them. */
    void place();

    QLabel* label = nullptr;
    AirButton* detailsButton = nullptr;
    QString hint;
    QString details;
    Kind kind = Kind::Hint;
    QPointer<QVariantAnimation> motion;
};

/**
 * kq-input for a password: what is typed stays hidden until the eye at the end of the field shows
 * it. It shakes once for a wrong password, and stays still with less motion.
 */
class AirSecretField : public QWidget
{
    Q_OBJECT
public:
    explicit AirSecretField(QWidget* parent = nullptr);

    QLineEdit* edit() const;
    QString text() const;
    void clear();
    void setPlaceholder(const QString& text);
    void setInvalid(bool invalid);
    void shake();

    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

signals:
    /** The person typed, deleted or pasted something. */
    void changed();
    void submitted();

protected:
    void paintEvent(QPaintEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    void moveEvent(QMoveEvent* event) override;
    void changeEvent(QEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    void restyle();
    void place();
    void showSecret(bool shown);
    void shiftTo(int sideways);

    QLineEdit* field = nullptr;
    AirIconButton* eye = nullptr;
    bool invalid = false;
    bool hovered = false;
    /**
     * Where the layout put the field. The shake moves the whole field from there: drawn aside
     * within its own rect, the box would lose one of its edges.
     */
    QPoint rest;
    /** The shake is moving the field, not the layout. */
    bool shifting = false;
    QPointer<QVariantAnimation> shaking;
};

/**
 * An ID in its three named parts, key, antispam and check, in groups of four on the sunken ground,
 * left to right in every language (E19, step 3, and moving a profile in). reveal() brings the
 * groups in one after another, each rising 4 px; with less motion they are all there at once.
 */
class AirIdParts : public QWidget
{
    Q_OBJECT
public:
    explicit AirIdParts(QWidget* parent = nullptr);

    void setId(const QString& id);
    void setTitles(const QString& key, const QString& antispam, const QString& check);
    /** Hides every group until reveal(), so the page can come in before its ID does. */
    void conceal();
    void reveal(bool animated);

    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;

private:
    /** How far group index has come in, 0 to 1. */
    qreal groupProgress(int index) const;
    void describe();

    QStringList parts[3];
    QString titles[3];
    /** Milliseconds since the reveal began; a negative value shows every group. */
    int revealed = -1;
    QPointer<QVariantAnimation> motion;
};

/**
 * The pages of the start window, one at a time. The next page slides in from the side it lies on,
 * in the direction of travel and mirrored right to left, and the page it replaces slides out; with
 * less motion the two cross-fade.
 */
class AirPageStack : public QWidget
{
    Q_OBJECT
public:
    enum class Travel
    {
        None,
        Forward,
        Back,
    };

    explicit AirPageStack(QWidget* parent = nullptr);

    void addPage(QWidget* page);
    void showPage(QWidget* page, Travel travel);
    QWidget* currentPage() const;
    bool isMoving() const;

signals:
    /** page is in place, at the end of its slide or at once. */
    void arrived(QWidget* page);

protected:
    void resizeEvent(QResizeEvent* event) override;

private:
    void settle();

    QVector<QPointer<QWidget>> pages;
    QPointer<QWidget> current;
    QPointer<QWidget> leaving;
    QPointer<QVariantAnimation> motion;
};
