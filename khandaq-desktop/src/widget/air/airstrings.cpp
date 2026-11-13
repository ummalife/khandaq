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

#include "airstrings.h"

#include <QCoreApplication>

#include <algorithm>
#include <cmath>

// The plural marker came with Qt 5.15; Qt 5.12 builds get the same expansion. lupdate goes by the
// name, so refresh the .ts files with lupdate 5.15 or later, or the plural forms drop out.
#ifndef QT_TRANSLATE_N_NOOP3
#define QT_TRANSLATE_N_NOOP3(scope, x, comment) {x, comment}
#endif

namespace AirStrings {

namespace {
struct Message
{
    const char* source;
    const char* comment;
};

struct Source
{
    // A constructor rather than an aggregate with a missing last field, which GCC's
    // -Wmissing-field-initializers reports for every line of the table.
    constexpr Source(Text id_, Message message_, bool numerus_ = false)
        : id{id_}
        , message(message_)
        , numerus{numerus_}
    {
    }

    Text id;
    Message message;
    /** The source has %n and comes in plural forms (QT_TRANSLATE_N_NOOP3). */
    bool numerus;
};

// clang-format off
const Source sources[] = {
    {Text::You, QT_TRANSLATE_NOOP3("Air", "You", "accessible name of your own block at the top")},
    {Text::YouHint, QT_TRANSLATE_NOOP3("Air", "Opens your profile", "tooltip of your own block")},
    {Text::StatusMenu, QT_TRANSLATE_NOOP3("Air", "Status", "button that opens the online, away, busy menu")},
    {Text::NetDirect, QT_TRANSLATE_NOOP3("Air", "online, direct", "own node: connected over UDP")},
    {Text::NetRelay, QT_TRANSLATE_NOOP3("Air", "online, via a relay", "own node: connected through a TCP relay")},
    {Text::NetConnecting, QT_TRANSLATE_NOOP3("Air", "connecting", "own node or a contact: the link is being set up")},
    {Text::NetOffline, QT_TRANSLATE_NOOP3("Air", "no network: messages wait", "own node: the device has no network")},
    {Text::NetHint, QT_TRANSLATE_NOOP3("Air", "Opens the state of the network", "tooltip of the line under your name")},
    {Text::CommandPlaceholder, QT_TRANSLATE_NOOP3("Air", "Find a chat or paste an ID", "placeholder of the command line")},
    {Text::CommandKeyHint, QT_TRANSLATE_NOOP3("Air", "Press / to search", "tooltip of the key hint in the command line")},
    {Text::NewContact, QT_TRANSLATE_NOOP3("Air", "New contact", "button and empty state action")},
    {Text::Groups, QT_TRANSLATE_NOOP3("Air", "Groups", "button that opens creating and joining groups")},
    {Text::FileTransfers, QT_TRANSLATE_NOOP3("Air", "File transfers", "button that opens the list of file transfers")},
    {Text::Favorites, QT_TRANSLATE_NOOP3("Air", "Favorites", "filter button with a bookmark")},
    {Text::Settings, QT_TRANSLATE_NOOP3("Air", "Settings", "button that opens the settings")},
    {Text::FilterRequests, QT_TRANSLATE_NOOP3("Air", "Requests", "filter pill")},
    {Text::FilterTransit, QT_TRANSLATE_NOOP3("Air", "In transit", "filter pill: messages still on their way")},
    {Text::SectionLive, QT_TRANSLATE_NOOP3("Air", "Online now", "list section")},
    {Text::SectionAway, QT_TRANSLATE_NOOP3("Air", "Offline", "list section")},
    {Text::SectionSaved, QT_TRANSLATE_NOOP3("Air", "Just for you", "list section of saved messages")},
    {Text::SectionMessages, QT_TRANSLATE_NOOP3("Air", "Messages", "list section of message search results")},
    {Text::RequestTag, QT_TRANSLATE_NOOP3("Air", "Incoming request", "label of a friend request card")},
    {Text::RequestKey, QT_TRANSLATE_NOOP3("Air", "key %1. You will see the name once you accept the request", "%1 is the start of the public key")},
    {Text::RequestAccept, QT_TRANSLATE_NOOP3("Air", "Accept", "friend request card button")},
    {Text::RequestDecline, QT_TRANSLATE_NOOP3("Air", "Decline", "friend request card button")},
    {Text::ReachLive, QT_TRANSLATE_NOOP3("Air", "online", "contact reach mark")},
    {Text::ReachRelay, QT_TRANSLATE_NOOP3("Air", "via a relay", "contact reach mark: online, connected through a TCP relay; short, it shares the line with the name")},
    {Text::ReachPending, QT_TRANSLATE_NOOP3("Air", "waiting for reply", "contact reach mark: our request is not answered yet")},
    {Text::ReachConnecting, QT_TRANSLATE_NOOP3("Air", "connecting", "own node or a contact: the link is being set up")},
    {Text::ReachGroupOnline, QT_TRANSLATE_NOOP3("Air", "%1 online", "group reach mark: %1 members online")},
    {Text::ReachGroupNone, QT_TRANSLATE_NOOP3("Air", "nobody online", "group reach mark")},
    {Text::ReachJustNow, QT_TRANSLATE_NOOP3("Air", "just now", "contact last seen")},
    {Text::ReachMinutes, QT_TRANSLATE_NOOP3("Air", "%1 min ago", "contact last seen")},
    {Text::ReachHours, QT_TRANSLATE_NOOP3("Air", "%1 h ago", "contact last seen")},
    {Text::ReachDays, QT_TRANSLATE_NOOP3("Air", "%1 d ago", "contact last seen")},
    {Text::ReachLongAgo, QT_TRANSLATE_NOOP3("Air", "a while ago", "contact last seen a week or more ago")},
    {Text::DeliveryTransit, QT_TRANSLATE_NOOP3("Air", "in transit", "state of our last message")},
    {Text::DeliverySent, QT_TRANSLATE_NOOP3("Air", "sent", "state of our last message")},
    {Text::DeliveryDelivered, QT_TRANSLATE_NOOP3("Air", "delivered", "state of our last message")},
    {Text::DeliveryFailed, QT_TRANSLATE_NOOP3("Air", "not sent", "state of our last message: it broke")},
    {Text::TransitFlag, QT_TRANSLATE_NOOP3("Air", "%1 in transit", "row flag: %1 messages on their way")},
    {Text::UnreadCount, QT_TRANSLATE_NOOP3("Air", "unread: %1", "accessible text of the unread badge")},
    {Text::Mentioned, QT_TRANSLATE_NOOP3("Air", "You were mentioned", "accessible text of the @ flag")},
    {Text::MentionsYou, QT_TRANSLATE_NOOP3("Air", "Mentions you", "what a screen reader says of a group message that mentions you")},
    {Text::TranscriptName, QT_TRANSLATE_NOOP3("Air", "Messages", "what a screen reader calls the list of messages of a conversation")},
    {Text::HintRequestSent, QT_TRANSLATE_NOOP3("Air", "request sent", "row hint of a contact who has not answered yet")},
    {Text::HintNoMessages, QT_TRANSLATE_NOOP3("Air", "no messages yet", "row hint of a conversation without messages")},
    {Text::Typing, QT_TRANSLATE_NOOP3("Air", "typing", "row preview while the contact types")},
    {Text::FileMessage, QT_TRANSLATE_NOOP3("Air", "File: %1", "row preview of a file, %1 is the file name")},
    {Text::RowActions, QT_TRANSLATE_NOOP3("Air", "Actions", "button that opens the menu of a row")},
    {Text::AddFavorite, QT_TRANSLATE_NOOP3("Air", "Add to favorites", "row action")},
    {Text::RemoveFavorite, QT_TRANSLATE_NOOP3("Air", "Remove from favorites", "row action")},
    {Text::EmptyNothingTitle, QT_TRANSLATE_NOOP3("Air", "Nobody here yet", "empty list")},
    {Text::EmptyNothingBody, QT_TRANSLATE_NOOP3("Air", "Paste a person’s ID into the line at the top or open “New contact”.", "empty list")},
    {Text::EmptyNoMatchTitle, QT_TRANSLATE_NOOP3("Air", "Nothing found", "search found nothing")},
    {Text::EmptyNoMatchBody, QT_TRANSLATE_NOOP3("Air", "No names or messages match. If it is an ID, check that it is complete: 76 characters.", "search found nothing")},
    {Text::EmptyNoRequestsTitle, QT_TRANSLATE_NOOP3("Air", "No requests", "requests filter is empty")},
    {Text::EmptyNoRequestsBody, QT_TRANSLATE_NOOP3("Air", "Incoming requests appear here, and yours that are still waiting for a reply.", "requests filter is empty")},
    {Text::EmptyNoTransitTitle, QT_TRANSLATE_NOOP3("Air", "Everything delivered", "in transit filter is empty")},
    {Text::EmptyNoTransitBody, QT_TRANSLATE_NOOP3("Air", "Messages waiting for someone to come online gather here.", "in transit filter is empty")},
    {Text::EmptyNoFavoritesTitle, QT_TRANSLATE_NOOP3("Air", "No favorites yet", "favorites filter is empty")},
    {Text::EmptyNoFavoritesBody, QT_TRANSLATE_NOOP3("Air", "Mark a chat in its menu and it will be here.", "favorites filter is empty")},
    {Text::EmptyShowAll, QT_TRANSLATE_NOOP3("Air", "Show all chats", "empty state action")},
    {Text::ThemeLabel, QT_TRANSLATE_NOOP3("Air", "Theme:", "settings label of the theme choice")},
    {Text::ThemeSystem, QT_TRANSLATE_NOOP3("Air", "Match system", "theme choice")},
    {Text::ThemeLight, QT_TRANSLATE_NOOP3("Air", "Light", "theme choice")},
    {Text::ThemeDark, QT_TRANSLATE_NOOP3("Air", "Dark", "theme choice")},
    {Text::AliasPrompt,
     QT_TRANSLATE_NOOP3("Air", "The name you see for this contact", "label of the alias dialog")},
    {Text::GroupNamePrompt, QT_TRANSLATE_NOOP3("Air", "Group name", "label of the group title dialog")},
    {Text::ChatList, QT_TRANSLATE_NOOP3("Air", "Chats", "accessible name of the list of chats")},
    {Text::DeliverySending, QT_TRANSLATE_NOOP3("Air", "sending", "state of our message: the person is online and it is leaving right now")},
    {Text::ChatSearch, QT_TRANSLATE_NOOP3("Air", "Search the conversation", "button in the conversation header")},
    {Text::ChatCall, QT_TRANSLATE_NOOP3("Air", "Call", "accessible name of the audio call button in the conversation header")},
    {Text::ChatVideo, QT_TRANSLATE_NOOP3("Air", "Video call", "accessible name of the video call button in the conversation header")},
    {Text::ChatDossierHint, QT_TRANSLATE_NOOP3("Air", "Opens the dossier: link, last 24 hours, key", "tooltip of the name in the conversation header")},
    {Text::DossierCloseHint, QT_TRANSLATE_NOOP3("Air", "Closes the dossier", "tooltip of the name while the dossier is open")},
    {Text::DayToday, QT_TRANSLATE_NOOP3("Air", "Today", "day mark in the conversation")},
    {Text::DayYesterday, QT_TRANSLATE_NOOP3("Air", "Yesterday", "day mark in the conversation")},
    {Text::PresenceDirect, QT_TRANSLATE_NOOP3("Air", "online, direct link", "line under the name in the conversation header")},
    {Text::PresenceDirectSince, QT_TRANSLATE_NOOP3("Air", "online since %1, direct link", "line under the name; %1 is the time this computer saw the person come online")},
    {Text::PresenceRelay, QT_TRANSLATE_NOOP3("Air", "online, via a relay", "line under the name in the conversation header")},
    {Text::PresenceRelaySince, QT_TRANSLATE_NOOP3("Air", "online since %1, via a relay", "line under the name; %1 is the time this computer saw the person come online")},
    {Text::PresencePending, QT_TRANSLATE_NOOP3("Air", "waiting for a reply to the request", "line under the name: our request is not answered yet")},
    {Text::AwayJustNow, QT_TRANSLATE_NOOP3("Air", "offline for under a minute", "line under the name")},
    {Text::AwayMinutes, QT_TRANSLATE_N_NOOP3("Air", "offline for %n minute(s)", "line under the name"), true},
    {Text::AwayHours, QT_TRANSLATE_N_NOOP3("Air", "offline for %n hour(s)", "line under the name"), true},
    {Text::AwayDays, QT_TRANSLATE_N_NOOP3("Air", "offline for %n day(s)", "line under the name"), true},
    {Text::AwayLong, QT_TRANSLATE_NOOP3("Air", "offline for over a week", "line under the name")},
    {Text::LinkDirect, QT_TRANSLATE_NOOP3("Air", "direct link", "spoken name of the link mark")},
    {Text::LinkRelay, QT_TRANSLATE_NOOP3("Air", "link via a relay", "spoken name of the link mark")},
    {Text::LinkNone, QT_TRANSLATE_NOOP3("Air", "no link", "spoken name of the link mark")},
    {Text::DayTitle, QT_TRANSLATE_NOOP3("Air", "Last 24 hours", "title of the strip of 24 hourly cells under the conversation header")},
    {Text::DayEmpty, QT_TRANSLATE_NOOP3("Air", "no marks yet", "beside the day strip: no hours marked yet")},
    {Text::DayBest, QT_TRANSLATE_NOOP3("Air", "usually %1:00 to %2:00", "beside the day strip; %1 and %2 are hours of the clock")},
    {Text::DayRanges, QT_TRANSLATE_NOOP3("Air", "past day: %1", "beside the day strip; %1 lists runs of hours like 7:00 to 9:00")},
    {Text::DayRange, QT_TRANSLATE_NOOP3("Air", "%1:00 to %2:00", "a run of hours; %1 and %2 are hours of the clock")},
    {Text::DayA11y, QT_TRANSLATE_NOOP3("Air", "online %1", "spoken text of the day strip; %1 lists runs of hours")},
    {Text::DayA11yNone, QT_TRANSLATE_NOOP3("Air", "no marks", "spoken text of an empty day strip")},
    {Text::DayNowOnline, QT_TRANSLATE_NOOP3("Air", "Online now", "spoken after the day strip: the person is online at this moment")},
    {Text::DayNote, QT_TRANSLATE_NOOP3("Air", "This computer marks the hours it sees the person online. The marks stay only here, for seven days.", "dossier, under the day strip")},
    {Text::ComposePlaceholder, QT_TRANSLATE_NOOP3("Air", "Message", "placeholder of the message field")},
    {Text::ComposeSend, QT_TRANSLATE_NOOP3("Air", "Send", "button that sends the message")},
    {Text::ComposeAttach, QT_TRANSLATE_NOOP3("Air", "Attach a file", "paperclip button of the message field")},
    {Text::ComposeHintLive, QT_TRANSLATE_NOOP3("Air", "Goes right away: %1 is online", "line under the message field; %1 is the first name")},
    {Text::ComposeHintWait, QT_TRANSLATE_NOOP3("Air", "Waits in transit: %1 is offline. It will go on its own", "line under the message field; %1 is the first name")},
    {Text::ComposeHintSaved, QT_TRANSLATE_NOOP3("Air", "The note stays only on this device", "line under the field of notes to yourself")},
    {Text::ComposeHintSelfConnecting, QT_TRANSLATE_NOOP3("Air", "Waits in transit: the app is connecting. It will go on its own", "line under the message field while our own node looks for the network")},
    {Text::ComposeHintSelfOffline, QT_TRANSLATE_NOOP3("Air", "Waits in transit: this computer has no network. It will go on its own", "line under the message field while the computer has no network")},
    {Text::BytesLeft, QT_TRANSLATE_N_NOOP3("Air", "%n byte(s) left", "length counter under the message field"), true},
    {Text::BytesOverParts, QT_TRANSLATE_N_NOOP3("Air", "%n byte(s) over one message: it goes in parts", "length counter: the text is longer than one message and is sent as several"), true},
    {Text::TrayHead, QT_TRANSLATE_N_NOOP3("Air", "In transit: %n message(s)", "heading of the tray of messages waiting for the person"), true},
    {Text::TrayWait, QT_TRANSLATE_NOOP3("Air", "They go on their own once %1 comes online.", "under the tray heading; %1 is the first name")},
    {Text::TrayItemHint, QT_TRANSLATE_NOOP3("Air", "You can edit or cancel it until it goes", "tooltip of a message in the tray")},
    {Text::TrayCollapseHint, QT_TRANSLATE_NOOP3("Air", "Collapses the tray", "tooltip of the tray heading while the tray is open")},
    {Text::TrayExpandHint, QT_TRANSLATE_NOOP3("Air", "Shows the messages in transit", "tooltip of the tray heading while the tray is collapsed")},
    {Text::TrayCancel, QT_TRANSLATE_NOOP3("Air", "Cancel sending", "action on a message in the tray or on one not sent")},
    {Text::TrayEdit, QT_TRANSLATE_NOOP3("Air", "Edit", "action on a message in the tray or on one not sent: its text goes back to the field")},
    {Text::FailedRetry, QT_TRANSLATE_NOOP3("Air", "Not sent. Retry", "link beside a message the network gave up on")},
    {Text::FailedRetryHint, QT_TRANSLATE_NOOP3("Air", "Sends the message again", "tooltip of the retry link")},
    {Text::FailedItemHint, QT_TRANSLATE_NOOP3("Air", "Opens actions for the message", "tooltip of a message not sent")},
    {Text::Retry, QT_TRANSLATE_NOOP3("Air", "Retry", "action on a message not sent")},
    {Text::DossierLinkTitle, QT_TRANSLATE_NOOP3("Air", "Link", "dossier section: how this computer reaches the person")},
    {Text::DossierLinkDirect, QT_TRANSLATE_NOOP3("Air", "Direct, with no relay.", "dossier, link section")},
    {Text::DossierLinkRelay, QT_TRANSLATE_NOOP3("Air", "Via a relay. It passes on encrypted messages and cannot see what they say.", "dossier, link section")},
    {Text::DossierLinkNone, QT_TRANSLATE_NOOP3("Air", "No link right now. Messages wait in transit and go on their own.", "dossier, link section")},
    {Text::PathRelay, QT_TRANSLATE_NOOP3("Air", "relay", "label under the middle point of the link picture")},
    {Text::DossierDayTitle, QT_TRANSLATE_NOOP3("Air", "Last 24 hours", "dossier section with the day strip")},
    {Text::DossierBest, QT_TRANSLATE_NOOP3("Air", "Best time to write: %1:00 to %2:00, when they are online most often.", "dossier; %1 and %2 are hours of the clock")},
    {Text::DossierNotificationsTitle, QT_TRANSLATE_NOOP3("Air", "Notifications", "dossier section")},
    {Text::DossierNoWake, QT_TRANSLATE_NOOP3("Air", "This computer does not send the signal with no text: messages reach the person once they open Khandaq.", "dossier, notifications section: the desktop app sends no push signal to the other person's phone")},
    {Text::DossierMute, QT_TRANSLATE_NOOP3("Air", "Mute", "dossier button: no sound or alerts from this conversation")},
    {Text::DossierUnmute, QT_TRANSLATE_NOOP3("Air", "Unmute", "dossier button: sound and alerts from this conversation again")},
    {Text::DossierMutedNote, QT_TRANSLATE_NOOP3("Air", "No sound or alerts from this conversation on this device.", "dossier, notifications section, while muted")},
    {Text::DossierVerifyTitle, QT_TRANSLATE_NOOP3("Air", "Key check", "dossier section and the title of the key check")},
    {Text::DossierNotVerified, QT_TRANSLATE_NOOP3("Air", "The key is not verified.", "dossier, key check section")},
    {Text::DossierVerified, QT_TRANSLATE_NOOP3("Air", "Key verified on %1.", "dossier, key check section; %1 is a date")},
    {Text::DossierVerify, QT_TRANSLATE_NOOP3("Air", "Verify", "dossier button that opens the key check")},
    {Text::DossierUnverify, QT_TRANSLATE_NOOP3("Air", "Undo verification", "dossier button")},
    {Text::VerifyMessage, QT_TRANSLATE_NOOP3("Air", "Ask the other person to read their ID from their profile. If its first 64 characters match the key below, you are talking to the right person.", "key check")},
    {Text::VerifyMatch, QT_TRANSLATE_NOOP3("Air", "It matches", "key check button")},
    {Text::DossierKeyTitle, QT_TRANSLATE_NOOP3("Air", "Key", "dossier section with the public key in groups of four")},
    {Text::DossierCopyKey, QT_TRANSLATE_NOOP3("Air", "Copy key", "dossier button")},
    {Text::DossierKeyCopied, QT_TRANSLATE_NOOP3("Air", "Key copied", "confirmation after copying the key")},
    {Text::DossierQr, QT_TRANSLATE_NOOP3("Air", "QR code", "dossier button")},
    {Text::QrKeyHeading, QT_TRANSLATE_NOOP3("Air", "Key: %1", "title of the QR code window; %1 is the name")},
    {Text::QrKeySubtitle, QT_TRANSLATE_NOOP3("Air", "Compare it with the ID in their profile: the first 64 characters must match.", "QR code window")},
    {Text::DossierFilesTitle, QT_TRANSLATE_NOOP3("Air", "Files", "dossier section: files of this conversation")},
    {Text::DossierBlock, QT_TRANSLATE_NOOP3("Air", "Block", "dossier button")},
    {Text::DossierBlockHint, QT_TRANSLATE_NOOP3("Air", "Asks first, then deletes the contact and hides requests from this key", "tooltip of the Block button in the dossier")},
    {Text::BlockTitle, QT_TRANSLATE_NOOP3("Air", "Block %1?", "title of the block confirmation; %1 is the contact's name")},
    {Text::BlockMessage, QT_TRANSLATE_NOOP3("Air", "The contact and the conversation are deleted from this device, and new requests from this key will not be shown.", "block confirmation")},
    {Text::Cancel, QT_TRANSLATE_NOOP3("Air", "Cancel", "dialog button that closes it and changes nothing")},
    {Text::BlockedTitle, QT_TRANSLATE_NOOP3("Air", "Blocked", "settings, privacy: the keys blocked on this device")},
    {Text::BlockedNone, QT_TRANSLATE_NOOP3("Air", "None", "settings, privacy: no key is blocked")},
    {Text::BlockedSectionNote, QT_TRANSLATE_NOOP3("Air", "Requests from blocked keys are not shown. The list is kept on this device only.", "settings, privacy: under the Blocked row")},
    {Text::BlockedEmpty, QT_TRANSLATE_NOOP3("Air", "Keys you block from a contact's dossier appear here.", "settings, privacy: the block list is empty")},
    {Text::BlockedNote, QT_TRANSLATE_NOOP3("Air", "Unblocking does not bring the contact back: that takes a new request.", "settings, privacy: above the blocked keys, under the note of the Blocked row")},
    {Text::Unblock, QT_TRANSLATE_NOOP3("Air", "Unblock", "button beside a blocked key")},
    {Text::KeyUnblocked, QT_TRANSLATE_NOOP3("Air", "Key unblocked", "toast after Unblock")},
    {Text::DossierRemove, QT_TRANSLATE_NOOP3("Air", "Remove", "dossier button that removes the contact")},
    {Text::DossierRemoveTitle, QT_TRANSLATE_NOOP3("Air", "Remove %1 from contacts?", "title of the remove confirmation; %1 is the contact's name")},
    {Text::DossierRemoveMessage, QT_TRANSLATE_NOOP3("Air", "The conversation is deleted from this device. To write to each other again, you will need a new request.", "remove confirmation")},
    {Text::DossierRename, QT_TRANSLATE_NOOP3("Air", "Rename", "dossier button")},
    {Text::SavedTitle, QT_TRANSLATE_NOOP3("Air", "Saved Messages", "title of the notes to yourself")},
    {Text::HintSaved, QT_TRANSLATE_NOOP3("Air", "only on this device", "row hint of the notes to yourself")},
    {Text::NoteDelete, QT_TRANSLATE_NOOP3("Air", "Delete message", "menu action on a message in Saved Messages")},
    {Text::NoteDeleteTitle, QT_TRANSLATE_NOOP3("Air", "Delete this message?", "title of the confirmation before a saved message is deleted")},
    {Text::NoteDeleteMessage, QT_TRANSLATE_NOOP3("Air", "Saved messages are kept only on this device, so it cannot be brought back.", "confirmation before a saved message is deleted")},
    {Text::Delete, QT_TRANSLATE_NOOP3("Air", "Delete", "dialog button that deletes")},
    {Text::SizeBytes, QT_TRANSLATE_NOOP3("Air", "%1 B", "file size in bytes; %1 is the number")},
    {Text::SizeKilobytes, QT_TRANSLATE_NOOP3("Air", "%1 KB", "file size in kilobytes (1 KB is 1000 bytes); %1 is the number")},
    {Text::SizeMegabytes, QT_TRANSLATE_NOOP3("Air", "%1 MB", "file size in megabytes (1 MB is 1000 KB); %1 is the number")},
    {Text::SizeGigabytes, QT_TRANSLATE_NOOP3("Air", "%1 GB", "file size in gigabytes (1 GB is 1000 MB); %1 is the number")},
    {Text::TransferSpeed, QT_TRANSLATE_NOOP3("Air", "%1/s", "how fast a file travels; %1 is a size such as 1.2 MB")},
    {Text::NcIdLabel, QT_TRANSLATE_NOOP3("Air", "Their ID", "new contact: label of the ID field")},
    {Text::NcIdPlaceholder, QT_TRANSLATE_NOOP3("Air", "Paste the ID: 76 characters, digits and letters A to F", "new contact: the empty ID field")},
    {Text::NcRulerKey, QT_TRANSLATE_NOOP3("Air", "key", "new contact: ruler part, the 64 characters of the public key")},
    {Text::NcRulerAntispam, QT_TRANSLATE_NOOP3("Air", "antispam", "new contact: ruler part, the 8 characters of the antispam (nospam)")},
    {Text::NcRulerCheck, QT_TRANSLATE_NOOP3("Air", "check", "new contact: ruler part, the 4 characters of the checksum")},
    {Text::NcRulerPart, QT_TRANSLATE_NOOP3("Air", "%1: %2 of %3", "new contact: a ruler part for screen readers; %1 is its name, %2 how many are typed, %3 how many it takes")},
    {Text::NcVerdictEmpty, QT_TRANSLATE_NOOP3("Air", "Spaces, line breaks and a tox: prefix can stay: the field cleans them up.", "new contact: verdict while the ID field is empty")},
    {Text::NcVerdictLeft, QT_TRANSLATE_N_NOOP3("Air", "%n character(s) left.", "new contact: verdict while the ID is not whole"), true},
    {Text::NcVerdictBad, QT_TRANSLATE_NOOP3("Air", "The check does not match: the ID has a typo or was not copied whole. Copy it again.", "new contact: verdict, the checksum failed")},
    {Text::NcVerdictOwn, QT_TRANSLATE_NOOP3("Air", "This is your own ID: you send it to others, you do not add it.", "new contact: verdict")},
    {Text::NcVerdictContact, QT_TRANSLATE_NOOP3("Air", "%1 is already in your contacts.", "new contact: verdict; %1 is the contact's name")},
    {Text::NcVerdictTheyAsked, QT_TRANSLATE_NOOP3("Air", "This person has already sent you a request. It is waiting in Requests.", "new contact: verdict")},
    {Text::NcVerdictBlocked, QT_TRANSLATE_NOOP3("Air", "This key is blocked on this device. Sending the request lifts the block.", "new contact: verdict")},
    {Text::NcVerdictOk, QT_TRANSLATE_NOOP3("Air", "The ID is fine. The request goes as soon as they are online.", "new contact: verdict")},
    {Text::NcWhyWhole, QT_TRANSLATE_NOOP3("Air", "Paste the whole ID first: 76 characters.", "new contact: why Send request is off")},
    {Text::NcWhyOwn, QT_TRANSLATE_NOOP3("Air", "You cannot add your own ID.", "new contact: why Send request is off")},
    {Text::NcWhyFix, QT_TRANSLATE_NOOP3("Air", "Fix the ID first.", "new contact: why Send request is off")},
    {Text::NcWhyContact, QT_TRANSLATE_NOOP3("Air", "They are already in your contacts.", "new contact: why Send request is off")},
    {Text::NcWhyTheyAsked, QT_TRANSLATE_NOOP3("Air", "Accept their request in Requests.", "new contact: why Send request is off")},
    {Text::NcOpenChat, QT_TRANSLATE_NOOP3("Air", "Open chat", "new contact: link beside the verdict")},
    {Text::NcOpenRequests, QT_TRANSLATE_NOOP3("Air", "Open Requests", "new contact: link beside the verdict")},
    {Text::NcPaste, QT_TRANSLATE_NOOP3("Air", "Paste", "new contact: under the empty ID field")},
    {Text::NcClear, QT_TRANSLATE_NOOP3("Air", "Clear", "new contact: under the ID field")},
    {Text::NcMessageLabel, QT_TRANSLATE_NOOP3("Air", "Message with the request", "new contact: label of the message field")},
    {Text::NcMessageNote, QT_TRANSLATE_NOOP3("Air", "They read it before they accept. Left empty, the greeting in the field goes.", "new contact: under the message field")},
    {Text::NcMessageTooLong, QT_TRANSLATE_NOOP3("Air", "Too long for a request: shorten the message.", "new contact: the message is over 1016 bytes")},
    {Text::NcGreeting, QT_TRANSLATE_NOOP3("Air", "Hello! Could you please add me to your contact list?", "new contact: the greeting sent when the message is left empty")},
    {Text::NcSend, QT_TRANSLATE_NOOP3("Air", "Send request", "new contact: the main button")},
    {Text::NcSendFailed, QT_TRANSLATE_NOOP3("Air", "The request was not sent. Try again.", "new contact: Tox refused the request")},
    {Text::NcOwnTitle, QT_TRANSLATE_NOOP3("Air", "Your ID", "new contact: heading over your own ID")},
    {Text::NcOwnNote, QT_TRANSLATE_NOOP3("Air", "Send it to people who want to add you.", "new contact: under your own ID")},
    {Text::NcCopy, QT_TRANSLATE_NOOP3("Air", "Copy", "new contact: copies your own ID")},
    {Text::NcCopied, QT_TRANSLATE_NOOP3("Air", "ID copied", "toast after Copy")},
    {Text::NcImport, QT_TRANSLATE_NOOP3("Air", "Import contacts from a file", "new contact: link to the import of a list of IDs")},
    {Text::NcSentTitle, QT_TRANSLATE_NOOP3("Air", "Request on its way", "new contact: after Send request")},
    {Text::NcSentText, QT_TRANSLATE_NOOP3("Air", "The request goes as soon as they are online. Their name becomes known once they accept it. Until then they are in the list as waiting for reply.", "new contact: after Send request")},
    {Text::NcAcceptedTitle, QT_TRANSLATE_NOOP3("Air", "Request accepted", "new contact: the person accepted while the panel was open")},
    {Text::NcAcceptedText, QT_TRANSLATE_NOOP3("Air", "%1 is in your contacts: you can write now.", "new contact: %1 is the name")},
    {Text::NcThem, QT_TRANSLATE_NOOP3("Air", "them", "new contact: the person whose name is not known yet")},
    {Text::NcDone, QT_TRANSLATE_NOOP3("Air", "Done", "new contact: closes the panel after sending")},
    {Text::NcPathWaiting, QT_TRANSLATE_NOOP3("Air", "You and %1, the link is not there yet", "new contact: the path picture for screen readers; %1 is the name")},
    {Text::NcPathLive, QT_TRANSLATE_NOOP3("Air", "You and %1, connected", "new contact: the path picture for screen readers; %1 is the name")},
    {Text::Close, QT_TRANSLATE_NOOP3("Air", "Close", "button that closes a panel")},
    {Text::CmdNewContact, QT_TRANSLATE_NOOP3("Air", "New contact with this ID", "command line: row over the matches when it holds the start of an ID")},
    {Text::CmdIdOk, QT_TRANSLATE_NOOP3("Air", "the ID is fine", "command line: note of the New contact row")},
    {Text::CmdIdLeft, QT_TRANSLATE_N_NOOP3("Air", "%n character(s) missing", "command line: note of the New contact row"), true},
    {Text::CmdIdOwn, QT_TRANSLATE_NOOP3("Air", "this is your ID", "command line: note of the New contact row")},
    {Text::CmdIdBad, QT_TRANSLATE_NOOP3("Air", "the check does not match", "command line: note of the New contact row")},
    {Text::CmdIdContact, QT_TRANSLATE_NOOP3("Air", "already in contacts", "command line: note of the New contact row")},
    {Text::NoChatOpen, QT_TRANSLATE_NOOP3("Air", "Choose a conversation in the list. To add someone, paste their ID into the line at the top.", "the conversation column while no conversation is open")},
    {Text::InviteTag, QT_TRANSLATE_NOOP3("Air", "Group invitation", "label of a group invitation card")},
    {Text::InviteFrom, QT_TRANSLATE_NOOP3("Air", "%1 invites you.", "group invitation card: %1 is the contact who invites")},
    {Text::InviteWait, QT_TRANSLATE_NOOP3("Air", "You can join once %1 is online.", "group invitation card and toast: %1 is the contact who invites")},
    {Text::InviteJoin, QT_TRANSLATE_NOOP3("Air", "Join", "group invitation card button")},
    {Text::RequestDeclined, QT_TRANSLATE_NOOP3("Air", "Request declined", "toast after Decline on a friend request card")},
    {Text::InviteDeclined, QT_TRANSLATE_NOOP3("Air", "Invitation declined", "toast after Decline on a group invitation card")},
    {Text::Undo, QT_TRANSLATE_NOOP3("Air", "Undo", "toast button that takes the last action back")},
    {Text::RequestAcceptFailed, QT_TRANSLATE_NOOP3("Air", "Could not accept the request. Try again.", "error dialog under Couldn't add the contact: Tox refused to add the person")},
    {Text::InviteJoinFailed, QT_TRANSLATE_NOOP3("Air", "Could not join the group.", "New group, under the key field: Tox refused to join")},
    {Text::GroupRoleFounder, QT_TRANSLATE_NOOP3("Air", "founder", "group role, as a member row says it")},
    {Text::GroupRoleModerator, QT_TRANSLATE_NOOP3("Air", "moderator", "group role, as a member row says it")},
    {Text::GroupRoleMember, QT_TRANSLATE_NOOP3("Air", "member", "group role, as a member row says it")},
    {Text::GroupRoleObserver, QT_TRANSLATE_NOOP3("Air", "observer", "group role, as a member row says it")},
    {Text::GroupYouFounder, QT_TRANSLATE_NOOP3("Air", "You founded the group. You name moderators, make anyone a member or an observer, remove members and change the group settings.", "group dossier: Your role")},
    {Text::GroupYouModerator, QT_TRANSLATE_NOOP3("Air", "You are a moderator. You make members observers and observers members, and you can remove them from the group.", "group dossier: Your role")},
    {Text::GroupYouMember, QT_TRANSLATE_NOOP3("Air", "You are a member. You write in the group; the founder and the moderators manage it.", "group dossier: Your role")},
    {Text::GroupYouObserver, QT_TRANSLATE_NOOP3("Air", "You are an observer. You read the group but cannot write; a moderator can give you the right to write.", "group dossier: Your role")},
    {Text::GroupHeader, QT_TRANSLATE_NOOP3("Air", "%1, %2 online now", "group header: %1 is the member count, %2 how many of the others are online")},
    {Text::GroupHeaderNone, QT_TRANSLATE_NOOP3("Air", "%1, nobody online now", "group header: %1 is the member count")},
    {Text::GroupHeaderOffline, QT_TRANSLATE_NOOP3("Air", "%1, not connected to the group", "group header: %1 is the member count")},
    {Text::GroupHintOffline, QT_TRANSLATE_NOOP3("Air", "No connection to the group: you can send once it connects", "line under the field of a group: Send waits, the desktop keeps no group message for later")},
    {Text::GroupHintSelfConnecting, QT_TRANSLATE_NOOP3("Air", "No connection to the group: the app is connecting. You can send once it connects", "line under the field of a group while our own node looks for the network: Send waits")},
    {Text::GroupHintSelfOffline, QT_TRANSLATE_NOOP3("Air", "No connection to the group: this computer has no network. You can send once it is back", "line under the field of a group while the computer has no network: Send waits")},
    {Text::GroupHintAlone, QT_TRANSLATE_NOOP3("Air", "Only you are in the group so far: invite people from the group dossier", "line under the field of a group")},
    {Text::GroupHintNobody, QT_TRANSLATE_NOOP3("Air", "Nobody else is online: you can send once someone comes online", "line under the field of a group: Send waits, a message now would reach nobody")},
    {Text::GroupHintOneOther, QT_TRANSLATE_NOOP3("Air", "The other member is online and gets it now", "line under the field of a group of two")},
    {Text::GroupGetNow, QT_TRANSLATE_NOOP3("Air", "Get it now", "group header: the strip of the members who get a message right away")},
    {Text::GroupGetNowSpoken, QT_TRANSLATE_NOOP3("Air", "Get it now: %1", "screen reader: %1 is the list of names")},
    {Text::GroupDossierHint, QT_TRANSLATE_NOOP3("Air", "Opens the group dossier", "tooltip of the group header")},
    {Text::GroupReadOnlyObserver, QT_TRANSLATE_NOOP3("Air", "Read only. You are an observer: a moderator can give you the right to write.", "bar in place of the field of a group")},
    {Text::GroupReadOnlyModerators, QT_TRANSLATE_NOOP3("Air", "Read only. Here only the moderators and the founder write.", "bar in place of the field of a group")},
    {Text::GroupReadOnlyFounder, QT_TRANSLATE_NOOP3("Air", "Read only. Here only the founder writes.", "bar in place of the field of a group")},
    {Text::GroupAskModerator, QT_TRANSLATE_NOOP3("Air", "Write to a moderator", "button of the read-only bar")},
    {Text::GroupAskFounder, QT_TRANSLATE_NOOP3("Air", "Write to the founder", "button of the read-only bar")},
    {Text::GroupAskNobody, QT_TRANSLATE_NOOP3("Air", "No moderator of this group is known yet", "read-only bar while nobody can be asked")},
    {Text::GroupAskChoose, QT_TRANSLATE_NOOP3("Air", "Whom to write to", "menu of the moderators to write to")},
    {Text::GroupMemberStatus, QT_TRANSLATE_NOOP3("Air", "%1, %2", "member row: %1 is the role, %2 online or offline")},
    {Text::GroupMemberOffline, QT_TRANSLATE_NOOP3("Air", "offline", "member row: not online now")},
    {Text::GroupMemberYou, QT_TRANSLATE_NOOP3("Air", "%1 (you)", "member row of your own")},
    {Text::GroupMemberHint, QT_TRANSLATE_NOOP3("Air", "Opens the actions for this member", "tooltip of a member row")},
    {Text::GroupYourRole, QT_TRANSLATE_NOOP3("Air", "Your role", "group dossier section")},
    {Text::GroupWhoGetsNow, QT_TRANSLATE_NOOP3("Air", "Who gets a message now", "group dossier section")},
    {Text::GroupWhoGetsNowNote, QT_TRANSLATE_NOOP3("Air", "Online now: %1 of %2. The others get messages when they come online.", "group dossier: %1 online of %2 others")},
    {Text::GroupMembers, QT_TRANSLATE_NOOP3("Air", "Members", "group dossier section")},
    {Text::GroupSearchMembers, QT_TRANSLATE_NOOP3("Air", "Find a member", "placeholder of the member search")},
    {Text::GroupNobodyFound, QT_TRANSLATE_NOOP3("Air", "Nobody with that name", "member search found nobody")},
    {Text::GroupKey, QT_TRANSLATE_NOOP3("Air", "Group key", "group dossier section")},
    {Text::GroupCopyKey, QT_TRANSLATE_NOOP3("Air", "Copy", "group dossier: copies the group key")},
    {Text::GroupInvite, QT_TRANSLATE_NOOP3("Air", "Invite", "group dossier and New group: invite contacts")},
    {Text::GroupKeyCopied, QT_TRANSLATE_NOOP3("Air", "Group key copied", "toast after copying the group key")},
    {Text::GroupQrHeading, QT_TRANSLATE_NOOP3("Air", "Group %1", "title of the QR code of a group key")},
    {Text::GroupQrSubtitle, QT_TRANSLATE_NOOP3("Air", "Scan to find the group by its key", "under the QR code of a group key")},
    {Text::GroupMutedNote, QT_TRANSLATE_NOOP3("Air", "No notifications for new messages of this group; the unread count stays in the list.", "group dossier, under Mute")},
    {Text::GroupUnmutedNote, QT_TRANSLATE_NOOP3("Air", "New messages of this group come with a notification.", "group dossier, under Mute")},
    {Text::GroupLeave, QT_TRANSLATE_NOOP3("Air", "Leave the group", "group dossier button")},
    {Text::GroupLeaveNote, QT_TRANSLATE_NOOP3("Air", "Its messages stop coming, and the history leaves this device.", "group dossier, under Leave the group")},
    {Text::GroupLeaveTitle, QT_TRANSLATE_NOOP3("Air", "Leave %1?", "question before leaving: %1 is the group")},
    {Text::GroupLeaveMessage, QT_TRANSLATE_NOOP3("Air", "The members see that you left. To come back you need an invitation or the group key.", "question before leaving a group")},
    {Text::GroupLeaveVerb, QT_TRANSLATE_NOOP3("Air", "Leave", "button that leaves the group")},
    {Text::GroupWritePrivately, QT_TRANSLATE_NOOP3("Air", "Write privately", "member menu and the field of private messages")},
    {Text::GroupMakeModerator, QT_TRANSLATE_NOOP3("Air", "Make moderator", "member menu")},
    {Text::GroupMakeMember, QT_TRANSLATE_NOOP3("Air", "Make member", "member menu")},
    {Text::GroupMakeObserver, QT_TRANSLATE_NOOP3("Air", "Make observer", "member menu")},
    {Text::GroupRemove, QT_TRANSLATE_NOOP3("Air", "Remove from the group", "member menu")},
    {Text::GroupMakeModeratorTitle, QT_TRANSLATE_NOOP3("Air", "Make %1 a moderator?", "question: %1 is the member")},
    {Text::GroupMakeMemberTitle, QT_TRANSLATE_NOOP3("Air", "Make %1 a member?", "question: %1 is the member")},
    {Text::GroupMakeObserverTitle, QT_TRANSLATE_NOOP3("Air", "Make %1 an observer?", "question: %1 is the member")},
    {Text::GroupRemoveTitle, QT_TRANSLATE_NOOP3("Air", "Remove %1 from the group?", "question: %1 is the member")},
    {Text::GroupMakeModeratorMessage, QT_TRANSLATE_NOOP3("Air", "A moderator makes members observers and back and can remove them.", "question before making someone a moderator")},
    {Text::GroupMakeMemberMessage, QT_TRANSLATE_NOOP3("Air", "A member writes in the group and manages nothing.", "question before making someone a member")},
    {Text::GroupMakeObserverMessage, QT_TRANSLATE_NOOP3("Air", "An observer reads the group but cannot write.", "question before making someone an observer")},
    {Text::GroupRemoveMessage, QT_TRANSLATE_NOOP3("Air", "The group's messages stop coming. Coming back takes a new invitation or, in an open group, its key.", "question before removing someone from a group")},
    {Text::GroupNowModerator, QT_TRANSLATE_NOOP3("Air", "%1 is a moderator now", "toast after the change: %1 is the member")},
    {Text::GroupNowMember, QT_TRANSLATE_NOOP3("Air", "%1 is a member now", "toast after the change: %1 is the member")},
    {Text::GroupNowObserver, QT_TRANSLATE_NOOP3("Air", "%1 is an observer now", "toast after the change: %1 is the member")},
    {Text::GroupRemoved, QT_TRANSLATE_NOOP3("Air", "%1 is no longer in the group", "toast after removing: %1 is the member")},
    {Text::GroupChangeFailed, QT_TRANSLATE_NOOP3("Air", "The group did not take the change. Try again once it is connected.", "error dialog: toxcore refused a role change or a removal")},
    {Text::GroupInviteTitle, QT_TRANSLATE_NOOP3("Air", "Invite to the group", "title of the invitation list")},
    {Text::InviteOnlineNote, QT_TRANSLATE_NOOP3("Air", "An invitation goes over your connection with the contact, so only contacts online now can be invited.", "invitation list")},
    {Text::InviteChooseFirst, QT_TRANSLATE_NOOP3("Air", "Choose whom to invite", "invitation list: why the button waits")},
    {Text::InviteWhenOnline, QT_TRANSLATE_NOOP3("Air", "can be invited once online", "invitation list: a contact who is offline")},
    {Text::InviteNoContacts, QT_TRANSLATE_NOOP3("Air", "No contacts yet: add someone first.", "invitation list without contacts")},
    {Text::InviteNobodyFound, QT_TRANSLATE_NOOP3("Air", "No contact with that name", "invitation list search found nobody")},
    {Text::InviteSearch, QT_TRANSLATE_NOOP3("Air", "Find a contact", "placeholder of the invitation list search")},
    {Text::NewGroupTitle, QT_TRANSLATE_NOOP3("Air", "New group", "title of the New group panel")},
    {Text::NewGroupName, QT_TRANSLATE_NOOP3("Air", "Name", "New group: the name field")},
    {Text::NewGroupNamePlaceholder, QT_TRANSLATE_NOOP3("Air", "For example, Book club", "New group: placeholder of the name")},
    {Text::NewGroupNameNote, QT_TRANSLATE_NOOP3("Air", "Everyone you invite sees it. Later the topic can change, the name cannot.", "New group: under the name")},
    {Text::NewGroupPrivacy, QT_TRANSLATE_NOOP3("Air", "Who can join", "New group and group dossier section")},
    {Text::PrivacyInviteOnly, QT_TRANSLATE_NOOP3("Air", "By invitation only", "who can join a group")},
    {Text::PrivacyInviteOnlyNote, QT_TRANSLATE_NOOP3("Air", "Only people invited by a member can join.", "who can join a group: consequence")},
    {Text::PrivacyOpen, QT_TRANSLATE_NOOP3("Air", "Open to anyone with the group key", "who can join a group")},
    {Text::PrivacyOpenNote, QT_TRANSLATE_NOOP3("Air", "Anyone who has the group key can join, no invitation needed.", "who can join a group: consequence")},
    {Text::NewGroupInviteNote, QT_TRANSLATE_NOOP3("Air", "Invitations go at once, so only contacts online can be invited here. Invite the others later from the group dossier.", "New group: over the contacts to invite")},
    {Text::NewGroupCreate, QT_TRANSLATE_NOOP3("Air", "Create group", "New group: the main button")},
    {Text::NewGroupNameFirst, QT_TRANSLATE_NOOP3("Air", "Name the group to create it", "New group: why the button waits")},
    {Text::NewGroupNameTooLong, QT_TRANSLATE_NOOP3("Air", "The name is too long for a group: shorten it", "New group: why the button waits")},
    {Text::NewGroupFailed, QT_TRANSLATE_NOOP3("Air", "Could not create the group. Try again.", "New group: toxcore refused it")},
    {Text::NewGroupJoinLink, QT_TRANSLATE_NOOP3("Air", "Join a group by its key", "New group: the quiet link to joining by key")},
    {Text::NewGroupCreateLink, QT_TRANSLATE_NOOP3("Air", "Create a new group", "join by key: the quiet link back to New group")},
    {Text::JoinTitle, QT_TRANSLATE_NOOP3("Air", "Join a group", "title of the panel while it joins by key")},
    {Text::JoinKeyNote, QT_TRANSLATE_NOOP3("Air", "Joining works when the group is open to anyone with its key, or when a contact of yours who is in it is online.", "join by key: under the field")},
    {Text::JoinKeyBad, QT_TRANSLATE_NOOP3("Air", "A group key has 64 characters: 0 to 9 and A to F.", "join by key: why Join waits")},
    {Text::JoinAlready, QT_TRANSLATE_NOOP3("Air", "You are in this group already.", "join by key: the key is of a group in the list")},
    {Text::JoinOpen, QT_TRANSLATE_NOOP3("Air", "Open the group", "join by key: opens the group that is in the list already")},
    {Text::PrivateTitle, QT_TRANSLATE_NOOP3("Air", "Private messages with %1", "title of the private messages with a member: %1")},
    {Text::PrivateNote, QT_TRANSLATE_NOOP3("Air", "Only %1 gets these messages. They are kept on this device, not in the group.", "private messages: %1 is the member")},
    {Text::PrivateEmpty, QT_TRANSLATE_NOOP3("Air", "Nothing written privately yet.", "private messages: none yet")},
    {Text::PrivateReceived, QT_TRANSLATE_NOOP3("Air", "%1 wrote to you privately", "band of a private message in a group: %1 is the member")},
    {Text::PrivateFailed, QT_TRANSLATE_NOOP3("Air", "Not sent: the group is not connected or the member left. Try again.", "private messages: toxcore refused the message")},
    {Text::GroupMemberMute, QT_TRANSLATE_NOOP3("Air", "Mute in this group", "member menu: no notifications for this member's messages")},
    {Text::GroupMemberUnmute, QT_TRANSLATE_NOOP3("Air", "Unmute in this group", "member menu")},
    {Text::GroupMemberMuted, QT_TRANSLATE_NOOP3("Air", "%1 muted in this group", "toast: %1 is the member")},
    {Text::GroupMemberUnmuted, QT_TRANSLATE_NOOP3("Air", "%1 unmuted", "toast: %1 is the member")},
    {Text::GroupMemberHide, QT_TRANSLATE_NOOP3("Air", "Hide their messages", "member menu: this device stops showing the member's messages and voice")},
    {Text::GroupMemberShow, QT_TRANSLATE_NOOP3("Air", "Show their messages", "member menu")},
    {Text::GroupMemberHidden, QT_TRANSLATE_NOOP3("Air", "%1: messages hidden on this device", "toast: %1 is the member")},
    {Text::GroupMemberShown, QT_TRANSLATE_NOOP3("Air", "%1: messages shown again", "toast: %1 is the member")},
    {Text::GroupMembersCount, QT_TRANSLATE_N_NOOP3("Air", "%n member(s)", "group header: how many are in the group, you included"), true},
    {Text::GroupHintAll, QT_TRANSLATE_N_NOOP3("Air", "All %n get it now: everyone is online", "line under the field of a group"), true},
    {Text::GroupHintSome, QT_TRANSLATE_N_NOOP3("Air", "%n of %1 get it now, the rest when they come online", "line under the field of a group: %n online of %1 others"), true},
    {Text::GroupBytesOver, QT_TRANSLATE_N_NOOP3("Air", "%n byte(s) over the limit of a group message", "length counter of a group: Send waits until the text is shorter"), true},
    {Text::InviteSendCount, QT_TRANSLATE_N_NOOP3("Air", "Invite %n", "invitation list: the main button"), true},
    {Text::InviteSent, QT_TRANSLATE_N_NOOP3("Air", "%n invitation(s) sent", "toast after inviting"), true},
    {Text::NewGroupCreateInvite, QT_TRANSLATE_N_NOOP3("Air", "Create and invite %n", "New group: the main button with contacts chosen"), true},
    {Text::GroupObserverNoPrivate, QT_TRANSLATE_NOOP3("Air", "An observer cannot write here, not even privately: ask a moderator outside the group.", "group: why an observer cannot ask or answer in private messages, which toxcore refuses to observers")},
    {Text::FileAccept, QT_TRANSLATE_NOOP3("Air", "Accept", "file entry: receive the file")},
    {Text::FileDecline, QT_TRANSLATE_NOOP3("Air", "Decline", "file entry: refuse the file")},
    {Text::FilePause, QT_TRANSLATE_NOOP3("Air", "Pause", "file entry: pause the transfer")},
    {Text::FileResume, QT_TRANSLATE_NOOP3("Air", "Resume", "file entry: go on with a paused transfer")},
    {Text::FileCancel, QT_TRANSLATE_NOOP3("Air", "Cancel", "file entry: stop the transfer for good")},
    {Text::FileSendAgain, QT_TRANSLATE_NOOP3("Air", "Send again", "file entry: send a file of ours that was not sent")},
    {Text::FileOpen, QT_TRANSLATE_NOOP3("Air", "Open", "file entry: open the file that is here")},
    {Text::FileShowInFolder, QT_TRANSLATE_NOOP3("Air", "Show in folder", "file entry: open the folder that holds the file")},
    {Text::FileLeft, QT_TRANSLATE_NOOP3("Air", "%1 left", "file entry while it moves: %1 is a size such as 1.2 MB")},
    {Text::FilePaused, QT_TRANSLATE_NOOP3("Air", "Paused", "file entry: we paused the transfer")},
    {Text::FilePausedThere, QT_TRANSLATE_NOOP3("Air", "Paused on the other side", "file entry: the other person paused the transfer, only they can go on")},
    {Text::FileWaitsAccept, QT_TRANSLATE_NOOP3("Air", "Waits for %1 to accept it", "file entry of ours: %1 is the person who has to accept it")},
    {Text::FileDownloadStopped, QT_TRANSLATE_NOOP3("Air", "The download stopped", "file entry of theirs that broke or was cancelled")},
    {Text::FileNotSent, QT_TRANSLATE_NOOP3("Air", "Not sent", "file entry of ours that broke or was cancelled")},
    {Text::FileNameAndSize, QT_TRANSLATE_NOOP3("Air", "%1, %2", "what a screen reader says for a file: %1 the name, %2 the size or the length")},
    {Text::FileSaveTitle, QT_TRANSLATE_NOOP3("Air", "Save the file", "title of the dialog that chooses where a received file goes")},
    {Text::GroupFileLimit, QT_TRANSLATE_NOOP3("Air", "A group takes files up to %1", "group: tooltip of the paperclip and the reason of a refusal; %1 is a size such as 200 MB")},
    {Text::FileNotSentTitle, QT_TRANSLATE_NOOP3("Air", "The file wasn't sent", "title of the dialog when a file could not be sent")},
    {Text::FileAcceptFailedTitle, QT_TRANSLATE_NOOP3("Air", "Couldn't accept the file", "title of the dialog when a received file could not be saved")},
    {Text::FileOffline, QT_TRANSLATE_NOOP3("Air", "%1 is offline. A file goes only while you are both online.", "why a file was not sent: %1 is the person")},
    {Text::FileTooLarge, QT_TRANSLATE_NOOP3("Air", "%1 is too large: a file can be up to %2.", "why a file was not sent: %1 the file name, %2 a size such as 200 MB")},
    {Text::FileEmpty, QT_TRANSLATE_NOOP3("Air", "%1 is empty: there is nothing to send.", "why a file was not sent: %1 the file name")},
    {Text::FileUnreadable, QT_TRANSLATE_NOOP3("Air", "%1 could not be read. Check that the file is still there and can be opened.", "why a file was not sent: %1 the file name")},
    {Text::FileNotWritable, QT_TRANSLATE_NOOP3("Air", "This folder cannot be written to. Choose another one.", "why a received file could not be saved")},
    {Text::FileGroupNotConnected, QT_TRANSLATE_NOOP3("Air", "The group is not connected right now. Try again once it is.", "why a file was not sent to a group")},
    {Text::VoiceNote, QT_TRANSLATE_NOOP3("Air", "Voice message", "the name a voice note shows and a screen reader says")},
    {Text::VoiceListen, QT_TRANSLATE_NOOP3("Air", "Listen", "voice note: plays it")},
    {Text::VoicePause, QT_TRANSLATE_NOOP3("Air", "Pause", "voice note: pauses it")},
    {Text::VoiceFailedTitle, QT_TRANSLATE_NOOP3("Air", "Couldn't play the voice message", "title of the dialog when a voice note can be played neither here nor by the system")},
    {Text::TryAgain, QT_TRANSLATE_NOOP3("Air", "Try again.", "what to do after an error that has no other advice")},
    {Text::Ok, QT_TRANSLATE_NOOP3("Air", "OK", "button that closes an error dialog")},
    {Text::Details, QT_TRANSLATE_NOOP3("Air", "Details", "error dialog: shows the code for support")},
    {Text::SupportCode, QT_TRANSLATE_NOOP3("Air", "Code for support: %1", "error dialog: %1 is a code such as toxav_call 2")},
    {Text::CopyCode, QT_TRANSLATE_NOOP3("Air", "Copy", "error dialog: copies the code for support")},
    {Text::CallEntry, QT_TRANSLATE_NOOP3("Air", "Call, %1", "a call in the transcript: %1 is its length such as 4:12")},
    {Text::CallEntryCutOff, QT_TRANSLATE_NOOP3("Air", "Call, %1, cut off", "a call in the transcript that ended by an error: %1 is its length")},
    {Text::CallMissed, QT_TRANSLATE_NOOP3("Air", "Missed call", "a call of theirs in the transcript that nobody answered")},
    {Text::CallNoAnswer, QT_TRANSLATE_NOOP3("Air", "No answer", "a call of ours in the transcript that nobody answered")},
    {Text::CallOutgoing, QT_TRANSLATE_NOOP3("Air", "Outgoing call", "a call of ours in the transcript, written by an older version")},
    {Text::CallIncoming, QT_TRANSLATE_NOOP3("Air", "Incoming call", "a call of theirs: the band while it rings, and in the transcript of an older version")},
    {Text::CallCalling, QT_TRANSLATE_NOOP3("Air", "Calling", "the call band while our call rings")},
    {Text::CallOfflineTitle, QT_TRANSLATE_NOOP3("Air", "%1 is offline: the call will not get through", "question before calling someone offline: %1 is the person")},
    {Text::CallOfflineWait, QT_TRANSLATE_NOOP3("Air", "You can wait here: the call starts once they are online.", "question before calling someone offline")},
    {Text::CallWaitAction, QT_TRANSLATE_NOOP3("Air", "Wait and call", "button: wait for the person and call once they are online")},
    {Text::CallWaiting, QT_TRANSLATE_NOOP3("Air", "Waiting for %1 to come online", "the call band while it waits: %1 is the person")},
    {Text::CallWaitingNote, QT_TRANSLATE_NOOP3("Air", "The call starts on its own once they are online.", "the call band while it waits")},
    {Text::CallCancel, QT_TRANSLATE_NOOP3("Air", "Cancel", "the call band: stop waiting for the person")},
    {Text::CallMicTitle, QT_TRANSLATE_NOOP3("Air", "No access to the microphone", "title of the dialog when macOS refuses the microphone")},
    {Text::CallMicMessage, QT_TRANSLATE_NOOP3("Air", "Without the microphone the other person will not hear you. Allow it in System Settings: Privacy and Security, Microphone.", "dialog when macOS refuses the microphone")},
    {Text::CallCameraTitle, QT_TRANSLATE_NOOP3("Air", "No access to the camera", "title of the dialog when macOS refuses the camera")},
    {Text::CallCameraMessage, QT_TRANSLATE_NOOP3("Air", "Without the camera the other person will not see you; the call can go with sound. Allow it in System Settings: Privacy and Security, Camera.", "dialog when macOS refuses the camera")},
    {Text::CallOpenSettings, QT_TRANSLATE_NOOP3("Air", "Open Settings", "button that opens the privacy settings of the system")},
    {Text::CallAudioOnly, QT_TRANSLATE_NOOP3("Air", "Call without video", "button: a video call goes with sound alone")},
    {Text::CallAnswer, QT_TRANSLATE_NOOP3("Air", "Answer", "the call band of an incoming call")},
    {Text::CallAnswerVideo, QT_TRANSLATE_NOOP3("Air", "With video", "the call band of an incoming video call: answer with our camera on")},
    {Text::CallDecline, QT_TRANSLATE_NOOP3("Air", "Decline", "the call band of an incoming call")},
    {Text::CallEnd, QT_TRANSLATE_NOOP3("Air", "End", "the call band: ends the call, or stops ours that rings")},
    {Text::CallMicMute, QT_TRANSLATE_NOOP3("Air", "Mute the microphone", "the call band")},
    {Text::CallMicUnmute, QT_TRANSLATE_NOOP3("Air", "Unmute the microphone", "the call band")},
    {Text::CallSoundMute, QT_TRANSLATE_NOOP3("Air", "Mute the sound", "the call band: the other person's voice")},
    {Text::CallSoundUnmute, QT_TRANSLATE_NOOP3("Air", "Unmute the sound", "the call band: the other person's voice")},
    {Text::CallStartFailedTitle, QT_TRANSLATE_NOOP3("Air", "Couldn't start the call", "title of the dialog when a call could not start")},
    {Text::CallAnswerFailedTitle, QT_TRANSLATE_NOOP3("Air", "Couldn't answer the call", "title of the dialog when a call could not be answered")},
    {Text::CallAlreadyOn, QT_TRANSLATE_NOOP3("Air", "A call with this person is already on.", "why a call could not start")},
    {Text::CallNotCalling, QT_TRANSLATE_NOOP3("Air", "%1 is no longer calling.", "why a call could not be answered: the person hung up first")},
    {Text::GroupNoCalls, QT_TRANSLATE_NOOP3("Air", "No calls in groups", "group header, where the call buttons of a conversation stand")},
    {Text::GroupNoCallsNote, QT_TRANSLATE_NOOP3("Air", "Tox groups carry messages and files, not calls. Call a member from your conversation with them.", "group header: tooltip of No calls in groups")},
    {Text::YouQr, QT_TRANSLATE_NOOP3("Air", "QR code of your ID", "You: what a screen reader says for the QR code")},
    {Text::YouQrHint, QT_TRANSLATE_NOOP3("Air", "Scanned in New contact on a phone", "You: under the QR code")},
    {Text::YouCopyId, QT_TRANSLATE_NOOP3("Air", "Copy ID", "You: copies your whole ID")},
    {Text::YouIdCopied, QT_TRANSLATE_NOOP3("Air", "ID copied", "You: toast after Copy ID")},
    {Text::YouCopyQr, QT_TRANSLATE_NOOP3("Air", "Copy QR code", "You: copies the picture of the QR code")},
    {Text::YouQrCopied, QT_TRANSLATE_NOOP3("Air", "QR code copied", "You: toast after Copy QR code")},
    {Text::YouSaveQr, QT_TRANSLATE_NOOP3("Air", "Save QR code", "You: saves the picture of the QR code to a file; also the title of the save dialog")},
    {Text::YouQrFilter, QT_TRANSLATE_NOOP3("Air", "PNG image (*.png)", "You: file type of the saved QR code; keep (*.png)")},
    {Text::YouQrSaved, QT_TRANSLATE_NOOP3("Air", "QR code saved", "You: toast after Save QR code")},
    {Text::YouQrSaveFailedTitle, QT_TRANSLATE_NOOP3("Air", "The QR code wasn't saved", "You: title of the error when the QR code could not be saved")},
    {Text::YouPhoto, QT_TRANSLATE_NOOP3("Air", "Profile photo", "You: what a screen reader says for your photo")},
    {Text::YouPhotoHint, QT_TRANSLATE_NOOP3("Air", "Changes the photo", "You: what a click on the photo does")},
    {Text::YouPhotoChoose, QT_TRANSLATE_NOOP3("Air", "Choose a photo", "You: menu of the photo; also the title of the file dialog")},
    {Text::YouPhotoRemove, QT_TRANSLATE_NOOP3("Air", "Remove the photo", "You: menu of the photo")},
    {Text::YouPhotoFilter, QT_TRANSLATE_NOOP3("Air", "Images (%1)", "You: file type filter of the photo dialog; %1 is the list of extensions")},
    {Text::YouPhotoFailedTitle, QT_TRANSLATE_NOOP3("Air", "The photo wasn't changed", "You: title of the error when a photo could not be set")},
    {Text::YouPhotoUnreadable, QT_TRANSLATE_NOOP3("Air", "This file can't be read as a picture. Choose another one.", "You: why a photo could not be set")},
    {Text::YouPhotoTooLarge, QT_TRANSLATE_NOOP3("Air", "The picture is too large. Choose a smaller one.", "You: why a photo could not be set")},
    {Text::YouName, QT_TRANSLATE_NOOP3("Air", "Name", "You: label of the name field")},
    {Text::YouNamePlaceholder, QT_TRANSLATE_NOOP3("Air", "Your name", "You: placeholder of the name field")},
    {Text::YouStatus, QT_TRANSLATE_NOOP3("Air", "Status", "You: label of the status field")},
    {Text::YouStatusPlaceholder, QT_TRANSLATE_NOOP3("Air", "A line your contacts see", "You: placeholder of the status field")},
    {Text::YouSaved, QT_TRANSLATE_NOOP3("Air", "Saved", "You: toast once the name or the status is saved")},
    {Text::YouDetails, QT_TRANSLATE_NOOP3("Air", "Details", "You: section with the parts of your ID")},
    {Text::YouPartKey, QT_TRANSLATE_NOOP3("Air", "Key", "You: the first part of the ID, 64 characters")},
    {Text::YouPartAntispam, QT_TRANSLATE_NOOP3("Air", "Antispam", "You: the second part of the ID, 8 characters")},
    {Text::YouPartCheck, QT_TRANSLATE_NOOP3("Air", "Check", "You: the last part of the ID, 4 characters")},
    {Text::YouAntispam, QT_TRANSLATE_NOOP3("Air", "Change antispam", "You: button")},
    {Text::YouAntispamTitle, QT_TRANSLATE_NOOP3("Air", "Change antispam?", "You: question before changing the antispam")},
    {Text::YouAntispamMessage, QT_TRANSLATE_NOOP3("Air", "Your old ID will stop taking new requests. Contacts stay; the ID and the QR code change.", "You: consequence of changing the antispam")},
    {Text::YouAntispamAction, QT_TRANSLATE_NOOP3("Air", "Change", "You: the verb of the antispam question")},
    {Text::YouAntispamDone, QT_TRANSLATE_NOOP3("Air", "Antispam changed: share the new ID", "You: toast after changing the antispam")},
    {Text::YouPassword, QT_TRANSLATE_NOOP3("Air", "Password", "You: section of the profile password")},
    {Text::YouPasswordOn, QT_TRANSLATE_NOOP3("Air", "The profile and its history are encrypted with this password.", "You: under Password while the profile has one")},
    {Text::YouPasswordOff, QT_TRANSLATE_NOOP3("Air", "The profile has no password: its files on this device are not encrypted.", "You: under Password while the profile has none")},
    {Text::YouPasswordSet, QT_TRANSLATE_NOOP3("Air", "Set a password", "You: button while the profile has no password")},
    {Text::YouPasswordChange, QT_TRANSLATE_NOOP3("Air", "Change password", "You: button while the profile has a password")},
    {Text::YouPasswordNew, QT_TRANSLATE_NOOP3("Air", "Type the new password twice.", "You: title of the dialog that takes a new password")},
    {Text::YouPasswordChanged, QT_TRANSLATE_NOOP3("Air", "Password saved", "You: toast after setting or changing the password")},
    {Text::YouPasswordRemove, QT_TRANSLATE_NOOP3("Air", "Remove password", "You: button")},
    {Text::YouPasswordRemoveTitle, QT_TRANSLATE_NOOP3("Air", "Remove the password?", "You: question before removing the password")},
    {Text::YouPasswordRemoveMessage, QT_TRANSLATE_NOOP3("Air", "The profile and its history are then stored unencrypted, and anyone who opens the app on this computer can get into it.", "You: consequence of removing the password")},
    {Text::YouPasswordRemoveAction, QT_TRANSLATE_NOOP3("Air", "Remove", "You: the verb of the question before removing the password")},
    {Text::YouPasswordRemoved, QT_TRANSLATE_NOOP3("Air", "Password removed", "You: toast after removing the password")},
    {Text::YouPasswordFailedTitle, QT_TRANSLATE_NOOP3("Air", "The password wasn't changed", "You: title of the error when the password could not change")},
    {Text::YouPasswordFailedMessage, QT_TRANSLATE_NOOP3("Air", "The history could not take the new password. Try again.", "You: why the password could not change")},
    {Text::YouFile, QT_TRANSLATE_NOOP3("Air", "Profile file", "You: section of the file the profile is kept in")},
    {Text::YouRename, QT_TRANSLATE_NOOP3("Air", "Rename", "You: renames the profile file")},
    {Text::YouRenameTitle, QT_TRANSLATE_NOOP3("Air", "Rename the profile", "You: title of the rename dialog")},
    {Text::YouRenameLabel, QT_TRANSLATE_NOOP3("Air", "New name of the profile file", "You: label of the field in the rename dialog")},
    {Text::YouRenamed, QT_TRANSLATE_NOOP3("Air", "Profile renamed", "You: toast after renaming the profile")},
    {Text::YouRenameFailedTitle, QT_TRANSLATE_NOOP3("Air", "The profile wasn't renamed", "You: title of the error when the profile could not be renamed")},
    {Text::YouRenameExists, QT_TRANSLATE_NOOP3("Air", "A profile named %1 already exists. Choose another name.", "You: why the profile could not be renamed")},
    {Text::YouRenameError, QT_TRANSLATE_NOOP3("Air", "The profile file couldn't be renamed. Try another name.", "You: why the profile could not be renamed when no other profile has the name")},
    {Text::YouExport, QT_TRANSLATE_NOOP3("Air", "Export", "You: saves a copy of the profile file")},
    {Text::YouExportNote, QT_TRANSLATE_NOOP3("Air", "The profile file holds your ID and contacts. Chat history stays on this device.", "You: under the profile file")},
    {Text::YouExportFilter, QT_TRANSLATE_NOOP3("Air", "Tox profile (*.tox)", "You: file type of an exported profile; keep (*.tox)")},
    {Text::YouExported, QT_TRANSLATE_NOOP3("Air", "Profile exported", "You: toast after exporting the profile")},
    {Text::YouExportFailedTitle, QT_TRANSLATE_NOOP3("Air", "The profile wasn't exported", "You: title of the error when the profile could not be saved")},
    {Text::YouNotWritable, QT_TRANSLATE_NOOP3("Air", "This folder can't be written to. Choose another one.", "You: why a file could not be saved")},
    {Text::YouLogout, QT_TRANSLATE_NOOP3("Air", "Log out", "You: button; also the verb of its question")},
    {Text::YouLogoutTitle, QT_TRANSLATE_NOOP3("Air", "Log out of the profile?", "You: question before logging out")},
    {Text::YouLogoutMessage, QT_TRANSLATE_NOOP3("Air", "The profile stays on this device; you can log in again with its password.", "You: what logging out means, under the button and in its question")},
    {Text::YouLogoutMessageNoPassword, QT_TRANSLATE_NOOP3("Air", "The profile stays on this device. It has no password, so anyone who opens the app on this computer can get back into it.", "You: what logging out means for a profile without a password")},
    {Text::YouDelete, QT_TRANSLATE_NOOP3("Air", "Delete the profile from this device", "You: button at the bottom")},
    {Text::YouDeleteTitle, QT_TRANSLATE_NOOP3("Air", "Delete the profile from this device?", "You: question before deleting the profile")},
    {Text::YouDeleteMessage, QT_TRANSLATE_NOOP3("Air", "%1: the profile, its contacts and history are erased from this computer. Without an exported copy they cannot be brought back.", "You: consequence of deleting; %1 is the name of the profile")},
    {Text::YouDeleteFailedTitle, QT_TRANSLATE_NOOP3("Air", "Couldn't delete the profile", "You: title of the error when files of the profile stayed")},
    {Text::YouDeleteLeftFiles, QT_TRANSLATE_NOOP3("Air", "These files are still on this device: %1. Remove them by hand.", "You: the files that could not be deleted, separated by commas")},
    {Text::StartStep, QT_TRANSLATE_NOOP3("Air", "Step %1 of %2", "first launch: screen reader name of the progress; %1 is the step, %2 the number of steps")},
    {Text::StartBack, QT_TRANSLATE_NOOP3("Air", "Back", "start window: the button that goes back a page")},
    {Text::StartNameTitle, QT_TRANSLATE_NOOP3("Air", "What should we call you", "first launch, step 1: title")},
    {Text::StartNameBody, QT_TRANSLATE_NOOP3("Air", "Only the people you add will see this name.", "first launch, step 1: under the title")},
    {Text::StartNamePlaceholder, QT_TRANSLATE_NOOP3("Air", "Name", "first launch, step 1: placeholder of the name field")},
    {Text::StartNameFirst, QT_TRANSLATE_NOOP3("Air", "Type a name first.", "first launch, step 1: why Next waits")},
    {Text::StartNext, QT_TRANSLATE_NOOP3("Air", "Next", "start window: the main button that goes on to the next step")},
    {Text::StartMoveProfile, QT_TRANSLATE_NOOP3("Air", "Move a profile from another device", "start window: quiet button that moves a profile file in")},
    {Text::StartDefaultFolder, QT_TRANSLATE_NOOP3("Air", "Profile", "start window: name of the profile file when the name typed leaves nothing a file may be called")},
    {Text::StartPasswordTitle, QT_TRANSLATE_NOOP3("Air", "Profile password", "first launch, step 2: title")},
    {Text::StartPasswordBody, QT_TRANSLATE_NOOP3("Air", "A password cannot be recovered: there are no servers. Without a password, the profile on this device is not encrypted.", "first launch, step 2: under the title, said before the choice")},
    {Text::StartPasswordSet, QT_TRANSLATE_NOOP3("Air", "Set a password", "first launch, step 2: choice")},
    {Text::StartPasswordSetDetail, QT_TRANSLATE_NOOP3("Air", "The profile and its history on this device are encrypted with the password, and the profile opens only with it.", "first launch, step 2: what Set a password means")},
    {Text::StartPasswordSkip, QT_TRANSLATE_NOOP3("Air", "Continue without a password", "first launch, step 2: choice")},
    {Text::StartPasswordSkipDetail, QT_TRANSLATE_NOOP3("Air", "The profile and its history are kept on this device unencrypted: anyone who can open its files can read them. It opens without a password.", "first launch, step 2: what Continue without a password means")},
    {Text::StartPasswordPlaceholder, QT_TRANSLATE_NOOP3("Air", "Password", "start window: placeholder of a password field")},
    {Text::StartRepeatPlaceholder, QT_TRANSLATE_NOOP3("Air", "Password again", "first launch, step 2: placeholder of the field that repeats the password")},
    {Text::StartPasswordRule, QT_TRANSLATE_NOOP3("Air", "At least 8 characters, with a letter and a digit.", "first launch, step 2: the rule of a new password, under its fields")},
    {Text::StartPasswordEmpty, QT_TRANSLATE_NOOP3("Air", "Make up a password first.", "first launch, step 2: why Create profile waits")},
    {Text::StartPasswordShort, QT_TRANSLATE_NOOP3("Air", "The password is shorter than 8 characters.", "first launch, step 2: why Create profile waits")},
    {Text::StartPasswordWeak, QT_TRANSLATE_NOOP3("Air", "The password needs a letter and a digit.", "first launch, step 2: why Create profile waits")},
    {Text::StartPasswordRepeatFirst, QT_TRANSLATE_NOOP3("Air", "Type the password again in the second field.", "first launch, step 2: why Create profile waits")},
    {Text::StartPasswordMismatch, QT_TRANSLATE_NOOP3("Air", "The passwords do not match.", "first launch, step 2: why Create profile waits")},
    {Text::StartShowPassword, QT_TRANSLATE_NOOP3("Air", "Show password", "password field: button that shows what is typed")},
    {Text::StartHidePassword, QT_TRANSLATE_NOOP3("Air", "Hide password", "password field: button that hides what is typed again")},
    {Text::StartAutoLogin, QT_TRANSLATE_NOOP3("Air", "Log in automatically", "start window: switch")},
    {Text::StartAutoLoginNote, QT_TRANSLATE_NOOP3("Air", "The password is kept in this computer's system credential store, and the profile opens at start without asking. Do not turn this on on a shared computer.", "start window: what Log in automatically means for a profile with a password")},
    {Text::StartCreate, QT_TRANSLATE_NOOP3("Air", "Create profile", "first launch, step 2: the main button")},
    {Text::StartCreating, QT_TRANSLATE_NOOP3("Air", "Creating the profile", "first launch, step 2: the main button while the profile is being made")},
    {Text::StartCreateFailed, QT_TRANSLATE_NOOP3("Air", "The profile was not created. Try again.", "first launch, step 2: error under the choice")},
    {Text::StartIdTitle, QT_TRANSLATE_NOOP3("Air", "Your ID is ready", "first launch, step 3: title")},
    {Text::StartIdBody, QT_TRANSLATE_NOOP3("Air", "Send your ID to the people you want to write to, or show them the QR code.", "first launch, step 3: under the title")},
    {Text::StartShare, QT_TRANSLATE_NOOP3("Air", "Share", "first launch, step 3: button that copies or saves the ID and its QR code")},
    {Text::StartAddPerson, QT_TRANSLATE_NOOP3("Air", "Add the first person", "first launch, step 3: the main button, opens New contact")},
    {Text::SignInGo, QT_TRANSLATE_NOOP3("Air", "Log in", "sign-in: the main button")},
    {Text::SignInOpening, QT_TRANSLATE_NOOP3("Air", "Opening the profile", "sign-in: the main button while the profile opens")},
    {Text::SignInEnterPassword, QT_TRANSLATE_NOOP3("Air", "Enter the password first.", "sign-in: why Log in waits")},
    {Text::SignInNoPassword, QT_TRANSLATE_NOOP3("Air", "This profile has no password: it opens without one, and its files on this device are not encrypted.", "sign-in: under the name of a profile without a password")},
    {Text::SignInNoPasswordShort, QT_TRANSLATE_NOOP3("Air", "No password", "sign-in: in the list of profiles, under a profile without a password")},
    {Text::SignInAutoLoginPlainNote, QT_TRANSLATE_NOOP3("Air", "The profile opens at start without asking.", "sign-in: what Log in automatically means for a profile without a password")},
    {Text::SignInProfiles, QT_TRANSLATE_NOOP3("Air", "Profiles on this device", "sign-in: title of the list of profiles")},
    {Text::SignInProfilesNote, QT_TRANSLATE_NOOP3("Air", "Right-click a profile to delete it.", "sign-in: under the list of profiles")},
    {Text::SignInForgot, QT_TRANSLATE_NOOP3("Air", "Forgot the password?", "sign-in: quiet button under the password field")},
    {Text::SignInForgotTitle, QT_TRANSLATE_NOOP3("Air", "The password cannot be recovered", "sign-in: title of what Forgot the password says")},
    {Text::SignInForgotMessage, QT_TRANSLATE_NOOP3("Air", "There are no servers, and only you know the password. You can create a new profile with a new ID: this one stays on this device, in its file %1, and opens if you remember the password. Or delete this profile from this device.", "sign-in: what Forgot the password says; %1 is the name of the profile file")},
    {Text::SignInCreate, QT_TRANSLATE_NOOP3("Air", "Create a new profile", "sign-in: quiet button; also a choice of Forgot the password")},
    {Text::SignInDelete, QT_TRANSLATE_NOOP3("Air", "Delete this profile", "sign-in: a choice of Forgot the password")},
    {Text::SignInWrongPassword, QT_TRANSLATE_NOOP3("Air", "Wrong password. Check the keyboard language and Caps Lock.", "sign-in: error under the password field")},
    {Text::SignInDamaged, QT_TRANSLATE_NOOP3("Air", "The profile file is damaged. If you have an exported copy, move the profile in from it.", "sign-in: error under the password field")},
    {Text::SignInNoAccess, QT_TRANSLATE_NOOP3("Air", "No access to the profile file. Check that it is still in its folder and that you may read it, then try again.", "sign-in: error under the password field")},
    {Text::SignInInUse, QT_TRANSLATE_NOOP3("Air", "This profile is already open in Khandaq on this computer. Close it there, then log in here.", "sign-in: error under the password field")},
    {Text::SignInBadProxy, QT_TRANSLATE_NOOP3("Air", "The proxy in this profile's network settings did not work, so the profile did not open. Check that the proxy is running, or log in without it.", "sign-in: error under the password field")},
    {Text::SignInWithoutProxy, QT_TRANSLATE_NOOP3("Air", "Log in without the proxy", "sign-in: button under the proxy error; also the verb of its question")},
    {Text::SignInWithoutProxyTitle, QT_TRANSLATE_NOOP3("Air", "Log in without the proxy?", "sign-in: question before the proxy is turned off")},
    {Text::SignInWithoutProxyMessage, QT_TRANSLATE_NOOP3("Air", "The proxy is turned off in the network settings of this profile: contacts and relays will see this computer's address.", "sign-in: consequence of logging in without the proxy")},
    {Text::SignInFailed, QT_TRANSLATE_NOOP3("Air", "Khandaq could not start this profile. Try again; if it fails again, write to support and attach the details.", "sign-in: error under the password field when the reason has only a code")},
    {Text::SignInFailedTitle, QT_TRANSLATE_NOOP3("Air", "The profile did not open", "sign-in: title of the details of an error")},
    {Text::ImportTitle, QT_TRANSLATE_NOOP3("Air", "Move a profile", "moving a profile in: title; also the title of the file dialog")},
    {Text::ImportFilter, QT_TRANSLATE_NOOP3("Air", "Khandaq profile (*.tox)", "moving a profile in: the kind of file the file dialog shows")},
    {Text::ImportPasswordBody, QT_TRANSLATE_NOOP3("Air", "The file \"%1\" is protected by a password. Enter the password the profile was saved with.", "moving a profile in: %1 is the name of the file")},
    {Text::ImportReading, QT_TRANSLATE_NOOP3("Air", "Reading the file", "moving a profile in: the main button while the file is read")},
    {Text::ImportWrongPassword, QT_TRANSLATE_NOOP3("Air", "This password does not open the file. It takes the profile's password on the device the file came from.", "moving a profile in: error under the password field")},
    {Text::ImportPreviewTitle, QT_TRANSLATE_NOOP3("Air", "Is this your profile?", "moving a profile in: title of the page with the name and the ID")},
    {Text::ImportPreviewBody, QT_TRANSLATE_NOOP3("Air", "Check the name and the ID: the profile will be added to this device.", "moving a profile in: under the title")},
    {Text::ImportName, QT_TRANSLATE_NOOP3("Air", "Name", "moving a profile in: the name of the profile in the file")},
    {Text::ImportNoName, QT_TRANSLATE_NOOP3("Air", "No name", "moving a profile in: the profile in the file has no name")},
    {Text::ImportConfirm, QT_TRANSLATE_NOOP3("Air", "Move the profile here", "moving a profile in: the main button")},
    {Text::ImportMoving, QT_TRANSLATE_NOOP3("Air", "Moving the profile", "moving a profile in: the main button while the profile is copied and opened")},
    {Text::ImportOtherFile, QT_TRANSLATE_NOOP3("Air", "Choose another file", "moving a profile in: button")},
    {Text::ImportNotProfile, QT_TRANSLATE_NOOP3("Air", "This is not a Khandaq profile file. Choose a .tox file exported from a profile.", "moving a profile in: what is wrong with the file")},
    {Text::ImportDamaged, QT_TRANSLATE_NOOP3("Air", "The file is damaged, and the profile in it cannot be read. Choose another copy of it.", "moving a profile in: what is wrong with the file")},
    {Text::ImportUnreadable, QT_TRANSLATE_NOOP3("Air", "The file could not be read. Check that it is still there and that you may read it, then choose it again.", "moving a profile in: what is wrong with the file")},
    {Text::ImportCopyFailed, QT_TRANSLATE_NOOP3("Air", "The file couldn't be copied into the folder of profiles. Try again.", "moving a profile in: why the profile was not moved")},
    {Text::ImportFailedTitle, QT_TRANSLATE_NOOP3("Air", "The profile was not moved", "moving a profile in: title of the error")},
    {Text::SettingsSections, QT_TRANSLATE_NOOP3("Air", "Sections of the settings", "Settings: screen reader name of the column of sections")},
    {Text::SettingsAppearance, QT_TRANSLATE_NOOP3("Air", "Appearance", "Settings: section")},
    {Text::SettingsNotifications, QT_TRANSLATE_NOOP3("Air", "Notifications", "Settings: section")},
    {Text::SettingsPrivacy, QT_TRANSLATE_NOOP3("Air", "Privacy", "Settings: section")},
    {Text::SettingsCalls, QT_TRANSLATE_NOOP3("Air", "Calls", "Settings: section of the call devices")},
    {Text::SettingsTransfer, QT_TRANSLATE_NOOP3("Air", "Profile transfer", "Settings: section of exporting and importing the profile")},
    {Text::SettingsNetwork, QT_TRANSLATE_NOOP3("Air", "Network", "Settings: section")},
    {Text::SettingsWindow, QT_TRANSLATE_NOOP3("Air", "Startup and window", "Settings: section of starting the app, its icon and its windows")},
    {Text::SettingsAbout, QT_TRANSLATE_NOOP3("Air", "About", "Settings: section of the versions and the license")},
    {Text::SettingsTheme, QT_TRANSLATE_NOOP3("Air", "Theme", "Settings, Appearance: row of Match system, Light and Dark")},
    {Text::SettingsLanguage, QT_TRANSLATE_NOOP3("Air", "Language", "Settings, Appearance: language of the interface")},
    {Text::SettingsList, QT_TRANSLATE_NOOP3("Air", "Chat list", "Settings, Appearance: how close the rows of the chat list stand")},
    {Text::SettingsListRegular, QT_TRANSLATE_NOOP3("Air", "Regular", "Settings, Appearance: chat list choice")},
    {Text::SettingsListCompact, QT_TRANSLATE_NOOP3("Air", "Compact", "Settings, Appearance: chat list choice, the rows closer together")},
    {Text::SettingsConversations, QT_TRANSLATE_NOOP3("Air", "Conversations", "Settings, Appearance: group of how messages look")},
    {Text::SettingsFont, QT_TRANSLATE_NOOP3("Air", "Message font", "Settings, Appearance: font of the messages; also the title of the font dialog")},
    {Text::SettingsFontApp, QT_TRANSLATE_NOOP3("Air", "As in the app", "Settings, Appearance: message font choice, the font of the interface")},
    {Text::SettingsFontValue, QT_TRANSLATE_NOOP3("Air", "%1, %2 px", "Settings, Appearance: %1 is the name of the font, %2 its size in pixels")},
    {Text::SettingsFontChoose, QT_TRANSLATE_NOOP3("Air", "Choose a font", "Settings, Appearance: opens the font dialog")},
    {Text::SettingsFormatting, QT_TRANSLATE_NOOP3("Air", "Text formatting", "Settings, Appearance: bold, italic and code written with marks such as *bold*")},
    {Text::SettingsFormattingPlain, QT_TRANSLATE_NOOP3("Air", "Off", "Settings, Appearance: text formatting choice, the text shows as it was written")},
    {Text::SettingsFormattingMarks, QT_TRANSLATE_NOOP3("Air", "Keep the marks", "Settings, Appearance: text formatting choice, formatted with its marks shown")},
    {Text::SettingsFormattingHidden, QT_TRANSLATE_NOOP3("Air", "Hide the marks", "Settings, Appearance: text formatting choice, formatted without its marks")},
    {Text::SettingsSmileys, QT_TRANSLATE_NOOP3("Air", "Smileys as pictures", "Settings, Appearance: switch")},
    {Text::SettingsSmileyPack, QT_TRANSLATE_NOOP3("Air", "Smiley set", "Settings, Appearance: which pictures the smileys use")},
    {Text::SettingsSmileySize, QT_TRANSLATE_NOOP3("Air", "Smiley size", "Settings, Appearance: number field in pixels")},
    {Text::SettingsPixels, QT_TRANSLATE_NOOP3("Air", "%1 px", "Settings: a size in pixels in a number field; %1 is the number")},
    {Text::SettingsNameColors, QT_TRANSLATE_NOOP3("Air", "Colored names in groups", "Settings, Appearance: each member's name in a colour of its own")},
    {Text::SettingsGroupEvents, QT_TRANSLATE_NOOP3("Air", "Group system messages", "Settings, Appearance: switch")},
    {Text::SettingsGroupEventsDetail, QT_TRANSLATE_NOOP3("Air", "Who joined, left or changed their name", "Settings, Appearance: under Group system messages")},
    {Text::SettingsStatusChanges, QT_TRANSLATE_NOOP3("Air", "Status changes in chats", "Settings, Appearance: a line in the chat when the contact goes online, away or busy")},
    {Text::SettingsSpelling, QT_TRANSLATE_NOOP3("Air", "Check spelling", "Settings, Appearance: in the message field")},
    {Text::SettingsNotify, QT_TRANSLATE_NOOP3("Air", "Notify about new messages", "Settings, Notifications: the switch the other rows depend on")},
    {Text::SettingsPreview, QT_TRANSLATE_NOOP3("Air", "Show message text", "Settings, Notifications: switch")},
    {Text::SettingsPreviewNote, QT_TRANSLATE_NOOP3("Air", "Names, message text and file names show in notifications. Turned off, a notification only says that something new came.", "Settings, Notifications: under Show message text")},
    {Text::SettingsSystemNotices, QT_TRANSLATE_NOOP3("Air", "System notifications", "Settings, Notifications: the banners of the system, outside the window")},
    {Text::SettingsSystemNoticesTray, QT_TRANSLATE_NOOP3("Air", "They come through the Khandaq icon: turn it on in Startup and window.", "Settings, Notifications: why System notifications is off; Startup and window is the section")},
    {Text::SettingsSystemNoticesNoTray, QT_TRANSLATE_NOOP3("Air", "This desktop shows no tray icons, and system notifications come through the Khandaq icon.", "Settings, Notifications: why System notifications is off on a desktop without a tray")},
    {Text::SettingsOpenWindow, QT_TRANSLATE_NOOP3("Air", "Open the window on a new message", "Settings, Notifications: switch")},
    {Text::SettingsSound, QT_TRANSLATE_NOOP3("Air", "Sound", "Settings, Notifications: switch")},
    {Text::SettingsBusySound, QT_TRANSLATE_NOOP3("Air", "Sound while Busy", "Settings, Notifications: switch; Busy is the status")},
    {Text::SettingsGroupsTitle, QT_TRANSLATE_NOOP3("Air", "Groups", "Settings, Notifications: group of the rows about group chats")},
    {Text::SettingsMentionsOnly, QT_TRANSLATE_NOOP3("Air", "Only when someone mentions you", "Settings, Notifications: when a group message notifies")},
    {Text::SettingsMentionsOnlyDetail, QT_TRANSLATE_NOOP3("Air", "Other group messages are counted without a sound or a notification.", "Settings, Notifications: under Only when someone mentions you")},
    {Text::SettingsTyping, QT_TRANSLATE_NOOP3("Air", "Show that I'm typing", "Settings, Privacy: switch")},
    {Text::SettingsHistory, QT_TRANSLATE_NOOP3("Air", "Keep chat history", "Settings, Privacy: switch")},
    {Text::SettingsHistoryDetail, QT_TRANSLATE_NOOP3("Air", "Turned off, new messages are not kept on this device. Messages waiting to be sent are kept either way.", "Settings, Privacy: under Keep chat history")},
    {Text::SettingsHistoryEraseTitle, QT_TRANSLATE_NOOP3("Air", "Erase the history kept so far?", "Settings, Privacy: question once Keep chat history is turned off")},
    {Text::SettingsHistoryEraseMessage, QT_TRANSLATE_NOOP3("Air", "New messages are no longer kept. Erasing removes the chats kept so far from this device; messages waiting to be sent and Saved Messages stay.", "Settings, Privacy: the question once Keep chat history is turned off")},
    {Text::SettingsHistoryEraseAction, QT_TRANSLATE_NOOP3("Air", "Erase", "Settings, Privacy: the verb of the history question")},
    {Text::SettingsHistoryErased, QT_TRANSLATE_NOOP3("Air", "History erased", "Settings, Privacy: toast")},
    {Text::HiddenTitle, QT_TRANSLATE_NOOP3("Air", "Hidden in groups", "Settings, Privacy: the members whose messages this computer hides in every group")},
    {Text::HiddenSectionNote, QT_TRANSLATE_NOOP3("Air", "Their messages are not shown in any group. The list is kept on this device only.", "Settings, Privacy: under Hidden in groups")},
    {Text::HiddenEmpty, QT_TRANSLATE_NOOP3("Air", "Members whose messages you hide from a group's dossier appear here.", "Settings, Privacy: nobody is hidden")},
    {Text::HiddenShown, QT_TRANSLATE_NOOP3("Air", "Messages shown again", "Settings, Privacy: toast after Show their messages")},
    {Text::SettingsImport, QT_TRANSLATE_NOOP3("Air", "Import", "Settings, Profile transfer: opens a profile from a file; also the title of the file dialog")},
    {Text::SettingsImportTitle, QT_TRANSLATE_NOOP3("Air", "Import a profile?", "Settings, Profile transfer: question before the file is chosen")},
    {Text::SettingsImportMessage, QT_TRANSLATE_NOOP3("Air", "The app logs out of this profile and opens the one from the file.", "Settings, Profile transfer: first sentence of the question; what logging out means follows it")},
    {Text::SettingsImportAction, QT_TRANSLATE_NOOP3("Air", "Choose a file", "Settings, Profile transfer: the verb of the import question")},
    {Text::SettingsImportMove, QT_TRANSLATE_NOOP3("Air", "Move", "The verb of the import question for a profile file opened with the app, as on the phones")},
    {Text::SettingsImportFailedTitle, QT_TRANSLATE_NOOP3("Air", "The profile wasn't imported", "Settings, Profile transfer: title of the error")},
    {Text::SettingsPortable, QT_TRANSLATE_NOOP3("Air", "Keep the profile next to the app", "Settings, Profile transfer: portable mode, for running from a removable drive")},
    {Text::SettingsPortableDetail, QT_TRANSLATE_NOOP3("Air", "For running Khandaq from a removable drive. Chat history and contact photos stay in the folder they are in now.", "Settings, Profile transfer: under Keep the profile next to the app")},
    {Text::SettingsUdp, QT_TRANSLATE_NOOP3("Air", "Use UDP", "Settings, Network: switch")},
    {Text::SettingsUdpProxy, QT_TRANSLATE_NOOP3("Air", "Off while a proxy is set: a proxy carries TCP only.", "Settings, Network: under Use UDP")},
    {Text::SettingsIpv6, QT_TRANSLATE_NOOP3("Air", "Use IPv6", "Settings, Network: switch")},
    {Text::SettingsLan, QT_TRANSLATE_NOOP3("Air", "Find contacts on the local network", "Settings, Network: LAN discovery")},
    {Text::SettingsProxy, QT_TRANSLATE_NOOP3("Air", "Proxy", "Settings, Network: row of None, SOCKS5 and HTTP")},
    {Text::SettingsProxyNone, QT_TRANSLATE_NOOP3("Air", "None", "Settings, Network: no proxy")},
    {Text::SettingsProxyAddress, QT_TRANSLATE_NOOP3("Air", "Proxy address", "Settings, Network: field")},
    {Text::SettingsProxyPort, QT_TRANSLATE_NOOP3("Air", "Proxy port", "Settings, Network: number field")},
    {Text::SettingsNetworkRestart, QT_TRANSLATE_NOOP3("Air", "Network settings take effect the next time you log in.", "Settings, Network: under the proxy")},
    {Text::SettingsAttachments, QT_TRANSLATE_NOOP3("Air", "Download attachments", "Settings, Network: row of Always, Small files and Never")},
    {Text::SettingsAttachmentsAlways, QT_TRANSLATE_NOOP3("Air", "Always", "Settings, Network: download attachments choice")},
    {Text::SettingsAttachmentsSmall, QT_TRANSLATE_NOOP3("Air", "Small files", "Settings, Network: download attachments choice, files up to the size limit")},
    {Text::SettingsAttachmentsNever, QT_TRANSLATE_NOOP3("Air", "Never", "Settings, Network: download attachments choice")},
    {Text::SettingsAttachmentsLimit, QT_TRANSLATE_NOOP3("Air", "Size limit", "Settings, Network: number field in megabytes; bigger files wait for Accept")},
    {Text::SettingsSaveTo, QT_TRANSLATE_NOOP3("Air", "Save files to", "Settings, Network: the folder files go to; also the title of the folder dialog")},
    {Text::SettingsDownloadsFolder, QT_TRANSLATE_NOOP3("Air", "Downloads", "Settings, Network: under Save files to, the downloads folder of the system, by its usual name in this language")},
    {Text::SettingsChange, QT_TRANSLATE_NOOP3("Air", "Change", "Settings, Network: chooses another folder")},
    {Text::SettingsAttachmentsNote, QT_TRANSLATE_NOOP3("Air", "Files that do not download by themselves wait for Accept in the chat.", "Settings, Network: under Download attachments; Accept is the button of a file")},
    {Text::SettingsAttachmentsGroups, QT_TRANSLATE_NOOP3("Air", "Files sent in a group are saved as they come, each group in its own folder in Khandaq Groups.", "Settings, Network: under Download attachments; Khandaq Groups is the name of a folder, keep it")},
    {Text::SettingsLog, QT_TRANSLATE_NOOP3("Air", "Technical log", "Settings, Network: group of the log support asks for")},
    {Text::SettingsLogSave, QT_TRANSLATE_NOOP3("Air", "Save the log", "Settings, Network: button; also the title of the save dialog")},
    {Text::SettingsLogCopy, QT_TRANSLATE_NOOP3("Air", "Copy the log", "Settings, Network: button")},
    {Text::SettingsLogSaved, QT_TRANSLATE_NOOP3("Air", "Log saved", "Settings, Network: toast")},
    {Text::SettingsLogCopied, QT_TRANSLATE_NOOP3("Air", "Log copied", "Settings, Network: toast")},
    {Text::SettingsLogFailedTitle, QT_TRANSLATE_NOOP3("Air", "The log wasn't saved", "Settings, Network: title of the error")},
    {Text::SettingsLogFilter, QT_TRANSLATE_NOOP3("Air", "Log (*.log)", "Settings, Network: file type of a saved log; keep (*.log)")},
    {Text::SettingsLogNote, QT_TRANSLATE_NOOP3("Air", "Technical details of the app and its connection, if support asks for them.", "Settings, Network: under the buttons of the log")},
    {Text::NetworkTitleDirect, QT_TRANSLATE_NOOP3("Air", "Online, direct", "Settings, Network: the state of our node, connected over UDP")},
    {Text::NetworkTitleRelay, QT_TRANSLATE_NOOP3("Air", "Online, via a relay", "Settings, Network: the state of our node, connected through a TCP relay")},
    {Text::NetworkTitleConnecting, QT_TRANSLATE_NOOP3("Air", "Connecting", "Settings, Network: the state of our node, looking for the network")},
    {Text::NetworkTitleOffline, QT_TRANSLATE_NOOP3("Air", "No network", "Settings, Network: the state of our node, the computer has no network")},
    {Text::NetworkMeaningDirect, QT_TRANSLATE_NOOP3("Air", "Messages go at once, straight to the other device.", "Settings, Network: what the direct state means")},
    {Text::NetworkMeaningRelay, QT_TRANSLATE_NOOP3("Air", "Messages go at once through a relay: this network closes the direct path, and delivery does not suffer.", "Settings, Network: what the relay state means")},
    {Text::NetworkMeaningConnecting, QT_TRANSLATE_NOOP3("Air", "This computer has internet and the app is looking for the network. New messages wait in transit and go on their own.", "Settings, Network: what the connecting state means")},
    {Text::NetworkMeaningOffline, QT_TRANSLATE_NOOP3("Air", "This computer has no internet. New messages wait in transit and go once it is back.", "Settings, Network: what having no network means")},
    {Text::NetworkInternet, QT_TRANSLATE_NOOP3("Air", "Internet", "Settings, Network: row, whether the computer has internet")},
    {Text::NetworkInternetYes, QT_TRANSLATE_NOOP3("Air", "Available", "Settings, Network: value of the Internet row")},
    {Text::NetworkInternetNo, QT_TRANSLATE_NOOP3("Air", "None", "Settings, Network: value of the Internet row")},
    {Text::NetworkLink, QT_TRANSLATE_NOOP3("Air", "Link", "Settings, Network: row, how our node is linked")},
    {Text::NetworkLinkDirect, QT_TRANSLATE_NOOP3("Air", "Direct", "Settings, Network: value of the Link row")},
    {Text::NetworkLinkRelay, QT_TRANSLATE_NOOP3("Air", "Via a relay", "Settings, Network: value of the Link row")},
    {Text::NetworkLinkNone, QT_TRANSLATE_NOOP3("Air", "Not yet", "Settings, Network: value of the Link row while not linked")},
    {Text::NetworkNodes, QT_TRANSLATE_NOOP3("Air", "Nodes", "Settings, Network: row, how many nodes our node is linked with; the value is a number")},
    {Text::NetworkNodesNote, QT_TRANSLATE_NOOP3("Air", "Relays this computer is connected to and contacts it reaches directly.", "Settings, Network: what the Nodes row counts")},
    {Text::NetworkReconnect, QT_TRANSLATE_NOOP3("Air", "Connect again", "Settings, Network: button, only while the app connects with internet at hand")},
    {Text::NetworkReconnectToast, QT_TRANSLATE_NOOP3("Air", "Connecting again", "Settings, Network: toast after Connect again")},
    {Text::SettingsRestore, QT_TRANSLATE_NOOP3("Air", "Restore default network settings", "Settings, Network: button in the danger style")},
    {Text::SettingsRestoreTitle, QT_TRANSLATE_NOOP3("Air", "Restore default network settings?", "Settings, Network: question")},
    {Text::SettingsRestoreMessage, QT_TRANSLATE_NOOP3("Air", "UDP, IPv6, the local network and the proxy go back to the values of a new profile. They take effect the next time you log in.", "Settings, Network: consequence of restoring")},
    {Text::SettingsRestoreAction, QT_TRANSLATE_NOOP3("Air", "Restore", "Settings, Network: the verb of the restore question")},
    {Text::SettingsRestored, QT_TRANSLATE_NOOP3("Air", "Network settings restored", "Settings, Network: toast")},
    {Text::SettingsStartup, QT_TRANSLATE_NOOP3("Air", "Startup", "Settings, Startup and window: group")},
    {Text::SettingsAutorun, QT_TRANSLATE_NOOP3("Air", "Open Khandaq when you log in to the computer", "Settings, Startup and window: switch")},
    {Text::SettingsTray, QT_TRANSLATE_NOOP3("Air", "Khandaq icon", "Settings, Startup and window: group of the icon in the menu bar or the tray")},
#ifdef Q_OS_MACOS
    {Text::SettingsTrayIcon, QT_TRANSLATE_NOOP3("Air", "Show in the menu bar", "Settings, Startup and window: the Khandaq icon in the macOS menu bar")},
#else
    {Text::SettingsTrayIcon, QT_TRANSLATE_NOOP3("Air", "Show in the tray", "Settings, Startup and window: the Khandaq icon in the system tray")},
#endif
    {Text::SettingsStartInTray, QT_TRANSLATE_NOOP3("Air", "Start without the window", "Settings, Startup and window: only the icon shows at start")},
    {Text::SettingsMinimizeToTray, QT_TRANSLATE_NOOP3("Air", "Hide the window on minimize", "Settings, Startup and window: minimizing leaves only the icon")},
    {Text::SettingsCloseToTray, QT_TRANSLATE_NOOP3("Air", "Keep running when the window is closed", "Settings, Startup and window: closing leaves only the icon")},
    {Text::SettingsLightIcon, QT_TRANSLATE_NOOP3("Air", "Light icon", "Settings, Startup and window: for a dark menu bar or tray")},
    {Text::SettingsWindows, QT_TRANSLATE_NOOP3("Air", "Windows", "Settings, Startup and window: group")},
    {Text::SettingsSeparate, QT_TRANSLATE_NOOP3("Air", "Chats in a separate window", "Settings, Startup and window: the chat opens apart from the list")},
    {Text::SettingsWindowEach, QT_TRANSLATE_NOOP3("Air", "A window for each chat", "Settings, Startup and window: switch under Chats in a separate window")},
    {Text::SettingsStatusTitle, QT_TRANSLATE_NOOP3("Air", "Status", "Settings, Startup and window: group")},
    {Text::SettingsAway, QT_TRANSLATE_NOOP3("Air", "Away when idle for", "Settings, Startup and window: number field in minutes; Away is the status")},
    {Text::SettingsMinutes, QT_TRANSLATE_NOOP3("Air", "%1 min", "Settings: a number of minutes in a number field; %1 is the number")},
    {Text::SettingsAwayNever, QT_TRANSLATE_NOOP3("Air", "Never", "Settings, Startup and window: Away when idle for, at 0")},
    {Text::SettingsVersion, QT_TRANSLATE_NOOP3("Air", "Version", "Settings, About")},
    {Text::SettingsCommit, QT_TRANSLATE_NOOP3("Air", "Build", "Settings, About: the commit the app was built from")},
    {Text::SettingsToxcore, QT_TRANSLATE_NOOP3("Air", "Toxcore version", "Settings, About")},
    {Text::SettingsQt, QT_TRANSLATE_NOOP3("Air", "Qt version", "Settings, About")},
    {Text::SettingsCheckUpdates, QT_TRANSLATE_NOOP3("Air", "Check for updates", "Settings, About: switch")},
    {Text::SettingsUpdateDownload, QT_TRANSLATE_NOOP3("Air", "Download the update", "Settings, About: button once a newer version is out")},
    {Text::SettingsOwner, QT_TRANSLATE_NOOP3("Air", "Owner and developer: Isa Dagestani", "Settings, About: credit")},
    {Text::SettingsBasedOn, QT_TRANSLATE_NOOP3("Air", "Khandaq is based on qTox, made by the qTox contributors.", "Settings, About")},
    {Text::SettingsWebsite, QT_TRANSLATE_NOOP3("Air", "Website", "Settings, About: link to khandaq.org")},
    {Text::SettingsContributors, QT_TRANSLATE_NOOP3("Air", "qTox contributors", "Settings, About: link to the list of qTox contributors")},
    {Text::SettingsReport, QT_TRANSLATE_NOOP3("Air", "Report a problem", "Settings, About: opens the website with a report that names the versions")},
    {Text::SettingsLicense, QT_TRANSLATE_NOOP3("Air", "License", "Settings, About: group of the license notice")},
    {Text::NotifyNewMessage, QT_TRANSLATE_NOOP3("Air", "New message", "notification and band: what came, when message text is not shown")},
    {Text::NotifyNewGroupMessage, QT_TRANSLATE_NOOP3("Air", "New group message", "notification: what came in a group, when message text is not shown")},
    {Text::NotifyIncomingFile, QT_TRANSLATE_NOOP3("Air", "Incoming file", "notification and band: a file came, when file names are not shown")},
    {Text::NotifyNewMessages, QT_TRANSLATE_N_NOOP3("Air", "%n new message(s)", "title of a notification for messages from several chats; their names follow"), true},
    {Text::NotifyMessagesFrom, QT_TRANSLATE_N_NOOP3("Air", "%n message(s) from %1", "title of a notification for several messages from one chat; %1 is the chat"), true},
    {Text::NotifyInvite, QT_TRANSLATE_NOOP3("Air", "Invites you to a group", "notification and band under the name of who invites, when the group has no name")},
    {Text::NotifyInviteTo, QT_TRANSLATE_NOOP3("Air", "Invites you to the group %1", "notification and band under the name of who invites; %1 is the group")},
    {Text::BandOpensChat, QT_TRANSLATE_NOOP3("Air", "Opens the chat", "screen reader: what a click on the band of a message does")},
    {Text::BandOpensRequest, QT_TRANSLATE_NOOP3("Air", "Opens the request", "screen reader: what a click on the band of a request does")},
    {Text::BandOpensInvite, QT_TRANSLATE_NOOP3("Air", "Opens the invitation", "screen reader: what a click on the band of an invitation does")},
    {Text::ErrorAddContactTitle, QT_TRANSLATE_NOOP3("Air", "Couldn't add the contact", "title of the error when Tox refused a request or its acceptance")},
    {Text::ErrorJoinGroupTitle, QT_TRANSLATE_NOOP3("Air", "Couldn't join the group", "title of the error when Tox refused to join a group")},
    {Text::ErrorRoleTitle, QT_TRANSLATE_NOOP3("Air", "Couldn't change the role", "title of the error when the group refused a change of role")},
    {Text::ErrorKickTitle, QT_TRANSLATE_NOOP3("Air", "Couldn't remove the member", "title of the error when the group refused to remove a member")},
    {Text::ErrorInviteTitle, QT_TRANSLATE_NOOP3("Air", "Couldn't invite to the group", "title of the error when no invitation went")},
    {Text::ErrorInviteSomeTitle, QT_TRANSLATE_NOOP3("Air", "Not everyone was invited", "title of the error when some invitations went and some did not")},
    {Text::InviteRetry, QT_TRANSLATE_NOOP3("Air", "Try again once the group is connected and they are online.", "what to do when invitations did not go")},
    {Text::AutoLoginFailed, QT_TRANSLATE_NOOP3("Air", "It didn't open by itself at start. Log in to it here.", "under the password field of sign-in: the profile set to open at start did not")},
    {Text::ScreenshotNotSaved, QT_TRANSLATE_NOOP3("Air", "The screenshot couldn't be saved for sending. Try again.", "why a screenshot was not sent")},
    {Text::LinkLocalTitle, QT_TRANSLATE_NOOP3("Air", "Open a local or network file?", "question before a link in a message opens a file:// or smb:// address")},
    {Text::LinkLocalMessage, QT_TRANSLATE_NOOP3("Air", "This link opens a file on your computer or network:\n\n%1\n\nOpening it can run a program on your computer. Links in messages come from other people.", "question before a file:// or smb:// link opens; %1 is the whole address")},
    {Text::LinkOpen, QT_TRANSLATE_NOOP3("Air", "Open", "the verb of the question before a link to a file or a program opens")},
    {Text::ProgramTitle, QT_TRANSLATE_NOOP3("Air", "Open a program?", "question before a received program opens")},
    {Text::ProgramMessage, QT_TRANSLATE_NOOP3("Air", "%1 is a program. Opening it runs it on this computer, and a program from someone else can harm it.", "question before a received program opens; %1 is the file name")},
    {Text::ClearScreenTitle, QT_TRANSLATE_NOOP3("Air", "Clear the messages on screen?", "question of Clear displayed messages in a group or Saved Messages")},
    {Text::ClearScreenMessage, QT_TRANSLATE_NOOP3("Air", "Only this window is cleared: the history on this device stays as it is.", "consequence of Clear displayed messages")},
    {Text::ClearScreenAction, QT_TRANSLATE_NOOP3("Air", "Clear", "the verb of the question of Clear displayed messages")},
    {Text::MessageUnknownFormat, QT_TRANSLATE_NOOP3("Air", "This message is in a form this app cannot show yet", "in place of a message whose markup this version cannot read, in the transcript, the list and notifications; as air_message_unknown_format on the phones")},
    {Text::MessageEdited, QT_TRANSLATE_NOOP3("Air", "edited", "under a message its author changed after sending it; as message_edited_marker on the phones")},
    {Text::ReplyUnknownSender, QT_TRANSLATE_NOOP3("Air", "Unknown", "in the quote of a reply, in place of the name when the reply does not say who wrote the quoted message; as chat_reply_unknown_sender on the phones")},
    {Text::SpokenReply, QT_TRANSLATE_NOOP3("Air", "In reply to %1", "what a screen reader says before the quote of a reply: the name of who wrote the quoted message; as chat_reply_header on the phones")},
};
// clang-format on

const Source& find(Text id)
{
    for (const Source& entry : sources) {
        if (entry.id == id) {
            return entry;
        }
    }
    return sources[0];
}
} // namespace

QString text(Text id)
{
    const Message& message = find(id).message;
    return QCoreApplication::translate(context, message.source, message.comment);
}

QString text(Text id, int count)
{
    const Message& message = find(id).message;
    return QCoreApplication::translate(context, message.source, message.comment, count);
}

bool isNumerus(Text id)
{
    return find(id).numerus;
}

const char* source(Text id)
{
    return find(id).message.source;
}

QVector<Text> all()
{
    QVector<Text> ids;
    for (const Source& entry : sources) {
        ids.append(entry.id);
    }
    return ids;
}

QString latinDigits(QString text, const QLocale& locale)
{
    const ushort zero = locale.zeroDigit().unicode();
    if (zero == '0') {
        return text;
    }
    for (QChar& character : text) {
        const ushort code = character.unicode();
        if (code >= zero && code <= zero + 9) {
            character = QChar(static_cast<ushort>('0' + (code - zero)));
        }
    }
    return text;
}

QString byteSizeText(qint64 bytes, const QLocale& locale)
{
    struct Unit
    {
        double bytes;
        int digits;
        Text format;
    };
    static const Unit units[] = {
        {1.0, 0, Text::SizeBytes},
        {1e3, 0, Text::SizeKilobytes},
        {1e6, 1, Text::SizeMegabytes},
        {1e9, 2, Text::SizeGigabytes},
    };
    const int count = static_cast<int>(sizeof(units) / sizeof(units[0]));
    const auto rounded = [](double value, int digits) {
        const double scale = std::pow(10.0, digits);
        return std::round(value * scale) / scale;
    };

    const double value = static_cast<double>(std::max<qint64>(0, bytes));
    int index = 0;
    while (index + 1 < count && value >= units[index + 1].bytes) {
        ++index;
    }
    double amount = rounded(value / units[index].bytes, units[index].digits);
    if (amount >= 1000.0 && index + 1 < count) {
        ++index;
        amount = rounded(value / units[index].bytes, units[index].digits);
    }

    // A language written with digits of its own keeps the Latin digits with their own marks, as
    // the phones do ("12.6"), rather than mixing Latin digits with its separators.
    const QLocale numbers = locale.zeroDigit() == QLatin1Char('0')
                                ? locale
                                : QLocale(QLocale::English, QLocale::UnitedStates);
    const QString number = numbers.toString(amount, 'f', QLocale::FloatingPointShortest);
    return text(units[index].format).arg(number);
}

QString speedText(qint64 bytesPerSecond, const QLocale& locale)
{
    return text(Text::TransferSpeed).arg(byteSizeText(bytesPerSecond, locale));
}

QString reachText(const Air::Reach& reach)
{
    using Kind = Air::Reach::Kind;
    switch (reach.kind) {
    case Kind::Direct:
        return text(Text::ReachLive);
    case Kind::Relay:
        return text(Text::ReachRelay);
    case Kind::Pending:
        return text(Text::ReachPending);
    case Kind::Connecting:
        return text(Text::ReachConnecting);
    case Kind::Group:
        return reach.online > 0 ? text(Text::ReachGroupOnline).arg(reach.online)
                                : text(Text::ReachGroupNone);
    case Kind::Local:
        return QString{};
    case Kind::Away:
        break;
    }

    const Air::AwayWords words = Air::AwayWords::of(reach.secondsAgo);
    switch (words.unit) {
    case Air::AwayWords::Unit::JustNow:
        return text(Text::ReachJustNow);
    case Air::AwayWords::Unit::Minutes:
        return text(Text::ReachMinutes).arg(words.amount);
    case Air::AwayWords::Unit::Hours:
        return text(Text::ReachHours).arg(words.amount);
    case Air::AwayWords::Unit::Days:
        return text(Text::ReachDays).arg(words.amount);
    case Air::AwayWords::Unit::LongAgo:
        break;
    }
    return text(Text::ReachLongAgo);
}

QString openFailureText(Air::OpenFailure::Kind kind)
{
    switch (kind) {
    case Air::OpenFailure::Kind::None:
        return QString();
    case Air::OpenFailure::Kind::WrongPassword:
        return text(Text::SignInWrongPassword);
    case Air::OpenFailure::Kind::Damaged:
        return text(Text::SignInDamaged);
    case Air::OpenFailure::Kind::NoAccess:
        return text(Text::SignInNoAccess);
    case Air::OpenFailure::Kind::InUse:
        return text(Text::SignInInUse);
    case Air::OpenFailure::Kind::BadProxy:
        return text(Text::SignInBadProxy);
    case Air::OpenFailure::Kind::Other:
        break;
    }
    return text(Text::SignInFailed);
}

QString importProblemText(ProfileFile::Contents::Problem problem)
{
    using Problem = ProfileFile::Contents::Problem;
    switch (problem) {
    case Problem::None:
    case Problem::NeedsPassword:
        return QString();
    case Problem::WrongPassword:
        return text(Text::ImportWrongPassword);
    case Problem::NotProfile:
        return text(Text::ImportNotProfile);
    case Problem::Damaged:
        return text(Text::ImportDamaged);
    case Problem::Unreadable:
        break;
    }
    return text(Text::ImportUnreadable);
}

QString networkText(Air::NetworkState state)
{
    switch (state) {
    case Air::NetworkState::Direct:
        return text(Text::NetDirect);
    case Air::NetworkState::Relay:
        return text(Text::NetRelay);
    case Air::NetworkState::Connecting:
        return text(Text::NetConnecting);
    case Air::NetworkState::Offline:
        break;
    }
    return text(Text::NetOffline);
}

QString networkTitle(Air::NetworkState state)
{
    switch (state) {
    case Air::NetworkState::Direct:
        return text(Text::NetworkTitleDirect);
    case Air::NetworkState::Relay:
        return text(Text::NetworkTitleRelay);
    case Air::NetworkState::Connecting:
        return text(Text::NetworkTitleConnecting);
    case Air::NetworkState::Offline:
        break;
    }
    return text(Text::NetworkTitleOffline);
}

QString networkMeaning(Air::NetworkState state)
{
    switch (state) {
    case Air::NetworkState::Direct:
        return text(Text::NetworkMeaningDirect);
    case Air::NetworkState::Relay:
        return text(Text::NetworkMeaningRelay);
    case Air::NetworkState::Connecting:
        return text(Text::NetworkMeaningConnecting);
    case Air::NetworkState::Offline:
        break;
    }
    return text(Text::NetworkMeaningOffline);
}

QString networkLinkText(Air::NetworkReport::Link link)
{
    switch (link) {
    case Air::NetworkReport::Link::Direct:
        return text(Text::NetworkLinkDirect);
    case Air::NetworkReport::Link::Relay:
        return text(Text::NetworkLinkRelay);
    case Air::NetworkReport::Link::None:
        break;
    }
    return text(Text::NetworkLinkNone);
}

QString sectionTitle(Air::SectionKind kind)
{
    switch (kind) {
    case Air::SectionKind::Live:
        return text(Text::SectionLive);
    case Air::SectionKind::Away:
        return text(Text::SectionAway);
    case Air::SectionKind::Saved:
        return text(Text::SectionSaved);
    case Air::SectionKind::Messages:
        return text(Text::SectionMessages);
    case Air::SectionKind::Requests:
        break;
    }
    return text(Text::FilterRequests);
}

QString deliveryText(Air::Delivery delivery)
{
    switch (delivery) {
    case Air::Delivery::Transit:
        return text(Text::DeliveryTransit);
    case Air::Delivery::Sent:
        return text(Text::DeliverySent);
    case Air::Delivery::Delivered:
        return text(Text::DeliveryDelivered);
    case Air::Delivery::Failed:
        return text(Text::DeliveryFailed);
    case Air::Delivery::None:
        break;
    }
    return QString{};
}

QString emptyTitle(Air::EmptyState state)
{
    switch (state) {
    case Air::EmptyState::NothingYet:
        return text(Text::EmptyNothingTitle);
    case Air::EmptyState::NoMatch:
        return text(Text::EmptyNoMatchTitle);
    case Air::EmptyState::NoRequests:
        return text(Text::EmptyNoRequestsTitle);
    case Air::EmptyState::NoTransit:
        return text(Text::EmptyNoTransitTitle);
    case Air::EmptyState::NoFavorites:
        return text(Text::EmptyNoFavoritesTitle);
    case Air::EmptyState::None:
        break;
    }
    return QString{};
}

QString emptyBody(Air::EmptyState state)
{
    switch (state) {
    case Air::EmptyState::NothingYet:
        return text(Text::EmptyNothingBody);
    case Air::EmptyState::NoMatch:
        return text(Text::EmptyNoMatchBody);
    case Air::EmptyState::NoRequests:
        return text(Text::EmptyNoRequestsBody);
    case Air::EmptyState::NoTransit:
        return text(Text::EmptyNoTransitBody);
    case Air::EmptyState::NoFavorites:
        return text(Text::EmptyNoFavoritesBody);
    case Air::EmptyState::None:
        break;
    }
    return QString{};
}

QString statusText(Air::EntryStatus status)
{
    switch (status) {
    case Air::EntryStatus::Transit:
        return text(Text::DeliveryTransit);
    case Air::EntryStatus::Sending:
        return text(Text::DeliverySending);
    case Air::EntryStatus::Sent:
        return text(Text::DeliverySent);
    case Air::EntryStatus::Delivered:
        return text(Text::DeliveryDelivered);
    case Air::EntryStatus::Failed:
        return text(Text::DeliveryFailed);
    case Air::EntryStatus::None:
        break;
    }
    return QString{};
}

QString dayMarkText(const QDate& date, const QDate& today, const QLocale& locale)
{
    switch (Air::dayMarkKind(date, today)) {
    case Air::DayMarkKind::Today:
        return text(Text::DayToday);
    case Air::DayMarkKind::Yesterday:
        return text(Text::DayYesterday);
    case Air::DayMarkKind::Date:
        break;
    }
    const bool withYear = date.year() != today.year();
    QString format;
    switch (locale.language()) {
    case QLocale::Chinese:
        format = withYear ? QStringLiteral("yyyy年M月d日") : QStringLiteral("M月d日");
        break;
    case QLocale::English:
        format = withYear ? QStringLiteral("MMMM d, yyyy") : QStringLiteral("MMMM d");
        break;
    default:
        format = withYear ? QStringLiteral("d MMMM yyyy") : QStringLiteral("d MMMM");
        break;
    }
    return latinDigits(locale.toString(date, format), locale);
}

QString awayText(qint64 seconds)
{
    const Air::AwayWords words = Air::AwayWords::of(seconds);
    switch (words.unit) {
    case Air::AwayWords::Unit::JustNow:
        return text(Text::AwayJustNow);
    case Air::AwayWords::Unit::Minutes:
        return text(Text::AwayMinutes, static_cast<int>(words.amount));
    case Air::AwayWords::Unit::Hours:
        return text(Text::AwayHours, static_cast<int>(words.amount));
    case Air::AwayWords::Unit::Days:
        return text(Text::AwayDays, static_cast<int>(words.amount));
    case Air::AwayWords::Unit::LongAgo:
        break;
    }
    return text(Text::AwayLong);
}

QString presenceText(const Air::Reach& reach, const QString& sinceTime, bool isTyping)
{
    using Kind = Air::Reach::Kind;
    if (isTyping && reach.isLive()) {
        return text(Text::Typing);
    }
    switch (reach.kind) {
    case Kind::Direct:
        return sinceTime.isEmpty() ? text(Text::PresenceDirect)
                                   : text(Text::PresenceDirectSince).arg(sinceTime);
    case Kind::Relay:
        return sinceTime.isEmpty() ? text(Text::PresenceRelay)
                                   : text(Text::PresenceRelaySince).arg(sinceTime);
    case Kind::Pending:
        return text(Text::PresencePending);
    case Kind::Connecting:
        return text(Text::ReachConnecting);
    case Kind::Away:
        return awayText(reach.secondsAgo);
    case Kind::Group:
    case Kind::Local:
        break;
    }
    return QString{};
}

QString linkText(Air::LinkState link)
{
    switch (link) {
    case Air::LinkState::Direct:
        return text(Text::LinkDirect);
    case Air::LinkState::Relay:
        return text(Text::LinkRelay);
    case Air::LinkState::None:
        break;
    }
    return text(Text::LinkNone);
}

QString composeHintText(const Air::ComposeHint& hint, const QString& name)
{
    switch (hint.kind) {
    case Air::ComposeHint::Kind::Saved:
        return text(Text::ComposeHintSaved);
    case Air::ComposeHint::Kind::Live:
        return text(Text::ComposeHintLive).arg(name);
    case Air::ComposeHint::Kind::Wait:
        return text(Text::ComposeHintWait).arg(name);
    case Air::ComposeHint::Kind::SelfConnecting:
        return text(Text::ComposeHintSelfConnecting);
    case Air::ComposeHint::Kind::SelfOffline:
        return text(Text::ComposeHintSelfOffline);
    case Air::ComposeHint::Kind::Empty:
        break;
    }
    return QString{};
}

QString composeCounterText(int left)
{
    return left >= 0 ? text(Text::BytesLeft, left) : text(Text::BytesOverParts, -left);
}

QString trayHeadText(int count)
{
    return text(Text::TrayHead, count);
}

QString messageWords(const QString& raw)
{
    const Air::MessageText read = Air::readMessageText(raw);
    if (read.unreadable) {
        return text(Text::MessageUnknownFormat);
    }
    return read.marked ? read.body : raw;
}

QString replyName(const QString& name)
{
    const QString trimmed = name.trimmed();
    return trimmed.isEmpty() ? text(Text::ReplyUnknownSender) : trimmed;
}

namespace {
/** The parts a screen reader says, the empty ones left out, joined with commas as on iOS. */
QString spokenParts(const QStringList& parts)
{
    QStringList said;
    for (const QString& part : parts) {
        const QString words = part.simplified();
        if (!words.isEmpty()) {
            said.append(words);
        }
    }
    return said.join(QStringLiteral(", "));
}
} // namespace

QString spokenRow(const Air::Entry& entry)
{
    // The mark is short for the line it shares with the name; spoken, it says the whole state.
    const bool relayed = entry.reach.kind == Air::Reach::Kind::Relay;
    QStringList parts{entry.title, relayed ? text(Text::PresenceRelay) : reachText(entry.reach)};
    // The mark in front of the preview stands only before a message: a draft or a hint has none.
    if (entry.previewStyle == Air::PreviewStyle::Message) {
        parts.append(deliveryText(entry.lastDelivery));
    }
    parts.append(entry.preview);
    if (entry.mentioned) {
        parts.append(text(Text::Mentioned));
    }
    if (entry.transitCount > 0) {
        parts.append(text(Text::TransitFlag).arg(entry.transitCount));
    }
    if (entry.unreadCount > 0) {
        parts.append(text(Text::UnreadCount).arg(entry.unreadCount));
    }
    parts.append(entry.timeText);
    return spokenParts(parts);
}

QString spokenRequest(const Air::Request& request)
{
    if (request.isInvitation()) {
        QString from = text(Text::InviteFrom).arg(request.inviterName);
        if (!request.inviterOnline) {
            from += QLatin1Char(' ') + text(Text::InviteWait).arg(request.inviterName);
        }
        return spokenParts({text(Text::InviteTag), request.timeText, request.groupName, from});
    }
    // The start of the key without the spaces of its groups, as iOS says it.
    QString key = Air::groupedKey(request.publicKey);
    key.remove(QLatin1Char(' '));
    return spokenParts({text(Text::RequestTag), request.timeText, request.message,
                        text(Text::RequestKey).arg(key)});
}

QString spokenMessageHit(const Air::MessageHit& hit)
{
    return spokenParts({hit.title, hit.snippet, hit.timeText});
}

QString spokenSection(Air::SectionKind kind, int count)
{
    return spokenParts({sectionTitle(kind), QString::number(count)});
}

QString spokenEntry(const QString& author, const QString& time, const QString& words,
                    Air::EntryStatus status, bool mentionsYou, const QString& quote,
                    const QString& notes)
{
    return spokenParts({author, time, quote, words, mentionsYou ? text(Text::MentionsYou) : QString(),
                        notes, statusText(status)});
}

QString spokenQuote(const QString& name, const QString& preview)
{
    return spokenParts({text(Text::SpokenReply).arg(replyName(name)), preview});
}

QString spokenNotes(bool edited, const QString& reactions)
{
    return spokenParts({edited ? text(Text::MessageEdited) : QString(), reactions});
}

QString dayRangeText(int from, int to)
{
    return text(Text::DayRange).arg(from).arg(to);
}

namespace {
QStringList rangeTexts(const Air::DayStrip& strip)
{
    QStringList parts;
    for (const QPair<int, int>& range : strip.ranges()) {
        parts.append(dayRangeText(range.first, range.second));
    }
    return parts;
}
} // namespace

QString dayStripSpokenText(const Air::DayStrip& strip, const QLocale& locale, bool nowOnline)
{
    const QStringList parts = rangeTexts(strip);
    QString spoken = parts.isEmpty() ? text(Text::DayA11yNone)
                                     : text(Text::DayA11y).arg(locale.createSeparatedList(parts));
    if (nowOnline) {
        spoken += QStringLiteral(". ") + text(Text::DayNowOnline);
    }
    return spoken;
}

QString dayHintText(const Air::DayStrip& strip, bool hasBest, int bestFrom, int bestTo)
{
    switch (Air::dayHint(hasBest, strip)) {
    case Air::DayHint::Best:
        return text(Text::DayBest).arg(bestFrom).arg(bestTo);
    case Air::DayHint::Ranges:
        return text(Text::DayRanges).arg(rangeTexts(strip).join(QStringLiteral(", ")));
    case Air::DayHint::Empty:
        break;
    }
    return text(Text::DayEmpty);
}

// ---- groups (E13 to E15) ----

QString groupRoleText(Air::GroupRole role)
{
    switch (role) {
    case Air::GroupRole::Founder:
        return text(Text::GroupRoleFounder);
    case Air::GroupRole::Moderator:
        return text(Text::GroupRoleModerator);
    case Air::GroupRole::Member:
        return text(Text::GroupRoleMember);
    case Air::GroupRole::Observer:
        return text(Text::GroupRoleObserver);
    }
    return text(Text::GroupRoleMember);
}

QString groupRoleExplanation(Air::GroupRole role)
{
    switch (role) {
    case Air::GroupRole::Founder:
        return text(Text::GroupYouFounder);
    case Air::GroupRole::Moderator:
        return text(Text::GroupYouModerator);
    case Air::GroupRole::Member:
        return text(Text::GroupYouMember);
    case Air::GroupRole::Observer:
        return text(Text::GroupYouObserver);
    }
    return text(Text::GroupYouMember);
}

QString groupHeaderText(const Air::GroupReach& reach)
{
    const QString members = text(Text::GroupMembersCount, reach.total);
    if (!reach.connected) {
        return text(Text::GroupHeaderOffline).arg(members);
    }
    if (reach.online == 0) {
        return text(Text::GroupHeaderNone).arg(members);
    }
    return text(Text::GroupHeader).arg(members).arg(reach.online);
}

QString groupHintText(const Air::GroupHint& hint, const Air::GroupReach& reach)
{
    switch (hint.kind) {
    case Air::GroupHint::Kind::Offline:
        return text(Text::GroupHintOffline);
    case Air::GroupHint::Kind::SelfConnecting:
        return text(Text::GroupHintSelfConnecting);
    case Air::GroupHint::Kind::SelfOffline:
        return text(Text::GroupHintSelfOffline);
    case Air::GroupHint::Kind::Alone:
        return text(Text::GroupHintAlone);
    case Air::GroupHint::Kind::Nobody:
        return text(Text::GroupHintNobody);
    case Air::GroupHint::Kind::OneOther:
        return text(Text::GroupHintOneOther);
    case Air::GroupHint::Kind::All:
        return text(Text::GroupHintAll, reach.online);
    case Air::GroupHint::Kind::Some:
        return text(Text::GroupHintSome, reach.online).arg(reach.recipients());
    }
    return QString();
}

QString groupReadOnlyText(Air::ComposeRight right)
{
    switch (right) {
    case Air::ComposeRight::Write:
        return QString();
    case Air::ComposeRight::Observer:
        return text(Text::GroupReadOnlyObserver);
    case Air::ComposeRight::ModeratorsOnly:
        return text(Text::GroupReadOnlyModerators);
    case Air::ComposeRight::FounderOnly:
        return text(Text::GroupReadOnlyFounder);
    }
    return QString();
}

QString groupAskText(Air::ComposeRight right)
{
    switch (right) {
    case Air::ComposeRight::Write:
        return QString();
    case Air::ComposeRight::Observer:
    case Air::ComposeRight::ModeratorsOnly:
        return text(Text::GroupAskModerator);
    case Air::ComposeRight::FounderOnly:
        return text(Text::GroupAskFounder);
    }
    return QString();
}

QString groupAskUnavailableText(Air::ComposeRight right, bool nobodyToAsk)
{
    if (right == Air::ComposeRight::Write) {
        return QString();
    }
    if (right == Air::ComposeRight::Observer) {
        return text(Text::GroupObserverNoPrivate);
    }
    return nobodyToAsk ? text(Text::GroupAskNobody) : QString();
}

QString groupCounterText(int left)
{
    return left >= 0 ? text(Text::BytesLeft, left) : text(Text::GroupBytesOver, -left);
}

QString groupMemberStatusText(const Air::GroupMember& member)
{
    return text(Text::GroupMemberStatus)
        .arg(groupRoleText(member.role),
             member.online ? text(Text::ReachLive) : text(Text::GroupMemberOffline));
}

QString memberActionText(Air::MemberAction action)
{
    switch (action) {
    case Air::MemberAction::MakeModerator:
        return text(Text::GroupMakeModerator);
    case Air::MemberAction::MakeMember:
        return text(Text::GroupMakeMember);
    case Air::MemberAction::MakeObserver:
        return text(Text::GroupMakeObserver);
    case Air::MemberAction::Remove:
        return text(Text::GroupRemove);
    }
    return QString();
}

QString memberActionTitle(Air::MemberAction action, const QString& name)
{
    switch (action) {
    case Air::MemberAction::MakeModerator:
        return text(Text::GroupMakeModeratorTitle).arg(name);
    case Air::MemberAction::MakeMember:
        return text(Text::GroupMakeMemberTitle).arg(name);
    case Air::MemberAction::MakeObserver:
        return text(Text::GroupMakeObserverTitle).arg(name);
    case Air::MemberAction::Remove:
        return text(Text::GroupRemoveTitle).arg(name);
    }
    return QString();
}

QString memberActionMessage(Air::MemberAction action)
{
    switch (action) {
    case Air::MemberAction::MakeModerator:
        return text(Text::GroupMakeModeratorMessage);
    case Air::MemberAction::MakeMember:
        return text(Text::GroupMakeMemberMessage);
    case Air::MemberAction::MakeObserver:
        return text(Text::GroupMakeObserverMessage);
    case Air::MemberAction::Remove:
        return text(Text::GroupRemoveMessage);
    }
    return QString();
}

QString memberActionDone(Air::MemberAction action, const QString& name)
{
    switch (action) {
    case Air::MemberAction::MakeModerator:
        return text(Text::GroupNowModerator).arg(name);
    case Air::MemberAction::MakeMember:
        return text(Text::GroupNowMember).arg(name);
    case Air::MemberAction::MakeObserver:
        return text(Text::GroupNowObserver).arg(name);
    case Air::MemberAction::Remove:
        return text(Text::GroupRemoved).arg(name);
    }
    return QString();
}

QString groupPrivacyText(Air::GroupPrivacy privacy)
{
    return text(privacy == Air::GroupPrivacy::InviteOnly ? Text::PrivacyInviteOnly : Text::PrivacyOpen);
}

QString groupPrivacyNote(Air::GroupPrivacy privacy)
{
    return text(privacy == Air::GroupPrivacy::InviteOnly ? Text::PrivacyInviteOnlyNote
                                                         : Text::PrivacyOpenNote);
}

// ---- files, voice notes and calls (E8, E9) ----

QString fileActionText(Air::FileAction action)
{
    switch (action) {
    case Air::FileAction::Accept:
        return text(Text::FileAccept);
    case Air::FileAction::Decline:
        return text(Text::FileDecline);
    case Air::FileAction::Pause:
        return text(Text::FilePause);
    case Air::FileAction::Resume:
        return text(Text::FileResume);
    case Air::FileAction::Cancel:
        return text(Text::FileCancel);
    case Air::FileAction::SendAgain:
        return text(Text::FileSendAgain);
    case Air::FileAction::Open:
        return text(Text::FileOpen);
    case Air::FileAction::ShowInFolder:
        return text(Text::FileShowInFolder);
    }
    return QString();
}

QString fileNoteText(const Air::FileEntryState& state, const QString& person, const QLocale& locale)
{
    switch (state.note) {
    case Air::FileNote::None:
        return QString();
    case Air::FileNote::Left:
        return text(Text::FileLeft).arg(byteSizeText(state.bytesLeft, locale));
    case Air::FileNote::Paused:
        return text(Text::FilePaused);
    case Air::FileNote::PausedThere:
        return text(Text::FilePausedThere);
    case Air::FileNote::WaitsAccept:
        return text(Text::FileWaitsAccept).arg(person);
    case Air::FileNote::DownloadStopped:
        return text(Text::FileDownloadStopped);
    case Air::FileNote::NotSent:
        return text(Text::FileNotSent);
    }
    return QString();
}

QString callEntryText(const Air::CallEntry& entry)
{
    switch (entry.outcome) {
    case Air::CallOutcome::Answered:
        return text(Text::CallEntry).arg(Air::clockText(entry.seconds));
    case Air::CallOutcome::CutOff:
        return text(Text::CallEntryCutOff).arg(Air::clockText(entry.seconds));
    case Air::CallOutcome::Missed:
        return text(Text::CallMissed);
    case Air::CallOutcome::NoAnswer:
        return text(Text::CallNoAnswer);
    case Air::CallOutcome::Outgoing:
        return text(Text::CallOutgoing);
    case Air::CallOutcome::Incoming:
        return text(Text::CallIncoming);
    }
    return QString();
}

QString groupFileLimitText(const QLocale& locale)
{
    return text(Text::GroupFileLimit).arg(byteSizeText(Air::fileLimitShownBytes, locale));
}

} // namespace AirStrings
