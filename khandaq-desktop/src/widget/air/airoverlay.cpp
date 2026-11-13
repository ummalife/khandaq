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

#include "airoverlay.h"

#include "src/widget/air/airchatparts.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/air/airwidgets.h"

#include <QAccessible>
#include <QApplication>
#include <QBoxLayout>
#include <QClipboard>
#include <QFontMetrics>
#include <QGraphicsDropShadowEffect>
#include <QGraphicsOpacityEffect>
#include <QKeyEvent>
#include <QLabel>
#include <QMouseEvent>
#include <QPainter>
#include <QShortcut>
#include <QStyle>
#include <QVariantAnimation>

#include <algorithm>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Elevation = AirTokens::Elevation;
namespace Motion = AirTokens::Motion;
namespace Radius = AirTokens::Radius;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

void paintText(QWidget* widget, Color color)
{
    AirPaint::textColor(widget, air().color(color));
}

/** How long a toast stays: an Undo needs the time to be read and reached. */
int toastMs(bool hasAction)
{
    if (!hasAction) {
        return 4000;
    }
    return QAccessible::isActive() ? 10000 : 5000;
}

/** The rounded ground of a dialog or a toast, with the shadow of its elevation. */
class Plate : public QWidget
{
public:
    Plate(QWidget* parent, Color ground_, int radius_, const Elevation::Shadow& shadow)
        : QWidget(parent)
        , ground{ground_}
        , radius{radius_}
    {
        auto* const effect = new QGraphicsDropShadowEffect(this);
        effect->setOffset(shadow.x, shadow.y);
        effect->setBlurRadius(shadow.blur);
        effect->setColor(air().color(Color::Shadow));
        setGraphicsEffect(effect);
    }

    void restyle()
    {
        if (auto* const effect = static_cast<QGraphicsDropShadowEffect*>(graphicsEffect())) {
            effect->setColor(air().color(Color::Shadow));
        }
        update();
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        painter.setPen(Qt::NoPen);
        painter.setBrush(air().color(ground));
        painter.drawRoundedRect(rect(), radius, radius);
    }

private:
    Color ground;
    int radius;
};

/**
 * How see-through an overlay is on its way in or out. The fade is on the overlay only while it is
 * see-through: fully opaque, an opacity effect paints the overlay straight onto the window, and the
 * shadow of the plate inside then asks for a second painter on that window, which Qt refuses, so
 * the plate went missing for that frame.
 */
void fadeTo(QWidget* overlay, qreal opacity)
{
    if (opacity >= 1.0 || qFuzzyIsNull(opacity - 1.0)) {
        overlay->setGraphicsEffect(nullptr);
        return;
    }
    auto* fade = qobject_cast<QGraphicsOpacityEffect*>(overlay->graphicsEffect());
    if (!fade) {
        fade = new QGraphicsOpacityEffect(overlay);
        overlay->setGraphicsEffect(fade);
    }
    fade->setOpacity(opacity);
}

/** The open dialogs: while one is open, the shortcuts of the window behind it wait. */
int openDialogs = 0;

class ShortcutGate : public QObject
{
public:
    static ShortcutGate& instance()
    {
        static ShortcutGate* gate = new ShortcutGate();
        return *gate;
    }

protected:
    bool eventFilter(QObject* watched, QEvent* event) override
    {
        // A shortcut of the window ("/" for the command line, switching chats) would act behind
        // the dialog; menu actions such as Quit still work.
        return openDialogs > 0 && event->type() == QEvent::Shortcut
               && qobject_cast<QShortcut*>(watched) != nullptr;
    }
};

const char toastProperty[] = "airToast";
} // namespace

// ---- AirDialog ----

AirDialog* AirDialog::confirm(QWidget* anchor, const QString& title, const QString& message,
                              const QString& verb, Verb kind, std::function<void()> onConfirm)
{
    QWidget* const window = anchor ? anchor->window() : nullptr;
    if (!window) {
        return nullptr;
    }
    auto* const dialog = new AirDialog(window, kind);
    dialog->titleLabel->setText(title);
    dialog->messageLabel->setText(message);
    dialog->verbButton->setText(verb);
    dialog->cancelButton->setText(AirStrings::text(Text::Cancel));
    dialog->onConfirm = std::move(onConfirm);
    dialog->setAccessibleName(title);
    dialog->setAccessibleDescription(message);
    dialog->place();
    dialog->show();
    dialog->raise();
    (kind == Verb::Danger ? dialog->cancelButton : dialog->verbButton)->setFocus(Qt::OtherFocusReason);
    dialog->animateIn();
    return dialog;
}

AirDialog* AirDialog::notice(QWidget* anchor, const QString& title, const QString& message,
                             const QString& details)
{
    QWidget* const window = anchor ? anchor->window() : nullptr;
    if (!window) {
        return nullptr;
    }
    auto* const dialog = new AirDialog(window, Verb::Ink);
    dialog->titleLabel->setText(title);
    dialog->messageLabel->setText(message);
    dialog->verbButton->setText(AirStrings::text(Text::Ok));
    // Nothing to take back: OK is the only way out besides Esc.
    dialog->cancelButton->hide();
    dialog->details = details;
    if (!details.isEmpty()) {
        dialog->detailsButton->setText(AirStrings::text(Text::Details));
        dialog->detailsButton->show();
        dialog->detailsLabel->setText(AirStrings::text(Text::SupportCode).arg(details));
        dialog->copyButton->setText(AirStrings::text(Text::CopyCode));
    }
    dialog->setAccessibleName(title);
    dialog->setAccessibleDescription(message);
    dialog->place();
    dialog->show();
    dialog->raise();
    dialog->verbButton->setFocus(Qt::OtherFocusReason);
    dialog->animateIn();
    return dialog;
}

void AirDialog::addChoice(const QString& text, std::function<void()> onChoice)
{
    auto* const button = new AirButton(AirButton::Kind::Plain, card);
    button->setObjectName(QStringLiteral("dialogChoice%1").arg(choiceButtons.size()));
    button->setText(text);
    actionsLayout->insertWidget(actionsLayout->indexOf(verbButton), button);
    // The dialog is up already: a child made now shows only when told to.
    button->show();
    choiceButtons.append(button);
    connect(button, &AirButton::clicked, this, [this, onChoice] {
        if (done) {
            return;
        }
        // Closes as Cancel does, then takes the other way.
        finish(false);
        if (onChoice) {
            onChoice();
        }
    });
    place();
}

AirDialog::AirDialog(QWidget* window, Verb kind_)
    : QWidget(window)
    , kind{kind_}
    , previousFocus{QApplication::focusWidget()}
{
    setAttribute(Qt::WA_DeleteOnClose);
    setFocusPolicy(Qt::NoFocus);

    card = new Plate(this, Color::Raised, Radius::sheet, Elevation::dialog);
    auto* const layout = new QVBoxLayout(card);
    layout->setContentsMargins(Space::s20, Space::s20, Space::s20, Space::s20);
    layout->setSpacing(Space::s10);
    titleLabel = new QLabel(card);
    titleLabel->setTextFormat(Qt::PlainText);
    titleLabel->setWordWrap(true);
    messageLabel = new QLabel(card);
    messageLabel->setTextFormat(Qt::PlainText);
    messageLabel->setWordWrap(true);
    layout->addWidget(titleLabel);
    layout->addWidget(messageLabel);

    // The code for support of an error, under Details: the words never carry it.
    detailsRow = new QWidget(card);
    auto* const detailsLayout = new QHBoxLayout(detailsRow);
    detailsLayout->setContentsMargins(0, 0, 0, 0);
    detailsLayout->setSpacing(Space::s8);
    detailsLabel = new QLabel(detailsRow);
    detailsLabel->setTextFormat(Qt::PlainText);
    detailsLabel->setWordWrap(true);
    detailsLabel->setTextInteractionFlags(Qt::TextSelectableByMouse);
    copyButton = new AirButton(AirButton::Kind::Quiet, detailsRow);
    copyButton->setObjectName(QStringLiteral("dialogCopy"));
    detailsLayout->addWidget(detailsLabel, 1);
    detailsLayout->addWidget(copyButton, 0, Qt::AlignVCenter);
    layout->addWidget(detailsRow);
    detailsRow->hide();

    layout->addSpacing(Space::s8);
    actionsLayout = new QHBoxLayout();
    actionsLayout->setSpacing(Space::s8);
    detailsButton = new AirButton(AirButton::Kind::Quiet, card);
    detailsButton->setObjectName(QStringLiteral("dialogDetails"));
    detailsButton->hide();
    actionsLayout->addWidget(detailsButton, 0, Qt::AlignVCenter);
    actionsLayout->addStretch();
    cancelButton = new AirButton(AirButton::Kind::Plain, card);
    cancelButton->setObjectName(QStringLiteral("dialogCancel"));
    verbButton =
        new AirButton(kind == Verb::Danger ? AirButton::Kind::Danger : AirButton::Kind::Ink, card);
    verbButton->setObjectName(QStringLiteral("dialogConfirm"));
    actionsLayout->addWidget(cancelButton);
    actionsLayout->addWidget(verbButton);
    layout->addLayout(actionsLayout);

    connect(cancelButton, &AirButton::clicked, this, [this] { finish(false); });
    connect(verbButton, &AirButton::clicked, this, [this] { finish(true); });
    connect(detailsButton, &AirButton::clicked, this, &AirDialog::showDetails);
    connect(copyButton, &AirButton::clicked, this,
            [this] { QGuiApplication::clipboard()->setText(details); });
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirDialog::restyle);

    window->installEventFilter(this);
    if (openDialogs++ == 0) {
        qApp->installEventFilter(&ShortcutGate::instance());
    }
    restyle();
}

AirDialog::~AirDialog()
{
    if (--openDialogs == 0) {
        qApp->removeEventFilter(&ShortcutGate::instance());
    }
}

void AirDialog::restyle()
{
    titleLabel->setFont(air().font(TextRole::Heading));
    paintText(titleLabel, Color::Ink);
    messageLabel->setFont(air().font(TextRole::Body));
    paintText(messageLabel, Color::Ink2);
    detailsLabel->setFont(air().font(TextRole::Caption));
    paintText(detailsLabel, Color::Ink2);
    static_cast<Plate*>(card)->restyle();
    update();
}

void AirDialog::place()
{
    QWidget* const window = parentWidget();
    setGeometry(window->rect());
    const int width = qMin(Size::dialog, window->width() - 2 * Space::s16);
    card->setFixedWidth(width);
    stackActions(width - 2 * Space::s20);
    const int height = card->layout()->heightForWidth(width);
    card->setFixedHeight(height > 0 ? height : card->sizeHint().height());
    const int lift = qRound((1 - progress) * Space::s8);
    card->move((window->width() - width) / 2, (window->height() - card->height()) / 2 + lift);
}

void AirDialog::stackActions(int room)
{
    QVector<AirButton*> buttons{detailsButton, cancelButton};
    buttons += choiceButtons;
    buttons.append(verbButton);
    int needed = -actionsLayout->spacing();
    for (AirButton* const button : buttons) {
        if (button->isVisibleTo(card)) {
            needed += button->sizeHint().width() + actionsLayout->spacing();
        }
    }
    const bool stack = needed > room;
    if (stack == stacked) {
        return;
    }
    stacked = stack;
    // The row is laid out from the end: the verb comes first, Cancel under the other ways on.
    actionsLayout->setDirection(stack ? QBoxLayout::BottomToTop : QBoxLayout::LeftToRight);
    for (AirButton* const button : buttons) {
        button->setSizePolicy(stack ? QSizePolicy::Preferred : QSizePolicy::Fixed, QSizePolicy::Fixed);
    }
}

void AirDialog::animateIn()
{
    fadeTo(this, 0.0);
    auto* const animation = new QVariantAnimation(this);
    motion = animation;
    animation->setStartValue(0.0);
    animation->setEndValue(1.0);
    animation->setDuration(air().duration(Motion::layout));
    animation->setEasingCurve(air().easeOut());
    connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        // With less motion the dialog only fades in.
        progress = air().reducedMotion() ? 1.0 : value.toReal();
        fadeTo(this, value.toReal());
        place();
    });
    connect(animation, &QVariantAnimation::finished, this, [this] {
        progress = 1;
        fadeTo(this, 1.0);
        place();
    });
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirDialog::finish(bool confirmed)
{
    if (done) {
        return;
    }
    done = true;
    if (motion) {
        motion->stop();
    }
    hide();
    if (previousFocus) {
        previousFocus->setFocus(Qt::OtherFocusReason);
    }
    emit finished(confirmed);
    if (confirmed && onConfirm) {
        onConfirm();
    }
    close();
}

void AirDialog::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.fillRect(rect(), air().color(Color::Scrim));
}

void AirDialog::mousePressEvent(QMouseEvent* event)
{
    if (!card->geometry().contains(event->pos())) {
        finish(false);
    }
}

void AirDialog::keyPressEvent(QKeyEvent* event)
{
    if (event->key() == Qt::Key_Escape) {
        finish(false);
        return;
    }
    if (event->key() == Qt::Key_Return || event->key() == Qt::Key_Enter) {
        if (auto* const button = qobject_cast<AirButton*>(QApplication::focusWidget())) {
            button->click();
            return;
        }
    }
    QWidget::keyPressEvent(event);
}

bool AirDialog::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == parentWidget() && event->type() == QEvent::Resize) {
        place();
    }
    return QWidget::eventFilter(watched, event);
}

void AirDialog::showDetails()
{
    detailsButton->hide();
    detailsRow->show();
    place();
    copyButton->setFocus(Qt::OtherFocusReason);
}

QVector<AirButton*> AirDialog::focusOrder() const
{
    QVector<AirButton*> order;
    // Copy hides with its row, not on its own, so what counts is whether the card would show it.
    for (AirButton* const button : {copyButton, detailsButton, cancelButton}) {
        if (button->isVisibleTo(card)) {
            order.append(button);
        }
    }
    order += choiceButtons;
    order.append(verbButton);
    if (stacked) {
        std::reverse(order.begin(), order.end());
    }
    return order;
}

bool AirDialog::focusNextPrevChild(bool next)
{
    // The keyboard stays in the dialog while it is open, going round its buttons.
    const QVector<AirButton*> order = focusOrder();
    int at = -1;
    for (int i = 0; i < order.size(); ++i) {
        if (order[i]->hasFocus()) {
            at = i;
            break;
        }
    }
    const int count = order.size();
    const int target = at < 0 ? 0 : (at + (next ? 1 : count - 1)) % count;
    order[target]->setFocus(next ? Qt::TabFocusReason : Qt::BacktabFocusReason);
    return true;
}

// ---- AirToast ----

AirToast* AirToast::post(QWidget* anchor, const QString& text, const QString& action,
                         std::function<void()> onAction, std::function<void()> onExpire)
{
    QWidget* const window = anchor ? anchor->window() : nullptr;
    if (!window) {
        if (onExpire) {
            onExpire();
        }
        return nullptr;
    }
    dismiss(window);
    auto* const toast = new AirToast(window, text, action);
    toast->onAction = std::move(onAction);
    toast->onExpire = std::move(onExpire);
    window->setProperty(toastProperty, QVariant::fromValue<QObject*>(toast));
    toast->place();
    toast->show();
    toast->raise();
    toast->remainingMs = toastMs(!action.isEmpty());
    toast->shown.start();
    toast->timer.start(toast->remainingMs);
    toast->animate(true);
    QAccessibleEvent alert(toast, QAccessible::Alert);
    QAccessible::updateAccessibility(&alert);
    return toast;
}

void AirToast::dismiss(QWidget* anchor)
{
    QWidget* const window = anchor ? anchor->window() : nullptr;
    if (!window) {
        return;
    }
    auto* const toast = qobject_cast<AirToast*>(window->property(toastProperty).value<QObject*>());
    if (toast) {
        toast->expire();
    }
}

AirToast::AirToast(QWidget* window, const QString& text, const QString& action)
    : QWidget(window)
    , words{text}
{
    setAttribute(Qt::WA_DeleteOnClose);
    setAccessibleName(action.isEmpty() ? text : text + QLatin1Char(' ') + action);

    // The shadow needs room around the plate.
    const Elevation::Shadow& shadow = Elevation::toast;
    auto* const outer = new QHBoxLayout(this);
    outer->setContentsMargins(shadow.blur, shadow.blur, shadow.blur, shadow.blur + shadow.y);
    card = new Plate(this, Color::Ink, Radius::card, shadow);
    outer->addWidget(card);
    auto* const row = new QHBoxLayout(card);
    row->setContentsMargins(Space::s16, Space::s10, Space::s12, Space::s10);
    row->setSpacing(Space::s16);
    label = new QLabel(text, card);
    label->setTextFormat(Qt::PlainText);
    label->setWordWrap(true);
    row->addWidget(label, 1);
    if (!action.isEmpty()) {
        actionButton = new AirButton(AirButton::Kind::OnInk, card);
        actionButton->setObjectName(QStringLiteral("toastAction"));
        actionButton->setText(action);
        row->addWidget(actionButton, 0, Qt::AlignVCenter);
        connect(actionButton, &AirButton::clicked, this, &AirToast::act);
    }

    timer.setSingleShot(true);
    connect(&timer, &QTimer::timeout, this, &AirToast::expire);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirToast::restyle);
    window->installEventFilter(this);
    restyle();
}

AirToast::~AirToast()
{
    QWidget* const window = parentWidget();
    if (window && window->property(toastProperty).value<QObject*>() == this) {
        window->setProperty(toastProperty, QVariant());
    }
}

QString AirToast::text() const
{
    return words;
}

void AirToast::restyle()
{
    label->setFont(air().font(TextRole::Label));
    paintText(label, Color::OnInk);
    static_cast<Plate*>(card)->restyle();
}

void AirToast::place()
{
    QWidget* const window = parentWidget();
    // As wide as the words on one line, up to the width of a dialog. The hint of a wrapping label
    // would break even two short words in two.
    const int shadow = 2 * Elevation::toast.blur;
    const int chrome = sizeHint().width() - label->sizeHint().width();
    const int oneLine = chrome + QFontMetrics(label->font()).horizontalAdvance(words) + 1;
    const int widest = qMin(Size::dialog + shadow, window->width() - 2 * Space::s16);
    QSize size(qMin(oneLine, widest), sizeHint().height());
    if (layout()->hasHeightForWidth()) {
        size.setHeight(layout()->heightForWidth(size.width()));
    }
    resize(size);
    // Above the composer of the conversation on screen when there is one, so it hides neither the
    // field nor the hint under it; else at the foot of the window.
    int floor = window->height() - Space::s24;
    int centre = window->width() / 2;
    for (QWidget* const composer : window->findChildren<QWidget*>(QStringLiteral("airComposer"))) {
        if (composer->isVisible()) {
            const QRect area(composer->mapTo(window, QPoint(0, 0)), composer->size());
            floor = area.top() - Space::s8;
            centre = area.center().x();
            break;
        }
    }
    const int lift = qRound((1 - progress) * Space::s8);
    const int left = qBound(Space::s16 - Elevation::toast.blur, centre - size.width() / 2,
                            window->width() - size.width() - Space::s16 + Elevation::toast.blur);
    move(left, floor - size.height() + Elevation::toast.blur + lift);
}

void AirToast::animate(bool in)
{
    if (motion) {
        motion->stop();
    }
    fadeTo(this, in ? 0.0 : 1.0);
    auto* const animation = new QVariantAnimation(this);
    motion = animation;
    animation->setStartValue(in ? 0.0 : 1.0);
    animation->setEndValue(in ? 1.0 : 0.0);
    animation->setDuration(air().duration(in ? Motion::state : Motion::tap));
    animation->setEasingCurve(air().easeOut());
    connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        // It rises from below; with less motion it only fades.
        progress = air().reducedMotion() ? 1.0 : value.toReal();
        fadeTo(this, value.toReal());
        place();
    });
    connect(animation, &QVariantAnimation::finished, this, [this, in] {
        if (in) {
            progress = 1;
            fadeTo(this, 1.0);
            place();
        } else {
            QWidget::close();
        }
    });
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirToast::expire()
{
    close(false);
}

void AirToast::act()
{
    close(true);
}

void AirToast::close(bool acted)
{
    if (closing) {
        return;
    }
    closing = true;
    timer.stop();
    QWidget* const window = parentWidget();
    if (window && window->property(toastProperty).value<QObject*>() == this) {
        window->setProperty(toastProperty, QVariant());
    }
    const std::function<void()> run = acted ? onAction : onExpire;
    onAction = nullptr;
    onExpire = nullptr;
    if (run) {
        run();
    }
    animate(false);
}

void AirToast::paintEvent(QPaintEvent*)
{
    // Transparent: the plate paints itself and its shadow.
}

void AirToast::enterEvent(QEvent* event)
{
    // The toast stays while the pointer is over it.
    if (timer.isActive()) {
        remainingMs = qMax(0, remainingMs - static_cast<int>(shown.elapsed()));
        timer.stop();
    }
    QWidget::enterEvent(event);
}

void AirToast::leaveEvent(QEvent* event)
{
    if (!closing && !timer.isActive()) {
        // Some time to read it again, never less than a second and a half.
        remainingMs = qMax(remainingMs, 1500);
        shown.restart();
        timer.start(remainingMs);
    }
    QWidget::leaveEvent(event);
}

bool AirToast::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == parentWidget() && event->type() == QEvent::Resize) {
        place();
    }
    return QWidget::eventFilter(watched, event);
}

// ---- AirBanner ----

namespace {
const char bannerProperty[] = "airBanner";

/** The ground of the band, with its words painted on it. */
class BannerCard : public Plate
{
public:
    explicit BannerCard(QWidget* parent)
        : Plate(parent, Color::Raised, Radius::card, Elevation::lift)
    {
        setCursor(Qt::PointingHandCursor);
    }

    QString title;
    QStringList lines;
    /** Where the words stand, as in a left-to-right interface. */
    QRect words;

protected:
    void paintEvent(QPaintEvent* event) override
    {
        Plate::paintEvent(event);
        QPainter painter(this);
        const Qt::LayoutDirection direction = layoutDirection();
        const int flags =
            static_cast<int>(QStyle::visualAlignment(direction, Qt::AlignLeft | Qt::AlignVCenter));
        const int lineHeight = air().lineHeight(TextRole::Label);
        QFont titleFont = air().font(TextRole::Label);
        titleFont.setWeight(QFont::DemiBold);
        painter.setFont(titleFont);
        painter.setPen(air().color(Color::Ink));
        int y = words.top();
        AirPaint::ownText(painter,
                          QStyle::visualRect(direction, rect(),
                                             QRect(words.left(), y, words.width(), lineHeight)),
                          flags, title);
        y += lineHeight + Space::s2;
        painter.setFont(air().font(TextRole::Label));
        painter.setPen(air().color(Color::Ink2));
        for (const QString& line : lines) {
            AirPaint::ownText(painter,
                              QStyle::visualRect(direction, rect(),
                                                 QRect(words.left(), y, words.width(), lineHeight)),
                              flags, line);
            y += lineHeight;
        }
    }
};

/** The middle of the conversation column: over its composer, else beside the list, else the window's. */
int columnCentre(QWidget* window)
{
    for (QWidget* const composer : window->findChildren<QWidget*>(QStringLiteral("airComposer"))) {
        if (composer->isVisible()) {
            return QRect(composer->mapTo(window, QPoint(0, 0)), composer->size()).center().x();
        }
    }
    QWidget* const list = window->findChild<QWidget*>(QStringLiteral("airList"));
    if (list && list->isVisible()) {
        const QRect area(list->mapTo(window, QPoint(0, 0)), list->size());
        // The column takes the rest of the window, on the far side of the list.
        return area.center().x() < window->width() / 2 ? (area.right() + 1 + window->width()) / 2
                                                       : area.left() / 2;
    }
    return window->width() / 2;
}

/** The foot of the Air band in the window, where the band comes out from; 0 without one. */
int bandFoot(QWidget* window)
{
    QWidget* const band = window->findChild<QWidget*>(QStringLiteral("airBand"));
    if (!band || !band->isVisible()) {
        return 0;
    }
    return band->mapTo(window, QPoint(0, band->height())).y();
}
} // namespace

constexpr int AirBanner::stayMs;

AirBanner* AirBanner::post(QWidget* anchor, const Content& content, std::function<void()> onOpen)
{
    QWidget* const window = anchor ? anchor->window() : nullptr;
    if (!window || !window->isVisible()) {
        return nullptr;
    }
    // A question over the window keeps it; the list keeps the count of what came.
    for (AirDialog* const dialog : window->findChildren<AirDialog*>()) {
        if (dialog->isVisible()) {
            return nullptr;
        }
    }
    if (AirBanner* const old = current(window)) {
        // In place of the band up now, without its way out.
        old->closing = true;
        old->timer.stop();
        old->onOpen = nullptr;
        window->setProperty(bannerProperty, QVariant());
        old->hide();
        old->deleteLater();
    }
    auto* const banner = new AirBanner(window, content);
    banner->onOpen = std::move(onOpen);
    window->setProperty(bannerProperty, QVariant::fromValue<QObject*>(banner));
    banner->progress = 0;
    banner->place();
    banner->show();
    banner->raise();
    banner->shown.start();
    banner->timer.start(banner->remainingMs);
    banner->animate(true);
    QAccessibleEvent alert(banner, QAccessible::Alert);
    QAccessible::updateAccessibility(&alert);
    return banner;
}

void AirBanner::dismiss(QWidget* anchor)
{
    if (AirBanner* const banner = current(anchor)) {
        banner->close(false);
    }
}

AirBanner* AirBanner::current(QWidget* anchor)
{
    QWidget* const window = anchor ? anchor->window() : nullptr;
    if (!window) {
        return nullptr;
    }
    return qobject_cast<AirBanner*>(window->property(bannerProperty).value<QObject*>());
}

AirBanner::AirBanner(QWidget* window, const Content& content)
    : QWidget(window)
    , data{content}
{
    setAttribute(Qt::WA_DeleteOnClose);
    setFocusPolicy(Qt::NoFocus);
    setAccessibleName(data.body.isEmpty() ? data.title : data.title + QStringLiteral(", ") + data.body);
    setAccessibleDescription(data.hint);

    card = new BannerCard(this);
    tile = new AirTile(Size::tileDesk, card);
    switch (data.tile) {
    case Tile::Person:
        tile->setKind(AirTile::Kind::Person);
        break;
    case Tile::Group:
        tile->setKind(AirTile::Kind::Group);
        break;
    case Tile::Request:
        tile->setKind(AirTile::Kind::Request);
        break;
    }
    tile->setPerson(data.tileName, data.tone);
    tile->setPhoto(data.photo);

    timer.setSingleShot(true);
    connect(&timer, &QTimer::timeout, this, [this] { close(false); });
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirBanner::restyle);
    window->installEventFilter(this);
    restyle();
}

AirBanner::~AirBanner()
{
    QWidget* const window = parentWidget();
    if (window && window->property(bannerProperty).value<QObject*>() == this) {
        window->setProperty(bannerProperty, QVariant());
    }
}

const AirBanner::Content& AirBanner::content() const
{
    return data;
}

void AirBanner::restyle()
{
    static_cast<Plate*>(card)->restyle();
    place();
    card->update();
}

void AirBanner::place()
{
    QWidget* const window = parentWidget();
    if (!window) {
        return;
    }
    auto* const plate = static_cast<BannerCard*>(card);
    const Elevation::Shadow& shadow = Elevation::lift;
    const int cardWidth =
        qMax(Size::tileDesk * 4, qMin(Size::dialog, window->width() - 2 * Space::s16));
    const int side = Size::tileDesk;
    const int textLeft = Space::s12 + side + Space::s12;
    const int textWidth = cardWidth - textLeft - Space::s16;
    QFont titleFont = air().font(TextRole::Label);
    titleFont.setWeight(QFont::DemiBold);
    plate->title = QFontMetrics(titleFont).elidedText(data.title, Qt::ElideRight, textWidth);
    plate->lines = AirPaint::linesOf(data.body, air().font(TextRole::Label), textWidth, 2);
    const int lineHeight = air().lineHeight(TextRole::Label);
    const int block =
        lineHeight + (plate->lines.isEmpty() ? 0 : Space::s2 + plate->lines.size() * lineHeight);
    const int cardHeight = Space::s12 + qMax(side, block) + Space::s12;
    plate->words = QRect(textLeft, (cardHeight - block) / 2, textWidth, block);

    card->setGeometry(shadow.blur, shadow.blur, cardWidth, cardHeight);
    const QSize hint = tile->sizeHint();
    const int extra = hint.height() - side;
    tile->setGeometry(QStyle::visualRect(layoutDirection(), card->rect(),
                                         QRect(Space::s12, (cardHeight - side) / 2 - extra,
                                               hint.width(), hint.height())));
    resize(cardWidth + 2 * shadow.blur, cardHeight + 2 * shadow.blur + shadow.y);

    // At rest under the Air band; on the way it comes out from under the band's foot.
    const int foot = bandFoot(window);
    const int travel = cardHeight + Space::s12;
    const int lift = qRound((1 - progress) * travel) + dragLift;
    const int cardTop = foot + Space::s12 - lift;
    const int centre = columnCentre(window);
    const int left = qBound(Space::s16 - shadow.blur, centre - width() / 2,
                            window->width() - width() - Space::s16 + shadow.blur);
    move(left, cardTop - shadow.blur);
    const int under = foot - y();
    if (under >= height()) {
        // Wholly under the band: nothing of it shows.
        setMask(QRegion(0, 0, 1, 1));
    } else if (under > 0) {
        setMask(QRegion(0, under, width(), height() - under));
    } else {
        clearMask();
    }
    plate->update();
}

void AirBanner::animate(bool in)
{
    if (motion) {
        motion->stop();
    }
    const qreal from = in ? 0.0 : 1.0;
    fadeTo(this, from);
    auto* const animation = new QVariantAnimation(this);
    motion = animation;
    animation->setStartValue(from);
    animation->setEndValue(1.0 - from);
    // It comes down in the layout time and leaves in the state time.
    animation->setDuration(air().duration(in ? Motion::layout : Motion::state));
    animation->setEasingCurve(air().easeOut());
    connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        // With less motion it only fades, in its place.
        progress = air().reducedMotion() ? 1.0 : value.toReal();
        fadeTo(this, value.toReal());
        place();
    });
    connect(animation, &QVariantAnimation::finished, this, [this, in] {
        if (in) {
            progress = 1;
            fadeTo(this, 1.0);
            place();
        } else {
            QWidget::close();
        }
    });
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirBanner::close(bool opened)
{
    if (closing) {
        return;
    }
    closing = true;
    timer.stop();
    QWidget* const window = parentWidget();
    if (window && window->property(bannerProperty).value<QObject*>() == this) {
        window->setProperty(bannerProperty, QVariant());
    }
    const std::function<void()> run = opened ? onOpen : nullptr;
    onOpen = nullptr;
    animate(false);
    if (run) {
        run();
    }
}

void AirBanner::paintEvent(QPaintEvent*)
{
    // Transparent: the card paints itself, its shadow and its words.
}

void AirBanner::mousePressEvent(QMouseEvent* event)
{
    if (closing || event->button() != Qt::LeftButton || !card->geometry().contains(event->pos())) {
        QWidget::mousePressEvent(event);
        return;
    }
    pressed = true;
    pressedAt = event->globalPos();
    dragLift = 0;
    event->accept();
}

void AirBanner::mouseMoveEvent(QMouseEvent* event)
{
    if (!pressed) {
        QWidget::mouseMoveEvent(event);
        return;
    }
    // Up only, as the band came down.
    dragLift = qMax(0, pressedAt.y() - event->globalPos().y());
    place();
}

void AirBanner::mouseReleaseEvent(QMouseEvent* event)
{
    if (!pressed) {
        QWidget::mouseReleaseEvent(event);
        return;
    }
    pressed = false;
    const QPoint moved = event->globalPos() - pressedAt;
    if (dragLift > Space::s20) {
        close(false);
        return;
    }
    if (moved.manhattanLength() < QApplication::startDragDistance()) {
        close(true);
        return;
    }
    // Not far enough: it goes back to its place.
    dragLift = 0;
    place();
}

void AirBanner::enterEvent(QEvent* event)
{
    // It stays while the pointer is over it.
    if (timer.isActive()) {
        remainingMs = qMax(0, remainingMs - static_cast<int>(shown.elapsed()));
        timer.stop();
    }
    QWidget::enterEvent(event);
}

void AirBanner::leaveEvent(QEvent* event)
{
    if (!closing && !timer.isActive()) {
        // Some time to read it again, never less than a second and a half.
        remainingMs = qMax(remainingMs, 1500);
        shown.restart();
        timer.start(remainingMs);
    }
    QWidget::leaveEvent(event);
}

bool AirBanner::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == parentWidget() && event->type() == QEvent::Resize) {
        place();
    }
    return QWidget::eventFilter(watched, event);
}
