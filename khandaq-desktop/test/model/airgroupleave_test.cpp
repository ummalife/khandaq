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

#include "src/model/air/airgroupleave.h"

#include <QtTest/QtTest>

using namespace Air;

namespace {
bool isNotice(const QByteArray& packet)
{
    return isGroupLeaveNotice(reinterpret_cast<const uint8_t*>(packet.constData()),
                              static_cast<size_t>(packet.size()));
}
} // namespace

// QA live F11: a member that leaves a group says so first, since toxcore's QUIT is the same when its
// app closes. Android and iOS read the same eight bytes.
class TestAirGroupLeave : public QObject
{
    Q_OBJECT
private slots:
    void theNoticeIsTheNgcHeaderWithId0x44();
    void laterBytesAfterTheHeaderAreNotRead();
    void otherNgcPacketsAreNotANotice();
};

void TestAirGroupLeave::theNoticeIsTheNgcHeaderWithId0x44()
{
    QCOMPARE(groupLeaveNoticePacket(), QByteArray::fromHex("6677881134350144"));
    QVERIFY(isNotice(groupLeaveNoticePacket()));
}

void TestAirGroupLeave::laterBytesAfterTheHeaderAreNotRead()
{
    QVERIFY(isNotice(groupLeaveNoticePacket() + QByteArray::fromHex("0001")));
}

void TestAirGroupLeave::otherNgcPacketsAreNotANotice()
{
    QVERIFY(!isGroupLeaveNotice(nullptr, 8));
    QVERIFY(!isNotice(groupLeaveNoticePacket().left(7)));
    for (const char id : {'\x01', '\x11', '\x21', '\x31', '\x41', '\x42', '\x43', '\x45', '\x50'}) {
        QByteArray other = groupLeaveNoticePacket();
        other[7] = id;
        QVERIFY2(!isNotice(other), qPrintable(QString::number(static_cast<uint8_t>(id), 16)));
    }
    QByteArray version2 = groupLeaveNoticePacket();
    version2[6] = '\x02';
    QVERIFY(!isNotice(version2));
}

QTEST_GUILESS_MAIN(TestAirGroupLeave)
#include "airgroupleave_test.moc"
