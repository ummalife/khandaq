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

#include "airgroupviews.h"

#include "src/model/air/airmodel.h"
#include "src/widget/air/airchatparts.h"
#include "src/widget/air/airnewcontact.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"

#include <QGraphicsOpacityEffect>
#include <QTimerEvent>
#include <QKeyEvent>
#include <QLabel>
#include <QLocale>
#include <QPainter>
#include <QPainterPath>
#include <QStyle>
#include <QTextEdit>
#include <QVBoxLayout>
#include <QVariantAnimation>

#include <functional>

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

/** From this many contacts on, an invitation list offers a search by name. */
constexpr int inviteSearchFrom = 8;

/** A rectangle laid out from the start edge, turned for a right-to-left interface. */
QRect visual(const QWidget* widget, const QRect& logical)
{
    return QStyle::visualRect(widget->layoutDirection(), widget->rect(), logical);
}

/** The text alignment of the interface's start edge, as an absolute one. */
int startAlignment(const QWidget* widget)
{
    return static_cast<int>(
        QStyle::visualAlignment(widget->layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter));
}

/**
 * The online dot over the end corner of a member's tile, cut out of the tile by a ring of the
 * ground the row stands on. The pointer passes through it.
 */
class LiveDot : public QWidget
{
public:
    LiveDot(std::function<QColor()> ground_, QWidget* parent)
        : QWidget(parent)
        , ground{std::move(ground_)}
    {
        setAttribute(Qt::WA_TransparentForMouseEvents);
        const int side = Size::dot + 2 * static_cast<int>(Stroke::strong);
        setFixedSize(side, side);
        AirPaint::followTheme(this);
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        painter.setPen(Qt::NoPen);
        painter.setBrush(ground());
        painter.drawEllipse(QRectF(rect()));
        const qreal ring = Stroke::strong;
        painter.setBrush(air().color(Color::Live));
        painter.drawEllipse(QRectF(rect()).adjusted(ring, ring, -ring, -ring));
    }

private:
    std::function<QColor()> ground;
};

/** A layer over its siblings that the pointer passes through; the owner says what it paints. */
class OverLayer : public QWidget
{
public:
    OverLayer(std::function<void(QPainter&)> paint_, QWidget* parent)
        : QWidget(parent)
        , paint{std::move(paint_)}
    {
        setAttribute(Qt::WA_TransparentForMouseEvents);
        setAttribute(Qt::WA_NoSystemBackground);
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        paint(painter);
    }

private:
    std::function<void(QPainter&)> paint;
};

/** Fades a tile in over the state duration; it is fully there at once with less motion. */
void fadeIn(QWidget* tile)
{
    if (air().reducedMotion()) {
        return;
    }
    auto* const effect = new QGraphicsOpacityEffect(tile);
    effect->setOpacity(0.0);
    tile->setGraphicsEffect(effect);
    auto* const animation = new QVariantAnimation(tile);
    animation->setStartValue(0.0);
    animation->setEndValue(1.0);
    animation->setDuration(air().duration(Motion::state));
    animation->setEasingCurve(air().easeOut());
    QObject::connect(animation, &QVariantAnimation::valueChanged, effect,
                     [effect](const QVariant& value) { effect->setOpacity(value.toReal()); });
    QObject::connect(animation, &QVariantAnimation::finished, tile,
                     [tile] { tile->setGraphicsEffect(nullptr); });
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}
} // namespace

// ---- AirGetNowBand ----

AirGetNowBand::AirGetNowBand(QWidget* parent)
    : QAbstractButton(parent)
{
    setObjectName(QStringLiteral("groupGetNow"));
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::StrongFocus);
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    label = new QLabel(this);
    label->setTextFormat(Qt::PlainText);
    more = new QLabel(this);
    more->setTextFormat(Qt::PlainText);
    more->hide();
    rings = new OverLayer([this](QPainter& painter) { paintRings(painter); }, this);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirGetNowBand::restyle);
    Translator::registerHandler(std::bind(&AirGetNowBand::retranslate, this), this);
    restyle();
    retranslate();
}

AirGetNowBand::~AirGetNowBand()
{
    Translator::unregister(this);
}

void AirGetNowBand::setMembers(const QVector<Air::GroupMember>& reachable)
{
    // Someone who has just come online fades in; the first fill shows everyone at once.
    const bool fade = !shownKeys.isEmpty() && isVisible();
    const QSet<QString> before = shownKeys;
    members = reachable;
    shownKeys.clear();
    while (tiles.size() > members.size()) {
        delete tiles.takeLast();
    }
    while (tiles.size() < members.size()) {
        auto* const tile = new AirTile(Size::tileCompact, this);
        tile->show();
        tiles.append(tile);
    }
    for (int index = 0; index < members.size(); ++index) {
        const Air::GroupMember& member = members.at(index);
        AirTile* const tile = tiles.at(index);
        tile->setPerson(member.name, Air::toneIndex(member.toneKey()));
        tile->setToolTip(member.name);
        shownKeys.insert(member.toneKey());
        if (fade && !before.contains(member.toneKey())) {
            fadeIn(tile);
        }
    }
    describe();
    fit();
    updateGeometry();
}

void AirGetNowBand::setSpeaking(const QString& key)
{
    if (!shownKeys.contains(key)) {
        return;
    }
    // Each packet of their voice keeps the ring for a while; it goes when they stop.
    speaking.insert(key, QDeadlineTimer(speakingMs));
    if (!speakingTimer.isActive()) {
        speakingTimer.start(speakingMs / 2, this);
    }
    rings->update();
}

void AirGetNowBand::paintRings(QPainter& painter)
{
    // A member whose voice is coming has their tile outlined in the online colour.
    const qreal stroke = Stroke::strong;
    painter.setPen(QPen(air().color(Color::Live), stroke));
    painter.setBrush(Qt::NoBrush);
    for (int index = 0; index < members.size() && index < tiles.size(); ++index) {
        const AirTile* const tile = tiles.at(index);
        if (tile->isVisible() && speaking.contains(members.at(index).toneKey())) {
            const qreal in = stroke / 2;
            painter.drawRoundedRect(QRectF(tile->geometry()).adjusted(in, in, -in, -in),
                                    Radius::control - in, Radius::control - in);
        }
    }
}

void AirGetNowBand::timerEvent(QTimerEvent* event)
{
    if (event->timerId() != speakingTimer.timerId()) {
        QAbstractButton::timerEvent(event);
        return;
    }
    for (auto it = speaking.begin(); it != speaking.end();) {
        it = it.value().hasExpired() ? speaking.erase(it) : std::next(it);
    }
    if (speaking.isEmpty()) {
        speakingTimer.stop();
    }
    rings->update();
}

void AirGetNowBand::retranslate()
{
    label->setText(AirStrings::text(Text::GroupGetNow));
    setToolTip(AirStrings::text(Text::GroupDossierHint));
    describe();
    fit();
    updateGeometry();
}

void AirGetNowBand::restyle()
{
    label->setFont(air().font(TextRole::Caption));
    AirPaint::textColor(label, air().color(Color::Ink3));
    more->setFont(air().font(TextRole::Caption));
    AirPaint::textColor(more, air().color(Color::Ink2));
    fit();
    update();
}

void AirGetNowBand::describe()
{
    QStringList names;
    for (const Air::GroupMember& member : members) {
        names.append(member.name);
    }
    setAccessibleName(
        AirStrings::text(Text::GroupGetNowSpoken).arg(QLocale().createSeparatedList(names)));
    setAccessibleDescription(AirStrings::text(Text::GroupDossierHint));
}

void AirGetNowBand::fit()
{
    const int labelWidth = label->sizeHint().width();
    const int height = this->height();
    const auto place = [this, height](QWidget* widget, int x, int width, int widgetHeight) {
        widget->setGeometry(visual(this, QRect(x, (height - widgetHeight) / 2, width, widgetHeight)));
    };
    place(label, 0, labelWidth, label->sizeHint().height());

    int x = labelWidth + Space::s8;
    const int room = width() - x;
    const int count = members.size();
    const QFontMetrics metrics(more->font());
    const auto moreWidth = [&metrics](int hidden) {
        return hidden > 0 ? Space::s4 + metrics.horizontalAdvance(QStringLiteral("+%1").arg(hidden))
                          : 0;
    };
    int shown = count;
    while (shown > 0
           && shown * (Size::tileCompact + Space::s4) - Space::s4 + moreWidth(count - shown) > room) {
        --shown;
    }
    for (int index = 0; index < tiles.size(); ++index) {
        AirTile* const tile = tiles.at(index);
        tile->setVisible(index < shown);
        if (index < shown) {
            place(tile, x, Size::tileCompact, Size::tileCompact);
            x += Size::tileCompact + Space::s4;
        }
    }
    const int hidden = count - shown;
    more->setVisible(hidden > 0);
    if (hidden > 0) {
        more->setText(QStringLiteral("+%1").arg(hidden));
        const int start = shown > 0 ? x : labelWidth + Space::s8;
        place(more, start, metrics.horizontalAdvance(more->text()) + 1, more->sizeHint().height());
    }
    rings->setGeometry(rect());
    rings->raise();
}

QSize AirGetNowBand::sizeHint() const
{
    const int tilesWidth = members.size() * (Size::tileCompact + Space::s4);
    return QSize(label->sizeHint().width() + Space::s8 + tilesWidth, Size::tileCompact);
}

QSize AirGetNowBand::minimumSizeHint() const
{
    return QSize(label->sizeHint().width(), Size::tileCompact);
}

void AirGetNowBand::paintEvent(QPaintEvent*)
{
    if (!AirPaint::keyboardFocus(this)) {
        return;
    }
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    AirPaint::focusRing(painter, QRectF(rect()), Radius::small);
}

void AirGetNowBand::keyPressEvent(QKeyEvent* event)
{
    if (event->key() == Qt::Key_Return || event->key() == Qt::Key_Enter) {
        event->accept();
        click();
        return;
    }
    QAbstractButton::keyPressEvent(event);
}

void AirGetNowBand::resizeEvent(QResizeEvent* event)
{
    QAbstractButton::resizeEvent(event);
    fit();
}

// ---- AirMemberRow ----

AirMemberRow::AirMemberRow(const Air::GroupMember& member, QWidget* parent)
    : QAbstractButton(parent)
    , shown{member}
{
    setObjectName(member.isSelf ? QStringLiteral("groupMemberSelf") : QStringLiteral("groupMember"));
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    tile = new AirTile(Size::tileDesk, this);
    tile->setAttribute(Qt::WA_TransparentForMouseEvents);
    tile->setPerson(member.name, Air::toneIndex(member.toneKey()));
    dot = new LiveDot([this] { return ground(); }, this);
    dot->setVisible(member.online);
    if (member.isSelf) {
        // Your own row only says who you are.
        setFocusPolicy(Qt::NoFocus);
        setAttribute(Qt::WA_TransparentForMouseEvents);
    } else {
        setFocusPolicy(Qt::StrongFocus);
        setCursor(Qt::PointingHandCursor);
        setToolTip(AirStrings::text(Text::GroupMemberHint));
        setAccessibleDescription(AirStrings::text(Text::GroupMemberHint));
    }
    setAccessibleName(AirStrings::text(Text::GroupMemberStatus).arg(nameText(), statusText()));
    AirPaint::followTheme(this);
}

const Air::GroupMember& AirMemberRow::member() const
{
    return shown;
}

QColor AirMemberRow::ground() const
{
    return (hovered || isDown()) && !shown.isSelf ? air().color(Color::Selected)
                                                  : air().color(Color::Surface);
}

QString AirMemberRow::nameText() const
{
    return shown.isSelf ? AirStrings::text(Text::GroupMemberYou).arg(shown.name) : shown.name;
}

QString AirMemberRow::statusText() const
{
    return AirStrings::groupMemberStatusText(shown);
}

QSize AirMemberRow::sizeHint() const
{
    return QSize(Size::listColumn, Size::rowDesk);
}

void AirMemberRow::resizeEvent(QResizeEvent* event)
{
    QAbstractButton::resizeEvent(event);
    const QRect tileRect(Space::s12, (height() - Size::tileDesk) / 2, Size::tileDesk, Size::tileDesk);
    tile->setGeometry(visual(this, tileRect));
    // The dot sits on the tile's lower end corner, a stroke outside it.
    const int side = dot->width();
    const int stroke = static_cast<int>(Stroke::strong);
    const QRect dotRect(tileRect.right() - side + stroke + 1, tileRect.bottom() - side + stroke + 1,
                        side, side);
    dot->setGeometry(visual(this, dotRect));
    dot->raise();
}

void AirMemberRow::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.fillRect(rect(), ground());

    const int textStart = Space::s12 + Size::tileDesk + Space::s12;
    const int textWidth = std::max(0, width() - textStart - Space::s12);
    const int nameHeight = air().lineHeight(TextRole::Body);
    const int statusHeight = air().lineHeight(TextRole::Caption);
    const int top = (height() - nameHeight - Space::s2 - statusHeight) / 2;
    const int flags = startAlignment(this) | Qt::TextSingleLine;

    painter.setFont(air().font(TextRole::Body));
    painter.setPen(air().color(Color::Ink));
    const QString name = painter.fontMetrics().elidedText(nameText(), Qt::ElideRight, textWidth);
    AirPaint::ownText(painter, visual(this, QRect(textStart, top, textWidth, nameHeight)), flags, name);

    painter.setFont(air().font(TextRole::Caption));
    painter.setPen(air().color(Color::Ink3));
    const QString status = painter.fontMetrics().elidedText(statusText(), Qt::ElideRight, textWidth);
    painter.drawText(visual(this,
                            QRect(textStart, top + nameHeight + Space::s2, textWidth, statusHeight)),
                     flags, status);

    if (AirPaint::keyboardFocus(this)) {
        AirPaint::focusRing(painter, QRectF(rect()), Radius::small);
    }
}

void AirMemberRow::enterEvent(QEvent* event)
{
    hovered = true;
    update();
    dot->update();
    QAbstractButton::enterEvent(event);
}

void AirMemberRow::leaveEvent(QEvent* event)
{
    hovered = false;
    update();
    dot->update();
    QAbstractButton::leaveEvent(event);
}

// ---- AirRadioRow ----

AirRadioRow::AirRadioRow(QWidget* parent)
    : QAbstractButton(parent)
{
    setCheckable(true);
    setAutoExclusive(true);
    setFocusPolicy(Qt::StrongFocus);
    setCursor(Qt::PointingHandCursor);
    QSizePolicy policy(QSizePolicy::Expanding, QSizePolicy::Preferred);
    policy.setHeightForWidth(true);
    setSizePolicy(policy);
    AirPaint::followTheme(this);
}

void AirRadioRow::setNote(const QString& note_)
{
    if (note != note_) {
        note = note_;
        setAccessibleDescription(note);
        updateGeometry();
        update();
    }
}

void AirRadioRow::measure(int width, QRect* title, QRect* noteRect) const
{
    const int textStart = Size::mark + Space::s10;
    const int textWidth = std::max(1, width - textStart);
    const QFontMetrics titleMetrics(air().font(TextRole::Body));
    const QFontMetrics noteMetrics(air().font(TextRole::Caption));
    const QRect titleBox =
        titleMetrics.boundingRect(QRect(0, 0, textWidth, QWIDGETSIZE_MAX), Qt::TextWordWrap, text());
    const int titleHeight = std::max(titleBox.height(), air().lineHeight(TextRole::Body));
    // The title stands in the middle of the chip height when it is one line.
    const int top = std::max(0, (Size::chip - titleHeight) / 2);
    *title = QRect(textStart, top, textWidth, titleHeight);
    if (note.isEmpty()) {
        *noteRect = QRect(textStart, title->bottom() + 1, textWidth, 0);
        return;
    }
    const QRect noteBox =
        noteMetrics.boundingRect(QRect(0, 0, textWidth, QWIDGETSIZE_MAX), Qt::TextWordWrap, note);
    *noteRect = QRect(textStart, title->bottom() + 1 + Space::s2, textWidth, noteBox.height());
}

bool AirRadioRow::hasHeightForWidth() const
{
    return true;
}

int AirRadioRow::heightForWidth(int width) const
{
    QRect title;
    QRect noteRect;
    measure(width, &title, &noteRect);
    return std::max(Size::chip, noteRect.bottom() + 1 + Space::s4);
}

QSize AirRadioRow::sizeHint() const
{
    return QSize(Size::listColumn, heightForWidth(Size::listColumn));
}

QSize AirRadioRow::minimumSizeHint() const
{
    return QSize(Size::mark + Space::s10 + Space::s64, Size::chip);
}

void AirRadioRow::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    QRect title;
    QRect noteRect;
    measure(width(), &title, &noteRect);

    // The mark: centred on the first line of the title.
    const int lineHeight = air().lineHeight(TextRole::Body);
    const QRect markLogical(0, title.top() + (lineHeight - Size::mark) / 2, Size::mark, Size::mark);
    const QRectF mark = QRectF(visual(this, markLogical));
    const QColor ink = air().color(Color::Ink);
    if (isChecked()) {
        const qreal stroke = Stroke::strong;
        painter.setPen(QPen(ink, stroke));
        painter.setBrush(air().color(Color::Surface));
        painter.drawEllipse(mark.adjusted(stroke / 2, stroke / 2, -stroke / 2, -stroke / 2));
        painter.setPen(Qt::NoPen);
        painter.setBrush(ink);
        const qreal inset = (Size::mark - Size::dot) / 2.0;
        painter.drawEllipse(mark.adjusted(inset, inset, -inset, -inset));
    } else {
        const QColor border = hovered ? air().color(Color::Ink3) : air().color(Color::LineStrong);
        painter.setPen(QPen(border, Stroke::hairline));
        painter.setBrush(air().color(Color::Surface));
        painter.drawEllipse(mark.adjusted(0.5, 0.5, -0.5, -0.5));
    }
    if (AirPaint::keyboardFocus(this)) {
        AirPaint::focusRing(painter, mark.adjusted(-3, -3, 3, 3), Radius::round);
    }

    const int flags = startAlignment(this) | Qt::TextWordWrap;
    painter.setFont(air().font(TextRole::Body));
    painter.setPen(isEnabled() ? ink : air().color(Color::Ink3));
    painter.drawText(visual(this, title), (flags & ~Qt::AlignVCenter) | Qt::AlignTop, text());
    if (!note.isEmpty()) {
        painter.setFont(air().font(TextRole::Caption));
        painter.setPen(air().color(Color::Ink2));
        painter.drawText(visual(this, noteRect), (flags & ~Qt::AlignVCenter) | Qt::AlignTop, note);
    }
}

void AirRadioRow::enterEvent(QEvent* event)
{
    hovered = true;
    update();
    QAbstractButton::enterEvent(event);
}

void AirRadioRow::leaveEvent(QEvent* event)
{
    hovered = false;
    update();
    QAbstractButton::leaveEvent(event);
}

// ---- AirInviteRow ----

AirInviteRow::AirInviteRow(const Air::InviteCandidate& candidate, QWidget* parent)
    : QAbstractButton(parent)
    , shown{candidate}
{
    setObjectName(QStringLiteral("inviteRow"));
    setCheckable(true);
    setEnabled(candidate.canInvite());
    setFocusPolicy(Qt::StrongFocus);
    setCursor(candidate.canInvite() ? Qt::PointingHandCursor : Qt::ArrowCursor);
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    tile = new AirTile(Size::tileCompact, this);
    tile->setAttribute(Qt::WA_TransparentForMouseEvents);
    tile->setPerson(candidate.name, Air::toneIndex(candidate.publicKey));
    setAccessibleName(candidate.name);
    if (!candidate.canInvite()) {
        setAccessibleDescription(AirStrings::text(Text::InviteWhenOnline));
    }
    AirPaint::followTheme(this);
}

const Air::InviteCandidate& AirInviteRow::candidate() const
{
    return shown;
}

QSize AirInviteRow::sizeHint() const
{
    return QSize(Size::listColumn, Size::rowDeskCompact);
}

void AirInviteRow::resizeEvent(QResizeEvent* event)
{
    QAbstractButton::resizeEvent(event);
    const int tileStart = Space::s8 + Size::mark + Space::s10;
    tile->setGeometry(visual(this, QRect(tileStart, (height() - Size::tileCompact) / 2,
                                         Size::tileCompact, Size::tileCompact)));
}

void AirInviteRow::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    if (hovered && isEnabled()) {
        AirPaint::box(painter, QRectF(rect()), Radius::field, air().color(Color::Selected), QColor());
    }

    // kq-check: the square mark in ink once chosen.
    const QRectF mark =
        QRectF(visual(this, QRect(Space::s8, (height() - Size::mark) / 2, Size::mark, Size::mark)));
    if (isChecked()) {
        AirPaint::box(painter, mark, Radius::small, air().color(Color::Ink), QColor());
        const int icon = Size::iconSmall;
        const QPixmap check =
            air().icon(QStringLiteral("check"), air().color(Color::OnInk), icon, devicePixelRatioF());
        painter.drawPixmap(QPointF(mark.center().x() - icon / 2.0, mark.center().y() - icon / 2.0),
                           check);
    } else {
        const QColor border = !isEnabled() ? air().color(Color::Line)
                              : hovered    ? air().color(Color::Ink3)
                                           : air().color(Color::LineStrong);
        AirPaint::box(painter, mark, Radius::small, air().color(Color::Surface), border);
    }
    if (AirPaint::keyboardFocus(this)) {
        AirPaint::focusRing(painter, mark.adjusted(-3, -3, 3, 3), Radius::small + 3);
    }

    const int textStart = Space::s8 + Size::mark + Space::s10 + Size::tileCompact + Space::s10;
    const int textWidth = std::max(0, width() - textStart - Space::s8);
    const int flags = startAlignment(this) | Qt::TextSingleLine;
    const int nameHeight = air().lineHeight(TextRole::Body);
    const bool noted = !shown.canInvite();
    const int noteHeight = noted ? air().lineHeight(TextRole::Caption) : 0;
    const int top = (height() - nameHeight - (noted ? Space::s2 + noteHeight : 0)) / 2;
    painter.setFont(air().font(TextRole::Body));
    painter.setPen(noted ? air().color(Color::Ink2) : air().color(Color::Ink));
    const QString name = painter.fontMetrics().elidedText(shown.name, Qt::ElideRight, textWidth);
    AirPaint::ownText(painter, visual(this, QRect(textStart, top, textWidth, nameHeight)), flags, name);
    if (noted) {
        painter.setFont(air().font(TextRole::Caption));
        painter.setPen(air().color(Color::Ink3));
        const QString note = painter.fontMetrics().elidedText(AirStrings::text(Text::InviteWhenOnline),
                                                              Qt::ElideRight, textWidth);
        painter.drawText(visual(this, QRect(textStart, top + nameHeight + Space::s2, textWidth,
                                            noteHeight)),
                         flags, note);
    }
}

void AirInviteRow::enterEvent(QEvent* event)
{
    hovered = true;
    update();
    QAbstractButton::enterEvent(event);
}

void AirInviteRow::leaveEvent(QEvent* event)
{
    hovered = false;
    update();
    QAbstractButton::leaveEvent(event);
}

// ---- AirInviteList ----

AirInviteList::AirInviteList(QWidget* parent)
    : QWidget(parent)
{
    auto* const column = new QVBoxLayout(this);
    column->setContentsMargins(0, 0, 0, 0);
    column->setSpacing(Space::s8);
    search = new AirTextField(AirTextField::Kind::Line, this);
    search->setObjectName(QStringLiteral("inviteSearch"));
    column->addWidget(search);
    empty = new QLabel(this);
    empty->setTextFormat(Qt::PlainText);
    empty->setWordWrap(true);
    column->addWidget(empty);
    rowsHolder = new QWidget(this);
    rows = new QVBoxLayout(rowsHolder);
    rows->setContentsMargins(0, 0, 0, 0);
    rows->setSpacing(0);
    column->addWidget(rowsHolder);

    connect(search, &AirTextField::changed, this, [this] {
        query = search->text();
        rebuild();
    });
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirInviteList::restyle);
    Translator::registerHandler(std::bind(&AirInviteList::retranslate, this), this);
    restyle();
    retranslate();
}

AirInviteList::~AirInviteList()
{
    Translator::unregister(this);
}

void AirInviteList::setCandidates(const QVector<Air::InviteCandidate>& candidates_)
{
    if (candidates_ == candidates) {
        // Nothing changed: the rows, the focus and the scroll stay as they are.
        return;
    }
    candidates = candidates_;
    // A choice stays only while its contact can still be invited.
    QSet<QString> kept;
    for (const Air::InviteCandidate& candidate : candidates) {
        if (candidate.canInvite() && chosenKeys.contains(candidate.publicKey)) {
            kept.insert(candidate.publicKey);
        }
    }
    const bool changed = kept != chosenKeys;
    chosenKeys = kept;
    rebuild();
    if (changed) {
        emit choiceChanged(chosenKeys.size());
    }
}

QVector<Air::InviteCandidate> AirInviteList::chosen() const
{
    QVector<Air::InviteCandidate> result;
    for (const Air::InviteCandidate& candidate : Air::arrangedInvites(candidates, QString())) {
        if (candidate.canInvite() && chosenKeys.contains(candidate.publicKey)) {
            result.append(candidate);
        }
    }
    return result;
}

void AirInviteList::choose(const QString& publicKey)
{
    for (const Air::InviteCandidate& candidate : candidates) {
        if (candidate.publicKey == publicKey && candidate.canInvite()
            && !chosenKeys.contains(publicKey)) {
            chosenKeys.insert(publicKey);
            rebuild();
            emit choiceChanged(chosenKeys.size());
            return;
        }
    }
}

void AirInviteList::clearChoice()
{
    const bool changed = !chosenKeys.isEmpty() || !query.isEmpty();
    chosenKeys.clear();
    query.clear();
    search->setText(QString());
    rebuild();
    if (changed) {
        emit choiceChanged(0);
    }
}

void AirInviteList::retranslate()
{
    search->setPlaceholder(AirStrings::text(Text::InviteSearch));
    search->edit()->setAccessibleName(AirStrings::text(Text::InviteSearch));
    rebuild();
}

void AirInviteList::restyle()
{
    empty->setFont(air().font(TextRole::Caption));
    AirPaint::textColor(empty, air().color(Color::Ink2));
}

void AirInviteList::rebuild()
{
    QWidget* const focused = focusWidget();
    const QString focusedKey = focused && focused->parentWidget() == rowsHolder
                                   ? static_cast<AirInviteRow*>(focused)->candidate().publicKey
                                   : QString();
    while (QLayoutItem* item = rows->takeAt(0)) {
        delete item->widget();
        delete item;
    }
    search->setVisible(candidates.size() >= inviteSearchFrom);
    const QVector<Air::InviteCandidate> shown = Air::arrangedInvites(candidates, query);
    for (const Air::InviteCandidate& candidate : shown) {
        auto* const row = new AirInviteRow(candidate, rowsHolder);
        row->setChecked(chosenKeys.contains(candidate.publicKey));
        const QString key = candidate.publicKey;
        connect(row, &AirInviteRow::toggled, this, [this, key](bool checked) {
            if (checked) {
                chosenKeys.insert(key);
            } else {
                chosenKeys.remove(key);
            }
            emit choiceChanged(chosenKeys.size());
        });
        rows->addWidget(row);
        if (key == focusedKey) {
            row->setFocus(Qt::OtherFocusReason);
        }
    }
    // Tab reaches the rows after the search, not after everything else in the window.
    AirFocus::inPlace(rowsHolder);
    if (candidates.isEmpty()) {
        empty->setText(AirStrings::text(Text::InviteNoContacts));
    } else if (shown.isEmpty()) {
        empty->setText(AirStrings::text(Text::InviteNobodyFound));
    } else {
        empty->clear();
    }
    empty->setVisible(!empty->text().isEmpty());
    rowsHolder->setVisible(!shown.isEmpty());
    updateGeometry();
}
