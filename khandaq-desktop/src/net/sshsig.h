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

/**
 * KHANDAQ (0.2.66): the signatures the release tooling makes, checked in the app.
 *
 * scripts/sign-desktop-artifacts.sh signs with `ssh-keygen -Y sign`: an Ed25519 key, SHA-512 and a
 * namespace that says what the signature is for (OpenSSH PROTOCOL.sshsig). Only that shape passes:
 * another key type or hash, another namespace, armour that is not canonical base64 or bytes after
 * the signature are all refused, and nothing is guessed.
 */
namespace SshSig {

/** The 32 bytes of an Ed25519 key from the base64 blob of an OpenSSH public key line ("AAAAC3Nza..."),
 * or nothing when the blob is not exactly one. */
QByteArray ed25519Key(const QByteArray& base64Blob);

/** Whether armored is a signature over message by publicKey (32 bytes) in nameSpace. */
bool verify(const QByteArray& message, const QByteArray& armored, const QByteArray& publicKey,
            const QByteArray& nameSpace);

} // namespace SshSig
