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

// KHANDAQ (beta ideas 2026-11-20): a profile photo made from a picture as You makes it; the photo
// chosen at first launch is made the same way before the profile exists.

#include "src/model/profile/profileinfo.h"
#include "src/model/toxclientstandards.h"

#include <QFile>
#include <QFileInfo>
#include <QImage>
#include <QRandomGenerator>
#include <QTemporaryDir>
#include <QtTest/QtTest>

namespace {
/** Pixels no PNG can pack: a picture of this size is far over what Tox sends as a photo. */
QImage noise(int width, int height)
{
    QImage image(width, height, QImage::Format_RGB32);
    QRandomGenerator random(20261120);
    for (int y = 0; y < height; ++y) {
        auto* const line = reinterpret_cast<QRgb*>(image.scanLine(y));
        for (int x = 0; x < width; ++x) {
            line[x] = 0xff000000u | (random.generate() & 0xffffffu);
        }
    }
    return image;
}

bool isPng(const QByteArray& data)
{
    return data.startsWith(QByteArray("\x89PNG\r\n\x1a\n", 8));
}
} // namespace

class TestProfileInfo : public QObject
{
    Q_OBJECT

private slots:
    void noPathIsNoPhoto();
    void aFileThatIsNotThereIsSaidSo();
    void aFileThatIsNoPictureIsSaidSo();
    void aLargePictureIsScaledUntilToxCanSendIt();
    void aSmallPictureKeepsItsSize();
};

void TestProfileInfo::noPathIsNoPhoto()
{
    QByteArray photo;
    QCOMPARE(ProfileInfo::readAvatar(QString(), photo), IProfileInfo::SetAvatarResult::EmptyPath);
    QVERIFY(photo.isEmpty());
}

void TestProfileInfo::aFileThatIsNotThereIsSaidSo()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    QByteArray photo;
    QCOMPARE(ProfileInfo::readAvatar(dir.filePath(QStringLiteral("gone.png")), photo),
             IProfileInfo::SetAvatarResult::CanNotOpen);
}

void TestProfileInfo::aFileThatIsNoPictureIsSaidSo()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("notes.png"));
    QFile file(path);
    QVERIFY(file.open(QIODevice::WriteOnly));
    file.write("These are notes, not a picture.");
    file.close();
    QByteArray photo;
    QCOMPARE(ProfileInfo::readAvatar(path, photo), IProfileInfo::SetAvatarResult::CanNotRead);
}

/**
 * @brief A large picture comes down to 256 px a side first, and further, keeping its shape, until
 * it is small enough to send.
 */
void TestProfileInfo::aLargePictureIsScaledUntilToxCanSendIt()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("large.png"));
    QVERIFY(noise(1200, 900).save(path, "PNG"));
    QVERIFY(QFileInfo(path).size() > static_cast<qint64>(ToxClientStandards::MaxAvatarSize));

    QByteArray photo;
    QCOMPARE(ProfileInfo::readAvatar(path, photo), IProfileInfo::SetAvatarResult::OK);
    QVERIFY(isPng(photo));
    QVERIFY(ToxClientStandards::IsValidAvatarSize(photo.size()));
    QImage made;
    QVERIFY(made.loadFromData(photo));
    QVERIFY(made.width() <= 256);
    QVERIFY(made.height() <= 256);
    QCOMPARE(made.width() * 3, made.height() * 4);
}

void TestProfileInfo::aSmallPictureKeepsItsSize()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("small.png"));
    QImage small(100, 80, QImage::Format_RGB32);
    small.fill(QColor(30, 120, 90));
    QVERIFY(small.save(path, "PNG"));

    QByteArray photo;
    QCOMPARE(ProfileInfo::readAvatar(path, photo), IProfileInfo::SetAvatarResult::OK);
    QVERIFY(isPng(photo));
    QImage made;
    QVERIFY(made.loadFromData(photo));
    QCOMPARE(made.size(), QSize(100, 80));
}

QTEST_GUILESS_MAIN(TestProfileInfo)
#include "profileinfo_test.moc"
