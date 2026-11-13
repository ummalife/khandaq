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

#include "aircomposer.h"

#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"

#include <QEvent>
#include <QGraphicsOpacityEffect>
#include <QGridLayout>
#include <QHBoxLayout>
#include <QLabel>
#include <QPainter>
#include <QTextEdit>
#include <QVBoxLayout>
#include <QVariantAnimation>

#include <algorithm>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Motion = AirTokens::Motion;
namespace Radius = AirTokens::Radius;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;
namespace Stroke = AirTokens::Stroke;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

/** kq-readonly: the sunken ground with the field's radius, under the eye, the words and the button. */
class ReadOnlyGround : public QWidget
{
public:
    explicit ReadOnlyGround(QWidget* parent)
        : QWidget(parent)
    {
        AirPaint::followTheme(this);
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        AirPaint::box(painter, QRectF(rect()), Radius::field, air().color(Color::Sunken), QColor());
    }
};
} // namespace

AirComposer::AirComposer(QTextEdit* field_, QWidget* parent)
    : QWidget(parent)
    , field{field_}
{
    // A toast stands above the composer it finds by this name (AirToast::place).
    setObjectName(QStringLiteral("airComposer"));
    setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Maximum);

    auto* const outer = new QVBoxLayout(this);
    outer->setContentsMargins(Space::s16, Space::s4, Space::s16, Space::s12);
    outer->setSpacing(0);
    writing = new QWidget(this);
    column = new QVBoxLayout(writing);
    column->setContentsMargins(0, 0, 0, 0);
    column->setSpacing(Space::s6);
    outer->addWidget(writing);

    // kq-compose: 4 px inside the frame, 6 between the paperclip, the field and "Send".
    frame = new QWidget(writing);
    auto* const row = new QHBoxLayout(frame);
    row->setContentsMargins(Space::s4, Space::s4, Space::s4, Space::s4);
    row->setSpacing(Space::s6);
    attach = new AirIconButton(QStringLiteral("paperclip"), frame);
    attach->setObjectName(QStringLiteral("composerAttach"));
    send = new AirButton(AirButton::Kind::Ink, frame);
    field->setParent(frame);
    field->setFrameStyle(QFrame::NoFrame);
    field->installEventFilter(this);
    row->addWidget(attach, 0, Qt::AlignBottom);
    row->addWidget(field, 1, Qt::AlignBottom);
    row->addWidget(send, 0, Qt::AlignBottom);
    column->addWidget(frame);
    // The field joins the frame after the buttons were made, so Tab would reach Send before it:
    // the order is the one the row shows.
    QWidget::setTabOrder(attach, field);
    QWidget::setTabOrder(field, send);

    hintLine = new QWidget(writing);
    auto* const hintRow = new QHBoxLayout(hintLine);
    hintRow->setContentsMargins(0, 0, 0, 0);
    hintRow->setSpacing(Space::s8);
    dot = new QWidget(hintLine);
    dot->setFixedSize(Size::dot, Size::dot);
    hint = new QLabel(hintLine);
    hint->setTextFormat(Qt::PlainText);
    auto* const effect = new QGraphicsOpacityEffect(hint);
    effect->setOpacity(1.0);
    hint->setGraphicsEffect(effect);
    counter = new QLabel(hintLine);
    counter->setTextFormat(Qt::PlainText);
    counter->hide();
    hintRow->addWidget(dot, 0, Qt::AlignVCenter);
    hintRow->addWidget(hint, 1);
    hintRow->addWidget(counter, 0);
    column->addWidget(hintLine);

    // kq-readonly: the eye, then the words with the button under them, 10 px down and 12 across;
    // a button that waits says why 6 px under it (kq-why).
    readOnlyBar = new ReadOnlyGround(this);
    readOnlyBar->setObjectName(QStringLiteral("composerReadOnly"));
    auto* const readOnlyGrid = new QGridLayout(readOnlyBar);
    readOnlyGrid->setContentsMargins(Space::s16, Space::s12, Space::s16, Space::s12);
    readOnlyGrid->setHorizontalSpacing(Space::s12);
    readOnlyGrid->setVerticalSpacing(Space::s10);
    readOnlyIcon = new QLabel(readOnlyBar);
    readOnlyIcon->setFixedSize(Size::icon, Size::icon);
    readOnlyText = new QLabel(readOnlyBar);
    readOnlyText->setTextFormat(Qt::PlainText);
    readOnlyText->setWordWrap(true);
    readOnlyButton = new AirButton(AirButton::Kind::Plain, readOnlyBar);
    readOnlyButton->setObjectName(QStringLiteral("composerReadOnlyAction"));
    readOnlyWhy = new QLabel(readOnlyBar);
    readOnlyWhy->setObjectName(QStringLiteral("composerReadOnlyWhy"));
    readOnlyWhy->setTextFormat(Qt::PlainText);
    readOnlyWhy->setWordWrap(true);
    readOnlyWhy->hide();
    auto* const askColumn = new QVBoxLayout();
    askColumn->setContentsMargins(0, 0, 0, 0);
    askColumn->setSpacing(Space::s6);
    askColumn->addWidget(readOnlyButton, 0, Qt::AlignLeading);
    askColumn->addWidget(readOnlyWhy);
    readOnlyGrid->addWidget(readOnlyIcon, 0, 0, Qt::AlignTop);
    readOnlyGrid->addWidget(readOnlyText, 0, 1);
    readOnlyGrid->addLayout(askColumn, 1, 1);
    readOnlyGrid->setColumnStretch(1, 1);
    outer->addWidget(readOnlyBar);
    readOnlyBar->hide();

    connect(readOnlyButton, &AirButton::clicked, this, &AirComposer::readOnlyActionRequested);
    connect(send, &AirButton::clicked, this, &AirComposer::sendRequested);
    connect(attach, &AirIconButton::clicked, this,
            [this] { emit attachRequested(attach->mapToGlobal(QPoint(0, attach->height()))); });
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirComposer::restyle);
    restyle();
    setHint(QString(), Air::ComposeHint::Dot::None);
}

void AirComposer::setAttachVisible(bool visible)
{
    attach->setVisible(visible);
}

void AirComposer::setAttachNote(const QString& note)
{
    attachNote = note;
    restyle();
}

void AirComposer::setHint(const QString& text, Air::ComposeHint::Dot dot_)
{
    const bool changed = hint->text() != text || dotKind != dot_;
    dotKind = dot_;
    dot->setVisible(!text.isEmpty() && dotKind != Air::ComposeHint::Dot::None);
    hint->setText(text);
    keepHintHeight();
    update();
    if (!changed || !isVisible() || text.isEmpty()) {
        return;
    }
    // A change of reach cross-fades the line in place.
    auto* const effect = qobject_cast<QGraphicsOpacityEffect*>(hint->graphicsEffect());
    if (!effect) {
        return;
    }
    if (fade) {
        fade->stop();
    }
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(0.0);
    animation->setEndValue(1.0);
    animation->setDuration(air().duration(Motion::state));
    connect(animation, &QVariantAnimation::valueChanged, effect,
            [effect](const QVariant& value) { effect->setOpacity(value.toReal()); });
    connect(animation, &QVariantAnimation::finished, effect, [effect] { effect->setOpacity(1.0); });
    fade = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirComposer::keepHintHeight()
{
    // The line keeps its height while empty, so the field does not jump when it comes back. A
    // least height set on a label also lets a squeezed window cut its words to that height, and
    // Arabic words stand taller than the caption's line: they keep the height they need.
    hint->setMinimumHeight(
        std::max(air().lineHeight(TextRole::Caption), hint->minimumSizeHint().height()));
}

void AirComposer::setCounter(const QString& text)
{
    counter->setText(text);
    counter->setVisible(!text.isEmpty());
}

void AirComposer::addAbove(QWidget* widget)
{
    column->insertWidget(column->indexOf(frame), widget);
}

void AirComposer::setReadOnly(const QString& text, const QString& action, const QString& unavailable)
{
    const bool readOnly = !text.isEmpty();
    readOnlyText->setText(text);
    readOnlyButton->setText(action);
    readOnlyButton->setVisible(readOnly && !action.isEmpty());
    readOnlyButton->setEnabled(unavailable.isEmpty());
    readOnlyButton->setToolTip(unavailable);
    readOnlyButton->setAccessibleDescription(unavailable);
    readOnlyWhy->setText(unavailable);
    readOnlyWhy->setVisible(readOnlyButton->isVisibleTo(readOnlyBar) && !unavailable.isEmpty());
    readOnlyBar->setAccessibleName(text);
    if (readOnly == !readOnlyBar->isHidden()) {
        return;
    }
    // The bar stands where the field was; what sits above the field (a quote) goes with it.
    const bool hadFocus = field->hasFocus();
    writing->setVisible(!readOnly);
    readOnlyBar->setVisible(readOnly);
    if (readOnly && hadFocus && readOnlyButton->isVisible() && readOnlyButton->isEnabled()) {
        readOnlyButton->setFocus(Qt::OtherFocusReason);
    }
    update();
}

bool AirComposer::isReadOnly() const
{
    return !readOnlyText->text().isEmpty();
}

void AirComposer::setSendBlocked(bool blocked)
{
    send->setEnabled(!blocked);
}

void AirComposer::restyle()
{
    send->setText(AirStrings::text(Text::ComposeSend));
    const QString attachName = AirStrings::text(Text::ComposeAttach);
    attach->setToolTip(attachNote.isEmpty() ? attachName : attachName + QLatin1Char('\n') + attachNote);
    attach->setAccessibleName(attachName);
    attach->setAccessibleDescription(attachNote);
    for (QLabel* label : {hint, counter}) {
        label->setFont(air().font(TextRole::Caption));
        AirPaint::textColor(label, air().color(Color::Ink2));
    }
    keepHintHeight();
    readOnlyText->setFont(air().font(TextRole::Body));
    AirPaint::textColor(readOnlyText, air().color(Color::Ink2));
    readOnlyWhy->setFont(air().font(TextRole::Caption));
    AirPaint::textColor(readOnlyWhy, air().color(Color::Ink3));
    readOnlyIcon->setPixmap(air().icon(QStringLiteral("eye"), air().color(Color::Ink3), Size::icon,
                                       devicePixelRatioF()));
    QPalette palette = field->palette();
    palette.setColor(QPalette::Text, air().color(Color::Ink));
    palette.setColor(QPalette::PlaceholderText, air().color(Color::Ink3));
    palette.setColor(QPalette::Highlight, air().color(Color::Selected));
    palette.setColor(QPalette::HighlightedText, air().color(Color::Ink));
    field->setPalette(palette);
    update();
}

QRect AirComposer::frameRect() const
{
    return frame->geometry().translated(writing->pos());
}

bool AirComposer::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == field && (event->type() == QEvent::FocusIn || event->type() == QEvent::FocusOut)) {
        update();
    }
    return QWidget::eventFilter(watched, event);
}

void AirComposer::paintEvent(QPaintEvent*)
{
    // While you may not write, the read-only bar stands where the field was: no frame under it,
    // whose edge would show around the bar's corners.
    if (writing->isHidden()) {
        return;
    }
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    // The frame turns ink while the field has the focus.
    const QColor border = field->hasFocus() ? air().color(Color::Ink) : air().color(Color::LineStrong);
    AirPaint::box(painter, frameRect(), Radius::field, air().color(Color::Surface), border);

    if (dot->isVisible()) {
        const QColor fill = dotKind == Air::ComposeHint::Dot::Live ? air().color(Color::Live)
                            : dotKind == Air::ComposeHint::Dot::Transit ? air().color(Color::Transit)
                                                                        : air().color(Color::Ink3);
        painter.setPen(Qt::NoPen);
        painter.setBrush(fill);
        painter.drawEllipse(QRectF(dot->geometry().translated(writing->pos() + hintLine->pos())));
    }
}
