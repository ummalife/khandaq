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

#include "airlocal.h"

#include "src/core/toxpk.h"
#include "src/model/friend.h"
#include "src/model/status.h"
#include "src/persistence/settings.h"

namespace {
/** An hour mark only changes when a new clock hour starts; a minute is close enough. */
constexpr int tickMs = 60 * 1000;
constexpr int saveDelayMs = 2000;
} // namespace

AirLocal::AirLocal(Settings& settings_, QObject* parent)
    : QObject(parent)
    , settings{settings_}
{
    store.load(settings.getAirLocalStore(), QDateTime::currentDateTime());

    ticker.setInterval(tickMs);
    connect(&ticker, &QTimer::timeout, this, &AirLocal::markOnlineFriends);
    ticker.start();

    saveLater.setSingleShot(true);
    saveLater.setInterval(saveDelayMs);
    connect(&saveLater, &QTimer::timeout, this, &AirLocal::save);
}

AirLocal::~AirLocal()
{
    // The settings are written on quit; whatever waited for the timer goes with them.
    if (saveLater.isActive()) {
        saveLater.stop();
        settings.setAirLocalStore(store.save());
    }
}

void AirLocal::watchFriend(Friend& frnd)
{
    const QString key = frnd.getPublicKey().toString();
    friends.insert(key, &frnd);
    connect(&frnd, &Friend::statusChanged, this, [this, &frnd] { onStatus(frnd); });
    connect(&frnd, &QObject::destroyed, this, [this, key] { friends.remove(key); });
    onStatus(frnd);
}

void AirLocal::forget(const ToxPk& publicKey)
{
    const QString key = publicKey.toString();
    const int waiting = store.requests().size() + store.invites().size();
    if (!store.forget(key)) {
        return;
    }
    if (store.requests().size() + store.invites().size() != waiting) {
        // The invitations of a removed contact go at once: Join would go through them.
        saveRequests();
    }
    noteChange(publicKey);
}

QSet<qint64> AirLocal::hours(const ToxPk& publicKey) const
{
    return store.hours(publicKey.toString());
}

QDateTime AirLocal::onlineSince(const ToxPk& publicKey) const
{
    return store.onlineSince(publicKey.toString());
}

bool AirLocal::isMuted(const ToxPk& publicKey) const
{
    return store.isMuted(publicKey.toString());
}

void AirLocal::setMuted(const ToxPk& publicKey, bool muted)
{
    if (store.setMuted(muted, publicKey.toString())) {
        noteChange(publicKey);
    }
}

QDateTime AirLocal::verifiedAt(const ToxPk& publicKey) const
{
    return store.verifiedAt(publicKey.toString());
}

void AirLocal::setVerified(const ToxPk& publicKey, bool verified)
{
    const QDateTime at = verified ? QDateTime::currentDateTime() : QDateTime();
    if (store.setVerified(at, publicKey.toString())) {
        noteChange(publicKey);
    }
}

QStringList AirLocal::blockedKeys() const
{
    return store.blockedKeys();
}

bool AirLocal::isBlocked(const ToxPk& publicKey) const
{
    return store.isBlocked(publicKey.toString());
}

void AirLocal::block(const ToxPk& publicKey)
{
    const bool waited = store.hasRequest(publicKey.toString());
    if (store.block(publicKey.toString())) {
        saveLater.stop();
        save();
        emit blockListChanged();
        if (waited) {
            emit requestsChanged();
        }
    }
}

void AirLocal::unblock(const QString& publicKey)
{
    if (store.unblock(publicKey)) {
        saveLater.stop();
        save();
        emit blockListChanged();
    }
}

QVector<Air::LocalStore::WaitingRequest> AirLocal::requests() const
{
    return store.requests();
}

bool AirLocal::hasRequest(const ToxPk& publicKey) const
{
    return store.hasRequest(publicKey.toString());
}

bool AirLocal::addRequest(const ToxPk& publicKey, const QString& message)
{
    if (!store.addRequest(publicKey.toString(), message, QDateTime::currentDateTime())) {
        return false;
    }
    saveRequests();
    return true;
}

void AirLocal::removeRequest(const ToxPk& publicKey)
{
    if (store.removeRequest(publicKey.toString())) {
        saveRequests();
    }
}

QVector<Air::LocalStore::WaitingInvite> AirLocal::invites() const
{
    return store.invites();
}

Air::LocalStore::WaitingInvite AirLocal::invite(const QString& id) const
{
    return store.invite(id);
}

QString AirLocal::addInvite(const ToxPk& inviter, const QString& groupName, const QByteArray& data)
{
    const QString id =
        store.addInvite(inviter.toString(), groupName, data, QDateTime::currentDateTime());
    if (!id.isEmpty()) {
        saveRequests();
    }
    return id;
}

void AirLocal::removeInvite(const QString& id)
{
    if (store.removeInvite(id)) {
        saveRequests();
    }
}

QVector<Air::LocalStore::PrivateLine> AirLocal::privateLines(const QString& groupKey,
                                                             const QString& peerKey) const
{
    return store.privateLines(groupKey, peerKey);
}

void AirLocal::addPrivateLine(const QString& groupKey, const QString& peerKey, bool ours,
                              const QString& text)
{
    Air::LocalStore::PrivateLine line;
    line.groupKey = groupKey;
    line.peerKey = peerKey;
    line.ours = ours;
    line.text = text;
    line.date = QDateTime::currentMSecsSinceEpoch();
    if (!store.addPrivateLine(line)) {
        return;
    }
    // Toxcore keeps no copy: the message is written at once, like a request.
    saveLater.stop();
    save();
    emit privateLinesChanged(Air::LocalStore::normalizedKey(groupKey),
                             Air::LocalStore::normalizedKey(peerKey));
}

void AirLocal::forgetGroup(const QString& groupKey)
{
    if (store.forgetGroup(groupKey)) {
        saveLater.stop();
        save();
    }
}

Air::EntryMarks AirLocal::entryMarks(const QString& key) const
{
    return store.entryMarks(key);
}

void AirLocal::setEntryMarks(const QString& key, const Air::EntryMarks& marks)
{
    if (store.setEntryMarks(key, marks)) {
        saveLater.start();
    }
}

QByteArray AirLocal::wireId(const QString& key) const
{
    return store.wireId(key);
}

void AirLocal::setWireId(const QString& key, const QByteArray& wireId)
{
    if (store.setWireId(key, wireId)) {
        saveLater.start();
    }
}

void AirLocal::forgetEntries()
{
    if (store.forgetEntries()) {
        saveLater.stop();
        save();
    }
}

#ifdef KHANDAQ_SCREENSHOT_HOOK
void AirLocal::markOnlineForShots(const ToxPk& publicKey, const QDateTime& at)
{
    if (store.markOnline(publicKey.toString(), at)) {
        noteChange(publicKey);
    }
}
#endif

void AirLocal::onStatus(Friend& frnd)
{
    const ToxPk& publicKey = frnd.getPublicKey();
    const QString key = publicKey.toString();
    const QDateTime now = QDateTime::currentDateTime();
    bool changed = false;
    if (Status::isOnline(frnd.getStatus())) {
        // The time this device saw them come online is kept in memory only.
        changed = store.noteConnected(key, now);
        if (store.markOnline(key, now)) {
            changed = true;
            saveLater.start();
        }
    } else {
        changed = store.noteDisconnected(key);
    }
    if (changed) {
        emit this->changed(publicKey);
    }
}

void AirLocal::markOnlineFriends()
{
    const QDateTime now = QDateTime::currentDateTime();
    if (store.pruneHours(now)) {
        saveLater.start();
    }
    for (const QPointer<Friend>& frnd : friends) {
        if (frnd && Status::isOnline(frnd->getStatus())
            && store.markOnline(frnd->getPublicKey().toString(), now)) {
            saveLater.start();
            emit changed(frnd->getPublicKey());
        }
    }
}

void AirLocal::noteChange(const ToxPk& publicKey)
{
    saveLater.start();
    emit changed(publicKey);
}

void AirLocal::saveRequests()
{
    saveLater.stop();
    save();
    emit requestsChanged();
}

void AirLocal::save()
{
    settings.setAirLocalStore(store.save());
    settings.savePersonal();
}
