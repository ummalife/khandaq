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

#include "airmention.h"

#include "src/model/air/airmodel.h"

#include <QRegularExpression>
#include <QSet>
#include <QStringList>

#include <algorithm>

namespace Air {

namespace {
const QString blockPrefix = QStringLiteral("[KQ|m|");
const QString blockSuffix = QStringLiteral("][KQ/m]");
const QString legacyBlockPrefix = QStringLiteral("[KQ|m]");
const QString legacyBlockSuffix = QStringLiteral("[|KQ|m]");

/** The first hex digits of a key a handle takes when it is shared, then when those are alike too. */
constexpr int shortKeyDigits = 4;
constexpr int longKeyDigits = 8;

/** A letter or a decimal digit, as Java's Character.isLetterOrDigit has them. */
bool isHandleLetter(uint codePoint)
{
    switch (QChar::category(codePoint)) {
    case QChar::Letter_Uppercase:
    case QChar::Letter_Lowercase:
    case QChar::Letter_Titlecase:
    case QChar::Letter_Modifier:
    case QChar::Letter_Other:
    case QChar::Number_DecimalDigit:
        return true;
    default:
        return false;
    }
}

bool isCombiningMark(uint codePoint)
{
    const QChar::Category category = QChar::category(codePoint);
    return category == QChar::Mark_NonSpacing || category == QChar::Mark_SpacingCombining
           || category == QChar::Mark_Enclosing;
}

/** What a token holds: [\p{L}\p{M}\p{N}_]. */
bool isTokenCodePoint(uint codePoint)
{
    if (codePoint == '_') {
        return true;
    }
    switch (QChar::category(codePoint)) {
    case QChar::Letter_Uppercase:
    case QChar::Letter_Lowercase:
    case QChar::Letter_Titlecase:
    case QChar::Letter_Modifier:
    case QChar::Letter_Other:
    case QChar::Mark_NonSpacing:
    case QChar::Mark_SpacingCombining:
    case QChar::Mark_Enclosing:
    case QChar::Number_DecimalDigit:
    case QChar::Number_Letter:
    case QChar::Number_Other:
        return true;
    default:
        return false;
    }
}

/** The code point that ends at end in text (a surrogate pair counts as one), and its length. */
uint codePointBefore(const QString& text, int end, int* length)
{
    const QChar last = text.at(end - 1);
    if (last.isLowSurrogate() && end >= 2 && text.at(end - 2).isHighSurrogate()) {
        *length = 2;
        return QChar::surrogateToUcs4(text.at(end - 2), last);
    }
    *length = 1;
    return last.unicode();
}

/** The code point that starts at start in text, and its length. */
uint codePointAt(const QString& text, int start, int* length)
{
    const QChar first = text.at(start);
    if (first.isHighSurrogate() && start + 1 < text.size() && text.at(start + 1).isLowSurrogate()) {
        *length = 2;
        return QChar::surrogateToUcs4(first, text.at(start + 1));
    }
    *length = 1;
    return first.unicode();
}

void appendCodePoint(QString* text, uint codePoint)
{
    if (QChar::requiresSurrogates(codePoint)) {
        text->append(QChar(QChar::highSurrogate(codePoint)));
        text->append(QChar(QChar::lowSurrogate(codePoint)));
    } else {
        text->append(QChar(static_cast<ushort>(codePoint)));
    }
}

const QRegularExpression& tokenPattern()
{
    static const QRegularExpression pattern(QStringLiteral("@([\\p{L}\\p{M}\\p{N}_]+)"),
                                            QRegularExpression::UseUnicodePropertiesOption);
    return pattern;
}

/** The handle of a name, or "u" and 8 hex digits of the key when the name gives none. */
QString baseHandle(const QString& name, const QString& key)
{
    const QString handle = mentionHandle(name);
    return handle.isEmpty() ? QStringLiteral("u") + key.left(longKeyDigits) : handle;
}

/** GroupMentionHelper.sanitizeField: a field that cannot end its pair or the block. */
QString blockField(const QString& value)
{
    QString field = value;
    field.remove(QLatin1Char('|')).remove(QLatin1Char(';')).remove(QLatin1Char(':'));
    field.replace(QLatin1Char('\n'), QLatin1Char(' '));
    return field.trimmed();
}

/** The keys of keys when there is exactly one, else empty. */
QString onlyKey(const QStringList& keys)
{
    return keys.size() == 1 ? keys.first() : QString();
}

/** Where the words with the mention block start in raw: after a reply's head and line breaks. */
QString wordsWithBlock(const QString& raw)
{
    QString words = raw.mid(ownWordsStart(raw));
    int start = 0;
    while (start < words.size()
           && (words.at(start) == QLatin1Char('\n') || words.at(start) == QLatin1Char('\r'))) {
        ++start;
    }
    return words.mid(start);
}

void appendPair(const QString& part, QVector<Mention>* mentions)
{
    const int colon = part.indexOf(QLatin1Char(':'));
    if (colon <= 0) {
        return;
    }
    Mention mention;
    mention.key = part.left(colon).trimmed().toUpper();
    mention.handle = part.mid(colon + 1);
    if (!mention.key.isEmpty() && !mention.handle.isEmpty()) {
        mentions->append(mention);
    }
}
} // namespace

bool Mention::operator==(const Mention& other) const
{
    return key == other.key && handle == other.handle;
}

bool MentionSpan::operator==(const MentionSpan& other) const
{
    return start == other.start && length == other.length && key == other.key;
}

QString mentionHandle(const QString& name)
{
    if (name.isEmpty()) {
        return QString();
    }
    QString stripped = name;
    const int paren = stripped.indexOf(QStringLiteral(" ("));
    if (paren > 0) {
        stripped.truncate(paren);
    }

    QString handle;
    bool lastUnderscore = false;
    for (uint codePoint : stripped.toUcs4()) {
        if (isHandleLetter(codePoint)) {
            appendCodePoint(&handle, codePoint);
            lastUnderscore = false;
        } else if (isCombiningMark(codePoint) && !handle.isEmpty() && !lastUnderscore) {
            // The mark belongs to the letter before it: an Indic name keeps its vowel signs.
            appendCodePoint(&handle, codePoint);
        } else if ((codePoint == ' ' || codePoint == '-' || codePoint == '.') && !lastUnderscore
                   && !handle.isEmpty()) {
            handle.append(QLatin1Char('_'));
            lastUnderscore = true;
        }
    }
    while (handle.endsWith(QLatin1Char('_'))) {
        handle.chop(1);
    }
    return handle;
}

QHash<QString, QString> mentionHandles(const QVector<GroupMember>& members)
{
    struct Named
    {
        QString key;
        QString base;
        QString handle;
    };
    QVector<Named> named;
    QSet<QString> seen;
    for (const GroupMember& member : members) {
        const QString key = member.publicKey.trimmed().toUpper();
        if (key.isEmpty() || seen.contains(key)) {
            continue;
        }
        seen.insert(key);
        const QString base = baseHandle(member.name, key);
        named.append(Named{key, base, base});
    }

    for (const int digits : {shortKeyDigits, longKeyDigits}) {
        QHash<QString, int> uses;
        for (const Named& entry : named) {
            ++uses[entry.handle.toCaseFolded()];
        }
        for (Named& entry : named) {
            if (uses.value(entry.handle.toCaseFolded()) > 1) {
                entry.handle = entry.base + QLatin1Char('_') + entry.key.left(digits);
            }
        }
    }

    QHash<QString, QString> handles;
    for (const Named& entry : named) {
        handles.insert(entry.key, entry.handle);
    }
    return handles;
}

QVector<MentionChoice> mentionChoices(const QVector<GroupMember>& members, const QString& query)
{
    const QHash<QString, QString> handles = mentionHandles(members);
    const QString folded = fold(query.trimmed());
    QVector<MentionChoice> choices;
    QSet<QString> seen;
    for (const GroupMember& member : members) {
        const QString key = member.publicKey.trimmed().toUpper();
        if (member.isSelf || key.isEmpty() || seen.contains(key)) {
            continue;
        }
        seen.insert(key);
        const QString handle = handles.value(key);
        if (!folded.isEmpty() && !fold(handle).startsWith(folded)
            && !fold(member.name).contains(folded)) {
            continue;
        }
        choices.append(MentionChoice{member, handle});
    }
    std::stable_sort(choices.begin(), choices.end(),
                     [](const MentionChoice& first, const MentionChoice& second) {
                         if (first.member.online != second.member.online) {
                             return first.member.online;
                         }
                         return compareTitles(first.member.name, second.member.name) < 0;
                     });
    return choices;
}

bool mentionQueryAt(const QString& text, int caret, int* start, QString* query)
{
    if (caret < 1 || caret > text.size()) {
        return false;
    }
    int begin = caret;
    while (begin > 0) {
        int length = 0;
        const uint codePoint = codePointBefore(text, begin, &length);
        if (!isTokenCodePoint(codePoint)) {
            break;
        }
        begin -= length;
    }
    if (begin < 1 || text.at(begin - 1) != QLatin1Char('@')) {
        return false;
    }
    const int at = begin - 1;
    if (at > 0 && !text.at(at - 1).isSpace()) {
        return false;
    }
    if (start) {
        *start = at;
    }
    if (query) {
        *query = text.mid(begin, caret - begin);
    }
    return true;
}

int mentionTokenEnd(const QString& text, int start)
{
    int end = start + 1;
    while (end < text.size()) {
        int length = 0;
        if (!isTokenCodePoint(codePointAt(text, end, &length))) {
            break;
        }
        end += length;
    }
    return std::min(end, text.size());
}

QVector<Mention> collectMentions(const QString& words, const QVector<GroupMember>& members)
{
    QVector<Mention> mentions;
    // Without an "@" there is nothing to look for, and the members are not walked.
    if (!words.contains(QLatin1Char('@'))) {
        return mentions;
    }

    const QHash<QString, QString> handles = mentionHandles(members);
    QHash<QString, QStringList> byHandle;
    QHash<QString, QStringList> byBase;
    QHash<QString, QStringList> bySuffixed;
    QSet<QString> seen;
    for (const GroupMember& member : members) {
        const QString key = member.publicKey.trimmed().toUpper();
        // mentionHandles keeps the first member of a key, as here.
        if (key.isEmpty() || seen.contains(key)) {
            continue;
        }
        seen.insert(key);
        if (member.isSelf) {
            continue;
        }
        const QString base = baseHandle(member.name, key);
        byHandle[handles.value(key).toCaseFolded()].append(key);
        byBase[base.toCaseFolded()].append(key);
        for (const int digits : {shortKeyDigits, longKeyDigits}) {
            bySuffixed[(base + QLatin1Char('_') + key.left(digits)).toCaseFolded()].append(key);
        }
    }

    QSet<QString> taken;
    auto matches = tokenPattern().globalMatch(words);
    while (matches.hasNext()) {
        const QString token = matches.next().captured(1);
        const QString folded = token.toCaseFolded();
        QString key = onlyKey(byHandle.value(folded));
        if (key.isEmpty()) {
            key = onlyKey(byBase.value(folded));
        }
        if (key.isEmpty()) {
            key = onlyKey(bySuffixed.value(folded));
        }
        const QString pair = key + QLatin1Char(':') + token;
        if (key.isEmpty() || taken.contains(pair)) {
            continue;
        }
        taken.insert(pair);
        mentions.append(Mention{key, token});
    }
    return mentions;
}

QString mentionBlock(const QVector<Mention>& mentions)
{
    QStringList pairs;
    for (const Mention& mention : mentions) {
        const QString key = blockField(mention.key);
        const QString handle = blockField(mention.handle);
        if (!key.isEmpty() && !handle.isEmpty()) {
            pairs.append(key + QLatin1Char(':') + handle);
        }
    }
    if (pairs.isEmpty()) {
        return QString();
    }
    return blockPrefix + pairs.join(QLatin1Char(';')) + blockSuffix + QLatin1Char('\n');
}

QVector<Mention> readMentions(const QString& raw)
{
    QVector<Mention> mentions;
    const QString words = wordsWithBlock(raw);
    if (words.startsWith(blockPrefix)) {
        const int end = words.indexOf(blockSuffix, blockPrefix.size());
        if (end < 0) {
            return mentions;
        }
        const QString payload = words.mid(blockPrefix.size(), end - blockPrefix.size());
        for (const QString& part : payload.split(QLatin1Char(';'))) {
            appendPair(part, &mentions);
        }
    } else if (words.startsWith(legacyBlockPrefix)) {
        const int end = words.indexOf(legacyBlockSuffix, legacyBlockPrefix.size());
        if (end >= 0) {
            appendPair(words.mid(legacyBlockPrefix.size(), end - legacyBlockPrefix.size()),
                       &mentions);
        }
    }
    return mentions;
}

bool mentionsKey(const QString& raw, const QString& key)
{
    const QString wanted = key.trimmed().toUpper();
    if (wanted.isEmpty()) {
        return false;
    }
    const QVector<Mention> mentions = readMentions(raw);
    return std::any_of(mentions.cbegin(), mentions.cend(),
                       [&wanted](const Mention& mention) { return mention.key == wanted; });
}

QVector<MentionSpan> mentionSpans(const QString& words, const QVector<Mention>& mentions)
{
    QVector<MentionSpan> spans;
    if (mentions.isEmpty() || !words.contains(QLatin1Char('@'))) {
        return spans;
    }
    QHash<QString, QString> keyOfHandle;
    for (const Mention& mention : mentions) {
        const QString folded = mention.handle.toCaseFolded();
        if (!keyOfHandle.contains(folded)) {
            keyOfHandle.insert(folded, mention.key);
        }
    }
    auto matches = tokenPattern().globalMatch(words);
    while (matches.hasNext()) {
        const QRegularExpressionMatch match = matches.next();
        const auto found = keyOfHandle.constFind(match.captured(1).toCaseFolded());
        if (found != keyOfHandle.constEnd()) {
            MentionSpan span;
            span.start = match.capturedStart(0);
            span.length = match.capturedLength(0);
            span.key = found.value();
            spans.append(span);
        }
    }
    return spans;
}

QString mentionLink(const QString& key)
{
    return QStringLiteral("kqmention:") + key.toUpper();
}

QString mentionLinkKey(const QString& link)
{
    const QString prefix = QStringLiteral("kqmention:");
    if (!link.startsWith(prefix, Qt::CaseInsensitive)) {
        return QString();
    }
    const QString key = link.mid(prefix.size()).toUpper();
    const bool hex = key.size() == 64 && std::all_of(key.cbegin(), key.cend(), [](QChar c) {
        return (c >= QLatin1Char('0') && c <= QLatin1Char('9'))
               || (c >= QLatin1Char('A') && c <= QLatin1Char('F'));
    });
    return hex ? key : QString();
}

} // namespace Air
