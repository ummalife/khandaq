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

#include "airconversation.h"

#include <QTime>
#include <QTimeZone>

#include <algorithm>

namespace Air {

namespace {
constexpr qint64 daySeconds = 86400;
constexpr qint64 weekSeconds = 7 * daySeconds;
constexpr int dayHours = 24;
/** A run of best hours this long or longer says nothing the strip does not. */
constexpr int bestHoursMaxLength = 20;

/** time, in the time zone of like. */
QDateTime inZoneOf(const QDateTime& time, const QDateTime& like)
{
    switch (like.timeSpec()) {
    case Qt::TimeZone:
        return time.toTimeZone(like.timeZone());
    case Qt::OffsetFromUTC:
        return time.toOffsetFromUtc(like.offsetFromUtc());
    case Qt::UTC:
    case Qt::LocalTime:
        break;
    }
    return time.toTimeSpec(like.timeSpec());
}

/** The clock time hour:00 of date, in the time zone of like. */
QDateTime atHour(const QDate& date, int hour, const QDateTime& like)
{
    const QTime time(hour, 0);
    switch (like.timeSpec()) {
    case Qt::TimeZone:
        return QDateTime(date, time, like.timeZone());
    case Qt::OffsetFromUTC:
        return QDateTime(date, time, Qt::OffsetFromUTC, like.offsetFromUtc());
    case Qt::UTC:
    case Qt::LocalTime:
        break;
    }
    return QDateTime(date, time, like.timeSpec());
}

QDateTime fromSeconds(qint64 seconds, const QDateTime& like)
{
    return inZoneOf(QDateTime::fromSecsSinceEpoch(seconds, Qt::UTC), like);
}
} // namespace

EntryStatus outgoingTextStatus(bool isSaved, bool isDelivered, bool handedOver, bool personOnline)
{
    if (isSaved) {
        return EntryStatus::None;
    }
    if (isDelivered) {
        return EntryStatus::Delivered;
    }
    if (!personOnline) {
        return EntryStatus::Transit;
    }
    return handedOver ? EntryStatus::Sent : EntryStatus::Sending;
}

EntryStatus directTextStatus(bool isSaved, bool isDelivered, bool gaveUp, bool handedOver,
                             bool personOnline)
{
    if (isSaved) {
        return EntryStatus::None;
    }
    if (isDelivered) {
        return EntryStatus::Delivered;
    }
    if (gaveUp) {
        return EntryStatus::Failed;
    }
    return outgoingTextStatus(false, false, handedOver, personOnline);
}

QString statusIconName(EntryStatus status)
{
    switch (status) {
    case EntryStatus::Transit:
    case EntryStatus::Sending:
        return QStringLiteral("clock");
    case EntryStatus::Sent:
        return QStringLiteral("check");
    case EntryStatus::Delivered:
        return QStringLiteral("check-double");
    case EntryStatus::Failed:
        return QStringLiteral("alert");
    case EntryStatus::None:
        break;
    }
    return QString{};
}

Delivery rowDelivery(EntryStatus status)
{
    switch (status) {
    case EntryStatus::Delivered:
        return Delivery::Delivered;
    case EntryStatus::Sent:
        return Delivery::Sent;
    case EntryStatus::Failed:
        return Delivery::Failed;
    case EntryStatus::Transit:
    case EntryStatus::Sending:
        return Delivery::Transit;
    case EntryStatus::None:
        break;
    }
    return Delivery::None;
}

bool startsRun(const RunKey& entry, const RunKey* previous)
{
    if (!previous) {
        return true;
    }
    if (!previous->isText || previous->author != entry.author) {
        return true;
    }
    if (inZoneOf(previous->date, entry.date).date() != entry.date.date()) {
        return true;
    }
    return previous->date.secsTo(entry.date) > runGapSeconds;
}

QString shortName(const QString& name)
{
    const QString trimmed = name.trimmed();
    int end = 0;
    while (end < trimmed.size() && !trimmed.at(end).isSpace()) {
        ++end;
    }
    return trimmed.left(end);
}

DayMarkKind dayMarkKind(const QDate& date, const QDate& today)
{
    if (date == today) {
        return DayMarkKind::Today;
    }
    if (date == today.addDays(-1)) {
        return DayMarkKind::Yesterday;
    }
    return DayMarkKind::Date;
}

LinkState linkState(const Reach& reach)
{
    switch (reach.kind) {
    case Reach::Kind::Direct:
        return LinkState::Direct;
    case Reach::Kind::Relay:
        return LinkState::Relay;
    case Reach::Kind::Away:
    case Reach::Kind::Pending:
    case Reach::Kind::Connecting:
    case Reach::Kind::Group:
    case Reach::Kind::Local:
        break;
    }
    return LinkState::None;
}

ComposeHint ComposeHint::make(bool isSaved, bool contactExists, const Reach& reach, NetworkState self)
{
    ComposeHint hint;
    if (isSaved) {
        hint.kind = Kind::Saved;
    } else if (!contactExists) {
        hint.kind = Kind::Empty;
    } else if (self == NetworkState::Connecting || self == NetworkState::Offline) {
        hint.kind = self == NetworkState::Connecting ? Kind::SelfConnecting : Kind::SelfOffline;
        hint.dot = Dot::Transit;
    } else if (reach.isLive()) {
        hint.kind = Kind::Live;
        hint.dot = Dot::Live;
    } else {
        hint.kind = Kind::Wait;
        hint.dot = Dot::Transit;
    }
    return hint;
}

bool ComposeHint::operator==(const ComposeHint& other) const
{
    return kind == other.kind && dot == other.dot;
}

bool ComposeRoom::operator==(const ComposeRoom& other) const
{
    return shown == other.shown && left == other.left;
}

ComposeRoom composeRoom(int byteCount, int limitBytes)
{
    ComposeRoom room;
    room.left = limitBytes - byteCount;
    room.shown = static_cast<double>(room.left) < static_cast<double>(limitBytes) * 0.1;
    return room;
}

QStringList keyGroups(const QString& key, int size)
{
    QString characters;
    characters.reserve(key.size());
    for (const QChar character : key.toUpper()) {
        if (!character.isSpace()) {
            characters.append(character);
        }
    }
    QStringList groups;
    if (size <= 0 || characters.isEmpty()) {
        return groups;
    }
    for (int start = 0; start < characters.size(); start += size) {
        groups.append(characters.mid(start, size));
    }
    return groups;
}

QString keyLines(const QString& key, int perLine)
{
    const QStringList groups = keyGroups(key);
    if (perLine <= 0) {
        return groups.join(QLatin1Char(' '));
    }
    QStringList lines;
    for (int start = 0; start < groups.size(); start += perLine) {
        lines.append(groups.mid(start, perLine).join(QLatin1Char(' ')));
    }
    return lines.join(QLatin1Char('\n'));
}

bool DayStrip::hasMarks() const
{
    return online.contains(true);
}

QVector<QPair<int, int>> DayStrip::ranges() const
{
    QVector<QPair<int, int>> result;
    int start = -1;
    for (int hour = 0; hour <= dayHours; ++hour) {
        const bool isOn = hour < online.size() && online.at(hour);
        if (isOn && start < 0) {
            start = hour;
        } else if (!isOn && start >= 0) {
            result.append(qMakePair(start, hour));
            start = -1;
        }
    }
    return result;
}

DayStrip DayStrip::make(const QSet<qint64>& hourStarts, const QDateTime& now)
{
    DayStrip strip;
    strip.nowHour = now.time().hour();
    strip.online = QVector<bool>(dayHours, false);
    const QDate today = now.date();
    for (int hour = 0; hour < dayHours; ++hour) {
        const QDate day = hour <= strip.nowHour ? today : today.addDays(-1);
        const QDateTime start = atHour(day, hour, now);
        // A clock hour skipped by a daylight saving change has no start of its own.
        if (start.time().hour() != hour) {
            continue;
        }
        strip.online[hour] = hourStarts.contains(start.toSecsSinceEpoch());
    }
    return strip;
}

bool DayStrip::operator==(const DayStrip& other) const
{
    return online == other.online && nowHour == other.nowHour;
}

bool bestHours(const QSet<qint64>& hourStarts, const QDateTime& now, int* from, int* to)
{
    const qint64 weekAgo = now.toSecsSinceEpoch() - weekSeconds;
    QVector<QSet<qint64>> daysPerHour(dayHours);
    QSet<qint64> allDays;
    for (const qint64 start : hourStarts) {
        if (start < weekAgo) {
            continue;
        }
        const QDateTime time = fromSeconds(start, now);
        const qint64 day = atHour(time.date(), 0, now).toSecsSinceEpoch();
        daysPerHour[time.time().hour()].insert(day);
        allDays.insert(day);
    }
    if (allDays.size() < 2) {
        return false;
    }

    int top = 0;
    for (const QSet<qint64>& days : daysPerHour) {
        top = std::max(top, days.size());
    }
    if (top < 2) {
        return false;
    }

    int bestFrom = -1;
    int bestLength = 0;
    int start = -1;
    for (int hour = 0; hour <= dayHours; ++hour) {
        const bool isTop = hour < dayHours && daysPerHour.at(hour).size() == top;
        if (isTop && start < 0) {
            start = hour;
        } else if (!isTop && start >= 0) {
            if (hour - start > bestLength) {
                bestFrom = start;
                bestLength = hour - start;
            }
            start = -1;
        }
    }
    if (bestFrom < 0 || bestLength >= bestHoursMaxLength) {
        return false;
    }
    if (from) {
        *from = bestFrom;
    }
    if (to) {
        *to = bestFrom + bestLength;
    }
    return true;
}

DayHint dayHint(bool hasBest, const DayStrip& strip)
{
    if (hasBest) {
        return DayHint::Best;
    }
    return strip.hasMarks() ? DayHint::Ranges : DayHint::Empty;
}

qint64 hourStart(const QDateTime& time)
{
    return atHour(time.date(), time.time().hour(), time).toSecsSinceEpoch();
}

TrayDetail trayDetail(bool personLive, bool hasPush)
{
    if (personLive) {
        return TrayDetail::Live;
    }
    return hasPush ? TrayDetail::Push : TrayDetail::Plain;
}

QVector<qint64> leftTray(const QVector<qint64>& before, const QSet<qint64>& now)
{
    QVector<qint64> left;
    for (const qint64 id : before) {
        if (!now.contains(id)) {
            left.append(id);
        }
    }
    return left;
}

} // namespace Air
