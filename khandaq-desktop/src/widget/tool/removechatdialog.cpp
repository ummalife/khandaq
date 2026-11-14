/*
    Copyright © 2019 by The qTox Project Contributors

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

#include "removechatdialog.h"
#include "src/model/chat.h"
#include "src/model/group.h"
#include "src/widget/air/airstrings.h"

#include <QPushButton>


RemoveChatDialog::RemoveChatDialog(QWidget* parent, const Chat& contact)
    : QDialog(parent)
{
    setWindowFlags(windowFlags() & ~Qt::WindowContextHelpButtonHint);
    setAttribute(Qt::WA_QuitOnClose, false);
    ui.setupUi(this);
    QString name = contact.getDisplayedName().toHtmlEscaped();
    auto removeButton = ui.buttonBox->button(QDialogButtonBox::Ok);
    auto cancelButton = ui.buttonBox->button(QDialogButtonBox::Cancel);
    if (qobject_cast<const Group*>(&contact)) {
        // KHANDAQ (QA live 2026-11-14): the list's Leave group asked whether to remove the group
        // "from your contacts list". A group is left: ask in the words of the group dossier.
        using AirStrings::Text;
        setWindowTitle(AirStrings::text(Text::GroupLeave));
        const QString boldName = QString("<b>%1</b>").arg(name);
        ui.label->setText(AirStrings::text(Text::GroupLeaveTitle).arg(boldName)
                          + QStringLiteral("<br><br>")
                          + AirStrings::text(Text::GroupLeaveMessage).toHtmlEscaped());
        // Its description for screen readers speaks of a friend; the box's own text says enough.
        ui.removeHistory->setAccessibleDescription(QString());
        removeButton->setText(AirStrings::text(Text::GroupLeaveVerb));
    } else {
        QString text = tr("Are you sure you want to remove %1 from your contacts list?")
                           .arg(QString("<b>%1</b>").arg(name));

        ui.label->setText(text);
        removeButton->setText(tr("Remove"));
    }
    cancelButton->setDefault(true);
    adjustSize();
    connect(ui.buttonBox, &QDialogButtonBox::accepted, this, &RemoveChatDialog::onAccepted);
    connect(ui.buttonBox, &QDialogButtonBox::rejected, this, &RemoveChatDialog::close);
    setFocus();
}

void RemoveChatDialog::onAccepted()
{
    _accepted = true;
    close();
}
