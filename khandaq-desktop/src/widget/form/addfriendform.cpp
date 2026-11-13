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

#include "addfriendform.h"
#include "src/core/core.h"
#include "src/core/toxstring.h"
#include "src/nexus.h"
#include "src/persistence/settings.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/contentlayout.h"
#include "src/widget/tool/croppinglabel.h"
#include "src/widget/style.h"
#include "src/widget/translator.h"
#include "src/widget/tool/imessageboxmanager.h"
#include <QErrorMessage>
#include <QFileDialog>
#include <QFont>
#include <QMessageBox>
#include <QRegularExpression>
#include <QScrollArea>
#include <QSet>
#include <QSizePolicy>
#include <QTabBar>
#include <QTabWidget>
#include <QWindow>
#include <tox/tox.h>

namespace
{
    void configureFormLayout(QVBoxLayout& lay)
    {
        lay.setContentsMargins(12, 12, 12, 12);
        lay.setSpacing(8);
        lay.setAlignment(Qt::AlignTop);
    }

    void configureFormField(QWidget& widget)
    {
        widget.setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    }
    QString getToxId(const QString& id)
    {
        const QString toxUriPrefix{"tox:"};
        QString strippedId = id.trimmed();
        if (strippedId.startsWith(toxUriPrefix)) {
            strippedId.remove(0, toxUriPrefix.length());
        }
        return strippedId;
    }

    bool checkIsValidId(const QString& id)
    {
        return ToxId::isToxId(id);
    }
}

AddFriendForm::AddFriendForm(ToxId ownId_, Settings& settings_, Style& style_,
    IMessageBoxManager& messageBoxManager_, Core& core_)
    : ownId{ownId_}
    , settings{settings_}
    , style{style_}
    , messageBoxManager{messageBoxManager_}
    , core{core_}
{
    tabWidget = new QTabWidget();
    main = new QWidget(tabWidget);
    head = new QWidget();
    QFont bold;
    bold.setBold(true);
    headLabel.setFont(bold);
    toxIdLabel.setTextFormat(Qt::RichText);

    main->setLayout(&layout);
    configureFormLayout(layout);
    layout.addWidget(&toxIdLabel);
    layout.addWidget(&toxId);
    layout.addWidget(&messageLabel);
    layout.addWidget(&message);
    layout.addWidget(&messageLengthLabel);
    layout.addWidget(&sendButton);
    layout.addStretch();
    message.setMaximumHeight(120);
    configureFormField(toxId);
    configureFormField(message);
    configureFormField(sendButton);
    main->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Maximum);
    tabWidget->addTab(main, QString());

    importContacts = new QWidget(tabWidget);
    importContacts->setLayout(&importContactsLayout);
    configureFormLayout(importContactsLayout);
    importFileLine.addWidget(&importFileLabel);
    importFileLine.addStretch();
    importFileLine.addWidget(&importFileButton);
    importContactsLayout.addLayout(&importFileLine);
    importContactsLayout.addWidget(&importMessageLabel);
    importContactsLayout.addWidget(&importMessage);
    importContactsLayout.addWidget(&importMessageLengthLabel);
    importContactsLayout.addWidget(&importSendButton);
    importContactsLayout.addStretch();
    importMessage.setMaximumHeight(120);
    configureFormField(importMessage);
    configureFormField(importSendButton);
    importContacts->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Maximum);
    tabWidget->addTab(importContacts, QString());
    // One ID is added in the New contact panel over the list, which checks it as it is typed; the
    // form keeps the import of a file of IDs.
#if QT_VERSION >= QT_VERSION_CHECK(5, 15, 0)
    tabWidget->setTabVisible(AddFriend, false);
#else
    tabWidget->setTabEnabled(AddFriend, false);
#endif
    tabWidget->setCurrentIndex(ImportContacts);
    // One page left: no tabs to choose from.
    tabWidget->tabBar()->hide();

    head->setLayout(&headLayout);
    headLayout.addWidget(&headLabel);

    connect(&toxId, &QLineEdit::returnPressed, this, &AddFriendForm::onSendTriggered);
    connect(&toxId, &QLineEdit::textChanged, this, &AddFriendForm::onIdChanged);
    connect(&message, &QTextEdit::textChanged, this, &AddFriendForm::onMessageChanged);
    connect(&importMessage, &QTextEdit::textChanged, this, &AddFriendForm::onImportMessageChanged);
    connect(tabWidget, &QTabWidget::currentChanged, this, &AddFriendForm::onCurrentChanged);
    connect(&sendButton, &QPushButton::clicked, this, &AddFriendForm::onSendTriggered);
    connect(&importSendButton, &QPushButton::clicked, this, &AddFriendForm::onImportSendClicked);
    connect(&importFileButton, &QPushButton::clicked, this, &AddFriendForm::onImportOpenClicked);

    // The fields get their accessible names with the other words, in retranslateUi.
    message.setTabChangesFocus(true);
    importMessage.setTabChangesFocus(true);
    // The count under the message turns red past the limit; red follows the theme.
    connect(&AirStyle::instance(), &AirStyle::changed, this, [this] {
        onMessageChanged();
        onImportMessageChanged();
    });

    retranslateUi();
    Translator::registerHandler(std::bind(&AddFriendForm::retranslateUi, this), this);

    while (settings.getFriendRequestSize() > 0) {
        settings.removeFriendRequest(0);
    }
    settings.clearUnreadFriendRequests();
    settings.savePersonal();
}

AddFriendForm::~AddFriendForm()
{
    Translator::unregister(this);
    head->deleteLater();
    tabWidget->deleteLater();
}

bool AddFriendForm::isShown() const
{
    if (head->isVisible()) {
        head->window()->windowHandle()->alert(0);
        return true;
    }

    return false;
}

void AddFriendForm::show(ContentLayout* contentLayout)
{
    contentLayout->mainContent->layout()->addWidget(tabWidget);
    contentLayout->mainHead->layout()->addWidget(head);
    tabWidget->show();
    head->show();
    importFileButton.setFocus();

    // Fix #3421
    // Needed to update tab after opening window
    const int index = tabWidget->currentIndex();
    onCurrentChanged(index);
}

QString AddFriendForm::getMessage() const
{
    const QString msg = message.toPlainText();
    return !msg.isEmpty() ? msg : message.placeholderText();
}

QString AddFriendForm::getImportMessage() const
{
    const QString msg = importMessage.toPlainText();
    return msg.isEmpty() ? importMessage.placeholderText() : msg;
}

void AddFriendForm::setMode(Mode mode)
{
    if (mode != ImportContacts) {
        // A single ID goes through the New contact panel; this form only imports.
        mode = ImportContacts;
    }
    tabWidget->setCurrentIndex(mode);
}

bool AddFriendForm::addFriendRequest(const QString& friendAddress, const QString& message_)
{
    Q_UNUSED(friendAddress);
    Q_UNUSED(message_);
    return false;
}

void AddFriendForm::addFriend(const QString& idText, const QString& requestMessage)
{
    ToxId friendId(idText);
    using AirStrings::Text;

    if (!friendId.isValid()) {
        messageBoxManager.showWarning(tr("Couldn't send the request"),
                                      AirStrings::text(Text::NcVerdictBad));
        return;
    }

    deleteFriendRequest(friendId);
    if (friendId == ownId) {
        messageBoxManager.showWarning(tr("Couldn't send the request"),
                                      AirStrings::text(Text::NcVerdictOwn));
    } else {
        emit friendRequested(friendId, requestMessage);
    }
}

void AddFriendForm::onSendTriggered()
{
    const QString id = getToxId(toxId.text());
    addFriend(id, getMessage());

    toxId.clear();
    message.clear();
}

QList<QString> AddFriendForm::prepareImportContacts(const QList<QString>& rawIds,
    ImportSkipStats& stats) const
{
    QList<QString> result;
    QSet<ToxPk> seen;

    for (const QString& id : rawIds) {
        const ToxId friendId(id);
        if (!friendId.isValid() || friendId == ownId) {
            continue;
        }

        const ToxPk publicKey = friendId.getPublicKey();
        if (seen.contains(publicKey)) {
            ++stats.duplicatesInFile;
            continue;
        }
        seen.insert(publicKey);

        if (core.hasFriendWithPublicKey(publicKey)) {
            ++stats.alreadyFriends;
            continue;
        }

        result.append(id);
    }

    return result;
}

void AddFriendForm::showImportSkippedSummary(const ImportSkipStats& stats, int sentCount) const
{
    if (stats.duplicatesInFile == 0 && stats.alreadyFriends == 0) {
        return;
    }

    QStringList lines;
    if (sentCount > 0) {
        lines.append(tr("%n request(s) sent.", "", sentCount));
    }
    if (stats.alreadyFriends > 0) {
        lines.append(tr("%n ID(s) skipped: already in your contacts.", "", stats.alreadyFriends));
    }
    if (stats.duplicatesInFile > 0) {
        lines.append(tr("%n ID(s) skipped: repeated in the file.", "", stats.duplicatesInFile));
    }

    messageBoxManager.showWarning(tr("Import contacts"), lines.join(QLatin1Char('\n')));
}

void AddFriendForm::onImportSendClicked()
{
    const QString requestMessage = getImportMessage();

    QString resolvedImportMessage = requestMessage.trimmed();
    if (resolvedImportMessage.isEmpty()) {
        // Spaces alone carry the greeting, as an empty field does.
        resolvedImportMessage = AirStrings::text(AirStrings::Text::NcGreeting);
    }

    if (ToxString(resolvedImportMessage).size() > tox_max_friend_request_length()) {
        messageBoxManager.showWarning(tr("Couldn't send the requests"),
                                      AirStrings::text(AirStrings::Text::NcMessageTooLong));
        return;
    }

    ImportSkipStats stats;
    const QList<QString> toSend = prepareImportContacts(contactsToImport, stats);

    for (const QString& id : toSend) {
        const ToxId friendId(id);
        deleteFriendRequest(friendId);
        emit friendRequested(friendId, resolvedImportMessage);
    }

    showImportSkippedSummary(stats, toSend.size());

    contactsToImport.clear();
    importMessage.clear();
    retranslateUi(); // Update the importFileLabel
    onImportMessageChanged();
}

void AddFriendForm::onImportOpenClicked()
{
    const QString path = QFileDialog::getOpenFileName(Q_NULLPTR, tr("Choose a file with IDs"));
    if (path.isEmpty()) {
        return;
    }

    QFile contactFile(path);
    if (!contactFile.open(QIODevice::ReadOnly | QIODevice::Text)) {
        messageBoxManager
            .showWarning(tr("Couldn't import contacts"),
                         tr("The file couldn't be opened. Check that it can be read."));
        return;
    }

    contactsToImport = QString::fromUtf8(contactFile.readAll()).split('\n');
    qDebug() << "Import list:";
    for (auto it = contactsToImport.begin(); it != contactsToImport.end();) {
        const QString id = getToxId(*it);
        if (checkIsValidId(id)) {
            *it = id;
            qDebug() << *it;
            ++it;
        } else {
            if (!id.isEmpty()) {
                qDebug() << "Invalid ID:" << *it;
            }
            it = contactsToImport.erase(it);
        }
    }

    if (contactsToImport.isEmpty()) {
        messageBoxManager.showWarning(
            tr("Couldn't import contacts"),
            tr("There are no IDs in this file. Each line holds one ID of 76 characters."));
        return;
    }

    ImportSkipStats stats;
    contactsToImport = prepareImportContacts(contactsToImport, stats);

    if (contactsToImport.isEmpty()) {
        showImportSkippedSummary(stats, 0);
        return;
    }

    retranslateUi(); // Update the importFileLabel to show how many contacts we have
    onImportMessageChanged();
}

void AddFriendForm::onIdChanged(const QString& id)
{
    const QString strippedId = getToxId(id);

    const bool isValidId = checkIsValidId(strippedId);
    const bool isValidOrEmpty = strippedId.isEmpty() || isValidId;

    toxIdLabel.setText(AirStrings::text(AirStrings::Text::NcIdLabel));
    toxId.setStyleSheet(isValidOrEmpty ? QStringLiteral("")
                                  : style.getStylesheet("addFriendForm/toxId.css", settings));
    toxId.setToolTip(isValidOrEmpty ? QString() : AirStrings::text(AirStrings::Text::NcVerdictBad));

    updateSendButtonState();
}

void AddFriendForm::onMessageChanged()
{
    updateMessageLengthLabel(messageLengthLabel, message.toPlainText());
    updateSendButtonState();
}

void AddFriendForm::onImportMessageChanged()
{
    updateMessageLengthLabel(importMessageLengthLabel, importMessage.toPlainText());
    updateSendButtonState();
}

void AddFriendForm::updateSendButtonState()
{
    const QString strippedId = getToxId(toxId.text());
    const bool validId = checkIsValidId(strippedId);
    const bool validMessage =
        ToxString(getMessage()).size() <= tox_max_friend_request_length();
    sendButton.setEnabled(validId && validMessage);

    const bool validImportMessage =
        ToxString(getImportMessage()).size() <= tox_max_friend_request_length();
    importSendButton.setEnabled(!contactsToImport.isEmpty() && validImportMessage);
}

void AddFriendForm::updateMessageLengthLabel(QLabel& label, const QString& text)
{
    // In the words New contact uses: what is left of the request, or that the message is too long.
    const auto maxBytes = static_cast<int>(tox_max_friend_request_length());
    const auto bytes = static_cast<int>(ToxString(text).size());
    const bool tooLong = bytes > maxBytes;
    label.setText(tooLong ? AirStrings::text(AirStrings::Text::NcMessageTooLong)
                          : AirStrings::text(AirStrings::Text::BytesLeft, maxBytes - bytes));
    AirPaint::textColor(&label, AirStyle::instance().color(tooLong ? AirTokens::Color::Danger
                                                                   : AirTokens::Color::Ink3));
}


void AddFriendForm::deleteFriendRequest(const ToxId& toxId_)
{
    const int size = settings.getFriendRequestSize();
    for (int i = 0; i < size; ++i) {
        Settings::Request request = settings.getFriendRequest(i);
        if (toxId_.getPublicKey() == ToxPk(request.address)) {
            settings.removeFriendRequest(i);
            return;
        }
    }
}

void AddFriendForm::onFriendRequestAccepted()
{
    QPushButton* acceptButton = static_cast<QPushButton*>(sender());
    QWidget* friendWidget = acceptButton->parentWidget();
    const int index = requestsLayout->indexOf(friendWidget);
    removeFriendRequestWidget(friendWidget);
    const int indexFromEnd = requestsLayout->count() - index - 1;
    const Settings::Request request = settings.getFriendRequest(indexFromEnd);
    emit friendRequestAccepted(ToxPk{request.address});
    settings.removeFriendRequest(indexFromEnd);
    settings.savePersonal();
}

void AddFriendForm::onFriendRequestRejected()
{
    QPushButton* rejectButton = static_cast<QPushButton*>(sender());
    QWidget* friendWidget = rejectButton->parentWidget();
    const int index = requestsLayout->indexOf(friendWidget);
    removeFriendRequestWidget(friendWidget);
    const int indexFromEnd = requestsLayout->count() - index - 1;
    settings.removeFriendRequest(indexFromEnd);
    settings.savePersonal();
}

void AddFriendForm::onCurrentChanged(int index)
{
    if (index == FriendRequest && settings.getUnreadFriendRequests() != 0) {
        settings.clearUnreadFriendRequests();
        settings.savePersonal();
        emit friendRequestsSeen();
    }
}

void AddFriendForm::retranslateUi()
{
    using AirStrings::Text;
    headLabel.setText(AirStrings::text(Text::NcImport));
    // Not kept in a static: the words follow a change of language.
    const QString messageLabelText = AirStrings::text(Text::NcMessageLabel);
    messageLabel.setText(messageLabelText);
    importMessageLabel.setText(messageLabelText);
    //: Button that opens the system dialog to choose a text file of IDs, one per line
    importFileButton.setText(tr("Choose a file"));
    importSendButton.setText(tr("Send requests"));
    sendButton.setText(AirStrings::text(Text::NcSend));
    // KHANDAQ (redesign E17): a request left blank carries the greeting of New contact, as on the
    // phones, instead of qTox's joke.
    const QString greeting = AirStrings::text(Text::NcGreeting);
    message.setPlaceholderText(greeting);
    importMessage.setPlaceholderText(greeting);
    toxId.setAccessibleName(AirStrings::text(Text::NcIdLabel));
    toxId.setAccessibleDescription(AirStrings::text(Text::NcIdPlaceholder));
    for (QTextEdit* const field : {&message, &importMessage}) {
        field->setAccessibleName(messageLabelText);
        field->setAccessibleDescription(AirStrings::text(Text::NcMessageNote));
    }

    importFileLabel.setText(
        contactsToImport.isEmpty()
            ? tr("Choose a text file with one ID on each line.")
            //: How many IDs of the chosen file get a request (at least one)
            : tr("Requests are ready for %n ID(s) from the file.", "", contactsToImport.size()));

    onIdChanged(toxId.text());
    onMessageChanged();
    onImportMessageChanged();

    tabWidget->setTabText(AddFriend, AirStrings::text(Text::NewContact));
    tabWidget->setTabText(ImportContacts, tr("Import contacts"));

    for (QPushButton* acceptButton : acceptButtons) {
        retranslateAcceptButton(acceptButton);
    }

    for (QPushButton* rejectButton : rejectButtons) {
        retranslateRejectButton(rejectButton);
    }
}

void AddFriendForm::addFriendRequestWidget(const QString& friendAddress_, const QString& message_)
{
    QWidget* friendWidget = new QWidget(tabWidget);
    QHBoxLayout* friendLayout = new QHBoxLayout(friendWidget);
    QVBoxLayout* horLayout = new QVBoxLayout();
    horLayout->setMargin(0);
    friendLayout->addLayout(horLayout);

    CroppingLabel* friendLabel = new CroppingLabel(friendWidget);
    friendLabel->setTextInteractionFlags(Qt::TextBrowserInteraction);
    friendLabel->setText("<b>" + friendAddress_ + "</b>");
    horLayout->addWidget(friendLabel);

    QLabel* messageLabel_ = new QLabel(message_);
    // allow to select text, but treat links as plaintext to prevent phishing
    messageLabel_->setTextInteractionFlags(Qt::TextSelectableByMouse | Qt::TextSelectableByKeyboard);
    messageLabel_->setTextFormat(Qt::PlainText);
    messageLabel_->setWordWrap(true);
    horLayout->addWidget(messageLabel_, 1);

    QPushButton* acceptButton = new QPushButton(friendWidget);
    acceptButtons.append(acceptButton);
    connect(acceptButton, &QPushButton::released, this, &AddFriendForm::onFriendRequestAccepted);
    friendLayout->addWidget(acceptButton);
    retranslateAcceptButton(acceptButton);

    QPushButton* rejectButton = new QPushButton(friendWidget);
    rejectButtons.append(rejectButton);
    connect(rejectButton, &QPushButton::released, this, &AddFriendForm::onFriendRequestRejected);
    friendLayout->addWidget(rejectButton);
    retranslateRejectButton(rejectButton);

    requestsLayout->insertWidget(0, friendWidget);
}

void AddFriendForm::removeFriendRequestWidget(QWidget* friendWidget)
{
    int index = requestsLayout->indexOf(friendWidget);
    requestsLayout->removeWidget(friendWidget);
    acceptButtons.removeAt(index);
    rejectButtons.removeAt(index);
    friendWidget->deleteLater();
}

void AddFriendForm::retranslateAcceptButton(QPushButton* acceptButton)
{
    acceptButton->setText(tr("Accept"));
}

void AddFriendForm::retranslateRejectButton(QPushButton* rejectButton)
{
    rejectButton->setText(tr("Reject"));
}
