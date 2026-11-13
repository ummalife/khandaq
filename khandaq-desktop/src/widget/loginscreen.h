/*
    Copyright © 2015-2019 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/


#pragma once

#include "src/model/air/airstart.h"
#include "src/persistence/profilefile.h"

#include <QDialog>
#include <QPointer>
#include <QShortcut>
#include <QVector>

#include <functional>

class AirButton;
class AirFieldNote;
class AirIdParts;
class AirIconButton;
class AirPageStack;
class AirQrCard;
class AirRadioRow;
class AirSecretField;
class AirStepProgress;
class AirSwitch;
class AirTextField;
class QLabel;
class QScrollArea;
class QVBoxLayout;
class QVariantAnimation;
class Settings;
class Style;

/**
 * The start window (E19, E20): first launch in three steps, sign-in, and moving a profile in from
 * another device. It is 560 px wide in the middle of the screen; once a profile is open it grows
 * into the frame of the main window, which takes its place (Nexus::showLogin).
 *
 * First launch asks for the name the people you add will see, then whether the profile gets a
 * password, saying before the choice what each means on this computer, then shows the ID ready to
 * share. Sign-in shows the profile's name and its password field, and the other profiles of this
 * device as a list. Why a profile did not open stands under the field. Moving a profile in reads
 * the file first: its password, then its name and ID, then it is copied here and opened.
 */
class LoginScreen : public QDialog
{
    Q_OBJECT

public:
    LoginScreen(Settings& settings, Style& style, const QString& initialProfileName = QString(),
                QWidget* parent = nullptr);
    ~LoginScreen() override;

    /** The profile set to open at start did not: its sign-in says why under the field. */
    void showFailure(const QString& profileName, const Air::OpenFailure& failure);
    /** Starts moving in the profile file at path: from Settings, or a file opened with the app. */
    void moveIn(const QString& path);
    /** Files of a profile deleted from You that stayed on disk, named over the window once it shows. */
    void tellLeftovers(const QStringList& files);
    /**
     * Grows the window into frame, the place of the main window, its words fading first; then
     * runs then. With less motion, or while the window is hidden, then runs at once.
     */
    void growInto(const QRect& frame, const std::function<void()>& then);

    bool event(QEvent* event) final;

signals:
    void windowStateChanged(Qt::WindowStates states);
    void autoLoginChanged(bool state);
    /**
     * fileName names the files of the new profile; selfName is what its contacts see. The password
     * is empty for a profile without one; autoLogin keeps it in the system credential store.
     */
    void createNewProfile(const QString& fileName, const QString& selfName, const QString& pass,
                          bool autoLogin);
    void loadProfile(const QString& name, const QString& pass, bool withoutProxy);
    /**
     * The profile is open and the window can give way to the main window: at once after sign-in,
     * after the last step of first launch. addFirstPerson opens New contact there.
     */
    void readyToOpen(bool addFirstPerson);

public slots:
    void onProfileLoaded();
    /** The profile is made; id is its ID, for the last step of first launch. */
    void onProfileCreated(const QString& id);
    void onProfileLoadFailed(const Air::OpenFailure& failure);
    void onAutoLoginChanged(bool state);

protected:
    void closeEvent(QCloseEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;
    void paintEvent(QPaintEvent* event) override;

private:
    class Page;

    Page* makePage(const QString& objectName);
    void buildNamePage();
    void buildPasswordPage();
    void buildIdPage();
    void buildSignInPage();
    void buildImportPages();

    /** Opens page over the one open now; Back comes back to that one. */
    void go(Page* page);
    /** Opens page with nothing to go back to: the pages before it are left for good. */
    void restart(Page* page);
    void goBack();
    bool canGoBack(const Page* page) const;
    /** What a page that is left forgets: a password typed on it. */
    void leave(Page* page);
    void arrived(QWidget* page);
    /** The main button of page, and why it waits when it does. */
    void refreshMain(Page* page);
    void setBusy(Page* page, bool busy);
    /** Enter in a field, or the main button: the page goes on when it can. */
    void submit(Page* page);

    QStringList profileNames() const;
    /** Every name the files of the profiles folder take, so a new profile clashes with none. */
    QStringList takenNames() const;
    void startFirstLaunch(bool replaceHistory);
    void showSignIn(const QString& chosen, bool replaceHistory);
    void buildProfileRows(const QStringList& names);
    void chooseProfile(const QString& name);
    /** Sign-in for the chosen profile: its name, its field when it has a password, its switch. */
    void refreshSignIn();
    void refreshPasswordChoice();
    void keepNameWithin();
    void create();
    void signIn(bool withoutProxy);
    void showOpenFailure(const Air::OpenFailure& failure, bool shake);
    void askForgot();
    void askDelete(const QString& name);
    void deleteProfile(const QString& name);
    void showProfileMenu(const QString& name, const QPoint& globalPos);
    void share();
    void saveQr();
    void chooseFile();
    void readImport();
    void showPreview(const ProfileFile::Contents& contents);
    void showImportProblem(const ProfileFile::Contents& contents);
    void confirmMoveIn();
    void finishFirstLaunch(bool addFirstPerson);

    void restyle();
    void retranslateUi();
#ifdef KHANDAQ_SCREENSHOT_HOOK
    void playShotHook();
    void runStartScript(const QString& path);
#endif

    Settings& settings;
    QShortcut quitShortcut;
    QWidget* content = nullptr;
    AirPageStack* stack = nullptr;
    QVector<Page*> pages;
    QVector<Page*> history;

    Page* namePage = nullptr;
    AirTextField* nameField = nullptr;
    AirButton* nameMoveButton = nullptr;
    QString typedName;

    Page* passwordPage = nullptr;
    AirRadioRow* setRow = nullptr;
    AirRadioRow* skipRow = nullptr;
    QWidget* secretBox = nullptr;
    AirSecretField* passField = nullptr;
    AirSecretField* repeatField = nullptr;
    AirFieldNote* passNote = nullptr;
    QWidget* createAutoLoginRow = nullptr;
    AirSwitch* createAutoLogin = nullptr;
    QLabel* createAutoLoginLabel = nullptr;
    QLabel* createAutoLoginNote = nullptr;
    AirFieldNote* createNote = nullptr;

    Page* idPage = nullptr;
    AirQrCard* qrCard = nullptr;
    AirIdParts* idParts = nullptr;
    AirButton* shareButton = nullptr;
    QString createdId;
    QString createdName;
    /** The name of the files of the profile made, for the picture of its QR code. */
    QString createdFile;
    bool idRevealed = false;

    Page* signInPage = nullptr;
    AirSecretField* signInField = nullptr;
    AirFieldNote* signInNote = nullptr;
    AirButton* forgotButton = nullptr;
    AirButton* withoutProxyButton = nullptr;
    QWidget* signInAutoLoginRow = nullptr;
    AirSwitch* signInAutoLogin = nullptr;
    QLabel* signInAutoLoginLabel = nullptr;
    QLabel* signInAutoLoginNote = nullptr;
    QWidget* profilesBox = nullptr;
    QLabel* profilesTitle = nullptr;
    QWidget* profilesPlate = nullptr;
    QVBoxLayout* profilesList = nullptr;
    QLabel* profilesNote = nullptr;
    QVector<AirRadioRow*> profileRows;
    AirButton* signInCreateButton = nullptr;
    AirButton* signInMoveButton = nullptr;
    QString chosenProfile;
    bool chosenEncrypted = false;

    Page* importPasswordPage = nullptr;
    AirSecretField* importField = nullptr;
    AirFieldNote* importNote = nullptr;
    Page* previewPage = nullptr;
    QLabel* previewNameTitle = nullptr;
    QLabel* previewName = nullptr;
    AirIdParts* previewId = nullptr;
    AirButton* otherFileButton = nullptr;
    Page* problemPage = nullptr;
    QString importPath;
    QString importPassword;
    ProfileFile::Contents importContents;
    /** The name the profile moved in got here, while it is being opened. */
    QString movingIn;

    /** The page waiting on a profile to open or be made, if any. */
    Page* busyPage = nullptr;
    bool growing = false;
    QPointer<QVariantAnimation> growth;
#ifdef KHANDAQ_SCREENSHOT_HOOK
    /** The old login hook made a profile: it opens without the last step of first launch. */
    bool hookOpensAtOnce = false;
#endif
};
