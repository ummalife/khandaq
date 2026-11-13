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

#include "profilefile.h"

#include "src/core/toxencrypt.h"
#include "src/core/toxstring.h"

#include <QDebug>
#include <QFile>

#include <tox/tox.h>
#include <tox/toxencryptsave.h>

namespace ProfileFile {

namespace {

/** A plain save begins with four zero bytes and this cookie, little endian (toxcore state.h). */
constexpr quint32 saveCookie = 0x15ed1b1f;
constexpr int saveHeaderLength = 8;

bool beginsAsSave(const QByteArray& data)
{
    if (data.size() < saveHeaderLength) {
        return false;
    }
    const auto* const bytes = reinterpret_cast<const uchar*>(data.constData());
    if (bytes[0] != 0 || bytes[1] != 0 || bytes[2] != 0 || bytes[3] != 0) {
        return false;
    }
    const quint32 cookie = static_cast<quint32>(bytes[4]) | (static_cast<quint32>(bytes[5]) << 8)
                           | (static_cast<quint32>(bytes[6]) << 16)
                           | (static_cast<quint32>(bytes[7]) << 24);
    return cookie == saveCookie;
}

Contents failed(Contents contents, Contents::Problem problem, const QString& code)
{
    contents.problem = problem;
    contents.code = code;
    return contents;
}

} // namespace

Contents read(const QByteArray& data, const QString& password)
{
    using Problem = Contents::Problem;
    Contents contents;
    QByteArray save = data;
    if (ToxEncrypt::isEncrypted(data)) {
        contents.encrypted = true;
        if (password.isEmpty()) {
            return failed(contents, Problem::NeedsPassword, QString());
        }
        if (data.size() <= TOX_PASS_ENCRYPTION_EXTRA_LENGTH) {
            return failed(contents, Problem::Damaged, QStringLiteral("encrypted save empty"));
        }
        // A wrong password and an encrypted save damaged after it was written fail the same way.
        save = ToxEncrypt::decryptPass(password, data);
        if (save.isEmpty()) {
            return failed(contents, Problem::WrongPassword, QStringLiteral("tox_pass_decrypt"));
        }
    }
    if (!beginsAsSave(save)) {
        return failed(contents, contents.encrypted ? Problem::Damaged : Problem::NotProfile,
                      QStringLiteral("save header"));
    }

    Tox_Err_Options_New optionsError;
    Tox_Options* const options = tox_options_new(&optionsError);
    if (options == nullptr) {
        return failed(contents, Problem::Unreadable,
                      QStringLiteral("Tox_Err_Options_New %1").arg(static_cast<int>(optionsError)));
    }
    // Read only: no socket is opened and nobody is reached.
    tox_options_set_udp_enabled(options, false);
    tox_options_set_ipv6_enabled(options, false);
    tox_options_set_local_discovery_enabled(options, false);
    tox_options_set_hole_punching_enabled(options, false);
    tox_options_set_tcp_port(options, 0);
    tox_options_set_proxy_type(options, TOX_PROXY_TYPE_NONE);
    tox_options_set_savedata_type(options, TOX_SAVEDATA_TYPE_TOX_SAVE);
    tox_options_set_savedata_data(options, reinterpret_cast<const uint8_t*>(save.constData()),
                                  static_cast<size_t>(save.size()));
    Tox_Err_New error;
    Tox* const tox = tox_new(options, &error);
    tox_options_free(options);
    if (tox == nullptr) {
        return failed(contents,
                      error == TOX_ERR_NEW_LOAD_BAD_FORMAT ? Problem::Damaged : Problem::Unreadable,
                      QStringLiteral("Tox_Err_New %1").arg(static_cast<int>(error)));
    }

    QByteArray name(static_cast<int>(tox_self_get_name_size(tox)), '\0');
    tox_self_get_name(tox, reinterpret_cast<uint8_t*>(name.data()));
    QByteArray address(TOX_ADDRESS_SIZE, '\0');
    tox_self_get_address(tox, reinterpret_cast<uint8_t*>(address.data()));
    tox_kill(tox);

    contents.name = ToxString(name).getQString();
    contents.id = QString::fromLatin1(address.toHex()).toUpper();
    return contents;
}

Contents readFile(const QString& path, const QString& password)
{
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) {
        Contents contents;
        contents.problem = Contents::Problem::Unreadable;
        contents.code = QStringLiteral("open %1").arg(static_cast<int>(file.error()));
        return contents;
    }
    if (file.size() > maxBytes) {
        qWarning() << "Not reading a profile file of" << file.size() << "bytes";
        Contents contents;
        contents.problem = Contents::Problem::NotProfile;
        contents.code = QStringLiteral("size");
        return contents;
    }
    return read(file.readAll(), password);
}

} // namespace ProfileFile
