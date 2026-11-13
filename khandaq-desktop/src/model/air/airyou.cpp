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

#include "airyou.h"

#include "src/model/air/airmodel.h"

#include <QStringList>
#include <QTextBoundaryFinder>

#include <qrencode.h>

namespace Air {

QString fitUtf8(const QString& text, int limitBytes)
{
    if (limitBytes <= 0) {
        return QString();
    }
    if (text.toUtf8().size() <= limitBytes) {
        return text;
    }
    QTextBoundaryFinder graphemes(QTextBoundaryFinder::Grapheme, text);
    int fits = 0;
    int bytes = 0;
    for (int end = graphemes.toNextBoundary(); end != -1; end = graphemes.toNextBoundary()) {
        const int size = text.mid(fits, end - fits).toUtf8().size();
        if (bytes + size > limitBytes) {
            break;
        }
        bytes += size;
        fits = end;
    }
    return text.left(fits);
}

FittedEdit fitEdit(const QString& before, const QString& after, int limitBytes)
{
    FittedEdit fitted;
    fitted.text = after;
    fitted.caret = after.size();
    if (after.toUtf8().size() <= limitBytes) {
        return fitted;
    }
    // What before and after share at their starts and at their ends is the text the edit kept;
    // between them is what went in.
    const int shorter = qMin(before.size(), after.size());
    int head = 0;
    while (head < shorter && before.at(head) == after.at(head)) {
        ++head;
    }
    int tail = 0;
    while (tail < shorter - head
           && before.at(before.size() - 1 - tail) == after.at(after.size() - 1 - tail)) {
        ++tail;
    }
    // Never between the two halves of a character beyond the first 65536.
    while (head > 0 && after.at(head - 1).isHighSurrogate()) {
        --head;
    }
    while (tail > 0 && after.at(after.size() - tail).isLowSurrogate()) {
        --tail;
    }
    const QString start = after.left(head);
    const QString end = after.right(tail);
    const QString inserted = fitUtf8(after.mid(head, after.size() - head - tail),
                                     limitBytes - (start + end).toUtf8().size());
    fitted.text = start + inserted + end;
    fitted.caret = head + inserted.size();
    if (fitted.text.toUtf8().size() > limitBytes) {
        // The text was over the limit before the edit already: what stayed of it is cut too.
        fitted.text = fitUtf8(fitted.text, limitBytes);
        fitted.caret = qMin(fitted.caret, fitted.text.size());
    }
    return fitted;
}

bool isLegacyDefaultStatus(const QString& status)
{
    // The translations of "Toxing on Khandaq" that the desktop shipped, from translations/*.ts,
    // written with escapes so the source stays ASCII; the language of each is beside it.
    static const QStringList defaults = {
        QStringLiteral("Toxing on Khandaq"),
        QStringLiteral("Toxing on qTox"),
        QStringLiteral("Khandaq is Tox"),
        QStringLiteral("\u0627\u0633\u062a\u062e\u062f\u0645 \u0627\u0644\u062a\u0648\u0643\u0633 "
                       "\u0639\u0644\u0649 \u0643\u064a\u0648\u062a\u0648\u0643\u0633"), // ar
        QStringLiteral("\u041a\u0430\u0440\u044b\u0441\u0442\u0430\u044e Khandaq"),      // be
        QStringLiteral("Tox-\u0432\u0430 \u0432 Khandaq"),                               // bg
        QStringLiteral("Toxuji na Khandaqe"),                                            // cs
        QStringLiteral("Toxen mit Khandaq"),                                             // de
        QStringLiteral(
            "\u03a0\u03b1\u03c1\u03bf\u03c5\u03c3\u03af\u03b1 \u03c3\u03c4\u03bf Khandaq"), // el
        QStringLiteral("Toksante \u0109e Khandaq"),                                         // eo
        QStringLiteral("Toxeando con Khandaq"),                                             // es
        QStringLiteral("Toxib rakendusega Khandaq"),                                        // et
        QStringLiteral(
            "\u062f\u0631 \u062d\u0627\u0644 Tox \u06a9\u0631\u062f\u0646 \u0631\u0648\u06cc "
            "Khandaq"),                                                      // fa
        QStringLiteral("Toxaa Khandaqilla"),                                 // fi
        QStringLiteral("Je toxe sur Khandaq"),                               // fr
        QStringLiteral("Toxeando en Khandaq"),                               // gl
        QStringLiteral("Toksiranje kroz Khandaq"),                           // hr
        QStringLiteral("A Khandaq klienst haszn\u00e1lom"),                  // hu
        QStringLiteral("Toxando su Khandaq"),                                // it
        QStringLiteral("Khandaq \u3067 tox \u3057\u3066\u3044\u307e\u3059"), // ja
        QStringLiteral("Naudoju Khandaq"),                                   // lt
        QStringLiteral("T\u0113rz\u0113t Khandaq"),                          // lv
        QStringLiteral(
            "\u0422\u043e\u043a\u0441\u0438\u0440\u0430\u045a\u0435 \u043d\u0430 Khandaq"), // mk
        QStringLiteral("Toxt op Khandaq"),                                                  // nl
        QStringLiteral("Toxt met Khandaq"),                                                 // nl_BE
        QStringLiteral("Toxer p\u00e5 Khandaq"),                                            // no_nb
        QStringLiteral("Toxuj\u0119 na Khandaq"),                                           // pl
        QStringLiteral("Toxin' aboard Khandaq"),                                            // pr
        QStringLiteral("A toxear no Khandaq"),                                              // pt
        QStringLiteral("Toxeando com Khandaq"),                                             // pt_BR
        QStringLiteral("Folosi\u021bi Tox \u00een Khandaq"),                                // ro
        QStringLiteral("\u041e\u0431\u0449\u0430\u0439\u0442\u0435\u0441\u044c \u0432 Khandaq"), // ru
        QStringLiteral("Toxujem na Khandaqe"), // sk
        QStringLiteral("Toxanje na Khandaqu"), // sl
        QStringLiteral("\u0422\u043e\u043a\u0441\u0443\u0458\u0435 \u043f\u0443\u0442\u0435\u043c "
                       "q-\u0422\u043e\u043a\u0441\u0430"), // sr
        QStringLiteral("Tox-uje putem Khandaq-a"),          // sr_Latn
        QStringLiteral("Toxar p\u00e5 Khandaq"),            // sv
        QStringLiteral(
            "Khandaq \u0b87\u0bb2\u0bcd Tox \u0b9a\u0bc6\u0baf\u0bcd\u0b95\u0bbf\u0bb1\u0bc7"
            "\u0ba9\u0bcd"),                           // ta
        QStringLiteral("Khandaq'da tox'lan\u0131yor"), // tr
        QStringLiteral("Khandaq \u0626\u0627\u0631\u0642\u0649\u0644\u0649\u0642 "
                       "\u0633\u06c6\u06be\u0628\u06d5\u062a\u0644\u0649\u0634\u0649\u0634"), // ug
        QStringLiteral("\u0412\u0456\u0442\u0430\u043d\u043d\u044f \u0437 Khandaq"),          // uk
        QStringLiteral("Qu\u1ea9y tr\u00ean Khandaq"),                                        // vi
        QStringLiteral("\u4f7f\u7528 Khandaq \u4e0e\u4ed6\u4eba\u4ea4\u6d41"), // zh_CN
    };
    return !status.isEmpty() && defaults.contains(status);
}

bool IdParts::isEmpty() const
{
    return key.isEmpty();
}

bool IdParts::operator==(const IdParts& other) const
{
    return key == other.key && antispam == other.antispam && check == other.check;
}

IdParts idParts(const QString& id)
{
    IdParts parts;
    if (id.size() != toxAddressHexLength) {
        return parts;
    }
    parts.key = id.left(toxKeyHexLength);
    parts.antispam = id.mid(toxKeyHexLength, toxAntispamHexLength);
    parts.check = id.mid(toxKeyHexLength + toxAntispamHexLength);
    return parts;
}

quint32 newAntispam(quint32 current, const std::function<quint32()>& random)
{
    quint32 next = random();
    // One draw in four billion repeats the antispam in use; a few more draws settle it, and a
    // generator that keeps repeating itself still gives another one.
    for (int attempt = 0; attempt < 8 && next == current; ++attempt) {
        next = random();
    }
    return next == current ? current ^ 1u : next;
}

bool QrModules::isDark(int x, int y) const
{
    if (x < 0 || y < 0 || x >= side || y >= side) {
        return false;
    }
    return dark.at(y * side + x);
}

QrModules qrModules(const QString& text)
{
    QrModules modules;
    const QByteArray data = text.toUtf8();
    if (data.isEmpty()) {
        return modules;
    }
    // The whole text in byte mode, as the old profile form encoded it; version 0 picks the smallest
    // version it fits in.
    QRcode* const code = QRcode_encodeString8bit(data.constData(), 0, QR_ECLEVEL_M);
    if (!code) {
        return modules;
    }
    modules.side = code->width;
    modules.dark.resize(code->width * code->width);
    for (int i = 0; i < modules.dark.size(); ++i) {
        // The lowest bit of each byte is the colour of the module; the others say what it is for.
        modules.dark[i] = (code->data[i] & 0x01) != 0;
    }
    QRcode_free(code);
    return modules;
}

QImage qrImage(const QrModules& modules, int moduleSide)
{
    if (modules.side <= 0 || moduleSide <= 0) {
        return QImage();
    }
    const int side = (modules.side + 2 * qrQuietModules) * moduleSide;
    QImage image(side, side, QImage::Format_RGB32);
    image.fill(Qt::white);
    const QRgb black = qRgb(0, 0, 0);
    for (int y = 0; y < side; ++y) {
        auto* const line = reinterpret_cast<QRgb*>(image.scanLine(y));
        const int row = y / moduleSide - qrQuietModules;
        for (int x = 0; x < side; ++x) {
            // Outside the code, in its quiet zone, isDark is false.
            if (modules.isDark(x / moduleSide - qrQuietModules, row)) {
                line[x] = black;
            }
        }
    }
    return image;
}

} // namespace Air
