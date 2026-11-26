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

#include "src/model/air/airgroupcontact.h"

#include "src/model/air/airlocalstore.h"
#include "src/model/air/airmessagemarks.h"

#include <QtTest/QtTest>

using namespace Air;

namespace {
const QByteArray magic = QByteArray::fromHex("667788113435");
const QByteArray nonce = QByteArray::fromHex("0123456789ABCDEF");

/** A Tox address as toxcore makes it: key, nospam, and the XOR of their byte pairs. */
QByteArray address(char keyByte = '\x5A', const QByteArray& nospam = QByteArray::fromHex("DEADBEEF"))
{
    QByteArray bytes(32, keyByte);
    bytes.append(nospam);
    char check[2] = {0, 0};
    for (int i = 0; i < bytes.size(); ++i) {
        check[i % 2] = static_cast<char>(check[i % 2] ^ bytes.at(i));
    }
    bytes.append(check, 2);
    return bytes;
}

bool read(const QByteArray& packet, GroupContactPacket* out = nullptr)
{
    GroupContactPacket ignored;
    return readGroupContactPacket(reinterpret_cast<const uint8_t*>(packet.constData()),
                                  static_cast<size_t>(packet.size()), out ? out : &ignored);
}

QString hexOf(const QByteArray& bytes)
{
    return QString::fromLatin1(bytes.toHex().toUpper());
}

const QString groupKey = QString(64, QLatin1Char('6'));
const QString otherGroupKey = QString(64, QLatin1Char('7'));
const QString memberKey = QString(64, QLatin1Char('8'));
const QDateTime now = QDateTime::fromMSecsSinceEpoch(1800000000000);

LocalStore::WaitingRequest groupOffer(const QByteArray& from = address())
{
    LocalStore::WaitingRequest offer;
    offer.address = hexOf(from);
    offer.groupKey = groupKey;
    offer.groupName = QStringLiteral("Family");
    offer.memberKey = memberKey;
    offer.memberName = QStringLiteral("Maryam");
    offer.nonce = nonce;
    return offer;
}

LocalStore reloaded(const LocalStore& store)
{
    LocalStore copy;
    copy.load(store.save(), now);
    return copy;
}

bool routed(const QByteArray& packet)
{
    return isGroupContactPacket(reinterpret_cast<const uint8_t*>(packet.constData()),
                                static_cast<size_t>(packet.size()));
}
} // namespace

class TestAirGroupContact : public QObject
{
    Q_OBJECT
private slots:
    void offerIsLaidOutAsTheDesignSays();
    void acceptCarriesItsOwnId();
    void noPacketForABrokenAddressOrNonce();
    void readsBackWhatItBuilds();
    void dropsAnyOtherLength();
    void dropsABrokenChecksum();
    void dropsAnotherMagicVersionOrId();
    void routesOnlyItsOwnIds();
    void nonceIsEightFreshBytes();
    void markerOpensTheRequest();
    void readsTheMarkerBack();
    void plainRequestHasNoMarker();
    void gateTakesOneOfferAMinute();
    void gateKeepsMembersApart();
    void offerOfAMemberWaitsAsARequest();
    void offerOfAMemberIsCheckedAsItComes();
    void ownOffersWaitForTheirAccept();
    void membersBehindContactsAreKept();
    void privateEntriesAreMarkedOnThisDevice();
};

void TestAirGroupContact::offerIsLaidOutAsTheDesignSays()
{
    const QByteArray packet = groupContactPacket(GroupContactPacket::Kind::Offer, address(), nonce);
    QCOMPARE(packet.size(), 54);
    QCOMPARE(packet.left(6), magic);
    QCOMPARE(packet.at(6), '\x01');
    QCOMPARE(packet.at(7), '\x61');
    QCOMPARE(packet.mid(8, 38), address());
    QCOMPARE(packet.mid(46), nonce);
}

void TestAirGroupContact::acceptCarriesItsOwnId()
{
    const QByteArray packet = groupContactPacket(GroupContactPacket::Kind::Accept, address(), nonce);
    QCOMPARE(packet.size(), 54);
    QCOMPARE(packet.at(7), '\x62');
}

void TestAirGroupContact::noPacketForABrokenAddressOrNonce()
{
    QByteArray broken = address();
    broken[37] = static_cast<char>(broken.at(37) ^ 0x01);
    QVERIFY(groupContactPacket(GroupContactPacket::Kind::Offer, broken, nonce).isEmpty());
    QVERIFY(groupContactPacket(GroupContactPacket::Kind::Offer, address().left(32), nonce).isEmpty());
    QVERIFY(groupContactPacket(GroupContactPacket::Kind::Offer, address(), nonce.left(7)).isEmpty());
}

void TestAirGroupContact::readsBackWhatItBuilds()
{
    GroupContactPacket packet;
    QVERIFY(read(groupContactPacket(GroupContactPacket::Kind::Offer, address('\x11'), nonce), &packet));
    QVERIFY(packet.kind == GroupContactPacket::Kind::Offer);
    QCOMPARE(packet.address, address('\x11'));
    QCOMPARE(packet.nonce, nonce);

    QVERIFY(read(groupContactPacket(GroupContactPacket::Kind::Accept, address('\x22'), nonce), &packet));
    QVERIFY(packet.kind == GroupContactPacket::Kind::Accept);
    QCOMPARE(packet.address, address('\x22'));
}

void TestAirGroupContact::dropsAnyOtherLength()
{
    const QByteArray packet = groupContactPacket(GroupContactPacket::Kind::Offer, address(), nonce);
    QVERIFY(!read(packet.left(53)));
    QVERIFY(!read(packet + QByteArray(1, '\0')));
    QVERIFY(!read(packet.left(8)));
    QVERIFY(!read(QByteArray()));
}

void TestAirGroupContact::dropsABrokenChecksum()
{
    QByteArray packet = groupContactPacket(GroupContactPacket::Kind::Offer, address(), nonce);
    packet[8 + 36] = static_cast<char>(packet.at(8 + 36) ^ 0x40);
    QVERIFY(!read(packet));
    // A changed nospam no longer matches the checksum either.
    packet = groupContactPacket(GroupContactPacket::Kind::Offer, address(), nonce);
    packet[8 + 33] = static_cast<char>(packet.at(8 + 33) ^ 0x01);
    QVERIFY(!read(packet));
}

void TestAirGroupContact::dropsAnotherMagicVersionOrId()
{
    const QByteArray packet = groupContactPacket(GroupContactPacket::Kind::Offer, address(), nonce);
    QByteArray changed = packet;
    changed[0] = '\x67';
    QVERIFY(!read(changed));
    changed = packet;
    changed[6] = '\x02';
    QVERIFY(!read(changed));
    // 41 and 42 are the edit and the deletion of a message.
    for (const char id : {'\x41', '\x42', '\x60', '\x63'}) {
        changed = packet;
        changed[7] = id;
        QVERIFY(!read(changed));
    }
}

void TestAirGroupContact::routesOnlyItsOwnIds()
{
    const QByteArray packet = groupContactPacket(GroupContactPacket::Kind::Offer, address(), nonce);
    QVERIFY(routed(packet));
    QVERIFY(routed(packet.left(8)));
    QVERIFY(routed(packet + QByteArray(4, '\0')));
    QVERIFY(!routed(packet.left(7)));
    QByteArray other = packet;
    other[7] = '\x02';
    QVERIFY(!routed(other));
    other[7] = '\x41';
    QVERIFY(!routed(other));
}

void TestAirGroupContact::nonceIsEightFreshBytes()
{
    const QByteArray first = newContactOfferNonce();
    const QByteArray second = newContactOfferNonce();
    QCOMPARE(first.size(), 8);
    QCOMPARE(second.size(), 8);
    QVERIFY(first != second);
}

void TestAirGroupContact::markerOpensTheRequest()
{
    QCOMPARE(contactRequestMarker(nonce), QStringLiteral("[KQ|c|0123456789abcdef]"));
    QCOMPARE(contactRequestMessage(nonce, QStringLiteral("I accepted your request")),
             QStringLiteral("[KQ|c|0123456789abcdef]\nI accepted your request"));
}

void TestAirGroupContact::readsTheMarkerBack()
{
    QByteArray readNonce;
    QString words;
    QVERIFY(readContactRequestMarker(
        QStringLiteral("[KQ|c|0123456789abcdef]\nI accepted your request from the group"), &readNonce,
        &words));
    QCOMPARE(readNonce, nonce);
    QCOMPARE(words, QStringLiteral("I accepted your request from the group"));

    QVERIFY(readContactRequestMarker(QStringLiteral("[KQ|c|0123456789ABCDEF]"), &readNonce, &words));
    QCOMPARE(readNonce, nonce);
    QCOMPARE(words, QString());
}

void TestAirGroupContact::plainRequestHasNoMarker()
{
    QByteArray readNonce;
    QString words;
    const QString plain = QStringLiteral("Hi, add me");
    QVERIFY(!readContactRequestMarker(plain, &readNonce, &words));
    QCOMPARE(words, plain);
    for (const char* text : {"[KQ|c|0123456789abcde]\nshort", "[KQ|c|0123456789abcdeg]\nnot hex",
                             "[KQ|c|0123456789abcdef0]\nlong", "[KQ|m|0123456789abcdef]\nother"}) {
        QVERIFY2(!readContactRequestMarker(QString::fromLatin1(text), &readNonce, &words), text);
        QCOMPARE(words, QString::fromLatin1(text));
    }
}

void TestAirGroupContact::gateTakesOneOfferAMinute()
{
    ContactOfferGate gate;
    QVERIFY(gate.admit(QStringLiteral("g:A"), 1000));
    QVERIFY(!gate.admit(QStringLiteral("g:A"), 1000 + 59999));
    QVERIFY(gate.admit(QStringLiteral("g:A"), 1000 + 60000));
    // A refused offer does not start the gap again; a taken one does.
    QVERIFY(!gate.admit(QStringLiteral("g:A"), 1000 + 60000 + 30000));
    QVERIFY(gate.admit(QStringLiteral("g:A"), 1000 + 120000));
}

void TestAirGroupContact::gateKeepsMembersApart()
{
    ContactOfferGate gate;
    QVERIFY(gate.admit(QStringLiteral("g:A"), 0));
    QVERIFY(gate.admit(QStringLiteral("g:B"), 1));
    QVERIFY(gate.admit(QStringLiteral("h:A"), 2));
    QVERIFY(!gate.admit(QStringLiteral("g:B"), 3));
}

void TestAirGroupContact::offerOfAMemberWaitsAsARequest()
{
    LocalStore store;
    QVERIFY(store.addGroupRequest(groupOffer(), now));
    // The same offer again is not news; a new one from the key takes its place.
    QVERIFY(!store.addGroupRequest(groupOffer(), now));
    LocalStore::WaitingRequest again = groupOffer();
    again.nonce = QByteArray::fromHex("1111111111111111");
    QVERIFY(store.addGroupRequest(again, now.addSecs(5)));
    QCOMPARE(store.requests().size(), 1);

    const LocalStore::WaitingRequest kept = reloaded(store).request(hexOf(address()).left(64));
    QVERIFY(kept.fromGroup());
    QCOMPARE(kept.publicKey, hexOf(address()).left(64));
    QCOMPARE(kept.address, hexOf(address()));
    QCOMPARE(kept.groupKey, groupKey);
    QCOMPARE(kept.groupName, QStringLiteral("Family"));
    QCOMPARE(kept.memberKey, memberKey);
    QCOMPARE(kept.memberName, QStringLiteral("Maryam"));
    QCOMPARE(kept.nonce, again.nonce);
    QCOMPARE(kept.date, now.addSecs(5).toMSecsSinceEpoch());
    // A Tox request from the key takes the place of the offer.
    QVERIFY(store.addRequest(kept.publicKey, QStringLiteral("Hi"), now.addSecs(9)));
    QVERIFY(!store.request(kept.publicKey).fromGroup());
}

void TestAirGroupContact::offerOfAMemberIsCheckedAsItComes()
{
    LocalStore store;
    LocalStore::WaitingRequest offer = groupOffer();
    offer.address = hexOf(address()).left(74) + QStringLiteral("00");
    QVERIFY(!store.addGroupRequest(offer, now));
    offer = groupOffer();
    offer.memberKey = QStringLiteral("not a key");
    QVERIFY(!store.addGroupRequest(offer, now));
    offer = groupOffer();
    offer.nonce = nonce.left(4);
    QVERIFY(!store.addGroupRequest(offer, now));
    QVERIFY(store.block(hexOf(address()).left(64)));
    QVERIFY(!store.addGroupRequest(groupOffer(), now));
    QVERIFY(store.requests().isEmpty());
}

void TestAirGroupContact::ownOffersWaitForTheirAccept()
{
    LocalStore store;
    LocalStore::ContactOffer offer;
    offer.groupKey = groupKey.toLower();
    offer.memberKey = memberKey;
    offer.memberName = QStringLiteral("Maryam");
    offer.nonce = nonce;
    offer.date = now.toMSecsSinceEpoch();
    QVERIFY(store.keepContactOffer(offer, now));
    QCOMPARE(store.contactOffer(nonce).groupKey, groupKey);
    QVERIFY(store.contactOffer(QByteArray::fromHex("0000000000000000")).isNull());

    // A new offer to the same member takes the place of the old one.
    LocalStore::ContactOffer newer = offer;
    newer.nonce = QByteArray::fromHex("2222222222222222");
    newer.date += 1000;
    QVERIFY(store.keepContactOffer(newer, now));
    QVERIFY(store.contactOffer(nonce).isNull());
    QCOMPARE(reloaded(store).contactOffers().size(), 1);
    QCOMPARE(reloaded(store).contactOffer(newer.nonce).memberName, QStringLiteral("Maryam"));

    // Thirty days, then it goes; the group left takes it too.
    QVERIFY(!store.pruneContactOffers(now.addDays(29)));
    QVERIFY(store.pruneContactOffers(now.addDays(31)));
    QVERIFY(store.contactOffers().isEmpty());
    QVERIFY(store.keepContactOffer(newer, now));
    QVERIFY(store.forgetGroup(groupKey));
    QVERIFY(store.contactOffers().isEmpty());
    // An offer too old, or with a broken nonce, is not kept.
    QVERIFY(!store.keepContactOffer(offer, now.addDays(31)));
    offer.nonce = nonce.left(7);
    QVERIFY(!store.keepContactOffer(offer, now));
    QVERIFY(store.dropContactOffer(QByteArray()) == false);
}

void TestAirGroupContact::membersBehindContactsAreKept()
{
    LocalStore store;
    LocalStore::MemberContact member;
    member.groupKey = groupKey;
    member.memberKey = memberKey;
    member.contactKey = hexOf(address()).left(64);
    member.memberName = QStringLiteral("Maryam");
    QVERIFY(store.keepMemberContact(member));
    QVERIFY(!store.keepMemberContact(member));
    // The whole ID that comes later fills the member kept without it.
    member.address = hexOf(address());
    QVERIFY(store.keepMemberContact(member));
    LocalStore::MemberContact keyOnly = member;
    keyOnly.address.clear();
    keyOnly.memberName.clear();
    QVERIFY(!store.keepMemberContact(keyOnly));
    QCOMPARE(store.memberContact(groupKey, memberKey).address, hexOf(address()));
    QCOMPARE(reloaded(store).memberContact(groupKey, memberKey), member);
    QCOMPARE(reloaded(store).memberOfContact(member.contactKey).memberName, QStringLiteral("Maryam"));
    QVERIFY(store.memberContact(otherGroupKey, memberKey).isNull());

    // An address of another key, or a broken one, is not kept.
    LocalStore::MemberContact wrong = member;
    wrong.address = hexOf(address(''));
    QVERIFY(!store.keepMemberContact(wrong));
    wrong.address = hexOf(address()).left(75) + QStringLiteral("0");
    QVERIFY(!store.keepMemberContact(wrong));

    // A removed contact takes the member along, and so does a left group.
    QVERIFY(store.forget(member.contactKey));
    QVERIFY(store.memberContact(groupKey, memberKey).isNull());
    QVERIFY(store.keepMemberContact(member));
    QVERIFY(store.forgetGroup(groupKey));
    QVERIFY(store.memberOfContact(member.contactKey).isNull());
}

void TestAirGroupContact::privateEntriesAreMarkedOnThisDevice()
{
    LocalStore store;
    const QString entry = textEntryKey(groupKey, memberKey, 1700000000123);
    PrivateEntry mark;
    mark.peerKey = memberKey.toLower();
    mark.peerName = QStringLiteral("Maryam");
    mark.ours = true;
    QVERIFY(store.markPrivateEntry(entry, mark));
    QVERIFY(!store.markPrivateEntry(entry, mark));
    QVERIFY(store.privateEntry(textEntryKey(groupKey, memberKey, 1700000000124)).isNull());
    const PrivateEntry kept = reloaded(store).privateEntry(entry);
    QCOMPARE(kept.peerKey, memberKey);
    QCOMPARE(kept.peerName, QStringLiteral("Maryam"));
    QVERIFY(kept.ours);
    QVERIFY(!store.markPrivateEntry(QStringLiteral("not an entry"), mark));
    mark.peerKey = QStringLiteral("short");
    QVERIFY(!store.markPrivateEntry(entry, mark));

    // The group left takes its marks along; an erased history takes them all.
    mark.peerKey = memberKey;
    const QString elsewhere = textEntryKey(otherGroupKey, memberKey, 1700000000123);
    QVERIFY(store.markPrivateEntry(elsewhere, mark));
    QVERIFY(store.forgetGroup(groupKey));
    QVERIFY(store.privateEntry(entry).isNull());
    QVERIFY(!store.privateEntry(elsewhere).isNull());
    QVERIFY(store.forgetEntries());
    QVERIFY(store.privateEntry(elsewhere).isNull());
}

QTEST_GUILESS_MAIN(TestAirGroupContact)
#include "airgroupcontact_test.moc"
