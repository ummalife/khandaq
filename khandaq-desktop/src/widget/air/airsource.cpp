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

#include "airsource.h"

#include "src/core/core.h"
#include "src/core/groupid.h"
#include "src/core/networkdiagnostics.h"
#include "src/core/toxpk.h"
#include "src/friendlist.h"
#include "src/grouplist.h"
#include "src/model/chatlogitem.h"
#include "src/model/friend.h"
#include "src/model/group.h"
#include "src/model/ichatlog.h"
#include "src/persistence/profile.h"
#include "src/persistence/settings.h"
#include "src/widget/air/airlocal.h"
#include "src/widget/air/airstrings.h"

#include <QLocale>
#include <QNetworkInterface>
#include <QTimer>

#include <algorithm>

using AirStrings::Text;

namespace {

/** ChatHistory preloads this many messages per conversation; reading further would hit the DB. */
constexpr size_t loadedWindow = 100;
constexpr int searchLimit = 50;
/** Our own link counts as settling for a minute after it comes up (as on iOS). */
constexpr qint64 settlingMs = 60 * 1000;
/** Asking the system about interfaces is not free; the answer is kept this long. */
constexpr qint64 reachabilityMs = 5 * 1000;

QString flat(QString text)
{
    text.replace(QLatin1Char('\n'), QLatin1Char(' '));
    text.replace(QLatin1Char('\r'), QLatin1Char(' '));
    return text.simplified();
}

Air::Delivery messageDelivery(MessageState state, bool group)
{
    switch (state) {
    case MessageState::pending:
        return Air::Delivery::Transit;
    case MessageState::broken:
        return Air::Delivery::Failed;
    case MessageState::complete:
        // A friend confirms each message; a group has nobody to confirm it, so it is only sent.
        return group ? Air::Delivery::Sent : Air::Delivery::Delivered;
    }
    return Air::Delivery::None;
}

Air::Delivery fileDelivery(ToxFile::FileStatus status)
{
    switch (status) {
    case ToxFile::FINISHED:
        return Air::Delivery::Delivered;
    case ToxFile::CANCELED:
    case ToxFile::BROKEN:
        return Air::Delivery::None;
    case ToxFile::INITIALIZING:
    case ToxFile::PAUSED:
    case ToxFile::TRANSMITTING:
        return Air::Delivery::Sent;
    }
    return Air::Delivery::None;
}

} // namespace

AirSource::AirSource(Core& core_, Profile& profile_, Settings& settings_, FriendList& friendList_,
                     GroupList& groupList_, QObject* parent)
    : QObject(parent)
    , core{core_}
    , profile{profile_}
    , settings{settings_}
    , friendList{friendList_}
    , groupList{groupList_}
{
    connect(&core, &Core::friendLinkChanged, this, &AirSource::onFriendLink);
    connect(&core, &Core::friendLastSeenChanged, this, &AirSource::onFriendLastSeen);
    connect(&core, &Core::selfLinkChanged, this, &AirSource::onSelfLink);
    connect(&core, &Core::relaysChanged, this, &AirSource::onRelays);
    // Toxcore says nothing when the computer loses or gets back its network while the node is not
    // linked; the line would keep the old words until the node linked.
    auto* const reachabilityTimer = new QTimer(this);
    reachabilityTimer->setInterval(static_cast<int>(reachabilityMs));
    connect(reachabilityTimer, &QTimer::timeout, this, &AirSource::watchReachability);
    reachabilityTimer->start();
    // The avatar is saved right after these signals; read it back once that has happened.
    const auto later = [this](const ToxPk& publicKey) {
        const ToxPk key = publicKey;
        QTimer::singleShot(0, this, [this, key] { reloadAvatar(key); });
    };
    connect(&profile, &Profile::friendAvatarChanged, this, later);
    connect(&profile, &Profile::friendAvatarRemoved, this, later);
    connect(&profile, &Profile::selfAvatarChanged, this,
            [this] { QTimer::singleShot(0, this, [this] { reloadSelfAvatar(); }); });
    reachabilityAge.invalidate();
}

QString AirSource::friendEntryId(const ToxPk& publicKey)
{
    return QStringLiteral("f:") + publicKey.toString();
}

QString AirSource::groupEntryId(const GroupId& groupId)
{
    return QStringLiteral("c:") + groupId.toString();
}

QString AirSource::savedEntryId()
{
    return QStringLiteral("saved");
}

void AirSource::watchNotes(const IChatLog& notesLog)
{
    notes = &notesLog;
    connect(&notesLog, &IChatLog::itemUpdated, this, &AirSource::changed);
    emit changed();
}

Air::Reach AirSource::reachOf(const Friend& frnd) const
{
    return Air::Reach::friendReach(friendLinks.value(frnd.getId(), Air::toxConnectionNone),
                                   lastSeen.value(frnd.getId(), 0),
                                   QDateTime::currentSecsSinceEpoch(), selfLinkSettling());
}

void AirSource::watchFriend(Friend& frnd, std::shared_ptr<IChatLog> chatLog)
{
    const QString id = friendEntryId(frnd.getPublicKey());
    Watched entry;
    entry.chat = &frnd;
    entry.log = std::move(chatLog);
    entry.friendNumber = frnd.getId();
    connect(&frnd, &Chat::displayedNameChanged, this, &AirSource::changed);
    connect(&frnd, &Friend::statusChanged, this, &AirSource::changed);
    connect(&frnd, &QObject::destroyed, this, [this, id] { forget(id); });
    if (entry.log) {
        connect(entry.log.get(), &IChatLog::itemUpdated, this, &AirSource::changed);
    }
    watched.insert(id, entry);
    reloadAvatar(frnd.getPublicKey());
    emit changed();
}

void AirSource::watchGroup(Group& group, std::shared_ptr<IChatLog> chatLog)
{
    const QString id = groupEntryId(group.getPersistentId());
    Watched entry;
    entry.chat = &group;
    entry.log = std::move(chatLog);
    entry.group = true;
    connect(&group, &Chat::displayedNameChanged, this, &AirSource::changed);
    connect(&group, &Group::numPeersChanged, this, &AirSource::changed);
    connect(&group, &Group::titleChanged, this, &AirSource::changed);
    connect(&group, &QObject::destroyed, this, [this, id] { forget(id); });
    if (entry.log) {
        connect(entry.log.get(), &IChatLog::itemUpdated, this, &AirSource::changed);
    }
    watched.insert(id, entry);
    emit changed();
}

void AirSource::forget(const QString& entryId)
{
    const auto it = watched.find(entryId);
    if (it == watched.end()) {
        return;
    }
    if (it->log) {
        disconnect(it->log.get(), nullptr, this, nullptr);
    }
    if (!it->group) {
        // Friend numbers are reused by toxcore, so nothing about this one may stick to the next.
        friendLinks.remove(it->friendNumber);
        lastSeen.remove(it->friendNumber);
        const QString key = entryId.mid(2);
        typing.remove(key);
        if (avatars.remove(key) > 0) {
            emit avatarChanged(key);
        }
    }
    unread.remove(entryId);
    watched.erase(it);
    emit changed();
}

QVector<Air::Entry> AirSource::entries()
{
    const qint64 nowSeconds = QDateTime::currentSecsSinceEpoch();
    const bool settling = selfLinkSettling();
    const QStringList favoriteList = settings.getFavoriteChats();
    // A loop rather than the range constructor, which Qt 5.12 lacks.
    QSet<QString> favorites;
    favorites.reserve(favoriteList.size());
    for (const QString& chatId : favoriteList) {
        favorites.insert(chatId);
    }

    QVector<Air::Entry> result;
    for (Friend* frnd : friendList.getAllFriends()) {
        const auto it = watched.constFind(friendEntryId(frnd->getPublicKey()));
        const IChatLog* log = it != watched.constEnd() ? it->log.get() : nullptr;
        result.append(friendEntry(*frnd, log, nowSeconds, settling, favorites));
    }
    for (Group* group : groupList.getAllGroups()) {
        const auto it = watched.constFind(groupEntryId(group->getPersistentId()));
        const IChatLog* log = it != watched.constEnd() ? it->log.get() : nullptr;
        result.append(groupEntry(*group, log, favorites));
    }
    result.append(savedEntry(favorites));
    return result;
}

Air::Entry AirSource::friendEntry(Friend& frnd, const IChatLog* log, qint64 nowSeconds,
                                  bool settling, const QSet<QString>& favorites)
{
    const ToxPk& publicKey = frnd.getPublicKey();
    const QString key = publicKey.toString();

    Air::Entry entry;
    entry.id = friendEntryId(publicKey);
    entry.title = frnd.getDisplayedName().trimmed();
    if (entry.title.isEmpty()) {
        entry.title = key;
    }
    entry.tone = Air::toneIndex(key);
    entry.reach = Air::Reach::friendReach(friendLinks.value(frnd.getId(), Air::toxConnectionNone),
                                          lastSeen.value(frnd.getId(), 0), nowSeconds, settling);
    entry.isFavorite = favorites.contains(entry.id);
    entry.photoKey = avatars.contains(key) ? key : QString();

    const Conversation conversation = log ? read(*log, false) : Conversation{};
    qint64 activity = conversation.lastTime.isValid() ? conversation.lastTime.toMSecsSinceEpoch() : 0;
    const QDateTime chatted = settings.getFriendActivity(publicKey);
    if (chatted.isValid()) {
        activity = std::max(activity, chatted.toMSecsSinceEpoch());
    }
    entry.activity = std::max<qint64>(0, activity);
    // Undelivered messages wait in transit only while the person is away; while they are online
    // the same messages are leaving or sent, as the conversation shows them.
    entry.transitCount = entry.reach.messagesInTransit(conversation.undelivered);

    const bool pending = entry.reach.kind == Air::Reach::Kind::Pending;
    if (!conversation.hasItem) {
        // Nothing said yet: a contact row, or a conversation whose history is not kept.
        entry.kind = entry.activity > 0 ? Air::EntryKind::Direct : Air::EntryKind::Contact;
        entry.preview = AirStrings::text(pending ? Text::HintRequestSent : Text::HintNoMessages);
        entry.previewStyle = Air::PreviewStyle::Hint;
        entry.timeText =
            entry.activity > 0 ? timeText(QDateTime::fromMSecsSinceEpoch(entry.activity)) : QString();
    } else {
        entry.kind = Air::EntryKind::Direct;
        entry.preview = conversation.preview;
        entry.previewStyle = Air::PreviewStyle::Message;
        entry.lastDelivery = conversation.lastPending
                                 ? Air::outgoingTextDelivery(false, true, entry.reach.isLive())
                                 : conversation.delivery;
        entry.timeText = timeText(conversation.lastTime);
        if (entry.preview.isEmpty() && pending) {
            entry.preview = AirStrings::text(Text::HintRequestSent);
            entry.previewStyle = Air::PreviewStyle::Hint;
        }
    }
    if (typing.contains(key)) {
        entry.preview = AirStrings::text(Text::Typing);
        entry.previewStyle = Air::PreviewStyle::Typing;
        entry.lastDelivery = Air::Delivery::None;
    }
    entry.unreadCount = unreadFor(entry.id, frnd.getEventFlag());
    return entry;
}

Air::Entry AirSource::groupEntry(Group& group, const IChatLog* log, const QSet<QString>& favorites)
{
    const QString key = group.getPersistentId().toString();

    Air::Entry entry;
    entry.id = groupEntryId(group.getPersistentId());
    entry.kind = Air::EntryKind::Group;
    entry.title = group.getDisplayedName().trimmed();
    if (entry.title.isEmpty()) {
        entry.title = key.left(8);
    }
    entry.tone = Air::toneIndex(key);
    // The peer list counts us too.
    entry.reach = Air::Reach::group(std::max(0, group.getPeersCount() - 1));
    entry.isFavorite = favorites.contains(entry.id);

    const Conversation conversation = log ? read(*log, true) : Conversation{};
    entry.transitCount = conversation.undelivered;
    if (conversation.hasItem) {
        entry.activity = std::max<qint64>(0, conversation.lastTime.toMSecsSinceEpoch());
        entry.preview = conversation.preview;
        entry.previewStyle = Air::PreviewStyle::Message;
        entry.lastDelivery = conversation.delivery;
        entry.timeText = timeText(conversation.lastTime);
    } else {
        entry.preview = AirStrings::text(Text::HintNoMessages);
        entry.previewStyle = Air::PreviewStyle::Hint;
    }
    entry.mentioned = group.getMentionedFlag();
    entry.unreadCount = unreadFor(entry.id, group.getEventFlag());
    return entry;
}

Air::Entry AirSource::savedEntry(const QSet<QString>& favorites) const
{
    Air::Entry entry;
    entry.id = savedEntryId();
    entry.kind = Air::EntryKind::Saved;
    entry.title = AirStrings::text(Text::SavedTitle);
    entry.reach = Air::Reach::local();
    entry.isFavorite = favorites.contains(entry.id);

    const Conversation conversation = notes ? read(*notes, false) : Conversation{};
    if (conversation.hasItem) {
        entry.activity = std::max<qint64>(0, conversation.lastTime.toMSecsSinceEpoch());
        entry.preview = conversation.preview;
        entry.previewStyle = Air::PreviewStyle::Message;
        entry.timeText = timeText(conversation.lastTime);
    } else {
        // Nothing written yet: the row says where notes stay.
        entry.preview = AirStrings::text(Text::HintSaved);
        entry.previewStyle = Air::PreviewStyle::Hint;
    }
    // A note goes nowhere, so it has no delivery to mark.
    entry.lastDelivery = Air::Delivery::None;
    return entry;
}

int AirSource::unreadFor(const QString& entryId, bool hasEvent)
{
    // The event flag is what every way of opening a conversation clears, so it decides; the count
    // only says how many arrived since it went up.
    if (!hasEvent) {
        unread.remove(entryId);
        return 0;
    }
    return std::max(1, unread.value(entryId, 0));
}

AirSource::Conversation AirSource::read(const IChatLog& log, bool group) const
{
    Conversation result;
    const size_t first = log.getFirstIdx().get();
    const size_t next = log.getNextIdx().get();
    if (next <= first) {
        return result;
    }
    const size_t start = std::max(first, next > loadedWindow ? next - loadedWindow : size_t{0});
    const ToxPk self = core.getSelfPublicKey();
    for (size_t index = next; index-- > start;) {
        // A cancelled message or a deleted note keeps its index but is gone for the list too; a
        // failed one stays, since the row shows that it was not sent.
        if (log.isCancelled(ChatLogIdx(index))) {
            continue;
        }
        const ChatLogItem& item = log.at(ChatLogIdx(index));
        switch (item.getContentType()) {
        case ChatLogItem::ContentType::message: {
            const ChatLogMessage& message = item.getContentAsMessage();
            const bool outgoing = item.getSender() == self;
            const bool pending = outgoing && message.state == MessageState::pending;
            if (pending) {
                ++result.undelivered;
            }
            if (!result.hasItem) {
                result.hasItem = true;
                result.lastTime = message.message.timestamp;
                result.preview = flat(AirStrings::messageWords(message.message.content));
                result.delivery =
                    outgoing ? messageDelivery(message.state, group) : Air::Delivery::None;
                result.lastPending = pending;
            }
            break;
        }
        case ChatLogItem::ContentType::fileTransfer: {
            if (!result.hasItem) {
                const ChatLogFile& file = item.getContentAsFile();
                const bool outgoing = file.file.direction == ToxFile::SENDING;
                result.hasItem = true;
                result.lastTime = file.timestamp;
                result.preview = AirStrings::text(Text::FileMessage).arg(flat(file.file.fileName));
                result.delivery = outgoing ? fileDelivery(file.file.status) : Air::Delivery::None;
            }
            break;
        }
        case ChatLogItem::ContentType::systemMessage:
            break;
        }
    }
    return result;
}

void AirSource::setLocal(AirLocal* local_)
{
    if (local) {
        disconnect(local, nullptr, this, nullptr);
    }
    local = local_;
    if (local) {
        connect(local, &AirLocal::requestsChanged, this, &AirSource::changed);
    }
    emit changed();
}

QVector<Air::Request> AirSource::requests() const
{
    QVector<Air::Request> cards;
    if (!local) {
        return cards;
    }
    for (const Air::LocalStore::WaitingRequest& waiting : local->requests()) {
        if (hiddenRequests.contains(waiting.publicKey)) {
            continue;
        }
        cards.append(Air::Request::make(waiting.publicKey, waiting.message,
                                        timeText(QDateTime::fromMSecsSinceEpoch(waiting.date)),
                                        waiting.date));
    }
    for (const Air::LocalStore::WaitingInvite& invite : local->invites()) {
        if (hiddenRequests.contains(Air::Request::invitationId(invite.id))) {
            continue;
        }
        // Join goes through the inviter, so the card says whether they are there to take it.
        const Friend* const inviter = friendList.findFriend(ToxPk(invite.inviterKey));
        const QString name =
            inviter ? inviter->getDisplayedName() : Air::groupedKey(invite.inviterKey);
        const bool online = inviter && reachOf(*inviter).isLive();
        cards.append(Air::Request::invitation(invite.id, invite.inviterKey, invite.groupName, name,
                                              online,
                                              timeText(QDateTime::fromMSecsSinceEpoch(invite.date)),
                                              invite.date));
    }
    return cards;
}

void AirSource::setRequestHidden(const QString& requestId, bool hidden)
{
    const bool changedNow =
        hidden ? !hiddenRequests.contains(requestId) : hiddenRequests.contains(requestId);
    if (!changedNow) {
        return;
    }
    if (hidden) {
        hiddenRequests.insert(requestId);
    } else {
        hiddenRequests.remove(requestId);
    }
    emit changed();
}

QVector<Air::MessageHit> AirSource::search(const QString& query) const
{
    struct Found
    {
        qint64 when = 0;
        Air::MessageHit hit;
    };

    const QString trimmed = query.trimmed();
    if (trimmed.isEmpty()) {
        return {};
    }
    QVector<Found> found;
    for (auto it = watched.constBegin(); it != watched.constEnd(); ++it) {
        // One read of the guarded pointer: GCC cannot tell that a second one finds it still there.
        const Chat* const chat = it->chat.data();
        if (!chat || !it->log) {
            continue;
        }
        const IChatLog& log = *it->log;
        const size_t first = log.getFirstIdx().get();
        const size_t next = log.getNextIdx().get();
        if (next <= first) {
            continue;
        }
        QString title = chat->getDisplayedName().trimmed();
        if (title.isEmpty()) {
            title = it.key().mid(2);
        }
        const size_t start = std::max(first, next > loadedWindow ? next - loadedWindow : size_t{0});
        for (size_t index = next; index-- > start;) {
            // A message its author deleted keeps its index, but nobody finds it any more.
            if (log.isCancelled(ChatLogIdx(index))) {
                continue;
            }
            const ChatLogItem& item = log.at(ChatLogIdx(index));
            if (item.getContentType() != ChatLogItem::ContentType::message) {
                continue;
            }
            const Message& message = item.getContentAsMessage().message;
            const QString body = flat(Air::plainMessageBody(message.content));
            if (Air::matchRanges(body, trimmed).isEmpty()) {
                continue;
            }
            Found match;
            match.when = message.timestamp.toMSecsSinceEpoch();
            match.hit.id = it.key() + QLatin1Char('#') + QString::number(index);
            match.hit.chatId = it.key();
            match.hit.title = title;
            match.hit.snippet = body;
            match.hit.timeText = timeText(message.timestamp);
            found.append(match);
        }
    }
    std::stable_sort(found.begin(), found.end(),
                     [](const Found& a, const Found& b) { return a.when > b.when; });
    QVector<Air::MessageHit> hits;
    const int count = std::min(searchLimit, static_cast<int>(found.size()));
    hits.reserve(count);
    for (int i = 0; i < count; ++i) {
        hits.append(found[i].hit);
    }
    return hits;
}

Air::NetworkState AirSource::networkState() const
{
    return Air::networkState(selfLink, selfLink != Air::toxConnectionNone || networkReachable());
}

Air::Nodes AirSource::nodes() const
{
    Air::Nodes nodes;
    nodes.relays = relays;
    for (const Watched& entry : watched) {
        if (!entry.group && entry.chat
            && friendLinks.value(entry.friendNumber, Air::toxConnectionNone) == Air::toxConnectionUdp) {
            ++nodes.directContacts;
        }
    }
    return nodes;
}

QString AirSource::networkReport() const
{
    const Air::Nodes now = nodes();
    NetworkDiagnostics::Snapshot snapshot;
    snapshot.selfConnection = selfLink;
    snapshot.internet = networkState() != Air::NetworkState::Offline;
    snapshot.relays = now.relays;
    snapshot.directContacts = now.directContacts;
    return NetworkDiagnostics::supportReport(snapshot);
}

#ifdef KHANDAQ_SCREENSHOT_HOOK
void AirSource::forgetNetworkForWalk()
{
    walkWithoutNetwork = true;
    reachabilityAge.invalidate();
    emit networkChanged();
    emit changed();
}
#endif

bool AirSource::selfLinkSettling() const
{
    return settlingLeft() > 0;
}

int AirSource::settlingLeft() const
{
    if (selfLink == Air::toxConnectionNone || !linkedFor.isValid()) {
        return 0;
    }
    return static_cast<int>(std::max<qint64>(0, settlingMs - linkedFor.elapsed()));
}

QPixmap AirSource::avatar(const QString& photoKey) const
{
    return avatars.value(photoKey);
}

QPixmap AirSource::selfAvatar() const
{
    return ownAvatar;
}

void AirSource::noteUnread(const QString& entryId)
{
    unread[entryId] = unread.value(entryId, 0) + 1;
    emit changed();
}

void AirSource::setTyping(const ToxPk& publicKey, bool isTyping)
{
    const QString key = publicKey.toString();
    const bool changedNow = isTyping ? !typing.contains(key) : typing.contains(key);
    if (!changedNow) {
        return;
    }
    if (isTyping) {
        typing.insert(key);
    } else {
        typing.remove(key);
    }
    emit changed();
}

QString AirSource::timeText(const QDateTime& when) const
{
    if (!when.isValid()) {
        return QString();
    }
    const QString code = settings.getTranslation();
    const QLocale locale = code.isEmpty() ? QLocale::system() : QLocale(code);
    const QDateTime localTime = when.toLocalTime();
    const QDate day = localTime.date();
    const QDate today = QDate::currentDate();
    // Today the time, within the week the day, before that the date: as short as stays clear.
    if (day == today) {
        return QLocale::c().toString(localTime.time(), QStringLiteral("H:mm"));
    }
    const qint64 daysAgo = day.daysTo(today);
    if (daysAgo > 0 && daysAgo < 7) {
        return locale.dayName(day.dayOfWeek(), QLocale::ShortFormat);
    }
    return AirStrings::latinDigits(locale.toString(day, QLocale::ShortFormat), locale);
}

bool AirSource::networkReachable() const
{
    if (reachabilityAge.isValid() && reachabilityAge.elapsed() < reachabilityMs) {
        return reachable;
    }
    reachabilityAge.start();
    reachable = false;
#ifdef KHANDAQ_SCREENSHOT_HOOK
    if (walkWithoutNetwork) {
        return reachable;
    }
#endif
    // Link-local addresses come with every interface that is merely up, so they do not count.
    for (const QNetworkInterface& interface : QNetworkInterface::allInterfaces()) {
        const auto flags = interface.flags();
        if (!flags.testFlag(QNetworkInterface::IsUp) || !flags.testFlag(QNetworkInterface::IsRunning)
            || flags.testFlag(QNetworkInterface::IsLoopBack)) {
            continue;
        }
        for (const QNetworkAddressEntry& address : interface.addressEntries()) {
            if (address.ip().isGlobal()) {
                reachable = true;
                return reachable;
            }
        }
    }
    return reachable;
}

void AirSource::reloadAvatar(const ToxPk& publicKey)
{
    const QString key = publicKey.toString();
    QPixmap photo;
    const QByteArray data = profile.loadAvatarData(publicKey);
    if (!data.isEmpty()) {
        photo.loadFromData(data);
    }
    const bool had = avatars.contains(key);
    if (photo.isNull()) {
        avatars.remove(key);
    } else {
        avatars.insert(key, photo);
    }
    if (had || !photo.isNull()) {
        emit avatarChanged(key);
        emit changed();
    }
}

void AirSource::reloadSelfAvatar()
{
    QPixmap photo;
    const QByteArray data = profile.loadAvatarData(core.getSelfPublicKey());
    if (!data.isEmpty()) {
        photo.loadFromData(data);
    }
    ownAvatar = photo;
    emit selfAvatarChanged();
}

void AirSource::onFriendLink(uint32_t friendNumber, int toxConnection)
{
    const int before = friendLinks.value(friendNumber, Air::toxConnectionNone);
    if (before == toxConnection) {
        return;
    }
    friendLinks.insert(friendNumber, toxConnection);
    // A contact reached directly is one of the nodes the line counts.
    if ((before == Air::toxConnectionUdp) != (toxConnection == Air::toxConnectionUdp)) {
        emit networkChanged();
    }
    emit changed();
}

void AirSource::onFriendLastSeen(uint32_t friendNumber, const QDateTime& when)
{
    lastSeen.insert(friendNumber, when.isValid() ? when.toSecsSinceEpoch() : 0);
    emit changed();
}

void AirSource::onSelfLink(int toxConnection)
{
    if (selfLink == Air::toxConnectionNone && toxConnection != Air::toxConnectionNone) {
        linkedFor.start();
        // What says "connecting" until then (rows, the chat header) changes back when it ends.
        QTimer::singleShot(static_cast<int>(settlingMs) + 1, this, [this] {
            if (!selfLinkSettling()) {
                emit changed();
            }
        });
    } else if (toxConnection == Air::toxConnectionNone) {
        linkedFor.invalidate();
    }
    const bool moved = selfLink != toxConnection;
    selfLink = toxConnection;
    if (moved) {
        emit networkChanged();
    }
    emit changed();
}

void AirSource::onRelays(int count)
{
    if (relays == count) {
        return;
    }
    relays = count;
    emit networkChanged();
    emit changed();
}

void AirSource::watchReachability()
{
    if (selfLink != Air::toxConnectionNone) {
        return;
    }
    const bool before = reachable;
    reachabilityAge.invalidate();
    if (networkReachable() != before) {
        emit networkChanged();
        emit changed();
    }
}
