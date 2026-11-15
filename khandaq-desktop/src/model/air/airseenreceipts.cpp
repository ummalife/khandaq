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

#include "airseenreceipts.h"

#include <QDateTime>
#include <QDebug>
#include <QTimer>

#include <utility>

namespace Air {

bool isReadingNow(const ReadingFacts& facts)
{
    return facts.appActive && facts.windowShown && !facts.windowMinimized && facts.windowActive
           && facts.windowExposed && facts.chatShown;
}

ReadMarkStore::~ReadMarkStore() = default;

SeenReceipts::SeenReceipts(ReadMarkStore& store_, Send send_, IsOnline isOnline_, int gapMs_,
                           QObject* parent)
    : QObject(parent)
    , store(store_)
    , send(std::move(send_))
    , isOnline(std::move(isOnline_))
    , gapMs(gapMs_)
{
    clock.start();
}

void SeenReceipts::seen(const ToxPk& friendPk, const SeenAnchor& anchor)
{
    store.noteRead(friendPk, anchor);
    flush(friendPk);
}

void SeenReceipts::friendOnline(const ToxPk& friendPk)
{
    flush(friendPk);
}

void SeenReceipts::flush(const ToxPk& friendPk)
{
    const QByteArray key = friendPk.getByteArray();
    if (waiting.contains(key) || !store.isReadPending(friendPk) || !isOnline(friendPk)) {
        return;
    }

    const qint64 now = clock.elapsed();
    const auto last = lastSent.constFind(key);
    if (last != lastSent.constEnd() && now - last.value() < gapMs) {
        // The one that waits goes once the gap has passed, as the newest seen by then.
        waiting.insert(key);
        QTimer::singleShot(static_cast<int>(gapMs - (now - last.value())), this, [this, friendPk, key] {
            waiting.remove(key);
            flush(friendPk);
        });
        return;
    }

    const SeenAnchor anchor = store.readAnchor(friendPk);
    SeenReceipt receipt;
    receipt.anchor = anchor.anchor;
    receipt.anchorId = anchor.id;
    receipt.time = static_cast<quint32>(QDateTime::currentSecsSinceEpoch());
    const QByteArray packet = seenPacket(receipt);
    if (packet.isEmpty()) {
        // An id of another size names nothing on the other side: no receipt is owed for it.
        store.noteReadSent(friendPk, anchor);
        return;
    }
    if (!send(friendPk, packet)) {
        qDebug() << "SeenReceipts: the read receipt waits for the person to come online";
        return;
    }
    lastSent.insert(key, now);
    store.noteReadSent(friendPk, anchor);
    qDebug() << "SeenReceipts: a read receipt went for a"
             << (anchor.anchor == MessageEffect::Anchor::File ? "file" : "text");
}

} // namespace Air
