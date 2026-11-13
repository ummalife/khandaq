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

#include "airstart.h"

#include "src/model/air/airyou.h"

#include <QSet>
#include <QTextBoundaryFinder>
#include <QVector>

namespace Air {

namespace {

/**
 * A character no file name takes on one system or another: what a path gives meaning to, control
 * characters, and the invisible format characters, among them the ones that turn the direction of
 * the text round and could make "gpj.exe" read as "exe.jpg". The two joiners stay: Persian writing
 * and emoji need them, and they turn nothing round.
 */
bool unsafeInFileName(uint code)
{
    switch (code) {
    case '/':
    case '\\':
    case ':':
    case '<':
    case '>':
    case '"':
    case '|':
    case '?':
    case '*':
        return true;
    default:
        break;
    }
    if (code == 0x200C || code == 0x200D) {
        return false;
    }
    switch (QChar::category(code)) {
    case QChar::Other_Control:
    case QChar::Other_Format:
    case QChar::Separator_Line:
    case QChar::Separator_Paragraph:
        return true;
    default:
        return false;
    }
}

/** Without the dots and spaces at its ends: Windows drops them, and a leading dot hides a file. */
QString trimEnds(QString text)
{
    const auto edge = [](QChar c) { return c == QLatin1Char('.') || c.isSpace(); };
    while (!text.isEmpty() && edge(text.at(0))) {
        text.remove(0, 1);
    }
    while (!text.isEmpty() && edge(text.at(text.size() - 1))) {
        text.chop(1);
    }
    return text;
}

/** The start of text with at most graphemes characters as a person sees them and bytes of UTF-8. */
QString cut(const QString& text, int graphemes, int bytes)
{
    QTextBoundaryFinder finder(QTextBoundaryFinder::Grapheme, text);
    int count = 0;
    int end = 0;
    while (count < graphemes) {
        const int next = finder.toNextBoundary();
        if (next < 0) {
            break;
        }
        end = next;
        ++count;
    }
    return trimEnds(fitUtf8(text.left(end), bytes));
}

QString clean(const QString& name)
{
    QString spaced;
    spaced.reserve(name.size());
    for (const uint code : name.toUcs4()) {
        if (unsafeInFileName(code)) {
            spaced.append(QLatin1Char(' '));
        } else {
            spaced.append(QString::fromUcs4(&code, 1));
        }
    }
    return cut(trimEnds(spaced.simplified()), profileFileNameMaxLength, profileFileNameMaxBytes);
}

/** The names Windows keeps for devices, which no file may take, whatever its extension. */
bool reservedOnWindows(const QString& name)
{
    static const QStringList devices{QStringLiteral("con"), QStringLiteral("prn"),
                                     QStringLiteral("aux"), QStringLiteral("nul")};
    const QString folded = name.toCaseFolded();
    if (devices.contains(folded)) {
        return true;
    }
    return folded.size() == 4
           && (folded.startsWith(QStringLiteral("com")) || folded.startsWith(QStringLiteral("lpt")))
           && folded.at(3) >= QLatin1Char('1') && folded.at(3) <= QLatin1Char('9');
}

} // namespace

int visibleLength(const QString& text)
{
    QTextBoundaryFinder finder(QTextBoundaryFinder::Grapheme, text);
    int count = 0;
    while (finder.toNextBoundary() >= 0) {
        ++count;
    }
    return count;
}

NewPasswordProblem newPasswordProblem(const QString& password, const QString& repeated)
{
    if (password.isEmpty()) {
        return NewPasswordProblem::Empty;
    }
    if (visibleLength(password) < newPasswordMinLength) {
        return NewPasswordProblem::Short;
    }
    bool letter = false;
    bool digit = false;
    for (const uint code : password.toUcs4()) {
        letter = letter || QChar::isLetter(code);
        digit = digit || QChar::isDigit(code);
    }
    if (!letter || !digit) {
        return NewPasswordProblem::Weak;
    }
    if (repeated.isEmpty()) {
        return NewPasswordProblem::RepeatFirst;
    }
    return repeated == password ? NewPasswordProblem::None : NewPasswordProblem::Mismatch;
}

QString profileFileName(const QString& name, const QStringList& existing, const QString& fallback)
{
    QString base = clean(name);
    if (base.isEmpty()) {
        base = clean(fallback);
    }
    if (base.isEmpty()) {
        base = QStringLiteral("Profile");
    }
    QSet<QString> taken;
    for (const QString& one : existing) {
        taken.insert(one.toCaseFolded());
    }
    const auto available = [&taken](const QString& candidate) {
        return !taken.contains(candidate.toCaseFolded()) && !reservedOnWindows(candidate);
    };
    if (available(base)) {
        return base;
    }
    for (int number = 2;; ++number) {
        const QString suffix = QLatin1Char(' ') + QString::number(number);
        const QString candidate = cut(base, profileFileNameMaxLength - suffix.size(),
                                      profileFileNameMaxBytes - suffix.size())
                                  + suffix;
        if (available(candidate)) {
            return candidate;
        }
    }
}

OpenFailure::OpenFailure(Kind kind_, const QString& code_)
    : kind{kind_}
    , code{code_}
{
}

bool OpenFailure::failed() const
{
    return kind != Kind::None;
}

bool OpenFailure::operator==(const OpenFailure& other) const
{
    return kind == other.kind && code == other.code;
}

} // namespace Air
