/*
    Copyright © 2013 by Maxim Biro <nurupo.contributions@gmail.com>
    Copyright © 2014-2019 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "core.h"
#include "coreav.h"
#include "corefile.h"

#include "src/core/coreext.h"
#include "src/core/dhtserver.h"
#include "src/core/icoresettings.h"
#include "src/core/toxlogger.h"
#include "src/core/networkdiagnostics.h"
#include "src/core/reconnectbackoff.h"
#include "src/core/toxoptions.h"
#include "src/core/toxstring.h"
#include "src/core/khandaqlimits.h"
#include "src/model/groupinvite.h"
#include "src/model/status.h"
#include "src/model/ibootstraplistgenerator.h"
#include "src/persistence/profile.h"
#include "util/strongtype.h"
#include "util/compatiblerecursivemutex.h"
#include "util/toxcoreerrorparser.h"

#include <QCoreApplication>
#include <QDateTime>
#include <array>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QHash>
#include <QVector>
#include <QList>
#include <QNetworkConfigurationManager>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QSet>
#include <QString>
#include <QStringBuilder>
#include <QStandardPaths>

#include <tox/tox.h>

#include <algorithm>
#include <cassert>
#include <chrono>
#include <memory>
#include <random>
#include <unordered_map>
#include <unordered_set>
#include <vector>

const QString Core::TOX_EXT = ".tox";

#define ASSERT_CORE_THREAD assert(QThread::currentThread() == coreThread.get())

namespace {

QList<DhtServer> shuffleBootstrapNodes(QList<DhtServer> bootstrapNodes)
{
    std::mt19937 rng(std::chrono::high_resolution_clock::now().time_since_epoch().count());
    std::shuffle(bootstrapNodes.begin(), bootstrapNodes.end(), rng);
    return bootstrapNodes;
}

QByteArray parseGroupChatIdHex(const QString& hexInput)
{
    QString hex = hexInput;
    hex.remove(QRegularExpression(QStringLiteral(R"(\s)")));
    hex.remove(QRegularExpression(QStringLiteral(R"([^a-fA-F0-9])")));
    if (hex.size() != TOX_GROUP_CHAT_ID_SIZE * 2) {
        return {};
    }
    const QByteArray bytes = QByteArray::fromHex(hex.toLatin1());
    if (bytes.size() != TOX_GROUP_CHAT_ID_SIZE) {
        return {};
    }
    return bytes;
}

} // namespace

Core::Core(QThread* coreThread_, IBootstrapListGenerator& bootstrapListGenerator_, const ICoreSettings& settings_)
    : tox(nullptr)
    , toxTimer{new QTimer{this}}
    , coreThread(coreThread_)
    , bootstrapListGenerator(bootstrapListGenerator_)
    , settings(settings_)
{
    assert(toxTimer);
    // need to migrate Settings and History if this changes
    assert(ToxPk::size == tox_public_key_size());
    assert(GroupId::size == TOX_GROUP_CHAT_ID_SIZE);
    assert(ToxId::size == tox_address_size());
    toxTimer->setSingleShot(true);
    connect(toxTimer, &QTimer::timeout, this, &Core::process);
    connect(coreThread_, &QThread::finished, toxTimer, &QTimer::stop);
}

Core::~Core()
{
    /*
     * First stop the thread to stop the timer and avoid Core emitting callbacks
     * into an already destructed CoreAV.
     */
    coreThread->exit(0);
    coreThread->wait();

    tox.reset();
}

/**
 * @brief Registers all toxcore callbacks
 * @param tox Tox instance to register the callbacks on
 */
void Core::registerCallbacks(Tox* tox)
{
    tox_callback_friend_request(tox, onFriendRequest);
    tox_callback_friend_message(tox, onFriendMessage);
    tox_callback_friend_name(tox, onFriendNameChange);
    tox_callback_friend_typing(tox, onFriendTypingChange);
    tox_callback_friend_status_message(tox, onStatusMessageChanged);
    tox_callback_friend_status(tox, onUserStatusChanged);
    tox_callback_friend_connection_status(tox, onConnectionStatusChanged);
    tox_callback_friend_read_receipt(tox, onReadReceiptCallback);
    tox_callback_group_invite(tox, onGroupInvite);
    tox_callback_group_message(tox, onGroupMessage);
    tox_callback_group_peer_join(tox, onGroupPeerJoin);
    tox_callback_group_peer_exit(tox, onGroupPeerExit);
    tox_callback_group_peer_name(tox, onGroupPeerNameChange);
    tox_callback_group_topic(tox, onGroupTopicChange);
    tox_callback_group_self_join(tox, onGroupSelfJoin);
    tox_callback_group_join_fail(tox, onGroupJoinFail);
    tox_callback_group_custom_packet(tox, onGroupCustomPacket);
    tox_callback_group_custom_private_packet(tox, onGroupCustomPrivatePacket);
    tox_callback_friend_lossless_packet(tox, onLosslessPacket);
}

/**
 * @brief Factory method for the Core object
 * @param savedata empty if new profile or saved data else
 * @param settings Settings specific to Core
 * @return nullptr or a Core object ready to start
 */
ToxCorePtr Core::makeToxCore(const QByteArray& savedata, const ICoreSettings& settings,
                             IBootstrapListGenerator& bootstrapNodes, ToxCoreErrors* err)
{
    QThread* thread = new QThread();
    if (thread == nullptr) {
        qCritical() << "Could not allocate Core thread";
        return {};
    }
    thread->setObjectName("qTox Core");

    auto toxOptions = ToxOptions::makeToxOptions(savedata, settings);
    if (toxOptions == nullptr) {
        qCritical() << "Could not allocate ToxOptions data structure";
        if (err) {
            *err = ToxCoreErrors::ERROR_ALLOC;
        }
        return {};
    }

    ToxCorePtr core(new Core(thread, bootstrapNodes, settings));
    if (core == nullptr) {
        if (err) {
            *err = ToxCoreErrors::ERROR_ALLOC;
        }
        return {};
    }

    Tox_Err_New tox_err;
    core->tox = ToxPtr(tox_new(*toxOptions, &tox_err));

    switch (tox_err) {
    case TOX_ERR_NEW_OK:
        break;

    case TOX_ERR_NEW_LOAD_BAD_FORMAT:
        qCritical() << "Failed to parse Tox save data";
        if (err) {
            *err = ToxCoreErrors::BAD_PROXY;
        }
        return {};

    case TOX_ERR_NEW_PORT_ALLOC:
        if (toxOptions->getIPv6Enabled()) {
            toxOptions->setIPv6Enabled(false);
            core->tox = ToxPtr(tox_new(*toxOptions, &tox_err));
            if (tox_err == TOX_ERR_NEW_OK) {
                qWarning() << "Core failed to start with IPv6, falling back to IPv4. LAN discovery "
                              "may not work properly.";
                break;
            }
        }

        qCritical() << "Can't to bind the port";
        if (err) {
            *err = ToxCoreErrors::FAILED_TO_START;
        }
        return {};

    case TOX_ERR_NEW_PROXY_BAD_HOST:
    case TOX_ERR_NEW_PROXY_BAD_PORT:
    case TOX_ERR_NEW_PROXY_BAD_TYPE:
        qCritical() << "Bad proxy, error code:" << tox_err;
        if (err) {
            *err = ToxCoreErrors::BAD_PROXY;
        }
        return {};

    case TOX_ERR_NEW_PROXY_NOT_FOUND:
        qCritical() << "Proxy not found";
        if (err) {
            *err = ToxCoreErrors::BAD_PROXY;
        }
        return {};

    case TOX_ERR_NEW_LOAD_ENCRYPTED:
        qCritical() << "Attempted to load encrypted Tox save data";
        if (err) {
            *err = ToxCoreErrors::INVALID_SAVE;
        }
        return {};

    case TOX_ERR_NEW_MALLOC:
        qCritical() << "Memory allocation failed";
        if (err) {
            *err = ToxCoreErrors::ERROR_ALLOC;
        }
        return {};

    case TOX_ERR_NEW_NULL:
        qCritical() << "A parameter was null";
        if (err) {
            *err = ToxCoreErrors::FAILED_TO_START;
        }
        return {};

    default:
        qCritical() << "Toxcore failed to start, unknown error code:" << tox_err;
        if (err) {
            *err = ToxCoreErrors::FAILED_TO_START;
        }
        return {};
    }

    // tox should be valid by now
    assert(core->tox != nullptr);

    // create CoreFile
    core->file = CoreFile::makeCoreFile(core.get(), core->tox.get(), core->coreLoopLock);
    if (!core->file) {
        qCritical() << "CoreFile failed to start";
        if (err) {
            *err = ToxCoreErrors::FAILED_TO_START;
        }
        return {};
    }

    core->ext = CoreExt::makeCoreExt(core->tox.get());
    connect(core.get(), &Core::friendStatusChanged, core->ext.get(), &CoreExt::onFriendStatusChanged);

    registerCallbacks(core->tox.get());

    // connect the thread with the Core
    connect(thread, &QThread::started, core.get(), &Core::onStarted);
    core->moveToThread(thread);

    // when leaving this function 'core' should be ready for it's start() action or
    // a nullptr
    return core;
}

void Core::onStarted()
{
    ASSERT_CORE_THREAD;

    // One time initialization stuff
    QString name = getUsername();
    if (!name.isEmpty()) {
        emit usernameSet(name);
    }

    QString msg = getStatusMessage();
    if (!msg.isEmpty()) {
        emit statusMessageSet(msg);
    }

    ToxId id = getSelfId();
    // Id comes from toxcore, must be valid
    assert(id.isValid());
    emit idSet(id);

    loadFriends();
    loadGroups();

    networkManager = new QNetworkConfigurationManager();
    connect(networkManager, &QNetworkConfigurationManager::onlineStateChanged, this, &Core::onNetworkChanged);
#if QT_VERSION >= QT_VERSION_CHECK(5, 2, 0)
    connect(networkManager, &QNetworkConfigurationManager::configurationChanged, this, &Core::onNetworkChanged);
#endif

    performKhandaqBootstrapBurst();

    process(); // starts its own timer
}

/**
 * @brief Starts toxcore and it's event loop, can be called from any thread
 */
void Core::start()
{
    coreThread->start();
}

const CoreAV* Core::getAv() const
{
    return av;
}

CoreAV* Core::getAv()
{
    return av;
}

void Core::setAv(CoreAV *coreAv)
{
    av = coreAv;
}

CoreFile* Core::getCoreFile() const
{
    return file.get();
}

Tox* Core::getTox() const
{
    return tox.get();
}

CompatibleRecursiveMutex &Core::getCoreLoopLock() const
{
    return coreLoopLock;
}

const CoreExt* Core::getExt() const
{
    return ext.get();
}

CoreExt* Core::getExt()
{
    return ext.get();
}

/**
 * @brief Processes toxcore events and ensure we stay connected, called by its own timer
 */
void Core::process()
{
    QMutexLocker ml{&coreLoopLock};

    ASSERT_CORE_THREAD;

    tox_iterate(tox.get(), this);
    ext->process();
    maintainGroups();

#ifdef DEBUG
    // we want to see the debug messages immediately
    fflush(stdout);
#endif

    // TODO(sudden6): recheck if this is still necessary
    if (checkConnection()) {
        ReconnectBackoff::instance().reset();
        tolerance = CORE_DISCONNECT_TOLERANCE;
    } else if (!(--tolerance)) {
        ReconnectBackoff::instance().noteAttempt(QStringLiteral("offline_periodic"), false);
        NetworkDiagnostics::logEvent(QStringLiteral("bootstrap_dht"),
                                     QStringLiteral("attempt=%1").arg(ReconnectBackoff::instance().currentAttempt()));
        bootstrapDht();
        tolerance = 3 * CORE_DISCONNECT_TOLERANCE;
    }

    unsigned sleeptime =
        qMin(tox_iteration_interval(tox.get()), getCoreFile()->corefileIterationInterval());
    toxTimer->start(sleeptime);
}

bool Core::checkConnection()
{
    ASSERT_CORE_THREAD;
    auto selfConnection = tox_self_get_connection_status(tox.get());
    QString connectionName;
    bool toxConnected = false;
    switch (selfConnection)
    {
        case TOX_CONNECTION_NONE:
            toxConnected = false;
            break;
        case TOX_CONNECTION_TCP:
            toxConnected = true;
            connectionName = "a TCP relay";
            break;
        case TOX_CONNECTION_UDP:
            toxConnected = true;
            connectionName = "the UDP DHT";
            break;
        qWarning() << "tox_self_get_connection_status returned unknown enum!";
    }

    if (toxConnected && !isConnected) {
        qDebug().noquote() << "Connected to" << connectionName;
        emit connected();
    } else if (!toxConnected && isConnected) {
        qDebug() << "Disconnected from the DHT";
        emit disconnected();
    }

    isConnected = toxConnected;
    return toxConnected;
}

void Core::performKhandaqBootstrapBurst()
{
    ASSERT_CORE_THREAD;

    struct KhandaqNode
    {
        const char* host;
        const char* publicKeyHex;
    };

    static const KhandaqNode khandaqNodes[] = {
        {"bootstrap1.khandaq.org", "74AE9E62A2AE51983CF9C6B526CD89ABD8AA91864B35FC0CF7AC60454CBDDD6D"},
        {"bootstrap2.khandaq.org", "5C6F3903FB1EC4AC386843D8FB584CC34567E045EC26939A6034C3A2746A9B6B"},
        {"bootstrap3.khandaq.org", "A181DD1F8C9A9D41BE1875A5C2687A89C3CB4F0F76ED9C390E7270B01BF24665"},
    };
    static const uint16_t tcpPorts[] = {33445, 3389};

    for (const auto& node : khandaqNodes) {
        ToxPk pk{QString::fromLatin1(node.publicKeyHex)};
        const uint8_t* pkPtr = pk.getData();

        tox_bootstrap(tox.get(), node.host, 33445, pkPtr, nullptr);
        for (const auto tcpPort : tcpPorts) {
            tox_add_tcp_relay(tox.get(), node.host, tcpPort, pkPtr, nullptr);
        }
    }
}

void Core::onNetworkChanged()
{
    ASSERT_CORE_THREAD;

    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (now - lastNetworkRebootstrapMs < 500) {
        return;
    }
    lastNetworkRebootstrapMs = now;

    ReconnectBackoff::instance().noteAttempt(QStringLiteral("network_change"), true);
    qInfo() << "Core: network changed, urgent rebootstrap";
    NetworkDiagnostics::logEvent(QStringLiteral("network_changed"), QStringLiteral("urgent rebootstrap"));
    tolerance = 0;
    performKhandaqBootstrapBurst();
    bootstrapDht();
}

/**
 * @brief Connects us to the Tox network
 */
void Core::bootstrapDht()
{
    ASSERT_CORE_THREAD;

    performKhandaqBootstrapBurst();

    auto const shuffledBootstrapNodes = shuffleBootstrapNodes(bootstrapListGenerator.getBootstrapNodes());
    if (shuffledBootstrapNodes.empty()) {
        qWarning() << "No bootstrap node list";
        return;
    }

    auto numNewNodes = 6;
    for (int i = 0; i < numNewNodes && i < shuffledBootstrapNodes.size(); ++i) {
        const auto& dhtServer = shuffledBootstrapNodes.at(i);
        QByteArray address;
        if (!dhtServer.ipv4.isEmpty()) {
            address = dhtServer.ipv4.toLatin1();
        } else if (!dhtServer.ipv6.isEmpty() && settings.getEnableIPv6()) {
            address = dhtServer.ipv6.toLatin1();
        } else {
            ++numNewNodes;
            continue;
        }

        ToxPk pk{dhtServer.publicKey};
        qDebug() << "Connecting to bootstrap node" << pk.toString();
        const uint8_t* pkPtr = pk.getData();

        Tox_Err_Bootstrap error;
        if (dhtServer.statusUdp) {
            tox_bootstrap(tox.get(), address.constData(), dhtServer.udpPort, pkPtr, &error);
            PARSE_ERR(error);
        }
        if (dhtServer.statusTcp) {
            const auto ports = dhtServer.tcpPorts.size();
            const auto tcpPort = dhtServer.tcpPorts[rand() % ports];
            tox_add_tcp_relay(tox.get(), address.constData(), tcpPort, pkPtr, &error);
            PARSE_ERR(error);
        }
    }
}

void Core::onFriendRequest(Tox* tox, const uint8_t* cFriendPk, const uint8_t* cMessage,
                           size_t cMessageSize, void* core)
{
    std::ignore = tox;
    std::ignore = cMessage;
    std::ignore = cMessageSize;
    Core* corePtr = static_cast<Core*>(core);
    const ToxPk friendPk(cFriendPk);
    if (corePtr->hasFriendWithPublicKey(friendPk)) {
        return;
    }
    corePtr->acceptFriendRequest(friendPk);
}

void Core::onFriendMessage(Tox* tox, uint32_t friendId, Tox_Message_Type type, const uint8_t* cMessage,
                           size_t cMessageSize, void* core)
{
    std::ignore = tox;
    bool isAction = (type == TOX_MESSAGE_TYPE_ACTION);
    QString msg = ToxString(cMessage, cMessageSize).getQString();
    emit static_cast<Core*>(core)->friendMessageReceived(friendId, msg, isAction);
}

void Core::onFriendNameChange(Tox* tox, uint32_t friendId, const uint8_t* cName, size_t cNameSize, void* core)
{
    std::ignore = tox;
    QString newName = ToxString(cName, cNameSize).getQString();
    // no saveRequest, this callback is called on every connection, not just on name change
    emit static_cast<Core*>(core)->friendUsernameChanged(friendId, newName);
}

void Core::onFriendTypingChange(Tox* tox, uint32_t friendId, bool isTyping, void* core)
{
    std::ignore = tox;
    emit static_cast<Core*>(core)->friendTypingChanged(friendId, isTyping);
}

void Core::onStatusMessageChanged(Tox* tox, uint32_t friendId, const uint8_t* cMessage,
                                  size_t cMessageSize, void* core)
{
    std::ignore = tox;
    QString message = ToxString(cMessage, cMessageSize).getQString();
    // no saveRequest, this callback is called on every connection, not just on name change
    emit static_cast<Core*>(core)->friendStatusMessageChanged(friendId, message);
}

void Core::onUserStatusChanged(Tox* tox, uint32_t friendId, Tox_User_Status userstatus, void* core)
{
    std::ignore = tox;
    Status::Status status;
    switch (userstatus) {
    case TOX_USER_STATUS_AWAY:
        status = Status::Status::Away;
        break;

    case TOX_USER_STATUS_BUSY:
        status = Status::Status::Busy;
        break;

    default:
        status = Status::Status::Online;
        break;
    }

    // no saveRequest, this callback is called on every connection, not just on name change
    emit static_cast<Core*>(core)->friendStatusChanged(friendId, status);
}

void Core::onConnectionStatusChanged(Tox* tox, uint32_t friendId, Tox_Connection status, void* vCore)
{
    std::ignore = tox;
    Core* core = static_cast<Core*>(vCore);
    Status::Status friendStatus = Status::Status::Offline;
    switch (status)
    {
        case TOX_CONNECTION_NONE:
            friendStatus = Status::Status::Offline;
            qDebug() << "Disconnected from friend" << friendId;
            break;
        case TOX_CONNECTION_TCP:
            friendStatus = Status::Status::Online;
            qDebug() << "Connected to friend" << friendId << "through a TCP relay";
            emit core->friendUsernameChanged(friendId, core->getFriendUsername(friendId));
            core->resendPendingGroupInviteRequests(friendId);
            break;
        case TOX_CONNECTION_UDP:
            friendStatus = Status::Status::Online;
            qDebug() << "Connected to friend" << friendId << "directly with UDP";
            emit core->friendUsernameChanged(friendId, core->getFriendUsername(friendId));
            core->resendPendingGroupInviteRequests(friendId);
            break;
        qWarning() << "tox_callback_friend_connection_status returned unknown enum!";
    }

    // Ignore Online because it will be emited from onUserStatusChanged
    bool isOffline = friendStatus == Status::Status::Offline;
    if (isOffline) {
        emit core->friendStatusChanged(friendId, friendStatus);
        core->checkLastOnline(friendId);
    }
}

void Core::onGroupInvite(Tox* tox, uint32_t friendId, const uint8_t* inviteData, size_t length,
                         const uint8_t* groupName, size_t groupNameLength, void* vCore)
{
    std::ignore = tox;
    Core* core = static_cast<Core*>(vCore);
    const QByteArray data(reinterpret_cast<const char*>(inviteData), static_cast<int>(length));
    const QString name = ToxString(groupName, groupNameLength).getQString();
    const GroupInvite inviteInfo(friendId, data, name);
    qDebug() << QString("NGC group invite by friend %1 (%2)").arg(friendId).arg(name);
    if (core->autoAcceptRequestedGroupInvite(inviteInfo)) {
        return; // we asked for this invite ourselves (friend-assisted join)
    }
    emit core->groupInviteReceived(inviteInfo);
}

void Core::onGroupMessage(Tox* tox, uint32_t groupId, uint32_t peerId, Tox_Message_Type type,
                          const uint8_t* cMessage, size_t length, uint32_t messageId, void* vCore)
{
    std::ignore = tox;
    std::ignore = messageId;
    Core* core = static_cast<Core*>(vCore);
    bool isAction = type == TOX_MESSAGE_TYPE_ACTION;
    QString message = ToxString(cMessage, length).getQString();
    emit core->groupMessageReceived(groupId, peerId, message, isAction);
}

void Core::onGroupPeerJoin(Tox* tox, uint32_t groupId, uint32_t peerId, void* vCore)
{
    std::ignore = tox;
    const auto core = static_cast<Core*>(vCore);
    qDebug() << QString("Group %1 peer %2 joined").arg(groupId).arg(peerId);
    emit core->groupPeerlistChanged(groupId);
    // first peers bring the synced shared state — refresh the real group name
    // (Group::setTitle ignores identical titles, so re-emitting is cheap)
    const QString groupName = core->getGroupName(static_cast<int>(groupId));
    if (!groupName.isEmpty()) {
        emit core->groupTitleChanged(static_cast<int>(groupId), QString(), groupName);
    }
}

void Core::onGroupPeerExit(Tox* tox, uint32_t groupId, uint32_t peerId, Tox_Group_Exit_Type exitType,
                           const uint8_t* name, size_t nameLength, const uint8_t* partMessage,
                           size_t partMessageLength, void* vCore)
{
    std::ignore = tox;
    std::ignore = exitType;
    std::ignore = name;
    std::ignore = nameLength;
    std::ignore = partMessage;
    std::ignore = partMessageLength;
    const auto core = static_cast<Core*>(vCore);
    qDebug() << QString("Group %1 peer %2 left").arg(groupId).arg(peerId);
    emit core->groupPeerlistChanged(groupId);
}

void Core::onGroupSelfJoin(Tox* tox, uint32_t groupId, void* vCore)
{
    std::ignore = tox;
    const auto core = static_cast<Core*>(vCore);
    qDebug() << QString("Group %1 self joined").arg(groupId);
    emit core->saveRequest();
    // Do NOT kickstart here: we just successfully joined; a reconnect would
    // tear the fresh connection down again and loop join/rejoin forever.
    emit core->groupPeerlistChanged(groupId);
    // shared state (incl. the real group name) has just been synced
    const QString groupName = core->getGroupName(static_cast<int>(groupId));
    if (!groupName.isEmpty()) {
        emit core->groupTitleChanged(static_cast<int>(groupId), QString(), groupName);
    }
}

void Core::onGroupJoinFail(Tox* tox, uint32_t groupId, Tox_Group_Join_Fail failType, void* vCore)
{
    std::ignore = tox;
    const auto core = static_cast<Core*>(vCore);
    qWarning() << QString("Group %1 join failed: %2").arg(groupId).arg(static_cast<int>(failType));
    core->kickstartGroupConnection(groupId);
}

void Core::onGroupCustomPacket(Tox* tox, uint32_t groupId, uint32_t peerId, const uint8_t* data,
                               size_t length, void* vCore)
{
    std::ignore = tox;
    auto core = static_cast<Core*>(vCore);
    core->handleNgcFileTransferPacket(groupId, peerId, data, length, false);
}

void Core::onGroupCustomPrivatePacket(Tox* tox, uint32_t groupId, uint32_t peerId,
                                      const uint8_t* data, size_t length, void* vCore)
{
    std::ignore = tox;
    auto core = static_cast<Core*>(vCore);
    core->handleNgcFileTransferPacket(groupId, peerId, data, length, true);
}

void Core::onGroupPeerNameChange(Tox* tox, uint32_t groupId, uint32_t peerId, const uint8_t* name,
                                 size_t length, void* vCore)
{
    std::ignore = tox;
    const auto newName = ToxString(name, length).getQString();
    qDebug() << QString("Group %1, peer %2, name changed to %3").arg(groupId).arg(peerId).arg(newName);
    auto* core = static_cast<Core*>(vCore);
    auto peerPk = core->getGroupPeerPk(groupId, peerId);
    emit core->groupPeerNameChanged(groupId, peerPk, newName);
}

void Core::onGroupTopicChange(Tox* tox, uint32_t groupId, uint32_t peerId, const uint8_t* topic,
                              size_t length, void* vCore)
{
    std::ignore = tox;
    Core* core = static_cast<Core*>(vCore);
    emit core->saveRequest();
    // The NGC group NAME (shared state) is the authoritative display title,
    // matching the Android client. The topic must not hijack the title.
    if (!core->getGroupName(static_cast<int>(groupId)).isEmpty()) {
        return;
    }
    QString author;
    if (peerId != 0) {
        author = core->getGroupPeerName(groupId, static_cast<int>(peerId));
    }
    emit core->groupTitleChanged(groupId, author, ToxString(topic, length).getQString());
}

/**
 * @brief Handling of custom lossless packets received by toxcore. Currently only used to forward toxext packets to CoreExt
 */
void Core::onLosslessPacket(Tox* tox, uint32_t friendId,
                            const uint8_t* data, size_t length, void* vCore)
{
    std::ignore = tox;
    Core* core = static_cast<Core*>(vCore);
    if (length > 0 && data[0] == 184 /* GROUP_INVITE_REQUEST_PACKET_ID */) {
        core->handleGroupInviteRequestPacket(friendId, data, length);
        return;
    }
    core->ext->onLosslessPacket(friendId, data, length);
}

void Core::onReadReceiptCallback(Tox* tox, uint32_t friendId, uint32_t receipt, void* core)
{
    std::ignore = tox;
    qDebug() << "Core: friend_read_receipt friendId" << friendId << "messageId" << receipt;
    emit static_cast<Core*>(core)->receiptRecieved(friendId, ReceiptNum{receipt});
}

void Core::acceptFriendRequest(const ToxPk& friendPk)
{
    QMutexLocker ml{&coreLoopLock};
    Tox_Err_Friend_Add error;
    uint32_t friendId = tox_friend_add_norequest(tox.get(), friendPk.getData(), &error);
    if (PARSE_ERR(error)) {
        emit saveRequest();
        emit friendAdded(friendId, friendPk);
        const QString username = getFriendUsername(friendId);
        if (!username.isEmpty()) {
            emit friendUsernameChanged(friendId, username);
        }
    } else {
        emit failedToAddFriend(friendPk);
    }
}

/**
 * @brief Checks that sending friendship request is correct and returns error message accordingly
 * @param friendId Id of a friend which request is destined to
 * @param message Friendship request message
 * @return Returns empty string if sending request is correct, according error message otherwise
 */
QString Core::getFriendRequestErrorMessage(const ToxId& friendId, const QString& message) const
{
    QMutexLocker ml{&coreLoopLock};

    if (!friendId.isValid()) {
        return tr("Invalid Tox ID", "Error while sending friend request");
    }

    if (ToxString(message).size() > tox_max_friend_request_length()) {
        return tr("Your message is too long!", "Error while sending friend request");
    }

    if (hasFriendWithPublicKey(friendId.getPublicKey())) {
        return tr("Friend is already added", "Error while sending friend request");
    }

    return QString{};
}

void Core::requestFriendship(const ToxId& friendId, const QString& message)
{
    QMutexLocker ml{&coreLoopLock};

    ToxPk friendPk = friendId.getPublicKey();
    QString requestMessage = message.trimmed();
    if (requestMessage.isEmpty()) {
        requestMessage = QStringLiteral("Khandaq");
    }
    QString errorMessage = getFriendRequestErrorMessage(friendId, requestMessage);
    if (!errorMessage.isNull()) {
        emit failedToAddFriend(friendPk, errorMessage);
        emit saveRequest();
        return;
    }

    ToxString cMessage(requestMessage);
    Tox_Err_Friend_Add error;
    uint32_t friendNumber =
        tox_friend_add(tox.get(), friendId.getBytes(), cMessage.data(), cMessage.size(), &error);
    if (PARSE_ERR(error)) {
        qDebug() << "Requested friendship from " << friendNumber;
        emit saveRequest();
        emit friendAdded(friendNumber, friendPk);
        emit requestSent(friendPk, requestMessage);
        const QString username = getFriendUsername(friendNumber);
        if (!username.isEmpty()) {
            emit friendUsernameChanged(friendNumber, username);
        }
    } else {
        qDebug() << "Failed to send friend request";
        emit failedToAddFriend(friendPk);
    }
}

bool Core::sendMessageWithType(uint32_t friendId, const QString& message, Tox_Message_Type type,
                               ReceiptNum& receipt)
{
    int size = message.toUtf8().size();
    auto maxSize = static_cast<int>(getMaxMessageSize());
    if (size > maxSize) {
        assert(false);
        qCritical() << "Core::sendMessageWithType called with message of size:" << size
                    << "when max is:" << maxSize << ". Ignoring.";
        return false;
    }

    ToxString cMessage(message);
    Tox_Err_Friend_Send_Message error;
    receipt = ReceiptNum{tox_friend_send_message(tox.get(), friendId, type, cMessage.data(),
                                                 cMessage.size(), &error)};
    if (PARSE_ERR(error)) {
        return true;
    }
    return false;
}

bool Core::sendMessage(uint32_t friendId, const QString& message, ReceiptNum& receipt)
{
    QMutexLocker ml(&coreLoopLock);
    return sendMessageWithType(friendId, message, TOX_MESSAGE_TYPE_NORMAL, receipt);
}

bool Core::sendAction(uint32_t friendId, const QString& action, ReceiptNum& receipt)
{
    QMutexLocker ml(&coreLoopLock);
    return sendMessageWithType(friendId, action, TOX_MESSAGE_TYPE_ACTION, receipt);
}

void Core::sendTyping(uint32_t friendId, bool typing)
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Set_Typing error;
    tox_self_set_typing(tox.get(), friendId, typing, &error);
    if (!PARSE_ERR(error)) {
        emit failedToSetTyping(typing);
    }
}

void Core::sendGroupMessageWithType(int groupId, const QString& message, Tox_Message_Type type)
{
    QMutexLocker ml{&coreLoopLock};

    // Only revive a genuinely disconnected group; a full reconnect on every
    // outgoing message would drop all peer connections each time we type.
    reconnectGroupIfDisconnected(static_cast<uint32_t>(groupId));

    int size = message.toUtf8().size();
    auto maxSize = static_cast<int>(getMaxMessageSize());
    if (size > maxSize) {
        qCritical() << "Core::sendMessageWithType called with message of size:" << size
                    << "when max is:" << maxSize << ". Ignoring.";
        return;
    }

    ToxString cMsg(message);
    Tox_Err_Group_Send_Message error;
    tox_group_send_message(tox.get(), groupId, type, cMsg.data(), cMsg.size(), nullptr, &error);
    if (!PARSE_ERR(error)) {
        emit groupSentFailed(groupId);
        return;
    }
}

void Core::sendGroupMessage(int groupId, const QString& message)
{
    QMutexLocker ml{&coreLoopLock};

    sendGroupMessageWithType(groupId, message, TOX_MESSAGE_TYPE_NORMAL);
}

void Core::sendGroupAction(int groupId, const QString& message)
{
    QMutexLocker ml{&coreLoopLock};

    sendGroupMessageWithType(groupId, message, TOX_MESSAGE_TYPE_ACTION);
}

void Core::changeGroupTitle(int groupId, const QString& title)
{
    QMutexLocker ml{&coreLoopLock};

    ToxString cTitle(title);
    Tox_Err_Group_Topic_Set error;
    tox_group_set_topic(tox.get(), groupId, cTitle.data(), cTitle.size(), &error);
    if (PARSE_ERR(error)) {
        emit saveRequest();
        emit groupTitleChanged(groupId, getUsername(), title);
    }
}

void Core::removeFriend(uint32_t friendId)
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Friend_Delete error;
    tox_friend_delete(tox.get(), friendId, &error);
    if (!PARSE_ERR(error)) {
        emit failedToRemoveFriend(friendId);
        return;
    }

    emit saveRequest();
    emit friendRemoved(friendId);
}

void Core::removeGroup(int groupId)
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Group_Leave error;
    tox_group_leave(tox.get(), groupId, nullptr, 0, &error);
    if (PARSE_ERR(error)) {
        emit saveRequest();
    }
}

void Core::quitGroupChat(int groupId) const
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Group_Leave error;
    tox_group_leave(tox.get(), groupId, nullptr, 0, &error);
    PARSE_ERR(error);
}

/**
 * @brief Returns our username, or an empty string on failure
 */
QString Core::getUsername() const
{
    QMutexLocker ml{&coreLoopLock};

    QString sname;
    if (!tox) {
        return sname;
    }

    int size = tox_self_get_name_size(tox.get());
    if (!size) {
        return {};
    }
    std::vector<uint8_t> nameBuf(size);
    tox_self_get_name(tox.get(), nameBuf.data());
    return ToxString(nameBuf.data(), size).getQString();
}

void Core::setUsername(const QString& username)
{
    QMutexLocker ml{&coreLoopLock};

    if (username == getUsername()) {
        return;
    }

    ToxString cUsername(username);
    Tox_Err_Set_Info error;
    tox_self_set_name(tox.get(), cUsername.data(), cUsername.size(), &error);
    if (!PARSE_ERR(error)) {
        emit failedToSetUsername(username);
        return;
    }

    emit usernameSet(username);
    emit saveRequest();
}

/**
 * @brief Returns our Tox ID
 */
ToxId Core::getSelfId() const
{
    QMutexLocker ml{&coreLoopLock};

    uint8_t friendId[TOX_ADDRESS_SIZE] = {0x00};
    tox_self_get_address(tox.get(), friendId);
    return ToxId(friendId, TOX_ADDRESS_SIZE);
}

/**
 * @brief Gets self public key
 * @return Self PK
 */
ToxPk Core::getSelfPublicKey() const
{
    QMutexLocker ml{&coreLoopLock};

    uint8_t selfPk[TOX_PUBLIC_KEY_SIZE] = {0x00};
    tox_self_get_public_key(tox.get(), selfPk);
    return ToxPk(selfPk);
}

QByteArray Core::getSelfDhtId() const
{
    QMutexLocker ml{&coreLoopLock};
    QByteArray dhtKey(TOX_PUBLIC_KEY_SIZE, 0x00);
    tox_self_get_dht_id(tox.get(), reinterpret_cast<uint8_t*>(dhtKey.data()));
    return dhtKey;
}

int Core::getSelfUdpPort() const
{
    QMutexLocker ml{&coreLoopLock};
    Tox_Err_Get_Port error;
    auto port = tox_self_get_udp_port(tox.get(), &error);
    if (!PARSE_ERR(error)) {
        return -1;
    }
    return port;
}

/**
 * @brief Returns our status message, or an empty string on failure
 */
QString Core::getStatusMessage() const
{
    QMutexLocker ml{&coreLoopLock};

    assert(tox != nullptr);

    size_t size = tox_self_get_status_message_size(tox.get());
    if (!size) {
        return {};
    }
    std::vector<uint8_t> nameBuf(size);
    tox_self_get_status_message(tox.get(), nameBuf.data());
    return ToxString(nameBuf.data(), size).getQString();
}

/**
 * @brief Returns our user status
 */
Status::Status Core::getStatus() const
{
    QMutexLocker ml{&coreLoopLock};

    return static_cast<Status::Status>(tox_self_get_status(tox.get()));
}

void Core::setStatusMessage(const QString& message)
{
    QMutexLocker ml{&coreLoopLock};

    if (message == getStatusMessage()) {
        return;
    }

    ToxString cMessage(message);
    Tox_Err_Set_Info error;
    tox_self_set_status_message(tox.get(), cMessage.data(), cMessage.size(), &error);
    if (!PARSE_ERR(error)) {
        emit failedToSetStatusMessage(message);
        return;
    }

    emit saveRequest();
    emit statusMessageSet(message);
}

void Core::setStatus(Status::Status status)
{
    QMutexLocker ml{&coreLoopLock};

    Tox_User_Status userstatus;
    switch (status) {
    case Status::Status::Online:
        userstatus = TOX_USER_STATUS_NONE;
        break;

    case Status::Status::Away:
        userstatus = TOX_USER_STATUS_AWAY;
        break;

    case Status::Status::Busy:
        userstatus = TOX_USER_STATUS_BUSY;
        break;

    default:
        return;
        break;
    }

    tox_self_set_status(tox.get(), userstatus);
    emit saveRequest();
    emit statusSet(status);
}

/**
 * @brief Returns the unencrypted tox save data
 */
QByteArray Core::getToxSaveData()
{
    QMutexLocker ml{&coreLoopLock};

    uint32_t fileSize = tox_get_savedata_size(tox.get());
    QByteArray data;
    data.resize(fileSize);
    tox_get_savedata(tox.get(), reinterpret_cast<uint8_t*>(data.data()));
    return data;
}

void Core::loadFriends()
{
    QMutexLocker ml{&coreLoopLock};

    const size_t friendCount = tox_self_get_friend_list_size(tox.get());
    if (friendCount == 0) {
        return;
    }

    std::vector<uint32_t> ids(friendCount);
    tox_self_get_friend_list(tox.get(), ids.data());
    uint8_t friendPk[TOX_PUBLIC_KEY_SIZE] = {0x00};
    for (size_t i = 0; i < friendCount; ++i) {
        Tox_Err_Friend_Get_Public_Key keyError;
        tox_friend_get_public_key(tox.get(), ids[i], friendPk, &keyError);
        if (!PARSE_ERR(keyError)) {
            continue;
        }
        emit friendAdded(ids[i], ToxPk(friendPk));
        emit friendUsernameChanged(ids[i], getFriendUsername(ids[i]));
        Tox_Err_Friend_Query queryError;
        size_t statusMessageSize = tox_friend_get_status_message_size(tox.get(), ids[i], &queryError);
        if (PARSE_ERR(queryError) && statusMessageSize) {
            std::vector<uint8_t> messageData(statusMessageSize);
            tox_friend_get_status_message(tox.get(), ids[i], messageData.data(), &queryError);
            QString friendStatusMessage = ToxString(messageData.data(), statusMessageSize).getQString();
            emit friendStatusMessageChanged(ids[i], friendStatusMessage);
        }
        checkLastOnline(ids[i]);
    }
}

void Core::loadGroups()
{
    QMutexLocker ml{&coreLoopLock};

    const uint32_t groupCount = tox_group_get_number_groups(tox.get());
    if (groupCount == 0) {
        return;
    }

    std::vector<uint32_t> groupNumbers(groupCount);
    tox_group_get_grouplist(tox.get(), groupNumbers.data());

    for (uint32_t groupNumber : groupNumbers) {
        Tox_Err_Group_State_Queries error;
        QString name;
        const GroupId persistentId = getGroupPersistentId(groupNumber);
        const QString defaultName = tr("Groupchat %1").arg(persistentId.toString().left(8));

        size_t topicSize = tox_group_get_topic_size(tox.get(), groupNumber, &error);
        if (PARSE_ERR(error) && topicSize > 0) {
            std::vector<uint8_t> topicBuf(topicSize);
            if (tox_group_get_topic(tox.get(), groupNumber, topicBuf.data(), &error)) {
                name = ToxString(topicBuf.data(), topicSize).getQString();
            }
        }
        if (name.isEmpty()) {
            size_t nameSize = tox_group_get_name_size(tox.get(), groupNumber, &error);
            if (PARSE_ERR(error) && nameSize > 0) {
                std::vector<uint8_t> nameBuf(nameSize);
                if (tox_group_get_name(tox.get(), groupNumber, nameBuf.data(), &error)) {
                    name = ToxString(nameBuf.data(), nameSize).getQString();
                }
            }
        }
        if (name.isEmpty()) {
            name = defaultName;
        }
        reconnectGroupIfDisconnected(groupNumber);
        emit emptyGroupCreated(groupNumber, persistentId, name);
    }
}

void Core::checkLastOnline(uint32_t friendId)
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Friend_Get_Last_Online error;
    const uint64_t lastOnline = tox_friend_get_last_online(tox.get(), friendId, &error);
    if (PARSE_ERR(error)) {
        emit friendLastSeenChanged(friendId, QDateTime::fromTime_t(lastOnline));
    }
}

/**
 * @brief Returns the list of friendIds in our friendlist, an empty list on error
 */
QVector<uint32_t> Core::getFriendList() const
{
    QMutexLocker ml{&coreLoopLock};

    QVector<uint32_t> friends;
    friends.resize(tox_self_get_friend_list_size(tox.get()));
    tox_self_get_friend_list(tox.get(), friends.data());
    return friends;
}

GroupId Core::getGroupPersistentId(uint32_t groupNumber) const
{
    QMutexLocker ml{&coreLoopLock};

    std::vector<uint8_t> idBuff(TOX_GROUP_CHAT_ID_SIZE);
    Tox_Err_Group_State_Queries error;
    if (tox_group_get_chat_id(tox.get(), groupNumber, idBuff.data(), &error)) {
        return GroupId{idBuff.data()};
    }
    qCritical() << "Failed to get NGC chat ID of group" << groupNumber;
    return {};
}

QVector<uint32_t> Core::getGroupPeerList(int groupId) const
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Group_Peer_Query error;
    const uint32_t count = tox_group_peer_count(tox.get(), groupId, &error);
    if (!PARSE_ERR(error) || count == 0) {
        return {};
    }

    QVector<uint32_t> peers(static_cast<int>(count));
    tox_group_get_peerlist(tox.get(), groupId, peers.data(), &error);
    if (!PARSE_ERR(error)) {
        return {};
    }
    return peers;
}

/**
 * @brief Get number of peers in the conference.
 * @return The number of peers in the conference. UINT32_MAX on failure.
 */
uint32_t Core::getGroupNumberPeers(int groupId) const
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Group_Peer_Query error;
    uint32_t count = tox_group_peer_count(tox.get(), groupId, &error);
    if (!PARSE_ERR(error)) {
        return std::numeric_limits<uint32_t>::max();
    }

    return count;
}

/**
 * @brief Get the NGC group name (shared state set by the founder).
 */
QString Core::getGroupName(int groupId) const
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Group_State_Queries error;
    const size_t nameSize = tox_group_get_name_size(tox.get(), groupId, &error);
    if (error != TOX_ERR_GROUP_STATE_QUERIES_OK || nameSize == 0) {
        return QString{};
    }
    std::vector<uint8_t> nameBuf(nameSize);
    if (!tox_group_get_name(tox.get(), groupId, nameBuf.data(), &error)
        || error != TOX_ERR_GROUP_STATE_QUERIES_OK) {
        return QString{};
    }
    return ToxString(nameBuf.data(), nameSize).getQString().trimmed();
}

/**
 * @brief Get the name of a peer of a group
 */
QString Core::getGroupPeerName(int groupId, int peerId) const
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Group_Peer_Query error;
    size_t length = tox_group_peer_get_name_size(tox.get(), groupId, peerId, &error);
    if (!PARSE_ERR(error) || !length) {
        return QString{};
    }

    std::vector<uint8_t> nameBuf(length);
    if (!tox_group_peer_get_name(tox.get(), groupId, peerId, nameBuf.data(), &error)) {
        return QString{};
    }

    return ToxString(nameBuf.data(), length).getQString();
}

/**
 * @brief Get the public key of a peer of a group
 */
ToxPk Core::getGroupPeerPk(int groupId, int peerId) const
{
    QMutexLocker ml{&coreLoopLock};

    uint8_t friendPk[TOX_GROUP_PEER_PUBLIC_KEY_SIZE] = {0x00};
    Tox_Err_Group_Peer_Query error;
    if (!tox_group_peer_get_public_key(tox.get(), groupId, peerId, friendPk, &error)) {
        return ToxPk{};
    }

    return ToxPk(friendPk);
}

/**
 * @brief Get the names of the peers of a group
 */
QStringList Core::getGroupPeerNames(int groupId) const
{
    QMutexLocker ml{&coreLoopLock};

    assert(tox != nullptr);

    const QVector<uint32_t> peers = getGroupPeerList(groupId);
    QStringList names;
    names.reserve(peers.size());
    for (uint32_t peerId : peers) {
        names.append(getGroupPeerName(groupId, static_cast<int>(peerId)));
    }
    return names;
}

bool Core::getGroupAvEnabled(int groupId) const
{
    std::ignore = groupId;
    return false;
}

/**
 * @brief Accept a groupchat invite.
 * @param inviteInfo Object which contains info about group invitation
 *
 * @return Conference number on success, UINT32_MAX on failure.
 */
uint32_t Core::joinGroupchat(const GroupInvite& inviteInfo)
{
    QMutexLocker ml{&coreLoopLock};

    const uint32_t friendId = inviteInfo.getFriendId();
    const QByteArray invite = inviteInfo.getInvite();
    const uint8_t* const inviteData = reinterpret_cast<const uint8_t*>(invite.constData());
    const size_t inviteLength = static_cast<size_t>(invite.length());

    QString selfName = getUsername();
    if (selfName.isEmpty()) {
        selfName = tr("User");
    }
    ToxString cSelfName(selfName);

    qDebug() << QString("Accepting NGC group invite from friend %1").arg(friendId);
    Tox_Err_Group_Invite_Accept error;
    const uint32_t groupNum = tox_group_invite_accept(tox.get(), friendId, inviteData, inviteLength,
                                                      cSelfName.data(), cSelfName.size(), nullptr, 0, &error);
    if (!PARSE_ERR(error) || groupNum == std::numeric_limits<uint32_t>::max()) {
        return std::numeric_limits<uint32_t>::max();
    }

    // Fresh invite accept: toxcore is already handshaking with the inviter,
    // forcing a reconnect here would abort that handshake.
    emit saveRequest();
    emit groupJoined(groupNum, getGroupPersistentId(groupNum));
    // NGC invites carry the group name — show it right away instead of the
    // "Groupchat #N" placeholder (shared state sync will confirm it later)
    const QString inviteGroupName = inviteInfo.getGroupName().trimmed();
    if (!inviteGroupName.isEmpty()) {
        emit groupTitleChanged(static_cast<int>(groupNum), QString(), inviteGroupName);
    }
    return groupNum;
}

uint32_t Core::joinGroupByChatId(const QByteArray& chatId)
{
    QMutexLocker ml{&coreLoopLock};

    if (chatId.size() != TOX_GROUP_CHAT_ID_SIZE) {
        qWarning() << "joinGroupByChatId: invalid chat id size" << chatId.size();
        return std::numeric_limits<uint32_t>::max();
    }

    Tox_Err_Group_State_Queries stateError;
    const uint32_t existingNum =
        tox_group_by_chat_id(tox.get(), reinterpret_cast<const uint8_t*>(chatId.constData()), &stateError);
    if (PARSE_ERR(stateError) && existingNum != std::numeric_limits<uint32_t>::max()) {
        // Re-joining a group we already know: force a fresh lookup only when
        // it is not already happily connected to other peers.
        Tox_Err_Group_Is_Connected connError;
        const int32_t conn = tox_group_is_connected(tox.get(), existingNum, &connError);
        Tox_Err_Group_Peer_Query peerError;
        const uint32_t peerCount = tox_group_peer_count(tox.get(), existingNum, &peerError);
        if (!PARSE_ERR(connError) || conn != 1 || !PARSE_ERR(peerError) || peerCount <= 1) {
            kickstartGroupConnection(existingNum);
            requestGroupInviteFromFriends(chatId);
        }
        emit saveRequest();
        emit groupJoined(existingNum, getGroupPersistentId(existingNum));
        return existingNum;
    }

    QString selfName = getUsername();
    if (selfName.isEmpty()) {
        selfName = tr("User");
    }
    ToxString cSelfName(selfName);

    Tox_Err_Group_Join error;
    const uint32_t groupNum = tox_group_join(tox.get(), reinterpret_cast<const uint8_t*>(chatId.constData()),
                                             cSelfName.data(), cSelfName.size(), nullptr, 0, &error);
    if (!PARSE_ERR(error) || groupNum == std::numeric_limits<uint32_t>::max()) {
        return std::numeric_limits<uint32_t>::max();
    }

    // Fresh tox_group_join already starts the DHT announce lookup; in parallel
    // ask friends that may be group members to invite us in instantly.
    requestGroupInviteFromFriends(chatId);
    emit saveRequest();
    emit groupJoined(groupNum, getGroupPersistentId(groupNum));
    return groupNum;
}

uint32_t Core::joinGroupByChatIdHex(const QString& hexId)
{
    const QByteArray chatId = parseGroupChatIdHex(hexId);
    if (chatId.isEmpty()) {
        qWarning() << "joinGroupByChatIdHex: invalid hex group id";
        return std::numeric_limits<uint32_t>::max();
    }
    return joinGroupByChatId(chatId);
}

void Core::reconnectGroupIfDisconnected(uint32_t groupNum) const
{
    // tox_group_is_connected (this fork): -1 = disconnected, 0 = connecting, 1 = connected.
    // Only revive a genuinely disconnected group; reconnecting a CONNECTING one
    // would abort the announce lookup that is already in progress.
    Tox_Err_Group_Is_Connected connError;
    const int32_t conn = tox_group_is_connected(tox.get(), groupNum, &connError);
    if (!PARSE_ERR(connError) || conn != -1) {
        return;
    }

    Tox_Err_Group_Reconnect recError;
    tox_group_reconnect(tox.get(), groupNum, &recError);
    PARSE_ERR(recError);
}

void Core::kickstartGroupConnection(uint32_t groupNum) const
{
    Tox_Err_Group_Reconnect recError;
    tox_group_reconnect(tox.get(), groupNum, &recError);
    PARSE_ERR(recError);
}

namespace {
constexpr int64_t GROUP_KICKSTART_MIN_INTERVAL_MS = 20000;
// A full tox_group_reconnect() tears down all peer connections and restarts the
// onion announce lookup from scratch. The lookup itself needs 30-90+ seconds to
// find peers, so kicking a *connected but lonely* group must be much rarer than
// reconnecting a genuinely disconnected one.
constexpr int64_t GROUP_LONELY_KICKSTART_MIN_INTERVAL_MS = 180000;
std::unordered_map<uint32_t, int64_t> groupLastKickstartMs;
// When a public group has been connected-but-peerless (or stuck CONNECTING)
// continuously since this timestamp, we occasionally reset the announce
// lookup backoff with a full reconnect.
std::unordered_map<uint32_t, int64_t> groupStagnantSinceMs;

// Friend-assisted group join: DHT announce lookups are slow (30-90+ s, can
// back off to much longer), but a friend who is already inside the target
// group can invite us over the existing friend connection instantly. On join
// by chat id we broadcast a small "invite me to this group" packet to online
// friends; a member that receives it replies with a normal NGC invite which
// we auto-accept.
constexpr uint8_t GROUP_INVITE_REQUEST_PACKET_ID = 184; // custom lossless range 160..191
constexpr uint8_t GROUP_INVITE_REQUEST_VERSION = 1;
constexpr int64_t GROUP_INVITE_REQUEST_TTL_MS = 10 * 60 * 1000;
constexpr int64_t GROUP_INVITE_REQUEST_RESEND_MS = 30 * 1000;
constexpr int64_t GROUP_INVITE_REPLY_MIN_INTERVAL_MS = 60 * 1000;
// chat ids (raw 32 bytes) we asked friends to invite us to → request time
std::unordered_map<std::string, int64_t> pendingFriendAssistedJoins;
// receiver side: (friendId, groupNum) → last time we sent them an invite
std::unordered_map<uint64_t, int64_t> lastGroupInviteReplyMs;
// sender side: (friendId, chat id hash) → last time we sent the request
std::unordered_map<uint64_t, int64_t> lastGroupInviteRequestMs;

uint64_t friendGroupKey(uint32_t friendId, uint32_t groupPart)
{
    return (static_cast<uint64_t>(friendId) << 32) | groupPart;
}

uint32_t chatIdHash32(const uint8_t* chatId)
{
    uint32_t h = 0;
    for (int i = 0; i < 4; ++i) {
        h = (h << 8) | chatId[i];
    }
    return h;
}

// NGC file transfer (TRIfA-compatible wire protocol):
// files inside NGC groups travel as lossless custom packets with this layout:
//   magic(6) = 66 77 88 11 34 35, version(1) = 01, pkt_id(1),
//   pkt 0x11 (live file, broadcast):  msg_id(32) create_ts(4) filename(255) data[1..36701]
//   pkt 0x12 (chunked begin):         same header + total_size(8) chunk_size(4) total_chunks(4)
//   pkt 0x13 (chunked data):            msg_id(32) chunk_index(4) chunk_size(4) data[...]
//   pkt 0x03 (history-sync file, private): msg_id(32) orig_sender_pk(32) ts(4)
//                                          peer_name(25) filename(255) data[1..36701]
const uint8_t NGC_MAGIC[6] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35};
constexpr uint8_t NGC_VERSION = 0x01;
constexpr size_t NGC_SYNC_FILE_HEADER_SIZE = 6 + 1 + 1 + 32 + 32 + 4 + 25 + 255; // 356

struct NgcIncomingAssembly {
    QString fileName;
    QString outPath;
    uint64_t totalSize = 0;
    uint32_t totalChunks = 0;
    uint32_t chunkPayload = 0;
    uint32_t receivedCount = 0;
    QVector<bool> received;
};

QHash<QString, NgcIncomingAssembly> ngcIncomingAssemblies;

QString ngcAssemblyKey(uint32_t groupId, const QByteArray& msgId)
{
    return QString::number(groupId) + QLatin1Char(':') + QString::fromLatin1(msgId.toHex());
}

void ngcPutMagic(uint8_t* p)
{
    memcpy(p, NGC_MAGIC, sizeof(NGC_MAGIC));
    p[6] = NGC_VERSION;
}

void ngcPutFilename(uint8_t* p, const QString& fileName)
{
    const QByteArray nameUtf8 = fileName.toUtf8().left(254);
    memset(p, 0, 255);
    memcpy(p, nameUtf8.constData(), static_cast<size_t>(nameUtf8.size()));
}

void ngcPutU32Be(uint8_t* p, uint32_t v)
{
    p[0] = static_cast<uint8_t>((v >> 24) & 0xff);
    p[1] = static_cast<uint8_t>((v >> 16) & 0xff);
    p[2] = static_cast<uint8_t>((v >> 8) & 0xff);
    p[3] = static_cast<uint8_t>(v & 0xff);
}

void ngcPutU64Be(uint8_t* p, uint64_t v)
{
    for (int i = 7; i >= 0; --i) {
        p[i] = static_cast<uint8_t>(v & 0xff);
        v >>= 8;
    }
}

uint32_t ngcReadU32Be(const uint8_t* p)
{
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16)
           | (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

uint64_t ngcReadU64Be(const uint8_t* p)
{
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) {
        v = (v << 8) | p[i];
    }
    return v;
}

// dedupe incoming files by msg_id (live + sync copies of the same message)
QSet<QByteArray> ngcSeenFileMsgIds;
QList<QByteArray> ngcSeenFileMsgIdOrder;

bool ngcFileMsgIdSeen(const QByteArray& msgId)
{
    if (ngcSeenFileMsgIds.contains(msgId)) {
        return true;
    }
    ngcSeenFileMsgIds.insert(msgId);
    ngcSeenFileMsgIdOrder.append(msgId);
    while (ngcSeenFileMsgIdOrder.size() > 512) {
        ngcSeenFileMsgIds.remove(ngcSeenFileMsgIdOrder.takeFirst());
    }
    return false;
}

QString ngcParseFilename(const uint8_t* field, size_t fieldLen)
{
    size_t len = 0;
    while (len < fieldLen && field[len] != 0) {
        ++len;
    }
    QString name = QString::fromUtf8(reinterpret_cast<const char*>(field), static_cast<int>(len)).trimmed();
    // strip any path components a malicious sender could embed
    name = QFileInfo(name).fileName();
    // KHANDAQ (security P-4): drop trailing dots/spaces and avoid Windows reserved device names
    // (CON, PRN, AUX, NUL, COM1-9, LPT1-9). A peer-chosen name like "CON" or "evil." otherwise causes
    // silent write failures / surprising behavior on Windows.
    while (!name.isEmpty() && (name.endsWith(QLatin1Char('.')) || name.endsWith(QLatin1Char(' ')))) {
        name.chop(1);
    }
    static const QSet<QString> reservedWindowsNames = {
        QStringLiteral("CON"), QStringLiteral("PRN"), QStringLiteral("AUX"), QStringLiteral("NUL"),
        QStringLiteral("COM1"), QStringLiteral("COM2"), QStringLiteral("COM3"), QStringLiteral("COM4"),
        QStringLiteral("COM5"), QStringLiteral("COM6"), QStringLiteral("COM7"), QStringLiteral("COM8"),
        QStringLiteral("COM9"), QStringLiteral("LPT1"), QStringLiteral("LPT2"), QStringLiteral("LPT3"),
        QStringLiteral("LPT4"), QStringLiteral("LPT5"), QStringLiteral("LPT6"), QStringLiteral("LPT7"),
        QStringLiteral("LPT8"), QStringLiteral("LPT9")
    };
    if (reservedWindowsNames.contains(name.section(QLatin1Char('.'), 0, 0).toUpper())) {
        name = QStringLiteral("_") + name;
    }
    if (name.isEmpty()) {
        name = QStringLiteral("file.bin");
    }
    return name;
}

bool shouldRunGroupMaintenance(uint32_t groupNum, std::unordered_map<uint32_t, int64_t>& map, int64_t minIntervalMs)
{
    const int64_t now = QDateTime::currentMSecsSinceEpoch();
    const int64_t last = map[groupNum];
    if (now - last < minIntervalMs) {
        return false;
    }
    map[groupNum] = now;
    return true;
}
} // namespace

void Core::maintainGroups()
{
    const uint32_t groupCount = tox_group_get_number_groups(tox.get());
    if (groupCount == 0) {
        return;
    }

    std::vector<uint32_t> groupList(groupCount);
    tox_group_get_grouplist(tox.get(), groupList.data());

    for (uint32_t groupNum : groupList) {
        Tox_Err_Group_State_Queries privacyError;
        const auto privacy = tox_group_get_privacy_state(tox.get(), groupNum, &privacyError);
        if (!PARSE_ERR(privacyError)) {
            reconnectGroupIfDisconnected(groupNum);
            continue;
        }

        Tox_Err_Group_Peer_Query peerError;
        const uint32_t peerCount = tox_group_peer_count(tox.get(), groupNum, &peerError);
        PARSE_ERR(peerError);

        Tox_Err_Group_Is_Connected connError;
        const int32_t conn = tox_group_is_connected(tox.get(), groupNum, &connError);
        PARSE_ERR(connError);

        if (privacy != TOX_GROUP_PRIVACY_STATE_PUBLIC) {
            reconnectGroupIfDisconnected(groupNum);
            continue;
        }

        // tox_group_is_connected: -1 = disconnected, 0 = connecting, 1 = connected
        if (conn == -1) {
            // Genuinely disconnected: retry quickly.
            groupStagnantSinceMs.erase(groupNum);
            if (shouldRunGroupMaintenance(groupNum, groupLastKickstartMs, GROUP_KICKSTART_MIN_INTERVAL_MS)) {
                kickstartGroupConnection(groupNum);
            }
        } else if (peerCount <= 1) {
            // Connecting, or connected but alone: the onion lookup is running
            // and needs 30-90+ seconds; only reset its exponential backoff
            // after the group stayed peerless for a long continuous stretch.
            const int64_t now = QDateTime::currentMSecsSinceEpoch();
            const auto it = groupStagnantSinceMs.find(groupNum);
            if (it == groupStagnantSinceMs.end()) {
                groupStagnantSinceMs[groupNum] = now;
            } else if (now - it->second >= GROUP_LONELY_KICKSTART_MIN_INTERVAL_MS) {
                it->second = now;
                kickstartGroupConnection(groupNum);
            }
            // Independent of the slow DHT path: keep asking friends that are
            // group members to invite us in over the friend connection. The
            // per-friend rate limit (30 s) keeps the traffic negligible.
            static std::unordered_map<uint32_t, int64_t> lastFriendAssistMs;
            int64_t& lastAssist = lastFriendAssistMs[groupNum];
            if (now - lastAssist >= 15000) {
                lastAssist = now;
                uint8_t chatIdBuf[TOX_GROUP_CHAT_ID_SIZE];
                Tox_Err_Group_State_Queries chatIdError;
                if (tox_group_get_chat_id(tox.get(), groupNum, chatIdBuf, &chatIdError)
                    && chatIdError == TOX_ERR_GROUP_STATE_QUERIES_OK) {
                    requestGroupInviteFromFriends(
                        QByteArray(reinterpret_cast<const char*>(chatIdBuf), TOX_GROUP_CHAT_ID_SIZE));
                }
            }
        } else {
            groupStagnantSinceMs.erase(groupNum);
        }
    }

    if (++groupMaintenanceTick >= 40) {
        groupMaintenanceTick = 0;
        for (uint32_t groupNum : groupList) {
            Tox_Err_Group_State_Queries privacyError;
            const auto privacy = tox_group_get_privacy_state(tox.get(), groupNum, &privacyError);
            if (!PARSE_ERR(privacyError) || privacy != TOX_GROUP_PRIVACY_STATE_PUBLIC) {
                continue;
            }
            emit groupPeerlistChanged(groupNum);
        }
    }

    static std::unordered_map<uint32_t, int64_t> groupLastPeerSyncMs;
    constexpr int64_t GROUP_PEER_SYNC_INTERVAL_MS = 30000;
    for (uint32_t groupNum : groupList) {
        if (shouldRunGroupMaintenance(groupNum, groupLastPeerSyncMs, GROUP_PEER_SYNC_INTERVAL_MS)) {
            emit groupPeerlistChanged(groupNum);
        }
    }
}

void Core::groupInviteFriend(uint32_t friendId, int groupId)
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Group_Invite_Friend error;
    tox_group_invite_friend(tox.get(), groupId, friendId, &error);
    PARSE_ERR(error);
}

/**
 * @brief Parse an incoming NGC custom packet; handle TRIfA-style file transfers.
 * Live files (pkt 0x11) arrive as broadcast custom packets, history-sync files
 * (pkt 0x03) as private custom packets from a peer that resyncs history to us.
 */
void Core::handleNgcFileTransferPacket(uint32_t groupId, uint32_t peerId, const uint8_t* data,
                                       size_t length, bool isPrivate)
{
    if (length < 8 || memcmp(data, NGC_MAGIC, sizeof(NGC_MAGIC)) != 0 || data[6] != NGC_VERSION) {
        return;
    }
    const uint8_t pktId = data[7];

    if (pktId == NGC_PKT_FILE && !isPrivate) {
        if (length <= NGC_FILE_HEADER_SIZE
            || length > NGC_FILE_HEADER_SIZE + NGC_SINGLE_PKT_MAX_FILESIZE) {
            return;
        }
        const QByteArray msgId(reinterpret_cast<const char*>(data + 8), 32);
        if (ngcFileMsgIdSeen(msgId)) {
            return;
        }
        const QString fileName = ngcParseFilename(data + 44, 255);
        const QByteArray fileData(reinterpret_cast<const char*>(data + NGC_FILE_HEADER_SIZE),
                                  static_cast<int>(length - NGC_FILE_HEADER_SIZE));
        const ToxPk sender = getGroupPeerPk(static_cast<int>(groupId), static_cast<int>(peerId));
        if (sender == getSelfPublicKey()) {
            return;
        }
        qDebug() << "NGC file received: group" << groupId << "peer" << peerId << fileName
                 << fileData.size() << "bytes";
        emit groupFileReceived(static_cast<int>(groupId), sender, fileName, fileData,
                               QDateTime::currentDateTime());
        return;
    }

    if (pktId == NGC_PKT_FILE_BEGIN && !isPrivate) {
        if (length < NGC_FILE_BEGIN_SIZE) {
            return;
        }
        const QByteArray msgId(reinterpret_cast<const char*>(data + 8), 32);
        const QString key = ngcAssemblyKey(groupId, msgId);
        if (ngcIncomingAssemblies.contains(key)) {
            return;
        }
        // KHANDAQ (audit A38): cap concurrent incoming assemblies — a peer flooding BEGINs for distinct
        // msgIds would otherwise pin unbounded memory (a received-bitmap + buffer per assembly).
        if (ngcIncomingAssemblies.size() >= 16) {
            return;
        }
        const QString fileName = ngcParseFilename(data + 44, 255);
        const uint64_t totalSize = ngcReadU64Be(data + 299);
        const uint32_t chunkPayload = ngcReadU32Be(data + 307);
        const uint32_t totalChunks = ngcReadU32Be(data + 311);
        if (totalSize == 0 || totalSize > KHANDAQ_MAX_FILE_TRANSFER_BYTES || totalChunks == 0
            || chunkPayload == 0 || chunkPayload > NGC_CHUNK_PAYLOAD_MAX) {
            return;
        }
        // KHANDAQ (security V-1): totalChunks is attacker-controlled bytes from a group peer and was
        // only checked !=0. Without a consistency bound, a peer can send totalChunks=0xFFFFFFFE with
        // totalSize=1 → static_cast<int> yields a negative size → QVector<bool>(-2) aborts (remote DoS),
        // and later FILE_CHUNK seeks (chunkIndex*chunkPayload) run far past totalSize (sparse-file disk
        // exhaustion). Require totalChunks to match ceil(totalSize/chunkPayload) and fit in int.
        const uint64_t expectedChunks = (totalSize + chunkPayload - 1) / chunkPayload;
        // KHANDAQ (audit A38): also bound the chunk COUNT to what the MAX chunk size allows for the MAX
        // file, so a peer using a tiny chunkPayload can't force a huge received-bitmap allocation
        // (QVector<bool> is 1 byte/entry, not bit-packed). Legit senders use chunkPayload == the max.
        const uint64_t maxChunks = (KHANDAQ_MAX_FILE_TRANSFER_BYTES + NGC_CHUNK_PAYLOAD_MAX - 1) / NGC_CHUNK_PAYLOAD_MAX;
        if (totalChunks != expectedChunks || totalChunks > maxChunks || totalChunks > 0x7FFFFFFFu) {
            return;
        }

        NgcIncomingAssembly asm_;
        asm_.fileName = fileName;
        asm_.totalSize = totalSize;
        asm_.totalChunks = totalChunks;
        asm_.chunkPayload = chunkPayload;
        asm_.received = QVector<bool>(static_cast<int>(totalChunks), false);
        asm_.outPath = QString(); // filled on complete via in-memory buffer write to temp - use settings path

        const QString saveDir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
                                + QDir::separator() + QStringLiteral("ngc_files");
        QDir().mkpath(saveDir);
        asm_.outPath = saveDir + QDir::separator() + fileName;

        QFile outFile(asm_.outPath);
        if (!outFile.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
            return;
        }
        if (totalSize > 0) {
            outFile.resize(static_cast<qint64>(totalSize));
        }
        outFile.close();

        ngcIncomingAssemblies.insert(key, asm_);
        qDebug() << "NGC chunked file begin:" << fileName << totalSize << "bytes" << totalChunks << "chunks";
        return;
    }

    if (pktId == NGC_PKT_FILE_CHUNK && !isPrivate) {
        if (length < NGC_FILE_CHUNK_HEADER_SIZE + 1) {
            return;
        }
        const QByteArray msgId(reinterpret_cast<const char*>(data + 8), 32);
        const QString key = ngcAssemblyKey(groupId, msgId);
        auto it = ngcIncomingAssemblies.find(key);
        if (it == ngcIncomingAssemblies.end()) {
            return;
        }
        NgcIncomingAssembly& asm_ = it.value();
        const uint32_t chunkIndex = ngcReadU32Be(data + 40);
        const uint32_t chunkSize = ngcReadU32Be(data + 44);
        if (chunkIndex >= asm_.totalChunks || chunkSize == 0
            || NGC_FILE_CHUNK_HEADER_SIZE + chunkSize > length) {
            return;
        }
        if (asm_.received.at(static_cast<int>(chunkIndex))) {
            return;
        }

        QFile outFile(asm_.outPath);
        if (!outFile.open(QIODevice::ReadWrite)) {
            return;
        }
        const qint64 offset = static_cast<qint64>(chunkIndex) * asm_.chunkPayload;
        outFile.seek(offset);
        outFile.write(reinterpret_cast<const char*>(data + NGC_FILE_CHUNK_HEADER_SIZE),
                      static_cast<qint64>(chunkSize));
        outFile.close();

        asm_.received[static_cast<int>(chunkIndex)] = true;
        ++asm_.receivedCount;

        if (asm_.receivedCount < asm_.totalChunks) {
            return;
        }

        const ToxPk sender = getGroupPeerPk(static_cast<int>(groupId), static_cast<int>(peerId));
        ngcIncomingAssemblies.remove(key);
        if (sender == getSelfPublicKey()) {
            return;
        }

        QFile in(asm_.outPath);
        if (!in.open(QIODevice::ReadOnly)) {
            return;
        }
        const QByteArray fileData = in.readAll();
        in.close();

        if (ngcFileMsgIdSeen(msgId)) {
            return;
        }
        qDebug() << "NGC chunked file complete:" << asm_.fileName << fileData.size() << "bytes";
        emit groupFileReceived(static_cast<int>(groupId), sender, asm_.fileName, fileData,
                               QDateTime::currentDateTime());
        return;
    }

    if (pktId == NGC_PKT_SYNC_FILE && isPrivate) {
        if (length <= NGC_SYNC_FILE_HEADER_SIZE
            || length > NGC_SYNC_FILE_HEADER_SIZE + NGC_SINGLE_PKT_MAX_FILESIZE) {
            return;
        }
        const QByteArray msgId(reinterpret_cast<const char*>(data + 8), 32);
        if (ngcFileMsgIdSeen(msgId)) {
            return;
        }
        const ToxPk origSender(QByteArray(reinterpret_cast<const char*>(data + 40), 32));
        if (origSender == getSelfPublicKey()) {
            return; // our own message echoed back via history sync
        }
        uint32_t ts = 0;
        for (int i = 0; i < 4; ++i) {
            ts = (ts << 8) | data[72 + i];
        }
        const QString fileName = ngcParseFilename(data + 101, 255);
        const QByteArray fileData(reinterpret_cast<const char*>(data + NGC_SYNC_FILE_HEADER_SIZE),
                                  static_cast<int>(length - NGC_SYNC_FILE_HEADER_SIZE));
        QDateTime when = ts > 0 ? QDateTime::fromSecsSinceEpoch(ts) : QDateTime::currentDateTime();
        qDebug() << "NGC sync file received: group" << groupId << "orig sender"
                 << origSender.toString().left(8) << fileName << fileData.size() << "bytes";
        emit groupFileReceived(static_cast<int>(groupId), origSender, fileName, fileData, when);
        return;
    }
}

/**
 * @brief Send a file into an NGC group (single pkt 0x11 or chunked 0x12/0x13 for larger files).
 */
bool Core::sendGroupFile(int groupId, const QString& fileName, const QString& localPath,
                         const QByteArray& fileData)
{
    QMutexLocker ml{&coreLoopLock};

    if (fileData.isEmpty()) {
        return sendGroupFileFromPath(groupId, fileName, localPath);
    }

    if (static_cast<uint64_t>(fileData.size()) > KHANDAQ_MAX_FILE_TRANSFER_BYTES) {
        qWarning() << "sendGroupFile: file too large" << fileData.size();
        return false;
    }

    if (static_cast<size_t>(fileData.size()) > NGC_SINGLE_PKT_MAX_FILESIZE) {
        return sendGroupFileFromPath(groupId, fileName, localPath);
    }

    QByteArray packet;
    packet.resize(static_cast<int>(NGC_FILE_HEADER_SIZE) + fileData.size());
    uint8_t* p = reinterpret_cast<uint8_t*>(packet.data());
    memset(p, 0, NGC_FILE_HEADER_SIZE);
    ngcPutMagic(p);
    p[7] = NGC_PKT_FILE;

    QByteArray msgId(32, 0);
    QRandomGenerator::system()->fillRange(reinterpret_cast<quint32*>(msgId.data()), 8);
    memcpy(p + 8, msgId.constData(), 32);
    ngcPutFilename(p + 44, fileName);
    memcpy(p + NGC_FILE_HEADER_SIZE, fileData.constData(), static_cast<size_t>(fileData.size()));

    Tox_Err_Group_Send_Custom_Packet error;
    tox_group_send_custom_packet(tox.get(), static_cast<uint32_t>(groupId), true,
                                 reinterpret_cast<const uint8_t*>(packet.constData()),
                                 static_cast<size_t>(packet.size()), &error);
    if (error != TOX_ERR_GROUP_SEND_CUSTOM_PACKET_OK) {
        qWarning() << "sendGroupFile: tox_group_send_custom_packet failed, error"
                   << static_cast<int>(error);
        return false;
    }

    ngcFileMsgIdSeen(msgId);
    emit groupFileSent(groupId, fileName, localPath, fileData.size());
    return true;
}

bool Core::sendGroupFileFromPath(int groupId, const QString& fileName, const QString& localPath)
{
    QFile in(localPath);
    if (!in.open(QIODevice::ReadOnly)) {
        qWarning() << "sendGroupFileFromPath: cannot open" << localPath;
        return false;
    }

    QString wireName = fileName.trimmed();
    if (wireName.isEmpty()) {
        wireName = QFileInfo(localPath).fileName();
    }

    const qint64 fileSize = in.size();
    if (fileSize <= 0 || static_cast<uint64_t>(fileSize) > KHANDAQ_MAX_FILE_TRANSFER_BYTES) {
        qWarning() << "sendGroupFileFromPath: invalid size" << fileSize;
        return false;
    }

    QByteArray msgId(32, 0);
    QRandomGenerator::system()->fillRange(reinterpret_cast<quint32*>(msgId.data()), 8);

    if (static_cast<uint64_t>(fileSize) <= NGC_SINGLE_PKT_MAX_FILESIZE) {
        const QByteArray payload = in.readAll();
        in.close();
        return sendGroupFile(groupId, wireName, localPath, payload);
    }

    const uint32_t totalChunks =
        static_cast<uint32_t>((static_cast<uint64_t>(fileSize) + NGC_CHUNK_PAYLOAD_MAX - 1)
                              / NGC_CHUNK_PAYLOAD_MAX);

    QByteArray beginPkt(static_cast<int>(NGC_FILE_BEGIN_SIZE), 0);
    uint8_t* bp = reinterpret_cast<uint8_t*>(beginPkt.data());
    ngcPutMagic(bp);
    bp[7] = NGC_PKT_FILE_BEGIN;
    memcpy(bp + 8, msgId.constData(), 32);
    ngcPutFilename(bp + 44, wireName);
    ngcPutU64Be(bp + 299, static_cast<uint64_t>(fileSize));
    ngcPutU32Be(bp + 307, static_cast<uint32_t>(NGC_CHUNK_PAYLOAD_MAX));
    ngcPutU32Be(bp + 311, totalChunks);

    Tox_Err_Group_Send_Custom_Packet error;
    tox_group_send_custom_packet(tox.get(), static_cast<uint32_t>(groupId), true,
                                 reinterpret_cast<const uint8_t*>(beginPkt.constData()),
                                 static_cast<size_t>(beginPkt.size()), &error);
    if (error != TOX_ERR_GROUP_SEND_CUSTOM_PACKET_OK) {
        in.close();
        return false;
    }

    std::array<char, NGC_CHUNK_PAYLOAD_MAX> buf{};
    for (uint32_t idx = 0; idx < totalChunks; ++idx) {
        const qint64 toRead = qMin(static_cast<qint64>(NGC_CHUNK_PAYLOAD_MAX),
                                   fileSize - static_cast<qint64>(idx) * static_cast<qint64>(NGC_CHUNK_PAYLOAD_MAX));
        const qint64 read = in.read(buf.data(), toRead);
        if (read != toRead) {
            in.close();
            return false;
        }

        QByteArray chunkPkt(static_cast<int>(NGC_FILE_CHUNK_HEADER_SIZE + read), 0);
        uint8_t* cp = reinterpret_cast<uint8_t*>(chunkPkt.data());
        ngcPutMagic(cp);
        cp[7] = NGC_PKT_FILE_CHUNK;
        memcpy(cp + 8, msgId.constData(), 32);
        ngcPutU32Be(cp + 40, idx);
        ngcPutU32Be(cp + 44, static_cast<uint32_t>(read));
        memcpy(cp + NGC_FILE_CHUNK_HEADER_SIZE, buf.data(), static_cast<size_t>(read));

        tox_group_send_custom_packet(tox.get(), static_cast<uint32_t>(groupId), true,
                                     reinterpret_cast<const uint8_t*>(chunkPkt.constData()),
                                     static_cast<size_t>(chunkPkt.size()), &error);
        if (error != TOX_ERR_GROUP_SEND_CUSTOM_PACKET_OK) {
            in.close();
            return false;
        }
    }
    in.close();

    ngcFileMsgIdSeen(msgId);
    emit groupFileSent(groupId, wireName, localPath, fileSize);
    return true;
}

/**
 * @brief Ask all online friends to send us an NGC invite for the given chat id.
 * A friend that is a member of the group replies with a regular group invite,
 * which joins us through the friend connection instantly instead of waiting
 * for the slow DHT announce lookup.
 */
void Core::requestGroupInviteFromFriends(const QByteArray& chatId)
{
    if (chatId.size() != TOX_GROUP_CHAT_ID_SIZE) {
        return;
    }

    const int64_t now = QDateTime::currentMSecsSinceEpoch();
    pendingFriendAssistedJoins[std::string(chatId.constData(), static_cast<size_t>(chatId.size()))] = now;

    uint8_t packet[2 + TOX_GROUP_CHAT_ID_SIZE];
    packet[0] = GROUP_INVITE_REQUEST_PACKET_ID;
    packet[1] = GROUP_INVITE_REQUEST_VERSION;
    memcpy(packet + 2, chatId.constData(), TOX_GROUP_CHAT_ID_SIZE);

    const size_t friendCount = tox_self_get_friend_list_size(tox.get());
    if (friendCount == 0) {
        return;
    }
    std::vector<uint32_t> friendIds(friendCount);
    tox_self_get_friend_list(tox.get(), friendIds.data());

    const uint32_t idHash = chatIdHash32(reinterpret_cast<const uint8_t*>(chatId.constData()));
    int sent = 0;
    for (uint32_t friendId : friendIds) {
        Tox_Err_Friend_Query connErr;
        const Tox_Connection conn = tox_friend_get_connection_status(tox.get(), friendId, &connErr);
        if (connErr != TOX_ERR_FRIEND_QUERY_OK || conn == TOX_CONNECTION_NONE) {
            continue;
        }
        const uint64_t key = friendGroupKey(friendId, idHash);
        const auto it = lastGroupInviteRequestMs.find(key);
        if (it != lastGroupInviteRequestMs.end() && now - it->second < GROUP_INVITE_REQUEST_RESEND_MS) {
            continue;
        }
        lastGroupInviteRequestMs[key] = now;
        Tox_Err_Friend_Custom_Packet sendErr;
        tox_friend_send_lossless_packet(tox.get(), friendId, packet, sizeof(packet), &sendErr);
        if (sendErr == TOX_ERR_FRIEND_CUSTOM_PACKET_OK) {
            ++sent;
        }
    }
    if (sent > 0) {
        qDebug() << "requestGroupInviteFromFriends: asked" << sent << "online friends for an invite";
    }
}

/**
 * @brief When a friend comes online, re-send them any still-pending
 * friend-assisted join requests.
 */
void Core::resendPendingGroupInviteRequests(uint32_t friendId)
{
    std::ignore = friendId;
    const int64_t now = QDateTime::currentMSecsSinceEpoch();
    std::vector<QByteArray> stillPending;
    for (auto it = pendingFriendAssistedJoins.begin(); it != pendingFriendAssistedJoins.end();) {
        if (now - it->second > GROUP_INVITE_REQUEST_TTL_MS) {
            it = pendingFriendAssistedJoins.erase(it);
            continue;
        }
        stillPending.emplace_back(it->first.data(), static_cast<int>(it->first.size()));
        ++it;
    }
    for (const QByteArray& chatId : stillPending) {
        // Skip requests for groups that already have peers.
        Tox_Err_Group_State_Queries stateError;
        const uint32_t groupNum =
            tox_group_by_chat_id(tox.get(), reinterpret_cast<const uint8_t*>(chatId.constData()), &stateError);
        if (stateError == TOX_ERR_GROUP_STATE_QUERIES_OK && groupNum != std::numeric_limits<uint32_t>::max()) {
            Tox_Err_Group_Peer_Query peerError;
            const uint32_t peerCount = tox_group_peer_count(tox.get(), groupNum, &peerError);
            Tox_Err_Group_Is_Connected connError;
            const int32_t conn = tox_group_is_connected(tox.get(), groupNum, &connError);
            if (peerError == TOX_ERR_GROUP_PEER_QUERY_OK && connError == TOX_ERR_GROUP_IS_CONNECTED_OK
                && conn == 1 && peerCount > 1) {
                pendingFriendAssistedJoins.erase(
                    std::string(chatId.constData(), static_cast<size_t>(chatId.size())));
                continue;
            }
        }
        requestGroupInviteFromFriends(chatId);
    }
}

/**
 * @brief A friend asks us to invite them into a group (custom packet 184).
 */
void Core::handleGroupInviteRequestPacket(uint32_t friendId, const uint8_t* data, size_t length)
{
    if (length != 2 + TOX_GROUP_CHAT_ID_SIZE || data[1] != GROUP_INVITE_REQUEST_VERSION) {
        return;
    }

    const uint8_t* chatId = data + 2;
    Tox_Err_Group_State_Queries stateError;
    const uint32_t groupNum = tox_group_by_chat_id(tox.get(), chatId, &stateError);
    if (stateError != TOX_ERR_GROUP_STATE_QUERIES_OK || groupNum == std::numeric_limits<uint32_t>::max()) {
        return; // we are not in that group — silently ignore
    }

    const int64_t now = QDateTime::currentMSecsSinceEpoch();
    const uint64_t key = friendGroupKey(friendId, groupNum);
    const auto it = lastGroupInviteReplyMs.find(key);
    if (it != lastGroupInviteReplyMs.end() && now - it->second < GROUP_INVITE_REPLY_MIN_INTERVAL_MS) {
        return;
    }
    lastGroupInviteReplyMs[key] = now;

    qDebug() << "handleGroupInviteRequestPacket: friend" << friendId
             << "asked for an invite to group" << groupNum << "- sending invite";
    Tox_Err_Group_Invite_Friend inviteError;
    tox_group_invite_friend(tox.get(), groupNum, friendId, &inviteError);
    PARSE_ERR(inviteError);
}

/**
 * @brief Auto-accept an incoming NGC invite that we requested ourselves
 * (friend-assisted join). Returns true if the invite was consumed.
 */
bool Core::autoAcceptRequestedGroupInvite(const GroupInvite& inviteInfo)
{
    const QByteArray& invite = inviteInfo.getInvite();
    if (invite.size() < TOX_GROUP_CHAT_ID_SIZE) {
        return false;
    }
    const std::string chatIdKey(invite.constData(), TOX_GROUP_CHAT_ID_SIZE);
    const auto pending = pendingFriendAssistedJoins.find(chatIdKey);
    if (pending == pendingFriendAssistedJoins.end()) {
        return false;
    }
    const int64_t now = QDateTime::currentMSecsSinceEpoch();
    if (now - pending->second > GROUP_INVITE_REQUEST_TTL_MS) {
        pendingFriendAssistedJoins.erase(pending);
        return false;
    }

    const uint8_t* chatId = reinterpret_cast<const uint8_t*>(invite.constData());
    Tox_Err_Group_State_Queries stateError;
    const uint32_t existingNum = tox_group_by_chat_id(tox.get(), chatId, &stateError);
    if (stateError == TOX_ERR_GROUP_STATE_QUERIES_OK && existingNum != std::numeric_limits<uint32_t>::max()) {
        Tox_Err_Group_Peer_Query peerError;
        const uint32_t peerCount = tox_group_peer_count(tox.get(), existingNum, &peerError);
        Tox_Err_Group_Is_Connected connError;
        const int32_t conn = tox_group_is_connected(tox.get(), existingNum, &connError);
        if (peerError == TOX_ERR_GROUP_PEER_QUERY_OK && connError == TOX_ERR_GROUP_IS_CONNECTED_OK
            && conn == 1 && peerCount > 1) {
            // DHT path already succeeded in the meantime — nothing to do.
            pendingFriendAssistedJoins.erase(chatIdKey);
            return true;
        }
        // A peerless instance from tox_group_join would clash with the invite
        // accept (which creates a fresh instance) — drop it first. toxcore
        // reuses the freed slot, so the accept normally gets the same number.
        qDebug() << "autoAcceptRequestedGroupInvite: replacing stuck group instance" << existingNum;
        Tox_Err_Group_Leave leaveError;
        tox_group_leave(tox.get(), existingNum, nullptr, 0, &leaveError);
        PARSE_ERR(leaveError);

        const uint32_t newGroupNum = joinGroupchat(inviteInfo);
        if (newGroupNum == std::numeric_limits<uint32_t>::max()) {
            qWarning() << "autoAcceptRequestedGroupInvite: invite accept failed";
            return true; // consumed anyway; don't show UI for a requested invite
        }
        if (newGroupNum != existingNum) {
            qWarning() << "autoAcceptRequestedGroupInvite: group number changed" << existingNum
                       << "->" << newGroupNum;
        }
        pendingFriendAssistedJoins.erase(chatIdKey);
        return true;
    }

    const uint32_t newGroupNum = joinGroupchat(inviteInfo);
    if (newGroupNum == std::numeric_limits<uint32_t>::max()) {
        qWarning() << "autoAcceptRequestedGroupInvite: invite accept failed";
        return true; // consumed anyway; don't show UI for a requested invite
    }
    pendingFriendAssistedJoins.erase(chatIdKey);
    qDebug() << "autoAcceptRequestedGroupInvite: joined group" << newGroupNum << "via friend invite";
    return true;
}

int Core::createGroup(Tox_Group_Privacy_State privacyState)
{
    QMutexLocker ml{&coreLoopLock};

    const QString groupName = tr("New group");
    QString selfName = getUsername();
    if (selfName.isEmpty()) {
        selfName = tr("User");
    }
    ToxString cGroupName(groupName);
    ToxString cSelfName(selfName);

    Tox_Err_Group_New error;
    const uint32_t groupId = tox_group_new(tox.get(), privacyState, cGroupName.data(), cGroupName.size(),
                                           cSelfName.data(), cSelfName.size(), &error);
    if (!PARSE_ERR(error) || groupId == std::numeric_limits<uint32_t>::max()) {
        return -1;
    }

    emit saveRequest();
    reconnectGroupIfDisconnected(static_cast<uint32_t>(groupId));
    emit emptyGroupCreated(groupId, getGroupPersistentId(groupId), groupName);
    return static_cast<int>(groupId);
}

/**
 * @brief Checks if a friend is online. Unknown friends are considered offline.
 */
bool Core::isFriendOnline(uint32_t friendId) const
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Friend_Query error;
    Tox_Connection connection = tox_friend_get_connection_status(tox.get(), friendId, &error);
    PARSE_ERR(error);
    return connection != TOX_CONNECTION_NONE;
}

/**
 * @brief Checks if we have a friend by public key
 */
bool Core::hasFriendWithPublicKey(const ToxPk& publicKey) const
{
    QMutexLocker ml{&coreLoopLock};

    if (publicKey.isEmpty()) {
        return false;
    }

    Tox_Err_Friend_By_Public_Key error;
    (void)tox_friend_by_public_key(tox.get(), publicKey.getData(), &error);
    return PARSE_ERR(error);
}

/**
 * @brief Get the public key part of the ToxID only
 */
ToxPk Core::getFriendPublicKey(uint32_t friendNumber) const
{
    QMutexLocker ml{&coreLoopLock};

    uint8_t rawid[TOX_PUBLIC_KEY_SIZE];
    Tox_Err_Friend_Get_Public_Key error;
    tox_friend_get_public_key(tox.get(), friendNumber, rawid, &error);
    if (!PARSE_ERR(error)) {
        qWarning() << "getFriendPublicKey: Getting public key failed";
        return ToxPk();
    }

    return ToxPk(rawid);
}

/**
 * @brief Get the username of a friend
 */
QString Core::getFriendUsername(uint32_t friendnumber) const
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Friend_Query error;
    size_t nameSize = tox_friend_get_name_size(tox.get(), friendnumber, &error);
    if (!PARSE_ERR(error) || !nameSize) {
        return QString();
    }

    std::vector<uint8_t> nameBuf(nameSize);
    tox_friend_get_name(tox.get(), friendnumber, nameBuf.data(), &error);
    if (!PARSE_ERR(error)) {
        return QString();
    }
    return ToxString(nameBuf.data(), nameSize).getQString();
}

uint64_t Core::getMaxMessageSize() const
{
    /*
     * TODO: Remove this hack; the reported max message length we receive from c-toxcore
     * as of 08-02-2019 is inaccurate, causing us to generate too large messages when splitting
     * them up.
     *
     * The inconsistency lies in c-toxcore group.c:2480 using MAX_GROUP_MESSAGE_DATA_LEN to verify
     * message size is within limit, but tox_max_message_length giving a different size limit to us.
     *
     * (uint32_t tox_max_message_length(void); declared in tox.h, unable to see explicit definition)
     */
    return tox_max_message_length() - 50;
}

QString Core::getPeerName(const ToxPk& id) const
{
    QMutexLocker ml{&coreLoopLock};

    Tox_Err_Friend_By_Public_Key keyError;
    uint32_t friendId = tox_friend_by_public_key(tox.get(), id.getData(), &keyError);
    if (!PARSE_ERR(keyError)) {
        qWarning() << "getPeerName: No such peer";
        return {};
    }

    Tox_Err_Friend_Query queryError;
    const size_t nameSize = tox_friend_get_name_size(tox.get(), friendId, &queryError);
    if (!PARSE_ERR(queryError) || !nameSize) {
        return {};
    }

    std::vector<uint8_t> nameBuf(nameSize);
    tox_friend_get_name(tox.get(), friendId, nameBuf.data(), &queryError);
    if (!PARSE_ERR(queryError)) {
        qWarning() << "getPeerName: Can't get name of friend " + QString().setNum(friendId);
        return {};
    }

    return ToxString(nameBuf.data(), nameSize).getQString();
}

/**
 * @brief Sets the NoSpam value to prevent friend request spam
 * @param nospam an arbitrary which becomes part of the Tox ID
 */
void Core::setNospam(uint32_t nospam)
{
    QMutexLocker ml{&coreLoopLock};

    tox_self_set_nospam(tox.get(), nospam);
    emit idSet(getSelfId());
}
