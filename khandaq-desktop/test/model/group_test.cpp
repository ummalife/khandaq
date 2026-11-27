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

#include "src/model/group.h"

#include "src/core/groupid.h"
#include "src/friendlist.h"
#include "src/model/air/aircommunity.h"
#include "src/model/notificationgenerator.h"
#include "src/model/systemmessage.h"

#include "mock/mockcoreidhandler.h"
#include "mock/mockgroupquery.h"

#include <QSignalSpy>
#include <QtTest/QtTest>

#include <memory>

namespace {
/** The topic of the founder that iOS and Android showed as the name of the group. */
const QString founderTopic =
    QStringLiteral("Official Khandaq community \u00B7 Be kind, no spam or ads");
const QString communityName = QStringLiteral("Khandaq Community");

class QuietNotificationSettings : public INotificationSettings
{
public:
    bool getNotify() const override { return true; }
    void setNotify(bool newValue) override { std::ignore = newValue; }
    bool getShowWindow() const override { return true; }
    void setShowWindow(bool newValue) override { std::ignore = newValue; }
    bool getDesktopNotify() const override { return true; }
    void setDesktopNotify(bool enabled) override { std::ignore = enabled; }
    bool getNotifySound() const override { return true; }
    void setNotifySound(bool newValue) override { std::ignore = newValue; }
    bool getNotifyHide() const override { return false; }
    void setNotifyHide(bool newValue) override { std::ignore = newValue; }
    bool getBusySound() const override { return true; }
    void setBusySound(bool newValue) override { std::ignore = newValue; }
    bool getGroupAlwaysNotify() const override { return true; }
    void setGroupAlwaysNotify(bool newValue) override { std::ignore = newValue; }
};
} // namespace

/**
 * KHANDAQ (0.2.57): the group model keeps the name of Khandaq Community, so every place that names
 * a group by it (the row, the chat, the dossier, notifications, the search, Report) says it.
 */
class TestGroup : public QObject
{
    Q_OBJECT
private slots:
    void init();
    void theCommunityStartsWithItsNameWhateverItCameWith();
    void neitherTopicNorRenameChangesTheCommunity();
    void otherGroupsTakeTheirTitleAsBefore();
    void aRenameChangesTheTitleOnceToxcoreTookIt();
    void aNotificationNamesTheCommunity();

private:
    std::unique_ptr<FriendList> friendList;
    std::unique_ptr<MockGroupQuery> groupQuery;
    std::unique_ptr<MockCoreIdHandler> coreIdHandler;
};

void TestGroup::init()
{
    friendList.reset(new FriendList());
    groupQuery.reset(new MockGroupQuery());
    coreIdHandler.reset(new MockCoreIdHandler());
}

void TestGroup::theCommunityStartsWithItsNameWhateverItCameWith()
{
    // Core::loadGroups names a saved group by its topic; a group joined before its state came
    // is "Group" and the start of its key.
    for (const QString& title : {founderTopic, QStringLiteral("Group CFF1995F"), QString()}) {
        Group community(0, GroupId(Air::Community::chatId()), title, false, "self", *groupQuery,
                        *coreIdHandler, *friendList);
        QCOMPARE(community.getName(), communityName);
        QCOMPARE(community.getDisplayedName(), communityName);
    }
}

void TestGroup::neitherTopicNorRenameChangesTheCommunity()
{
    Group community(0, GroupId(Air::Community::chatId()), communityName, false, "self",
                    *groupQuery, *coreIdHandler, *friendList);
    QSignalSpy shown(&community, &Group::displayedNameChanged);
    QSignalSpy changed(&community, &Group::titleChanged);
    QSignalSpy renamed(&community, &Group::titleChangedByUser);

    // A topic that comes, or the name the state brings: nothing moves, no system line is written.
    community.setTitle(QStringLiteral("Khandaq"), founderTopic);
    community.setTitle(QString(), QStringLiteral("Something else"));
    // A rename here would set the topic of the group (Widget::titleChangedByUser): none goes.
    community.setName(QStringLiteral("My group"));

    QCOMPARE(community.getName(), communityName);
    QCOMPARE(shown.count(), 0);
    QCOMPARE(changed.count(), 0);
    QCOMPARE(renamed.count(), 0);
}

void TestGroup::otherGroupsTakeTheirTitleAsBefore()
{
    const GroupId otherId(QByteArray(32, '\x0b'));
    Group other(1, otherId, founderTopic, false, "self", *groupQuery, *coreIdHandler, *friendList);
    QCOMPARE(other.getName(), founderTopic);

    QSignalSpy changed(&other, &Group::titleChanged);
    QSignalSpy renamed(&other, &Group::titleChangedByUser);
    other.setTitle(QStringLiteral("Yusuf"), QStringLiteral("Family"));
    QCOMPARE(other.getName(), QStringLiteral("Family"));
    QCOMPARE(changed.count(), 1);
    QCOMPARE(changed.at(0).at(0).toString(), QStringLiteral("Yusuf"));

    // KHANDAQ (inbox #13, 27.11): a rename asks toxcore for the topic, and the title changes once
    // it took it (Core::changeGroupTitle).
    other.setName(QStringLiteral("Our family"));
    QCOMPARE(other.getName(), QStringLiteral("Family"));
    QCOMPARE(renamed.count(), 1);
    QCOMPARE(renamed.at(0).at(0).toString(), QStringLiteral("Our family"));
    other.setTitle(QStringLiteral("self"), QStringLiteral("Our family"));
    QCOMPARE(other.getName(), QStringLiteral("Our family"));

    // An empty title changes nothing, as before.
    other.setTitle(QString(), QString());
    QCOMPARE(other.getName(), QStringLiteral("Our family"));
}

/**
 * KHANDAQ (inbox #13, 27.11): a group has one name. A rename asks toxcore for it, and the title
 * changes only once toxcore took it, with the line of the rename under the name the member has in
 * the group, once. A rename toxcore refused leaves the title as it was and writes nothing. Khandaq
 * Community, whose name never changes, asks for none and takes no topic, name or join.
 */
void TestGroup::aRenameChangesTheTitleOnceToxcoreTookIt()
{
    // As GroupChatForm::onTitleChanged writes the line.
    const auto line = [](const QList<QVariant>& change) -> QString {
        SystemMessage message;
        message.messageType = SystemMessageType::titleChanged;
        message.args[0] = change.at(0).toString();
        message.args[1] = change.at(1).toString();
        return message.toString();
    };

    const GroupId groupId(QByteArray(32, '\x0c'));
    Group group(2, groupId, QStringLiteral("Book club"), false, QStringLiteral("Amina"),
                *groupQuery, *coreIdHandler, *friendList);
    QSignalSpy shown(&group, &Group::displayedNameChanged);
    QSignalSpy changed(&group, &Group::titleChanged);
    QSignalSpy asked(&group, &Group::titleChangedByUser);
    group.setName(QStringLiteral("Reading circle"));
    QCOMPARE(asked.count(), 1);
    QCOMPARE(asked.at(0).at(0).toString(), QStringLiteral("Reading circle"));
    QCOMPARE(group.getName(), QStringLiteral("Book club"));
    QCOMPARE(shown.count(), 0);
    QCOMPARE(changed.count(), 0);

    // Toxcore took it: Core::changeGroupTitle gives the title under our name in the group.
    group.setTitle(QStringLiteral("Amina"), QStringLiteral("Reading circle"));
    QCOMPARE(group.getName(), QStringLiteral("Reading circle"));
    QCOMPARE(shown.count(), 1);
    QCOMPARE(changed.count(), 1);
    QCOMPARE(line(changed.at(0)), QStringLiteral("Amina renamed the group to Reading circle"));
    // The same title again, as the join of a member brings it: no second line.
    group.setTitle(QString(), QStringLiteral("Reading circle"));
    QCOMPARE(shown.count(), 1);
    QCOMPARE(changed.count(), 1);

    // Refused (Core::groupTitleChangeFailed): nothing follows the request, and the title stays.
    group.setName(QStringLiteral("Our circle"));
    QCOMPARE(asked.count(), 2);
    QCOMPARE(group.getName(), QStringLiteral("Reading circle"));
    QCOMPARE(shown.count(), 1);
    QCOMPARE(changed.count(), 1);

    Group community(0, GroupId(Air::Community::chatId()), communityName, false,
                    QStringLiteral("Amina"), *groupQuery, *coreIdHandler, *friendList);
    QSignalSpy communityShown(&community, &Group::displayedNameChanged);
    QSignalSpy communityChanged(&community, &Group::titleChanged);
    QSignalSpy communityAsked(&community, &Group::titleChangedByUser);
    // A rename, a topic a member set, and the name the join of a member brings.
    community.setName(QStringLiteral("Reading circle"));
    community.setTitle(QStringLiteral("Amina"), QStringLiteral("Reading circle"));
    community.setTitle(QString(), QStringLiteral("Book club"));
    QCOMPARE(community.getName(), communityName);
    QCOMPARE(communityShown.count(), 0);
    QCOMPARE(communityChanged.count(), 0);
    QCOMPARE(communityAsked.count(), 0);
}

void TestGroup::aNotificationNamesTheCommunity()
{
    QuietNotificationSettings settings;
    NotificationGenerator generator(settings, nullptr);
    Group community(0, GroupId(Air::Community::chatId()), founderTopic, false, "self",
                    *groupQuery, *coreIdHandler, *friendList);
    community.setTitle(QStringLiteral("Khandaq"), founderTopic);
    const ToxPk sender = groupQuery->getGroupPeerPk(0, 0);
    community.updateUsername(sender, QStringLiteral("Yusuf"));

    const NotificationData data = generator.groupMessageNotification(&community, sender, "Salam");
    QCOMPARE(data.title, communityName);
}

QTEST_GUILESS_MAIN(TestGroup)
#include "group_test.moc"
