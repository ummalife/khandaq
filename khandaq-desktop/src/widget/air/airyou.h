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

#pragma once

#include "src/model/air/airyou.h"

#include <QAbstractButton>
#include <QPixmap>
#include <QWidget>

class AirButton;
class AirTextField;
class ContentLayout;
class IProfileInfo;
class QBoxLayout;
class QLabel;
class QScrollArea;
class QVBoxLayout;

/** The QR code of your ID: dark modules on a white card in both themes, what cameras read best. */
class AirQrCard : public QWidget
{
    Q_OBJECT
public:
    explicit AirQrCard(QWidget* parent = nullptr);

    void setCode(const Air::QrModules& modules);
    const Air::QrModules& code() const;

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;

private:
    Air::QrModules modules;
};

/** Your photo, or your initials on the ink tile of the "you" block; a click offers to change it. */
class AirYouPhoto : public QAbstractButton
{
    Q_OBJECT
public:
    explicit AirYouPhoto(QWidget* parent = nullptr);

    /** A null photo shows the initials of name. */
    void setPerson(const QString& name, const QPixmap& photo);
    bool hasPhoto() const;

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;
    void enterEvent(QEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    QString name;
    QPixmap photo;
    bool hovered = false;
};

/**
 * You (E17), in the content column: first the QR code of your ID with Copy ID, then your photo,
 * name and status, edited where they stand, the ID in its three named parts with Change antispam,
 * the password, the profile file, Log out with what it means and, apart at the very bottom,
 * deleting the profile (DESIGN.md, "You on the phone"). Every question is asked over the window,
 * every outcome is said in a toast and every failure in a dialog that names the operation.
 */
class AirYouPage : public QWidget
{
    Q_OBJECT
public:
    explicit AirYouPage(IProfileInfo& profileInfo, QWidget* parent = nullptr);
    ~AirYouPage() override;

    /** Puts the page into the content column of contentLayout and shows it. */
    void showIn(ContentLayout* contentLayout);
    /** Whether the page is on screen; its window is brought to attention when it is. */
    bool isShown() const;
    /** Reads your ID, name, status, password and profile file again. */
    void refresh();
    /** Your photo as the "you" block has it: null while you have none. */
    void setPhoto(const QPixmap& photo);
    /** The question Log out asks; the tray and the menu bar ask it here too. */
    void askLogout();
    /**
     * Export, wherever it is offered (You, Settings > Profile transfer): asks where to save the
     * profile over the window of anchor, then says there how it went.
     */
    static void askExport(QWidget* anchor, IProfileInfo& info);
    static void finishExport(QWidget* anchor, IProfileInfo& info, const QString& path);

    // What the file and password dialogs give back.
    void applyPhoto(const QString& path);
    void applySaveQr(const QString& path);
    void applyExport(const QString& path);
    void applyRename(const QString& name);
    void applyNewPassword(const QString& password);

protected:
    void paintEvent(QPaintEvent* event) override;
    void changeEvent(QEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    enum class Field
    {
        Name,
        Status,
    };

    QVBoxLayout* section(QVBoxLayout* column, QLabel** title);
    void arrange(int width);
    /** Puts the words of every label at the start edge of the interface, whatever their script. */
    void alignLabels();
    void retranslate();
    void restyle();
    void refreshId();
    void refreshFields();
    void refreshProfile();
    /** Keeps an edit of the field within the bytes Tox carries. */
    void keepWithin(Field which);
    /** Saves the field when editing ends; an emptied name takes the name back. */
    void commit(Field which);
    /** Puts the saved value back into the field. */
    void revert(Field which);
    AirTextField* fieldOf(Field which) const;
    QString savedValue(Field which) const;
    QImage qrPicture() const;
    /** A toast over the window, while the page is on screen. */
    void say(const QString& text);
    void showPhotoMenu();
    void choosePhoto();
    void saveQr();
    void exportProfile();
    void rename();
    void askPassword();
    void askRemovePassword();
    void askAntispam();
    void askDelete();
    QString logoutMessage() const;

    IProfileInfo& profileInfo;
    QScrollArea* scroller = nullptr;
    QWidget* content = nullptr;
    QVBoxLayout* page = nullptr;
    QLabel* heading = nullptr;
    QBoxLayout* columns = nullptr;
    QWidget* qrColumn = nullptr;
    AirQrCard* qrCard = nullptr;
    QLabel* qrHint = nullptr;
    QBoxLayout* qrActions = nullptr;
    AirButton* copyIdButton = nullptr;
    AirButton* copyQrButton = nullptr;
    AirButton* saveQrButton = nullptr;
    AirYouPhoto* photoButton = nullptr;
    QLabel* nameTitle = nullptr;
    AirTextField* nameField = nullptr;
    QLabel* statusTitle = nullptr;
    AirTextField* statusField = nullptr;
    QLabel* detailsTitle = nullptr;
    QLabel* partTitles[3] = {nullptr, nullptr, nullptr};
    QLabel* partValues[3] = {nullptr, nullptr, nullptr};
    AirButton* antispamButton = nullptr;
    QLabel* passwordTitle = nullptr;
    QLabel* passwordText = nullptr;
    QBoxLayout* passwordActions = nullptr;
    AirButton* passwordButton = nullptr;
    AirButton* passwordRemoveButton = nullptr;
    QLabel* fileTitle = nullptr;
    QLabel* fileName = nullptr;
    QLabel* fileNote = nullptr;
    QBoxLayout* fileActions = nullptr;
    AirButton* renameButton = nullptr;
    AirButton* exportButton = nullptr;
    AirButton* folderButton = nullptr;
    AirButton* logoutButton = nullptr;
    QLabel* logoutNote = nullptr;
    AirButton* deleteButton = nullptr;
    QPixmap photoPixmap;
    /** What each field held after the last edit, within its bytes. */
    QString typedName;
    QString typedStatus;
};
