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

#include "airnetworkband.h"

#include "src/widget/air/airsource.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"

#include <QAccessible>
#include <QDebug>
#include <QGraphicsOpacityEffect>
#include <QHBoxLayout>
#include <QLabel>
#include <QPainter>
#include <QVariantAnimation>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
using Phase = Air::NoNetworkNotice::Phase;
namespace Motion = AirTokens::Motion;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;

namespace {
/** The system is asked once a second: the band comes 3 s after the network goes, to the second. */
constexpr int pollMs = 1000;

const AirStyle& air()
{
    return AirStyle::instance();
}

/** For the log: when the computer lost its network and got online again, as support asks. */
const char* phaseName(Phase phase)
{
    switch (phase) {
    case Phase::Hidden:
        break;
    case Phase::NoNetwork:
        return "no network";
    case Phase::Connecting:
        return "connecting";
    case Phase::BackOnline:
        return "back online";
    }
    return "hidden";
}
} // namespace

AirNetworkBand::AirNetworkBand(AirSource& source_, QWidget* parent)
    : QWidget(parent)
    , source{source_}
{
    setObjectName(QStringLiteral("airNetworkBand"));
    setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Fixed);
    setFocusPolicy(Qt::NoFocus);

    content = new QWidget(this);
    glyph = new QLabel(content);
    glyph->setFixedSize(Size::icon, Size::icon);
    title = new QLabel(content);
    title->setTextFormat(Qt::PlainText);
    note = new QLabel(content);
    note->setTextFormat(Qt::PlainText);
    note->setWordWrap(true);
    auto* const layout = new QHBoxLayout(content);
    layout->setContentsMargins(Space::s16, Space::s8, Space::s16, Space::s8);
    layout->setSpacing(Space::s8);
    layout->addWidget(glyph, 0, Qt::AlignVCenter);
    layout->addWidget(title, 0, Qt::AlignVCenter);
    layout->addWidget(note, 1, Qt::AlignVCenter);

    motion = new QVariantAnimation(this);
    connect(motion, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        if (fading) {
            fade(value.toReal());
        } else {
            shown = value.toReal();
            place();
        }
    });
    connect(motion, &QVariantAnimation::finished, this, [this] {
        fade(1.0);
        if (!goingIn) {
            shown = 0;
            hide();
        }
    });

    poll.setInterval(pollMs);
    connect(&poll, &QTimer::timeout, this, &AirNetworkBand::askSystem);
    // A step of the notice that comes with the time asks the system afresh, so the band never comes
    // on a reading up to a second old.
    step.setSingleShot(true);
    connect(&step, &QTimer::timeout, this, &AirNetworkBand::askSystem);
    // Our node coming online ends Connecting at once.
    connect(&source, &AirSource::networkChanged, this, &AirNetworkBand::follow);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirNetworkBand::restyle);
    Translator::registerHandler(std::bind(&AirNetworkBand::retranslate, this), this);

    restyle();
    hide();
    clock.start();
    poll.start();
    QTimer::singleShot(0, this, &AirNetworkBand::askSystem);
}

AirNetworkBand::~AirNetworkBand()
{
    Translator::unregister(this);
}

Air::NoNetworkNotice::Phase AirNetworkBand::phase() const
{
    return notice.phase();
}

void AirNetworkBand::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.fillRect(rect(), air().color(Color::Sunken));
    painter.fillRect(QRect(0, height() - 1, width(), 1), air().color(Color::Line));
}

void AirNetworkBand::resizeEvent(QResizeEvent* event)
{
    QWidget::resizeEvent(event);
    place();
}

void AirNetworkBand::askSystem()
{
    network = source.computerHasNetwork();
    follow();
}

void AirNetworkBand::follow()
{
    const qint64 now = clock.elapsed();
    const bool online = Air::NetworkReport::make(source.networkState()).live;
    if (notice.update(network, online, now)) {
        present(notice.phase());
    }
    const qint64 next = notice.msToNextStep(now);
    if (next < 0) {
        step.stop();
    } else {
        step.start(static_cast<int>(next));
    }
}

void AirNetworkBand::present(Air::NoNetworkNotice::Phase phase)
{
    qDebug() << "Network band:" << phaseName(phase);
    const Phase before = shownPhase;
    shownPhase = phase;
    if (phase == Phase::Hidden) {
        slide(false);
        return;
    }
    restyle();
    retranslate();
    if (before == Phase::Hidden) {
        slide(true);
        // The title once, as it comes; what follows is in its name for whoever looks.
        QAccessibleEvent alert(this, QAccessible::Alert);
        QAccessible::updateAccessibility(&alert);
    } else {
        place();
    }
}

void AirNetworkBand::slide(bool in)
{
    if (in == goingIn && (motion->state() == QAbstractAnimation::Running || isHidden() != in)) {
        return;
    }
    motion->stop();
    goingIn = in;
    // Reduced motion: no slide, the band is there or gone at once and its words fade, 120 ms at
    // most (AirStyle::duration).
    fading = air().reducedMotion();
    motion->setDuration(air().duration(in ? Motion::layout : Motion::state));
    motion->setEasingCurve(air().easeOut());
    if (in) {
        QWidget::show();
    }
    if (fading) {
        shown = 1;
        place();
        motion->setStartValue(in ? 0.0 : 1.0);
        motion->setEndValue(in ? 1.0 : 0.0);
    } else {
        motion->setStartValue(shown);
        motion->setEndValue(in ? 1.0 : 0.0);
    }
    motion->start();
}

void AirNetworkBand::place()
{
    const int full = contentHeight();
    const int tall = qRound(full * shown);
    // The words slide down from under the Air band as the band opens, rather than being squeezed.
    content->setGeometry(0, tall - full, width(), full);
    setFixedHeight(tall);
}

int AirNetworkBand::contentHeight() const
{
    // Until the layout has placed the band it has no width of its own; the window's is close.
    const QWidget* const holder = parentWidget();
    const int across = width() > 0 || !holder ? width() : holder->width();
    const int forWidth = content->hasHeightForWidth() ? content->heightForWidth(across) : -1;
    return forWidth > 0 ? forWidth : content->sizeHint().height();
}

void AirNetworkBand::fade(qreal opacity)
{
    // An opaque band paints without the effect, so its words stay as crisp as the rest.
    if (opacity >= 1.0) {
        content->setGraphicsEffect(nullptr);
        return;
    }
    auto* effect = qobject_cast<QGraphicsOpacityEffect*>(content->graphicsEffect());
    if (!effect) {
        effect = new QGraphicsOpacityEffect(content);
        content->setGraphicsEffect(effect);
    }
    effect->setOpacity(opacity);
}

void AirNetworkBand::restyle()
{
    QString icon = QStringLiteral("wifi-off");
    Color tint = Color::Ink2;
    if (shownPhase == Phase::Connecting) {
        icon = QStringLiteral("clock");
    } else if (shownPhase == Phase::BackOnline) {
        icon = QStringLiteral("check");
        tint = Color::Live;
    }
    glyph->setPixmap(air().icon(icon, air().color(tint), Size::icon, devicePixelRatioF()));
    title->setFont(air().font(TextRole::Label));
    AirPaint::textColor(title, air().color(Color::Ink));
    note->setFont(air().font(TextRole::Caption));
    AirPaint::textColor(note, air().color(Color::Ink2));
    update();
}

void AirNetworkBand::retranslate()
{
    Text words = Text::NoNetworkTitle;
    if (shownPhase == Phase::Connecting) {
        words = Text::NoNetworkConnecting;
    } else if (shownPhase == Phase::BackOnline) {
        words = Text::NoNetworkBack;
    }
    title->setText(AirStrings::text(words));
    // What the messages do is said while there is no network; after that the title says it all.
    const bool withNote = shownPhase == Phase::NoNetwork;
    note->setText(withNote ? AirStrings::text(Text::NoNetworkNote) : QString());
    note->setVisible(withNote);
    setAccessibleName(title->text());
    setAccessibleDescription(note->text());
    place();
}
