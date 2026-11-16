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

#include <QByteArray>
#include <QDateTime>
#include <QString>
#include <QUrl>

#include <functional>

namespace Air {

class LocalStore;

/**
 * KHANDAQ (0.2.57): Report, on a message someone wrote in a group, any group (SPEC-community,
 * part B, item 5): a mail to support for the person to send from their own mail app. App Review
 * asks for it on iOS (guideline 1.2, content from strangers), and every platform has it.
 */
struct GroupReport
{
    QString groupName;
    /** The author's key in the group, in hexadecimal; the mail names its first 8 characters. */
    QString authorKey;
    /** When the message was written. */
    QDateTime time;
    QString text;
};

/** Where reports go. */
constexpr char reportAddress[] = "support@khandaq.org";
/** The text of the message goes up to this many characters; none is cut in half. */
constexpr int reportTextLimit = 500;

/**
 * mailto: reportAddress, with a subject that names the group and a body with the group, the
 * author, the time in UTC and the text, each part percent-encoded and its lines ended with CRLF.
 * The words of the mail are support's, in English: the person reads them before sending.
 */
QUrl reportMailUrl(const GroupReport& report);

/**
 * KHANDAQ (0.2.57): Khandaq Community, the official group of the app (SPEC-community, part B).
 * Every profile joins it once by itself, muted, and never again once the person has left it;
 * otherwise it is a group like any other. Its founder is our bot on khandaq.org, whose state keeps
 * the chat id, so the id is compiled in.
 */
namespace Community {

/** The chat id of the group, 64 hexadecimal characters in upper case. */
constexpr char chatIdHex[] = "CFF1995F235303B88C4EF1CE903DCEDC77CEAC82DD1323DAB2CACD2EF840646F";

/** The chat id as toxcore takes it: 32 bytes. */
QByteArray chatId();
/** Whether a chat id (32 bytes) is that of Khandaq Community. */
bool isCommunity(const QByteArray& groupChatId);

/**
 * Whether to join it now: our node is connected, the profile never joined it by itself before
 * (autoJoinDone) nor left it (left), and it is not in it already, by hand or from an earlier join.
 */
bool shouldAutoJoin(bool connected, bool autoJoinDone, bool left, bool alreadyInGroup);

/**
 * Joins it through join when shouldAutoJoin says so, with the marks store keeps. join says whether
 * the group now exists here, toxcore having taken the join. Only then is the group muted and the
 * join marked done in store; a join that failed leaves store as it was, so the next start or
 * connection tries again. True when it joined.
 */
bool autoJoin(LocalStore& store, bool connected, bool alreadyInGroup,
              const std::function<bool()>& join);

/**
 * A group was left: when it is Khandaq Community it is never joined by itself again, however it
 * was joined. True when that changed store.
 */
bool noteGroupLeft(LocalStore& store, const QByteArray& groupChatId);

} // namespace Community
} // namespace Air
