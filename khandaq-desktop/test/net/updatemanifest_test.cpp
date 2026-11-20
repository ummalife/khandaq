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

#include "src/net/updatemanifest.h"

#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QtTest/QtTest>

/**
 * KHANDAQ (0.2.65): what a verified manifest may say and what the app makes of it. Versions are
 * three plain numbers compared as numbers; a manifest is read strictly, each platform names only its
 * own download; and a version is offered only when it is newer than the running one and not older
 * than the newest one a verified manifest has shown.
 */
class TestUpdateManifest : public QObject
{
    Q_OBJECT

private slots:
    void initTestCase();
    void parsesVersions();
    void refusesWhatIsNotAVersion_data();
    void refusesWhatIsNotAVersion();
    void comparesAsNumbers();
    void readsTheRunningBuild();
    void readsTheSignedFixture();
    void refusesAnotherSchema();
    void refusesNotesOffTheSite();
    void refusesAFileOfAnotherPlatform();
    void refusesBadEntries_data();
    void refusesBadEntries();
    void passesOverWhatItDoesNotKnow();
    void refusesWhatIsNotJson();
    void picksTheNotesOfTheLanguage();
    void offersOnlyANewerVersion();
    void neverGoesBelowTheHighestSeen();
    void offersNothingForAMissingPlatform();
    void namesTheFilesBesideTheManifest();
    void acceptsQaAddressesOnlyOverHttpsOrLoopback();
    void releaseBuildIgnoresTheQaVariable();

private:
    static QJsonObject fixtureObject();
    static QByteArray bytes(const QJsonObject& object);
    static UpdateManifest::Version version(const char* text);

    QByteArray fixtureBytes;
};

QJsonObject TestUpdateManifest::fixtureObject()
{
    QFile file(QStringLiteral(":/update/khandaq-desktop-update.json"));
    if (!file.open(QIODevice::ReadOnly)) {
        return {};
    }
    return QJsonDocument::fromJson(file.readAll()).object();
}

QByteArray TestUpdateManifest::bytes(const QJsonObject& object)
{
    return QJsonDocument(object).toJson();
}

UpdateManifest::Version TestUpdateManifest::version(const char* text)
{
    UpdateManifest::Version parsed;
    UpdateManifest::parseVersion(QString::fromLatin1(text), parsed);
    return parsed;
}

void TestUpdateManifest::initTestCase()
{
    Q_INIT_RESOURCE(fixtures);
    QFile file(QStringLiteral(":/update/khandaq-desktop-update.json"));
    QVERIFY(file.open(QIODevice::ReadOnly));
    fixtureBytes = file.readAll();
    QVERIFY(!fixtureObject().isEmpty());
}

void TestUpdateManifest::parsesVersions()
{
    UpdateManifest::Version parsed;
    QVERIFY(UpdateManifest::parseVersion(QStringLiteral("0.2.64"), parsed));
    QCOMPARE(parsed.major, 0u);
    QCOMPARE(parsed.minor, 2u);
    QCOMPARE(parsed.patch, 64u);
    QVERIFY(UpdateManifest::parseVersion(QStringLiteral("v1.20.3"), parsed));
    QCOMPARE(UpdateManifest::toString(parsed), QStringLiteral("1.20.3"));
    QVERIFY(UpdateManifest::parseVersion(QStringLiteral("999999999.0.999999999"), parsed));
    QCOMPARE(parsed.major, 999999999u);
    QCOMPARE(UpdateManifest::toString(version("0.0.0")), QStringLiteral("0.0.0"));
}

void TestUpdateManifest::refusesWhatIsNotAVersion_data()
{
    QTest::addColumn<QString>("text");
    for (const char* text :
         {"", "v", "0.2", "0.2.64.1", "0.2.", ".2.64", "0..64", "00.2.64", "0.02.64", "0.2.064",
          "0.2.x", "-1.2.3", "+1.2.3", "1.2.3 ", " 1.2.3", "1.2.3\n", "1.2.3-rc1", "v0.2.64-3-gabc1234",
          "V1.2.3", "vv1.2.3", "1,2,3", "1234567890.0.0", "0.0.99999999999999999999", "Nightly"}) {
        QTest::newRow(text) << QString::fromLatin1(text);
    }
    // Digits of other scripts are digits to Qt; not to a version.
    QTest::newRow("fullwidth") << QStringLiteral("１.2.3");
    QTest::newRow("arabic-indic") << QStringLiteral("١.2.3");
}

void TestUpdateManifest::refusesWhatIsNotAVersion()
{
    QFETCH(QString, text);
    UpdateManifest::Version parsed = version("7.7.7");
    QVERIFY(!UpdateManifest::parseVersion(text, parsed));
    // A refused text leaves what was there.
    QCOMPARE(UpdateManifest::toString(parsed), QStringLiteral("7.7.7"));
}

void TestUpdateManifest::comparesAsNumbers()
{
    using UpdateManifest::compare;
    QVERIFY(compare(version("0.2.10"), version("0.2.9")) > 0);
    QVERIFY(compare(version("0.2.9"), version("0.2.10")) < 0);
    QVERIFY(compare(version("0.10.0"), version("0.9.99")) > 0);
    QVERIFY(compare(version("1.0.0"), version("0.999.999")) > 0);
    QCOMPARE(compare(version("0.2.64"), version("v0.2.64")), 0);
    QVERIFY(compare(version("0.2.65"), version("0.2.64")) > 0);
    QVERIFY(compare(version("999999999.0.0"), version("999999998.999999999.999999999")) > 0);
}

void TestUpdateManifest::readsTheRunningBuild()
{
    const UpdateManifest::Running tagged =
        UpdateManifest::running(QStringLiteral("v0.2.64"), QStringLiteral("v0.2.64"));
    QVERIFY(tagged.known);
    QVERIFY(tagged.release);
    QCOMPARE(UpdateManifest::toString(tagged.version), QStringLiteral("0.2.64"));

    const UpdateManifest::Running after =
        UpdateManifest::running(QStringLiteral("Nightly"), QStringLiteral("v0.2.64-3-gabc1234"));
    QVERIFY(after.known);
    QVERIFY(!after.release);
    QCOMPARE(UpdateManifest::toString(after.version), QStringLiteral("0.2.64"));

    for (const auto& pair : {qMakePair(QStringLiteral("Nightly"), QStringLiteral("Nightly")),
                             qMakePair(QStringLiteral("0.2.64"), QStringLiteral("0.2.64")),
                             qMakePair(QStringLiteral("v0.2.64-rc1"), QStringLiteral("v0.2.64-rc1")),
                             qMakePair(QStringLiteral(""), QStringLiteral("")),
                             qMakePair(QStringLiteral("Nightly"),
                                       QStringLiteral("v0.2.64-3-gabc1234-dirty"))}) {
        const UpdateManifest::Running build = UpdateManifest::running(pair.first, pair.second);
        QVERIFY2(!build.release, qPrintable(pair.first));
        QVERIFY2(!build.known, qPrintable(pair.second));
    }
}

void TestUpdateManifest::readsTheSignedFixture()
{
    UpdateManifest::Release release;
    QString error;
    QVERIFY2(UpdateManifest::parse(fixtureBytes, release, error), qPrintable(error));
    QCOMPARE(release.platforms.size(), 5);
    QCOMPARE(release.platforms.keys(), UpdateManifest::platformKeys());
    const UpdateManifest::Entry mac = release.platforms.value(QStringLiteral("macos-zip"));
    QCOMPARE(UpdateManifest::toString(mac.version), QStringLiteral("0.2.65"));
    QCOMPARE(mac.file, QStringLiteral("khandaq-macos.zip"));
    QCOMPARE(mac.size, qint64{3000});
    QCOMPARE(mac.sha256.toHex(),
             QByteArray("f541874101876255b4baf3a739778d04cb9cba25ffa38b30bc1fb8b0701f2a45"));
    // Each platform has a version of its own.
    QCOMPARE(UpdateManifest::toString(release.platforms.value(QStringLiteral("linux-deb-amd64")).version),
             QStringLiteral("0.2.64"));
    QCOMPARE(release.notesEnglish, QUrl(QStringLiteral("https://khandaq.org/changelog")));
    QCOMPARE(release.notesRussian, QUrl(QStringLiteral("https://khandaq.org/ru/changelog.html")));
}

void TestUpdateManifest::refusesAnotherSchema()
{
    for (const QJsonValue& schema : {QJsonValue(QStringLiteral("khandaq-desktop-update/2")),
                                     QJsonValue(QStringLiteral("")), QJsonValue(1), QJsonValue()}) {
        QJsonObject object = fixtureObject();
        object.insert(QStringLiteral("schema"), schema);
        UpdateManifest::Release release;
        QString error;
        QVERIFY(!UpdateManifest::parse(bytes(object), release, error));
        QVERIFY(!error.isEmpty());
    }
    QJsonObject object = fixtureObject();
    object.remove(QStringLiteral("schema"));
    UpdateManifest::Release release;
    QString error;
    QVERIFY(!UpdateManifest::parse(bytes(object), release, error));
}

void TestUpdateManifest::refusesNotesOffTheSite()
{
    for (const char* url :
         {"http://khandaq.org/changelog", "https://khandaq.org.evil.example/changelog",
          "https://evil.example/changelog", "https://user@khandaq.org/changelog",
          "https://khandaq.org:8443/changelog", "javascript:alert(1)", "", "//khandaq.org/x"}) {
        QJsonObject object = fixtureObject();
        QJsonObject notes = object.value(QStringLiteral("notesUrl")).toObject();
        notes.insert(QStringLiteral("ru"), QString::fromLatin1(url));
        object.insert(QStringLiteral("notesUrl"), notes);
        UpdateManifest::Release release;
        QString error;
        QVERIFY2(!UpdateManifest::parse(bytes(object), release, error), url);
    }
    QJsonObject object = fixtureObject();
    object.remove(QStringLiteral("notesUrl"));
    UpdateManifest::Release release;
    QString error;
    QVERIFY(!UpdateManifest::parse(bytes(object), release, error));
}

void TestUpdateManifest::refusesAFileOfAnotherPlatform()
{
    // A manifest that would have the Windows app fetch and run anything but its installer.
    for (const char* file : {"khandaq-windows-x86_64.zip", "../khandaq-windows-installer.exe",
                             "khandaq-windows-installer.exe?x", "https://evil.example/setup.exe",
                             "KHANDAQ-WINDOWS-INSTALLER.EXE", "khandaq-windows-installer.exe ", ""}) {
        QJsonObject object = fixtureObject();
        QJsonObject platforms = object.value(QStringLiteral("platforms")).toObject();
        QJsonObject entry = platforms.value(QStringLiteral("windows-x86_64-installer")).toObject();
        entry.insert(QStringLiteral("file"), QString::fromLatin1(file));
        platforms.insert(QStringLiteral("windows-x86_64-installer"), entry);
        object.insert(QStringLiteral("platforms"), platforms);
        UpdateManifest::Release release;
        QString error;
        QVERIFY2(!UpdateManifest::parse(bytes(object), release, error), file);
        QVERIFY(error.contains(QStringLiteral("windows-x86_64-installer")));
    }
    for (const QString& platform : UpdateManifest::platformKeys()) {
        QVERIFY(!UpdateManifest::fileFor(platform).isEmpty());
        QVERIFY(!UpdateManifest::fileFor(platform).contains(QLatin1Char('/')));
    }
    QVERIFY(UpdateManifest::fileFor(QStringLiteral("windows-arm64-installer")).isEmpty());
}

void TestUpdateManifest::refusesBadEntries_data()
{
    QTest::addColumn<QString>("field");
    QTest::addColumn<QJsonValue>("value");
    QTest::newRow("version with v") << "version" << QJsonValue(QStringLiteral("v0.2.65"));
    QTest::newRow("version garbage") << "version" << QJsonValue(QStringLiteral("0.2.65-1-gabc"));
    QTest::newRow("version number") << "version" << QJsonValue(65);
    QTest::newRow("version missing") << "version" << QJsonValue(QJsonValue::Undefined);
    QTest::newRow("size zero") << "size" << QJsonValue(0);
    QTest::newRow("size negative") << "size" << QJsonValue(-3000);
    QTest::newRow("size fraction") << "size" << QJsonValue(3000.5);
    QTest::newRow("size string") << "size" << QJsonValue(QStringLiteral("3000"));
    QTest::newRow("size too large") << "size" << QJsonValue(2.0 * 1024 * 1024 * 1024);
    QTest::newRow("size missing") << "size" << QJsonValue(QJsonValue::Undefined);
    QTest::newRow("sha256 upper") << "sha256"
                                  << QJsonValue(QStringLiteral(
                                         "F541874101876255B4BAF3A739778D04CB9CBA25FFA38B30BC1FB8B0701F2A45"));
    QTest::newRow("sha256 short") << "sha256" << QJsonValue(QStringLiteral("f541874101876255"));
    QTest::newRow("sha256 newline")
        << "sha256"
        << QJsonValue(QStringLiteral("f541874101876255b4baf3a739778d04cb9cba25ffa38b30bc1fb8b0701f2a45\n"));
    QTest::newRow("sha256 not hex")
        << "sha256"
        << QJsonValue(QStringLiteral("g541874101876255b4baf3a739778d04cb9cba25ffa38b30bc1fb8b0701f2a45"));
    QTest::newRow("sha256 missing") << "sha256" << QJsonValue(QJsonValue::Undefined);
}

void TestUpdateManifest::refusesBadEntries()
{
    QFETCH(QString, field);
    QFETCH(QJsonValue, value);
    QJsonObject object = fixtureObject();
    QJsonObject platforms = object.value(QStringLiteral("platforms")).toObject();
    QJsonObject entry = platforms.value(QStringLiteral("macos-zip")).toObject();
    if (value.isUndefined()) {
        entry.remove(field);
    } else {
        entry.insert(field, value);
    }
    platforms.insert(QStringLiteral("macos-zip"), entry);
    object.insert(QStringLiteral("platforms"), platforms);
    UpdateManifest::Release release;
    QString error;
    QVERIFY(!UpdateManifest::parse(bytes(object), release, error));
    QVERIFY2(error.startsWith(QStringLiteral("macos-zip")), qPrintable(error));

    // The same entry as anything but an object.
    platforms.insert(QStringLiteral("macos-zip"), QJsonValue(QStringLiteral("khandaq-macos.zip")));
    object.insert(QStringLiteral("platforms"), platforms);
    QVERIFY(!UpdateManifest::parse(bytes(object), release, error));
}

void TestUpdateManifest::passesOverWhatItDoesNotKnow()
{
    QJsonObject object = fixtureObject();
    object.insert(QStringLiteral("released"), QStringLiteral("2026-11-20"));
    QJsonObject platforms = object.value(QStringLiteral("platforms")).toObject();
    // A platform of a later app, with a file this one would never take.
    platforms.insert(QStringLiteral("windows-arm64-installer"),
                     QJsonObject{{QStringLiteral("file"), QStringLiteral("other.exe")}});
    QJsonObject mac = platforms.value(QStringLiteral("macos-zip")).toObject();
    mac.insert(QStringLiteral("minimumSystem"), QStringLiteral("11.0"));
    platforms.insert(QStringLiteral("macos-zip"), mac);
    platforms.remove(QStringLiteral("linux-deb-amd64"));
    object.insert(QStringLiteral("platforms"), platforms);
    UpdateManifest::Release release;
    QString error;
    QVERIFY2(UpdateManifest::parse(bytes(object), release, error), qPrintable(error));
    QCOMPARE(release.platforms.size(), 4);
    QVERIFY(!release.platforms.contains(QStringLiteral("windows-arm64-installer")));
    QVERIFY(!release.platforms.contains(QStringLiteral("linux-deb-amd64")));
}

void TestUpdateManifest::refusesWhatIsNotJson()
{
    for (const QByteArray& text :
         {QByteArray(), QByteArray("[]"), QByteArray("null"), QByteArray("{"),
          fixtureBytes + QByteArray("{}"), QByteArray("\xef\xbb\xbf") + QByteArray("x"),
          QByteArray(70000, ' ')}) {
        UpdateManifest::Release release;
        QString error;
        QVERIFY(!UpdateManifest::parse(text, release, error));
    }
    QJsonObject object = fixtureObject();
    object.insert(QStringLiteral("platforms"), QJsonValue(QStringLiteral("all")));
    UpdateManifest::Release release;
    QString error;
    QVERIFY(!UpdateManifest::parse(bytes(object), release, error));
}

void TestUpdateManifest::picksTheNotesOfTheLanguage()
{
    UpdateManifest::Release release;
    QString error;
    QVERIFY(UpdateManifest::parse(fixtureBytes, release, error));
    QCOMPARE(UpdateManifest::notesFor(release, QStringLiteral("ru_RU")), release.notesRussian);
    QCOMPARE(UpdateManifest::notesFor(release, QStringLiteral("ru")), release.notesRussian);
    QCOMPARE(UpdateManifest::notesFor(release, QStringLiteral("en_US")), release.notesEnglish);
    QCOMPARE(UpdateManifest::notesFor(release, QStringLiteral("ar")), release.notesEnglish);
    QCOMPARE(UpdateManifest::notesFor(release, QStringLiteral("zh_CN")), release.notesEnglish);
}

void TestUpdateManifest::offersOnlyANewerVersion()
{
    UpdateManifest::Release release;
    QString error;
    QVERIFY(UpdateManifest::parse(fixtureBytes, release, error));
    const QString mac = QStringLiteral("macos-zip");

    UpdateManifest::Decision decision =
        UpdateManifest::decide(release, mac, version("0.2.64"), UpdateManifest::Version{});
    QVERIFY(decision.offer);
    QCOMPARE(decision.entry.file, QStringLiteral("khandaq-macos.zip"));
    QCOMPARE(UpdateManifest::toString(decision.highestSeen), QStringLiteral("0.2.65"));

    decision = UpdateManifest::decide(release, mac, version("0.2.65"), version("0.2.65"));
    QVERIFY(!decision.offer);
    decision = UpdateManifest::decide(release, mac, version("0.2.66"), UpdateManifest::Version{});
    QVERIFY(!decision.offer);
    QCOMPARE(UpdateManifest::toString(decision.highestSeen), QStringLiteral("0.2.65"));
    // 0.2.10 is newer than 0.2.9 as a number, though not as text.
    QJsonObject object = fixtureObject();
    QJsonObject platforms = object.value(QStringLiteral("platforms")).toObject();
    QJsonObject entry = platforms.value(mac).toObject();
    entry.insert(QStringLiteral("version"), QStringLiteral("0.2.10"));
    platforms.insert(mac, entry);
    object.insert(QStringLiteral("platforms"), platforms);
    QVERIFY(UpdateManifest::parse(bytes(object), release, error));
    QVERIFY(UpdateManifest::decide(release, mac, version("0.2.9"), UpdateManifest::Version{}).offer);
    // The Linux package of this release is still the one before: nothing for it.
    QVERIFY(UpdateManifest::parse(fixtureBytes, release, error));
    QVERIFY(!UpdateManifest::decide(release, QStringLiteral("linux-deb-amd64"), version("0.2.64"),
                                    UpdateManifest::Version{})
                 .offer);
}

void TestUpdateManifest::neverGoesBelowTheHighestSeen()
{
    UpdateManifest::Release release;
    QString error;
    QVERIFY(UpdateManifest::parse(fixtureBytes, release, error));
    const QString mac = QStringLiteral("macos-zip");
    // 0.2.66 was seen once; an older signed manifest (0.2.65) served again offers nothing, even to
    // a copy still at 0.2.63, and what was seen is kept.
    UpdateManifest::Decision decision =
        UpdateManifest::decide(release, mac, version("0.2.63"), version("0.2.66"));
    QVERIFY(!decision.offer);
    QCOMPARE(UpdateManifest::toString(decision.highestSeen), QStringLiteral("0.2.66"));
    // Equal to what was seen: the same release served again is still on offer.
    decision = UpdateManifest::decide(release, mac, version("0.2.63"), version("0.2.65"));
    QVERIFY(decision.offer);
    QCOMPARE(UpdateManifest::toString(decision.highestSeen), QStringLiteral("0.2.65"));
}

void TestUpdateManifest::offersNothingForAMissingPlatform()
{
    UpdateManifest::Release release;
    QString error;
    QVERIFY(UpdateManifest::parse(fixtureBytes, release, error));
    for (const QString& platform : {QString(), QStringLiteral("windows-arm64-installer")}) {
        const UpdateManifest::Decision decision =
            UpdateManifest::decide(release, platform, version("0.0.1"), version("0.2.60"));
        QVERIFY(!decision.offer);
        QCOMPARE(UpdateManifest::toString(decision.highestSeen), QStringLiteral("0.2.60"));
    }
}

void TestUpdateManifest::namesTheFilesBesideTheManifest()
{
    const QUrl manifest(QStringLiteral("https://khandaq.org/downloads/khandaq-desktop-update.json"));
    QCOMPARE(UpdateEndpoint::signatureUrl(manifest),
             QUrl(QStringLiteral("https://khandaq.org/downloads/khandaq-desktop-update.json.sig")));
    QCOMPARE(UpdateEndpoint::artifactUrl(manifest, QStringLiteral("khandaq-macos.zip")),
             QUrl(QStringLiteral("https://khandaq.org/downloads/khandaq-macos.zip")));
    const QUrl local(QStringLiteral("http://127.0.0.1:8123/qa/khandaq-desktop-update.json"));
    QCOMPARE(UpdateEndpoint::artifactUrl(local, QStringLiteral("khandaq-windows-installer.exe")),
             QUrl(QStringLiteral("http://127.0.0.1:8123/qa/khandaq-windows-installer.exe")));
}

void TestUpdateManifest::acceptsQaAddressesOnlyOverHttpsOrLoopback()
{
    QVERIFY(UpdateEndpoint::acceptsQaUrl(QUrl(QStringLiteral("https://qa.example/u.json"))));
    QVERIFY(UpdateEndpoint::acceptsQaUrl(QUrl(QStringLiteral("http://127.0.0.1:8000/u.json"))));
    for (const char* url : {"http://qa.example/u.json", "http://localhost/u.json",
                            "http://127.0.0.2/u.json", "http://[::1]/u.json", "ftp://127.0.0.1/u.json",
                            "file:///tmp/u.json", "https://user:pass@qa.example/u.json", "u.json", ""}) {
        QVERIFY2(!UpdateEndpoint::acceptsQaUrl(QUrl(QString::fromLatin1(url))), url);
    }
}

void TestUpdateManifest::releaseBuildIgnoresTheQaVariable()
{
    // The documented name (docs/DESKTOP-SIGNING.md); a release build does not even contain it.
    const char qaUrlVariable[] = "KHANDAQ_UPDATE_MANIFEST_URL";
    const QUrl production(QStringLiteral("https://khandaq.org/downloads/khandaq-desktop-update.json"));
    qputenv(qaUrlVariable, "http://127.0.0.1:9/evil/khandaq-desktop-update.json");
    const QUrl chosen = UpdateEndpoint::manifestUrl();
    const QVector<QByteArray> keys = UpdateEndpoint::trustedKeys();
    qunsetenv(qaUrlVariable);
    if (UpdateEndpoint::qaBuild()) {
        // Only a build made with UPDATE_CHECK_QA, which is never released, follows it.
        QCOMPARE(chosen, QUrl(QStringLiteral("http://127.0.0.1:9/evil/khandaq-desktop-update.json")));
        QCOMPARE(keys.size(), 2);
        return;
    }
    QCOMPARE(chosen, production);
    // The release key alone.
    QCOMPARE(keys.size(), 1);
    QCOMPARE(UpdateEndpoint::manifestUrl(), production);
}

QTEST_GUILESS_MAIN(TestUpdateManifest)
#include "updatemanifest_test.moc"
