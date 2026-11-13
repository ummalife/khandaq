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

#include "airlistview.h"

#include "src/widget/air/airaccessible.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"

#include <QAccessible>
#include <QContextMenuEvent>
#include <QCursor>
#include <QHelpEvent>
#include <QKeyEvent>
#include <QLinearGradient>
#include <QMouseEvent>
#include <QPainter>
#include <QPainterPath>
#include <QScrollBar>
#include <QSet>
#include <QStyle>
#include <QTextLayout>
#include <QTimer>
#include <QToolTip>

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

// How far a row rises while the first list arrives.
constexpr int riseDistance = Space::s8;
// The tallest group of rows that arrives one after another; the rest come with the last of them.
constexpr int staggerSteps = 5;
constexpr int frameInterval = 16;
constexpr int requestMessageLines = 3;

const AirStyle& air()
{
    return AirStyle::instance();
}

QFont weighted(TextRole role, int weight)
{
    QFont font = air().font(role);
    font.setWeight(weight);
    return font;
}

/** The key part of an id like "f:56A1..." or "c:9F3B...", which also sets the tone of its tile. */
QString toneKey(const QString& chatId)
{
    const int colon = chatId.indexOf(QLatin1Char(':'));
    return colon >= 0 ? chatId.mid(colon + 1) : chatId;
}

QString flatText(QString text)
{
    text.replace(QLatin1Char('\n'), QLatin1Char(' '));
    text.replace(QLatin1Char('\r'), QLatin1Char(' '));
    return text.simplified();
}

/**
 * About the narrowest width that wraps text into as many lines as width does, so the lines come out
 * even and no word is left alone on the last one. A few pixels of slack keep the painter, whose
 * glyph positions may round differently from these metrics, on the same breaks.
 */
int balancedWidth(const QFontMetrics& metrics, int width, int flags, const QString& text)
{
    const auto heightAt = [&](int candidate) {
        return metrics.boundingRect(QRect(0, 0, candidate, 100000), flags, text).height();
    };
    const int height = heightAt(width);
    int low = width / 2;
    int high = width;
    while (low < high) {
        const int middle = (low + high) / 2;
        if (heightAt(middle) > height) {
            low = middle + 1;
        } else {
            high = middle;
        }
    }
    return std::min(width, high + Space::s4);
}

/** The ground of the request cards stays lit this long after the Requests filter, then fades. */
constexpr int glowHoldMs = 900;

/** The words that open a card: the message of a request, the name of the group of an invitation. */
QString requestLead(const Air::Request& request)
{
    return request.isInvitation() ? request.groupName : flatText(request.message);
}

QFont requestLeadFont(const Air::Request& request)
{
    return request.isInvitation() ? weighted(TextRole::Body, QFont::DemiBold)
                                  : air().font(TextRole::Body);
}

/** The verb of a card: Accept a person, Join a group. */
QString acceptText(const Air::Request& request)
{
    return AirStrings::text(request.isInvitation() ? Text::InviteJoin : Text::RequestAccept);
}

/**
 * The quiet line of a card: the start of the key and that the name comes with Accept, or who
 * invites and, while they are away, that joining waits for them.
 */
QString requestNote(const Air::Request& request)
{
    if (!request.isInvitation()) {
        return AirStrings::text(Text::RequestKey).arg(Air::groupedKey(request.publicKey));
    }
    QString note = AirStrings::text(Text::InviteFrom).arg(request.inviterName);
    if (!request.inviterOnline) {
        note += QLatin1Char(' ') + AirStrings::text(Text::InviteWait).arg(request.inviterName);
    }
    return note;
}

/** The part of the quiet line set apart: the key in mono, the inviter's name in ink-2. */
QString requestNoteRun(const Air::Request& request)
{
    return request.isInvitation() ? request.inviterName : Air::groupedKey(request.publicKey);
}

/**
 * Lays out the quiet line of a card in lines of the caption's height, wrapped to the width of rect,
 * with its run set apart; draws it at the top of rect when painter is given. Returns its height.
 */
int requestNoteBlock(QPainter* painter, const QRect& rect, Qt::LayoutDirection direction,
                     const Air::Request& request)
{
    const QString text = requestNote(request);
    const QString run = requestNoteRun(request);
    const int lineHeight = air().lineHeight(TextRole::Caption);

    QTextCharFormat plain;
    plain.setForeground(air().color(Color::Ink3));
    QTextCharFormat apart;
    apart.setForeground(air().color(Color::Ink2));
    if (!request.isInvitation()) {
        apart.setFont(air().font(TextRole::Mono));
    }
    QVector<QTextLayout::FormatRange> formats;
    const auto addRange = [&formats](int start, int length, const QTextCharFormat& format) {
        if (length > 0) {
            QTextLayout::FormatRange range;
            range.start = start;
            range.length = length;
            range.format = format;
            formats.append(range);
        }
    };
    const int start = run.isEmpty() ? -1 : text.indexOf(run);
    if (start < 0) {
        addRange(0, text.size(), plain);
    } else {
        addRange(0, start, plain);
        addRange(start, run.size(), apart);
        addRange(start + run.size(), text.size() - start - run.size(), plain);
    }

    QTextLayout layout(text, air().font(TextRole::Caption));
    QTextOption option(Qt::AlignLeft);
    option.setWrapMode(QTextOption::WrapAtWordBoundaryOrAnywhere);
    option.setTextDirection(direction);
    layout.setTextOption(option);
    layout.setFormats(formats);
    layout.beginLayout();
    int lines = 0;
    for (QTextLine line = layout.createLine(); line.isValid(); line = layout.createLine()) {
        line.setLineWidth(rect.width());
        line.setPosition(QPointF(0, lines * lineHeight + (lineHeight - line.height()) / 2.0));
        ++lines;
    }
    layout.endLayout();
    if (painter) {
        layout.draw(painter, rect.topLeft());
    }
    return std::max(1, lines) * lineHeight;
}

} // namespace

// ---- Track ----

qreal AirListView::Track::at(qint64 time) const
{
    if (duration <= 0) {
        return to;
    }
    const qint64 elapsed = time - start - delay;
    if (elapsed <= 0) {
        return from;
    }
    if (elapsed >= duration) {
        return to;
    }
    return from + (to - from) * curve.valueForProgress(static_cast<qreal>(elapsed) / duration);
}

bool AirListView::Track::running(qint64 time) const
{
    return duration > 0 && time < start + delay + duration;
}

void AirListView::Track::jump(qreal value)
{
    from = value;
    to = value;
    duration = 0;
    delay = 0;
}

void AirListView::Track::animate(qreal target, qint64 time, int milliseconds,
                                 const QEasingCurve& easing, int delayMilliseconds)
{
    // A track at rest on its target has nothing to show: run anyway, it would keep the list
    // painting frames that do not change for the whole duration, as on each refresh of the times.
    if (milliseconds <= 0 || (!running(time) && qFuzzyCompare(to + 1.0, target + 1.0))) {
        jump(target);
        return;
    }
    from = at(time);
    to = target;
    start = time;
    duration = milliseconds;
    delay = delayMilliseconds;
    curve = easing;
}

// ---- AirListView ----

AirListView::AirListView(QWidget* parent)
    : QAbstractScrollArea(parent)
{
    setObjectName(QStringLiteral("airList"));
    setFrameShape(QFrame::NoFrame);
    setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setVerticalScrollBarPolicy(Qt::ScrollBarAsNeeded);
    setFocusPolicy(Qt::StrongFocus);
    setMouseTracking(true);
    viewport()->setMouseTracking(true);
    viewport()->setAutoFillBackground(false);
    verticalScrollBar()->setSingleStep(Size::rowDesk / 2);

    clock.start();
    frames = new QTimer(this);
    frames->setInterval(frameInterval);
    connect(frames, &QTimer::timeout, this, &AirListView::onFrame);

    emptyAction = new AirButton(AirButton::Kind::Plain, viewport());
    emptyAction->hide();
    connect(emptyAction, &AirButton::clicked, this,
            [this] { emit emptyActionTriggered(snapshot.emptyState); });

    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirListView::restyle);
    restyle();
    // Rows, cards and headings as elements of their own for a screen reader.
    AirAccessible::install();
}

AirListView::~AirListView() = default;

qint64 AirListView::now() const
{
    return clock.elapsed();
}

void AirListView::setAvatarProvider(AvatarProvider provider)
{
    avatars = std::move(provider);
    tileCache.clear();
    viewport()->update();
}

void AirListView::forgetAvatar(const QString& photoKey)
{
    const QString prefix = photoKey + QLatin1Char('|');
    for (auto it = tileCache.begin(); it != tileCache.end();) {
        if (it.key().startsWith(prefix)) {
            it = tileCache.erase(it);
        } else {
            ++it;
        }
    }
    viewport()->update();
}

void AirListView::setSnapshot(const Air::Snapshot& next, const QString& nextQuery)
{
    const bool visible = isVisible();
    // The first rows anyone sees arrive one after another; a list filled while hidden waits for
    // showEvent to do that.
    const bool firstLoad = visible && !shownRows && next.itemCount() > 0;
    Air::Changes changes;
    // Too many changes at once read as noise: the list then shows the new state straight away.
    const bool animate = visible && shownRows && Air::diff(snapshot, next, changes);
    snapshot = next;
    query = nextQuery;
    if (firstLoad) {
        shownRows = true;
    }
    relayout(animate, firstLoad);
}

void AirListView::showEvent(QShowEvent* event)
{
    QAbstractScrollArea::showEvent(event);
    if (!shownRows && snapshot.itemCount() > 0) {
        shownRows = true;
        relayout(false, true);
    }
}

void AirListView::setCompact(bool compact_)
{
    if (compact == compact_) {
        return;
    }
    compact = compact_;
    tileCache.clear();
    relayout(isVisible() && shownRows, false);
}

void AirListView::setOpenEntry(const QString& entryId)
{
    if (openEntry == entryId) {
        return;
    }
    openEntry = entryId;
    // A conversation opened from elsewhere (the toolbar, the next-chat keys) shows its row.
    const int index = lineIndexOf(QStringLiteral("entry:") + entryId);
    if (index >= 0) {
        ensureVisible(index);
    }
    viewport()->update();
}

int AirListView::heightOf(const Air::Row& row) const
{
    switch (row.type) {
    case Air::Row::Type::Header:
        return Space::s12 + air().lineHeight(TextRole::Label) + Space::s6;
    case Air::Row::Type::Request:
        return Space::s8 + requestCardHeight(row.item.request) + Space::s4;
    case Air::Row::Type::Entry:
        return compact ? Size::rowDeskCompact : Size::rowDesk;
    case Air::Row::Type::Message:
        return Size::rowDesk;
    }
    return Size::rowDesk;
}

int AirListView::requestCardHeight(const Air::Request& request) const
{
    const int width = std::max(1, viewport()->width() - 2 * Space::s12 - 2 * Space::s16);
    int height = Space::s16 + air().lineHeight(TextRole::Label);
    const QString lead = requestLead(request);
    if (!lead.isEmpty()) {
        const QFontMetrics body(requestLeadFont(request));
        const QRect bounds = body.boundingRect(QRect(0, 0, width, 10000), Qt::TextWordWrap, lead);
        const int lineCount = std::min(requestMessageLines,
                                       std::max(1, bounds.height() / std::max(1, body.lineSpacing())));
        height += Space::s8 + lineCount * air().lineHeight(TextRole::Body);
    }
    height += Space::s8 + requestNoteBlock(nullptr, QRect(0, 0, width, 0), layoutDirection(), request);
    height += Space::s8 + Size::control + Space::s16;
    return height;
}

int AirListView::contentHeight() const
{
    int height = 0;
    for (const Line& line : lines) {
        if (!line.leaving) {
            height = std::max(height, line.targetTop + line.height);
        }
    }
    height += emptyLayoutAt(0).height;
    return height + Space::s12;
}

void AirListView::relayout(bool animate, bool firstLoad)
{
    const qint64 time = now();
    const bool reduced = air().reducedMotion();
    const bool slide = animate && !reduced;
    const int fade = air().duration(Motion::state);

    const QString pressedId =
        pressed.line >= 0 && pressed.line < lines.size() ? lines[pressed.line].id : QString();

    QHash<QString, Line> previous;
    QVector<Line> leaving;
    // The first list is new throughout; without animation the old lines simply go.
    if (!firstLoad) {
        for (const Line& line : lines) {
            if (!line.leaving) {
                previous.insert(line.id, line);
            } else if (animate && line.opacity.running(time)) {
                leaving.append(line);
            }
        }
    }

    const QVector<Air::Row> rows = snapshot.rows();
    QVector<Line> next;
    next.reserve(rows.size());
    QSet<QString> kept;
    int top = 0;
    int index = 0;
    for (const Air::Row& row : rows) {
        Line line;
        line.id = row.id();
        line.row = row;
        line.height = heightOf(row);
        line.targetTop = top;

        const auto old = previous.constFind(line.id);
        if (old != previous.constEnd()) {
            kept.insert(line.id);
            line.top = old->top;
            line.opacity = old->opacity;
            line.rise = old->rise;
            line.roll = old->roll;
            line.previousCount = old->previousCount;
            if (slide) {
                line.top.animate(top, time, Motion::layout, air().easeStandard());
            } else {
                line.top.jump(top);
            }
            if (row.type == Air::Row::Type::Header && old->row.count != row.count) {
                line.previousCount = old->row.count;
                line.roll.jump(0);
                line.roll.animate(1, time, animate ? fade : 0, air().easeStandard());
            }
        } else {
            line.top.jump(top);
            line.roll.jump(1);
            if (firstLoad) {
                // Only the first list arrives row after row; later ones never stagger.
                const int delay = reduced ? 0 : std::min(index, staggerSteps) * Motion::stagger;
                line.opacity.jump(0);
                line.opacity.animate(1, time, air().duration(Motion::reveal), air().easeOut(), delay);
                if (reduced) {
                    line.rise.jump(0);
                } else {
                    line.rise.jump(riseDistance);
                    line.rise.animate(0, time, Motion::reveal, air().easeOut(), delay);
                }
            } else if (animate) {
                line.opacity.jump(0);
                line.opacity.animate(1, time, fade, air().easeStandard());
                line.rise.jump(0);
            } else {
                line.opacity.jump(1);
                line.rise.jump(0);
            }
        }
        top += line.height;
        next.append(line);
        ++index;
    }

    if (animate) {
        for (auto it = previous.constBegin(); it != previous.constEnd(); ++it) {
            if (kept.contains(it.key())) {
                continue;
            }
            Line gone = it.value();
            gone.leaving = true;
            gone.top.jump(gone.top.at(time));
            gone.rise.jump(gone.rise.at(time));
            gone.opacity.animate(0, time, fade, air().easeStandard());
            leaving.append(gone);
        }
    }

    // Lines on their way out are painted first, beneath the ones that stay.
    lines = leaving + next;
    lineIndex.clear();
    for (int i = 0; i < lines.size(); ++i) {
        if (!lines[i].leaving) {
            lineIndex.insert(lines[i].id, i);
        }
    }
    if (!cursorId.isEmpty() && !lineIndex.contains(cursorId)) {
        cursorId.clear();
    }
    // A press keeps its row through the update, so a click is not lost when a message arrives.
    pressed.line = pressedId.isEmpty() ? -1 : lineIndexOf(pressedId);
    if (pressed.line < 0) {
        pressed = Hit{};
    }
    refreshHover();

    if (snapshot.emptyState != Air::EmptyState::None) {
        emptyAction->setText(snapshot.emptyState == Air::EmptyState::NothingYet
                                 ? AirStrings::text(Text::NewContact)
                                 : AirStrings::text(Text::EmptyShowAll));
    }
    updateScrollRange();
    placeEmptyPanel();
    startFrames();
    viewport()->update();
    announceLines();
}

void AirListView::updateScrollRange()
{
    const int range = std::max(0, contentHeight() - viewport()->height());
    verticalScrollBar()->setRange(0, range);
    verticalScrollBar()->setPageStep(viewport()->height());
}

int AirListView::rowsBottom() const
{
    int bottom = 0;
    for (const Line& line : lines) {
        if (!line.leaving) {
            bottom = std::max(bottom, line.targetTop + line.height);
        }
    }
    return bottom;
}

AirListView::EmptyLayout AirListView::emptyLayoutAt(int top) const
{
    EmptyLayout layout;
    const Air::EmptyState state = snapshot.emptyState;
    if (state == Air::EmptyState::None) {
        return layout;
    }
    // The words keep a readable measure, about 34 characters, as on the other clients.
    const QFont body = air().font(TextRole::Caption);
    const int measure = QFontMetrics(body).averageCharWidth() * 34;
    const int width = std::max(1, std::min(viewport()->width() - 2 * Space::s20, measure));
    const int left = (viewport()->width() - width) / 2;
    const int flags = Qt::AlignHCenter | Qt::AlignTop | Qt::TextWordWrap;

    int y = top + Space::s32;
    const QRect titleBounds =
        QFontMetrics(weighted(TextRole::Heading, QFont::DemiBold))
            .boundingRect(QRect(0, 0, width, 10000), flags, AirStrings::emptyTitle(state));
    layout.title =
        QRect(left, y, width, std::max(titleBounds.height(), air().lineHeight(TextRole::Heading)));
    y += layout.title.height() + Space::s8;
    const QFontMetrics bodyMetrics(body);
    const QString bodyText = AirStrings::emptyBody(state);
    const int bodyWidth = balancedWidth(bodyMetrics, width, flags, bodyText);
    const QRect bodyBounds = bodyMetrics.boundingRect(QRect(0, 0, bodyWidth, 10000), flags, bodyText);
    layout.body = QRect((viewport()->width() - bodyWidth) / 2, y, bodyWidth, bodyBounds.height());
    y += layout.body.height() + Space::s16;
    const QSize button = emptyAction->sizeHint();
    layout.action =
        QRect((viewport()->width() - button.width()) / 2, y, button.width(), button.height());
    y += button.height() + Space::s32;
    layout.height = y - top;
    return layout;
}

void AirListView::placeEmptyPanel()
{
    if (snapshot.emptyState == Air::EmptyState::None) {
        emptyAction->hide();
        return;
    }
    const EmptyLayout layout = emptyLayoutAt(rowsBottom() - verticalScrollBar()->value());
    emptyAction->setGeometry(visual(layout.action));
    emptyAction->show();
}

void AirListView::paintEmpty(QPainter& painter) const
{
    const Air::EmptyState state = snapshot.emptyState;
    const EmptyLayout layout = emptyLayoutAt(rowsBottom() - verticalScrollBar()->value());
    const int flags = Qt::AlignHCenter | Qt::AlignTop | Qt::TextWordWrap;
    painter.setFont(weighted(TextRole::Heading, QFont::DemiBold));
    painter.setPen(air().color(Color::Ink));
    painter.drawText(visual(layout.title), flags, AirStrings::emptyTitle(state));
    painter.setFont(air().font(TextRole::Caption));
    painter.setPen(air().color(Color::Ink2));
    painter.drawText(visual(layout.body), flags, AirStrings::emptyBody(state));
}

void AirListView::startFrames()
{
    if (!frames->isActive()) {
        frames->start();
    }
}

void AirListView::onFrame()
{
    const qint64 time = now();
    bool running = false;
    bool pruned = false;
    for (const Line& line : lines) {
        if (line.top.running(time) || line.opacity.running(time) || line.rise.running(time)
            || line.roll.running(time)) {
            running = true;
        }
    }
    if (glow.running(time)) {
        running = true;
    }
    QVector<Line> remaining;
    remaining.reserve(lines.size());
    for (const Line& line : lines) {
        if (line.leaving && !line.opacity.running(time)) {
            pruned = true;
            continue;
        }
        remaining.append(line);
    }
    if (pruned) {
        const QString pressedId = pressed.line >= 0 ? lines[pressed.line].id : QString();
        lines = remaining;
        lineIndex.clear();
        for (int i = 0; i < lines.size(); ++i) {
            if (!lines[i].leaving) {
                lineIndex.insert(lines[i].id, i);
            }
        }
        pressed.line = pressedId.isEmpty() ? -1 : lineIndexOf(pressedId);
        if (pressed.line < 0) {
            pressed = Hit{};
        }
    }
    if (pruned || !running) {
        refreshHover();
    }
    viewport()->update();
    if (!running) {
        frames->stop();
    }
}

void AirListView::hoverAt(const QPoint& viewportPos)
{
    const Hit hit = viewport()->rect().contains(viewportPos) ? hitAt(viewportPos) : Hit{};
    if (hit.line == hovered.line && hit.spot == hovered.spot) {
        return;
    }
    hovered = hit;
    // Request cards are not clickable as a whole, only their buttons are.
    const bool clickable =
        hit.spot != Spot::None
        && !(hit.spot == Spot::Row && lines[hit.line].row.type == Air::Row::Type::Request);
    viewport()->setCursor(clickable ? Qt::PointingHandCursor : Qt::ArrowCursor);
    viewport()->update();
}

void AirListView::refreshHover()
{
    if (!viewport()->underMouse()) {
        if (hovered.line >= 0) {
            hovered = Hit{};
            viewport()->update();
        }
        return;
    }
    hoverAt(viewport()->mapFromGlobal(QCursor::pos()));
}

void AirListView::restyle()
{
    // AirStyle::changed also fires when the language changes, so the words are set here too.
    setAccessibleName(AirStrings::text(Text::ChatList));
    tileCache.clear();
    if (!lines.isEmpty() || snapshot.emptyState != Air::EmptyState::None) {
        relayout(false, false);
    }
    viewport()->update();
}

void AirListView::resizeEvent(QResizeEvent* event)
{
    QAbstractScrollArea::resizeEvent(event);
    // Request cards wrap their text, so their height follows the width.
    bool hasCards = false;
    for (const Line& line : lines) {
        if (line.row.type == Air::Row::Type::Request) {
            hasCards = true;
            break;
        }
    }
    if (hasCards) {
        relayout(false, false);
    } else {
        updateScrollRange();
        placeEmptyPanel();
    }
}

void AirListView::scrollContentsBy(int, int)
{
    placeEmptyPanel();
    viewport()->update();
}

QRect AirListView::visual(const QRect& logical) const
{
    return QStyle::visualRect(layoutDirection(), viewport()->rect(), logical);
}

QRect AirListView::lineRect(const Line& line) const
{
    const int top = qRound(line.top.at(now())) - verticalScrollBar()->value();
    return QRect(0, top, viewport()->width(), line.height);
}

QRect AirListView::menuRect(const QRect& row) const
{
    const int size = Size::controlSmall;
    const QRect logical(row.right() - Space::s12 - size + 1, row.top() + (row.height() - size) / 2,
                        size, size);
    return visual(logical);
}

QRect AirListView::favoriteRect(const QRect& row) const
{
    const int size = Size::controlSmall;
    const QRect logical(row.right() - Space::s12 - 2 * size - Space::s4 + 1,
                        row.top() + (row.height() - size) / 2, size, size);
    return visual(logical);
}

QRect AirListView::acceptRect(const Line& line, const QRect& row) const
{
    const QRect card = row.adjusted(Space::s12, Space::s8, -Space::s12, -Space::s4);
    const QFontMetrics metrics(air().font(TextRole::Label));
    const int width = metrics.horizontalAdvance(acceptText(line.row.item.request)) + 2 * Space::s16;
    const QRect logical(card.left() + Space::s16, card.bottom() - Space::s16 - Size::control + 1,
                        width, Size::control);
    return visual(logical);
}

QRect AirListView::declineRect(const Line& line, const QRect& row) const
{
    const QRect card = row.adjusted(Space::s12, Space::s8, -Space::s12, -Space::s4);
    const QFontMetrics metrics(air().font(TextRole::Label));
    const int acceptWidth =
        metrics.horizontalAdvance(acceptText(line.row.item.request)) + 2 * Space::s16;
    const int width =
        metrics.horizontalAdvance(AirStrings::text(Text::RequestDecline)) + 2 * Space::s16;
    const QRect logical(card.left() + Space::s16 + acceptWidth + Space::s8,
                        card.bottom() - Space::s16 - Size::control + 1, width, Size::control);
    return visual(logical);
}

AirListView::Hit AirListView::hitAt(const QPoint& pos) const
{
    Hit hit;
    for (int i = lines.size() - 1; i >= 0; --i) {
        const Line& line = lines[i];
        if (line.leaving) {
            continue;
        }
        const QRect row = lineRect(line);
        if (!row.contains(pos)) {
            continue;
        }
        hit.line = i;
        hit.spot = Spot::Row;
        if (line.row.type == Air::Row::Type::Entry) {
            if (favoriteRect(row).contains(pos)) {
                hit.spot = Spot::Favorite;
            } else if (menuRect(row).contains(pos)) {
                hit.spot = Spot::Menu;
            }
        } else if (line.row.type == Air::Row::Type::Request) {
            if (acceptRect(line, row).contains(pos)) {
                hit.spot = Spot::Accept;
            } else if (declineRect(line, row).contains(pos)) {
                hit.spot = Spot::Decline;
            }
        } else if (line.row.type == Air::Row::Type::Header) {
            hit.spot = Spot::None;
        }
        break;
    }
    return hit;
}

void AirListView::mouseMoveEvent(QMouseEvent* event)
{
    hoverAt(event->pos());
    QAbstractScrollArea::mouseMoveEvent(event);
}

void AirListView::leaveEvent(QEvent* event)
{
    hovered = Hit{};
    viewport()->update();
    QAbstractScrollArea::leaveEvent(event);
}

void AirListView::mousePressEvent(QMouseEvent* event)
{
    if (event->button() == Qt::LeftButton) {
        pressed = hitAt(event->pos());
        cursorVisible = false;
        viewport()->update();
    }
    QAbstractScrollArea::mousePressEvent(event);
}

void AirListView::mouseReleaseEvent(QMouseEvent* event)
{
    if (event->button() != Qt::LeftButton) {
        QAbstractScrollArea::mouseReleaseEvent(event);
        return;
    }
    const Hit hit = hitAt(event->pos());
    const Hit down = pressed;
    pressed = Hit{};
    if (hit.line < 0 || hit.line != down.line || hit.spot != down.spot) {
        viewport()->update();
        return;
    }
    const Line& line = lines[hit.line];
    switch (hit.spot) {
    case Spot::Row:
        cursorId = line.id;
        activateLine(hit.line);
        break;
    case Spot::Favorite:
        emit favoriteToggled(line.row.item.entry.id, !line.row.item.entry.isFavorite);
        break;
    case Spot::Menu: {
        const QRect anchor = menuRect(lineRect(line));
        emit entryMenuRequested(line.row.item.entry.id,
                                viewport()->mapToGlobal(QPoint(anchor.left(), anchor.bottom())));
        break;
    }
    case Spot::Accept:
    case Spot::Decline:
        pressRequest(line, hit.spot);
        break;
    case Spot::None:
        break;
    }
    viewport()->update();
}

void AirListView::contextMenuEvent(QContextMenuEvent* event)
{
    // Mouse menus arrive through the viewport and keyboard ones on the list itself: the global
    // position means the same in both.
    const Hit hit = event->reason() == QContextMenuEvent::Keyboard
                        ? Hit{}
                        : hitAt(viewport()->mapFromGlobal(event->globalPos()));
    if (hit.line >= 0 && lines[hit.line].row.type == Air::Row::Type::Entry) {
        emit entryMenuRequested(lines[hit.line].row.item.entry.id, event->globalPos());
        return;
    }
    if (event->reason() == QContextMenuEvent::Keyboard && !cursorId.isEmpty()) {
        const int index = lineIndexOf(cursorId);
        if (index >= 0 && lines[index].row.type == Air::Row::Type::Entry) {
            const QRect row = lineRect(lines[index]);
            emit entryMenuRequested(lines[index].row.item.entry.id,
                                    viewport()->mapToGlobal(row.center()));
            return;
        }
    }
    emit backgroundMenuRequested(event->globalPos());
}

bool AirListView::viewportEvent(QEvent* event)
{
    if (event->type() == QEvent::ToolTip) {
        auto* help = static_cast<QHelpEvent*>(event);
        const Hit hit = hitAt(help->pos());
        QString tip;
        if (hit.line >= 0 && hit.spot == Spot::Favorite) {
            tip = lines[hit.line].row.item.entry.isFavorite ? AirStrings::text(Text::RemoveFavorite)
                                                            : AirStrings::text(Text::AddFavorite);
        } else if (hit.line >= 0 && hit.spot == Spot::Menu) {
            tip = AirStrings::text(Text::RowActions);
        }
        if (tip.isEmpty()) {
            QToolTip::hideText();
            event->ignore();
        } else {
            QToolTip::showText(help->globalPos(), tip, viewport());
        }
        return true;
    }
    return QAbstractScrollArea::viewportEvent(event);
}

void AirListView::focusInEvent(QFocusEvent* event)
{
    if (event->reason() == Qt::TabFocusReason || event->reason() == Qt::BacktabFocusReason) {
        cursorVisible = true;
        const int current = cursorId.isEmpty() ? -1 : lineIndexOf(cursorId);
        if (current < 0 || !isNavigable(lines[current], true)) {
            moveCursorAmong(1, true);
        } else {
            // The ring stands on the row the cursor was left on, which the list may have scrolled
            // away from since: it comes back into view with the focus.
            ensureVisible(current);
        }
    }
    viewport()->update();
    QAbstractScrollArea::focusInEvent(event);
}

void AirListView::focusOutEvent(QFocusEvent* event)
{
    viewport()->update();
    QAbstractScrollArea::focusOutEvent(event);
}

void AirListView::keyPressEvent(QKeyEvent* event)
{
    const int index = lineIndexOf(cursorId);
    const bool onCard = index >= 0 && lines[index].row.type == Air::Row::Type::Request;
    switch (event->key()) {
    case Qt::Key_Down:
        moveCursorAmong(1, true);
        return;
    case Qt::Key_Up:
        moveCursorAmong(-1, true);
        return;
    case Qt::Key_Home:
        cursorId.clear();
        moveCursorAmong(1, true);
        return;
    case Qt::Key_End:
        cursorId.clear();
        moveCursorAmong(-1, true);
        return;
    case Qt::Key_Left:
    case Qt::Key_Right:
        if (onCard) {
            // Decline stands after Accept in the reading direction.
            const bool forward =
                (event->key() == Qt::Key_Right) == (layoutDirection() == Qt::LeftToRight);
            cursorSpot = forward ? Spot::Decline : Spot::Accept;
            cursorVisible = true;
            viewport()->update();
            return;
        }
        break;
    case Qt::Key_Return:
    case Qt::Key_Enter:
    case Qt::Key_Space:
        if (onCard) {
            pressRequest(lines[index], cursorSpot);
            return;
        }
        activateCursor();
        return;
    default:
        break;
    }
    QAbstractScrollArea::keyPressEvent(event);
}

int AirListView::lineIndexOf(const QString& id) const
{
    return lineIndex.value(id, -1);
}

bool AirListView::isNavigable(const Line& line, bool cards) const
{
    return !line.leaving
           && (line.row.type == Air::Row::Type::Entry || line.row.type == Air::Row::Type::Message
               || (cards && line.row.type == Air::Row::Type::Request));
}

void AirListView::moveCursor(int step)
{
    moveCursorAmong(step, hasFocus());
}

void AirListView::moveCursorAmong(int step, bool cards)
{
    QVector<int> order;
    for (int i = 0; i < lines.size(); ++i) {
        if (isNavigable(lines[i], cards)) {
            order.append(i);
        }
    }
    if (order.isEmpty()) {
        return;
    }
    int position = -1;
    const QString from = cursorId.isEmpty() ? QStringLiteral("entry:") + openEntry : cursorId;
    const int current = lineIndexOf(from);
    if (current >= 0) {
        position = order.indexOf(current);
    }
    if (position < 0) {
        position = step > 0 ? 0 : order.size() - 1;
    } else {
        position = std::max(0, std::min(static_cast<int>(order.size()) - 1, position + step));
    }
    if (cursorId != lines[order[position]].id) {
        // A card is entered on its first button.
        cursorSpot = Spot::Accept;
    }
    cursorId = lines[order[position]].id;
    cursorVisible = true;
    ensureVisible(order[position]);
    viewport()->update();
    announceCursor();
}

void AirListView::activateCursor()
{
    int index = lineIndexOf(cursorId);
    if (index < 0 && !query.isEmpty()) {
        for (int i = 0; i < lines.size(); ++i) {
            if (isNavigable(lines[i], false)) {
                index = i;
                break;
            }
        }
    }
    if (index >= 0) {
        activateLine(index);
    }
}

void AirListView::pressRequest(const Line& line, Spot spot)
{
    if (line.leaving || line.row.type != Air::Row::Type::Request) {
        return;
    }
    // The answer may rebuild the list, and this line with it: the id is kept first.
    const QString requestId = line.row.item.request.id;
    if (spot == Spot::Accept) {
        emit requestAccepted(requestId);
    } else if (spot == Spot::Decline) {
        emit requestDeclined(requestId);
    }
}

void AirListView::highlightRequests(const QString& requestId)
{
    int first = -1;
    for (int i = 0; i < lines.size(); ++i) {
        const Line& line = lines[i];
        if (!line.leaving && line.row.type == Air::Row::Type::Request
            && (requestId.isEmpty() || line.row.item.request.id == requestId)) {
            first = i;
            break;
        }
    }
    if (first < 0) {
        return;
    }
    glowId = requestId;
    ensureVisible(first);
    glow.jump(1);
    // Lit for a moment, then back; with less motion the way back is the short fade of any change.
    glow.animate(0, now(), air().duration(Motion::reveal), air().easeOut(), glowHoldMs);
    startFrames();
    viewport()->update();
}

void AirListView::activateLine(int index)
{
    const Line& line = lines[index];
    switch (line.row.type) {
    case Air::Row::Type::Entry:
        emit entryActivated(line.row.item.entry.id);
        break;
    case Air::Row::Type::Message:
        emit messageActivated(line.row.item.message.chatId);
        break;
    case Air::Row::Type::Request:
    case Air::Row::Type::Header:
        break;
    }
}

QStringList AirListView::spokenIds() const
{
    QStringList ids;
    for (const Line& line : lines) {
        if (!line.leaving) {
            ids.append(line.id);
        }
    }
    return ids;
}

QVector<AirListView::SpokenLine> AirListView::spokenLines() const
{
    QVector<SpokenLine> spoken;
    for (const QString& id : spokenIds()) {
        SpokenLine line;
        if (spokenLine(id, &line)) {
            spoken.append(line);
        }
    }
    return spoken;
}

bool AirListView::hasSpokenLine(const QString& id) const
{
    // A line on its way out is gone for a screen reader already, as it is for the keyboard.
    const int index = lineIndexOf(id);
    return index >= 0 && !lines[index].leaving;
}

bool AirListView::spokenLine(const QString& id, SpokenLine* spoken) const
{
    if (!hasSpokenLine(id)) {
        return false;
    }
    const int index = lineIndexOf(id);
    const Line& line = lines[index];
    SpokenLine item;
    item.id = line.id;
    item.type = line.row.type;
    item.rect = lineRect(line);
    item.cursor = cursorVisible && line.id == cursorId;
    switch (line.row.type) {
    case Air::Row::Type::Header:
        item.name = AirStrings::spokenSection(line.row.section, line.row.count);
        break;
    case Air::Row::Type::Request: {
        const Air::Request& request = line.row.item.request;
        item.name = AirStrings::spokenRequest(request);
        item.acceptName = acceptText(request);
        item.declineName = AirStrings::text(Text::RequestDecline);
        item.acceptRect = acceptRect(line, item.rect);
        item.declineRect = declineRect(line, item.rect);
        break;
    }
    case Air::Row::Type::Entry:
        item.name = AirStrings::spokenRow(line.row.item.entry);
        item.open = line.row.item.entry.id == openEntry;
        break;
    case Air::Row::Type::Message:
        item.name = AirStrings::spokenMessageHit(line.row.item.message);
        break;
    }
    *spoken = item;
    return true;
}

void AirListView::pressSpoken(const QString& id, SpokenPress press)
{
    // A line on its way out answers no press, as a click on it would not.
    if (!hasSpokenLine(id)) {
        return;
    }
    const int index = lineIndexOf(id);
    switch (press) {
    case SpokenPress::Line:
        activateLine(index);
        break;
    case SpokenPress::Accept:
        pressRequest(lines[index], Spot::Accept);
        break;
    case SpokenPress::Decline:
        pressRequest(lines[index], Spot::Decline);
        break;
    }
}

void AirListView::announceLines()
{
    // Nothing is built for a screen reader that is not there.
    if (!QAccessible::isActive()) {
        return;
    }
    QAccessibleEvent event(viewport(), QAccessible::ObjectReorder);
    QAccessible::updateAccessibility(&event);
}

void AirListView::announceCursor()
{
    if (!QAccessible::isActive() || !hasFocus()) {
        return;
    }
    const QStringList ids = spokenIds();
    for (int i = 0; i < ids.size(); ++i) {
        if (ids[i] == cursorId) {
            QAccessibleEvent event(viewport(), QAccessible::Focus);
            event.setChild(i);
            QAccessible::updateAccessibility(&event);
            return;
        }
    }
}

void AirListView::ensureVisible(int index)
{
    const Line& line = lines[index];
    QScrollBar* bar = verticalScrollBar();
    if (line.targetTop < bar->value()) {
        bar->setValue(line.targetTop);
    } else if (line.targetTop + line.height > bar->value() + viewport()->height()) {
        bar->setValue(line.targetTop + line.height - viewport()->height());
    }
}

QString AirListView::entryNextTo(const QString& entryId, bool forward) const
{
    QStringList order;
    for (const Line& line : lines) {
        if (!line.leaving && line.row.type == Air::Row::Type::Entry) {
            order.append(line.row.item.entry.id);
        }
    }
    if (order.isEmpty()) {
        return QString();
    }
    const int position = order.indexOf(entryId);
    if (position < 0) {
        return forward ? order.first() : order.last();
    }
    const int count = order.size();
    return order[(position + (forward ? 1 : count - 1)) % count];
}

// ---- painting ----

void AirListView::paintEvent(QPaintEvent*)
{
    QPainter painter(viewport());
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setRenderHint(QPainter::TextAntialiasing);
    painter.setLayoutDirection(layoutDirection());
    painter.fillRect(viewport()->rect(), air().color(Color::Surface));

    const qint64 time = now();
    for (const Line& line : lines) {
        QRect row = lineRect(line);
        row.translate(0, qRound(line.rise.at(time)));
        if (!row.intersects(viewport()->rect())) {
            continue;
        }
        const qreal opacity = line.opacity.at(time);
        if (opacity <= 0.0) {
            continue;
        }
        painter.save();
        painter.setOpacity(opacity);
        switch (line.row.type) {
        case Air::Row::Type::Header:
            paintHeader(painter, line, row, time);
            break;
        case Air::Row::Type::Request:
            paintRequest(painter, line, row);
            break;
        case Air::Row::Type::Entry:
            paintEntry(painter, line, row);
            break;
        case Air::Row::Type::Message:
            paintMessage(painter, line, row);
            break;
        }
        painter.restore();
    }
    if (snapshot.emptyState != Air::EmptyState::None) {
        paintEmpty(painter);
    }
}

void AirListView::paintHeader(QPainter& painter, const Line& line, const QRect& row, qint64 time) const
{
    const int height = air().lineHeight(TextRole::Label);
    const QRect area(row.left() + Space::s16, row.top() + Space::s12, row.width() - 2 * Space::s16,
                     height);
    const QFont countFont = weighted(TextRole::Caption, QFont::Medium);
    const QString count = QString::number(line.row.count);
    const int countWidth =
        std::max(QFontMetrics(countFont).horizontalAdvance(count),
                 QFontMetrics(countFont).horizontalAdvance(QString::number(line.previousCount)));

    painter.setPen(air().color(Color::Ink3));
    painter.setFont(air().font(TextRole::Label));
    const QRect title = visual(area.adjusted(0, 0, -(countWidth + Space::s8), 0));
    painter.drawText(title,
                     QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter),
                     QFontMetrics(painter.font())
                         .elidedText(AirStrings::sectionTitle(line.row.section), Qt::ElideRight,
                                     title.width()));

    painter.setFont(countFont);
    const QRect counter = visual(QRect(area.right() - countWidth + 1, area.top(), countWidth, height));
    AirPaint::rollingText(painter, counter,
                          QStyle::visualAlignment(layoutDirection(), Qt::AlignRight | Qt::AlignVCenter),
                          QString::number(line.previousCount), count, line.roll.at(time));
}

void AirListView::paintTile(QPainter& painter, const QRect& tile, const QString& title, int tone,
                            Air::EntryKind kind, bool pending, const QString& photoKey) const
{
    const qreal dpr = devicePixelRatioF();
    if (!photoKey.isEmpty() && avatars) {
        const QString key = QStringLiteral("%1|%2|%3").arg(photoKey).arg(tile.width()).arg(dpr);
        auto cached = tileCache.constFind(key);
        QPixmap rounded;
        if (cached != tileCache.constEnd()) {
            rounded = cached.value();
        } else {
            const QPixmap photo = avatars(photoKey);
            if (!photo.isNull()) {
                const int pixels = qRound(tile.width() * dpr);
                rounded = QPixmap(pixels, pixels);
                rounded.fill(Qt::transparent);
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
                tilePainter.end();
                rounded.setDevicePixelRatio(dpr);
            }
            tileCache.insert(key, rounded);
        }
        if (!rounded.isNull()) {
            painter.drawPixmap(tile.topLeft(), rounded);
            painter.setPen(QPen(air().color(Color::Line), Stroke::hairline));
            painter.setBrush(Qt::NoBrush);
            painter.drawRoundedRect(QRectF(tile).adjusted(0.5, 0.5, -0.5, -0.5), Radius::control,
                                    Radius::control);
            return;
        }
    }

    const QRectF box = QRectF(tile).adjusted(0.5, 0.5, -0.5, -0.5);
    if (kind == Air::EntryKind::Group) {
        // A group's tile has a second outline behind it, offset towards the end and up.
        const int offset = Space::s4 - 1;
        const QRectF back =
            box.translated(layoutDirection() == Qt::RightToLeft ? -offset : offset, -offset);
        painter.setPen(QPen(air().color(Color::LineStrong), Stroke::hairline));
        painter.setBrush(air().color(Color::Surface));
        painter.drawRoundedRect(back, Radius::control, Radius::control);
    }
    if (pending) {
        QPen dashed(air().color(Color::LineStrong), Stroke::hairline);
        dashed.setStyle(Qt::DashLine);
        painter.setPen(dashed);
        painter.setBrush(Qt::NoBrush);
    } else {
        painter.setPen(QPen(air().color(Color::Line), Stroke::hairline));
        painter.setBrush(air().color(Color::Sunken));
    }
    painter.drawRoundedRect(box, Radius::control, Radius::control);

    if (kind == Air::EntryKind::Saved) {
        // kq-tile-saved: the notes to yourself carry a bookmark in ink instead of initials.
        const int side = Size::iconSmall;
        const QPixmap glyph = air().icon(QStringLiteral("bookmark"), air().color(Color::Ink), side,
                                         devicePixelRatioF());
        painter.drawPixmap(QPointF(tile.left() + (tile.width() - side) / 2.0,
                                   tile.top() + (tile.height() - side) / 2.0),
                           glyph);
        return;
    }
    painter.setFont(weighted(TextRole::Caption, QFont::DemiBold));
    painter.setPen(pending ? air().color(Color::Ink3) : air().tone(tone));
    painter.drawText(tile, Qt::AlignCenter, Air::initials(title));
}

int AirListView::paintFlags(QPainter& painter, const Air::Entry& entry, const QRect& area) const
{
    // Laid out from the end of the line backwards: unread at the very end, then @, then transit.
    int end = area.right() + 1;
    const int centre = area.top() + area.height() / 2;
    const int badge = Size::badge;
    QFont captionStrong = weighted(TextRole::Caption, QFont::DemiBold);
    const QFontMetrics metrics(captionStrong);
    bool any = false;

    const auto place = [&](int width) {
        const QRect logical(end - width, centre - badge / 2, width, badge);
        end -= width + Space::s6;
        any = true;
        return visual(logical);
    };

    if (entry.unreadCount > 0) {
        const QString text = AirPaint::countText(entry.unreadCount);
        const QRect rect = place(std::max(badge, metrics.horizontalAdvance(text) + 2 * Space::s6));
        painter.setPen(Qt::NoPen);
        painter.setBrush(air().color(Color::Ink));
        painter.drawRoundedRect(rect, badge / 2.0, badge / 2.0);
        painter.setFont(captionStrong);
        painter.setPen(air().color(Color::OnInk));
        painter.drawText(rect, Qt::AlignCenter, text);
    }
    if (entry.mentioned) {
        const QRect rect = place(badge);
        painter.setPen(QPen(air().color(Color::Ink), Stroke::strong));
        painter.setBrush(Qt::NoBrush);
        painter.drawEllipse(QRectF(rect).adjusted(0.75, 0.75, -0.75, -0.75));
        painter.setFont(captionStrong);
        painter.setPen(air().color(Color::Ink));
        painter.drawText(rect, Qt::AlignCenter, QStringLiteral("@"));
    }
    if (entry.transitCount > 0) {
        const QString text = AirStrings::text(Text::TransitFlag).arg(entry.transitCount);
        const int width =
            Space::s6 + Size::iconSmall + Space::s4 + metrics.horizontalAdvance(text) + Space::s8;
        if (end - width >= area.left()) {
            const QRect rect = place(width);
            painter.setPen(Qt::NoPen);
            painter.setBrush(air().color(Color::TransitSoft));
            painter.drawRoundedRect(rect, badge / 2.0, badge / 2.0);
            const QRect logical = visual(rect);
            const QRect iconRect = visual(QRect(logical.left() + Space::s6,
                                                logical.top() + (badge - Size::iconSmall) / 2,
                                                Size::iconSmall, Size::iconSmall));
            painter.drawPixmap(iconRect.topLeft(),
                               air().icon(QStringLiteral("clock"), air().color(Color::Transit),
                                          Size::iconSmall, devicePixelRatioF()));
            const QRect words =
                visual(QRect(logical.left() + Space::s6 + Size::iconSmall + Space::s4, logical.top(),
                             logical.width() - Space::s6 - Size::iconSmall - Space::s4, badge));
            painter.setFont(captionStrong);
            painter.setPen(air().color(Color::Transit));
            painter.drawText(words,
                             QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter),
                             text);
        }
    }
    return any ? area.right() + 1 - end - Space::s6 : 0;
}

void AirListView::paintActionButton(QPainter& painter, const QRect& rect, const QString& icon,
                                    bool hot) const
{
    if (hot) {
        painter.setPen(Qt::NoPen);
        painter.setBrush(air().color(Color::Selected));
        painter.drawRoundedRect(rect, Radius::control, Radius::control);
    }
    const QColor ink = hot ? air().color(Color::Ink) : air().color(Color::Ink2);
    const QPixmap glyph = air().icon(icon, ink, Size::icon, devicePixelRatioF());
    painter.drawPixmap(rect.left() + (rect.width() - Size::icon) / 2,
                       rect.top() + (rect.height() - Size::icon) / 2, glyph);
}

void AirListView::paintTextButton(QPainter& painter, const QRect& rect, const QString& text,
                                  bool ink, bool hot) const
{
    QColor fill = ink ? (hot ? air().color(Color::InkHover) : air().color(Color::Ink))
                      : air().color(Color::Raised);
    QColor border = ink ? fill : (hot ? air().color(Color::Ink3) : air().color(Color::LineStrong));
    painter.setPen(QPen(border, Stroke::hairline));
    painter.setBrush(fill);
    painter.drawRoundedRect(QRectF(rect).adjusted(0.5, 0.5, -0.5, -0.5), Radius::control,
                            Radius::control);
    painter.setFont(air().font(TextRole::Label));
    painter.setPen(ink ? air().color(Color::OnInk) : air().color(Color::Ink));
    painter.drawText(rect, Qt::AlignCenter, text);
}

void AirListView::paintEntry(QPainter& painter, const Line& line, const QRect& row) const
{
    const Air::Entry& entry = line.row.item.entry;
    const int index = lineIndexOf(line.id);
    const bool isHovered = !line.leaving && index >= 0 && hovered.line == index;
    const bool isCursor = cursorVisible && cursorId == line.id && (hasFocus() || !query.isEmpty());
    const bool isOpen = entry.id == openEntry;

    QColor fill = air().color(Color::Surface);
    if (isOpen) {
        fill = air().color(Color::Selected);
    } else if (isHovered || isCursor) {
        fill = air().color(Color::Sunken);
    }
    painter.fillRect(row, fill);
    if (isCursor) {
        AirPaint::focusRing(painter, QRectF(row).adjusted(Space::s4, 1, -Space::s4, -1),
                            Radius::control);
    }

    const int tileSize = compact ? Size::tileCompact : Size::tileDesk;
    const QRect tile = visual(QRect(row.left() + Space::s16,
                                    row.top() + (row.height() - tileSize) / 2, tileSize, tileSize));
    const bool pending = entry.reach.kind == Air::Reach::Kind::Pending;
    paintTile(painter, tile, entry.title, entry.tone, entry.kind, pending, entry.photoKey);

    const int textLeft = row.left() + Space::s16 + tileSize + Space::s12;
    const int textRight = row.right() - Space::s16 + 1;
    const int bodyHeight = air().lineHeight(TextRole::Body);
    const int labelHeight = air().lineHeight(TextRole::Label);
    const int topLine = compact
                            ? row.top() + (row.height() - bodyHeight) / 2
                            : row.top() + (row.height() - bodyHeight - Space::s2 - labelHeight) / 2;

    // The reach mark stands at the end of the first line; a live one is green with a still dot. On
    // the selected row the green word falls below 4.5:1, so the word turns ink-2 and the dot alone
    // stays green.
    const QString reach = AirStrings::reachText(entry.reach);
    const bool live = entry.reach.isLive();
    const QFont caption = air().font(TextRole::Caption);
    const int reachTextWidth = QFontMetrics(caption).horizontalAdvance(reach);
    const int reachWidth = reachTextWidth + (live ? Size::dot + Space::s6 : 0);
    const QRect reachLogical(textRight - reachWidth, topLine, reachWidth, bodyHeight);
    if (!reach.isEmpty()) {
        if (live) {
            const QRect dot = visual(QRect(reachLogical.left(), topLine + (bodyHeight - Size::dot) / 2,
                                           Size::dot, Size::dot));
            painter.setPen(Qt::NoPen);
            painter.setBrush(air().color(Color::Live));
            painter.drawEllipse(dot);
        }
        Color wordColor = Color::Ink3;
        if (live) {
            wordColor = isOpen ? Color::Ink2 : Color::Live;
        }
        painter.setFont(caption);
        painter.setPen(air().color(wordColor));
        painter.drawText(visual(QRect(textRight - reachTextWidth, topLine, reachTextWidth, bodyHeight)),
                         Qt::AlignCenter, reach);
    }

    int nameRight = (reach.isEmpty() ? textRight : reachLogical.left()) - Space::s8;
    if (compact) {
        // One line only: the flags move up next to the reach mark.
        const int used =
            paintFlags(painter, entry, QRect(textLeft, topLine, nameRight - textLeft, bodyHeight));
        if (used > 0) {
            nameRight -= used + Space::s8;
        }
    }
    const int favoriteWidth = entry.isFavorite ? Size::iconSmall + Space::s6 : 0;
    const QFont nameFont = weighted(TextRole::Body, QFont::DemiBold);
    const QFontMetrics nameMetrics(nameFont);
    const int nameSpace = std::max(0, nameRight - textLeft - favoriteWidth);
    const QString name = nameMetrics.elidedText(entry.title, Qt::ElideRight, nameSpace);
    const int nameWidth = std::min(nameSpace, nameMetrics.horizontalAdvance(name));
    painter.setFont(nameFont);
    painter.setPen(pending ? air().color(Color::Ink2) : air().color(Color::Ink));
    AirPaint::ownText(painter, visual(QRect(textLeft, topLine, nameSpace, bodyHeight)),
                      QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter),
                      name);
    if (entry.isFavorite) {
        const QRect mark = visual(QRect(textLeft + nameWidth + Space::s6,
                                        topLine + (bodyHeight - Size::iconSmall) / 2,
                                        Size::iconSmall, Size::iconSmall));
        painter.drawPixmap(mark.topLeft(),
                           air().icon(QStringLiteral("bookmark"), air().color(Color::Ink3),
                                      Size::iconSmall, devicePixelRatioF()));
    }

    if (!compact) {
        const int bottomLine = topLine + bodyHeight + Space::s2;
        int x = textLeft;
        if (!entry.timeText.isEmpty()) {
            painter.setFont(caption);
            painter.setPen(air().color(Color::Ink3));
            const int width = QFontMetrics(caption).horizontalAdvance(entry.timeText);
            painter.drawText(visual(QRect(x, bottomLine, width, labelHeight)), Qt::AlignCenter,
                             entry.timeText);
            x += width + Space::s8;
        }
        const int flags = paintFlags(painter, entry, QRect(x, bottomLine, textRight - x, labelHeight));
        const int previewRight = textRight - (flags > 0 ? flags + Space::s8 : 0);

        if (entry.previewStyle == Air::PreviewStyle::Message
            && entry.lastDelivery != Air::Delivery::None) {
            QString icon;
            QColor color = air().color(Color::Ink3);
            switch (entry.lastDelivery) {
            case Air::Delivery::Transit:
                icon = QStringLiteral("clock");
                color = air().color(Color::Transit);
                break;
            case Air::Delivery::Sent:
                icon = QStringLiteral("check");
                break;
            case Air::Delivery::Delivered:
                icon = QStringLiteral("check-double");
                break;
            case Air::Delivery::Failed:
                icon = QStringLiteral("alert");
                color = air().color(Color::Danger);
                break;
            case Air::Delivery::None:
                break;
            }
            const QRect mark = visual(QRect(x, bottomLine + (labelHeight - Size::iconSmall) / 2,
                                            Size::iconSmall, Size::iconSmall));
            painter.drawPixmap(mark.topLeft(),
                               air().icon(icon, color, Size::iconSmall, devicePixelRatioF()));
            x += Size::iconSmall + Space::s4;
        }

        const QFont previewFont = weighted(TextRole::Label, QFont::Normal);
        const int previewWidth = std::max(0, previewRight - x);
        painter.setFont(previewFont);
        painter.setPen(entry.previewStyle == Air::PreviewStyle::Message ? air().color(Color::Ink2)
                                                                        : air().color(Color::Ink3));
        AirPaint::ownText(painter, visual(QRect(x, bottomLine, previewWidth, labelHeight)),
                          QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter),
                          QFontMetrics(previewFont)
                              .elidedText(flatText(entry.preview), Qt::ElideRight, previewWidth));
    }

    if (isHovered) {
        // The two row actions cover the end of the row while the pointer is on it.
        const QRect favorite = favoriteRect(row);
        const QRect menu = menuRect(row);
        const QRect logicalFavorite = visual(favorite);
        const QRect cover =
            visual(QRect(logicalFavorite.left() - Space::s8, row.top(),
                         row.right() - logicalFavorite.left() + Space::s8 + 1, row.height()));
        painter.fillRect(cover, fill);
        paintActionButton(painter, favorite, QStringLiteral("bookmark"),
                          hovered.spot == Spot::Favorite || entry.isFavorite);
        paintActionButton(painter, menu, QStringLiteral("chevron-down"), hovered.spot == Spot::Menu);
    }
}

void AirListView::paintMessage(QPainter& painter, const Line& line, const QRect& row) const
{
    const Air::MessageHit& hit = line.row.item.message;
    const int index = lineIndexOf(line.id);
    const bool isHovered = !line.leaving && index >= 0 && hovered.line == index;
    const bool isCursor = cursorVisible && cursorId == line.id;
    const QColor fill =
        isHovered || isCursor ? air().color(Color::Sunken) : air().color(Color::Surface);
    painter.fillRect(row, fill);
    if (isCursor) {
        AirPaint::focusRing(painter, QRectF(row).adjusted(Space::s4, 1, -Space::s4, -1),
                            Radius::control);
    }

    const int tileSize = Size::tileDesk;
    const QRect tile = visual(QRect(row.left() + Space::s16,
                                    row.top() + (row.height() - tileSize) / 2, tileSize, tileSize));
    paintTile(painter, tile, hit.title, Air::toneIndex(toneKey(hit.chatId)),
              hit.chatId.startsWith(QStringLiteral("c:")) ? Air::EntryKind::Group
                                                          : Air::EntryKind::Direct,
              false, QString());

    const int textLeft = row.left() + Space::s16 + tileSize + Space::s12;
    const int textRight = row.right() - Space::s16 + 1;
    const int bodyHeight = air().lineHeight(TextRole::Body);
    const int labelHeight = air().lineHeight(TextRole::Label);
    const int topLine = row.top() + (row.height() - bodyHeight - Space::s2 - labelHeight) / 2;

    const QFont caption = air().font(TextRole::Caption);
    const int timeWidth = QFontMetrics(caption).horizontalAdvance(hit.timeText);
    painter.setFont(caption);
    painter.setPen(air().color(Color::Ink3));
    painter.drawText(visual(QRect(textRight - timeWidth, topLine, timeWidth, bodyHeight)),
                     Qt::AlignCenter, hit.timeText);

    const QFont nameFont = weighted(TextRole::Body, QFont::DemiBold);
    const int nameSpace = std::max(0, textRight - timeWidth - Space::s8 - textLeft);
    painter.setFont(nameFont);
    painter.setPen(air().color(Color::Ink));
    AirPaint::ownText(painter, visual(QRect(textLeft, topLine, nameSpace, bodyHeight)),
                      QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter),
                      QFontMetrics(nameFont).elidedText(hit.title, Qt::ElideRight, nameSpace));

    // The snippet starts near the first match, and every match stands out in ink.
    QString snippet = flatText(hit.snippet);
    QVector<QPair<int, int>> ranges = Air::matchRanges(snippet, query);
    if (!ranges.isEmpty() && ranges.first().first > 24) {
        int start = snippet.lastIndexOf(QLatin1Char(' '), ranges.first().first - 12);
        start = start < 0 ? ranges.first().first - 12 : start + 1;
        snippet = snippet.mid(start);
        ranges = Air::matchRanges(snippet, query);
    }
    const QFont snippetFont = weighted(TextRole::Label, QFont::Normal);
    QFont matchFont = snippetFont;
    matchFont.setWeight(QFont::DemiBold);
    QTextLayout layout(snippet, snippetFont);
    QTextOption option;
    // The snippet is someone's message: it runs in its own direction, not the interface's.
    const Qt::LayoutDirection snippetDirection = AirPaint::ownDirection(snippet);
    option.setTextDirection(snippetDirection);
    // The glyphs start at the line's left edge whatever the direction; x below places the line.
    option.setAlignment(Qt::AlignLeft | Qt::AlignAbsolute);
    option.setWrapMode(QTextOption::NoWrap);
    layout.setTextOption(option);
    QVector<QTextLayout::FormatRange> formats;
    for (const auto& range : ranges) {
        QTextLayout::FormatRange format;
        format.start = range.first;
        format.length = range.second - range.first;
        format.format.setFont(matchFont);
        format.format.setForeground(air().color(Color::Ink));
        formats.append(format);
    }
    layout.setFormats(formats);
    layout.beginLayout();
    QTextLine textLine = layout.createLine();
    if (textLine.isValid()) {
        textLine.setLineWidth(1000000);
    }
    layout.endLayout();

    const int bottomLine = topLine + bodyHeight + Space::s2;
    const QRect snippetArea = visual(QRect(textLeft, bottomLine, textRight - textLeft, labelHeight));
    if (textLine.isValid()) {
        painter.save();
        painter.setClipRect(snippetArea);
        painter.setPen(air().color(Color::Ink2));
        const qreal natural = textLine.naturalTextWidth();
        const bool overflows = natural > snippetArea.width();
        // A snippet that fits stands at the start of the interface; a longer one keeps its own
        // start in view.
        const bool alignRight =
            overflows ? snippetDirection == Qt::RightToLeft : layoutDirection() == Qt::RightToLeft;
        const qreal x = alignRight ? snippetArea.right() + 1 - natural : snippetArea.left();
        const qreal y = snippetArea.top() + (labelHeight - textLine.height()) / 2.0;
        layout.draw(&painter, QPointF(x, y - textLine.y()));
        painter.restore();
        if (overflows) {
            // A long snippet fades out at its own end instead of being cut mid-letter.
            const int fadeWidth = Space::s24;
            const bool rtl = snippetDirection == Qt::RightToLeft;
            const QRect fade =
                rtl ? QRect(snippetArea.left(), snippetArea.top(), fadeWidth, labelHeight)
                    : QRect(snippetArea.right() + 1 - fadeWidth, snippetArea.top(), fadeWidth,
                            labelHeight);
            QLinearGradient gradient(fade.topLeft(), fade.topRight());
            QColor clear = fill;
            clear.setAlpha(0);
            gradient.setColorAt(rtl ? 1.0 : 0.0, clear);
            gradient.setColorAt(rtl ? 0.0 : 1.0, fill);
            painter.fillRect(fade, gradient);
        }
    }
}

void AirListView::paintRequest(QPainter& painter, const Line& line, const QRect& row) const
{
    const Air::Request& request = line.row.item.request;
    const QRect card = row.adjusted(Space::s12, Space::s8, -Space::s12, -Space::s4);
    // Separated by its ground, never a frame; the Requests filter lights the ground up once.
    QColor ground = air().color(Color::Sunken);
    const qreal lit = glowId.isEmpty() || glowId == request.id ? glow.at(now()) : 0.0;
    if (lit > 0.0) {
        const QColor peak = air().color(Color::Line);
        ground = QColor::fromRgbF(ground.redF() + (peak.redF() - ground.redF()) * lit,
                                  ground.greenF() + (peak.greenF() - ground.greenF()) * lit,
                                  ground.blueF() + (peak.blueF() - ground.blueF()) * lit);
    }
    painter.setPen(Qt::NoPen);
    painter.setBrush(ground);
    painter.drawRoundedRect(card, Radius::card, Radius::card);

    const QRect inner = card.adjusted(Space::s16, Space::s16, -Space::s16, -Space::s16);
    int y = inner.top();
    const int labelHeight = air().lineHeight(TextRole::Label);
    const QFont caption = air().font(TextRole::Caption);
    const int timeWidth = QFontMetrics(caption).horizontalAdvance(request.timeText);
    painter.setFont(caption);
    painter.setPen(air().color(Color::Ink3));
    painter.drawText(visual(QRect(inner.right() - timeWidth + 1, y, timeWidth, labelHeight)),
                     Qt::AlignCenter, request.timeText);
    painter.setFont(weighted(TextRole::Label, QFont::DemiBold));
    painter.setPen(air().color(Color::Ink));
    painter.drawText(visual(QRect(inner.left(), y, inner.width() - timeWidth - Space::s8, labelHeight)),
                     QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter),
                     AirStrings::text(request.isInvitation() ? Text::InviteTag : Text::RequestTag));
    y += labelHeight;

    const int alignment =
        static_cast<int>(QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignTop))
        | Qt::TextWordWrap;
    const QString lead = requestLead(request);
    if (!lead.isEmpty()) {
        y += Space::s8;
        const QFont body = requestLeadFont(request);
        const int maxHeight = requestMessageLines * air().lineHeight(TextRole::Body);
        const QRect bounds = QFontMetrics(body).boundingRect(QRect(0, 0, inner.width(), 10000),
                                                             Qt::TextWordWrap, lead);
        const int height = std::min(maxHeight, bounds.height());
        painter.save();
        painter.setClipRect(visual(QRect(inner.left(), y, inner.width(), height)));
        painter.setFont(body);
        painter.setPen(air().color(Color::Ink));
        AirPaint::ownText(painter, visual(QRect(inner.left(), y, inner.width(), height)), alignment,
                          lead);
        painter.restore();
        y += height;
    }

    y += Space::s8;
    requestNoteBlock(&painter, QRect(inner.left(), y, inner.width(), 0), layoutDirection(), request);

    const int index = lineIndexOf(line.id);
    const bool hotAccept = index >= 0 && hovered.line == index && hovered.spot == Spot::Accept;
    const bool hotDecline = index >= 0 && hovered.line == index && hovered.spot == Spot::Decline;
    const QRect accept = acceptRect(line, row);
    const QRect decline = declineRect(line, row);
    paintTextButton(painter, accept, acceptText(request), true, hotAccept);
    paintTextButton(painter, decline, AirStrings::text(Text::RequestDecline), false, hotDecline);
    // The keyboard cursor on a card stands on one of its buttons.
    if (cursorVisible && hasFocus() && cursorId == line.id && !line.leaving) {
        AirPaint::focusRing(painter, QRectF(cursorSpot == Spot::Decline ? decline : accept),
                            Radius::control);
    }
}
