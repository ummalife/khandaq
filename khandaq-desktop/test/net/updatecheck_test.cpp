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

#include "src/net/sshsig.h"
#include "src/net/updatecheck.h"
#include "src/net/updateinstaller.h"
#include "src/persistence/iupdatesettings.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QHash>
#include <QSignalSpy>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QtTest/QtTest>

namespace {
/** The settings the check and the installer read and keep, in memory. */
class FakeUpdateSettings : public IUpdateSettings
{
public:
    bool getCheckUpdates() const override
    {
        return checkUpdates;
    }
    QNetworkProxy getProxy() const override
    {
        return proxy;
    }
    qint64 getUpdateLastCheck() const override
    {
        return lastCheck;
    }
    void setUpdateLastCheck(qint64 secondsSinceEpoch) override
    {
        lastCheck = secondsSinceEpoch;
    }
    QString getUpdateHighestSeen(const QString& platform) const override
    {
        return highestSeen.value(platform);
    }
    void setUpdateHighestSeen(const QString& platform, const QString& version) override
    {
        highestSeen.insert(platform, version);
    }
    qint64 getUpdateManifestSerial() const override
    {
        return manifestSerial;
    }
    void setUpdateManifestSerial(qint64 serial) override
    {
        manifestSerial = serial;
    }
    void saveGlobal() override
    {
        ++saves;
    }

    bool checkUpdates = true;
    QNetworkProxy proxy{QNetworkProxy::NoProxy};
    qint64 lastCheck = 0;
    QMap<QString, QString> highestSeen;
    qint64 manifestSerial = 0;
    int saves = 0;
};

struct Answer
{
    int status = 200;
    QByteArray body;
    QByteArray headers;
    /** The length the headers announce, when it is not the body's. */
    qint64 announced = -1;
    /** Nothing is ever answered. */
    bool silent = false;
    /** The connection stays open after the body. */
    bool hold = false;
};

Answer answer(int status, const QByteArray& body)
{
    Answer made;
    made.status = status;
    made.body = body;
    return made;
}

/** The bytes the manifest fixture describes for every platform. */
QByteArray artifact()
{
    QByteArray bytes(3000, Qt::Uninitialized);
    for (int i = 0; i < bytes.size(); ++i) {
        bytes[i] = static_cast<char>((i * 7 + 3) % 256);
    }
    return bytes;
}

QByteArray fixture(const QString& name)
{
    QFile file(QStringLiteral(":/update/") + name);
    return file.open(QIODevice::ReadOnly) ? file.readAll() : QByteArray();
}
} // namespace

/** An HTTP server on 127.0.0.1 that answers each path as it is told and keeps every request. */
class LocalServer : public QObject
{
    Q_OBJECT
public:
    LocalServer()
    {
        server.listen(QHostAddress::LocalHost, 0);
        connect(&server, &QTcpServer::newConnection, this, &LocalServer::accept);
    }

    QUrl url(const QString& path) const
    {
        return QUrl(QStringLiteral("http://127.0.0.1:%1%2").arg(server.serverPort()).arg(path));
    }

    QHash<QString, Answer> answers;
    QList<QByteArray> requests;

private:
    void accept()
    {
        while (QTcpSocket* const socket = server.nextPendingConnection()) {
            connect(socket, &QTcpSocket::readyRead, this, [this, socket] { serve(socket); });
            connect(socket, &QTcpSocket::disconnected, this, [this, socket] {
                pending.remove(socket);
                socket->deleteLater();
            });
        }
    }

    void serve(QTcpSocket* socket)
    {
        QByteArray& buffer = pending[socket];
        buffer += socket->readAll();
        const int end = buffer.indexOf("\r\n\r\n");
        if (end < 0) {
            return;
        }
        const QByteArray request = buffer.left(end + 4);
        buffer.remove(0, end + 4);
        requests.append(request);
        const QList<QByteArray> words = request.split(' ');
        const QString path = words.size() > 1 ? QString::fromLatin1(words.at(1)) : QString();
        const Answer reply = answers.value(path, answer(404, "not here"));
        if (reply.silent) {
            return;
        }
        QByteArray response = "HTTP/1.1 " + QByteArray::number(reply.status) + " Answer\r\n";
        response += "Content-Length: "
                    + QByteArray::number(reply.announced >= 0 ? reply.announced : reply.body.size())
                    + "\r\n";
        response += reply.headers;
        response += "Connection: close\r\n\r\n";
        response += reply.body;
        socket->write(response);
        if (!reply.hold) {
            socket->disconnectFromHost();
        }
    }

    QTcpServer server;
    QHash<QTcpSocket*, QByteArray> pending;
};

/**
 * KHANDAQ (0.2.66): the update check and the download against a server on this machine, with the
 * manifest signed by the throwaway test key: what is offered, what is refused before a byte of it
 * is read as JSON, what the requests say about who asks, and that a download is kept only when it
 * is the file the signed manifest describes.
 */
class TestUpdateCheck : public QObject
{
    Q_OBJECT

private slots:
    void initTestCase();
    void init();
    void cleanup();

    void offersTheVersionTheSignedManifestNames();
    void saysUpToDate();
    void sendsNothingThatTellsWhoAsks();
    void refusesATamperedManifest();
    void refusesASignatureOfAnotherNameSpace();
    void refusesAManifestSignedByAnotherKey();
    void refusesRedirects();
    void refusesOversizedAnswers();
    void givesUpOnASilentServer();
    void neverGoesBelowTheHighestSeen();
    void refusesAManifestOlderThanOneSeen();
    void takesTheSameManifestAgain();
    void keepsTheHighestVersionOfEachPlatform();
    void checksOnlyReleaseBuilds();
    void sendsNothingWithoutAUsableProxy();
    void checksAgainOnlyAfterADay();

    void keepsTheVerifiedDownload();
    void reusesAVerifiedDownload();
    void deletesADownloadThatIsNotTheSignedFile();
    void stopsADownloadLargerThanTheManifestSays();
    void saysWhenTheDownloadFails();
    void stopsWhenAsked();
    void givesTheCommandForAPackage();
    void cleansUpEarlierDownloads();
    void cleanUpSparesADownloadUnderWay();

private:
    UpdateCheck::Setup setup(UpdatePlatform::Kind kind, const char* running) const;
    UpdateInstaller::Setup installSetup(UpdatePlatform::Kind kind, const char* running) const;
    /** Checks, and gives back the offer when there is one. */
    bool offered(UpdateCheck& check);
    void serveSigned(const QByteArray& manifest, const QByteArray& signature);
    static bool finished(UpdateInstaller& installer);

    QByteArray testKey;
    QByteArray manifest;
    QByteArray signature;
    /** The serial of the signed fixture. */
    qint64 fixtureSerial = 0;
    LocalServer* server = nullptr;
    QTemporaryDir* temp = nullptr;
    FakeUpdateSettings settings;
};

namespace {
const QString manifestPath = QStringLiteral("/downloads/khandaq-desktop-update.json");
const QString portablePath = QStringLiteral("/downloads/khandaq-linux-x86_64-portable.tar.gz");
const QString debPath = QStringLiteral("/downloads/khandaq-messenger_amd64.deb");
const QString portableKey = QStringLiteral("linux-portable-x86_64");
const QString debKey = QStringLiteral("linux-deb-amd64");
} // namespace

void TestUpdateCheck::initTestCase()
{
    Q_INIT_RESOURCE(fixtures);
    const QList<QByteArray> parts = fixture(QStringLiteral("update-test.pub")).split(' ');
    QVERIFY(parts.size() >= 2);
    testKey = SshSig::ed25519Key(parts.at(1));
    QCOMPARE(testKey.size(), 32);
    manifest = fixture(QStringLiteral("khandaq-desktop-update.json"));
    signature = fixture(QStringLiteral("khandaq-desktop-update.json.sig"));
    QVERIFY(!manifest.isEmpty() && !signature.isEmpty());
    UpdateManifest::Release release;
    QString error;
    QVERIFY2(UpdateManifest::parse(manifest, release, error), qPrintable(error));
    fixtureSerial = release.serial;
    QVERIFY(fixtureSerial > 1);
    QCOMPARE(QCryptographicHash::hash(artifact(), QCryptographicHash::Sha256).toHex(),
             QByteArray("f541874101876255b4baf3a739778d04cb9cba25ffa38b30bc1fb8b0701f2a45"));
}

void TestUpdateCheck::init()
{
    settings = FakeUpdateSettings{};
    server = new LocalServer;
    temp = new QTemporaryDir;
    QVERIFY(temp->isValid());
    serveSigned(manifest, signature);
    server->answers.insert(portablePath, answer(200, artifact()));
    server->answers.insert(debPath, answer(200, artifact()));
}

void TestUpdateCheck::cleanup()
{
    delete server;
    server = nullptr;
    delete temp;
    temp = nullptr;
}

void TestUpdateCheck::serveSigned(const QByteArray& body, const QByteArray& sig)
{
    server->answers.insert(manifestPath, answer(200, body));
    server->answers.insert(manifestPath + QStringLiteral(".sig"), answer(200, sig));
}

UpdateCheck::Setup TestUpdateCheck::setup(UpdatePlatform::Kind kind, const char* running) const
{
    UpdateCheck::Setup made;
    made.manifest = server->url(manifestPath);
    made.layout.kind = kind;
    made.layout.root = temp->filePath(QStringLiteral("Khandaq"));
    made.running = UpdateManifest::running(QString::fromLatin1(running), QString::fromLatin1(running));
    made.keys = {testKey};
    made.timeoutMs = 2000;
    return made;
}

UpdateInstaller::Setup TestUpdateCheck::installSetup(UpdatePlatform::Kind kind, const char* running) const
{
    UpdateInstaller::Setup made;
    made.manifest = server->url(manifestPath);
    made.layout.kind = kind;
    made.layout.root = temp->filePath(QStringLiteral("Khandaq"));
    UpdateManifest::parseVersion(QString::fromLatin1(running), made.running);
    made.downloads = temp->filePath(QStringLiteral("My 'downloads'"));
    made.stallMs = 2000;
    return made;
}

bool TestUpdateCheck::offered(UpdateCheck& check)
{
    QSignalSpy available(&check, &UpdateCheck::updateAvailable);
    QSignalSpy current(&check, &UpdateCheck::upToDate);
    QSignalSpy failed(&check, &UpdateCheck::updateCheckFailed);
    check.checkForUpdate();
    QElapsedTimer clock;
    clock.start();
    while (available.count() + current.count() + failed.count() == 0 && clock.elapsed() < 10000) {
        QTest::qWait(10);
    }
    // One ending, and only one.
    QTest::qWait(50);
    return available.count() + current.count() + failed.count() == 1 && available.count() == 1
           && !check.checking();
}

bool TestUpdateCheck::finished(UpdateInstaller& installer)
{
    QElapsedTimer clock;
    clock.start();
    while (installer.stage() == UpdateInstaller::Stage::Downloading && clock.elapsed() < 10000) {
        QTest::qWait(20);
    }
    return installer.stage() != UpdateInstaller::Stage::Downloading;
}

void TestUpdateCheck::offersTheVersionTheSignedManifestNames()
{
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QVERIFY(check.supported());
    QSignalSpy available(&check, &UpdateCheck::updateAvailable);
    const qint64 before = QDateTime::currentSecsSinceEpoch();
    QVERIFY(offered(check));
    QCOMPARE(available.first().first().toString(), QStringLiteral("0.2.65"));
    QVERIFY(check.hasOffer());
    QCOMPARE(check.offer().file, QStringLiteral("khandaq-linux-x86_64-portable.tar.gz"));
    QCOMPARE(settings.highestSeen, (QMap<QString, QString>{{portableKey, QStringLiteral("0.2.65")}}));
    QCOMPARE(settings.manifestSerial, fixtureSerial);
    QVERIFY(settings.lastCheck >= before);
    QVERIFY(settings.saves >= 1);
    QCOMPARE(check.release().notesRussian, QUrl(QStringLiteral("https://khandaq.org/ru/changelog.html")));
    // The manifest, then its signature.
    QCOMPARE(server->requests.size(), 2);
}

void TestUpdateCheck::saysUpToDate()
{
    // The Linux package of the fixture is still 0.2.64.
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxDeb, "v0.2.64"));
    QSignalSpy current(&check, &UpdateCheck::upToDate);
    QVERIFY(!offered(check));
    QCOMPARE(current.count(), 1);
    QVERIFY(!check.hasOffer());
    QCOMPARE(settings.highestSeen, (QMap<QString, QString>{{debKey, QStringLiteral("0.2.64")}}));
    QVERIFY(settings.lastCheck > 0);
}

void TestUpdateCheck::sendsNothingThatTellsWhoAsks()
{
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QVERIFY(offered(check));
    UpdateInstaller installer(settings, installSetup(UpdatePlatform::Kind::LinuxPortable, "0.2.64"));
    installer.install(check.offer());
    QVERIFY(finished(installer));
    QCOMPARE(server->requests.size(), 3);
    for (const QByteArray& request : server->requests) {
        QVERIFY2(request.startsWith("GET /downloads/"), request.constData());
        const QByteArray lower = request.toLower();
        QVERIFY2(request.contains("\r\nUser-Agent: Khandaq\r\n"), request.constData());
        QVERIFY2(request.contains("\r\nAccept-Language: *\r\n"), request.constData());
        QVERIFY2(request.contains("\r\nAccept-Encoding: identity\r\n"), request.constData());
        QVERIFY2(!lower.contains("cookie"), request.constData());
        QVERIFY2(!lower.contains("0.2.64"), request.constData());
        QVERIFY2(!lower.contains("referer"), request.constData());
        QVERIFY2(!lower.contains("authorization"), request.constData());
    }
}

void TestUpdateCheck::refusesATamperedManifest()
{
    QByteArray tampered = manifest;
    tampered.replace("0.2.64", "0.2.99");
    serveSigned(tampered, signature);
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxDeb, "v0.2.64"));
    QSignalSpy failed(&check, &UpdateCheck::updateCheckFailed);
    QVERIFY(!offered(check));
    QCOMPARE(failed.count(), 1);
    QVERIFY(!check.hasOffer());
    // Nothing of what came is kept.
    QVERIFY(settings.highestSeen.isEmpty());
    QCOMPARE(settings.manifestSerial, qint64{0});
    QCOMPARE(settings.lastCheck, qint64{0});
    QCOMPARE(settings.saves, 0);
}

void TestUpdateCheck::refusesASignatureOfAnotherNameSpace()
{
    serveSigned(manifest, fixture(QStringLiteral("khandaq-desktop-update.json.release.sig")));
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QSignalSpy failed(&check, &UpdateCheck::updateCheckFailed);
    QVERIFY(!offered(check));
    QCOMPARE(failed.count(), 1);
}

void TestUpdateCheck::refusesAManifestSignedByAnotherKey()
{
    UpdateCheck::Setup made = setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64");
    // The release key, which never signed the fixture.
    made.keys = UpdateEndpoint::trustedKeys().mid(0, 1);
    UpdateCheck check(settings, made);
    QSignalSpy failed(&check, &UpdateCheck::updateCheckFailed);
    QVERIFY(!offered(check));
    QCOMPARE(failed.count(), 1);
}

void TestUpdateCheck::refusesRedirects()
{
    Answer moved = answer(302, QByteArray());
    moved.headers = "Location: " + server->url(QStringLiteral("/elsewhere.json")).toEncoded() + "\r\n";
    server->answers.insert(manifestPath, moved);
    server->answers.insert(QStringLiteral("/elsewhere.json"), answer(200, manifest));
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QSignalSpy failed(&check, &UpdateCheck::updateCheckFailed);
    QVERIFY(!offered(check));
    QCOMPARE(failed.count(), 1);
    QCOMPARE(server->requests.size(), 1);
}

void TestUpdateCheck::refusesOversizedAnswers()
{
    // Larger than the limit as it comes.
    UpdateCheck::Setup made = setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64");
    made.manifestLimit = manifest.size() - 1;
    {
        UpdateCheck check(settings, made);
        QSignalSpy failed(&check, &UpdateCheck::updateCheckFailed);
        QVERIFY(!offered(check));
        QCOMPARE(failed.count(), 1);
    }
    // Announced as larger than the limit: never read.
    Answer huge = answer(200, manifest);
    huge.announced = 10 * 1024 * 1024;
    huge.hold = true;
    server->answers.insert(manifestPath, huge);
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QSignalSpy failed(&check, &UpdateCheck::updateCheckFailed);
    QElapsedTimer clock;
    clock.start();
    QVERIFY(!offered(check));
    QCOMPARE(failed.count(), 1);
    // Refused at once rather than by the timeout.
    QVERIFY(clock.elapsed() < 1500);
}

void TestUpdateCheck::givesUpOnASilentServer()
{
    Answer silent;
    silent.silent = true;
    server->answers.insert(manifestPath, silent);
    UpdateCheck::Setup made = setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64");
    made.timeoutMs = 300;
    UpdateCheck check(settings, made);
    QSignalSpy failed(&check, &UpdateCheck::updateCheckFailed);
    QVERIFY(!offered(check));
    QCOMPARE(failed.count(), 1);
    QVERIFY(!check.checking());
}

void TestUpdateCheck::neverGoesBelowTheHighestSeen()
{
    settings.highestSeen.insert(portableKey, QStringLiteral("0.2.66"));
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.63"));
    QSignalSpy current(&check, &UpdateCheck::upToDate);
    QVERIFY(!offered(check));
    QCOMPARE(current.count(), 1);
    QCOMPARE(settings.highestSeen.value(portableKey), QStringLiteral("0.2.66"));
}

void TestUpdateCheck::refusesAManifestOlderThanOneSeen()
{
    // Signed by the key, and older than a manifest this copy has verified: served again, it would
    // hold back the newer release, or bring back the older bytes of a version.
    settings.manifestSerial = fixtureSerial + 1;
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QSignalSpy failed(&check, &UpdateCheck::updateCheckFailed);
    QVERIFY(!offered(check));
    QCOMPARE(failed.count(), 1);
    QVERIFY(!check.hasOffer());
    QCOMPARE(settings.manifestSerial, fixtureSerial + 1);
    QVERIFY(settings.highestSeen.isEmpty());
    QCOMPARE(settings.lastCheck, qint64{0});
    QCOMPARE(settings.saves, 0);
}

void TestUpdateCheck::takesTheSameManifestAgain()
{
    settings.manifestSerial = fixtureSerial;
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QVERIFY(offered(check));
    QCOMPARE(settings.manifestSerial, fixtureSerial);
}

void TestUpdateCheck::keepsTheHighestVersionOfEachPlatform()
{
    // A portable folder and a .deb on one computer share the settings; the fixture offers the
    // folder 0.2.65 and the package 0.2.64. What the folder saw must not hold the package back.
    {
        UpdateCheck portable(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
        QVERIFY(offered(portable));
    }
    UpdateCheck package(settings, setup(UpdatePlatform::Kind::LinuxDeb, "v0.2.63"));
    QSignalSpy available(&package, &UpdateCheck::updateAvailable);
    QVERIFY(offered(package));
    QCOMPARE(available.first().first().toString(), QStringLiteral("0.2.64"));
    QCOMPARE(settings.highestSeen, (QMap<QString, QString>{{portableKey, QStringLiteral("0.2.65")},
                                                          {debKey, QStringLiteral("0.2.64")}}));
}

void TestUpdateCheck::checksOnlyReleaseBuilds()
{
    UpdateCheck::Setup made = setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64");
    made.running = UpdateManifest::running(QStringLiteral("Nightly"), QStringLiteral("v0.2.64-3-gabc1234"));
    UpdateCheck check(settings, made);
    QCOMPARE(check.supported(), UpdateEndpoint::qaBuild());
    if (UpdateEndpoint::qaBuild()) {
        return;
    }
    QSignalSpy started(&check, &UpdateCheck::checkStarted);
    check.checkForUpdate();
    QTest::qWait(200);
    QCOMPARE(started.count(), 0);
    QVERIFY(server->requests.isEmpty());

    // Nor a copy of a kind the releases are not made for.
    UpdateCheck none(settings, setup(UpdatePlatform::Kind::None, "v0.2.64"));
    QVERIFY(!none.supported());
}

void TestUpdateCheck::sendsNothingWithoutAUsableProxy()
{
    settings.proxy = QNetworkProxy(QNetworkProxy::Socks5Proxy, QString(), 9050);
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QSignalSpy failed(&check, &UpdateCheck::updateCheckFailed);
    check.checkForUpdate();
    QCOMPARE(failed.count(), 1);
    QTest::qWait(100);
    QVERIFY(server->requests.isEmpty());

    QNetworkProxy route;
    QVERIFY(!UpdateNetwork::route(QNetworkProxy(QNetworkProxy::HttpProxy, QStringLiteral("proxy"), 0), route));
    QVERIFY(!UpdateNetwork::route(QNetworkProxy(QNetworkProxy::DefaultProxy), route));
    QVERIFY(UpdateNetwork::route(QNetworkProxy(QNetworkProxy::Socks5Proxy, QStringLiteral("127.0.0.1"), 9050), route));
    QCOMPARE(route.type(), QNetworkProxy::Socks5Proxy);
    // The proxy looks the name up.
    QVERIFY(route.capabilities() & QNetworkProxy::HostNameLookupCapability);
    QVERIFY(UpdateNetwork::route(QNetworkProxy(QNetworkProxy::NoProxy), route));
    QCOMPARE(route.type(), QNetworkProxy::NoProxy);
}

void TestUpdateCheck::checksAgainOnlyAfterADay()
{
    UpdateCheck::Setup made = setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64");
    made.firstCheckEarliestMs = 50;
    made.firstCheckLatestMs = 100;
    made.recheckMs = 100;

    // With the switch off nothing is asked, ever.
    settings.checkUpdates = false;
    {
        UpdateCheck check(settings, made);
        check.start();
        QTest::qWait(500);
        QVERIFY(server->requests.isEmpty());
    }

    // On: once soon after the start, whenever the last check was; then not again within the day.
    settings.checkUpdates = true;
    settings.lastCheck = QDateTime::currentSecsSinceEpoch() - 60;
    UpdateCheck check(settings, made);
    QSignalSpy available(&check, &UpdateCheck::updateAvailable);
    check.start();
    QTRY_COMPARE_WITH_TIMEOUT(available.count(), 1, 5000);
    QTest::qWait(500);
    QCOMPARE(server->requests.size(), 2);
    QCOMPARE(available.count(), 1);

    // A day after the last check that went through, the next hourly look asks again.
    settings.lastCheck = QDateTime::currentSecsSinceEpoch() - 24 * 60 * 60 - 1;
    QTRY_COMPARE_WITH_TIMEOUT(available.count(), 2, 5000);
    QCOMPARE(server->requests.size(), 4);
    // A clock set back puts the last check in the future: that counts as a day gone too.
    settings.lastCheck = QDateTime::currentSecsSinceEpoch() + 24 * 60 * 60;
    QTRY_COMPARE_WITH_TIMEOUT(available.count(), 3, 5000);
}

void TestUpdateCheck::keepsTheVerifiedDownload()
{
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QVERIFY(offered(check));
    UpdateInstaller installer(settings, installSetup(UpdatePlatform::Kind::LinuxPortable, "0.2.64"));
    QSignalSpy quit(&installer, &UpdateInstaller::quitRequested);
    installer.install(check.offer());
    QCOMPARE(installer.stage(), UpdateInstaller::Stage::Downloading);
    QVERIFY(finished(installer));
    QCOMPARE(installer.stage(), UpdateInstaller::Stage::Manual);
    QCOMPARE(installer.failure(), UpdateInstaller::Failure::None);
    QCOMPARE(installer.percent(), 100);
    const QString file = installer.file();
    QCOMPARE(QFileInfo(file).fileName(), QStringLiteral("khandaq-linux-x86_64-portable.tar.gz"));
    QCOMPARE(QFileInfo(file).dir().dirName(), QStringLiteral("0.2.65"));
    QFile kept(file);
    QVERIFY(kept.open(QIODevice::ReadOnly));
    QCOMPARE(kept.readAll(), artifact());
    QVERIFY(!QFileInfo::exists(file + QStringLiteral(".part")));
    // Nothing is started for a copy left to the person.
    QCOMPARE(quit.count(), 0);
    QVERIFY(installer.command().isEmpty());
}

void TestUpdateCheck::reusesAVerifiedDownload()
{
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QVERIFY(offered(check));
    const UpdateInstaller::Setup made = installSetup(UpdatePlatform::Kind::LinuxPortable, "0.2.64");
    const QString folder = QDir(made.downloads).filePath(QStringLiteral("0.2.65"));
    QVERIFY(QDir().mkpath(folder));
    QFile ready(QDir(folder).filePath(QStringLiteral("khandaq-linux-x86_64-portable.tar.gz")));
    QVERIFY(ready.open(QIODevice::WriteOnly));
    QCOMPARE(ready.write(artifact()), qint64{3000});
    ready.close();
    const int before = server->requests.size();
    UpdateInstaller installer(settings, made);
    installer.install(check.offer());
    QCOMPARE(installer.stage(), UpdateInstaller::Stage::Manual);
    QCOMPARE(server->requests.size(), before);
}

void TestUpdateCheck::deletesADownloadThatIsNotTheSignedFile()
{
    QByteArray other = artifact();
    other[1500] = static_cast<char>(other.at(1500) ^ 0x01);
    server->answers.insert(portablePath, answer(200, other));
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QVERIFY(offered(check));
    UpdateInstaller installer(settings, installSetup(UpdatePlatform::Kind::LinuxPortable, "0.2.64"));
    installer.install(check.offer());
    QVERIFY(finished(installer));
    QCOMPARE(installer.stage(), UpdateInstaller::Stage::Failed);
    QCOMPARE(installer.failure(), UpdateInstaller::Failure::Integrity);
    QVERIFY(!QFileInfo::exists(installer.file()));
    QVERIFY(!QFileInfo::exists(installer.file() + QStringLiteral(".part")));
}

void TestUpdateCheck::stopsADownloadLargerThanTheManifestSays()
{
    Answer longer = answer(200, artifact() + QByteArray(64 * 1024, 'x'));
    server->answers.insert(portablePath, longer);
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QVERIFY(offered(check));
    UpdateInstaller installer(settings, installSetup(UpdatePlatform::Kind::LinuxPortable, "0.2.64"));
    installer.install(check.offer());
    QVERIFY(finished(installer));
    QCOMPARE(installer.stage(), UpdateInstaller::Stage::Failed);
    QCOMPARE(installer.failure(), UpdateInstaller::Failure::Integrity);
    QVERIFY(!QFileInfo::exists(installer.file() + QStringLiteral(".part")));
}

void TestUpdateCheck::saysWhenTheDownloadFails()
{
    server->answers.remove(portablePath);
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QVERIFY(offered(check));
    UpdateInstaller installer(settings, installSetup(UpdatePlatform::Kind::LinuxPortable, "0.2.64"));
    installer.install(check.offer());
    QVERIFY(finished(installer));
    QCOMPARE(installer.stage(), UpdateInstaller::Stage::Failed);
    QCOMPARE(installer.failure(), UpdateInstaller::Failure::Download);
    // Asked again, it tries again.
    server->answers.insert(portablePath, answer(200, artifact()));
    installer.install(check.offer());
    QVERIFY(finished(installer));
    QCOMPARE(installer.stage(), UpdateInstaller::Stage::Manual);
}

void TestUpdateCheck::stopsWhenAsked()
{
    Answer slow = answer(200, artifact().left(100));
    slow.announced = 3000;
    slow.hold = true;
    server->answers.insert(portablePath, slow);
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QVERIFY(offered(check));
    UpdateInstaller installer(settings, installSetup(UpdatePlatform::Kind::LinuxPortable, "0.2.64"));
    installer.install(check.offer());
    QTRY_VERIFY_WITH_TIMEOUT(installer.percent() > 0, 5000);
    QVERIFY(QFileInfo::exists(installer.file() + QStringLiteral(".part")));
    installer.cancel();
    QCOMPARE(installer.stage(), UpdateInstaller::Stage::Idle);
    QTRY_VERIFY_WITH_TIMEOUT(!QFileInfo::exists(installer.file() + QStringLiteral(".part")), 5000);
    QCOMPARE(installer.stage(), UpdateInstaller::Stage::Idle);
}

void TestUpdateCheck::givesTheCommandForAPackage()
{
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxDeb, "v0.2.63"));
    QVERIFY(offered(check));
    UpdateInstaller installer(settings, installSetup(UpdatePlatform::Kind::LinuxDeb, "0.2.63"));
    installer.install(check.offer());
    QVERIFY(finished(installer));
    QCOMPARE(installer.stage(), UpdateInstaller::Stage::Manual);
    QString quoted = installer.file();
    quoted.replace(QStringLiteral("'"), QStringLiteral("'\\''"));
    QCOMPARE(installer.command(), QStringLiteral("sudo apt install '") + quoted + QStringLiteral("'"));
}

void TestUpdateCheck::cleansUpEarlierDownloads()
{
    const UpdateInstaller::Setup made = installSetup(UpdatePlatform::Kind::LinuxPortable, "0.2.64");
    const QDir downloads(made.downloads);
    for (const char* name : {"0.2.63/a.tar.gz", "0.2.64/a.tar.gz", "0.2.65/a.tar.gz", "0.2.65/a.tar.gz.part",
                             "other/b.part", "other/keep.txt"}) {
        const QString path = downloads.filePath(QString::fromLatin1(name));
        QVERIFY(QDir().mkpath(QFileInfo(path).absolutePath()));
        QFile file(path);
        QVERIFY(file.open(QIODevice::WriteOnly));
    }
    UpdateInstaller installer(settings, made);
    installer.cleanUp();
    QVERIFY(!downloads.exists(QStringLiteral("0.2.63")));
    QVERIFY(!downloads.exists(QStringLiteral("0.2.64")));
    QVERIFY(downloads.exists(QStringLiteral("0.2.65/a.tar.gz")));
    QVERIFY(!downloads.exists(QStringLiteral("0.2.65/a.tar.gz.part")));
    QVERIFY(!downloads.exists(QStringLiteral("other/b.part")));
    QVERIFY(downloads.exists(QStringLiteral("other/keep.txt")));
}

/** The clean-up comes a few seconds after the start; Update may have been pressed by then. */
void TestUpdateCheck::cleanUpSparesADownloadUnderWay()
{
    Answer slow = answer(200, artifact().left(100));
    slow.announced = 3000;
    slow.hold = true;
    server->answers.insert(portablePath, slow);
    UpdateCheck check(settings, setup(UpdatePlatform::Kind::LinuxPortable, "v0.2.64"));
    QVERIFY(offered(check));
    UpdateInstaller installer(settings, installSetup(UpdatePlatform::Kind::LinuxPortable, "0.2.64"));
    installer.install(check.offer());
    QTRY_VERIFY_WITH_TIMEOUT(installer.percent() > 0, 5000);
    const QString part = installer.file() + QStringLiteral(".part");
    QVERIFY(QFileInfo::exists(part));
    installer.cleanUp();
    QVERIFY(QFileInfo::exists(part));
    QCOMPARE(installer.stage(), UpdateInstaller::Stage::Downloading);
    installer.cancel();
    QTRY_VERIFY_WITH_TIMEOUT(!QFileInfo::exists(part), 5000);
}

QTEST_GUILESS_MAIN(TestUpdateCheck)
#include "updatecheck_test.moc"
