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

#include "updatemanifest.h"

#include "src/net/sshsig.h"

#include <QDebug>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonParseError>
#include <QJsonValue>
#include <QRegularExpression>

#include <cmath>

namespace {
constexpr char productionManifest[] = "https://khandaq.org/downloads/khandaq-desktop-update.json";
constexpr char notesHost[] = "khandaq.org";

/**
 * The public half of the release signing key, as the base64 blob of
 * web/downloads/KHANDAQ-RELEASE-SIGNING.pub. scripts/check-desktop-update-key.py holds this line to
 * that file, so the app can only ever trust the key the releases are signed with.
 */
constexpr char releaseKeyBlob[] = "AAAAC3NzaC1lZDI1NTE5AAAAIDheQRznnRA4AQiY08DM/G90vhYEIC82Nfdwig4S4aGk";

const QMap<QString, QString>& platformFiles()
{
    // The names of the downloads on khandaq.org and in the GitHub release.
    static const QMap<QString, QString> files{
        {QStringLiteral("windows-x86_64-installer"), QStringLiteral("khandaq-windows-installer.exe")},
        {QStringLiteral("windows-x86_64-zip"), QStringLiteral("khandaq-windows-x86_64.zip")},
        {QStringLiteral("macos-zip"), QStringLiteral("khandaq-macos.zip")},
        {QStringLiteral("linux-deb-amd64"), QStringLiteral("khandaq-messenger_amd64.deb")},
        {QStringLiteral("linux-portable-x86_64"),
         QStringLiteral("khandaq-linux-x86_64-portable.tar.gz")},
    };
    return files;
}

bool refuse(QString& error, const QString& why)
{
    error = why;
    return false;
}

bool readNotes(const QJsonValue& value, QUrl& out)
{
    if (!value.isString()) {
        return false;
    }
    const QUrl url(value.toString(), QUrl::StrictMode);
    if (!url.isValid() || url.scheme() != QLatin1String("https")
        || url.host() != QLatin1String(notesHost) || !url.userInfo().isEmpty() || url.port() != -1) {
        return false;
    }
    out = url;
    return true;
}

bool readEntry(const QJsonValue& value, const QString& platform, UpdateManifest::Entry& out,
               QString& error)
{
    if (!value.isObject()) {
        return refuse(error, platform + QStringLiteral(": not an object"));
    }
    const QJsonObject object = value.toObject();
    UpdateManifest::Entry entry;

    const QJsonValue version = object.value(QStringLiteral("version"));
    if (!version.isString() || version.toString().startsWith(QLatin1Char('v'))
        || !UpdateManifest::parseVersion(version.toString(), entry.version)) {
        return refuse(error, platform + QStringLiteral(": no version"));
    }

    const QJsonValue file = object.value(QStringLiteral("file"));
    if (!file.isString() || file.toString() != UpdateManifest::fileFor(platform)) {
        return refuse(error, platform + QStringLiteral(": not the file of this platform"));
    }
    entry.file = file.toString();

    // JSON numbers are doubles: a size is a whole number of bytes, at least one, within the limit.
    const QJsonValue size = object.value(QStringLiteral("size"));
    const double bytes = size.toDouble(-1);
    if (!size.isDouble() || !(bytes >= 1)
        || bytes > static_cast<double>(UpdateManifest::artifactLimit)) {
        return refuse(error, platform + QStringLiteral(": no size"));
    }
    entry.size = static_cast<qint64>(bytes);
    if (static_cast<double>(entry.size) != bytes) {
        return refuse(error, platform + QStringLiteral(": no size"));
    }

    static const QRegularExpression sha256Hex(QStringLiteral("\\A[0-9a-f]{64}\\z"));
    const QJsonValue sha256 = object.value(QStringLiteral("sha256"));
    if (!sha256.isString() || !sha256Hex.match(sha256.toString()).hasMatch()) {
        return refuse(error, platform + QStringLiteral(": no sha256"));
    }
    entry.sha256 = QByteArray::fromHex(sha256.toString().toLatin1());

    out = entry;
    return true;
}
} // namespace

namespace UpdateManifest {

bool parseVersion(const QString& text, Version& out)
{
    // \A and \z rather than ^ and $, which would let a final newline through.
    static const QRegularExpression shape(QStringLiteral(
        "\\Av?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\z"));
    const QRegularExpressionMatch match = shape.match(text);
    if (!match.hasMatch()) {
        return false;
    }
    Version version;
    version.major = match.captured(1).toUInt();
    version.minor = match.captured(2).toUInt();
    version.patch = match.captured(3).toUInt();
    out = version;
    return true;
}

QString toString(const Version& version)
{
    return QString::number(version.major) + QLatin1Char('.') + QString::number(version.minor)
           + QLatin1Char('.') + QString::number(version.patch);
}

int compare(const Version& a, const Version& b)
{
    if (a.major != b.major) {
        return a.major < b.major ? -1 : 1;
    }
    if (a.minor != b.minor) {
        return a.minor < b.minor ? -1 : 1;
    }
    if (a.patch != b.patch) {
        return a.patch < b.patch ? -1 : 1;
    }
    return 0;
}

QStringList platformKeys()
{
    return platformFiles().keys();
}

QString fileFor(const QString& platform)
{
    return platformFiles().value(platform);
}

bool parse(const QByteArray& json, Release& out, QString& error)
{
    QJsonParseError parseError;
    const QJsonDocument document = QJsonDocument::fromJson(json, &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject()) {
        return refuse(error, QStringLiteral("not a JSON object"));
    }
    const QJsonObject root = document.object();
    const QJsonValue named = root.value(QStringLiteral("schema"));
    if (!named.isString() || named.toString() != QLatin1String(schema)) {
        return refuse(error, QStringLiteral("not the schema %1").arg(QLatin1String(schema)));
    }

    Release release;
    // A double, as every JSON number: a whole one, at least 1, that a double holds exactly.
    const QJsonValue serial = root.value(QStringLiteral("serial"));
    const double serialNumber = serial.toDouble(-1);
    if (!serial.isDouble() || !(serialNumber >= 1)
        || serialNumber > static_cast<double>(serialLimit)
        || std::floor(serialNumber) != serialNumber) {
        return refuse(error, QStringLiteral("no serial"));
    }
    release.serial = static_cast<qint64>(serialNumber);

    const QJsonValue notes = root.value(QStringLiteral("notesUrl"));
    if (!notes.isObject()
        || !readNotes(notes.toObject().value(QStringLiteral("en")), release.notesEnglish)
        || !readNotes(notes.toObject().value(QStringLiteral("ru")), release.notesRussian)) {
        return refuse(error, QStringLiteral("no release notes on khandaq.org"));
    }

    const QJsonValue platforms = root.value(QStringLiteral("platforms"));
    if (!platforms.isObject()) {
        return refuse(error, QStringLiteral("no platforms"));
    }
    const QJsonObject byPlatform = platforms.toObject();
    for (const QString& platform : platformKeys()) {
        if (!byPlatform.contains(platform)) {
            continue;
        }
        Entry entry;
        if (!readEntry(byPlatform.value(platform), platform, entry, error)) {
            return false;
        }
        release.platforms.insert(platform, entry);
    }

    out = release;
    return true;
}

QUrl notesFor(const Release& release, const QString& language)
{
    return language.startsWith(QLatin1String("ru")) ? release.notesRussian : release.notesEnglish;
}

Running running(const QString& describeExact, const QString& describe)
{
    Running build;
    if (describeExact.startsWith(QLatin1Char('v')) && parseVersion(describeExact, build.version)) {
        build.known = true;
        build.release = true;
        return build;
    }
    // Off a tag, "v0.2.64-3-gabc1234", or from a tree with changes not committed, "v0.2.64-dirty"
    // and "v0.2.64-3-gabc1234-dirty": the version it was built after. Never a release.
    static const QRegularExpression afterTag(
        QStringLiteral("\\A(v[0-9]+\\.[0-9]+\\.[0-9]+)(?:-[0-9]+-g[0-9a-f]+)?(?:-dirty)?\\z"));
    const QRegularExpressionMatch match = afterTag.match(describe);
    build.known = match.hasMatch() && parseVersion(match.captured(1), build.version);
    return build;
}

Decision decide(const Release& release, const QString& platform, const Version& current,
                const Version& highestSeen)
{
    Decision decision;
    decision.highestSeen = highestSeen;
    const auto found = release.platforms.constFind(platform);
    if (found == release.platforms.constEnd()) {
        return decision;
    }
    const Entry& entry = found.value();
    if (compare(entry.version, highestSeen) > 0) {
        decision.highestSeen = entry.version;
    }
    decision.offer = compare(entry.version, current) > 0 && compare(entry.version, highestSeen) >= 0;
    if (decision.offer) {
        decision.entry = entry;
    }
    return decision;
}

} // namespace UpdateManifest

namespace UpdateEndpoint {

bool qaBuild()
{
    return UPDATE_CHECK_QA != 0;
}

bool acceptsQaUrl(const QUrl& url)
{
    if (!url.isValid() || !url.userInfo().isEmpty() || url.host().isEmpty()) {
        return false;
    }
    if (url.scheme() == QLatin1String("https")) {
        return true;
    }
    return url.scheme() == QLatin1String("http") && url.host() == QLatin1String("127.0.0.1");
}

QUrl manifestUrl()
{
#if UPDATE_CHECK_QA
    const QString asked = qEnvironmentVariable(qaUrlVariable);
    if (!asked.isEmpty()) {
        const QUrl url(asked, QUrl::StrictMode);
        if (acceptsQaUrl(url)) {
            return url;
        }
        qWarning() << "Update check: the QA manifest address is neither https nor http://127.0.0.1;"
                   << "using the release one";
    }
#endif
    return QUrl(QLatin1String(productionManifest));
}

QUrl signatureUrl(const QUrl& manifest)
{
    QUrl url = manifest;
    url.setPath(manifest.path() + QStringLiteral(".sig"));
    return url;
}

QUrl artifactUrl(const QUrl& manifest, const QString& file)
{
    return manifest.resolved(QUrl(file));
}

QVector<QByteArray> trustedKeys()
{
    QVector<QByteArray> keys{SshSig::ed25519Key(QByteArray(releaseKeyBlob))};
#if UPDATE_CHECK_QA
    keys.append(SshSig::ed25519Key(QByteArray(UPDATE_CHECK_QA_KEY)));
#endif
    keys.removeAll(QByteArray());
    return keys;
}

} // namespace UpdateEndpoint
