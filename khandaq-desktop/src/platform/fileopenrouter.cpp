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

#include "fileopenrouter.h"

#include <QDebug>
#include <QFileOpenEvent>
#include <QUrl>

#include <utility>

FileOpenRouter::FileOpenRouter(QObject* parent)
    : QObject(parent)
{
}

bool FileOpenRouter::requestOf(const QFileOpenEvent& event, Request* request)
{
    const QUrl url = event.url();
    if (url.scheme().compare(QLatin1String("tox"), Qt::CaseInsensitive) == 0) {
        request->profileFile = false;
        request->target = url.toString();
        return true;
    }
    // A file comes with its path, and with a file: URL as well.
    const QString file = event.file();
    if (!file.isEmpty() && file.endsWith(QLatin1String(".tox"), Qt::CaseInsensitive)) {
        request->profileFile = true;
        request->target = file;
        return true;
    }
    return false;
}

void FileOpenRouter::setTaker(Taker taker_)
{
    taker = std::move(taker_);
}

void FileOpenRouter::deliver()
{
    if (!taker) {
        return;
    }
    // Taken from the list first: a taker may run a loop of its own (a question), in which another
    // request comes and is delivered.
    QVector<Request> pending;
    pending.swap(waiting);
    for (int i = 0; i < pending.size(); ++i) {
        if (!taker(pending[i])) {
            waiting.append(pending[i]);
        }
    }
}

int FileOpenRouter::waitingCount() const
{
    return waiting.size();
}

bool FileOpenRouter::eventFilter(QObject* watched, QEvent* event)
{
    if (event->type() != QEvent::FileOpen) {
        return QObject::eventFilter(watched, event);
    }
    Request request;
    if (!requestOf(*static_cast<QFileOpenEvent*>(event), &request)) {
        qDebug() << "The system asked to open something that is neither a tox: link nor a profile";
        return QObject::eventFilter(watched, event);
    }
    qDebug() << "The system asked to open"
             << (request.profileFile ? "a profile file" : "a tox: link");
    waiting.append(request);
    deliver();
    return true;
}
