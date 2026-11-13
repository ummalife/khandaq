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

#include "airstartparts.h"

#include "airstrings.h"
#include "airstyle.h"
#include "airtokens.h"
#include "airwidgets.h"
#include "src/model/air/airconversation.h"
#include "src/model/air/airyou.h"

#include <QEvent>
#include <QFontMetrics>
#include <QGraphicsOpacityEffect>
#include <QLabel>
#include <QLineEdit>
#include <QPainter>
#include <QPainterPath>
#include <QStyle>
#include <QVariantAnimation>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Motion = AirTokens::Motion;
namespace Radius = AirTokens::Radius;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

/** The groups of an ID come in this far below their place and rise into it. */
constexpr int revealRise = Space::s4;
/** The sideways steps of a shake, in pixels, spread evenly over its time (khandaq-ios airShakeOnce). */
constexpr int shakeSteps[] = {0, -Space::s6, Space::s6, -Space::s4, Space::s2, 0};
constexpr int shakeStepCount = static_cast<int>(sizeof(shakeSteps) / sizeof(shakeSteps[0]));
/** Groups of an ID: 16 in the key, 2 in the antispam, 1 in the check. */
constexpr int idGroupCount = 16 + 2 + 1;
constexpr int keyGroupsPerLine = 4;
} // namespace

// ---- AirStepProgress ----

// C++11: qBound takes it by reference, so it needs a definition; without optimisation (a Debug
// build) nothing folds it away and the link fails.
constexpr int AirStepProgress::stepCount;

AirStepProgress::AirStepProgress(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    AirPaint::followTheme(this);
    retranslate();
}

void AirStepProgress::setStep(int step)
{
    if (motion) {
        motion->stop();
    }
    current = qBound(1, step, stepCount);
    filled = 0;
    retranslate();
    update();
}

int AirStepProgress::step() const
{
    return current;
}

void AirStepProgress::fillCurrent(bool animated)
{
    if (filled >= 1.0 || motion) {
        return;
    }
    if (!animated || air().reducedMotion() || !isVisible()) {
        filled = 1.0;
        update();
        return;
    }
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(0.0);
    animation->setEndValue(1.0);
    animation->setDuration(air().duration(Motion::layout));
    animation->setEasingCurve(air().easeOut());
    connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        filled = value.toReal();
        update();
    });
    motion = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

QSize AirStepProgress::sizeHint() const
{
    return QSize(stepCount * Space::s32 + (stepCount - 1) * Space::s6, Size::bar);
}

void AirStepProgress::retranslate()
{
    setAccessibleName(AirStrings::text(Text::StartStep).arg(current).arg(stepCount));
}

void AirStepProgress::changeEvent(QEvent* event)
{
    if (event->type() == QEvent::LanguageChange) {
        retranslate();
    }
    QWidget::changeEvent(event);
}

void AirStepProgress::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setPen(Qt::NoPen);
    const bool rightToLeft = layoutDirection() == Qt::RightToLeft;
    const qreal radius = Size::bar / 2.0;
    const qreal top = (height() - Size::bar) / 2.0;
    for (int index = 0; index < stepCount; ++index) {
        // The first step stands at the start edge of the interface.
        const int slot = rightToLeft ? stepCount - 1 - index : index;
        const QRectF track(slot * (Space::s32 + Space::s6), top, Space::s32, Size::bar);
        QPainterPath shape;
        shape.addRoundedRect(track, radius, radius);
        painter.fillPath(shape, air().color(Color::Sunken));
        const int step = index + 1;
        const qreal share = step < current ? 1.0 : step == current ? filled : 0.0;
        if (share <= 0) {
            continue;
        }
        // The fill comes in from the start edge, clipped by the round ends of its track.
        QRectF fill = track;
        fill.setWidth(track.width() * share);
        if (rightToLeft) {
            fill.moveRight(track.right());
        }
        painter.save();
        painter.setClipPath(shape);
        painter.fillRect(fill, air().color(Color::Ink));
        painter.restore();
    }
}

// ---- AirFieldNote ----

AirFieldNote::AirFieldNote(QWidget* parent)
    : QWidget(parent)
{
    QSizePolicy policy(QSizePolicy::Preferred, QSizePolicy::Fixed);
    policy.setHeightForWidth(true);
    setSizePolicy(policy);
    label = new QLabel(this);
    label->setTextFormat(Qt::PlainText);
    label->setWordWrap(true);
    detailsButton = new AirButton(AirButton::Kind::Quiet, this);
    detailsButton->setObjectName(QStringLiteral("startDetails"));
    detailsButton->hide();
    connect(detailsButton, &AirButton::clicked, this, [this] { emit detailsRequested(details); });
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirFieldNote::restyle);
    restyle();
}

void AirFieldNote::setHint(const QString& hint_)
{
    hint = hint_;
    if (kind == Kind::Hint) {
        set(Kind::Hint, hint, QString(), false);
    }
}

void AirFieldNote::showRefusal(const QString& text)
{
    set(Kind::Refusal, text, QString(), true);
}

void AirFieldNote::showError(const QString& text, const QString& details_)
{
    set(Kind::Error, text, details_, true);
}

void AirFieldNote::clear()
{
    if (kind != Kind::Hint || label->text() != hint) {
        set(Kind::Hint, hint, QString(), false);
    }
}

QString AirFieldNote::text() const
{
    return label->text();
}

bool AirFieldNote::showsError() const
{
    return kind == Kind::Error;
}

void AirFieldNote::set(Kind kind_, const QString& text, const QString& details_, bool animated)
{
    if (motion) {
        motion->stop();
    }
    kind = kind_;
    details = details_;
    label->setText(text);
    label->setGraphicsEffect(nullptr);
    label->move(0, 0);
    detailsButton->setText(AirStrings::text(Text::Details));
    detailsButton->setVisible(!details.isEmpty());
    restyle();
    place();
    // An error is said once it is there: a screen reader reads the note out.
    setAccessibleName(text);

    if (!animated || text.isEmpty() || air().reducedMotion() || !isVisible()) {
        return;
    }
    // It comes in from a little lower, without moving anything around it: the room is kept.
    auto* const fade = new QGraphicsOpacityEffect(label);
    fade->setOpacity(0.0);
    label->setGraphicsEffect(fade);
    const QPointer<QGraphicsOpacityEffect> effect(fade);
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(0.0);
    animation->setEndValue(1.0);
    animation->setDuration(air().duration(Motion::state));
    animation->setEasingCurve(air().easeOut());
    connect(animation, &QVariantAnimation::valueChanged, this, [this, effect](const QVariant& value) {
        const qreal progress = value.toReal();
        if (effect) {
            effect->setOpacity(progress);
        }
        label->move(0, qRound(revealRise * (1.0 - progress)));
    });
    connect(animation, &QVariantAnimation::finished, this, [this] {
        // An effect left at full opacity keeps painting through its own pixmap: it goes.
        label->setGraphicsEffect(nullptr);
        label->move(0, 0);
    });
    motion = animation;
    label->move(0, revealRise);
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirFieldNote::restyle()
{
    label->setFont(air().font(TextRole::Caption));
    const Color color = kind == Kind::Error     ? Color::Danger
                        : kind == Kind::Refusal ? Color::Ink2
                                                : Color::Ink3;
    AirPaint::textColor(label, air().color(color));
    label->setAlignment(QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignTop));
    updateGeometry();
    update();
}

// The note keeps room for two lines, and grows only for a longer message or Details.
int AirFieldNote::heightForWidth(int width) const
{
    const int lines = 2 * air().lineHeight(TextRole::Caption);
    int height = qMax(lines, label->text().isEmpty() ? 0 : label->heightForWidth(qMax(1, width)));
    if (detailsButton->isVisibleTo(this)) {
        height += Space::s2 + detailsButton->sizeHint().height();
    }
    return height;
}

bool AirFieldNote::hasHeightForWidth() const
{
    return true;
}

QSize AirFieldNote::sizeHint() const
{
    const int width = qMax(Space::s64, label->sizeHint().width());
    return QSize(width, heightForWidth(width));
}

QSize AirFieldNote::minimumSizeHint() const
{
    return QSize(0, 2 * air().lineHeight(TextRole::Caption));
}

void AirFieldNote::resizeEvent(QResizeEvent*)
{
    place();
}

void AirFieldNote::place()
{
    const int labelHeight = label->text().isEmpty() ? 0 : label->heightForWidth(qMax(1, width()));
    label->setGeometry(0, label->y(), width(), labelHeight);
    const QSize button = detailsButton->sizeHint();
    const int left = layoutDirection() == Qt::RightToLeft ? width() - button.width() : 0;
    detailsButton->setGeometry(left, labelHeight + Space::s2, button.width(), button.height());
}

// ---- AirSecretField ----

AirSecretField::AirSecretField(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    setCursor(Qt::IBeamCursor);
    field = new QLineEdit(this);
    field->setEchoMode(QLineEdit::Password);
    field->setFrame(false);
    field->setAttribute(Qt::WA_MacShowFocusRect, false);
    field->setInputMethodHints(Qt::ImhHiddenText | Qt::ImhSensitiveData | Qt::ImhNoAutoUppercase
                               | Qt::ImhNoPredictiveText);
    field->installEventFilter(this);
    eye = new AirIconButton(QStringLiteral("eye"), this);
    eye->setCheckable(true);
    eye->setCursor(Qt::PointingHandCursor);
    connect(field, &QLineEdit::textEdited, this, [this] {
        setInvalid(false);
        emit changed();
    });
    connect(field, &QLineEdit::returnPressed, this, &AirSecretField::submitted);
    connect(eye, &AirIconButton::toggled, this, &AirSecretField::showSecret);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirSecretField::restyle);
    restyle();
    showSecret(false);
}

QLineEdit* AirSecretField::edit() const
{
    return field;
}

QString AirSecretField::text() const
{
    return field->text();
}

void AirSecretField::clear()
{
    field->clear();
    // A password shown on screen is hidden again once it is gone.
    eye->setChecked(false);
    setInvalid(false);
}

void AirSecretField::setPlaceholder(const QString& text)
{
    field->setPlaceholderText(text);
    field->setAccessibleName(text);
}

void AirSecretField::setInvalid(bool invalid_)
{
    if (invalid != invalid_) {
        invalid = invalid_;
        update();
    }
}

void AirSecretField::shake()
{
    if (air().reducedMotion() || !isVisible()) {
        return;
    }
    if (shaking) {
        shaking->stop();
        shiftTo(0);
    } else {
        rest = pos();
    }
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(0.0);
    animation->setEndValue(1.0);
    animation->setDuration(air().duration(Motion::reveal));
    connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        const qreal position = value.toReal() * (shakeStepCount - 1);
        const int step = qMin(static_cast<int>(position), shakeStepCount - 2);
        const qreal within = position - step;
        shiftTo(qRound(shakeSteps[step] + (shakeSteps[step + 1] - shakeSteps[step]) * within));
    });
    connect(animation, &QVariantAnimation::finished, this, [this] { shiftTo(0); });
    shaking = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirSecretField::shiftTo(int sideways)
{
    shifting = true;
    move(rest.x() + sideways, rest.y());
    shifting = false;
}

void AirSecretField::moveEvent(QMoveEvent* event)
{
    // Put somewhere by its layout: that is where it rests, and the shake goes on from there.
    if (!shifting) {
        rest = pos();
    }
    QWidget::moveEvent(event);
}

QSize AirSecretField::sizeHint() const
{
    return QSize(Size::dialog, Size::field);
}

QSize AirSecretField::minimumSizeHint() const
{
    return QSize(Size::control * 3, Size::field);
}

void AirSecretField::showSecret(bool shown)
{
    field->setEchoMode(shown ? QLineEdit::Normal : QLineEdit::Password);
    const QString words = AirStrings::text(shown ? Text::StartHidePassword : Text::StartShowPassword);
    eye->setToolTip(words);
    eye->setAccessibleName(words);
}

void AirSecretField::restyle()
{
    field->setFont(air().font(TextRole::Body));
    const QColor ink = air().color(Color::Ink);
    const QColor selected = air().color(Color::Selected);
    QPalette palette = field->palette();
    palette.setColor(QPalette::Base, Qt::transparent);
    palette.setColor(QPalette::Text, ink);
    palette.setColor(QPalette::Highlight, selected);
    palette.setColor(QPalette::HighlightedText, ink);
    palette.setColor(QPalette::PlaceholderText, air().color(Color::Ink3));
    field->setPalette(palette);
    // A window's legacy style sheet frames and paints every QLineEdit; the field's own wins.
    field->setStyleSheet(QStringLiteral("QLineEdit { background: transparent; border: none;"
                                        " padding: 0; margin: 0; color: %1;"
                                        " selection-background-color: %2; selection-color: %1; }")
                             .arg(ink.name(QColor::HexArgb), selected.name(QColor::HexArgb)));
    showSecret(eye->isChecked());
    place();
    update();
}

void AirSecretField::place()
{
    const int inset = (Size::field - Size::control) / 2;
    const bool rightToLeft = layoutDirection() == Qt::RightToLeft;
    const int eyeLeft = rightToLeft ? inset : width() - inset - Size::control;
    eye->setGeometry(eyeLeft, inset, Size::control, Size::control);
    // The words start a field's padding in from the start edge and stop short of the eye.
    const int textWidth = qMax(0, width() - Space::s12 - Size::control - 2 * inset);
    const int textLeft = rightToLeft ? Size::control + 2 * inset : Space::s12;
    field->setGeometry(textLeft, 0, textWidth, height());
}

void AirSecretField::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    // kq-input: red while what it holds cannot go, ink with the focus, darker under the pointer.
    QColor border = air().color(Color::LineStrong);
    if (invalid) {
        border = air().color(Color::Danger);
    } else if (field->hasFocus()) {
        border = air().color(Color::Ink);
    } else if (hovered) {
        border = air().color(Color::Ink3);
    }
    const QRectF box = QRectF(rect());
    AirPaint::box(painter, box, Radius::field, air().color(Color::Surface), border);
    if (AirPaint::keyboardFocus(field)) {
        AirPaint::focusRing(painter, box, Radius::field);
    }
}

void AirSecretField::resizeEvent(QResizeEvent*)
{
    place();
}

void AirSecretField::changeEvent(QEvent* event)
{
    if (event->type() == QEvent::LayoutDirectionChange) {
        place();
    }
    QWidget::changeEvent(event);
}

void AirSecretField::enterEvent(QEvent*)
{
    hovered = true;
    update();
}

void AirSecretField::leaveEvent(QEvent*)
{
    hovered = false;
    update();
}

bool AirSecretField::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == field && (event->type() == QEvent::FocusIn || event->type() == QEvent::FocusOut)) {
        update();
    }
    return QWidget::eventFilter(watched, event);
}

// ---- AirIdParts ----

namespace {
/** Where the titles and the groups of an ID stand: the key first, the antispam and the check under it. */
struct IdLayout
{
    QRect titles[3];
    QRect groups[idGroupCount];
    int width = 0;
    int height = 0;
};

IdLayout idLayout(const QString titles[3])
{
    const QFontMetrics mono(air().font(TextRole::Mono));
    const QFontMetrics caption(air().font(TextRole::Caption));
    const int groupWidth = mono.horizontalAdvance(QStringLiteral("0000"));
    const int gap = mono.horizontalAdvance(QLatin1Char(' '));
    const int monoLine = air().lineHeight(TextRole::Mono);
    const int captionLine = air().lineHeight(TextRole::Caption);
    IdLayout layout;
    const int left = Space::s16;
    int y = Space::s16;
    layout.titles[0] = QRect(left, y, caption.horizontalAdvance(titles[0]), captionLine);
    y += captionLine + Space::s4;
    for (int index = 0; index < 16; ++index) {
        const int column = index % keyGroupsPerLine;
        const int line = index / keyGroupsPerLine;
        layout.groups[index] =
            QRect(left + column * (groupWidth + gap), y + line * monoLine, groupWidth, monoLine);
    }
    const int keyWidth = keyGroupsPerLine * groupWidth + (keyGroupsPerLine - 1) * gap;
    y += 4 * monoLine + Space::s12;
    const int antispamWidth = qMax(2 * groupWidth + gap, caption.horizontalAdvance(titles[1]));
    const int checkLeft = left + antispamWidth + Space::s24;
    const int checkWidth = qMax(groupWidth, caption.horizontalAdvance(titles[2]));
    layout.titles[1] = QRect(left, y, caption.horizontalAdvance(titles[1]), captionLine);
    layout.titles[2] = QRect(checkLeft, y, caption.horizontalAdvance(titles[2]), captionLine);
    y += captionLine + Space::s4;
    layout.groups[16] = QRect(left, y, groupWidth, monoLine);
    layout.groups[17] = QRect(left + groupWidth + gap, y, groupWidth, monoLine);
    layout.groups[18] = QRect(checkLeft, y, groupWidth, monoLine);
    y += monoLine + Space::s16;
    layout.width = left + qMax(keyWidth, antispamWidth + Space::s24 + checkWidth) + Space::s16;
    layout.height = y;
    return layout;
}
} // namespace

AirIdParts::AirIdParts(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Fixed);
    // An ID reads left to right in every language.
    setLayoutDirection(Qt::LeftToRight);
    AirPaint::followTheme(this);
    connect(&AirStyle::instance(), &AirStyle::changed, this, [this] { updateGeometry(); });
}

void AirIdParts::setId(const QString& id)
{
    const Air::IdParts named = Air::idParts(id);
    parts[0] = Air::keyGroups(named.key);
    parts[1] = Air::keyGroups(named.antispam);
    parts[2] = Air::keyGroups(named.check);
    describe();
    update();
}

void AirIdParts::setTitles(const QString& key, const QString& antispam, const QString& check)
{
    titles[0] = key;
    titles[1] = antispam;
    titles[2] = check;
    describe();
    updateGeometry();
    update();
}

void AirIdParts::conceal()
{
    if (motion) {
        motion->stop();
    }
    revealed = 0;
    update();
}

void AirIdParts::reveal(bool animated)
{
    if (motion) {
        motion->stop();
    }
    if (!animated || air().reducedMotion()) {
        revealed = -1;
        update();
        return;
    }
    const int total = (idGroupCount - 1) * Motion::stagger + Motion::state;
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(0);
    animation->setEndValue(total);
    animation->setDuration(total);
    connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        revealed = value.toInt();
        update();
    });
    connect(animation, &QVariantAnimation::finished, this, [this] {
        revealed = -1;
        update();
    });
    motion = animation;
    revealed = 0;
    update();
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

qreal AirIdParts::groupProgress(int index) const
{
    if (revealed < 0) {
        return 1.0;
    }
    const qreal progress = static_cast<qreal>(revealed - index * Motion::stagger) / Motion::state;
    return air().easeOut().valueForProgress(qBound(0.0, progress, 1.0));
}

void AirIdParts::describe()
{
    QStringList words;
    for (int part = 0; part < 3; ++part) {
        if (!parts[part].isEmpty()) {
            words.append(titles[part] + QStringLiteral(": ") + parts[part].join(QLatin1Char(' ')));
        }
    }
    setAccessibleName(words.join(QStringLiteral("; ")));
}

QSize AirIdParts::sizeHint() const
{
    const IdLayout layout = idLayout(titles);
    return QSize(layout.width, layout.height);
}

QSize AirIdParts::minimumSizeHint() const
{
    return sizeHint();
}

void AirIdParts::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setPen(Qt::NoPen);
    painter.setBrush(air().color(Color::Sunken));
    painter.drawRoundedRect(rect(), Radius::card, Radius::card);

    const IdLayout layout = idLayout(titles);
    painter.setFont(air().font(TextRole::Caption));
    painter.setPen(air().color(Color::Ink3));
    for (int part = 0; part < 3; ++part) {
        painter.drawText(layout.titles[part], Qt::AlignLeft | Qt::AlignVCenter, titles[part]);
    }
    painter.setFont(air().font(TextRole::Mono));
    const QColor ink = air().color(Color::Ink);
    int index = 0;
    for (int part = 0; part < 3; ++part) {
        for (const QString& group : parts[part]) {
            if (index >= idGroupCount) {
                break;
            }
            const qreal progress = groupProgress(index);
            if (progress > 0) {
                painter.setOpacity(progress);
                painter.setPen(ink);
                const QRect place =
                    layout.groups[index].translated(0, qRound(revealRise * (1.0 - progress)));
                painter.drawText(place, Qt::AlignLeft | Qt::AlignVCenter, group);
            }
            ++index;
        }
    }
    painter.setOpacity(1.0);
}

// ---- AirPageStack ----

AirPageStack::AirPageStack(QWidget* parent)
    : QWidget(parent)
{
}

void AirPageStack::addPage(QWidget* page)
{
    page->setParent(this);
    page->setGeometry(rect());
    page->hide();
    pages.append(page);
}

QWidget* AirPageStack::currentPage() const
{
    return current;
}

bool AirPageStack::isMoving() const
{
    return motion;
}

void AirPageStack::showPage(QWidget* page, Travel travel)
{
    // One move at a time: a page still on its way lands where it was going.
    if (motion) {
        QVariantAnimation* const running = motion;
        motion.clear();
        running->stop();
        settle();
    }
    QWidget* const from = current;
    current = page;
    page->setGeometry(rect());
    if (!from || from == page || travel == Travel::None || !isVisible()) {
        if (from && from != page) {
            from->hide();
        }
        page->show();
        page->raise();
        emit arrived(page);
        return;
    }
    leaving = from;
    page->show();
    page->raise();
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(0.0);
    animation->setEndValue(1.0);
    animation->setEasingCurve(air().easeOut());
    if (air().reducedMotion()) {
        // Nothing slides: the page fades in over the one it replaces.
        auto* const fade = new QGraphicsOpacityEffect(page);
        fade->setOpacity(0.0);
        page->setGraphicsEffect(fade);
        const QPointer<QGraphicsOpacityEffect> effect(fade);
        animation->setDuration(air().duration(Motion::state));
        connect(animation, &QVariantAnimation::valueChanged, this, [effect](const QVariant& value) {
            if (effect) {
                effect->setOpacity(value.toReal());
            }
        });
    } else {
        // Forward comes in from the end edge of the interface, back from its start edge.
        const bool forward = travel == Travel::Forward;
        const bool rightToLeft = layoutDirection() == Qt::RightToLeft;
        const int side = forward != rightToLeft ? 1 : -1;
        const int span = width();
        const QPointer<QWidget> incoming(page);
        const QPointer<QWidget> outgoing(from);
        animation->setDuration(air().duration(Motion::layout));
        connect(animation, &QVariantAnimation::valueChanged, this,
                [incoming, outgoing, side, span](const QVariant& value) {
                    const qreal progress = value.toReal();
                    if (incoming) {
                        incoming->move(qRound(side * span * (1.0 - progress)), 0);
                    }
                    if (outgoing) {
                        outgoing->move(qRound(-side * span * progress), 0);
                    }
                });
        page->move(side * span, 0);
    }
    connect(animation, &QVariantAnimation::finished, this, &AirPageStack::settle);
    motion = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirPageStack::settle()
{
    // The animation that ended deletes itself later: the stack stands still from now.
    motion.clear();
    if (leaving && leaving != current) {
        leaving->hide();
        leaving->move(0, 0);
    }
    leaving = nullptr;
    if (current) {
        // An effect left at full opacity keeps painting through its own pixmap: it goes.
        current->setGraphicsEffect(nullptr);
        current->setGeometry(rect());
        emit arrived(current);
    }
}

void AirPageStack::resizeEvent(QResizeEvent*)
{
    for (const QPointer<QWidget>& page : pages) {
        if (page && (page != current || !motion)) {
            page->setGeometry(rect());
        }
    }
}
