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

#include "airchatparts.h"

#include "src/model/air/airmodel.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"

#include <QBoxLayout>
#include <QFontMetricsF>
#include <QLabel>
#include <QPainter>
#include <QPainterPath>
#include <QVariantAnimation>
#include <QtMath>

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

/** How far the second outline of a group's tile stands off, towards the end and up. */
constexpr int groupOffset = Space::s4 - 1;

/** The outline of the current hour stands this far around its cell. */
constexpr qreal nowRing = Stroke::hairline + Stroke::strong;

/** The dashes of a link that is not there yet: 4 px of line, 5 px apart. */
constexpr qreal pathDash = 4;
constexpr qreal pathGap = 5;
/** How many times the dashes run their length before they stand (a little over ten seconds). */
constexpr int pathRuns = 12;
} // namespace

// ---- AirTile ----

AirTile::AirTile(int side_, QWidget* parent)
    : QWidget(parent)
    , side{side_}
{
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    setAttribute(Qt::WA_TransparentForMouseEvents);
    AirPaint::followTheme(this);
}

void AirTile::setPerson(const QString& title_, int tone_)
{
    title = title_;
    tone = tone_;
    update();
}

void AirTile::setKind(Kind kind_)
{
    if (kind == kind_) {
        return;
    }
    kind = kind_;
    updateGeometry();
    update();
}

void AirTile::setPhoto(const QPixmap& photo_)
{
    photo = photo_;
    update();
}

QSize AirTile::sizeHint() const
{
    const int extra = kind == Kind::Group ? groupOffset : 0;
    return QSize(side + extra, side + extra);
}

void AirTile::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setRenderHint(QPainter::SmoothPixmapTransform);

    const bool rightToLeft = layoutDirection() == Qt::RightToLeft;
    const int extra = kind == Kind::Group ? groupOffset : 0;
    // The tile sits at the bottom and the start; a group's second outline takes the rest.
    const QRect tile(rightToLeft ? extra : 0, extra, side, side);

    if (!photo.isNull() && kind == Kind::Person) {
        const qreal dpr = devicePixelRatioF();
        const int pixels = qRound(side * dpr);
        QPixmap rounded(pixels, pixels);
        rounded.fill(Qt::transparent);
        {
            QPainter tilePainter(&rounded);
            tilePainter.setRenderHint(QPainter::Antialiasing);
            tilePainter.setRenderHint(QPainter::SmoothPixmapTransform);
            QPainterPath clip;
            clip.addRoundedRect(QRectF(0, 0, pixels, pixels), Radius::control * dpr,
                                Radius::control * dpr);
            tilePainter.setClipPath(clip);
            tilePainter.drawPixmap(QRect(0, 0, pixels, pixels),
                                   photo.scaled(pixels, pixels, Qt::KeepAspectRatioByExpanding,
                                                Qt::SmoothTransformation));
        }
        rounded.setDevicePixelRatio(dpr);
        painter.drawPixmap(tile.topLeft(), rounded);
        AirPaint::box(painter, tile, Radius::control, QColor(), air().color(Color::Line));
        return;
    }

    if (kind == Kind::Request) {
        // As the row of a request sent and not yet answered: no ground, a dashed outline.
        QPen dashed(air().color(Color::LineStrong), Stroke::hairline);
        dashed.setStyle(Qt::DashLine);
        painter.setPen(dashed);
        painter.setBrush(Qt::NoBrush);
        painter.drawRoundedRect(QRectF(tile).adjusted(0.5, 0.5, -0.5, -0.5), Radius::control,
                                Radius::control);
        const int icon = side > Size::tileDesk ? Size::icon : Size::iconSmall;
        const QPixmap glyph = air().icon(QStringLiteral("user-plus"), air().color(Color::Ink3),
                                         icon, devicePixelRatioF());
        painter.drawPixmap(tile.x() + (side - icon) / 2, tile.y() + (side - icon) / 2, glyph);
        return;
    }
    if (kind == Kind::Group) {
        const QRect back = tile.translated(rightToLeft ? -extra : extra, -extra);
        AirPaint::box(painter, back, Radius::control, air().color(Color::Surface),
                      air().color(Color::LineStrong));
    }
    AirPaint::box(painter, tile, Radius::control, air().color(Color::Sunken), air().color(Color::Line));

    if (kind == Kind::Saved) {
        const int icon = side > Size::tileDesk ? Size::icon : Size::iconSmall;
        const QPixmap glyph = air().icon(QStringLiteral("bookmark"), air().color(Color::Ink), icon,
                                         devicePixelRatioF());
        painter.drawPixmap(tile.x() + (side - icon) / 2, tile.y() + (side - icon) / 2, glyph);
        return;
    }

    // Caption type at the desk size, scaled with the tile for the bigger one of the dossier.
    QFont font = air().font(TextRole::Caption);
    font.setWeight(QFont::DemiBold);
    // Air fonts are sized in points, so the scale goes on the point size.
    font.setPointSizeF(font.pointSizeF() * side / static_cast<qreal>(Size::tileDesk));
    painter.setFont(font);
    painter.setPen(air().tone(tone));
    painter.drawText(tile, Qt::AlignCenter, Air::initials(title));
}

// ---- AirLinkMark ----

AirLinkMark::AirLinkMark(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    AirPaint::followTheme(this);
}

void AirLinkMark::setState(Air::LinkState state_)
{
    if (state != state_) {
        state = state_;
        update();
    }
}

QSize AirLinkMark::sizeHint() const
{
    return QSize(Size::linkMark, Size::linkMark / 3);
}

void AirLinkMark::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    // Drawn in the 30 by 10 box of the design's SVG, mirrored in right-to-left languages so the
    // far end stays on the person's side.
    if (layoutDirection() == Qt::RightToLeft) {
        painter.translate(width(), 0);
        painter.scale(-1, 1);
    }

    const qreal radius = 3;
    const QPointF near(4, 5);
    const QPointF far(26, 5);
    switch (state) {
    case Air::LinkState::Direct: {
        const QColor live = air().color(Color::Live);
        painter.setPen(QPen(live, Stroke::strong));
        painter.drawLine(QPointF(7, 5), QPointF(23, 5));
        painter.setPen(Qt::NoPen);
        painter.setBrush(live);
        painter.drawEllipse(near, radius, radius);
        painter.drawEllipse(far, radius, radius);
        break;
    }
    case Air::LinkState::Relay: {
        const QColor live = air().color(Color::Live);
        painter.setPen(QPen(live, Stroke::strong));
        painter.drawLine(QPointF(7, 5), QPointF(12, 5));
        painter.drawLine(QPointF(18, 5), QPointF(23, 5));
        painter.setBrush(Qt::NoBrush);
        painter.drawRoundedRect(QRectF(12.5, 2.5, 5, 5), 1, 1);
        painter.setPen(Qt::NoPen);
        painter.setBrush(live);
        painter.drawEllipse(near, radius, radius);
        painter.drawEllipse(far, radius, radius);
        break;
    }
    case Air::LinkState::None: {
        const QColor quiet = air().color(Color::Ink3);
        QPen dashed(quiet, Stroke::strong);
        dashed.setDashPattern({2 / Stroke::strong, 3 / Stroke::strong});
        dashed.setCapStyle(Qt::FlatCap);
        painter.setPen(dashed);
        painter.drawLine(QPointF(10, 5), QPointF(20, 5));
        painter.setPen(Qt::NoPen);
        painter.setBrush(quiet);
        painter.drawEllipse(near, radius, radius);
        painter.setPen(QPen(quiet, Stroke::mark));
        painter.setBrush(Qt::NoBrush);
        painter.drawEllipse(far, radius, radius);
        break;
    }
    }
}

// ---- AirDayStrip ----

AirDayStrip::AirDayStrip(bool big_, QWidget* parent)
    : QWidget(parent)
    , big{big_}
{
    setSizePolicy(big ? QSizePolicy::Expanding : QSizePolicy::Fixed, QSizePolicy::Fixed);
    strip.online = QVector<bool>(24, false);
    AirPaint::followTheme(this);
}

void AirDayStrip::setStrip(const Air::DayStrip& strip_, bool liveNow_)
{
    if (strip == strip_ && liveNow == liveNow_) {
        return;
    }
    strip = strip_;
    liveNow = liveNow_;
    update();
}

QSize AirDayStrip::sizeHint() const
{
    // The outline of the current hour stands around its cell, so the widget keeps room for it.
    const int ring = qCeil(nowRing);
    const int cellHeight = big ? Size::hourCellTall : Size::hourCell;
    return QSize(Size::hours + 2 * ring, cellHeight + 2 * ring);
}

QSize AirDayStrip::minimumSizeHint() const
{
    return sizeHint();
}

void AirDayStrip::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);

    const int ring = qCeil(nowRing);
    const qreal gap = Space::s2;
    const qreal stripWidth = big ? width() - 2 * ring : Size::hours;
    const qreal cellWidth = (stripWidth - gap * 23) / 24.0;
    const qreal cellHeight = big ? Size::hourCellTall : Size::hourCell;
    const bool rightToLeft = layoutDirection() == Qt::RightToLeft;

    for (int hour = 0; hour < 24; ++hour) {
        const int slot = rightToLeft ? 23 - hour : hour;
        const qreal x = ring + slot * (cellWidth + gap);
        const QRectF cell(x, ring, cellWidth, cellHeight);
        const bool isNow = hour == strip.nowHour;
        const bool on = hour < strip.online.size() && strip.online[hour];
        const QColor fill = isNow && liveNow ? air().color(Color::Live)
                            : on             ? air().color(Color::LiveCell)
                                             : air().color(Color::Sunken);
        painter.setPen(Qt::NoPen);
        painter.setBrush(fill);
        painter.drawRoundedRect(cell, Radius::tick, Radius::tick);
        if (isNow) {
            const qreal offset = Stroke::hairline + Stroke::strong / 2.0;
            painter.setPen(QPen(air().color(Color::Ink), Stroke::strong));
            painter.setBrush(Qt::NoBrush);
            painter.drawRoundedRect(cell.adjusted(-offset, -offset, offset, offset),
                                    Radius::tick + offset, Radius::tick + offset);
        }
    }
}

// ---- AirPathPicture ----

AirPathPicture::AirPathPicture(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    setFixedHeight(76);
    AirPaint::followTheme(this);
}

void AirPathPicture::setPerson(const QString& name_, const QString& initials_, Air::LinkState state_)
{
    name = name_;
    initials = initials_;
    state = state_;
    if (state != Air::LinkState::None) {
        setRunning(false);
    }
    update();
}

void AirPathPicture::setRunning(bool running)
{
    if (run) {
        run->stop();
    }
    phase = 0;
    update();
    if (!running || state != Air::LinkState::None || air().reducedMotion()) {
        return;
    }
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(0.0);
    animation->setEndValue(pathDash + pathGap);
    animation->setDuration(2 * Motion::reveal);
    animation->setLoopCount(pathRuns);
    connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        phase = value.toReal();
        update();
    });
    connect(animation, &QVariantAnimation::finished, this, [this] {
        phase = 0;
        update();
    });
    run = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirPathPicture::hideEvent(QHideEvent* event)
{
    // Out of sight the dashes stop for good; the panel starts them again when it shows.
    if (run) {
        run->stop();
    }
    QWidget::hideEvent(event);
}

void AirPathPicture::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    const bool rightToLeft = layoutDirection() == Qt::RightToLeft;
    const qreal radius = 18;
    const qreal y = 30;
    const qreal nearX = rightToLeft ? width() - 26.0 : 26.0;
    const qreal farX = rightToLeft ? 26.0 : width() - 26.0;
    const qreal direction = rightToLeft ? -1.0 : 1.0;
    const QPointF lineFrom(nearX + direction * (radius + 2), y);
    const QPointF lineTo(farX - direction * (radius + 2), y);

    const QColor live = air().color(Color::Live);
    QPen line(state == Air::LinkState::None ? air().color(Color::LineStrong) : live, Stroke::strong);
    if (state == Air::LinkState::None) {
        // The pattern is counted in pen widths; a falling offset carries the dashes from you on.
        line.setDashPattern({pathDash / Stroke::strong, pathGap / Stroke::strong});
        line.setDashOffset(-phase / Stroke::strong);
    }
    painter.setPen(line);
    if (state == Air::LinkState::Relay) {
        // The relay in the middle, with the line broken around it.
        const qreal middle = (lineFrom.x() + lineTo.x()) / 2.0;
        const qreal half = 6;
        painter.drawLine(lineFrom, QPointF(middle - direction * (half + 2), y));
        painter.drawLine(QPointF(middle + direction * (half + 2), y), lineTo);
        painter.setBrush(air().color(Color::Surface));
        painter.drawRoundedRect(QRectF(middle - half, y - half, 2 * half, 2 * half), Radius::tick,
                                Radius::tick);
        painter.setFont(air().font(TextRole::Caption));
        painter.setPen(air().color(Color::Ink2));
        painter.drawText(QRectF(middle - 80, y + half + 6, 160, 18),
                         Qt::AlignHCenter | Qt::AlignTop, AirStrings::text(Text::PathRelay));
    } else {
        painter.drawLine(lineFrom, lineTo);
    }

    // The two ends: sunken circles with "You" and the person's initials.
    QFont inside = air().font(TextRole::Caption);
    inside.setWeight(QFont::DemiBold);
    const QPointF ends[] = {QPointF(nearX, y), QPointF(farX, y)};
    const QString words[] = {AirStrings::text(Text::You), initials};
    for (int i = 0; i < 2; ++i) {
        painter.setPen(QPen(air().color(Color::LineStrong), Stroke::mark));
        painter.setBrush(air().color(Color::Sunken));
        painter.drawEllipse(ends[i], radius, radius);
        painter.setFont(inside);
        painter.setPen(air().color(Color::Ink));
        painter.drawText(QRectF(ends[i].x() - radius, y - radius, 2 * radius, 2 * radius),
                         Qt::AlignCenter, words[i]);
    }

    // The person's name under their end, kept inside the picture.
    painter.setFont(air().font(TextRole::Caption));
    painter.setPen(air().color(Color::Ink2));
    const QFontMetricsF metrics(painter.font());
    const qreal room = width() / 2.0;
    const QString shown = metrics.elidedText(name, Qt::ElideRight, room);
    const qreal textWidth = metrics.horizontalAdvance(shown);
    qreal left = farX - textWidth / 2.0;
    left = qBound<qreal>(0.0, left, width() - textWidth);
    painter.drawText(QRectF(left, 56, textWidth + 1, 20), Qt::AlignLeft | Qt::AlignTop, shown);
}

// ---- AirSwitch ----

AirSwitch::AirSwitch(QWidget* parent)
    : QAbstractButton(parent)
{
    setCheckable(true);
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::StrongFocus);
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    AirPaint::followTheme(this);
}

QSize AirSwitch::sizeHint() const
{
    return QSize(Size::switchWidth, Size::switchHeight);
}

void AirSwitch::checkStateSet()
{
    // Set from code: no slide.
    if (toggling) {
        return;
    }
    if (motion) {
        motion->stop();
    }
    position = isChecked() ? 1.0 : 0.0;
    update();
}

void AirSwitch::nextCheckState()
{
    toggling = true;
    QAbstractButton::nextCheckState();
    toggling = false;
    slide();
}

void AirSwitch::slide()
{
    const qreal target = isChecked() ? 1.0 : 0.0;
    if (motion) {
        motion->stop();
    }
    if (air().reducedMotion()) {
        position = target;
        update();
        return;
    }
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(position);
    animation->setEndValue(target);
    animation->setDuration(air().duration(Motion::state));
    animation->setEasingCurve(air().easeOut());
    connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        position = value.toReal();
        update();
    });
    motion = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirSwitch::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    const QRectF track(0, 0, Size::switchWidth, Size::switchHeight);
    const QColor off = air().color(Color::LineStrong);
    const QColor on = air().color(Color::SwitchOn);
    QColor fill;
    fill.setRgbF(off.redF() + (on.redF() - off.redF()) * position,
                 off.greenF() + (on.greenF() - off.greenF()) * position,
                 off.blueF() + (on.blueF() - off.blueF()) * position);
    painter.setOpacity(isEnabled() ? 1.0 : 0.4);
    painter.setPen(Qt::NoPen);
    painter.setBrush(fill);
    painter.drawRoundedRect(track, track.height() / 2.0, track.height() / 2.0);

    const qreal inset = (Size::switchHeight - Size::switchKnob) / 2.0;
    const qreal travel = Size::switchWidth - Size::switchHeight;
    const qreal along =
        layoutDirection() == Qt::RightToLeft ? travel * (1.0 - position) : travel * position;
    const QRectF knob(inset + along, inset, Size::switchKnob, Size::switchKnob);
    painter.setPen(QPen(air().color(Color::LineStrong), Stroke::hairline));
    painter.setBrush(air().color(Color::FixedWhite));
    painter.drawEllipse(knob);

    if (AirPaint::keyboardFocus(this)) {
        painter.setOpacity(1.0);
        AirPaint::focusRing(painter, rect(), track.height() / 2.0);
    }
}

// ---- AirNoChat ----

AirNoChat::AirNoChat(QWidget* parent)
    : QWidget(parent)
{
    setObjectName(QStringLiteral("noChat"));
    auto* const layout = new QVBoxLayout(this);
    layout->setContentsMargins(Space::s32, Space::s32, Space::s32, Space::s32);
    layout->setSpacing(0);
    sentence = new QLabel(this);
    sentence->setObjectName(QStringLiteral("noChatSentence"));
    sentence->setTextFormat(Qt::PlainText);
    sentence->setWordWrap(true);
    sentence->setAlignment(Qt::AlignCenter);
    // A short measure keeps the sentence on two or three lines in a wide window. The row centres
    // it without an alignment flag, which would give the label its one-line height.
    sentence->setMaximumWidth(Size::dialog);
    auto* const row = new QHBoxLayout();
    row->setSpacing(0);
    row->addStretch(1);
    row->addWidget(sentence, 100);
    row->addStretch(1);
    layout->addStretch(1);
    layout->addLayout(row);
    layout->addStretch(1);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirNoChat::restyle);
    Translator::registerHandler(std::bind(&AirNoChat::retranslate, this), this);
    restyle();
    retranslate();
}

AirNoChat::~AirNoChat()
{
    Translator::unregister(this);
}

void AirNoChat::retranslate()
{
    sentence->setText(AirStrings::text(Text::NoChatOpen));
}

void AirNoChat::restyle()
{
    sentence->setFont(air().font(TextRole::Body));
    AirPaint::textColor(sentence, air().color(Color::Ink3));
    update();
}

void AirNoChat::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.fillRect(rect(), air().color(Color::Surface));
}
