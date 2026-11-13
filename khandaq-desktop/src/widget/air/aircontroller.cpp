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

#include "aircontroller.h"

#include "src/persistence/settings.h"
#include "src/widget/air/airband.h"
#include "src/widget/air/airlistview.h"
#include "src/widget/air/airsource.h"
#include "src/widget/translator.h"

#include <QLineEdit>
#include <QTimer>

namespace {
constexpr int coalesceMs = 30;
// Reach words count minutes: a tick each half minute keeps them right without busy work.
constexpr int tickMs = 30 * 1000;
} // namespace

AirController::AirController(AirSource& source_, AirBand& band_, AirListView& list_,
                             Settings& settings_, QObject* parent)
    : QObject(parent)
    , source{source_}
    , band{band_}
    , list{list_}
    , settings{settings_}
{
    coalesce = new QTimer(this);
    coalesce->setSingleShot(true);
    coalesce->setInterval(coalesceMs);
    connect(coalesce, &QTimer::timeout, this, &AirController::rebuild);

    tick = new QTimer(this);
    tick->setInterval(tickMs);
    connect(tick, &QTimer::timeout, this, &AirController::refresh);
    tick->start();

    settleEnd = new QTimer(this);
    settleEnd->setSingleShot(true);
    connect(settleEnd, &QTimer::timeout, this, &AirController::refresh);

    connect(&source, &AirSource::changed, this, &AirController::refresh);
    connect(&source, &AirSource::avatarChanged, &list, &AirListView::forgetAvatar);
    connect(&source, &AirSource::selfAvatarChanged, this, &AirController::updateSelf);

    connect(&band, &AirBand::queryChanged, this, &AirController::onQueryChanged);
    connect(&band, &AirBand::filterToggled, this, &AirController::onFilterToggled);
    connect(&band, &AirBand::navigateRequested, &list, &AirListView::moveCursor);
    connect(&band, &AirBand::activateRequested, &list, &AirListView::activateCursor);
    connect(&band, &AirBand::contactAddressEntered, this, &AirController::newContactRequested);
    connect(&band, &AirBand::newContactRequested, this, [this] { emit newContactRequested({}); });

    connect(&list, &AirListView::entryActivated, this, &AirController::chatRequested);
    // A matching message opens its conversation.
    connect(&list, &AirListView::messageActivated, this, &AirController::chatRequested);
    connect(&list, &AirListView::favoriteToggled, this, &AirController::favoriteToggled);
    connect(&list, &AirListView::entryMenuRequested, this, &AirController::entryMenuRequested);
    connect(&list, &AirListView::backgroundMenuRequested, this,
            &AirController::backgroundMenuRequested);
    connect(&list, &AirListView::requestAccepted, this, &AirController::requestAccepted);
    connect(&list, &AirListView::requestDeclined, this, &AirController::requestDeclined);
    connect(&list, &AirListView::emptyActionTriggered, this, [this](Air::EmptyState state) {
        if (state == Air::EmptyState::NothingYet) {
            emit newContactRequested({});
        } else {
            showAll();
        }
    });

    list.setCompact(settings.getCompactLayout());
    list.setAvatarProvider([this](const QString& photoKey) { return source.avatar(photoKey); });
    connect(&settings, &Settings::compactLayoutChanged, &list, &AirListView::setCompact);
    connect(&settings, &Settings::favoriteChatsChanged, this, &AirController::refresh);

    // Hints, times and section words are built in the current language.
    Translator::registerHandler([this] { refresh(); }, this);

    band.setFilter(filter);
    updateSelf();
    refresh();
}

AirController::~AirController()
{
    Translator::unregister(this);
}

void AirController::setOpenChat(const QString& entryId)
{
    openEntry = entryId;
    list.setOpenEntry(entryId);
    // Opening a conversation clears its unread mark, and only a rebuild takes that in.
    refresh();
}

QString AirController::openChat() const
{
    return openEntry;
}

QString AirController::chatNextTo(bool forward) const
{
    return list.entryNextTo(openEntry, forward);
}

void AirController::setSelfName(const QString& name)
{
    selfName = name;
    updateSelf();
}

void AirController::setFilter(Air::Filter next)
{
    if (filter == next) {
        return;
    }
    filter = next;
    band.setFilter(filter);
    refreshNow();
    // The cards are what Requests is for: the list goes to them and shows them (E12).
    if (filter == Air::Filter::Requests) {
        list.highlightRequests();
    }
}

void AirController::revealRequests()
{
    if (filter != Air::Filter::Requests) {
        setFilter(Air::Filter::Requests);
        return;
    }
    list.highlightRequests();
}

void AirController::lightRequest(const QString& requestId)
{
    list.highlightRequests(requestId);
}

void AirController::showAll()
{
    band.commandLine()->clear();
    query.clear();
    filter = Air::Filter::All;
    band.setFilter(filter);
    refreshNow();
}

void AirController::refresh()
{
    if (!coalesce->isActive()) {
        coalesce->start();
    }
}

void AirController::refreshNow()
{
    coalesce->stop();
    rebuild();
}

void AirController::rebuild()
{
    const QVector<Air::Entry> entries = source.entries();
    const QVector<Air::MessageHit> hits =
        query.isEmpty() ? QVector<Air::MessageHit>{} : source.search(query);
    const Air::Snapshot snapshot = Air::build(entries, source.requests(), hits, filter, query);
    list.setSnapshot(snapshot, query);
    band.setCounts(snapshot.counts);
    band.setNetwork(source.networkState());

    // The rows that say "connecting" right after we come online change back when that ends.
    const int settling = source.settlingLeft();
    if (settling > 0) {
        settleEnd->start(settling + coalesceMs);
    }
}

void AirController::updateSelf()
{
    band.setSelf(selfName, source.selfAvatar());
}

void AirController::onFilterToggled(Air::Filter toggled)
{
    // Pressing the active pill again returns to every chat.
    setFilter(filter == toggled ? Air::Filter::All : toggled);
}

void AirController::onQueryChanged(const QString& next)
{
    if (query == next) {
        return;
    }
    query = next;
    // Typing answers at once; only bursts from the client are gathered.
    refreshNow();
}
