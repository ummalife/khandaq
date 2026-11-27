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

#include "src/chatlog/content/airlocationcard.h"

#include "src/chatlog/chatmessage.h"
#include "src/chatlog/content/airlabel.h"
#include "src/chatlog/documentcache.h"
#include "src/model/air/airlocation.h"
#include "src/model/air/airmessagemarks.h"
#include "src/model/air/airmodel.h"
#include "src/net/locationmaploader.h"
#include "src/persistence/paths.h"
#include "src/persistence/settings.h"
#include "src/persistence/smileypack.h"
#include "src/widget/air/airlistview.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/style.h"
#include "src/widget/tool/chatquotepreview.h"
#include "src/widget/tool/imessageboxmanager.h"

#include <QApplication>
#include <QBuffer>
#include <QDesktopServices>
#include <QDir>
#include <QFile>
#include <QFontInfo>
#include <QFontMetricsF>
#include <QGraphicsScene>
#include <QGraphicsSceneMouseEvent>
#include <QImage>
#include <QNetworkAccessManager>
#include <QNetworkProxy>
#include <QNetworkReply>
#include <QPainter>
#include <QPointer>
#include <QSignalSpy>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QtTest/QtTest>

#include <cmath>
#include <cstring>
#include <memory>
#include <tuple>

using AirTokens::Color;
using AirTokens::TextRole;
namespace Space = AirTokens::Space;

namespace {
Air::Location at(double latitude, double longitude)
{
    Air::Location place;
    place.latitude = latitude;
    place.longitude = longitude;
    return place;
}

const Air::Location redSquare = at(55.7558, 37.6173);
/** Four tiles: the window lies below the middle of the tile of the place. */
const Air::Location mecca = at(21.4225, 39.8262);

/** A tile the test's manager hands out: made up, answered when the test says so. */
class TileReply : public QNetworkReply
{
public:
    TileReply(const QNetworkRequest& request, QObject* parent)
        : QNetworkReply(parent)
    {
        setRequest(request);
        setUrl(request.url());
        setOperation(QNetworkAccessManager::GetOperation);
        open(QIODevice::ReadOnly | QIODevice::Unbuffered);
    }

    void respond(const QByteArray& bytes, int status)
    {
        if (isFinished()) {
            return;
        }
        body = bytes;
        setAttribute(QNetworkRequest::HttpStatusCodeAttribute, status);
        setHeader(QNetworkRequest::ContentLengthHeader, body.size());
        if (status != 200) {
            setError(QNetworkReply::ContentNotFoundError, QStringLiteral("Not Found"));
        }
        emit metaDataChanged();
        if (isFinished()) {
            return;
        }
        emit readyRead();
        if (isFinished()) {
            return;
        }
        setFinished(true);
        emit finished();
    }

    void abort() override
    {
        if (isFinished()) {
            return;
        }
        aborted = true;
        setError(QNetworkReply::OperationCanceledError, QStringLiteral("Operation canceled"));
        setFinished(true);
        emit finished();
    }

    bool isSequential() const override
    {
        return true;
    }

    qint64 bytesAvailable() const override
    {
        return body.size() - taken + QIODevice::bytesAvailable();
    }

    bool aborted = false;

protected:
    qint64 readData(char* data, qint64 maxSize) override
    {
        const qint64 count = qMin(maxSize, static_cast<qint64>(body.size()) - taken);
        if (count <= 0) {
            return isFinished() ? -1 : 0;
        }
        std::memcpy(data, body.constData() + taken, static_cast<size_t>(count));
        taken += count;
        return count;
    }

private:
    QByteArray body;
    qint64 taken = 0;
};

/** Sends nothing anywhere: each request is a TileReply the test answers. */
class TileManager : public QNetworkAccessManager
{
public:
    QVector<QNetworkRequest> requests;
    QVector<QPointer<TileReply>> replies;

    /** Answers every request still waiting, in the order they came, the new ones too. */
    void answerAll(const std::function<QByteArray(const QUrl&)>& bytes, int status = 200)
    {
        for (int i = 0; i < replies.size(); ++i) {
            if (replies.at(i) && !replies.at(i)->isFinished()) {
                replies.at(i)->respond(bytes(replies.at(i)->url()), status);
            }
        }
    }

    int waiting() const
    {
        int count = 0;
        for (const QPointer<TileReply>& reply : replies) {
            count += reply && !reply->isFinished() ? 1 : 0;
        }
        return count;
    }

protected:
    QNetworkReply* createRequest(Operation operation, const QNetworkRequest& request,
                                 QIODevice* outgoing) override
    {
        std::ignore = operation;
        std::ignore = outgoing;
        requests.append(request);
        auto* const reply = new TileReply(request, this);
        replies.append(reply);
        return reply;
    }
};

/** Takes the links QDesktopServices would open in the browser. */
class LinkCatcher : public QObject
{
    Q_OBJECT
public:
    QList<QUrl> opened;

public slots:
    void open(const QUrl& url)
    {
        opened.append(url);
    }
};

/** Shows no dialog: a Settings needs someone to report to, and the test has nobody to ask. */
class QuietMessages : public IMessageBoxManager
{
public:
    void showInfo(const QString&, const QString&) override {}
    void showWarning(const QString&, const QString&) override {}
    void showError(const QString&, const QString&) override {}
    bool askQuestion(const QString&, const QString&, bool defaultAns, bool, bool) override
    {
        return defaultAns;
    }
    bool askQuestion(const QString&, const QString&, const QString&, const QString&,
                     bool defaultAns, bool) override
    {
        return defaultAns;
    }
    void confirmExecutableOpen(const QFileInfo&) override {}
};

QByteArray pngOf(const QImage& image)
{
    QByteArray bytes;
    QBuffer buffer(&bytes);
    buffer.open(QIODevice::WriteOnly);
    image.save(&buffer, "PNG");
    return bytes;
}

QImage solid(const QColor& color, int width = Air::LocationMap::tileSize,
             int height = Air::LocationMap::tileSize)
{
    QImage image(width, height, QImage::Format_ARGB32);
    image.fill(color);
    return image;
}

/** The place in pixels of the whole map at the zoom of the tiles. */
QPointF worldPixel(const Air::Location& place)
{
    const Air::LocationMap::Layout layout = Air::LocationMap::layoutOf(place);
    const Air::LocationMap::Tile& first = layout.tiles.first();
    return QPointF(first.x * Air::LocationMap::tileSize - first.offset.x() + layout.marker.x(),
                   first.y * Air::LocationMap::tileSize - first.offset.y() + layout.marker.y());
}

/**
 * A tile that looks like a street map near place, so the pictures show the card as it will look:
 * land, a park, a river, buildings and streets that run on from one tile into the next.
 */
QImage streetTile(int tileX, int tileY, const QPointF& place)
{
    QImage tile = solid(QColor(0xf2, 0xef, 0xe9));
    QPainter painter(&tile);
    painter.setRenderHint(QPainter::Antialiasing);
    // The place in the tile's own pixels: everything is drawn around it, a little off it by the
    // place, so that two places do not have one map.
    const QPointF off(std::fmod(place.x(), 47.0) - 23.5, std::fmod(place.y(), 31.0) - 15.5);
    const QPointF p = place - QPointF(tileX * 256.0, tileY * 256.0) + off;
    painter.setPen(Qt::NoPen);
    painter.setBrush(QColor(0xcd, 0xeb, 0xb0));
    painter.drawRect(QRectF(p.x() - 170, p.y() - 95, 110, 70));
    painter.setBrush(QColor(0xd9, 0xd0, 0xc9));
    for (int row = -3; row <= 3; ++row) {
        for (int column = -5; column <= 5; ++column) {
            const QRectF block(p.x() + column * 46 + 8, p.y() + row * 38 + 6, 30, 22);
            if (!block.intersects(QRectF(p.x() - 172, p.y() - 97, 114, 74))) {
                painter.drawRect(block);
            }
        }
    }
    QPen river(QColor(0xaa, 0xd3, 0xdf), 22, Qt::SolidLine, Qt::RoundCap);
    painter.setPen(river);
    painter.drawLine(QPointF(p.x() - 400, p.y() + 95), QPointF(p.x() + 400, p.y() + 55));
    QPen casing(QColor(0xd6, 0xcf, 0xc7), 9);
    QPen street(Qt::white, 7);
    QPen avenue(QColor(0xf7, 0xfa, 0xbf), 9);
    const QLineF streets[] = {
        QLineF(p.x() - 400, p.y() - 2, p.x() + 400, p.y() - 2),
        QLineF(p.x() + 4, p.y() - 300, p.x() + 4, p.y() + 300),
        QLineF(p.x() - 240, p.y() - 300, p.x() + 160, p.y() + 300),
    };
    for (const QLineF& line : streets) {
        painter.setPen(casing);
        painter.drawLine(line);
    }
    for (int i = 0; i < 3; ++i) {
        painter.setPen(i == 0 ? avenue : street);
        painter.drawLine(streets[i]);
    }
    painter.end();
    return tile;
}

QImage streetMap(const Air::Location& place)
{
    const Air::LocationMap::Layout layout = Air::LocationMap::layoutOf(place);
    const QPointF pixel = worldPixel(place);
    QVector<QImage> tiles;
    for (const Air::LocationMap::Tile& tile : layout.tiles) {
        tiles.append(streetTile(tile.x, tile.y, pixel));
    }
    return LocationMapLoader::draw(layout, tiles);
}

QImage painted(AirLocationCard& card, qreal devicePixelRatio = 1)
{
    const QRectF box = card.boundingRect();
    QImage image(qCeil(box.width() * devicePixelRatio), qCeil(box.height() * devicePixelRatio),
                 QImage::Format_ARGB32_Premultiplied);
    image.setDevicePixelRatio(devicePixelRatio);
    image.fill(AirStyle::instance().color(Color::Bg));
    QPainter painter(&image);
    card.paint(&painter, nullptr, nullptr);
    painter.end();
    return image;
}

/** How far two colours are apart, channel by channel. */
int distance(const QColor& a, const QColor& b)
{
    return std::abs(a.red() - b.red()) + std::abs(a.green() - b.green())
           + std::abs(a.blue() - b.blue());
}

/** The pixels of image within area whose colour is near color. */
int pixelsNear(const QImage& image, const QRect& area, const QColor& color, int within = 60)
{
    int count = 0;
    for (int y = area.top(); y <= area.bottom(); ++y) {
        for (int x = area.left(); x <= area.right(); ++x) {
            count += distance(image.pixelColor(x, y), color) <= within ? 1 : 0;
        }
    }
    return count;
}

/** The scale of the pictures: a screen of twice the pixels, as a Retina display. */
constexpr qreal shotScale = 2;

/** widget drawn on ground at shotScale. */
QImage rendered(QWidget& widget, const QColor& ground)
{
    QImage image(widget.size() * shotScale, QImage::Format_ARGB32_Premultiplied);
    image.setDevicePixelRatio(shotScale);
    image.fill(ground);
    QPainter painter(&image);
    widget.render(&painter, QPoint(), QRegion(), QWidget::DrawChildren);
    painter.end();
    return image;
}

/** image without the rows of its own ground (its bottom-left pixel) at its foot, but a margin. */
QImage croppedToContent(const QImage& image)
{
    const QColor ground = image.pixelColor(0, image.height() - 1);
    int last = 0;
    for (int y = 0; y < image.height(); ++y) {
        for (int x = 0; x < image.width(); ++x) {
            if (image.pixelColor(x, y) != ground) {
                last = y;
                break;
            }
        }
    }
    const int margin = qCeil(Space::s8 * shotScale);
    return image.copy(0, 0, image.width(), qMin(image.height(), last + 1 + margin));
}

void click(QGraphicsScene& scene, QGraphicsItem* item, const QPointF& at, Qt::MouseButton button)
{
    QGraphicsSceneMouseEvent press(QEvent::GraphicsSceneMousePress);
    press.setButton(button);
    press.setButtons(button);
    press.setPos(at);
    press.setScenePos(item->mapToScene(at));
    scene.sendEvent(item, &press);
    QGraphicsSceneMouseEvent release(QEvent::GraphicsSceneMouseRelease);
    release.setButton(button);
    release.setPos(at);
    release.setScenePos(item->mapToScene(at));
    scene.sendEvent(item, &release);
}
} // namespace

/**
 * KHANDAQ (28.11.2026): a place a phone shared showed on the desktop as the words
 * "khandaq-location:55.75580,37.61730". The entry is a card now, as on the phones: the map from
 * OpenStreetMap's tiles with the dot of the place, "Location" and the coordinates; the glyph of a
 * place while the map is not there, at the same height.
 */
class TestAirLocationCard : public QObject
{
    Q_OBJECT
public:
    explicit TestAirLocationCard(const QString& home_)
        : home{QDir::cleanPath(home_)}
    {
    }

private slots:
    void initTestCase();
    void drawsTheWindowFromItsTiles();
    void fetchesTheTilesTwoAtATimeThroughTheProxy();
    void aProxyThatCannotBeUsedSendsNothing();
    void aPlaceWhoseTilesFailIsAskedForAgainLater();
    void aTileTooLargeIsNoTile();
    void theCardKeepsItsHeightWhenTheMapComes();
    void theCardFetchesItsMapOnceInView();
    void theCardIsCopiedAndSpokenByItsCoordinates();
    void theCoordinatesAreInTabularFigures();
    void aClickOpensThePlaceOnOpenStreetMap();
    void theCardShowsTheGlyphThenTheMap();
    void aLocationMessageIsACardInTheTranscript();
    void aQuoteOfAPlaceSaysItsName();
    void screenshots();
    void cleanupTestCase();

private:
    void useTheme(bool dark);

    const QString home;
    QuietMessages messages;
    std::unique_ptr<Settings> settings;
    Style style;
};

void TestAirLocationCard::initTestCase()
{
    // A Settings reads and writes the settings file of the Khandaq of this computer: it is made
    // only when that file lies in the home of the test.
    const QString folder = QDir::cleanPath(Paths().getSettingsDirPath());
    if (!home.isEmpty() && folder.startsWith(home + QLatin1Char('/'))) {
        settings.reset(new Settings(messages));
    } else {
        qWarning() << "The settings would not stay in the test's home:" << folder;
    }
}

void TestAirLocationCard::cleanupTestCase()
{
    settings.reset();
}

void TestAirLocationCard::useTheme(bool dark)
{
    settings->setThemeMode(dark ? Settings::ThemeMode::Dark : Settings::ThemeMode::Light);
    AirStyle::instance().follow(*settings, style, nullptr);
    QCOMPARE(AirStyle::instance().isDark(), dark);
}

void TestAirLocationCard::drawsTheWindowFromItsTiles()
{
    // Red Square: two tiles side by side, the window 128 px into the first and 32 px down.
    const Air::LocationMap::Layout layout = Air::LocationMap::layoutOf(redSquare);
    QCOMPARE(layout.tiles.size(), 2);
    const QImage map =
        LocationMapLoader::draw(layout, {solid(QColor(200, 0, 0)), solid(QColor(0, 0, 200))});
    QCOMPARE(map.size(), QSize(Air::LocationMap::width, Air::LocationMap::height));
    QCOMPARE(map.pixelColor(0, 0), QColor(200, 0, 0));
    QCOMPARE(map.pixelColor(127, 119), QColor(200, 0, 0));
    QCOMPARE(map.pixelColor(128, 0), QColor(0, 0, 200));
    QCOMPARE(map.pixelColor(259, 119), QColor(0, 0, 200));

    // Past the edge of the map there is no tile, and the window stays clear there.
    const Air::LocationMap::Layout pole = Air::LocationMap::layoutOf(at(90.0, 0.0));
    QVector<QImage> tiles;
    for (int i = 0; i < pole.tiles.size(); ++i) {
        tiles.append(solid(QColor(0, 150, 0)));
    }
    const QImage edge = LocationMapLoader::draw(pole, tiles);
    int clear = 0;
    for (int y = 0; y < edge.height(); ++y) {
        clear += edge.pixelColor(0, y).alpha() == 0 ? 1 : 0;
    }
    QVERIFY2(clear > 0 && clear < edge.height(), "the edge of the map is not where it should be");
}

void TestAirLocationCard::fetchesTheTilesTwoAtATimeThroughTheProxy()
{
    TileManager manager;
    LocationMapLoader loader(&manager);
    QSignalSpy ready(&loader, &LocationMapLoader::ready);
    const QNetworkProxy tor(QNetworkProxy::Socks5Proxy, QStringLiteral("127.0.0.1"), 9050);

    loader.load(mecca, tor);
    QVERIFY(loader.loading(mecca));
    QCOMPARE(manager.requests.size(), 2);
    // The proxy of the profile, which looks the names up itself.
    QCOMPARE(manager.proxy().type(), QNetworkProxy::Socks5Proxy);
    QCOMPARE(manager.proxy().hostName(), QStringLiteral("127.0.0.1"));
    QCOMPARE(manager.proxy().port(), quint16(9050));
    QVERIFY(manager.proxy().capabilities() & QNetworkProxy::HostNameLookupCapability);

    // The agent names the app; no language, no cookies, no redirect, and no "no-cache".
    const QNetworkRequest first = manager.requests.first();
    QVERIFY2(first.header(QNetworkRequest::UserAgentHeader)
                 .toString()
                 .startsWith(QStringLiteral("KhandaqMessenger")),
             qPrintable(first.header(QNetworkRequest::UserAgentHeader).toString()));
    QCOMPARE(LocationMapLoader::userAgent(),
             first.header(QNetworkRequest::UserAgentHeader).toString());
    QCOMPARE(first.rawHeader("Accept-Language"), QByteArray("*"));
    QVERIFY(!first.hasRawHeader("Cache-Control"));
    QCOMPARE(first.attribute(QNetworkRequest::CacheLoadControlAttribute,
                             QNetworkRequest::PreferNetwork)
                 .toInt(),
             int(QNetworkRequest::PreferNetwork));
    QCOMPARE(first.attribute(QNetworkRequest::RedirectPolicyAttribute).toInt(),
             int(QNetworkRequest::ManualRedirectPolicy));
    QCOMPARE(first.attribute(QNetworkRequest::CookieLoadControlAttribute).toInt(),
             int(QNetworkRequest::Manual));

    // Asked again while on its way: nothing more goes.
    loader.load(mecca, tor);
    QCOMPARE(manager.requests.size(), 2);

    // One answered, the next goes; never more than two wait.
    const QByteArray tile = pngOf(solid(QColor(0, 120, 200)));
    manager.replies.first()->respond(tile, 200);
    QCOMPARE(manager.requests.size(), 3);
    QCOMPARE(manager.waiting(), 2);
    QCOMPARE(ready.count(), 0);
    manager.answerAll([&tile](const QUrl&) { return tile; });
    manager.answerAll([&tile](const QUrl&) { return tile; });
    QCOMPARE(manager.requests.size(), 4);
    QCOMPARE(manager.waiting(), 0);

    // The tiles of the layout, in its order.
    const Air::LocationMap::Layout layout = Air::LocationMap::layoutOf(mecca);
    for (int i = 0; i < layout.tiles.size(); ++i) {
        QCOMPARE(manager.requests.at(i).url(), Air::LocationMap::tileUrl(layout.tiles.at(i)));
    }

    QCOMPARE(ready.count(), 1);
    QCOMPARE(ready.first().first().toString(), Air::locationKey(mecca));
    QVERIFY(!loader.loading(mecca));
    const QImage map = loader.map(mecca);
    QCOMPARE(map.size(), QSize(Air::LocationMap::width, Air::LocationMap::height));
    QCOMPARE(map.pixelColor(130, 60), QColor(0, 120, 200));

    // Drawn, a map is never fetched again.
    loader.load(mecca, tor);
    QCOMPARE(manager.requests.size(), 4);

    // With no proxy in the profile the tiles go straight, not through the system's proxy.
    loader.load(redSquare, QNetworkProxy(QNetworkProxy::NoProxy));
    QCOMPARE(manager.proxy().type(), QNetworkProxy::NoProxy);
    QCOMPARE(manager.requests.size(), 6);
}

void TestAirLocationCard::aProxyThatCannotBeUsedSendsNothing()
{
    TileManager manager;
    LocationMapLoader loader(&manager);
    loader.load(redSquare, QNetworkProxy(QNetworkProxy::Socks5Proxy, QString(), 9050));
    loader.load(mecca, QNetworkProxy(QNetworkProxy::HttpProxy, QStringLiteral("proxy"), 0));
    QCOMPARE(manager.requests.size(), 0);
    QVERIFY(!loader.loading(redSquare));
    QVERIFY(!loader.loading(mecca));
    QVERIFY(loader.map(redSquare).isNull());
}

void TestAirLocationCard::aPlaceWhoseTilesFailIsAskedForAgainLater()
{
    TileManager manager;
    LocationMapLoader loader(&manager);
    QSignalSpy ready(&loader, &LocationMapLoader::ready);
    loader.load(redSquare, QNetworkProxy(QNetworkProxy::NoProxy));
    QCOMPARE(manager.requests.size(), 2);
    // One tile comes, the other does not: a map with a hole is not kept.
    manager.replies.at(0)->respond(pngOf(solid(Qt::green)), 200);
    manager.replies.at(1)->respond(QByteArray("Not Found"), 404);
    QCOMPARE(ready.count(), 0);
    QVERIFY(!loader.loading(redSquare));
    QVERIFY(loader.map(redSquare).isNull());
    // Not again at once: scrolling past the card does not ask the server over and over.
    loader.load(redSquare, QNetworkProxy(QNetworkProxy::NoProxy));
    QCOMPARE(manager.requests.size(), 2);

    // A picture that is no tile is none either.
    loader.load(mecca, QNetworkProxy(QNetworkProxy::NoProxy));
    manager.answerAll([](const QUrl&) { return pngOf(solid(Qt::green, 64, 64)); });
    manager.answerAll([](const QUrl&) { return QByteArray("<html>"); });
    QCOMPARE(ready.count(), 0);
    QVERIFY(loader.map(mecca).isNull());
}

void TestAirLocationCard::aTileTooLargeIsNoTile()
{
    TileManager manager;
    LocationMapLoader loader(&manager);
    loader.load(redSquare, QNetworkProxy(QNetworkProxy::NoProxy));
    QPointer<TileReply> large = manager.replies.first();
    large->respond(QByteArray(2 * 1024 * 1024, 'x'), 200);
    QVERIFY(large.isNull() || large->aborted);
    manager.answerAll([](const QUrl&) { return pngOf(solid(Qt::green)); });
    QVERIFY(loader.map(redSquare).isNull());
}

void TestAirLocationCard::theCardKeepsItsHeightWhenTheMapComes()
{
    TileManager manager;
    LocationMapLoader loader(&manager);
    AirLocationCard card(redSquare, nullptr, style, loader);
    card.setWidth(400);
    const AirStyle& air = AirStyle::instance();
    const QRectF before = card.boundingRect();
    QCOMPARE(before.width(), qreal(Air::LocationMap::width));
    QCOMPARE(before.height(), qreal(Air::LocationMap::height + Space::s8
                                    + air.lineHeight(TextRole::Body)
                                    + air.lineHeight(TextRole::Caption)));
    QCOMPARE(card.mapRect(), QRectF(0, 0, Air::LocationMap::width, Air::LocationMap::height));
    QVERIFY(!card.showsMap());

    // Another place's map is not this one's.
    loader.keep(mecca, solid(Qt::green, Air::LocationMap::width, Air::LocationMap::height));
    QVERIFY(!card.showsMap());
    loader.keep(redSquare, solid(Qt::green, Air::LocationMap::width, Air::LocationMap::height));
    QVERIFY(card.showsMap());
    QCOMPARE(card.boundingRect(), before);

    // A card made once the map is there shows it at once.
    AirLocationCard again(redSquare, nullptr, style, loader);
    QVERIFY(again.showsMap());

    // Narrower than a map, the card takes the room there is and shows the middle of the map.
    const QPointF middle = card.markerPoint();
    QVERIFY(std::fabs(middle.x() - Air::LocationMap::layoutOf(redSquare).marker.x()) < 1e-9);
    card.setWidth(200);
    QCOMPARE(card.boundingRect().width(), 200.0);
    QCOMPARE(card.boundingRect().height(), before.height());
    QVERIFY(std::fabs(card.markerPoint().x() - (middle.x() - 30)) < 1e-9);
    QCOMPARE(card.markerPoint().y(), middle.y());
}

void TestAirLocationCard::theCardFetchesItsMapOnceInView()
{
    if (!settings) {
        QSKIP("No settings of the test's own for the proxy");
    }
    TileManager manager;
    LocationMapLoader loader(&manager);
    settings->setProxyType(Settings::ProxyType::ptNone);
    AirLocationCard card(redSquare, settings.get(), style, loader);
    // Made, and out of view, it asks for nothing.
    QCOMPARE(manager.requests.size(), 0);
    card.visibilityChanged(false);
    QCOMPARE(manager.requests.size(), 0);
    card.visibilityChanged(true);
    QVERIFY(loader.loading(redSquare));
    QCOMPARE(manager.requests.size(), 2);
    QCOMPARE(manager.proxy().type(), QNetworkProxy::NoProxy);

    const QByteArray tile = pngOf(solid(QColor(0, 120, 200)));
    manager.answerAll([&tile](const QUrl&) { return tile; });
    QVERIFY(card.showsMap());
    // Drawn, it asks no more.
    card.visibilityChanged(false);
    card.visibilityChanged(true);
    QCOMPARE(manager.requests.size(), 2);

    // A card with no settings, as a picture of one, never asks.
    TileManager quiet;
    LocationMapLoader none(&quiet);
    AirLocationCard picture(mecca, nullptr, style, none);
    picture.visibilityChanged(true);
    QCOMPARE(quiet.requests.size(), 0);
}

void TestAirLocationCard::theCardIsCopiedAndSpokenByItsCoordinates()
{
    TileManager manager;
    LocationMapLoader loader(&manager);
    AirLocationCard card(redSquare, nullptr, style, loader);
    card.setWidth(400);
    QCOMPARE(card.getText(), QStringLiteral("55.75580, 37.61730"));
    QCOMPARE(card.getSpokenText(), QStringLiteral("Location: 55.75580, 37.61730"));
    QCOMPARE(card.getSelectedText(), QString());

    // A selection takes the card whole.
    card.selectionTripleClick(QPointF());
    QCOMPARE(card.getSelectedText(), QStringLiteral("55.75580, 37.61730"));
    QVERIFY(card.isOverSelection(QPointF(10, 10)));
    QVERIFY(!card.isOverSelection(QPointF(500, 10)));
    card.selectionCleared();
    QCOMPARE(card.getSelectedText(), QString());
    QVERIFY(!card.isOverSelection(QPointF(10, 10)));
    card.selectionStarted(QPointF(10, 10));
    QCOMPARE(card.getSelectedText(), QString());
    card.selectionMouseMove(QPointF(40, 10));
    QCOMPARE(card.getSelectedText(), QStringLiteral("55.75580, 37.61730"));
}

void TestAirLocationCard::theCoordinatesAreInTabularFigures()
{
    // The coordinates are in the caption, whose figures are all of one width (as the length of a
    // voice note): two places one above the other line up figure by figure.
    const QFontMetricsF metrics(AirStyle::instance().font(TextRole::Caption));
    QCOMPARE(QFontInfo(AirStyle::instance().font(TextRole::Caption)).family(),
             QStringLiteral("IBM Plex Sans"));
    const qreal zero = metrics.horizontalAdvance(QLatin1Char('0'));
    for (const QChar figure : QStringLiteral("123456789")) {
        QCOMPARE(metrics.horizontalAdvance(figure), zero);
    }
}

void TestAirLocationCard::aClickOpensThePlaceOnOpenStreetMap()
{
    LinkCatcher catcher;
    QDesktopServices::setUrlHandler(QStringLiteral("https"), &catcher, "open");
    TileManager manager;
    LocationMapLoader loader(&manager);
    QGraphicsScene scene;
    auto* const card = new AirLocationCard(redSquare, nullptr, style, loader);
    card->setWidth(400);
    scene.addItem(card);

    click(scene, card, QPointF(100, 60), Qt::LeftButton);
    QCOMPARE(catcher.opened.size(), 1);
    QCOMPARE(catcher.opened.first().toString(),
             QStringLiteral("https://www.openstreetmap.org/?mlat=55.75580&mlon=37.61730"
                            "#map=16/55.75580/37.61730"));
    // The words under the map open it too.
    click(scene, card, QPointF(20, Air::LocationMap::height + 20), Qt::LeftButton);
    QCOMPARE(catcher.opened.size(), 2);

    // A right click opens the menu of the entry, not the map.
    click(scene, card, QPointF(100, 60), Qt::RightButton);
    QCOMPARE(catcher.opened.size(), 2);

    // A drag that selects opens nothing when it ends.
    QGraphicsSceneMouseEvent press(QEvent::GraphicsSceneMousePress);
    press.setButton(Qt::LeftButton);
    press.setPos(QPointF(100, 60));
    scene.sendEvent(card, &press);
    card->selectionStarted(card->mapToScene(QPointF(100, 60)));
    QGraphicsSceneMouseEvent release(QEvent::GraphicsSceneMouseRelease);
    release.setButton(Qt::LeftButton);
    release.setPos(QPointF(150, 60));
    scene.sendEvent(card, &release);
    QCOMPARE(catcher.opened.size(), 2);

    // Nor does the second click of a double click.
    QGraphicsSceneMouseEvent twice(QEvent::GraphicsSceneMouseDoubleClick);
    twice.setButton(Qt::LeftButton);
    twice.setPos(QPointF(100, 60));
    scene.sendEvent(card, &twice);
    release.setPos(QPointF(100, 60));
    scene.sendEvent(card, &release);
    QCOMPARE(catcher.opened.size(), 2);

    QDesktopServices::unsetUrlHandler(QStringLiteral("https"));
}

void TestAirLocationCard::theCardShowsTheGlyphThenTheMap()
{
    TileManager manager;
    LocationMapLoader loader(&manager);
    AirLocationCard card(redSquare, nullptr, style, loader);
    card.setWidth(400);
    const AirStyle& air = AirStyle::instance();
    const QColor sunken = air.color(Color::Sunken);

    // No map yet: the sunken box, the glyph of a place in its middle, the corners round.
    const QImage waiting = painted(card);
    QCOMPARE(waiting.pixelColor(20, 20), sunken);
    QCOMPARE(waiting.pixelColor(0, 0), air.color(Color::Bg));
    QVERIFY2(pixelsNear(waiting, QRect(114, 44, 32, 32), air.color(Color::Ink3), 40) > 20,
             "no glyph in the middle of the box");
    QCOMPARE(pixelsNear(waiting, QRect(0, 0, 260, 120), QColor(220, 53, 69), 30), 0);
    // "Location" in ink under the box.
    QVERIFY2(pixelsNear(waiting, QRect(0, 128, 120, 21), air.color(Color::Ink), 60) > 10,
             "no title under the box");
    QVERIFY2(pixelsNear(waiting, QRect(0, 149, 160, 16), air.color(Color::Ink2), 60) > 10,
             "no coordinates under the title");

    loader.keep(redSquare, solid(QColor(0, 160, 90), Air::LocationMap::width,
                                 Air::LocationMap::height));
    const QImage shown = painted(card);
    QCOMPARE(shown.size(), waiting.size());
    QCOMPARE(shown.pixelColor(20, 20), QColor(0, 160, 90));
    QCOMPARE(shown.pixelColor(0, 0), air.color(Color::Bg));
    // The dot of the place, red in a white ring (7 px out, 2 px wide), where the layout puts it.
    const QPointF marker = card.markerPoint();
    const QPoint dot(qFloor(marker.x()), qFloor(marker.y()));
    QVERIFY(distance(shown.pixelColor(dot), QColor(220, 53, 69)) < 30);
    QVERIFY(distance(shown.pixelColor(qFloor(marker.x() + 7), dot.y()), Qt::white) < 30);
    QVERIFY(distance(shown.pixelColor(qFloor(marker.x() - 7), dot.y()), Qt::white) < 30);
    QCOMPARE(shown.pixelColor(qFloor(marker.x() + 12), dot.y()), QColor(0, 160, 90));
    // The attribution on its light plate in the end corner at the bottom: white at 190 of 255
    // over the green of this map, the words black at 190 over it.
    const QColor plate(190, 231, 213);
    QVERIFY2(pixelsNear(shown, QRect(170, 108, 80, 10), plate, 9) > 200, "no attribution plate");
    QVERIFY2(pixelsNear(shown, QRect(170, 108, 80, 10), QColor(48, 59, 54), 60) > 10,
             "no attribution on the plate");
    QCOMPARE(pixelsNear(shown, QRect(0, 100, 150, 20), plate, 9), 0);
}

void TestAirLocationCard::aLocationMessageIsACardInTheTranscript()
{
    if (!settings) {
        QSKIP("No settings of the test's own for an entry");
    }
    SmileyPack smileys(*settings);
    // The pack loads on a thread of its own: it is done before anything reads it.
    std::ignore = smileys.getEmoticons();
    DocumentCache documents(smileys, *settings);
    ChatMessage::AirLook look;
    look.who = QStringLiteral("Amina");
    look.tone = 2;
    look.spokenWho = QStringLiteral("Amina");
    const QDateTime when(QDate(2026, 11, 28), QTime(14, 5));

    const ChatMessage::Ptr place = ChatMessage::createChatMessage(
        look, QStringLiteral("Amina"), QStringLiteral("khandaq-location:55.75580,37.61730"),
        ChatMessage::NORMAL, when, Air::EntryMarks{}, documents, smileys, *settings, style);
    auto* const card = qobject_cast<AirLocationCard*>(place->getContent(1));
    QVERIFY(card);
    QCOMPARE(place->toString(), QStringLiteral("55.75580, 37.61730"));
    QVERIFY(place->spokenText().contains(QStringLiteral("Location: 55.75580, 37.61730")));

    // An edit to words makes it a text again, and back.
    QVERIFY(place->updateMessage(QStringLiteral("See you at five"), ChatMessage::NORMAL,
                                 Air::EntryMarks{}, smileys));
    QVERIFY(!qobject_cast<AirLocationCard*>(place->getContent(1)));
    QCOMPARE(place->toString(), QStringLiteral("See you at five"));
    QVERIFY(place->updateMessage(QStringLiteral("my Location: 55.7558, 37.6173"),
                                 ChatMessage::NORMAL, Air::EntryMarks{}, smileys));
    QVERIFY(qobject_cast<AirLocationCard*>(place->getContent(1)));

    // A /me action keeps its line.
    const ChatMessage::Ptr action = ChatMessage::createChatMessage(
        look, QStringLiteral("Amina"), QStringLiteral("khandaq-location:55.75580,37.61730"),
        ChatMessage::ACTION, when, Air::EntryMarks{}, documents, smileys, *settings, style);
    QVERIFY(!qobject_cast<AirLocationCard*>(action->getContent(1)));
}

void TestAirLocationCard::aQuoteOfAPlaceSaysItsName()
{
    AirQuote quote(QStringLiteral("Amina"), QStringLiteral("khandaq-location:55.75580,37.61730"));
    quote.setWidth(400);
    QVERIFY(quote.boundingRect().height() > 0);
    QCOMPARE(quote.getSpokenText(), AirStrings::spokenQuote(QStringLiteral("Amina"),
                                                            QStringLiteral("Location: 55.75580, "
                                                                           "37.61730")));
}

void TestAirLocationCard::screenshots()
{
    const QString folder = qEnvironmentVariable("KHANDAQ_LOCATION_SHOTS");
    if (folder.isEmpty()) {
        QSKIP("KHANDAQ_LOCATION_SHOTS names no folder for the pictures");
    }
    if (!settings) {
        QSKIP("No settings of the test's own to change the theme with");
    }
    QVERIFY(QDir().mkpath(folder));
    const auto save = [&folder](const QImage& image, const QString& name) {
        const QString path = QDir(folder).filePath(name + QStringLiteral(".png"));
        QVERIFY2(image.save(path), qPrintable(path));
    };

    // The test has no pack of emoticons to draw them with.
    const bool emoticons = settings->getUseEmoticons();
    settings->setUseEmoticons(false);
    SmileyPack smileys(*settings);
    std::ignore = smileys.getEmoticons();
    DocumentCache documents(smileys, *settings);
    // The entries take their maps from the app's own loader, filled here with maps of made-up
    // tiles: nothing is fetched. The third place has none, as before its tiles come.
    LocationMapLoader::instance().keep(redSquare, streetMap(redSquare));
    LocationMapLoader::instance().keep(mecca, streetMap(mecca));
    const Air::Location sydney = at(-33.8568, 151.2153);
    const QString theirPlace = QStringLiteral("khandaq-location:55.75580,37.61730");
    const QString ourPlace = QStringLiteral("khandaq-location:21.42250,39.82620");
    const QString placeWithoutMap = QStringLiteral("khandaq-location:-33.85680,151.21530");

    ChatMessage::AirLook amina;
    amina.who = QStringLiteral("Amina");
    amina.tone = 2;
    amina.spokenWho = amina.who;
    ChatMessage::AirLook bilal = amina;
    bilal.who = QStringLiteral("Bilal");
    bilal.tone = 4;
    bilal.spokenWho = bilal.who;
    ChatMessage::AirLook you;
    you.who = AirStrings::text(AirStrings::Text::You);
    you.tone = Air::youTone;
    you.spokenWho = you.who;
    you.hasMark = true;
    you.status = Air::EntryStatus::Seen;
    // Our reply to the place that came: the header carries its words as they came.
    Air::ReplyMeta meta;
    meta.localId = 42;
    meta.authorKey = QStringLiteral("56A1ADE4");
    meta.timeMs = 1764331500000;
    meta.authorName = amina.who;
    meta.words = AirStrings::quotedWords(theirPlace);
    QCOMPARE(meta.words, theirPlace);

    for (const bool dark : {false, true}) {
        useTheme(dark);
        const QString theme = dark ? QStringLiteral("dark") : QStringLiteral("light");
        const QColor ground = AirStyle::instance().color(Color::Bg);

        // The card alone, with its map and while the map is not there.
        TileManager manager;
        LocationMapLoader loader(&manager);
        loader.keep(redSquare, streetMap(redSquare));
        for (const bool withMap : {true, false}) {
            AirLocationCard card(withMap ? redSquare : sydney, nullptr, style, loader);
            card.setWidth(400);
            const QRectF box = card.boundingRect();
            const int pad = Space::s16;
            QImage image(qCeil((box.width() + 2 * pad) * shotScale),
                         qCeil((box.height() + 2 * pad) * shotScale),
                         QImage::Format_ARGB32_Premultiplied);
            image.setDevicePixelRatio(shotScale);
            image.fill(ground);
            QPainter painter(&image);
            painter.translate(pad, pad);
            card.paint(&painter, nullptr, nullptr);
            painter.end();
            save(image, QStringLiteral("card-%1-%2")
                            .arg(withMap ? QStringLiteral("map") : QStringLiteral("placeholder"),
                                 theme));
        }

        // A conversation: a place that came, one we sent, one whose map is not here yet, and our
        // reply to the first, which quotes it.
        QGraphicsScene scene;
        const QDateTime when(QDate(2026, 11, 28), QTime(14, 5));
        const std::vector<ChatMessage::Ptr> entries{
            ChatMessage::createChatMessage(amina, amina.who, theirPlace, ChatMessage::NORMAL, when,
                                           Air::EntryMarks{}, documents, smileys, *settings,
                                           style),
            ChatMessage::createChatMessage(you, you.who, ourPlace, ChatMessage::NORMAL,
                                           when.addSecs(60), Air::EntryMarks{}, documents, smileys,
                                           *settings, style),
            ChatMessage::createChatMessage(bilal, bilal.who, placeWithoutMap, ChatMessage::NORMAL,
                                           when.addSecs(120), Air::EntryMarks{}, documents,
                                           smileys, *settings, style),
            ChatMessage::createChatMessage(you, you.who,
                                           Air::replyHead(meta) + QStringLiteral("I'm coming"),
                                           ChatMessage::NORMAL, when.addSecs(180),
                                           Air::EntryMarks{}, documents, smileys, *settings,
                                           style)};
        const qreal width = 560;
        qreal y = Space::s8;
        for (const ChatMessage::Ptr& entry : entries) {
            entry->addToScene(&scene);
            // The words of an entry are drawn once it comes into view, as in the transcript. The
            // cards are not told: they would fetch their maps.
            for (int column = 0; column < entry->getColumnCount(); ++column) {
                ChatLineContent* const part = entry->getContent(column);
                if (!qobject_cast<AirLocationCard*>(part)) {
                    part->visibilityChanged(true);
                }
            }
            entry->layout(width, QPointF(0, y));
            y += entry->sceneBoundingRect().height() + Space::s4;
        }
        const QRectF area(0, 0, width, y + Space::s4);
        QImage image(qCeil(area.width() * shotScale), qCeil(area.height() * shotScale),
                     QImage::Format_ARGB32_Premultiplied);
        image.setDevicePixelRatio(shotScale);
        image.fill(ground);
        QPainter painter(&image);
        painter.setRenderHint(QPainter::Antialiasing);
        scene.render(&painter, area, area);
        painter.end();
        save(image, QStringLiteral("transcript-%1").arg(theme));

        // The bar over the field while that reply is written.
        ChatQuotePreview bar;
        bar.setReply(meta, amina.who);
        QCOMPARE(bar.shownLines(int(width)).join(QLatin1Char('\n')),
                 QStringLiteral("Location: 55.75580, 37.61730"));
        bar.resize(int(width), bar.heightForWidth(int(width)));
        bar.show();
        QApplication::processEvents();
        save(rendered(bar, ground), QStringLiteral("reply-bar-%1").arg(theme));

        // The list of chats, whose rows read the last message as AirSource does.
        QVector<Air::Entry> rows;
        Air::Entry sent;
        sent.id = QStringLiteral("f:") + QString(64, QLatin1Char('B'));
        sent.title = QStringLiteral("Amina");
        sent.reach = Air::Reach::direct();
        sent.preview = AirStrings::messageWords(ourPlace);
        sent.lastDelivery = Air::Delivery::Seen;
        sent.timeText = QStringLiteral("14:06");
        sent.activity = 3000;
        sent.tone = 2;
        rows.append(sent);
        Air::Entry came = sent;
        came.id = QStringLiteral("f:") + QString(64, QLatin1Char('C'));
        came.title = QStringLiteral("Bilal");
        came.preview = AirStrings::messageWords(placeWithoutMap);
        came.lastDelivery = Air::Delivery::None;
        came.timeText = QStringLiteral("14:07");
        came.activity = 4000;
        came.unreadCount = 1;
        came.tone = 4;
        rows.append(came);
        Air::Entry words = came;
        words.id = QStringLiteral("f:") + QString(64, QLatin1Char('D'));
        words.title = QStringLiteral("Yusuf");
        words.preview = AirStrings::messageWords(QStringLiteral("See you after the prayer"));
        words.timeText = QStringLiteral("13:40");
        words.activity = 2000;
        words.unreadCount = 0;
        words.tone = 1;
        rows.append(words);
        QCOMPARE(sent.preview, QStringLiteral("Location"));
        QCOMPARE(came.preview, QStringLiteral("Location"));
        AirListView list;
        list.resize(352, 420);
        list.show();
        list.setSnapshot(Air::build(rows, {}, {}, Air::Filter::All, QString()), QString());
        // The rows come in as they fade in.
        QTest::qWait(600);
        save(croppedToContent(rendered(list, ground)), QStringLiteral("chat-list-%1").arg(theme));
    }
    useTheme(false);
    settings->setUseEmoticons(emoticons);
}

int main(int argc, char* argv[])
{
    // The card needs a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    // The themes and the entries need a Settings, which reads and writes the settings of the home
    // folder: the test gets a home of its own, which goes with it.
    QTemporaryDir home;
    if (home.isValid()) {
        qputenv("HOME", QFile::encodeName(home.path()));
        QStandardPaths::setTestModeEnabled(true);
    }
    QApplication app(argc, argv);
    // The Plex faces, as the app has them: the line heights are theirs.
    AirStyle::installFonts();
    TestAirLocationCard test(home.isValid() ? home.path() : QString());
    return QTest::qExec(&test, argc, argv);
}

#include "airlocationcard_test.moc"
