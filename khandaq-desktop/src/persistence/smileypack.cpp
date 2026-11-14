/*
    Copyright © 2014-2019 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "smileypack.h"
#include "src/persistence/settings.h"

#include <QDir>
#include <QDomElement>
#include <QMap>
#include <QPair>
#include <QRegularExpression>
#include <QStandardPaths>
#include <QStringBuilder>
#include <QTextBoundaryFinder>
#include <QVector>
#include <QtConcurrent/QtConcurrentRun>
#include <QTimer>

#include <initializer_list>
#include <iterator>

#if defined(Q_OS_FREEBSD)
#include <locale.h>
#endif

/**
 * @class SmileyPack
 * @brief Maps emoticons to smileys.
 *
 * @var SmileyPack::filenameTable
 * @brief Matches an emoticon to its corresponding smiley ie. ":)" -> "happy.png"
 *
 * @var SmileyPack::iconCache
 * @brief representation of a smiley ie. "happy.png" -> data
 *
 * @var SmileyPack::emoticons
 * @brief {{ ":)", ":-)" }, {":(", ...}, ... }
 *
 * @var SmileyPack::path
 * @brief directory containing the cfg and image files
 *
 * @var SmileyPack::defaultPaths
 * @brief Contains all directories where smileys could be found
 */

namespace {
QStringList loadDefaultPaths();

const QStringList DEFAULT_PATHS = loadDefaultPaths();

const QString RICH_TEXT_PATTERN = QStringLiteral("<img title=\"%1\" src=\"key:%1\"\\>");

const QString EMOTICONS_FILE_NAME = QStringLiteral("emoticons.xml");

constexpr int CLEANUP_TIMEOUT = 5 * 60 * 1000; // 5 minutes

const QChar ZERO_WIDTH_JOINER{0x200D};
const QChar EMOJI_PRESENTATION{0xFE0F};

const QString EMOJI_FONT_PATTERN = QStringLiteral("<span style=\"font-family:'%1'\">%2</span>");

QString fromCodePoints(std::initializer_list<uint> codePoints)
{
    QString text;
    for (const uint codePoint : codePoints) {
        text += QString::fromUcs4(&codePoint, 1);
    }
    return text;
}

// KHANDAQ (owner, 2026-11-14; QA live F14): the joined sequences that Android's emoji picker does
// not offer (KhandaqEmojiFilter.BLOCKED_CONTAINS, a user's request of 17 Aug 2026) are not drawn
// as one picture here either: their joiners are taken out of what is drawn, and the members are
// drawn apart, as before #95 made every joined sequence one character. Written without U+FE0F and the
// skin tones, which are taken out of a sequence before it is compared, so that every spelling and
// every skin tone of these hits. Keep in step with KhandaqEmojiFilter.java.
const QStringList BLOCKED_SEQUENCES = {
    fromCodePoints({0x1F3F3, 0x200D, 0x1F308}),                          // rainbow flag
    fromCodePoints({0x1F3F3, 0x200D, 0x26A7}),                           // transgender flag
    fromCodePoints({0x1F468, 0x200D, 0x2764, 0x200D, 0x1F468}),          // couple with heart, men
    fromCodePoints({0x1F469, 0x200D, 0x2764, 0x200D, 0x1F469}),          // couple with heart, women
    fromCodePoints({0x1F468, 0x200D, 0x2764, 0x200D, 0x1F48B, 0x200D, 0x1F468}), // kiss, men
    fromCodePoints({0x1F469, 0x200D, 0x2764, 0x200D, 0x1F48B, 0x200D, 0x1F469}), // kiss, women
    fromCodePoints({0x1F468, 0x200D, 0x1F468}),                          // two fathers, any children
    fromCodePoints({0x1F469, 0x200D, 0x1F469}),                          // two mothers, any children
    fromCodePoints({0x1F468, 0x200D, 0x1F91D, 0x200D, 0x1F468}),         // men holding hands
    fromCodePoints({0x1F469, 0x200D, 0x1F91D, 0x200D, 0x1F469}),         // women holding hands
};

bool isSkinTone(uint codePoint)
{
    return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
}

/**
 * @brief What follows an emoji inside its sequence: the presentation selectors, a skin tone, the
 * keycap mark and the tag letters of a subdivision flag
 */
bool isSequenceExtender(uint codePoint)
{
    return codePoint == 0xFE0F || codePoint == 0xFE0E || codePoint == 0x20E3 || isSkinTone(codePoint)
           || (codePoint >= 0xE0020 && codePoint <= 0xE007F);
}

/**
 * @brief An emoji that can stand on either side of a joiner: the symbols and pictographs of the
 * BMP that have an emoji form (U+203C to U+3299: ☠, ❤, ♀, ⚕ ...), without the combining marks for
 * symbols (U+20D0 to U+20FF, the keycap mark among them), and everything from U+1F000 on
 */
bool isSequenceMember(uint codePoint)
{
    return (codePoint >= 0x203C && codePoint <= 0x3299 && !(codePoint >= 0x20D0 && codePoint <= 0x20FF))
           || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF && !isSkinTone(codePoint));
}

uint codePointAt(const QString& text, int position, int* length)
{
    const QChar first = text.at(position);
    if (first.isHighSurrogate() && position + 1 < text.size() && text.at(position + 1).isLowSurrogate()) {
        *length = 2;
        return QChar::surrogateToUcs4(first, text.at(position + 1));
    }
    *length = 1;
    return first.unicode();
}

int skipExtenders(const QString& text, int position)
{
    while (position < text.size()) {
        int length = 0;
        if (!isSequenceExtender(codePointAt(text, position, &length))) {
            break;
        }
        position += length;
    }
    return position;
}

bool hasSkinTone(const QString& text, int from, int to)
{
    for (int position = from; position < to;) {
        int length = 0;
        if (isSkinTone(codePointAt(text, position, &length))) {
            return true;
        }
        position += length;
    }
    return false;
}

/**
 * @brief Finds the joined emoji sequences of a text: an emoji, and one or more times a joiner and
 * an emoji, each with its extenders; and an emoji with a skin tone, which is one character too
 * @return The start and the end of each sequence
 * @note Found by the code points rather than by Qt's cluster rules: Qt 5.12 (the Windows build)
 * ends the character at a joiner that is followed by an emoji newer than Unicode 10.
 * @note KHANDAQ (QA live 2026-11-14): ✌🏽 left to the system font was the text font's ✌ and the
 * tone alone, a brown square: ✌ is drawn as text unless it is asked for as an emoji, and the pack
 * has no picture with a tone. In the emoji font it is one picture.
 */
QVector<QPair<int, int>> joinedSequences(const QString& text)
{
    QVector<QPair<int, int>> sequences;
    int position = 0;
    while (position < text.size()) {
        int length = 0;
        if (!isSequenceMember(codePointAt(text, position, &length))) {
            position += length;
            continue;
        }
        const int start = position;
        int end = skipExtenders(text, position + length);
        bool joined = hasSkinTone(text, position + length, end);
        while (end + 1 < text.size() && text.at(end) == ZERO_WIDTH_JOINER) {
            int memberLength = 0;
            if (!isSequenceMember(codePointAt(text, end + 1, &memberLength))) {
                break;
            }
            end = skipExtenders(text, end + 1 + memberLength);
            joined = true;
        }
        if (joined) {
            sequences.append({start, end});
        }
        position = end;
    }
    return sequences;
}

bool isBlockedSequence(const QString& sequence)
{
    QString bare;
    bare.reserve(sequence.size());
    for (int position = 0; position < sequence.size();) {
        int length = 0;
        const uint codePoint = codePointAt(sequence, position, &length);
        if (codePoint != 0xFE0F && !isSkinTone(codePoint)) {
            bare += sequence.mid(position, length);
        }
        position += length;
    }
    for (const QString& blocked : BLOCKED_SEQUENCES) {
        if (bare.contains(blocked)) {
            return true;
        }
    }
    return false;
}

/**
 * @brief The text with the joiners of the blocked sequences taken out, so that no font draws such
 * a sequence as one character and each member is a character of its own, with its picture where
 * the pack has one (🏳️‍🌈 is the font's white flag and the pack's rainbow, as before #95)
 * @note Only what is drawn changes: the history keeps the message as it came.
 */
QString withBlockedSequencesApart(const QString& text)
{
    const QVector<QPair<int, int>> sequences = joinedSequences(text);
    QString result(text);
    for (auto it = sequences.crbegin(); it != sequences.crend(); ++it) {
        const int start = it->first;
        const int end = it->second;
        if (!isBlockedSequence(text.mid(start, end - start))) {
            continue;
        }
        for (int position = end - 1; position >= start; --position) {
            if (result.at(position) == ZERO_WIDTH_JOINER) {
                result.remove(position, 1);
            }
        }
    }
    return result;
}

QString emojiFontFamily()
{
#if defined(Q_OS_MACOS)
    return QStringLiteral("Apple Color Emoji");
#elif defined(Q_OS_WIN)
    return QStringLiteral("Segoe UI Emoji");
#else
    return QStringLiteral("Noto Color Emoji");
#endif
}

/**
 * @brief Construct list of standard directories with "emoticons" sub dir, whether these directories
 * exist or not
 * @return Constructed list of default emoticons directories
 */
QStringList loadDefaultPaths()
{
#if defined(Q_OS_FREEBSD)
    // TODO: Remove when will be fixed.
    // Workaround to fix https://bugreports.qt.io/browse/QTBUG-57522
    setlocale(LC_ALL, "");
#endif
    const QString EMOTICONS_SUB_PATH = QDir::separator() + QStringLiteral("emoticons");
    QStringList paths{":/smileys", "~/.kde4/share/emoticons", "~/.kde/share/emoticons",
                      EMOTICONS_SUB_PATH};

    // qTox exclusive emoticons
    QStandardPaths::StandardLocation location;
    location = QStandardPaths::AppDataLocation;

    QStringList locations = QStandardPaths::standardLocations(location);
    // system wide emoticons
    locations.append(QStandardPaths::standardLocations(QStandardPaths::GenericDataLocation));
    for (QString qtoxPath : locations) {
        qtoxPath.append(EMOTICONS_SUB_PATH);
        if (!paths.contains(qtoxPath)) {
            paths.append(qtoxPath);
        }
    }

    return paths;
}

bool isAscii(const QString& string)
{
    constexpr auto asciiExtMask = 0x80;

    return (string.toUtf8()[0] & asciiExtMask) == 0;
}

/**
 * @brief Tells whether a key found in a message is the whole character the reader sees there
 * @param graphemes Grapheme cluster boundaries of the message
 * @param message The message the key was found in
 * @param start Where the key starts
 * @param end Where the key ends
 * @return True if the key is a grapheme cluster of its own, alone or followed by U+FE0F only
 */
bool isWholeCharacter(QTextBoundaryFinder& graphemes, const QString& message, int start, int end)
{
    const auto atBoundary = [&graphemes](int position) -> bool {
        graphemes.setPosition(position);
        return graphemes.isAtBoundary();
    };
    // KHANDAQ (QA 2026-11-14, F1): Qt 5.12, which the Windows build bundles, has the cluster rules
    // of Unicode 10: a joiner (U+200D) followed by an emoji that was not joinable then ends the
    // character, so the fire of the heart on fire (U+2764 U+FE0F U+200D U+1F525) looks like a
    // character of its own. An emoji after a joiner is a part of a sequence whatever the rules of
    // the Qt at hand say.
    if (!atBoundary(start) || (start > 0 && message.at(start - 1) == ZERO_WIDTH_JOINER)) {
        return false;
    }
    // KHANDAQ (QA 2026-11-14, F1): U+FE0F only asks for the colour picture of the emoji before it,
    // and the pack has that picture under the bare key (U+2764 U+FE0F is U+2764 there). The
    // selector stays in the text after the picture, as it always has, so a copy keeps what the
    // sender typed.
    if (end < message.size() && message.at(end) == EMOJI_PRESENTATION) {
        ++end;
    }
    return atBoundary(end);
}

} // namespace

SmileyPack::SmileyPack(ISmileySettings& settings_)
    : cleanupTimer{new QTimer(this)}
    , settings{settings_}
{
    loadingMutex.lock();
    QtConcurrent::run(this, &SmileyPack::load, settings.getSmileyPack());
    settings.connectTo_smileyPackChanged(this,
        [&](const QString&) { onSmileyPackChanged(); });
    connect(cleanupTimer, &QTimer::timeout, this, &SmileyPack::cleanupIconsCache);
    cleanupTimer->start(CLEANUP_TIMEOUT);
}

SmileyPack::~SmileyPack()
{
    delete cleanupTimer;
}

/**
 * @brief Wraps passed string into smiley HTML image reference
 * @param key Describes which smiley is needed
 * @return Key that wrapped into image ref
 */
QString SmileyPack::getAsRichText(const QString& key)
{
    return RICH_TEXT_PATTERN.arg(key);
}

/**
 * @brief A joined emoji sequence the pack has no picture of, set in the colour emoji font of the
 * system
 *
 * KHANDAQ (QA 2026-11-14): left to the font of the message, Qt 5 took the members that have a text
 * glyph (☠ of the pirate flag U+1F3F4 U+200D U+2620 U+FE0F, ❤ of the heart on fire, ♀ and ⚕ of the
 * people) from another font than the emoji before the joiner, and the sequence fell apart into a
 * flag and a skull. Set in one emoji font, it is one picture.
 */
QString SmileyPack::getInEmojiFont(const QString& sequence)
{
    return EMOJI_FONT_PATTERN.arg(emojiFontFamily(), sequence);
}

void SmileyPack::cleanupIconsCache()
{
    QMutexLocker locker(&loadingMutex);
    for (auto it = cachedIcon.begin(); it != cachedIcon.end();) {
        std::shared_ptr<QIcon>& icon = it->second;
        if (icon.use_count() == 1) {
            it = cachedIcon.erase(it);
        } else {
            ++it;
        }
    }
}

/**
 * @brief Does the same as listSmileyPaths, but with default paths
 */
QList<QPair<QString, QString>> SmileyPack::listSmileyPacks()
{
    return listSmileyPacks(DEFAULT_PATHS);
}

/**
 * @brief Searches all files called "emoticons.xml" within the every passed path in the depth of 2
 * @param paths Paths where to search for file
 * @return Vector of pairs: {directoryName, absolutePathToFile}
 */
QList<QPair<QString, QString>> SmileyPack::listSmileyPacks(const QStringList& paths)
{
    QList<QPair<QString, QString>> smileyPacks;
    const QString homePath = QDir::homePath();
    for (QString path : paths) {
        if (path.startsWith('~')) {
            path.replace(0, 1, homePath);
        }

        QDir dir(path);
        if (!dir.exists()) {
            continue;
        }

        for (const QString& subdirectory : dir.entryList(QDir::Dirs | QDir::NoDotAndDotDot)) {
            dir.cd(subdirectory);
            if (dir.exists(EMOTICONS_FILE_NAME)) {
                QString absPath = dir.absolutePath() + QDir::separator() + EMOTICONS_FILE_NAME;
                QPair<QString, QString> p{dir.dirName(), absPath};
                if (!smileyPacks.contains(p)) {
                    smileyPacks.append(p);
                }
            }

            dir.cdUp();
        }
    }

    return smileyPacks;
}

/**
 * @brief Load smile pack
 * @note The caller must lock loadingMutex and should run it in a thread
 * @param filename Filename of smilepack.
 * @return False if cannot open file, true otherwise.
 */
bool SmileyPack::load(const QString& filename)
{
    QFile xmlFile(filename);
    if (!xmlFile.exists() || !xmlFile.open(QIODevice::ReadOnly)) {
        loadingMutex.unlock();
        return false;
    }

    QDomDocument doc;
    doc.setContent(xmlFile.readAll());
    xmlFile.close();

    /* parse the cfg file
     * sample:
     * <?xml version='1.0'?>
     * <messaging-emoticon-map>
     *   <emoticon file="smile.png" >
     *       <string>:)</string>
     *       <string>:-)</string>
     *   </emoticon>
     *   <emoticon file="sad.png" >
     *       <string>:(</string>
     *       <string>:-(</string>
     *   </emoticon>
     * </messaging-emoticon-map>
     */

    path = QFileInfo(filename).absolutePath();
    QDomNodeList emoticonElements = doc.elementsByTagName("emoticon");
    const QString itemName = QStringLiteral("file");
    const QString childName = QStringLiteral("string");
    const int iconsCount = emoticonElements.size();
    emoticons.clear();
    emoticonToPath.clear();
    cachedIcon.clear();

    for (int i = 0; i < iconsCount; ++i) {
        QDomNode node = emoticonElements.at(i);
        QString iconName = node.attributes().namedItem(itemName).nodeValue();
        QString iconPath = QDir{path}.filePath(iconName);
        QDomElement stringElement = node.firstChildElement(childName);
        QStringList emoticonList;
        while (!stringElement.isNull()) {
            QString emoticon = stringElement.text().replace("<", "&lt;").replace(">", "&gt;");
            emoticonToPath.insert(emoticon, iconPath);
            emoticonList.append(emoticon);
            stringElement = stringElement.nextSibling().toElement();
        }

        emoticons.append(emoticonList);
    }

    constructRegex();

    loadingMutex.unlock();
    return true;
}

/**
 * @brief Creates the regex for replacing emoticons with the path to their pictures
 */
void SmileyPack::constructRegex()
{
    QString allPattern = QStringLiteral("(");
    QString regularPatterns;
    QString multiCharacterEmojiPatterns;

    // construct one big regex that matches on every emoticon
    for (const QString& emote : emoticonToPath.keys()) {
        if (!isAscii(emote)) {
            if (emote.toUcs4().length() == 1) {
                regularPatterns.append(emote);
                regularPatterns.append(QStringLiteral("|"));
            }
            else {
                multiCharacterEmojiPatterns.append(emote);
                multiCharacterEmojiPatterns.append(QStringLiteral("|"));
            }
        } else {
            // patterns like ":)" or ":smile:", don't match inside a word or else will hit punctuation and html tags
            regularPatterns.append(QStringLiteral(R"((?<=^|\s))") % QRegularExpression::escape(emote) % QStringLiteral(R"((?=$|\s))"));
            regularPatterns.append(QStringLiteral("|"));
        }
    }

    // Regexps are evaluated from left to right, insert multichar emojis first so they are evaluated first
    allPattern.append(multiCharacterEmojiPatterns);
    allPattern.append(regularPatterns);
    allPattern[allPattern.size() - 1] = QChar(')');

    // compile and optimize regex
    smilify.setPattern(allPattern);
    smilify.optimize();
}

/**
 * @brief Replaces all found text emoticons to HTML reference with its according icon filename
 * @param message Message where to search for emoticons
 * @return Formatted copy of message
 * @note An emoji that is only a part of a character, such as 👍 of 👍🏽, stays text.
 * @note A joined sequence the pack has no picture of is set in the emoji font of the system
 * (getInEmojiFont), except the sequences the picker does not offer, which are drawn apart.
 */
QString SmileyPack::smileyfied(const QString& message)
{
    QMutexLocker locker(&loadingMutex);
    // KHANDAQ (owner, 2026-11-14; QA live F14): the sequences the picker does not offer are drawn
    // apart. Done before anything else, so that their members are characters of their own below.
    const QString msg = withBlockedSequencesApart(message);
    QString result(msg);

    // The pictures and the sequences set in the emoji font, by where they start in msg.
    QMap<int, QPair<int, QString>> replacements;
    for (const auto& sequence : joinedSequences(msg)) {
        replacements.insert(sequence.first,
                            {sequence.second - sequence.first,
                             getInEmojiFont(msg.mid(sequence.first, sequence.second - sequence.first))});
    }

    QRegularExpressionMatchIterator iter = smilify.globalMatch(msg);
    // KHANDAQ (QA 2026-11-14, F1): a key of the pack can be a part of a longer character: 👍 of 👍🏽,
    // the letters 🇹 and 🇷 of the flag 🇹🇷, 👨 of the family U+1F468 U+200D U+1F469 U+200D U+1F467.
    // Replacing such a part with its picture left the rest of the character alone (the skin tone as
    // a brown square, the flag as two letters in circles). A part stays text, and the system font
    // draws the whole character. A key that is the whole character still gets its picture, the
    // longest key first as before: the regex tries the keys of several code points (the flags)
    // before the others.
    QTextBoundaryFinder graphemes(QTextBoundaryFinder::Grapheme, msg);
    while (iter.hasNext()) {
        QRegularExpressionMatch match = iter.next();
        int startPos = match.capturedStart();
        int keyLength = match.capturedLength();
        if (!isWholeCharacter(graphemes, msg, startPos, startPos + keyLength)) {
            continue;
        }
        // A key inside a joined sequence is never a whole character; this keeps the two apart
        // even where Qt's cluster rules and the sequences found above disagree.
        const auto next = replacements.lowerBound(startPos);
        const auto previous = next == replacements.begin() ? replacements.end() : std::prev(next);
        if ((next != replacements.end() && next.key() < startPos + keyLength)
            || (previous != replacements.end() && previous.key() + previous.value().first > startPos)) {
            continue;
        }
        replacements.insert(startPos, {keyLength, SmileyPack::getAsRichText(match.captured())});
    }

    // From the end, so that the positions before each replacement stay where they were.
    for (auto it = replacements.constEnd(); it != replacements.constBegin();) {
        --it;
        result.replace(it.key(), it.value().first, it.value().second);
    }
    return result;
}

/**
 * @brief Returns all emoticons that was extracted from files, grouped by according icon file
 */
QList<QStringList> SmileyPack::getEmoticons() const
{
    QMutexLocker locker(&loadingMutex);
    return emoticons;
}

/**
 * @brief Gets icon accoring to passed emoticon
 * @param emoticon Passed emoticon
 * @return Returns cached icon according to passed emoticon, null if no icon mapped to this emoticon
 */
std::shared_ptr<QIcon> SmileyPack::getAsIcon(const QString& emoticon) const
{
    QMutexLocker locker(&loadingMutex);
    if (cachedIcon.find(emoticon) != cachedIcon.end()) {
        return cachedIcon[emoticon];
    }

    const auto iconPathIt = emoticonToPath.find(emoticon);
    if (iconPathIt == emoticonToPath.end()) {
        return std::make_shared<QIcon>();
    }

    const QString& iconPath = iconPathIt.value();
    auto icon = std::make_shared<QIcon>(iconPath);
    cachedIcon[emoticon] = icon;
    return icon;
}

void SmileyPack::onSmileyPackChanged()
{
    loadingMutex.lock();
    QtConcurrent::run(this, &SmileyPack::load, settings.getSmileyPack());
}
