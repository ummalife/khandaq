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
#include <QSet>
#include <QStandardPaths>
#include <QStringBuilder>
#include <QTextBoundaryFinder>
#include <QVector>
#include <QtConcurrent/QtConcurrentRun>
#include <QTimer>

#include <algorithm>
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
const QChar COMBINING_ENCLOSING_KEYCAP{0x20E3};

const QString EMOJI_FONT_PATTERN = QStringLiteral("<span style=\"font-family:'%1'\">%2</span>");
const QString EMOJI_FONT_SIZED_PATTERN =
    QStringLiteral("<span style=\"font-family:'%1'; font-size:%2px\">%3</span>");
// The line breaks and the runs of spaces of a plain text, kept in rich text.
const QString PLAIN_TEXT_PATTERN = QStringLiteral("<span style=\"white-space:pre-wrap\">%1</span>");

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

// KHANDAQ (owner, 2026-11-14): the single emoji that Android's picker does not offer either
// (KhandaqEmojiFilter BLOCKED_SYMBOLS, BLOCKED_CREATURES and BLOCKED_PAIRS, the same request of
// 17 Aug 2026): symbols of other religions and of the occult, devils and the like, same-sex pairs.
// The desktop's packs had ⛪, the zodiac, 🔯, 👻 👽 👾 👹 👺 😈 👿 and 👬 👭 in the picker. Only the
// picker leaves them out: a message that carries one shows it as before. Compared without U+FE0F
// and the skin tones, like BLOCKED_SEQUENCES. Keep in step with KhandaqEmojiFilter.java.
const QSet<uint> BLOCKED_SINGLES = {
    0x271D,  // latin cross
    0x2626,  // orthodox cross
    0x2721,  // star of David
    0x1F52F, // six-pointed star with dot
    0x1F54E, // menorah
    0x1F549, // om
    0x2638,  // wheel of dharma
    0x262F,  // yin yang
    0x1F6D0, // place of worship
    0x26EA,  // church
    0x1F54D, // synagogue
    0x1F6D5, // hindu temple
    0x26E9,  // shinto shrine
    0x2648, 0x2649, 0x264A, 0x264B, 0x264C, 0x264D, // zodiac, aries to virgo
    0x264E, 0x264F, 0x2650, 0x2651, 0x2652, 0x2653, // zodiac, libra to pisces
    0x26CE,  // ophiuchus
    0x1F47F, // angry face with horns
    0x1F608, // smiling face with horns
    0x1F479, // ogre
    0x1F47A, // goblin
    0x1F47B, // ghost
    0x1F47D, // alien
    0x1F47E, // alien monster
    0x1F46C, // two men holding hands
    0x1F46D, // two women holding hands
    0x26A7,  // transgender symbol
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

bool isRegionalIndicator(uint codePoint)
{
    return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
}

bool isKeycapBase(uint codePoint)
{
    return (codePoint >= '0' && codePoint <= '9') || codePoint == '#' || codePoint == '*';
}

/**
 * @brief Whether the system's emoji font draws flags and keycaps
 * @note KHANDAQ (QA live 2026-11-14, Windows): Segoe UI Emoji has no flags, so 🇹🇷 stays the
 * letters T and R in it, and Qt 5 drew 1️⃣ in it as the 1 and the keycap's frame side by side, the
 * frame over the next character. The text font draws the 1 inside its frame. On Windows both stay
 * in the text font, as before the emoji font took them on the Mac and Linux.
 */
constexpr bool emojiFontDrawsFlagsAndKeycaps()
{
#if defined(Q_OS_WIN)
    return false;
#else
    return true;
#endif
}

/**
 * @brief Finds the flags (two regional indicator letters, paired from the start of a run) and the
 * keycaps (a digit, # or *, U+FE0F if any and U+20E3) of a text that the emoji font is to draw
 * @return The start and the end of each; none where the emoji font does not draw them
 * @note KHANDAQ (QA live 2026-11-14): left to the system font, 🇹🇷 was as big as the text next to
 * the pack's 🇷🇺, and 1️⃣ was the text font's 1 in a frame.
 */
QVector<QPair<int, int>> flagsAndKeycaps(const QString& text)
{
    QVector<QPair<int, int>> characters;
    if (!emojiFontDrawsFlagsAndKeycaps()) {
        return characters;
    }
    int position = 0;
    while (position < text.size()) {
        int length = 0;
        const uint codePoint = codePointAt(text, position, &length);
        int end = position + length;
        if (isRegionalIndicator(codePoint) && end < text.size()) {
            int nextLength = 0;
            if (isRegionalIndicator(codePointAt(text, end, &nextLength))) {
                characters.append({position, end + nextLength});
                position = end + nextLength;
                continue;
            }
        } else if (isKeycapBase(codePoint)) {
            if (end < text.size() && text.at(end) == EMOJI_PRESENTATION) {
                ++end;
            }
            if (end < text.size() && text.at(end) == COMBINING_ENCLOSING_KEYCAP) {
                characters.append({position, end + 1});
                position = end + 1;
                continue;
            }
        }
        position += length;
    }
    return characters;
}

/**
 * @brief The emoji of a text that the system's emoji font draws: its joined sequences, flags and
 * keycaps, by where they start
 */
QVector<QPair<int, int>> emojiCharacters(const QString& text)
{
    QVector<QPair<int, int>> characters = joinedSequences(text);
    for (const auto& character : flagsAndKeycaps(text)) {
        const bool inSequence = std::any_of(
            characters.cbegin(), characters.cend(), [&character](const QPair<int, int>& sequence) {
                return character.first < sequence.second && sequence.first < character.second;
            });
        if (!inSequence) {
            characters.append(character);
        }
    }
    std::sort(characters.begin(), characters.end());
    return characters;
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
 * @brief Whether the text is one of BLOCKED_SINGLES, in any spelling and any skin tone
 */
bool isBlockedSingle(const QString& emoticon)
{
    uint single = 0;
    int count = 0;
    for (int position = 0; position < emoticon.size();) {
        int length = 0;
        const uint codePoint = codePointAt(emoticon, position, &length);
        position += length;
        if (codePoint == 0xFE0F || isSkinTone(codePoint)) {
            continue;
        }
        single = codePoint;
        if (++count > 1) {
            return false;
        }
    }
    return count == 1 && BLOCKED_SINGLES.contains(single);
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
 * @param pixelSize The size of the pack's pictures around it; 0 leaves it the size of the text
 * around it, as a plain text that has no pictures has it
 * @note KHANDAQ (QA live 2026-11-14): at the size of the text, a sequence was half as big as the
 * pictures of the pack in the same message (24 px by default): 👍🏽 next to 👍.
 */
QString SmileyPack::getInEmojiFont(const QString& sequence, int pixelSize)
{
    if (pixelSize <= 0) {
        return EMOJI_FONT_PATTERN.arg(emojiFontFamily(), sequence);
    }
    return EMOJI_FONT_SIZED_PATTERN.arg(emojiFontFamily(), QString::number(pixelSize), sequence);
}

/**
 * @brief The colour emoji font of the system
 */
QString SmileyPack::emojiFontFamily()
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
 * @brief Where a plain text has emoji to set in the emoji font: its joined sequences but those the
 * picker does not offer, which are left to be drawn apart, its flags and its keycaps
 * @param text A plain text
 * @return The start and the end of each
 * @note For a text that stays as it is, such as the one being typed; a text shown as rich text is
 * set by plainTextAsRichText().
 */
QVector<QPair<int, int>> SmileyPack::emojiFontRanges(const QString& text)
{
    QVector<QPair<int, int>> ranges;
    for (const auto& sequence : emojiCharacters(text)) {
        if (!isBlockedSequence(text.mid(sequence.first, sequence.second - sequence.first))) {
            ranges.append(sequence);
        }
    }
    return ranges;
}

/**
 * @brief A plain text as rich text, its joined sequences in the emoji font at the size of the text
 * and the sequences the picker does not offer drawn apart, as a message shows them
 * @param text A plain text, as typed
 * @return The text escaped, its line breaks and spaces kept
 * @note KHANDAQ (QA live 2026-11-14): a QLabel with the plain text drew 👨‍⚕️ as 👨 and ⚕, as the
 * message itself was before getInEmojiFont().
 */
QString SmileyPack::plainTextAsRichText(const QString& text)
{
    const QString apart = withBlockedSequencesApart(text);
    QString html;
    int position = 0;
    for (const auto& sequence : emojiCharacters(apart)) {
        html += apart.mid(position, sequence.first - position).toHtmlEscaped();
        html += getInEmojiFont(
            apart.mid(sequence.first, sequence.second - sequence.first).toHtmlEscaped(), 0);
        position = sequence.second;
    }
    html += apart.mid(position).toHtmlEscaped();
    return PLAIN_TEXT_PATTERN.arg(html);
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

    // The pictures and the sequences set in the emoji font, by where they start in msg. The
    // sequences are as big as the pictures.
    const int pictureSize = settings.getEmojiFontPointSize();
    QMap<int, QPair<int, QString>> replacements;
    for (const auto& sequence : joinedSequences(msg)) {
        const int length = sequence.second - sequence.first;
        replacements.insert(sequence.first,
                            {length, getInEmojiFont(msg.mid(sequence.first, length), pictureSize)});
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

    // A flag or a keycap the pack has no picture of is set in the emoji font too, as big as the
    // pictures; one the pack has keeps its picture.
    for (const auto& character : flagsAndKeycaps(msg)) {
        const auto next = replacements.lowerBound(character.first);
        const auto previous = next == replacements.begin() ? replacements.end() : std::prev(next);
        if ((next != replacements.end() && next.key() < character.second)
            || (previous != replacements.end()
                && previous.key() + previous.value().first > character.first)) {
            continue;
        }
        const int length = character.second - character.first;
        const QString inFont = getInEmojiFont(msg.mid(character.first, length), pictureSize);
        replacements.insert(character.first, {length, inFont});
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
 * @brief Whether the picker offers this emoticon, given with all of its spellings
 * @note The same as Android's picker (KhandaqEmojiFilter): no single emoji of BLOCKED_SINGLES and
 * no joined sequence of BLOCKED_SEQUENCES, in any spelling and any skin tone.
 */
bool SmileyPack::isOfferedInPicker(const QStringList& emoticonSet)
{
    for (const QString& emoticon : emoticonSet) {
        if (isBlockedSingle(emoticon) || isBlockedSequence(emoticon)) {
            return false;
        }
    }
    return true;
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
