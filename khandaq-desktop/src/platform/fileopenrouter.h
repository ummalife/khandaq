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

#include <QObject>
#include <QString>
#include <QVector>

#include <functional>

class QFileOpenEvent;

/**
 * KHANDAQ (beta ideas 2026-11-20): what macOS asks the app to open. A tox: link clicked anywhere,
 * or a profile file (.tox) opened in the Finder, comes to the application as QEvent::FileOpen,
 * where Windows and Linux start the app with it as an argument (AppManager::run). Set as an event
 * filter of the application, the router takes these events and hands each to its taker, which says
 * whether it can take it now; one it cannot waits, in order, for deliver(): one that comes before
 * there is a window to take it, at the launch the link or the file started.
 */
class FileOpenRouter : public QObject
{
    Q_OBJECT
public:
    struct Request
    {
        /** A profile file rather than a tox: link. */
        bool profileFile = false;
        /** The link, or the path of the file. */
        QString target;
    };
    /** Takes a request now; false leaves it waiting. */
    using Taker = std::function<bool(const Request&)>;

    explicit FileOpenRouter(QObject* parent = nullptr);

    /** What an open event asks: a tox: link or a .tox file; false for anything else. */
    static bool requestOf(const QFileOpenEvent& event, Request* request);

    void setTaker(Taker taker_);
    /** Hands what waits to the taker, in order; what it cannot take yet waits on. */
    void deliver();
    int waitingCount() const;

protected:
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    Taker taker;
    QVector<Request> waiting;
};
