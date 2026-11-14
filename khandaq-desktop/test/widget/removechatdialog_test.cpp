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

#include "src/widget/tool/removechatdialog.h"
#include "src/friendlist.h"
#include "src/model/friend.h"
#include "src/model/group.h"
#include "src/widget/air/airstrings.h"

#include "mock/mockcoreidhandler.h"
#include "mock/mockgroupquery.h"

#include <QApplication>
#include <QCheckBox>
#include <QDialogButtonBox>
#include <QLabel>
#include <QPushButton>
#include <QtTest/QtTest>

using AirStrings::Text;

namespace {
QString question(RemoveChatDialog& dialog)
{
    return dialog.findChild<QLabel*>(QStringLiteral("label"))->text();
}

QString acceptText(RemoveChatDialog& dialog)
{
    return dialog.findChild<QDialogButtonBox*>(QStringLiteral("buttonBox"))
        ->button(QDialogButtonBox::Ok)
        ->text();
}
} // namespace

class TestRemoveChatDialog : public QObject
{
    Q_OBJECT
private slots:
    void aGroupIsLeftNotRemovedFromTheContacts();
    void aFriendIsRemovedFromTheContacts();
};

/**
 * @brief The list's Leave group asks in the words of the group dossier, never of a contact.
 */
void TestRemoveChatDialog::aGroupIsLeftNotRemovedFromTheContacts()
{
    MockGroupQuery groupQuery;
    MockCoreIdHandler idHandler;
    FriendList friendList;
    Group group(0, GroupId(0), QStringLiteral("QA <receipts>"), false, QStringLiteral("selfName"),
                groupQuery, idHandler, friendList);
    RemoveChatDialog dialog(nullptr, group);

    QCOMPARE(dialog.windowTitle(), AirStrings::text(Text::GroupLeave));
    const QString asked = question(dialog);
    QVERIFY(asked.contains(AirStrings::text(Text::GroupLeaveTitle)
                               .arg(QStringLiteral("<b>QA &lt;receipts&gt;</b>"))));
    QVERIFY(asked.contains(AirStrings::text(Text::GroupLeaveMessage)));
    QVERIFY(!asked.contains(QStringLiteral("contacts")));
    QCOMPARE(acceptText(dialog), AirStrings::text(Text::GroupLeaveVerb));
    const auto* history = dialog.findChild<QCheckBox*>(QStringLiteral("removeHistory"));
    QVERIFY(history->accessibleDescription().isEmpty());
    QVERIFY(!dialog.accepted());
}

void TestRemoveChatDialog::aFriendIsRemovedFromTheContacts()
{
    Friend contact(0, ToxPk());
    contact.setName(QStringLiteral("friendName"));
    RemoveChatDialog dialog(nullptr, contact);

    QCOMPARE(dialog.windowTitle(), QStringLiteral("Remove friend"));
    const QString asked = question(dialog);
    QVERIFY(asked.contains(QStringLiteral("remove <b>friendName</b>")));
    QVERIFY(asked.contains(QStringLiteral("from your contacts list?")));
    QCOMPARE(acceptText(dialog), QStringLiteral("Remove"));
    const auto* history = dialog.findChild<QCheckBox*>(QStringLiteral("removeHistory"));
    QCOMPARE(history->accessibleDescription(),
             QStringLiteral("Remove all chat history with the friend if set"));
}

int main(int argc, char* argv[])
{
    // The widgets need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QApplication app(argc, argv);
    TestRemoveChatDialog test;
    return QTest::qExec(&test, argc, argv);
}

#include "removechatdialog_test.moc"
