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

#include "airtray.h"

#include "src/persistence/smileypack.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"

#include <QAccessible>
#include <QContextMenuEvent>
#include <QGraphicsOpacityEffect>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QLabel>
#include <QMenu>
#include <QMouseEvent>
#include <QPainter>
#include <QPointer>
#include <QScrollArea>
#include <QScrollBar>
#include <QStyle>
#include <QTimer>
#include <QVBoxLayout>

#include <algorithm>
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

void paintText(QLabel* label, Color color)
{
    AirPaint::textColor(label, air().color(color));
}

QLabel* makeLabel(TextRole role, QWidget* parent)
{
    auto* const label = new QLabel(parent);
    label->setFont(air().font(role));
    label->setTextFormat(Qt::PlainText);
    return label;
}

/** The kq-queue card stands this far in from the sides of the column and above the field. */
constexpr int trayInset = Space::s12;
constexpr int trayBelow = Space::s10;
} // namespace

bool AirQueueItem::operator==(const AirQueueItem& other) const
{
    return id == other.id && time == other.time && text == other.text;
}

// ---- AirQueueRow ----

AirQueueRow::AirQueueRow(const AirQueueItem& item, Air::EntryStatus status_, QWidget* parent)
    : QWidget(parent)
    , itemId{item.id}
    , status{status_}
    , time{item.time}
    , text{item.text}
{
    setCursor(Qt::PointingHandCursor);
    // Its actions are reached from the keyboard as with a click.
    setFocusPolicy(Qt::StrongFocus);

    // kq-entry: the time column, the text at most 760 px wide and the 18 px mark after it.
    auto* const row = new QHBoxLayout(this);
    row->setContentsMargins(0, Space::s4, 0, Space::s4);
    row->setSpacing(Space::s10);

    timeLabel = makeLabel(TextRole::Caption, this);
    timeLabel->setFixedWidth(Size::timeColumn);
    timeLabel->setContentsMargins(0, Space::s2, 0, 0);
    timeLabel->setText(item.time);
    row->addWidget(timeLabel, 0, Qt::AlignTop);

    textColumn = new QVBoxLayout();
    textColumn->setSpacing(0);
    textLabel = makeLabel(TextRole::Body, this);
    // KHANDAQ (QA live 2026-11-14): as plain text, the row drew 👨‍⚕️ as 👨 and ⚕ while the message
    // showed one picture. As rich text its joined sequences are in the emoji font, as there.
    textLabel->setTextFormat(Qt::RichText);
    // Rich text gives the label a text control that takes clicks and has a menu of its own (Copy,
    // Select All): the clicks and the menu stay the row's, as with the plain text.
    textLabel->setAttribute(Qt::WA_TransparentForMouseEvents);
    textLabel->setWordWrap(true);
    textLabel->setText(SmileyPack::plainTextAsRichText(item.text));
    textLabel->setMaximumWidth(Size::textMeasure);
    textLabel->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Preferred);
    textColumn->addWidget(textLabel);
    row->addLayout(textColumn, 1);

    mark = new QLabel(this);
    mark->setFixedSize(Size::mark, Size::mark);
    mark->setAlignment(Qt::AlignCenter);
    row->addWidget(mark, 0, Qt::AlignTop);
    // Past the measure the row stays empty; without this the layout would centre each part in
    // its share of the spare width.
    row->addStretch();

    restyle();
    retranslate();
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirQueueRow::restyle);
    Translator::registerHandler(std::bind(&AirQueueRow::retranslate, this), this);
}

AirQueueRow::~AirQueueRow()
{
    Translator::unregister(this);
}

qint64 AirQueueRow::id() const
{
    return itemId;
}

void AirQueueRow::addRetry()
{
    if (retryButton) {
        return;
    }
    retryButton = new AirButton(AirButton::Kind::Retry, this);
    textColumn->addWidget(retryButton, 0, Qt::AlignLeading);
    connect(retryButton, &AirButton::clicked, this, [this] { emit retryRequested(itemId); });
    retranslate();
}

void AirQueueRow::retranslate()
{
    // The time and the words stand at the start edge of the interface, next to each other,
    // whatever script the words are in: a QLabel would align them by their own direction.
    const Qt::Alignment start =
        QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft) | Qt::AlignTop;
    timeLabel->setAlignment(start);
    textLabel->setAlignment(start);
    setToolTip(AirStrings::text(status == Air::EntryStatus::Failed ? Text::FailedItemHint
                                                                   : Text::TrayItemHint));
    mark->setAccessibleName(AirStrings::statusText(status));
    // Read as a row of the list is: its time, its words, its state.
    setAccessibleName(
        QStringList{time, text, AirStrings::statusText(status)}.join(QStringLiteral(", ")));
    setAccessibleDescription(toolTip());
    if (retryButton) {
        retryButton->setText(AirStrings::text(Text::FailedRetry));
        retryButton->setToolTip(AirStrings::text(Text::FailedRetryHint));
    }
}

void AirQueueRow::restyle()
{
    timeLabel->setFont(air().font(TextRole::Caption));
    textLabel->setFont(air().font(TextRole::Body));
    paintText(timeLabel, Color::Ink3);
    paintText(textLabel, Color::Ink);
    const QString icon = Air::statusIconName(status);
    const Color color = status == Air::EntryStatus::Failed ? Color::Danger : Color::Transit;
    mark->setPixmap(icon.isEmpty()
                        ? QPixmap()
                        : air().icon(icon, air().color(color), Size::iconSmall, devicePixelRatioF()));
    mark->setAccessibleName(AirStrings::statusText(status));
}

void AirQueueRow::mouseReleaseEvent(QMouseEvent* event)
{
    if (event->button() == Qt::LeftButton && rect().contains(event->pos())) {
        emit actionsRequested(itemId, event->globalPos());
        return;
    }
    QWidget::mouseReleaseEvent(event);
}

void AirQueueRow::contextMenuEvent(QContextMenuEvent* event)
{
    emit actionsRequested(itemId, event->globalPos());
}

void AirQueueRow::keyPressEvent(QKeyEvent* event)
{
    switch (event->key()) {
    case Qt::Key_Return:
    case Qt::Key_Enter:
    case Qt::Key_Space:
    case Qt::Key_Menu:
        // The menu opens under the words, where a click would have opened it.
        emit actionsRequested(itemId, textLabel->mapToGlobal(QPoint(0, textLabel->height())));
        return;
    default:
        QWidget::keyPressEvent(event);
    }
}

void AirQueueRow::focusInEvent(QFocusEvent* event)
{
    QWidget::focusInEvent(event);
    update();
}

void AirQueueRow::focusOutEvent(QFocusEvent* event)
{
    QWidget::focusOutEvent(event);
    update();
}

void AirQueueRow::paintEvent(QPaintEvent*)
{
    if (AirPaint::keyboardFocus(this)) {
        QPainter painter(this);
        AirPaint::focusRing(painter, QRectF(rect()), Radius::small);
    }
}

// ---- AirTray ----

AirTray::AirTray(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Maximum);
    auto* const outer = new QVBoxLayout(this);
    // kq-queue: 8 12 6 inside a card that stands 12 in from the sides and 10 above the field.
    outer->setContentsMargins(trayInset + Space::s12, Space::s8, trayInset + Space::s12,
                              Space::s6 + trayBelow);
    outer->setSpacing(0);

    auto* const head = new QHBoxLayout();
    head->setSpacing(Space::s8);
    auto* const headText = new QVBoxLayout();
    headText->setSpacing(Space::s2);
    title = makeLabel(TextRole::Label, this);
    detail = makeLabel(TextRole::Caption, this);
    detail->setWordWrap(true);
    headText->addWidget(title);
    headText->addWidget(detail);
    head->addLayout(headText, 1);
    chevron = new AirIconButton(QStringLiteral("chevron-down"), this);
    chevron->setObjectName(QStringLiteral("trayChevron"));
    chevron->setFixedSize(Size::controlSmall, Size::controlSmall);
    head->addWidget(chevron, 0, Qt::AlignTop);
    outer->addLayout(head);
    outer->addSpacing(Space::s4);

    scroller = new QScrollArea(this);
    scroller->setFrameShape(QFrame::NoFrame);
    scroller->setWidgetResizable(true);
    scroller->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    // Tab stops on the messages, which the scroller brings into view, not on the scroller.
    scroller->setFocusPolicy(Qt::NoFocus);
    list = new QWidget(scroller);
    listLayout = new QVBoxLayout(list);
    listLayout->setContentsMargins(0, 0, 0, 0);
    listLayout->setSpacing(0);
    // The messages keep their own height; a list taller than them leaves the rest empty.
    listLayout->addStretch();
    // setWidget() turns the list's own background on; restyle() turns it off again.
    scroller->setWidget(list);
    outer->addWidget(scroller);

    connect(chevron, &AirIconButton::clicked, this, [this] { setExpanded(!expanded); });
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirTray::restyle);
    Translator::registerHandler(std::bind(&AirTray::retranslate, this), this);
    restyle();
    setExpanded(true);
    hide();
}

AirTray::~AirTray()
{
    Translator::unregister(this);
}

void AirTray::restyle()
{
    // The messages sit on the tray's own colour, which paintEvent() draws. The window's sheet gives
    // every scroll area the ground colour, and Widget::reloadTheme() clears the sheets of the Air
    // widgets, so the scroll area's own sheet is set again on every theme change.
    scroller->setStyleSheet(QStringLiteral("QScrollArea { background: transparent; }"));
    scroller->viewport()->setAutoFillBackground(false);
    list->setAutoFillBackground(false);
    title->setFont(air().font(TextRole::Label));
    detail->setFont(air().font(TextRole::Caption));
    paintText(title, Color::Transit);
    paintText(detail, Color::Ink2);
    update();
}

bool AirTray::isEmpty() const
{
    return shown.isEmpty();
}

void AirTray::setMaximumItemsHeight(int height)
{
    if (itemsHeightLimit != height) {
        itemsHeightLimit = height;
        fitItems();
    }
}

void AirTray::limitTo(QWidget* reference, qreal share)
{
    if (heightReference) {
        heightReference->removeEventFilter(this);
    }
    heightReference = reference;
    heightShare = share;
    if (reference) {
        reference->installEventFilter(this);
        followReference();
    }
}

void AirTray::followReference()
{
    // Read once: a QPointer read again after each call may be null to the compiler.
    QWidget* const reference = heightReference.data();
    if (!reference) {
        return;
    }
    const int height = reference->height();
    // The share keeps the transcript in view. A column too short for it keeps whole what stands
    // around the list instead: the list takes the room they leave and scrolls, down to one
    // message. Their room is the column's least height without the list's own.
    const int ownHeight = scroller->isVisibleTo(reference) ? scroller->minimumHeight() : 0;
    const int around = reference->minimumSizeHint().height() - ownHeight;
    int oneMessage = 0;
    if (!rows.isEmpty()) {
        const QWidget* const first = rows.first();
        const int width = scroller->viewport()->width();
        oneMessage = first->hasHeightForWidth() && width > 0 ? first->heightForWidth(width)
                                                             : first->sizeHint().height();
    }
    const int room = std::max(height - around, oneMessage);
    // 0 would mean no limit at all.
    setMaximumItemsHeight(std::max(1, std::min(qRound(height * heightShare), room)));
}

bool AirTray::eventFilter(QObject* watched, QEvent* event)
{
    // A resize changes the column's height; a layout request, what else stands in it.
    if (watched == heightReference
        && (event->type() == QEvent::Resize || event->type() == QEvent::LayoutRequest)) {
        followReference();
    }
    return QWidget::eventFilter(watched, event);
}

void AirTray::setItems(const QVector<AirQueueItem>& items, const QString& personName, bool personLive)
{
    detail->setText(AirStrings::text(Text::TrayWait).arg(personName));
    if (!items.isEmpty()) {
        title->setText(AirStrings::trayHeadText(items.size()));
    }
    if (items == shown && !departing) {
        return;
    }

    ++generation;
    if (items.isEmpty()) {
        const bool wasShown = isVisible() && !shown.isEmpty();
        shown.clear();
        if (wasShown && personLive) {
            depart();
            return;
        }
        rebuild(shown);
        hide();
        return;
    }

    departing = false;
    setMaximumHeight(QWIDGETSIZE_MAX);
    shown = items;
    rebuild(shown);
    show();
}

void AirTray::rebuild(const QVector<AirQueueItem>& items)
{
    for (AirQueueRow* row : rows) {
        // Out of the layout now, so the height measured below is that of the new rows only.
        listLayout->removeWidget(row);
        row->hide();
        row->deleteLater();
    }
    rows.clear();
    for (const AirQueueItem& item : items) {
        auto* const row = new AirQueueRow(item, Air::EntryStatus::Transit, list);
        connect(row, &AirQueueRow::actionsRequested, this, &AirTray::showActions);
        // Before the closing stretch.
        listLayout->insertWidget(listLayout->count() - 1, row);
        rows.append(row);
    }
    fitItems();
}

void AirTray::fitItems()
{
    // A hidden tray never takes its layout requests in: the rows need measuring afresh.
    listLayout->activate();
    const int width = scroller->viewport()->width();
    const int content = listLayout->hasHeightForWidth() && width > 0
                            ? listLayout->totalHeightForWidth(width)
                            : list->sizeHint().height();
    const int limit = itemsHeightLimit > 0 ? itemsHeightLimit : content;
    scroller->setFixedHeight(expanded ? std::min(content, limit) : 0);
    scroller->setVerticalScrollBarPolicy(content > limit ? Qt::ScrollBarAsNeeded
                                                         : Qt::ScrollBarAlwaysOff);
}

void AirTray::resizeEvent(QResizeEvent* event)
{
    QWidget::resizeEvent(event);
    fitItems();
}

void AirTray::setExpanded(bool expanded_)
{
    expanded = expanded_;
    scroller->setVisible(expanded);
    chevron->setRotation(expanded ? 180 : 0);
    retranslate();
    fitItems();
}

void AirTray::retranslate()
{
    chevron->setToolTip(AirStrings::text(expanded ? Text::TrayCollapseHint : Text::TrayExpandHint));
    chevron->setAccessibleName(chevron->toolTip());
}

void AirTray::mouseReleaseEvent(QMouseEvent* event)
{
    // The heading folds and opens the tray as the chevron does.
    if (event->button() == Qt::LeftButton && event->pos().y() < scroller->y()) {
        setExpanded(!expanded);
        return;
    }
    QWidget::mouseReleaseEvent(event);
}

void AirTray::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    const QRectF card = QRectF(rect()).adjusted(trayInset, 0, -trayInset, -trayBelow);
    AirPaint::box(painter, card, Radius::card, air().color(Color::TransitTray), QColor());
}

void AirTray::showActions(qint64 id, const QPoint& globalPos)
{
    QMenu menu(this);
    QAction* const edit = menu.addAction(AirStrings::text(Text::TrayEdit));
    QAction* const cancel = menu.addAction(AirStrings::text(Text::TrayCancel));
    QAction* const chosen = menu.exec(globalPos);
    if (chosen == edit) {
        emit editRequested(id);
    } else if (chosen == cancel) {
        emit cancelRequested(id);
    }
}

/**
 * The person came online: the messages leave one by one, 90 ms apart, rising a little as they
 * fade, and then the card folds. Their entries are already in the transcript.
 */
void AirTray::depart()
{
    departing = true;
    const int departure = generation;
    const bool calm = air().reducedMotion();
    const int step = calm ? 0 : Motion::stagger;
    const int fade = air().duration(Motion::state);

    for (int i = 0; i < rows.size(); ++i) {
        QPointer<AirQueueRow> row = rows[i];
        auto* const effect = new QGraphicsOpacityEffect(row);
        effect->setOpacity(1.0);
        row->setGraphicsEffect(effect);
        QTimer::singleShot(step * i, this, [this, row, effect, fade, departure] {
            if (!row || departure != generation) {
                return;
            }
            auto* const animation = new QVariantAnimation(row);
            animation->setStartValue(1.0);
            animation->setEndValue(0.0);
            animation->setDuration(fade);
            animation->setEasingCurve(air().easeOut());
            const int rise = air().reducedMotion() ? 0 : Space::s8;
            const QPoint origin = row->pos();
            connect(animation, &QVariantAnimation::valueChanged, row,
                    [row, effect, rise, origin](const QVariant& value) {
                        const qreal opacity = value.toReal();
                        effect->setOpacity(opacity);
                        row->move(origin.x(), origin.y() - qRound(rise * (1.0 - opacity)));
                    });
            animation->start(QAbstractAnimation::DeleteWhenStopped);
        });
    }

    // Then the card folds over the layout duration.
    const int folding = step * std::max(0, static_cast<int>(rows.size()) - 1) + fade;
    QTimer::singleShot(folding, this, [this, departure] {
        if (departure != generation) {
            return;
        }
        auto* const fold = new QVariantAnimation(this);
        fold->setStartValue(height());
        fold->setEndValue(0);
        fold->setDuration(air().duration(Motion::layout));
        fold->setEasingCurve(air().easeOut());
        connect(fold, &QVariantAnimation::valueChanged, this, [this, departure](const QVariant& value) {
            if (departure == generation) {
                setMaximumHeight(value.toInt());
            }
        });
        connect(fold, &QVariantAnimation::finished, this, [this, departure] {
            if (departure != generation) {
                return;
            }
            departing = false;
            hide();
            setMaximumHeight(QWIDGETSIZE_MAX);
            rebuild(QVector<AirQueueItem>());
        });
        fold->start(QAbstractAnimation::DeleteWhenStopped);
    });
}

// ---- AirFailedList ----

AirFailedList::AirFailedList(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Maximum);
    listLayout = new QVBoxLayout(this);
    // Set like the entries of the transcript: 16 px in from the sides.
    listLayout->setContentsMargins(Space::s16, 0, Space::s16, Space::s4);
    listLayout->setSpacing(Space::s2);
    hide();
}

void AirFailedList::setItems(const QVector<AirQueueItem>& items)
{
    if (items == shown) {
        return;
    }
    shown = items;
    for (AirQueueRow* row : rows) {
        listLayout->removeWidget(row);
        row->hide();
        row->deleteLater();
    }
    rows.clear();
    for (const AirQueueItem& item : items) {
        auto* const row = new AirQueueRow(item, Air::EntryStatus::Failed, this);
        row->addRetry();
        connect(row, &AirQueueRow::actionsRequested, this, &AirFailedList::showActions);
        connect(row, &AirQueueRow::retryRequested, this, &AirFailedList::retryRequested);
        listLayout->addWidget(row);
        rows.append(row);
    }
    setVisible(!items.isEmpty());
}

void AirFailedList::showActions(qint64 id, const QPoint& globalPos)
{
    QMenu menu(this);
    QAction* const retry = menu.addAction(AirStrings::text(Text::Retry));
    QAction* const edit = menu.addAction(AirStrings::text(Text::TrayEdit));
    QAction* const cancel = menu.addAction(AirStrings::text(Text::TrayCancel));
    QAction* const chosen = menu.exec(globalPos);
    if (chosen == retry) {
        emit retryRequested(id);
    } else if (chosen == edit) {
        emit editRequested(id);
    } else if (chosen == cancel) {
        emit cancelRequested(id);
    }
}
