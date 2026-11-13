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

#include "src/model/air/airpreferences.h"
#include "src/persistence/inotificationsettings.h"
#include "src/platform/traynotify.h"

#include <QDir>
#include <QFile>
#include <QSet>
#include <QtTest/QtTest>

namespace {
const QString contactFolder = QStringLiteral("/files/from-maryam");
const QString globalFolder = QStringLiteral("/files/chosen");
const QString home = QStringLiteral("/home/amina");
const QString downloads = QStringLiteral("/home/amina/Downloads");
const QString downloadsName = QStringLiteral("Downloads");
constexpr qint64 megabyte = Air::bytesPerMegabyte;

/** Notifications on or off, as the switches of Settings > Notifications leave them. */
class NotificationSwitches : public INotificationSettings
{
public:
    bool notify = true;
    bool desktopNotify = true;

    bool getNotify() const override
    {
        return notify;
    }
    void setNotify(bool newValue) override
    {
        notify = newValue;
    }
    bool getShowWindow() const override
    {
        return false;
    }
    void setShowWindow(bool) override {}
    bool getDesktopNotify() const override
    {
        return desktopNotify;
    }
    void setDesktopNotify(bool enabled) override
    {
        desktopNotify = enabled;
    }
    bool getNotifySound() const override
    {
        return false;
    }
    void setNotifySound(bool) override {}
    bool getNotifyHide() const override
    {
        return false;
    }
    void setNotifyHide(bool) override {}
    bool getBusySound() const override
    {
        return false;
    }
    void setBusySound(bool) override {}
    bool getGroupAlwaysNotify() const override
    {
        return false;
    }
    void setGroupAlwaysNotify(bool) override {}
};
} // namespace

/**
 * The rules behind Settings (E18): the three choices of Download attachments and the folder a
 * file from a contact goes to, the list of members hidden in groups, the languages offered, and
 * when the tray icon shows a system notification (E22).
 */
class TestAirPreferences : public QObject
{
    Q_OBJECT
private slots:
    void storedSettingsMakeTheirChoice();
    void everyChoiceReadsBackAsItself();
    void smallFilesKeepsAnEarlierLimit();
    void neverKeepsTheLimitForLater();
    void filesFromAContactGoToTheirFolder();
    void filesGoToTheChosenFolderElseDownloads();
    void neverLeavesFilesWaiting();
    void aNewInstallationLetsFilesWait();
    void theLimitCountsTheFileItself();
    void theDownloadsFolderGoesByItsName();
    void anotherFolderShowsItsPath();
    void hiddenKeysAreListedOnceInOrder();
    void showingAgainRemovesEveryEntryOfTheKey();
    void everyOfferedLanguageHasItsTranslation();
    void languagesAreNamedInThemselves();
    void noTwoLanguagesReadTheSame();
    void aNotificationNeedsBothSwitchesAndAnIcon();
    void withoutAnIconNothingIsShown();
};

void TestAirPreferences::storedSettingsMakeTheirChoice()
{
    QCOMPARE(Air::attachmentMode(true, 0), Air::AttachmentMode::Always);
    QCOMPARE(Air::attachmentMode(true, 10 * megabyte), Air::AttachmentMode::Small);
    QCOMPARE(Air::attachmentMode(false, 0), Air::AttachmentMode::Never);
    QCOMPARE(Air::attachmentMode(false, 10 * megabyte), Air::AttachmentMode::Never);
}

void TestAirPreferences::everyChoiceReadsBackAsItself()
{
    for (const Air::AttachmentMode mode :
         {Air::AttachmentMode::Always, Air::AttachmentMode::Small, Air::AttachmentMode::Never}) {
        for (const qint64 previous : {qint64{0}, 3 * megabyte}) {
            const Air::AttachmentRule rule = Air::attachmentRule(mode, previous);
            QCOMPARE(Air::attachmentMode(rule.autoSave, rule.maxSize), mode);
        }
    }
    const Air::AttachmentRule always = Air::attachmentRule(Air::AttachmentMode::Always, 3 * megabyte);
    QVERIFY(always.autoSave);
    QCOMPARE(always.maxSize, qint64{0});
}

void TestAirPreferences::smallFilesKeepsAnEarlierLimit()
{
    QCOMPARE(Air::attachmentRule(Air::AttachmentMode::Small, 3 * megabyte).maxSize, 3 * megabyte);
    QCOMPARE(Air::attachmentRule(Air::AttachmentMode::Small, 0).maxSize, Air::smallAttachmentDefault);
    QVERIFY(Air::attachmentRule(Air::AttachmentMode::Small, 0).autoSave);
}

void TestAirPreferences::neverKeepsTheLimitForLater()
{
    const Air::AttachmentRule never = Air::attachmentRule(Air::AttachmentMode::Never, 3 * megabyte);
    QVERIFY(!never.autoSave);
    QCOMPARE(never.maxSize, 3 * megabyte);
    // Small chosen again starts from that limit.
    QCOMPARE(Air::attachmentRule(Air::AttachmentMode::Small, never.maxSize).maxSize, 3 * megabyte);
}

void TestAirPreferences::filesFromAContactGoToTheirFolder()
{
    QCOMPARE(Air::autoAcceptFolder(contactFolder, true, globalFolder, downloads, 0, 50 * megabyte),
             contactFolder);
    // A folder chosen for the contact takes their files even when the rest wait for Accept.
    QCOMPARE(Air::autoAcceptFolder(contactFolder, false, globalFolder, downloads, 0, megabyte),
             contactFolder);
    QCOMPARE(Air::autoAcceptFolder(contactFolder, false, globalFolder, downloads, megabyte, 2 * megabyte),
             QString());
}

void TestAirPreferences::filesGoToTheChosenFolderElseDownloads()
{
    QCOMPARE(Air::autoAcceptFolder(QString(), true, globalFolder, downloads, 0, megabyte),
             globalFolder);
    QCOMPARE(Air::autoAcceptFolder(QString(), true, QString(), downloads, 0, megabyte), downloads);
}

void TestAirPreferences::neverLeavesFilesWaiting()
{
    // The old General tab had this switch, and nothing read it: every file was saved anyway.
    QCOMPARE(Air::autoAcceptFolder(QString(), false, globalFolder, downloads, 0, 1), QString());
    QCOMPARE(Air::autoAcceptFolder(QString(), false, QString(), downloads, 0, 1), QString());
}

void TestAirPreferences::aNewInstallationLetsFilesWait()
{
    // Settings::loadGlobal falls back to this when nothing is stored yet: a new installation saves
    // nothing a contact sends until Accept, and its settings page shows Never.
    QVERIFY(!Air::autoSaveDefault);
    QCOMPARE(Air::attachmentMode(Air::autoSaveDefault, 0), Air::AttachmentMode::Never);
    QCOMPARE(Air::autoAcceptFolder(QString(), Air::autoSaveDefault, globalFolder, downloads, 0, 1),
             QString());
    // A folder chosen for a contact still takes their files: that was chosen, not defaulted.
    QCOMPARE(Air::autoAcceptFolder(contactFolder, Air::autoSaveDefault, globalFolder, downloads, 0, 1),
             contactFolder);
}

void TestAirPreferences::theLimitCountsTheFileItself()
{
    const qint64 limit = 10 * megabyte;
    QCOMPARE(Air::autoAcceptFolder(QString(), true, QString(), downloads, limit, limit), downloads);
    QCOMPARE(Air::autoAcceptFolder(QString(), true, QString(), downloads, limit, limit + 1), QString());
    // No limit at all takes any size.
    QCOMPARE(Air::autoAcceptFolder(QString(), true, QString(), downloads, 0, 4000 * megabyte),
             downloads);
}

void TestAirPreferences::theDownloadsFolderGoesByItsName()
{
    QCOMPARE(Air::folderLabel(downloads, downloads, downloadsName, home, true), downloadsName);
    QCOMPARE(Air::folderLabel(downloads + QStringLiteral("/"), downloads, downloadsName, home, true),
             downloadsName);
    // No folder chosen: files go to the downloads folder.
    QCOMPARE(Air::folderLabel(QString(), downloads, downloadsName, home, true), downloadsName);
}

void TestAirPreferences::anotherFolderShowsItsPath()
{
    const QString documents = QStringLiteral("/home/amina/Documents/Khandaq");
    QCOMPARE(Air::folderLabel(documents, downloads, downloadsName, home, true),
             QDir::toNativeSeparators(QStringLiteral("~/Documents/Khandaq")));
    QCOMPARE(Air::folderLabel(documents, downloads, downloadsName, home, false),
             QDir::toNativeSeparators(documents));
    QCOMPARE(Air::folderLabel(home, downloads, downloadsName, home, true), QStringLiteral("~"));
    // A folder whose name only starts like the home folder is not in it.
    QCOMPARE(Air::folderLabel(QStringLiteral("/home/aminat/files"), downloads, downloadsName, home, true),
             QDir::toNativeSeparators(QStringLiteral("/home/aminat/files")));
    QCOMPARE(Air::folderLabel(QStringLiteral("/media/usb/inbox"), downloads, downloadsName, home, true),
             QDir::toNativeSeparators(QStringLiteral("/media/usb/inbox")));
}

void TestAirPreferences::hiddenKeysAreListedOnceInOrder()
{
    const QString first = QString(64, QLatin1Char('A'));
    const QString second = QString(64, QLatin1Char('B'));
    // The old free-text field left blank lines and spaces; an empty list read back as one blank.
    QCOMPARE(Air::hiddenKeys({QString()}), QStringList());
    QCOMPARE(Air::hiddenKeys({second, QStringLiteral("  ") + first, QString(), second,
                              first + QStringLiteral("\t")}),
             (QStringList{second, first}));
}

void TestAirPreferences::showingAgainRemovesEveryEntryOfTheKey()
{
    const QString first = QString(64, QLatin1Char('A'));
    const QString second = QString(64, QLatin1Char('B'));
    const QStringList stored{first, second, QStringLiteral(" ") + first};
    QCOMPARE(Air::withoutHiddenKey(stored, first), (QStringList{second}));
    QCOMPARE(Air::withoutHiddenKey(stored, QStringLiteral(" ") + second + QStringLiteral(" ")),
             (QStringList{first, QStringLiteral(" ") + first}));
    QCOMPARE(Air::withoutHiddenKey(stored, QString(64, QLatin1Char('C'))), stored);
}

void TestAirPreferences::everyOfferedLanguageHasItsTranslation()
{
    Q_INIT_RESOURCE(translations);
    const QStringList languages = Air::interfaceLanguages();
    QVERIFY(languages.contains(QStringLiteral("en")));
    QVERIFY(languages.contains(QStringLiteral("ru")));
    QVERIFY(languages.contains(QStringLiteral("ar")));
    QVERIFY(languages.contains(QStringLiteral("zh_CN")));
    QSet<QString> once;
    for (const QString& language : languages) {
        once.insert(language);
    }
    QCOMPARE(once.size(), languages.size());
    for (const QString& language : languages) {
        QVERIFY2(QFile::exists(QStringLiteral(":translations/") + language + QStringLiteral(".qm")),
                 qPrintable(language));
    }
}

void TestAirPreferences::languagesAreNamedInThemselves()
{
    QCOMPARE(Air::languageName(QStringLiteral("ru")), QStringLiteral("Русский"));
    QCOMPARE(Air::languageName(QStringLiteral("de")), QStringLiteral("Deutsch"));
    QCOMPARE(Air::languageName(QStringLiteral("eo")), QStringLiteral("Esperanto"));
    QCOMPARE(Air::languageName(QStringLiteral("jbo")), QStringLiteral("Lojban"));
    QCOMPARE(Air::languageName(QStringLiteral("pr")), QStringLiteral("Pirate"));
    // Qt says "American English", "español de España", and "português" for both translations.
    QCOMPARE(Air::languageName(QStringLiteral("en")), QStringLiteral("English"));
    QCOMPARE(Air::languageName(QStringLiteral("es")), QStringLiteral("Espa\u00F1ol"));
    QCOMPARE(Air::languageName(QStringLiteral("pt")), QStringLiteral("Portugu\u00EAs (Portugal)"));
    QCOMPARE(Air::languageName(QStringLiteral("pt_BR")), QStringLiteral("Portugu\u00EAs (Brasil)"));
    // Tamazight, in Tifinagh as the translation is written; Qt has no name for it.
    QCOMPARE(Air::languageName(QStringLiteral("ber")),
             QStringLiteral("\u2D5C\u2D30\u2D4E\u2D30\u2D63\u2D49\u2D56\u2D5C"));
    for (const QString& language : Air::interfaceLanguages()) {
        QVERIFY2(!Air::languageName(language).isEmpty(), qPrintable(language));
    }
}

void TestAirPreferences::noTwoLanguagesReadTheSame()
{
    QSet<QString> names;
    for (const QString& language : Air::interfaceLanguages()) {
        const QString name = Air::languageName(language);
        QVERIFY2(!names.contains(name), qPrintable(language));
        names.insert(name);
        // A language Qt does not know would be named by its code.
        QVERIFY2(name != language, qPrintable(language));
    }
}

void TestAirPreferences::aNotificationNeedsBothSwitchesAndAnIcon()
{
    QVERIFY(TrayNotify::wanted(true, true, true));
    QVERIFY(!TrayNotify::wanted(false, true, true));
    QVERIFY(!TrayNotify::wanted(true, false, true));
    QVERIFY(!TrayNotify::wanted(true, true, false));
}

void TestAirPreferences::withoutAnIconNothingIsShown()
{
    NotificationSwitches switches;
    TrayNotify notifier(switches);
    QVERIFY(!notifier.canShow());
    notifier.setTrayIcon(nullptr);
    QVERIFY(!notifier.canShow());
    NotificationData data;
    data.title = QStringLiteral("Maryam Hasanova");
    data.message = QStringLiteral("See you at the library");
    // No icon to carry it: nothing is shown, and nothing breaks.
    notifier.notifyMessage(data);
}

QTEST_GUILESS_MAIN(TestAirPreferences)
#include "airpreferences_test.moc"
