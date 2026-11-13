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

#include "src/widget/air/airyou.h"

#include "src/core/toxid.h"
#include "src/model/air/airconversation.h"
#include "src/model/profile/iprofileinfo.h"
#include "src/widget/air/airnewcontact.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airwidgets.h"

#include <QApplication>
#include <QLabel>
#include <QTextEdit>
#include <QtTest/QtTest>

using AirStrings::Text;

namespace {
const QString key =
    QStringLiteral("DC43B11E32BDAFE763689CBFDC35F5A49DE19F8F68441D9FFFB8AEAE3A827951");

/** IProfileInfo without a Tox: it keeps what the page sets and says what it was asked. */
class FakeProfileInfo : public QObject, public IProfileInfo
{
    Q_OBJECT
public:
    bool setPassword(const QString& password_) override
    {
        password = password_;
        encrypted = passwordWorks;
        return passwordWorks;
    }
    bool deletePassword() override
    {
        ++passwordRemovals;
        encrypted = !passwordWorks;
        return passwordWorks;
    }
    bool isEncrypted() const override
    {
        return encrypted;
    }

    void copyId() const override
    {
        ++idCopies;
    }
    ToxId getSelfId() const override
    {
        return ToxId(key + antispam + QStringLiteral("5908"));
    }
    void changeNospam() override
    {
        antispam = QStringLiteral("12345678");
        emit idChanged(getSelfId());
    }

    QString getUsername() const override
    {
        return username;
    }
    QString getStatusMessage() const override
    {
        return status;
    }
    // Tox takes a name or a status as it is, and says so, as Core does.
    void setUsername(const QString& name) override
    {
        ++nameSaves;
        if (refuseNames) {
            return;
        }
        username = name;
        emit usernameChanged(name);
    }
    void setStatusMessage(const QString& message) override
    {
        ++statusSaves;
        status = message;
        emit statusMessageChanged(message);
    }

    QString getProfileName() const override
    {
        return profileName;
    }
    QString getProfileFolder() const override
    {
        return QStringLiteral("/profiles/");
    }
    RenameResult renameProfile(const QString& name) override
    {
        renamedTo = name;
        if (renameResult == RenameResult::OK) {
            profileName = name;
        }
        return renameResult;
    }
    SaveResult exportProfile(const QString& path) const override
    {
        exportedTo = path;
        return saveResult;
    }
    void removeProfile() override
    {
        ++removals;
    }
    void logout() override
    {
        ++logouts;
    }
    void logoutToImport(const QString& path) override
    {
        importedFrom = path;
        ++logouts;
    }

    void copyQr(const QImage& image) const override
    {
        copiedQr = image;
    }
    SaveResult saveQr(const QImage& image, const QString& path) const override
    {
        savedQr = image;
        exportedTo = path;
        return saveResult;
    }

    SetAvatarResult setAvatar(const QString& path) override
    {
        avatarPath = path;
        return avatarResult;
    }
    void removeAvatar() override
    {
        ++avatarRemovals;
    }

    SIGNAL_IMPL(FakeProfileInfo, idChanged, const ToxId& id)
    SIGNAL_IMPL(FakeProfileInfo, usernameChanged, const QString& name)
    SIGNAL_IMPL(FakeProfileInfo, statusMessageChanged, const QString& message)

    QString antispam = QStringLiteral("297A3343");
    QString username = QStringLiteral("Amina Rakhimova");
    QString status = QStringLiteral("Back after lunch");
    QString profileName = QStringLiteral("amina");
    QString password;
    QString renamedTo;
    QString avatarPath;
    bool encrypted = true;
    bool passwordWorks = true;
    bool refuseNames = false;
    RenameResult renameResult = RenameResult::OK;
    SaveResult saveResult = SaveResult::OK;
    SetAvatarResult avatarResult = SetAvatarResult::OK;
    int nameSaves = 0;
    int statusSaves = 0;
    int passwordRemovals = 0;
    int removals = 0;
    int logouts = 0;
    QString importedFrom;
    int avatarRemovals = 0;
    mutable int idCopies = 0;
    mutable QString exportedTo;
    mutable QImage copiedQr;
    mutable QImage savedQr;
};

template <typename T>
T* named(QWidget* page, const char* name)
{
    return page->findChild<T*>(QLatin1String(name));
}

/** The words of every toast over the window, the newest last. */
QStringList toasts(QWidget* page)
{
    QStringList words;
    for (const AirToast* const toast : page->window()->findChildren<AirToast*>()) {
        words.append(toast->text());
    }
    return words;
}

/** The dialog open over the window, if there is one. */
AirDialog* openDialog(QWidget* page)
{
    for (AirDialog* const dialog : page->window()->findChildren<AirDialog*>()) {
        if (dialog->isVisible()) {
            return dialog;
        }
    }
    return nullptr;
}

bool dialogSays(AirDialog* dialog, const QString& text)
{
    for (const QLabel* const label : dialog->findChildren<QLabel*>()) {
        if (label->isVisible() && label->text().contains(text)) {
            return true;
        }
    }
    return false;
}

void press(AirDialog* dialog, const char* button)
{
    dialog->findChild<AirButton*>(QLatin1String(button))->click();
}

/** Types into a field as a person: all of it replaced by text, then Enter. */
void typeAndEnter(AirTextField* field, const QString& text)
{
    field->edit()->setFocus();
    field->edit()->selectAll();
    field->edit()->insertPlainText(text);
    QTest::keyClick(field->edit(), Qt::Key_Return);
}
} // namespace

class TestAirYouPage : public QObject
{
    Q_OBJECT
private slots:
    void showsTheIdAndItsParts();
    void copiesTheIdAndTheQrCode();
    void nameIsSavedWhenEditingEnds();
    void anEmptiedNameTakesTheNameBack();
    void aRefusedNameIsNotCalledSaved();
    void escapeTakesTheEditBack();
    void fieldsKeepToTheBytesToxCarries();
    void theStatusMayBeEmpty();
    void antispamAsksFirst();
    void passwordFollowsTheProfile();
    void removingThePasswordAsksFirst();
    void renameSaysWhatHappened();
    void exportAndSaveSayWhatHappened();
    void aPhotoThatCannotBeReadIsSaid();
    void logoutSaysWhatItMeans();
    void deletingAsksWithTheProfileName();
    void columnsStandSideBySideWhenThereIsRoom();
    void wordsStandAtTheStartEdge();
};

void TestAirYouPage::showsTheIdAndItsParts()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    QCOMPARE(named<QLabel>(&page, "youKey")->text(), Air::keyLines(key));
    QCOMPARE(named<QLabel>(&page, "youAntispamPart")->text(), QStringLiteral("297A 3343"));
    QCOMPARE(named<QLabel>(&page, "youCheck")->text(), QStringLiteral("5908"));
    // "tox:" and the 76 characters: version 5 of the code, 37 modules a side.
    QCOMPARE(named<AirQrCard>(&page, "youQr")->code().side, 37);
    QCOMPARE(named<AirTextField>(&page, "youName")->text(), info.username);
    QCOMPARE(named<AirTextField>(&page, "youStatus")->text(), info.status);
    QCOMPARE(named<QLabel>(&page, "youFileName")->text(), QStringLiteral("amina.tox"));
}

void TestAirYouPage::copiesTheIdAndTheQrCode()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.resize(1100, 900);
    page.show();
    named<AirButton>(&page, "youCopyId")->click();
    QCOMPARE(info.idCopies, 1);
    QVERIFY(toasts(&page).contains(AirStrings::text(Text::YouIdCopied)));
    named<AirButton>(&page, "youCopyQr")->click();
    // Twelve pixels a module with the quiet zone: a picture that prints and scans well.
    QCOMPARE(info.copiedQr.size(), QSize(45 * 12, 45 * 12));
    QVERIFY(toasts(&page).contains(AirStrings::text(Text::YouQrCopied)));
}

void TestAirYouPage::nameIsSavedWhenEditingEnds()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.resize(1100, 900);
    page.show();
    QApplication::setActiveWindow(&page);
    auto* const name = named<AirTextField>(&page, "youName");
    typeAndEnter(name, QStringLiteral("  Amina R.  "));
    QCOMPARE(info.username, QStringLiteral("Amina R."));
    QCOMPARE(name->text(), QStringLiteral("Amina R."));
    QVERIFY(toasts(&page).contains(AirStrings::text(Text::YouSaved)));

    // Leaving the field saves what it holds, as Enter does.
    name->edit()->setFocus();
    name->edit()->selectAll();
    name->edit()->insertPlainText(QStringLiteral("Amina"));
    named<AirTextField>(&page, "youStatus")->edit()->setFocus();
    QCOMPARE(info.username, QStringLiteral("Amina"));
    QCOMPARE(info.nameSaves, 2);
}

void TestAirYouPage::anEmptiedNameTakesTheNameBack()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.show();
    QApplication::setActiveWindow(&page);
    auto* const name = named<AirTextField>(&page, "youName");
    typeAndEnter(name, QStringLiteral("   "));
    QCOMPARE(info.nameSaves, 0);
    QCOMPARE(name->text(), QStringLiteral("Amina Rakhimova"));
    // The same name again is nothing new either.
    typeAndEnter(name, QStringLiteral("Amina Rakhimova"));
    QCOMPARE(info.nameSaves, 0);
    QVERIFY(!toasts(&page).contains(AirStrings::text(Text::YouSaved)));
}

void TestAirYouPage::aRefusedNameIsNotCalledSaved()
{
    FakeProfileInfo info;
    info.refuseNames = true;
    AirYouPage page(info);
    page.show();
    QApplication::setActiveWindow(&page);
    auto* const name = named<AirTextField>(&page, "youName");
    typeAndEnter(name, QStringLiteral("Amina"));
    QCOMPARE(info.nameSaves, 1);
    // The field shows the name Tox kept.
    QCOMPARE(name->text(), QStringLiteral("Amina Rakhimova"));
    QVERIFY(!toasts(&page).contains(AirStrings::text(Text::YouSaved)));
}

void TestAirYouPage::escapeTakesTheEditBack()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.show();
    QApplication::setActiveWindow(&page);
    auto* const status = named<AirTextField>(&page, "youStatus");
    status->edit()->setFocus();
    status->edit()->selectAll();
    status->edit()->insertPlainText(QStringLiteral("Away"));
    QTest::keyClick(status->edit(), Qt::Key_Escape);
    QCOMPARE(status->text(), QStringLiteral("Back after lunch"));
    QCOMPARE(info.statusSaves, 0);
}

void TestAirYouPage::fieldsKeepToTheBytesToxCarries()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.show();
    auto* const name = named<AirTextField>(&page, "youName");
    name->edit()->setFocus();
    name->edit()->selectAll();
    name->edit()->insertPlainText(QString(200, QLatin1Char('a')));
    QCOMPARE(name->text().toUtf8().size(), Air::nameLimitBytes);
    // Two bytes a letter: 504 Cyrillic letters fill the 1007 bytes of a status but one.
    auto* const status = named<AirTextField>(&page, "youStatus");
    status->edit()->setFocus();
    status->edit()->selectAll();
    status->edit()->insertPlainText(QString(600, QChar(0x0414)));
    QCOMPARE(status->text().size(), 503);
    QVERIFY(status->text().toUtf8().size() <= Air::statusLimitBytes);
}

void TestAirYouPage::theStatusMayBeEmpty()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.show();
    QApplication::setActiveWindow(&page);
    auto* const status = named<AirTextField>(&page, "youStatus");
    status->edit()->setFocus();
    status->edit()->selectAll();
    status->edit()->textCursor().removeSelectedText();
    QTest::keyClick(status->edit(), Qt::Key_Return);
    QCOMPARE(info.statusSaves, 1);
    QCOMPARE(info.status, QString());
}

void TestAirYouPage::antispamAsksFirst()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.resize(1100, 900);
    page.show();
    QApplication::setActiveWindow(&page);
    named<AirButton>(&page, "youAntispam")->click();
    AirDialog* const question = openDialog(&page);
    QVERIFY(question);
    QVERIFY(dialogSays(question, AirStrings::text(Text::YouAntispamMessage)));
    // Cancel changes nothing.
    press(question, "dialogCancel");
    QCOMPARE(info.antispam, QStringLiteral("297A3343"));

    named<AirButton>(&page, "youAntispam")->click();
    press(openDialog(&page), "dialogConfirm");
    QCOMPARE(info.antispam, QStringLiteral("12345678"));
    QCOMPARE(named<QLabel>(&page, "youAntispamPart")->text(), QStringLiteral("1234 5678"));
    QVERIFY(toasts(&page).contains(AirStrings::text(Text::YouAntispamDone)));
}

void TestAirYouPage::passwordFollowsTheProfile()
{
    FakeProfileInfo info;
    info.encrypted = false;
    AirYouPage page(info);
    page.resize(1100, 900);
    page.show();
    auto* const button = named<AirButton>(&page, "youPassword");
    auto* const remove = named<AirButton>(&page, "youPasswordRemove");
    QCOMPARE(button->text(), AirStrings::text(Text::YouPasswordSet));
    QVERIFY(remove->isHidden());

    page.applyNewPassword(QStringLiteral("correct horse"));
    QCOMPARE(info.password, QStringLiteral("correct horse"));
    QCOMPARE(button->text(), AirStrings::text(Text::YouPasswordChange));
    QVERIFY(!remove->isHidden());
    QVERIFY(toasts(&page).contains(AirStrings::text(Text::YouPasswordChanged)));

    // When the history cannot take it, a dialog names what failed.
    info.passwordWorks = false;
    page.applyNewPassword(QStringLiteral("battery staple"));
    AirDialog* const notice = openDialog(&page);
    QVERIFY(notice);
    QVERIFY(dialogSays(notice, AirStrings::text(Text::YouPasswordFailedTitle)));
}

void TestAirYouPage::removingThePasswordAsksFirst()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.resize(1100, 900);
    page.show();
    QApplication::setActiveWindow(&page);
    named<AirButton>(&page, "youPasswordRemove")->click();
    AirDialog* const question = openDialog(&page);
    QVERIFY(question);
    QVERIFY(dialogSays(question, AirStrings::text(Text::YouPasswordRemoveMessage)));
    // A question that removes something starts on Cancel.
    QCOMPARE(QApplication::focusWidget()->objectName(), QStringLiteral("dialogCancel"));
    press(question, "dialogConfirm");
    QCOMPARE(info.passwordRemovals, 1);
    QVERIFY(named<AirButton>(&page, "youPasswordRemove")->isHidden());
    QVERIFY(toasts(&page).contains(AirStrings::text(Text::YouPasswordRemoved)));
}

void TestAirYouPage::renameSaysWhatHappened()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.resize(1100, 900);
    page.show();
    page.applyRename(QStringLiteral("  amina  "));
    QVERIFY(info.renamedTo.isEmpty());

    page.applyRename(QStringLiteral(" amina-work "));
    QCOMPARE(info.renamedTo, QStringLiteral("amina-work"));
    QCOMPARE(named<QLabel>(&page, "youFileName")->text(), QStringLiteral("amina-work.tox"));
    QVERIFY(toasts(&page).contains(AirStrings::text(Text::YouRenamed)));

    info.renameResult = IProfileInfo::RenameResult::ProfileAlreadyExists;
    page.applyRename(QStringLiteral("home"));
    AirDialog* const exists = openDialog(&page);
    QVERIFY(exists);
    QVERIFY(dialogSays(exists, AirStrings::text(Text::YouRenameExists).arg(QStringLiteral("home"))));
    press(exists, "dialogConfirm");

    info.renameResult = IProfileInfo::RenameResult::Error;
    page.applyRename(QStringLiteral("home"));
    QVERIFY(dialogSays(openDialog(&page), AirStrings::text(Text::YouRenameError)));
}

void TestAirYouPage::exportAndSaveSayWhatHappened()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.resize(1100, 900);
    page.show();
    page.applyExport(QStringLiteral("/copies/amina.tox"));
    QCOMPARE(info.exportedTo, QStringLiteral("/copies/amina.tox"));
    QVERIFY(toasts(&page).contains(AirStrings::text(Text::YouExported)));
    page.applySaveQr(QStringLiteral("/copies/amina.png"));
    QCOMPARE(info.savedQr.size(), QSize(45 * 12, 45 * 12));
    QVERIFY(toasts(&page).contains(AirStrings::text(Text::YouQrSaved)));

    info.saveResult = IProfileInfo::SaveResult::NoWritePermission;
    page.applyExport(QStringLiteral("/read-only/amina.tox"));
    AirDialog* const notice = openDialog(&page);
    QVERIFY(notice);
    QVERIFY(dialogSays(notice, AirStrings::text(Text::YouExportFailedTitle)));
    QVERIFY(dialogSays(notice, AirStrings::text(Text::YouNotWritable)));
}

void TestAirYouPage::aPhotoThatCannotBeReadIsSaid()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.resize(1100, 900);
    page.show();
    page.applyPhoto(QStringLiteral("/photos/me.jpg"));
    QCOMPARE(info.avatarPath, QStringLiteral("/photos/me.jpg"));
    QVERIFY(!openDialog(&page));

    info.avatarResult = IProfileInfo::SetAvatarResult::CanNotRead;
    page.applyPhoto(QStringLiteral("/photos/notes.txt"));
    AirDialog* const unreadable = openDialog(&page);
    QVERIFY(unreadable);
    QVERIFY(dialogSays(unreadable, AirStrings::text(Text::YouPhotoUnreadable)));
    press(unreadable, "dialogConfirm");

    info.avatarResult = IProfileInfo::SetAvatarResult::TooLarge;
    page.applyPhoto(QStringLiteral("/photos/huge.png"));
    QVERIFY(dialogSays(openDialog(&page), AirStrings::text(Text::YouPhotoTooLarge)));
}

void TestAirYouPage::logoutSaysWhatItMeans()
{
    FakeProfileInfo info;
    info.encrypted = false;
    AirYouPage page(info);
    page.resize(1100, 900);
    page.show();
    QApplication::setActiveWindow(&page);
    // Without a password, the note under the button says who can get back in.
    const QString open = AirStrings::text(Text::YouLogoutMessageNoPassword);
    bool said = false;
    for (const QLabel* const label : page.findChildren<QLabel*>()) {
        said = said || label->text() == open;
    }
    QVERIFY(said);

    page.askLogout();
    AirDialog* const question = openDialog(&page);
    QVERIFY(question);
    QVERIFY(dialogSays(question, open));
    press(question, "dialogCancel");
    QCOMPARE(info.logouts, 0);

    named<AirButton>(&page, "youLogout")->click();
    press(openDialog(&page), "dialogConfirm");
    QCOMPARE(info.logouts, 1);
}

void TestAirYouPage::deletingAsksWithTheProfileName()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.resize(1100, 900);
    page.show();
    QApplication::setActiveWindow(&page);
    named<AirButton>(&page, "youDelete")->click();
    AirDialog* const question = openDialog(&page);
    QVERIFY(question);
    QVERIFY(dialogSays(question, AirStrings::text(Text::YouDeleteMessage).arg(QStringLiteral("amina"))));
    QCOMPARE(QApplication::focusWidget()->objectName(), QStringLiteral("dialogCancel"));
    press(question, "dialogCancel");
    QCOMPARE(info.removals, 0);

    named<AirButton>(&page, "youDelete")->click();
    press(openDialog(&page), "dialogConfirm");
    QCOMPARE(info.removals, 1);
}

void TestAirYouPage::columnsStandSideBySideWhenThereIsRoom()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    page.resize(1100, 900);
    page.show();
    QCoreApplication::processEvents();
    QWidget* const qr = named<QWidget>(&page, "youQr");
    QWidget* const name = named<QWidget>(&page, "youName");
    const auto at = [&page](QWidget* widget) { return widget->mapTo(&page, QPoint(0, 0)); };
    // The QR code first, at the start edge, the fields beside it.
    QVERIFY(at(qr).x() < at(name).x());
    QVERIFY(at(name).y() < at(qr).y() + qr->height());

    // Narrow: the fields go under the code.
    page.resize(560, 900);
    QCoreApplication::processEvents();
    QVERIFY(at(name).y() > at(qr).y() + qr->height());

    // Right to left: the code stands at the right, the start edge there.
    page.setLayoutDirection(Qt::RightToLeft);
    page.resize(1100, 900);
    QCoreApplication::processEvents();
    QVERIFY(at(qr).x() > at(name).x());
}

void TestAirYouPage::wordsStandAtTheStartEdge()
{
    FakeProfileInfo info;
    AirYouPage page(info);
    auto* const file = named<QLabel>(&page, "youFileName");
    auto* const key = named<QLabel>(&page, "youKey");
    const auto edge = [](QLabel* label) {
        return int(label->alignment() & Qt::AlignHorizontal_Mask);
    };
    QCOMPARE(edge(file), int(Qt::AlignLeft | Qt::AlignAbsolute));
    // Right to left the file name, in Latin letters, stands at the right with the words around it,
    // not at the far edge its script would put it at; the parts of the ID still read from the left.
    page.setLayoutDirection(Qt::RightToLeft);
    QCOMPARE(edge(file), int(Qt::AlignRight | Qt::AlignAbsolute));
    QCOMPARE(edge(key), int(Qt::AlignLeft | Qt::AlignAbsolute));
    page.setLayoutDirection(Qt::LeftToRight);
    QCOMPARE(edge(file), int(Qt::AlignLeft | Qt::AlignAbsolute));
}

int main(int argc, char* argv[])
{
    // The widgets need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QApplication app(argc, argv);
    TestAirYouPage test;
    return QTest::qExec(&test, argc, argv);
}

#include "airyoupage_test.moc"
