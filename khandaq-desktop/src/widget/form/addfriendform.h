/*
    Copyright © 2014-2019 by The qTox Project Contributors

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

#include "src/core/toxid.h"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QSet>
#include <QTextEdit>
#include <QVBoxLayout>

class QTabWidget;

class ContentLayout;
class Settings;
class Style;
class IMessageBoxManager;
class Core;

class AddFriendForm : public QObject
{
    Q_OBJECT
public:
    enum Mode
    {
        AddFriend = 0,
        ImportContacts = 1,
        FriendRequest = 2
    };

    AddFriendForm(ToxId ownId_, Settings& settings, Style& style,
        IMessageBoxManager& messageBoxManager, Core& core);
    AddFriendForm(const AddFriendForm&) = delete;
    AddFriendForm& operator=(const AddFriendForm&) = delete;
    ~AddFriendForm();

    bool isShown() const;
    void show(ContentLayout* contentLayout);
    void setMode(Mode mode);

    bool addFriendRequest(const QString& friendAddress, const QString& message_);

signals:
    void friendRequested(const ToxId& friendAddress, const QString& message);
    void friendRequestAccepted(const ToxPk& friendAddress);
    void friendRequestsSeen();

public slots:
    void onUsernameSet(const QString& userName);

private slots:
    void onSendTriggered();
    void onIdChanged(const QString& id);
    void onMessageChanged();
    void onImportMessageChanged();
    void onImportSendClicked();
    void onImportOpenClicked();
    void onFriendRequestAccepted();
    void onFriendRequestRejected();
    void onCurrentChanged(int index);

private:
    void addFriend(const QString& idText, const QString& requestMessage);
    void retranslateUi();
    void addFriendRequestWidget(const QString& friendAddress_, const QString& message_);
    void removeFriendRequestWidget(QWidget* friendWidget);
    void retranslateAcceptButton(QPushButton* acceptButton);
    void retranslateRejectButton(QPushButton* rejectButton);
    void deleteFriendRequest(const ToxId& toxId_);
    void setIdFromClipboard();
    QString getMessage() const;
    QString getImportMessage() const;
    void updateSendButtonState();
    void updateMessageLengthLabel(QLabel& label, const QString& text);
    struct ImportSkipStats
    {
        int duplicatesInFile = 0;
        int alreadyFriends = 0;
    };
    QList<QString> prepareImportContacts(const QList<QString>& rawIds, ImportSkipStats& stats) const;
    void showImportSkippedSummary(const ImportSkipStats& stats, int sentCount) const;

private:
    QLabel headLabel;
    QLabel toxIdLabel;
    QLabel messageLabel;
    QLabel messageLengthLabel;
    QLabel importFileLabel;
    QLabel importMessageLabel;
    QLabel importMessageLengthLabel;

    QPushButton sendButton;
    QPushButton importFileButton;
    QPushButton importSendButton;
    QLineEdit toxId;
    QTextEdit message;
    QTextEdit importMessage;
    QVBoxLayout layout;
    QVBoxLayout headLayout;
    QVBoxLayout importContactsLayout;
    QHBoxLayout importFileLine;
    QWidget* head;
    QWidget* main;
    QWidget* importContacts;
    QString lastUsername;
    QTabWidget* tabWidget;
    QVBoxLayout* requestsLayout;
    QList<QPushButton*> acceptButtons;
    QList<QPushButton*> rejectButtons;
    QList<QString> contactsToImport;

    ToxId ownId;
    Settings& settings;
    Style& style;
    IMessageBoxManager& messageBoxManager;
    Core& core;
};
