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

#include "src/model/air/airnetwork.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airtokens.h"

#include <QPointer>
#include <QWidget>

#include <functional>
#include <utility>
#include <vector>

class AirLocal;
class AirSource;
class AirSwitch;
class ContentLayout;
class IProfileInfo;
class Profile;
class QAbstractButton;
class QLabel;
class QScrollArea;
class QStackedWidget;
class QVBoxLayout;
class QVariantAnimation;
class Settings;
class UpdateCheck;

/**
 * Settings (E18), in the content column with the list of chats beside it: the sections on the start
 * side, what the chosen one holds beside them (design/redesign/02-plan.md, E18, desktop). The
 * sections go by task, as on the phones (DESIGN.md, "Settings on the phone"): Appearance,
 * Notifications, Privacy, Calls, Profile transfer, Network, then Startup and window, which only a
 * computer has, and About. Rows have their value at the end; a consequence is said under a row only
 * where there is one. A question is asked over the window and an outcome is said in a toast.
 */
class AirSettingsPage : public QWidget
{
    Q_OBJECT
public:
    enum class Section
    {
        Appearance,
        Notifications,
        Privacy,
        Calls,
        Transfer,
        Network,
        Window,
        About,
    };

    /**
     * callsForm is the form of the call devices (the Audio and video tab of the old settings),
     * shown under Calls as it is; the page takes it over. updateCheck is null in builds without
     * the update check.
     */
    AirSettingsPage(Settings& settings, Profile& profile, IProfileInfo& profileInfo, AirLocal& airLocal,
                    QWidget* callsForm, UpdateCheck* updateCheck, QWidget* parent = nullptr);
    ~AirSettingsPage() override;

    /** Puts the page into the content column of contentLayout and shows it. */
    void showIn(ContentLayout* contentLayout);
    /** Whether the page is on screen; its window is brought to attention when it is. */
    bool isShown() const;
    void showSection(Section section);
    Section currentSection() const;
    /**
     * The section a name gives: appearance, notifications, privacy, calls, transfer, network,
     * window or about, as the walk and the object names of the sections say them.
     */
    static bool sectionNamed(const QString& name, Section* section);
    /**
     * Moves the profile file at path in (E20): the app logs out of the profile open now, and the
     * start window asks for the file's password if it has one and shows its name and ID before
     * the profile is copied here. asked: the person agreed to log out already; otherwise the
     * question comes over anchor first. A file that holds no profile is said over anchor, and
     * false comes back.
     */
    static bool moveProfileIn(QWidget* anchor, IProfileInfo& info, const QString& path, bool asked);
    /** Reads every setting on the page again. */
    void refresh();
    /**
     * The node whose state Network shows, and how to ask it to connect again. The section follows
     * the node while it is on screen.
     */
    void setNode(AirSource* source, std::function<void()> reconnect);

signals:
    /** The light or dark tray icon was chosen: the icons are drawn again. */
    void trayIconChanged();

protected:
    void paintEvent(QPaintEvent* event) override;
    void changeEvent(QEvent* event) override;
    void showEvent(QShowEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    using Words = std::function<QString()>;

    struct Page
    {
        Section section{};
        AirStrings::Text title{};
        QAbstractButton* button = nullptr;
        QScrollArea* scroller = nullptr;
        QWidget* content = nullptr;
        QVBoxLayout* column = nullptr;
    };

    Page& addPage(Section section, AirStrings::Text title);
    /** A group of rows, under its title when it has one, as wide as a measure of text at most. */
    QVBoxLayout* group(Page& page, const Words& title, const QString& name);
    QLabel* label(AirTokens::TextRole role, AirTokens::Color color, QWidget* parent,
                  const Words& words);
    /** A row: its words on the start side, what it is set to at the end. */
    QWidget* row(QVBoxLayout* rows, const Words& title, const Words& detail, QWidget* control);
    AirSwitch* switchRow(QVBoxLayout* rows, const Words& title, const Words& detail,
                         const std::function<bool()>& read, const std::function<void(bool)>& write,
                         const std::function<bool()>& enabled = nullptr);
    /** A row whose value opens a menu of choices under it. */
    QWidget* menuRow(QVBoxLayout* rows, const Words& title, const Words& value,
                     const std::function<void(QWidget* anchor)>& open);
    /** Choices side by side under their title, one under another when they do not fit. */
    QWidget* segmentRow(QVBoxLayout* rows, const Words& title, const std::vector<Words>& choices,
                        const std::function<int()>& read, const std::function<void(int)>& write);
    QLabel* note(QVBoxLayout* rows, const Words& words);
    /** A whole number in a field, with the words of pattern ("%1 MB") round it when it has one;
     * special is said in place of the smallest number ("Never" for 0). */
    QWidget* numberRow(QVBoxLayout* rows, const Words& title, const Words& pattern,
                       const QString& name, int minimum, int maximum, const std::function<int()>& read,
                       const std::function<void(int)>& write, const Words& special = nullptr);

    void buildAppearance();
    void buildNotifications();
    void buildPrivacy();
    void buildCalls(QWidget* form);
    void buildTransfer();
    void buildNetwork();
    /** The state of the node in words, first in Network (DESIGN.md, "Network state on the phone"). */
    void buildNodeState(Page& page);
    void buildWindow();
    void buildAbout(UpdateCheck* updateCheck);

    void retranslate();
    void restyle();
    void alignLabels();
    /** Enables the rows whose setting depends on another one, and says why where it does. */
    void refreshStates();
    void arrangeNavigation();
    /** Moves to the section before or after the current one, as Up and Down do in the list. */
    void stepSection(int step);
    /**
     * Brings pane in as the section shown: it rises into place from below when it lies further down
     * the list (down), from above when higher up, and fades in; with less motion it only fades.
     */
    void slideIn(QWidget* pane, bool down);
    /** A toast over the window, while the page is on screen. */
    void say(const QString& text);
    QPoint menuPoint(QWidget* anchor) const;

    void openLanguageMenu(QWidget* anchor);
    void openFontMenu(QWidget* anchor);
    void openSmileyMenu(QWidget* anchor);
    void chooseChatFont();
    void chooseDownloadFolder();
    void askHistoryErase();
    void askImport();
    void importProfile(const QString& path);
    /** Shows the state of the node now; a new state cross-fades while the section is on screen. */
    void refreshNode(bool crossFade);
    /** Lays the words shown now over the group and fades them out while the new ones come in. */
    void crossFadeNode();
    /** The log for support: the state of the node in counts, then the log file of the app. */
    QString supportLog() const;
    void saveLog();
    void copyLog();
    void askRestoreNetwork();

    Settings& settings;
    Profile& profile;
    IProfileInfo& profileInfo;
    AirLocal& airLocal;
    /** The detail lines that change with the state of the settings, with their words. */
    std::vector<std::pair<QLabel*, Words>> details;
    QWidget* navigation = nullptr;
    QLabel* navigationTitle = nullptr;
    QVBoxLayout* navigationColumn = nullptr;
    QStackedWidget* stack = nullptr;
    std::vector<Page> pages;
    /** The form of the call devices, the old one: its fields get the focus ring of the page. */
    QPointer<QWidget> deviceForm;
    /** The slide of the section coming in, while it runs. */
    QPointer<QVariantAnimation> slide;
    /** Puts the words of the page in the language of the interface. */
    std::vector<std::function<void()>> wordings;
    /** Reads what the page shows from the settings. */
    std::vector<std::function<void()>> readers;
    /** Enables what depends on another setting. */
    std::vector<std::function<void()>> enablers;

    QPointer<AirSource> nodeSource;
    std::function<void()> reconnectNode;
    QWidget* nodeGroup = nullptr;
    QWidget* nodeDot = nullptr;
    QLabel* nodeTitle = nullptr;
    QLabel* nodeMeaning = nullptr;
    QLabel* nodeInternet = nullptr;
    QLabel* nodeLink = nullptr;
    QLabel* nodeCount = nullptr;
    QAbstractButton* reconnectButton = nullptr;
    /** What the group says now, to tell a new state from a count that moved. */
    Air::NetworkReport shownNode;
    bool nodeShown = false;
    /** The cross-fade of a new state, while it runs. */
    QPointer<QVariantAnimation> nodeFade;
};
