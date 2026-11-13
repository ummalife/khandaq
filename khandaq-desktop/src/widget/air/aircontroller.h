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

#include "src/model/air/airmodel.h"

#include <QObject>
#include <QPoint>
#include <QString>

class AirBand;
class AirListView;
class AirSource;
class QTimer;
class Settings;

/**
 * Ties the Air screen together: it reads AirSource, builds the snapshot with the current filter and
 * query, and feeds the band and the list. Changes are gathered for 30 ms so a burst (a history
 * load, many friends coming online) costs one update; a slow tick keeps the "3 h ago" words true.
 */
class AirController : public QObject
{
    Q_OBJECT
public:
    AirController(AirSource& source, AirBand& band, AirListView& list, Settings& settings,
                  QObject* parent = nullptr);
    ~AirController() override;

    /** The conversation now open on the right, or empty. */
    void setOpenChat(const QString& entryId);
    QString openChat() const;
    /** The conversation after (or before) the open one, in the order of the list. */
    QString chatNextTo(bool forward) const;

    void setSelfName(const QString& name);
    /** Requests also takes the list to the request cards and lights them up for a moment. */
    void setFilter(Air::Filter filter);
    /**
     * Requests, with the list at the cards and the cards lit for a moment, even when Requests is on
     * already: the band of a request or an invitation opens them so.
     */
    void revealRequests();
    /** One card lit for a moment where it stands, the filter as it is. */
    void lightRequest(const QString& requestId);
    /** Back to every chat: no filter, empty command line. */
    void showAll();
    /** Collects changes and rebuilds once they stop for a moment. */
    void refresh();
    void refreshNow();

signals:
    void chatRequested(const QString& entryId);
    /** New contact, prefilled with address when one was pasted. */
    void newContactRequested(const QString& address);
    void favoriteToggled(const QString& entryId, bool favorite);
    void entryMenuRequested(const QString& entryId, const QPoint& globalPos);
    void backgroundMenuRequested(const QPoint& globalPos);
    void requestAccepted(const QString& requestId);
    void requestDeclined(const QString& requestId);

private:
    void rebuild();
    void updateSelf();
    void onFilterToggled(Air::Filter filter);
    void onQueryChanged(const QString& query);

    AirSource& source;
    AirBand& band;
    AirListView& list;
    Settings& settings;

    Air::Filter filter = Air::Filter::All;
    QString query;
    QString openEntry;
    QString selfName;

    QTimer* coalesce = nullptr;
    QTimer* tick = nullptr;
    QTimer* settleEnd = nullptr;
};
