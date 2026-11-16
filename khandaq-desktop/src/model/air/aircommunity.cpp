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

#include "aircommunity.h"

#include "src/model/air/airlocalstore.h"

namespace Air {

namespace {
/** The first limit characters of text: a pair of surrogates is one character. */
QString firstCharacters(const QString& text, int limit)
{
    int units = 0;
    for (int characters = 0; characters < limit && units < text.size(); ++characters) {
        const bool pair = text.at(units).isHighSurrogate() && units + 1 < text.size()
                          && text.at(units + 1).isLowSurrogate();
        units += pair ? 2 : 1;
    }
    return text.left(units);
}

/** Lines of a mail end with CRLF (RFC 6068), whatever ended them before. */
QString mailLines(QString text)
{
    text.replace(QStringLiteral("\r\n"), QStringLiteral("\n"));
    text.replace(QLatin1Char('\r'), QLatin1Char('\n'));
    return text.replace(QLatin1Char('\n'), QStringLiteral("\r\n"));
}

QString encoded(const QString& text)
{
    return QString::fromLatin1(QUrl::toPercentEncoding(text));
}
} // namespace

QUrl reportMailUrl(const GroupReport& report)
{
    const QString group = report.groupName.simplified();
    const QString body =
        mailLines(QStringLiteral("Group: %1\nAuthor: %2\nTime: %3\nMessage:\n%4")
                      .arg(group, report.authorKey.left(8).toUpper(),
                           report.time.toUTC().toString(Qt::ISODate),
                           firstCharacters(report.text, reportTextLimit)));
    QUrl url;
    url.setScheme(QStringLiteral("mailto"));
    url.setPath(QString::fromLatin1(reportAddress));
    url.setQuery(QStringLiteral("subject=") + encoded(QStringLiteral("Report: %1").arg(group))
                 + QStringLiteral("&body=") + encoded(body));
    return url;
}

namespace Community {

QByteArray chatId()
{
    return QByteArray::fromHex(QByteArray(chatIdHex));
}

bool isCommunity(const QByteArray& groupChatId)
{
    return groupChatId == chatId();
}

bool shouldAutoJoin(bool connected, bool autoJoinDone, bool left, bool alreadyInGroup)
{
    return connected && !autoJoinDone && !left && !alreadyInGroup;
}

bool autoJoin(LocalStore& store, bool connected, bool alreadyInGroup,
              const std::function<bool()>& join)
{
    if (!shouldAutoJoin(connected, store.communityAutoJoinDone(), store.communityLeft(),
                        alreadyInGroup)) {
        return false;
    }
    if (!join()) {
        return false;
    }
    // The group mutes as it comes: a person added without asking hears nothing of it until they
    // turn it on. The key of a group's mute is its chat id, as the dossier's switch keeps it.
    store.setMuted(true, QString::fromLatin1(chatIdHex));
    store.setCommunityAutoJoinDone();
    return true;
}

bool noteGroupLeft(LocalStore& store, const QByteArray& groupChatId)
{
    return isCommunity(groupChatId) && store.setCommunityLeft();
}

bool savesFileAtOnce(const QByteArray& groupChatId, bool fromSelf)
{
    return fromSelf || !isCommunity(groupChatId);
}

bool HeldFiles::known(const QByteArray& groupChatId, const QByteArray& fileId) const
{
    return !fileId.isEmpty() && knownIds.value(groupChatId).contains(fileId);
}

QVector<HeldFiles::File> HeldFiles::hold(const File& file)
{
    if (!file.entry.resumeFileId.isEmpty()) {
        knownIds[file.groupChatId].insert(file.entry.resumeFileId);
    }
    const qint64 size = file.data.size();
    if (size > limitBytes) {
        return {file};
    }
    QVector<File> gone;
    while (!files.isEmpty() && total + size > limitBytes) {
        total -= files.first().data.size();
        gone.append(files.takeFirst());
    }
    files.append(file);
    total += size;
    return gone;
}

bool HeldFiles::take(uint32_t number, File& file)
{
    for (int i = 0; i < files.size(); ++i) {
        if (files.at(i).entry.fileNum == number) {
            file = files.takeAt(i);
            total -= file.data.size();
            return true;
        }
    }
    return false;
}

void HeldFiles::forgetGroup(const QByteArray& groupChatId)
{
    knownIds.remove(groupChatId);
    for (int i = files.size() - 1; i >= 0; --i) {
        if (files.at(i).groupChatId == groupChatId) {
            total -= files.at(i).data.size();
            files.removeAt(i);
        }
    }
}

qint64 HeldFiles::bytes() const
{
    return total;
}

int HeldFiles::count() const
{
    return files.size();
}

} // namespace Community
} // namespace Air
