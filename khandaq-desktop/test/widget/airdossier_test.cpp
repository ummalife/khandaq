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

#include "src/widget/air/airdossier.h"
#include "src/widget/air/airwidgets.h"

#include <QApplication>
#include <QDesktopServices>
#include <QFile>
#include <QList>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QUrl>
#include <QtTest/QtTest>

namespace {

/** The button of the "Files" section for the file called name: its label is "name (size)". */
AirButton* fileButton(const AirDossier& dossier, const QString& name)
{
    for (AirButton* const button : dossier.findChildren<AirButton*>()) {
        if (button->text().startsWith(name + QLatin1String(" ("))) {
            return button;
        }
    }
    return nullptr;
}

AirDossierFile dossierFile(const QString& name, const QString& path, qint64 size)
{
    AirDossierFile file;
    file.name = name;
    file.path = path;
    file.size = size;
    return file;
}

} // namespace

/** Takes the place of the system for file links: a file opened directly lands here, unopened. */
class DirectOpens : public QObject
{
    Q_OBJECT
public:
    QList<QUrl> urls;

public slots:
    void open(const QUrl& url)
    {
        urls.append(url);
    }
};

class TestAirDossier : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void init();
    void cleanupTestCase();
    void receivedFileIsHandedToTheChat();
    void fileThatIsNotHereCannotBeOpened();

private:
    DirectOpens directOpens;
};

void TestAirDossier::initTestCase()
{
    QDesktopServices::setUrlHandler(QStringLiteral("file"), &directOpens, "open");
    // The guard holds only while Qt hands file links to it; a file that does not exist tells.
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    QDesktopServices::openUrl(QUrl::fromLocalFile(dir.filePath(QStringLiteral("absent.txt"))));
    QCOMPARE(directOpens.urls.size(), 1);
}

void TestAirDossier::init()
{
    directOpens.urls.clear();
}

void TestAirDossier::cleanupTestCase()
{
    QDesktopServices::unsetUrlHandler(QStringLiteral("file"));
}

/**
 * A file of the "Files" section came from someone else: the dossier hands its path to the chat,
 * which opens it as the file list does and asks before an executable. Clicking it must not open
 * the file straight away.
 */
void TestAirDossier::receivedFileIsHandedToTheChat()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("setup.command"));
    QFile file(path);
    QVERIFY(file.open(QIODevice::WriteOnly));
    QCOMPARE(file.write("exit 0\n"), qint64(7));
    file.close();

    QWidget host;
    AirDossier dossier(&host);
    AirDossierInfo info;
    info.name = QStringLiteral("Omar");
    info.files.append(dossierFile(QStringLiteral("setup.command"), path, 7));
    dossier.setInfo(info);

    AirButton* const button = fileButton(dossier, QStringLiteral("setup.command"));
    QVERIFY(button != nullptr);
    QVERIFY(button->isEnabled());

    QSignalSpy requests(&dossier, &AirDossier::fileOpenRequested);
    button->click();
    QCOMPARE(requests.count(), 1);
    QCOMPARE(requests.at(0).at(0).toString(), path);
    QVERIFY(directOpens.urls.isEmpty());
}

/** A file that was not received yet, or was moved away, is listed but cannot be clicked. */
void TestAirDossier::fileThatIsNotHereCannotBeOpened()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());

    QWidget host;
    AirDossier dossier(&host);
    AirDossierInfo info;
    info.name = QStringLiteral("Omar");
    info.files.append(
        dossierFile(QStringLiteral("moved.pdf"), dir.filePath(QStringLiteral("moved.pdf")), 10));
    info.files.append(dossierFile(QStringLiteral("incoming.pdf"), QString(), 10));
    dossier.setInfo(info);

    QSignalSpy requests(&dossier, &AirDossier::fileOpenRequested);
    for (const QString& name : {QStringLiteral("moved.pdf"), QStringLiteral("incoming.pdf")}) {
        AirButton* const button = fileButton(dossier, name);
        QVERIFY2(button != nullptr, qPrintable(name));
        QVERIFY2(!button->isEnabled(), qPrintable(name));
        button->click();
    }
    QCOMPARE(requests.count(), 0);
    QVERIFY(directOpens.urls.isEmpty());
}

int main(int argc, char* argv[])
{
    // The widgets need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QApplication app(argc, argv);
    TestAirDossier test;
    return QTest::qExec(&test, argc, argv);
}

#include "airdossier_test.moc"
