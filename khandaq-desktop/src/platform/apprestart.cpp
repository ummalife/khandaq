/*
    Copyright © 2026 by The Khandaq Project Contributors

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

#include "apprestart.h"

#include <QCoreApplication>
#include <QDebug>
#include <QProcess>

namespace AppRestart {
namespace {
bool asked = false;
QString program;
QStringList kept;
QStringList programArguments;
} // namespace

void keep(const QStringList& arguments)
{
    kept = arguments;
}

void request(const QStringList& arguments)
{
    // The path is taken now: QCoreApplication::applicationFilePath needs the application object,
    // which is gone by the time the program starts again.
    program = QCoreApplication::applicationFilePath();
    programArguments = kept + arguments;
    asked = !program.isEmpty();
    if (!asked) {
        qWarning() << "Restart: the path of the program is unknown, Khandaq only quits";
    }
    QCoreApplication::quit();
}

bool requested()
{
    return asked;
}

bool startIfRequested()
{
    if (!asked) {
        return false;
    }
    asked = false;
    qint64 pid = 0;
    const bool started = QProcess::startDetached(program, programArguments, QString(), &pid);
    if (started) {
        qDebug() << "Restart: started again as process" << pid;
    } else {
        qWarning() << "Restart: could not start" << program;
    }
    return started;
}

} // namespace AppRestart
