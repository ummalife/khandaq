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

#include <QByteArray>
#include <QMap>
#include <QString>
#include <QStringList>
#include <QUrl>
#include <QVector>

/**
 * KHANDAQ (0.2.65): the signed list of desktop releases the app updates from.
 *
 * scripts/generate-desktop-update-manifest.py writes https://khandaq.org/downloads/
 * khandaq-desktop-update.json from the artifacts built on the release machine, and the release key
 * signs it in its own namespace. It names, for each kind of desktop download, the version inside it,
 * its file, its size and its SHA-256. Each platform has a version of its own: Linux is not built for
 * every release, and an app must never be told a version its own download does not carry.
 */
namespace UpdateManifest {

/** The schema the manifest names; a manifest of another one is not read. */
constexpr char schema[] = "khandaq-desktop-update/1";
/** What a manifest signature is made for, so that no artifact signature can pass for one. */
constexpr char signatureNameSpace[] = "khandaq-desktop-update";
/** The largest download a manifest may name. */
constexpr qint64 artifactLimit = qint64{1} << 30;
/** The largest serial: the largest whole number every JSON reader holds exactly (2^53 - 1). */
constexpr qint64 serialLimit = (qint64{1} << 53) - 1;

struct Version
{
    quint32 major = 0;
    quint32 minor = 0;
    quint32 patch = 0;
};

/** "0.2.64", or the tag "v0.2.64". Three decimal parts of at most nine digits, without leading
 * zeros; anything else is not a version. */
bool parseVersion(const QString& text, Version& out);
QString toString(const Version& version);
/** Negative, zero or positive as a is older than, the same as or newer than b. */
int compare(const Version& a, const Version& b);

struct Entry
{
    Version version;
    QString file;
    qint64 size = 0;
    /** The 32 bytes of its SHA-256. */
    QByteArray sha256;
};

struct Release
{
    /** By platform key; only the platforms this app knows. */
    QMap<QString, Entry> platforms;
    QUrl notesEnglish;
    QUrl notesRussian;
    /** Grows with every manifest the release scripts write (the UTC time it was written, in
     * seconds): one below the highest an app has verified is an old manifest served again. */
    qint64 serial = 0;
};

/** The platform keys this app knows, each with the one file its entry may name. */
QStringList platformKeys();
QString fileFor(const QString& platform);

/**
 * Reads a manifest whose signature has been verified. Strict about everything the app uses: the
 * schema, the serial (a whole number from 1 to serialLimit), each known platform's version, file
 * (the one named for that platform), size and SHA-256, and the release notes, which must be pages
 * of https://khandaq.org/. A field or platform the app
 * does not know is passed over, so that adding one never stops the apps in the field from updating.
 * error says why a manifest was refused.
 */
bool parse(const QByteArray& json, Release& out, QString& error);

/** The release notes in the language of the interface: Russian for ru, English otherwise. */
QUrl notesFor(const Release& release, const QString& language);

struct Running
{
    Version version;
    /** The version could be read at all. */
    bool known = false;
    /** Built from an exact release tag: only such a build checks for updates. */
    bool release = false;
};

/** The running build from GIT_DESCRIBE_EXACT ("v0.2.64", or "Nightly" off a tag) and GIT_DESCRIBE
 * ("v0.2.64-3-gabc1234" off a tag), the version a QA build counts itself as. Either ends in
 * "-dirty" for a tree with changes not committed, which is never a release. */
Running running(const QString& describeExact, const QString& describe);

struct Decision
{
    bool offer = false;
    Entry entry;
    /** The highest version a verified manifest has shown for this platform, to be kept. */
    Version highestSeen;
};

/**
 * Offers the platform's entry only when it is newer than the running version and not older than
 * the highest one a verified manifest has shown before: an old signed manifest served again never
 * takes anyone back.
 */
Decision decide(const Release& release, const QString& platform, const Version& current,
                const Version& highestSeen);

} // namespace UpdateManifest

/** Where the manifest is and whose signature it needs. */
namespace UpdateEndpoint {

#if UPDATE_CHECK_QA
/** The environment variable a QA build reads the manifest address from. Only a QA build contains
 * the name at all: scripts/generate-desktop-update-manifest.py refuses a binary that does. */
constexpr char qaUrlVariable[] = "KHANDAQ_UPDATE_MANIFEST_URL";
#endif

/** Built with UPDATE_CHECK_QA, which a release never is. */
bool qaBuild();
/** https://khandaq.org/downloads/khandaq-desktop-update.json; in a QA build, what
 * KHANDAQ_UPDATE_MANIFEST_URL says when acceptsQaUrl takes it. */
QUrl manifestUrl();
/** https with a host, or http to 127.0.0.1. */
bool acceptsQaUrl(const QUrl& url);
QUrl signatureUrl(const QUrl& manifest);
/** A download named by the manifest, beside the manifest. */
QUrl artifactUrl(const QUrl& manifest, const QString& file);
/** The keys a manifest signature may be made with: the release key, and in a QA build the test key
 * it was configured with. */
QVector<QByteArray> trustedKeys();

} // namespace UpdateEndpoint
