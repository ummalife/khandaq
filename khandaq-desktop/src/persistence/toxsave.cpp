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

#include "toxsave.h"
#include "src/widget/widget.h"
#include "src/ipc.h"
#include <QString>

const QString ToxSave::eventHandlerKey = QStringLiteral("save");

ToxSave::ToxSave(IPC& ipc_, Widget& widget_)
    : ipc{ipc_}
    , widget{widget_}
{}

ToxSave::~ToxSave()
{
    ipc.unregisterEventHandler(eventHandlerKey);
}

bool ToxSave::toxSaveEventHandler(const QByteArray& eventData, void* userData)
{
    auto toxSave = static_cast<ToxSave*>(userData);

    if (!eventData.endsWith(".tox")) {
        return false;
    }

    toxSave->handleToxSave(QString::fromUtf8(eventData));
    return true;
}

/**
 * @brief A profile file opened with the app: it is moved in from the start window, once the person
 * agrees to log out of the profile open now (KHANDAQ redesign E20).
 * @param path Path to .tox file.
 * @return True if the file holds a profile and the question was asked, false otherwise.
 */
bool ToxSave::handleToxSave(const QString& path)
{
    return widget.askImportProfile(path);
}
