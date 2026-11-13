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

#include "airpreferences.h"

#include <QDir>
#include <QLocale>

namespace Air {

AttachmentMode attachmentMode(bool autoSave, qint64 maxSize)
{
    if (!autoSave) {
        return AttachmentMode::Never;
    }
    return maxSize > 0 ? AttachmentMode::Small : AttachmentMode::Always;
}

AttachmentRule attachmentRule(AttachmentMode mode, qint64 previousMaxSize)
{
    AttachmentRule rule;
    switch (mode) {
    case AttachmentMode::Always:
        rule.autoSave = true;
        rule.maxSize = 0;
        break;
    case AttachmentMode::Small:
        rule.autoSave = true;
        rule.maxSize = previousMaxSize > 0 ? previousMaxSize : smallAttachmentDefault;
        break;
    case AttachmentMode::Never:
        // The limit stays for the day Small is chosen again.
        rule.autoSave = false;
        rule.maxSize = previousMaxSize > 0 ? previousMaxSize : 0;
        break;
    }
    return rule;
}

QString autoAcceptFolder(const QString& contactFolder, bool autoSave, const QString& globalFolder,
                         const QString& downloads, qint64 maxSize, qint64 fileSize)
{
    if (maxSize > 0 && fileSize > maxSize) {
        return QString();
    }
    if (!contactFolder.isEmpty()) {
        return contactFolder;
    }
    if (!autoSave) {
        return QString();
    }
    return globalFolder.isEmpty() ? downloads : globalFolder;
}

QString folderLabel(const QString& folder, const QString& downloads, const QString& downloadsName,
                    const QString& home, bool tildeForHome)
{
    const QString chosen = QDir::cleanPath(folder);
    if (folder.isEmpty() || chosen == QDir::cleanPath(downloads)) {
        return downloadsName;
    }
    const QString base = home.isEmpty() ? QString() : QDir::cleanPath(home);
    QString shown = chosen;
    if (tildeForHome && !base.isEmpty() && base != QLatin1String("/")
        && (chosen == base || chosen.startsWith(base + QLatin1Char('/')))) {
        shown = QLatin1Char('~') + chosen.mid(base.length());
    }
    return QDir::toNativeSeparators(shown);
}

QStringList hiddenKeys(const QStringList& blackList)
{
    QStringList keys;
    for (const QString& entry : blackList) {
        const QString key = entry.trimmed();
        if (!key.isEmpty() && !keys.contains(key)) {
            keys.append(key);
        }
    }
    return keys;
}

QStringList withoutHiddenKey(const QStringList& blackList, const QString& key)
{
    const QString wanted = key.trimmed();
    QStringList kept;
    for (const QString& entry : blackList) {
        if (entry.trimmed() != wanted) {
            kept.append(entry);
        }
    }
    return kept;
}

QStringList interfaceLanguages()
{
    // The translations the build carries (translations/translations.qrc), as the old General
    // tab listed them.
    // clang-format off
    return {
        QStringLiteral("ar"), QStringLiteral("be"), QStringLiteral("ber"), QStringLiteral("bg"),
        QStringLiteral("cs"), QStringLiteral("da"), QStringLiteral("de"), QStringLiteral("el"),
        QStringLiteral("en"), QStringLiteral("eo"), QStringLiteral("es"), QStringLiteral("et"),
        QStringLiteral("fa"), QStringLiteral("fi"), QStringLiteral("fr"), QStringLiteral("gl"),
        QStringLiteral("he"), QStringLiteral("hr"), QStringLiteral("hu"), QStringLiteral("is"),
        QStringLiteral("it"), QStringLiteral("ja"), QStringLiteral("jbo"), QStringLiteral("kn"),
        QStringLiteral("ko"), QStringLiteral("lt"), QStringLiteral("mk"), QStringLiteral("nl"),
        QStringLiteral("no_nb"), QStringLiteral("pl"), QStringLiteral("pr"), QStringLiteral("pt"),
        QStringLiteral("pt_BR"), QStringLiteral("ro"), QStringLiteral("ru"), QStringLiteral("si"),
        QStringLiteral("sk"), QStringLiteral("sl"), QStringLiteral("sq"), QStringLiteral("sr"),
        QStringLiteral("sr_Latn"), QStringLiteral("sv"), QStringLiteral("sw"), QStringLiteral("ta"),
        QStringLiteral("tr"), QStringLiteral("ug"), QStringLiteral("uk"), QStringLiteral("ur"),
        QStringLiteral("zh_CN"), QStringLiteral("zh_TW"),
    };
    // clang-format on
}

QString languageName(const QString& locale)
{
    // Qt names some of them in English (en as "American English"), with a country nobody chose
    // (es as "español de España"), the same as another (pt and pt_BR) or not at all (ber, jbo,
    // pr; QTBUG-57802, QTBUG-47891). The pt translation is the one of Portugal.
    const struct
    {
        const char* locale;
        const char16_t* name;
    } own[] = {
        {"ber", u"ⵜⴰⵎⴰⵣⵉⵖⵜ"},
        {"en", u"English"},
        {"eo", u"Esperanto"},
        {"es", u"Español"},
        {"jbo", u"Lojban"},
        {"pr", u"Pirate"},
        {"pt", u"Português (Portugal)"},
        {"pt_BR", u"Português (Brasil)"},
    };
    for (const auto& entry : own) {
        if (locale == QLatin1String(entry.locale)) {
            return QString::fromUtf16(entry.name);
        }
    }
    const QString name = QLocale(locale).nativeLanguageName();
    if (name.isEmpty()) {
        return locale;
    }
    // A language names itself with a capital where its script has one ("Deutsch", not "deutsch").
    return QLocale(locale).toUpper(name.left(1)) + name.mid(1);
}

} // namespace Air
