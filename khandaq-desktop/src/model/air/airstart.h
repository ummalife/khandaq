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

// ---- First launch and sign-in (E19, E20): the rules the start window applies --------------------

namespace Air {

/** A new password takes at least this many characters (khandaq-ios PasswordPolicy). */
constexpr int newPasswordMinLength = 8;

/** Why a new password cannot be taken yet, or None once it can. */
enum class NewPasswordProblem
{
    None,
    Empty,
    /** Fewer than newPasswordMinLength characters as a person counts them. */
    Short,
    /** No letter or no digit in it. */
    Weak,
    /** The password is fine and the field that repeats it is still empty. */
    RepeatFirst,
    Mismatch,
};

/**
 * The rule of a new password, on first launch and wherever a password is set: at least 8
 * characters, with a letter and a digit of any script, typed the same twice. Passwords made before
 * the rule keep opening their profiles: it applies only when a password is set.
 */
NewPasswordProblem newPasswordProblem(const QString& password, const QString& repeated);

/** The characters of text as a person sees them: an accent or an emoji of several code points is one. */
int visibleLength(const QString& text);

/** A profile file name keeps to this many characters (khandaq-ios ProfileFolderName.maxLength). */
constexpr int profileFileNameMaxLength = 64;
/** ... and to this many bytes of UTF-8, under the 255 a file name may take with ".tox" and ".ini". */
constexpr int profileFileNameMaxBytes = 200;

/**
 * The name of the files of a new profile (name.tox, name.ini, name.db) from the name the person
 * typed: one line without the characters a file system gives meaning to on macOS, Linux or Windows,
 * without dots or spaces at its ends and not a name Windows keeps for devices, cut to the limits
 * above, and unique among existing compared without case, as the file systems of macOS and Windows
 * compare names ("Amina 2" after "Amina"). fallback stands in when nothing of name is left.
 */
QString profileFileName(const QString& name, const QStringList& existing, const QString& fallback);

/** Why a profile did not open or was not made, as the start window says it under the field (E20). */
struct OpenFailure
{
    enum class Kind
    {
        None,
        /** The password does not open the save. Tox cannot tell it from a damaged encrypted save. */
        WrongPassword,
        /** The save is empty, cut short, or not one toxcore loads. */
        Damaged,
        /** The file is gone, or this user may not read or write it. */
        NoAccess,
        /** Khandaq on this computer has the profile open already. */
        InUse,
        /** Toxcore did not start with the proxy of the profile's network settings. */
        BadProxy,
        /** Something else did not start: the code says what, for support. */
        Other,
    };

    Kind kind = Kind::None;
    /** For support, under Details: the step that failed and its code. Never part of the words. */
    QString code;

    OpenFailure() = default;
    OpenFailure(Kind kind, const QString& code);

    bool failed() const;
    bool operator==(const OpenFailure& other) const;
};

} // namespace Air
