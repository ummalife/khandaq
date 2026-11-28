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

#include "src/persistence/paths.h"
#include "src/persistence/settings.h"
#include "src/widget/tool/imessageboxmanager.h"

#include <QApplication>
#include <QDir>
#include <QFile>
#include <QSettings>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QtTest/QtTest>

#include <memory>

namespace {
/** Shows no dialog: a Settings needs someone to report to, and the test has nobody to ask. */
class QuietMessages : public IMessageBoxManager
{
public:
    void showInfo(const QString&, const QString&) override {}
    void showWarning(const QString&, const QString&) override {}
    void showError(const QString&, const QString&) override {}
    bool askQuestion(const QString&, const QString&, bool defaultAns, bool, bool) override
    {
        return defaultAns;
    }
    bool askQuestion(const QString&, const QString&, const QString&, const QString&,
                     bool defaultAns, bool) override
    {
        return defaultAns;
    }
    void confirmExecutableOpen(const QFileInfo&) override {}
};
} // namespace

class TestSettings : public QObject
{
    Q_OBJECT
public:
    explicit TestSettings(const QString& home_)
        : home{home_}
    {
    }

private slots:
    void initTestCase();
    void init();
    void aNewSettingOpensNoWindowOnAMessage();
    void aSettingFromBeforeOpensNoWindowOnAMessage();
    void openingTheWindowOnAMessageIsKeptOnceChosen();

private:
    QString iniPath() const;
    void writeIni(const QString& group, const QString& key, const QVariant& value) const;

    const QString home;
    QuietMessages messages;
};

void TestSettings::initTestCase()
{
    // A Settings reads and writes the settings file of the Khandaq of this computer: the test runs
    // only when that file lies in the home of the test.
    const QString folder = QDir::cleanPath(Paths().getSettingsDirPath());
    if (home.isEmpty() || !folder.startsWith(home + QLatin1Char('/'))) {
        QSKIP("The settings would not stay in the test's home");
    }
    QVERIFY(QDir().mkpath(folder));
}

void TestSettings::init()
{
    QFile::remove(iniPath());
}

QString TestSettings::iniPath() const
{
    // Settings::globalSettingsFile, which is private.
    return QDir(Paths().getSettingsDirPath()).filePath(QStringLiteral("khandaq.ini"));
}

void TestSettings::writeIni(const QString& group, const QString& key, const QVariant& value) const
{
    QSettings ini(iniPath(), QSettings::IniFormat);
    ini.setValue(QStringLiteral("Version/settingsVersion"), 1);
    ini.setValue(group + QLatin1Char('/') + key, value);
    ini.sync();
    QCOMPARE(ini.status(), QSettings::NoError);
}

void TestSettings::aNewSettingOpensNoWindowOnAMessage()
{
    Settings settings(messages);
    QVERIFY(!settings.getShowWindow());
}

void TestSettings::aSettingFromBeforeOpensNoWindowOnAMessage()
{
    // As 0.2.78 and older wrote it for everyone who had not turned it off: the window hidden in
    // the tray came up and in front at every message (inbox #30).
    writeIni(QStringLiteral("GUI"), QStringLiteral("showWindow"), true);
    Settings settings(messages);
    QVERIFY(!settings.getShowWindow());
}

void TestSettings::openingTheWindowOnAMessageIsKeptOnceChosen()
{
    writeIni(QStringLiteral("GUI"), QStringLiteral("showWindow"), false);
    {
        Settings settings(messages);
        settings.setShowWindow(true);
        settings.saveGlobal();
    } // The Settings saves before it goes.

    {
        const QSettings ini(iniPath(), QSettings::IniFormat);
        QCOMPARE(ini.value(QStringLiteral("GUI/openWindowOnMessage")).toBool(), true);
        QVERIFY(!ini.contains(QStringLiteral("GUI/showWindow")));
    }
    Settings again(messages);
    QVERIFY(again.getShowWindow());
}

int main(int argc, char* argv[])
{
    // Fonts need a platform, not a screen: the tests run without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    // A Settings reads and writes the settings of the home folder: the test gets a home of its own,
    // which goes with it.
    QTemporaryDir home;
    if (home.isValid()) {
        qputenv("HOME", QFile::encodeName(home.path()));
        QStandardPaths::setTestModeEnabled(true);
    }
    QApplication app(argc, argv);
    TestSettings test(home.isValid() ? QDir::cleanPath(home.path()) : QString());
    return QTest::qExec(&test, argc, argv);
}

#include "settings_test.moc"
