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

// KHANDAQ (beta ideas 2026-11-20): what macOS asks the app to open, routed as a start argument is.

#include "src/platform/fileopenrouter.h"

#include <QCoreApplication>
#include <QFileOpenEvent>
#include <QStringList>
#include <QUrl>
#include <QtTest/QtTest>

namespace {
const QString toxId = QStringLiteral(
    "76518406F6A9F2217E8DC487CC783C25CC16A15EB36FF32E335A235342C48A39218F515C39A6");

QCoreApplication* app()
{
    return QCoreApplication::instance();
}

/** Sends an open event to the application, as the system does; true when a filter took it. */
bool sendOpen(QFileOpenEvent& event)
{
    return QCoreApplication::sendEvent(app(), &event);
}
} // namespace

class TestFileOpenRouter : public QObject
{
    Q_OBJECT

private slots:
    void aLinkAndAProfileFileAreRequests();
    void anythingElseIsNoRequest();
    void whatComesBeforeAWindowWaitsInOrder();
    void whatATakerCannotTakeYetWaitsOn();
    void otherEventsPassThrough();
};

/** @brief A tox: link as it was clicked, and a .tox file by its path or its file: URL. */
void TestFileOpenRouter::aLinkAndAProfileFileAreRequests()
{
    FileOpenRouter::Request request;
    QVERIFY(FileOpenRouter::requestOf(QFileOpenEvent(QUrl(QStringLiteral("tox:") + toxId)),
                                      &request));
    QVERIFY(!request.profileFile);
    QCOMPARE(request.target, QStringLiteral("tox:") + toxId);

    QVERIFY(FileOpenRouter::requestOf(QFileOpenEvent(QUrl(QStringLiteral("TOX:") + toxId)),
                                      &request));
    QVERIFY(!request.profileFile);
    QVERIFY(request.target.endsWith(toxId));

    QVERIFY(FileOpenRouter::requestOf(QFileOpenEvent(QStringLiteral("/Users/isa/Maryam.tox")),
                                      &request));
    QVERIFY(request.profileFile);
    QCOMPARE(request.target, QStringLiteral("/Users/isa/Maryam.tox"));

    QVERIFY(FileOpenRouter::requestOf(
        QFileOpenEvent(QUrl::fromLocalFile(QStringLiteral("/Users/isa/Old profile.TOX"))),
        &request));
    QVERIFY(request.profileFile);
    QCOMPARE(request.target, QStringLiteral("/Users/isa/Old profile.TOX"));
}

/** @brief A web link, another file or nothing at all is not the app's to open. */
void TestFileOpenRouter::anythingElseIsNoRequest()
{
    FileOpenRouter::Request request;
    QVERIFY(!FileOpenRouter::requestOf(QFileOpenEvent(QUrl(QStringLiteral("https://khandaq.org"))),
                                       &request));
    QVERIFY(!FileOpenRouter::requestOf(QFileOpenEvent(QStringLiteral("/Users/isa/photo.png")),
                                       &request));
    QVERIFY(!FileOpenRouter::requestOf(QFileOpenEvent(QStringLiteral("/Users/isa/tox")),
                                       &request));
    QVERIFY(!FileOpenRouter::requestOf(QFileOpenEvent(QUrl()), &request));
}

/**
 * @brief The launch a link or a file started: the event comes before there is a window to take it,
 * and goes to it, in order, once there is.
 */
void TestFileOpenRouter::whatComesBeforeAWindowWaitsInOrder()
{
    FileOpenRouter router;
    app()->installEventFilter(&router);

    QFileOpenEvent link(QUrl(QStringLiteral("tox:") + toxId));
    QVERIFY(sendOpen(link));
    QFileOpenEvent file(QStringLiteral("/Users/isa/Maryam.tox"));
    QVERIFY(sendOpen(file));
    QCOMPARE(router.waitingCount(), 2);

    QStringList taken;
    router.setTaker([&taken](const FileOpenRouter::Request& request) {
        taken << (request.profileFile ? QStringLiteral("file ") : QStringLiteral("link "))
                     + request.target;
        return true;
    });
    // Nothing goes until the app says a window is there.
    QCOMPARE(taken.size(), 0);
    router.deliver();
    QCOMPARE(taken, QStringList() << QStringLiteral("link tox:") + toxId
                                  << QStringLiteral("file /Users/isa/Maryam.tox"));
    QCOMPARE(router.waitingCount(), 0);

    // From then on one goes as it comes.
    QFileOpenEvent again(QStringLiteral("/Users/isa/Yusuf.tox"));
    QVERIFY(sendOpen(again));
    QCOMPARE(taken.size(), 3);
    QCOMPARE(taken.last(), QStringLiteral("file /Users/isa/Yusuf.tox"));
    QCOMPARE(router.waitingCount(), 0);

    app()->removeEventFilter(&router);
}

/**
 * @brief The start window takes a profile file and leaves a link for the main window; the link
 * goes once the main window is there.
 */
void TestFileOpenRouter::whatATakerCannotTakeYetWaitsOn()
{
    FileOpenRouter router;
    app()->installEventFilter(&router);
    bool mainWindow = false;
    QStringList taken;
    router.setTaker([&](const FileOpenRouter::Request& request) {
        if (!request.profileFile && !mainWindow) {
            return false;
        }
        taken << request.target;
        return true;
    });

    QFileOpenEvent link(QUrl(QStringLiteral("tox:") + toxId));
    QVERIFY(sendOpen(link));
    QFileOpenEvent file(QStringLiteral("/Users/isa/Maryam.tox"));
    QVERIFY(sendOpen(file));
    QCOMPARE(taken, QStringList() << QStringLiteral("/Users/isa/Maryam.tox"));
    QCOMPARE(router.waitingCount(), 1);

    router.deliver();
    QCOMPARE(router.waitingCount(), 1);

    mainWindow = true;
    router.deliver();
    QCOMPARE(taken, QStringList() << QStringLiteral("/Users/isa/Maryam.tox")
                                  << QStringLiteral("tox:") + toxId);
    QCOMPARE(router.waitingCount(), 0);

    app()->removeEventFilter(&router);
}

/** @brief Other events, and an open event of something else, go on as without the router. */
void TestFileOpenRouter::otherEventsPassThrough()
{
    FileOpenRouter router;
    app()->installEventFilter(&router);
    int calls = 0;
    router.setTaker([&calls](const FileOpenRouter::Request&) {
        ++calls;
        return true;
    });

    QFileOpenEvent web(QUrl(QStringLiteral("https://khandaq.org")));
    QVERIFY(!sendOpen(web));
    QEvent other(QEvent::User);
    QCoreApplication::sendEvent(app(), &other);
    QCOMPARE(calls, 0);
    QCOMPARE(router.waitingCount(), 0);

    app()->removeEventFilter(&router);
}

QTEST_GUILESS_MAIN(TestFileOpenRouter)
#include "fileopenrouter_test.moc"
