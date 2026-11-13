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

#include <QString>
#include <QStringList>

// ---- Settings (E18): the rules behind the choices the settings page offers ----------------------

namespace Air {

/** Download attachments, as three choices (DESIGN.md, "Settings on the phone"). */
enum class AttachmentMode
{
    /** Every file a contact sends is saved by itself. */
    Always,
    /** Files up to a size are saved by themselves; bigger ones wait for Accept. */
    Small,
    /** Every file waits for Accept in the chat. */
    Never,
};

/** Bytes in a megabyte: sizes count in thousands, as on the phones. */
constexpr qint64 bytesPerMegabyte = 1000 * 1000;
/** The size Small starts from when no limit was set before: a photo or a voice note fits. */
constexpr qint64 smallAttachmentDefault = 10 * bytesPerMegabyte;
/** The largest limit the field takes, in megabytes. */
constexpr int smallAttachmentMaxMegabytes = 4000;

/** The settings behind a choice: whether files are saved by themselves, and up to what size. */
struct AttachmentRule
{
    bool autoSave = true;
    /** 0 means no limit. */
    qint64 maxSize = 0;
};

/** The choice the stored settings make. */
AttachmentMode attachmentMode(bool autoSave, qint64 maxSize);
/**
 * The settings for a choice. Small keeps a limit set before (previousMaxSize) and starts from
 * smallAttachmentDefault without one.
 */
AttachmentRule attachmentRule(AttachmentMode mode, qint64 previousMaxSize);

/**
 * Where a file a contact sends is saved by itself, or an empty string when it waits for Accept.
 * A folder chosen for the contact takes every file within the size limit; the folder of the
 * settings, else downloads, takes them only while files are saved by themselves (autoSave).
 */
QString autoAcceptFolder(const QString& contactFolder, bool autoSave, const QString& globalFolder,
                         const QString& downloads, qint64 maxSize, qint64 fileSize);
/**
 * How the page says the folder files are saved to: downloadsName (the name of the downloads folder
 * in the language of the interface) when it is that folder or none is chosen, else its path, the
 * home folder written as ~ where tildeForHome says the system writes it so.
 */
QString folderLabel(const QString& folder, const QString& downloads, const QString& downloadsName,
                    const QString& home, bool tildeForHome);

/**
 * The keys whose messages and voice this device hides in every group (Hide their messages in a
 * group dossier), as Settings > Privacy lists them: each once, in the order they were hidden,
 * without the blank lines the old free-text field of the list could leave.
 */
QStringList hiddenKeys(const QStringList& blackList);
/** The list with key shown again: every entry that holds it goes, however it was spaced. */
QStringList withoutHiddenKey(const QStringList& blackList, const QString& key);

/** The languages the interface offers, as locale names, in the order the list shows them. */
QStringList interfaceLanguages();
/**
 * A language named in itself (Russian in Cyrillic, Arabic in Arabic), as the list shows it; no two
 * languages of the list read the same.
 */
QString languageName(const QString& locale);

} // namespace Air
