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

#include <QImage>
#include <QString>
#include <QVector>

#include <functional>

// ---- You (E17): your ID, name and status as the You page shows and edits them ------------------

namespace Air {

/** Tox carries at most this many UTF-8 bytes of your name (TOX_MAX_NAME_LENGTH). */
constexpr int nameLimitBytes = 128;
/** ... and of your status line (TOX_MAX_STATUS_MESSAGE_LENGTH). */
constexpr int statusLimitBytes = 1007;

/**
 * The longest start of text that fits in limitBytes of UTF-8, cut between two characters as a
 * person sees them, so no letter loses its accent and no emoji its half (khandaq-ios AirYouField
 * refuses the edit that would go over instead).
 */
QString fitUtf8(const QString& text, int limitBytes);

/** An edit of a field kept within its bytes, and where the caret goes after it. */
struct FittedEdit
{
    QString text;
    int caret = 0;
};

/**
 * The edit that turned before into after, kept within limitBytes of UTF-8: what was typed or pasted
 * goes in as far as it fits, cut as fitUtf8 cuts, and the rest of the text stays as it was. The
 * caret goes after what went in.
 */
FittedEdit fitEdit(const QString& before, const QString& after, int limitBytes);

/**
 * Whether status is a default an older build wrote into every new profile, never chosen by the
 * person: qTox's "Toxing on Khandaq" in any of its translations, "Toxing on qTox" from before the
 * rename, and the phones' old "Khandaq is Tox". A profile that still has one loses it at load, as
 * the phones clear theirs (khandaq-ios KhandaqBranding.clearLegacyDefaultStatus).
 */
bool isLegacyDefaultStatus(const QString& status);

/** The three named parts of an ID: key (64 characters), antispam (8) and check (4). */
struct IdParts
{
    QString key;
    QString antispam;
    QString check;

    bool isEmpty() const;
    bool operator==(const IdParts& other) const;
};

/** The parts of a whole 76-character ID; empty parts for anything else. */
IdParts idParts(const QString& id);

/**
 * An antispam other than current, so the old ID stops taking new requests (khandaq-ios
 * AirYouId.newAntispam). random gives 32 random bits a time.
 */
quint32 newAntispam(quint32 current, const std::function<quint32()>& random);

/** The modules of a QR code, row by row from the top. */
struct QrModules
{
    /** Modules a side; 0 when the text could not be encoded. */
    int side = 0;
    QVector<bool> dark;

    bool isDark(int x, int y) const;
};

/** The QR code of text: byte mode, error correction M, the smallest version it fits in. */
QrModules qrModules(const QString& text);

/**
 * QR codes want four light modules round them to be read (ISO/IEC 18004, the quiet zone); the white
 * card of the code gives them in both themes.
 */
constexpr int qrQuietModules = 4;

/** The code as a picture: dark modules on white, moduleSide pixels each, with its quiet zone. */
QImage qrImage(const QrModules& modules, int moduleSide);

} // namespace Air
