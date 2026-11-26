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

#include "aircallparts.h"

#include "airchatparts.h"
#include "airstrings.h"
#include "airstyle.h"
#include "airwidgets.h"
#include "src/model/air/aircallstage.h"
#include "src/model/air/airmedia.h"
#include "src/widget/translator.h"

#include <QDebug>
#include <QEvent>
#include <QFile>
#include <QFontMetricsF>
#include <QHash>
#include <QPainter>
#include <QSvgRenderer>
#include <QVector>
#include <QtMath>

#include <cmath>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Radius = AirTokens::Radius;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;
namespace Stroke = AirTokens::Stroke;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

/** The call screens are dark in both themes: everything here takes the dark palette. */
QColor dark(Color role)
{
    return AirStyle::darkColor(role);
}

/** The circle of a control, as on the phones. */
constexpr int circleSide = Space::s64;
/** The glyph in the circle: the phones draw 30 pt call glyphs on their 64 pt circle. */
constexpr int glyphSide = 28;
/** Between the circle and its word. */
constexpr int wordGap = Space::s8;
/** The shadow under words over video: its blur and how far it falls. */
constexpr int shadowBlur = 4;
constexpr int shadowDrop = 1;
constexpr qreal shadowOpacity = 0.5;
/** The gaps of the head, as those of an audio call: the name to the time, the time to the chip. */
constexpr int nameGap = Space::s6;
constexpr int chipGap = Space::s12;
/**
 * The chip: 4 px over and under its words, 10 px at its ends, 6 px between the mark and the words.
 */
constexpr int chipPadY = Space::s4;
constexpr int chipPadX = Space::s10;
constexpr int chipInnerGap = Space::s6;

// The glyphs the sprite of the design system has no symbol for, drawn as its symbols are: on 24 by
// 24, stroked with the icon stroke, round ends and joins. The full screen pair keeps the corners of
// the scan symbol.
const char speakerGlyph[] =
    "<path d=\"M4.5 9.5h3L12 5.5v13l-4.5-4h-3a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1z\"/>"
    "<path d=\"M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11\"/>";
const char bubbleGlyph[] =
    "<path d=\"M20.5 15a2 2 0 0 1-2 2H8l-4.5 3.5V6.5a2 2 0 0 1 2-2h13a2 2 0 0 1 2 2z\"/>";
const char expandGlyph[] =
    "<path d=\"M4 8.5V5.5A1.5 1.5 0 0 1 5.5 4h3M15.5 4h3A1.5 1.5 0 0 1 20 5.5v3"
    "M20 15.5v3a1.5 1.5 0 0 1-1.5 1.5h-3M8.5 20h-3A1.5 1.5 0 0 1 4 18.5v-3\"/>";
const char collapseGlyph[] =
    "<path d=\"M9 4v3.5A1.5 1.5 0 0 1 7.5 9H4M15 4v3.5A1.5 1.5 0 0 0 16.5 9H20"
    "M20 15h-3.5a1.5 1.5 0 0 0-1.5 1.5V20M4 15h3.5A1.5 1.5 0 0 1 9 16.5V20\"/>";

enum class Glyph
{
    Microphone,
    Speaker,
    Eye,
    Bubble,
    Expand,
    Collapse,
    HangUp,
};

QByteArray drawnGlyph(const char* inner)
{
    return QByteArrayLiteral("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\" "
                             "viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#000000\" "
                             "stroke-width=\"")
           + QByteArray::number(Stroke::icon)
           + QByteArrayLiteral("\" stroke-linecap=\"round\" stroke-linejoin=\"round\">") + inner
           + QByteArrayLiteral("</svg>");
}

/** A symbol of the sprite, as tools/gen-design-tokens.py writes it: stroked in black. */
QByteArray spriteGlyph(const char* name)
{
    QFile file(QStringLiteral(":/img/air/%1.svg").arg(QLatin1String(name)));
    if (!file.open(QIODevice::ReadOnly)) {
        qWarning() << "Missing Air icon" << name;
        return QByteArray();
    }
    return file.readAll();
}

QByteArray glyphSource(Glyph glyph)
{
    switch (glyph) {
    case Glyph::Microphone:
        return spriteGlyph("mic");
    case Glyph::Speaker:
        return drawnGlyph(speakerGlyph);
    case Glyph::Eye:
        return spriteGlyph("eye");
    case Glyph::Bubble:
        return drawnGlyph(bubbleGlyph);
    case Glyph::Expand:
        return drawnGlyph(expandGlyph);
    case Glyph::Collapse:
        return drawnGlyph(collapseGlyph);
    case Glyph::HangUp:
        return spriteGlyph("phone");
    }
    return QByteArray();
}

/**
 * A glyph in color, side by side device-independent pixels. crossed lays the slash of the
 * sprite's "-off" symbols (M4 4l16 16) over it, with a gap cut along the slash so it reads apart
 * from the lines under it.
 */
QPixmap glyphPixmap(Glyph glyph, bool crossed, const QColor& color, int side, qreal dpr)
{
    static QHash<QString, QPixmap> cache;
    const QString key = QStringLiteral("%1|%2|%3|%4|%5")
                            .arg(static_cast<int>(glyph))
                            .arg(crossed ? 1 : 0)
                            .arg(color.name(QColor::HexArgb))
                            .arg(side)
                            .arg(dpr);
    const auto cached = cache.constFind(key);
    if (cached != cache.constEnd()) {
        return cached.value();
    }

    QByteArray svg = glyphSource(glyph);
    svg.replace("#000000", color.name(QColor::HexRgb).toLatin1());
    QSvgRenderer renderer(svg);
    const int pixels = qMax(1, qCeil(side * dpr));
    QPixmap pixmap(pixels, pixels);
    pixmap.fill(Qt::transparent);
    {
        QPainter painter(&pixmap);
        painter.setRenderHint(QPainter::Antialiasing);
        painter.save();
        painter.setOpacity(color.alphaF());
        if (glyph == Glyph::HangUp) {
            // The handset of the sprite laid down with its ends to the ground, as the phones draw
            // the end of a call.
            painter.translate(pixels / 2.0, pixels / 2.0);
            painter.rotate(135);
            painter.translate(-pixels / 2.0, -pixels / 2.0);
        }
        renderer.render(&painter, QRectF(0, 0, pixels, pixels));
        painter.restore();
        if (crossed) {
            const qreal unit = pixels / 24.0;
            const QLineF slash(4 * unit, 4 * unit, 20 * unit, 20 * unit);
            painter.setCompositionMode(QPainter::CompositionMode_Clear);
            painter.setPen(QPen(Qt::black, 3 * Stroke::icon * unit, Qt::SolidLine, Qt::RoundCap));
            painter.drawLine(slash);
            painter.setCompositionMode(QPainter::CompositionMode_SourceOver);
            painter.setPen(QPen(color, Stroke::icon * unit, Qt::SolidLine, Qt::RoundCap));
            painter.drawLine(slash);
        }
    }
    pixmap.setDevicePixelRatio(dpr);
    cache.insert(key, pixmap);
    return pixmap;
}

/**
 * Three box blurs of radius pixels over the alpha of a black image, each way: close to a Gaussian
 * blur. The colour channels stay black, so the image stays a valid premultiplied one.
 */
void blurAlpha(QImage& image, int radius)
{
    const int width = image.width();
    const int height = image.height();
    if (radius < 1 || width == 0 || height == 0) {
        return;
    }
    const int window = 2 * radius + 1;
    QVector<int> line(qMax(width, height));
    for (int pass = 0; pass < 3; ++pass) {
        for (int y = 0; y < height; ++y) {
            QRgb* const row = reinterpret_cast<QRgb*>(image.scanLine(y));
            for (int x = 0; x < width; ++x) {
                line[x] = qAlpha(row[x]);
            }
            int sum = 0;
            for (int x = 0; x <= radius && x < width; ++x) {
                sum += line[x];
            }
            for (int x = 0; x < width; ++x) {
                row[x] = qRgba(0, 0, 0, sum / window);
                const int out = x - radius;
                const int in = x + radius + 1;
                if (out >= 0) {
                    sum -= line[out];
                }
                if (in < width) {
                    sum += line[in];
                }
            }
        }
        for (int x = 0; x < width; ++x) {
            for (int y = 0; y < height; ++y) {
                line[y] = qAlpha(reinterpret_cast<const QRgb*>(image.constScanLine(y))[x]);
            }
            int sum = 0;
            for (int y = 0; y <= radius && y < height; ++y) {
                sum += line[y];
            }
            for (int y = 0; y < height; ++y) {
                reinterpret_cast<QRgb*>(image.scanLine(y))[x] = qRgba(0, 0, 0, sum / window);
                const int out = y - radius;
                const int in = y + radius + 1;
                if (out >= 0) {
                    sum -= line[out];
                }
                if (in < height) {
                    sum += line[in];
                }
            }
        }
    }
}

/** The font of role scaled to pixels, for the words that shrink to fit. */
QFont fontAt(TextRole role, qreal pixels)
{
    QFont font = air().font(role);
    const qreal base = AirTokens::desktopType[static_cast<int>(role)].pixelSize;
    font.setPointSizeF(font.pointSizeF() * pixels / base);
    return font;
}

QString wordOf(AirCallControl::Kind kind)
{
    switch (kind) {
    case AirCallControl::Kind::Microphone:
        return AirStrings::text(Text::CallMicrophone);
    case AirCallControl::Kind::Sound:
        return AirStrings::text(Text::CallSound);
    case AirCallControl::Kind::SelfView:
        return AirStrings::text(Text::CallSelfView);
    case AirCallControl::Kind::Messages:
        return AirStrings::text(Text::CallMessages);
    case AirCallControl::Kind::FullScreen:
        return AirStrings::text(Text::CallFullScreen);
    case AirCallControl::Kind::End:
        break;
    }
    return AirStrings::text(Text::CallEnd);
}

Glyph glyphOf(AirCallControl::Kind kind, bool engaged)
{
    switch (kind) {
    case AirCallControl::Kind::Microphone:
        return Glyph::Microphone;
    case AirCallControl::Kind::Sound:
        return Glyph::Speaker;
    case AirCallControl::Kind::SelfView:
        return Glyph::Eye;
    case AirCallControl::Kind::Messages:
        return Glyph::Bubble;
    case AirCallControl::Kind::FullScreen:
        return engaged ? Glyph::Collapse : Glyph::Expand;
    case AirCallControl::Kind::End:
        break;
    }
    return Glyph::HangUp;
}
} // namespace

// ---- AirShadowedText ----

int AirShadowedText::reach()
{
    return shadowBlur + shadowDrop;
}

void AirShadowedText::paint(QPainter& painter, const QRect& rect, int flags, const QString& text,
                            const QColor& color)
{
    const qreal dpr = painter.device()->devicePixelRatioF();
    const QFont font = painter.font();
    const QString wanted = QStringLiteral("%1|%2|%3|%4|%5|%6")
                               .arg(text, font.key())
                               .arg(rect.width())
                               .arg(rect.height())
                               .arg(flags)
                               .arg(dpr);
    if (wanted != key) {
        key = wanted;
        QImage image(qCeil((rect.width() + 2 * shadowBlur) * dpr),
                     qCeil((rect.height() + 2 * shadowBlur) * dpr),
                     QImage::Format_ARGB32_Premultiplied);
        image.setDevicePixelRatio(dpr);
        image.fill(Qt::transparent);
        {
            QPainter shadowPainter(&image);
            shadowPainter.setFont(font);
            shadowPainter.setPen(dark(Color::FixedBlack));
            const QRect inImage(shadowBlur, shadowBlur, rect.width(), rect.height());
            shadowPainter.drawText(inImage, flags, text);
        }
        // A blur of 4 is a Gaussian of sigma 2 (as CSS reads it); three boxes of this radius come
        // closest to it.
        const qreal sigma = shadowBlur / 2.0 * dpr;
        blurAlpha(image, qMax(1, qRound((std::sqrt(4.0 * sigma * sigma + 1.0) - 1.0) / 2.0)));
        shadow = image;
    }
    painter.save();
    painter.setOpacity(painter.opacity() * shadowOpacity);
    painter.drawImage(QPointF(rect.x() - shadowBlur, rect.y() - shadowBlur + shadowDrop), shadow);
    painter.restore();
    painter.setPen(color);
    painter.drawText(rect, flags, text);
}

// ---- AirCallControl ----

AirCallControl::AirCallControl(Kind kind_, QWidget* parent)
    : QAbstractButton(parent)
    , controlKind{kind_}
{
    setCheckable(controlKind != Kind::End);
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::StrongFocus);
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    setEngaged(false);
    Translator::registerHandler(std::bind(&AirCallControl::retranslate, this), this);
    retranslate();
}

AirCallControl::~AirCallControl()
{
    Translator::unregister(this);
}

AirCallControl::Kind AirCallControl::kind() const
{
    return controlKind;
}

void AirCallControl::setEngaged(bool engaged_)
{
    engaged = controlKind != Kind::End && engaged_;
    if (isCheckable()) {
        // A screen reader hears checked while what the switch turns is on: the microphone, the
        // sound, our video and the messages while not engaged, the full screen while engaged.
        setChecked(controlKind == Kind::FullScreen ? engaged : !engaged);
    }
    update();
}

bool AirCallControl::isEngaged() const
{
    return engaged;
}

void AirCallControl::setNotice(bool notice_)
{
    if (notice != notice_) {
        notice = notice_;
        update();
    }
}

bool AirCallControl::hasNotice() const
{
    return notice;
}

int AirCallControl::controlHeight()
{
    return circleSide + wordGap + air().lineHeight(TextRole::Label);
}

QSize AirCallControl::sizeHint() const
{
    return QSize(Air::CallStage::columnWidth(), controlHeight() + AirShadowedText::reach());
}

QRectF AirCallControl::circleRect() const
{
    return QRectF((width() - circleSide) / 2.0, 0, circleSide, circleSide);
}

QRect AirCallControl::wordRect() const
{
    // The shadow of the word stays inside the control.
    const int reach = AirShadowedText::reach();
    return QRect(reach, circleSide + wordGap, qMax(0, width() - 2 * reach),
                 air().lineHeight(TextRole::Label));
}

bool AirCallControl::hitButton(const QPoint& pos) const
{
    return circleRect().contains(pos) || wordRect().contains(pos);
}

void AirCallControl::nextCheckState()
{
    // The state comes back from the call (the microphone muted, the full screen taken), not from
    // the click.
}

void AirCallControl::retranslate()
{
    setText(wordOf(controlKind));
    update();
}

void AirCallControl::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setRenderHint(QPainter::SmoothPixmapTransform);

    QRectF circle = circleRect();
    if (isDown()) {
        // As on the phones, a pressed circle gives a little.
        const qreal give = circle.width() * 0.015;
        circle.adjust(give, give, -give, -give);
    }
    QColor fill = dark(Color::MediaControl);
    QColor ink = dark(Color::FixedWhite);
    if (controlKind == Kind::End) {
        fill = dark(Color::DangerFill);
    } else if (engaged) {
        fill = dark(Color::FixedWhite);
        ink = dark(Color::LockTop);
    }
    painter.setPen(Qt::NoPen);
    painter.setBrush(fill);
    painter.drawEllipse(circle);

    const bool crossed = engaged && controlKind != Kind::FullScreen;
    const QPixmap glyph =
        glyphPixmap(glyphOf(controlKind, engaged), crossed, ink, glyphSide, devicePixelRatioF());
    const QPointF centre = circle.center();
    painter.drawPixmap(QPointF(centre.x() - glyphSide / 2.0, centre.y() - glyphSide / 2.0), glyph);

    if (notice) {
        // A dot on the rim at the top of the circle, on the side the line ends.
        const qreal along = circle.width() / 2.0 * M_SQRT1_2;
        const bool rightToLeft = layoutDirection() == Qt::RightToLeft;
        const qreal dotX = rightToLeft ? centre.x() - along : centre.x() + along;
        const QPointF dot(dotX, centre.y() - along);
        painter.setBrush(dark(Color::Live));
        painter.drawEllipse(dot, Size::dot / 2.0, Size::dot / 2.0);
    }

    const QRect wordBox = wordRect();
    const QString words = text();
    const qreal basePixels = AirTokens::desktopType[static_cast<int>(TextRole::Label)].pixelSize;
    const qreal fullWidth = QFontMetricsF(air().font(TextRole::Label)).horizontalAdvance(words);
    const qreal labelPixels =
        Air::CallStage::labelPixelSize(basePixels, fullWidth, wordBox.width());
    const QFont font = fontAt(TextRole::Label, labelPixels);
    painter.setFont(font);
    const QString shown = QFontMetricsF(font).elidedText(words, Qt::ElideRight, wordBox.width());
    word.paint(painter, wordBox, Qt::AlignHCenter | Qt::AlignVCenter | Qt::TextSingleLine, shown,
               dark(Color::FixedWhite));

    if (AirPaint::keyboardFocus(this)) {
        AirPaint::focusRing(painter, circle, Radius::round, dark(Color::Focus));
    }
}

// ---- AirCallHeader ----

AirCallHeader::AirCallHeader(QWidget* parent)
    : QWidget(parent)
{
    mark = new AirLinkMark(this);
    mark->setOnDark(true);
    mark->setFixedSize(mark->sizeHint());
    Translator::registerHandler(std::bind(&AirCallHeader::retranslate, this), this);
    retranslate();
}

AirCallHeader::~AirCallHeader()
{
    Translator::unregister(this);
}

void AirCallHeader::setName(const QString& name_)
{
    if (name != name_) {
        name = name_;
        retranslate();
    }
}

void AirCallHeader::setSeconds(qint64 seconds_)
{
    if (seconds != seconds_) {
        seconds = seconds_;
        retranslate();
    }
}

void AirCallHeader::setLink(Air::LinkState link_)
{
    if (link != link_) {
        link = link_;
        mark->setState(link);
        retranslate();
    }
}

int AirCallHeader::contentHeight()
{
    return air().lineHeight(TextRole::Display) + nameGap + air().lineHeight(TextRole::Body)
           + chipGap + air().lineHeight(TextRole::Label) + 2 * chipPadY;
}

QSize AirCallHeader::sizeHint() const
{
    return QSize(width(), contentHeight() + 2 * AirShadowedText::reach());
}

QRect AirCallHeader::chipRect() const
{
    const QFontMetricsF metrics(air().font(TextRole::Label));
    const int textWidth = qCeil(metrics.horizontalAdvance(linkWords));
    const int side = Air::CallStage::selfInset();
    const int chipWidth = qMin(chipPadX + Size::linkMark + chipInnerGap + textWidth + chipPadX,
                               qMax(0, width() - 2 * side));
    const int top = AirShadowedText::reach() + air().lineHeight(TextRole::Display) + nameGap
                    + air().lineHeight(TextRole::Body) + chipGap;
    return QRect((width() - chipWidth) / 2, top, chipWidth,
                 air().lineHeight(TextRole::Label) + 2 * chipPadY);
}

void AirCallHeader::placeMark()
{
    const QRect chip = chipRect();
    const bool rightToLeft = layoutDirection() == Qt::RightToLeft;
    const int x = rightToLeft ? chip.x() + chip.width() - chipPadX - mark->width()
                              : chip.x() + chipPadX;
    mark->move(x, chip.y() + (chip.height() - mark->height()) / 2);
}

void AirCallHeader::resizeEvent(QResizeEvent* event)
{
    QWidget::resizeEvent(event);
    placeMark();
}

void AirCallHeader::changeEvent(QEvent* event)
{
    QWidget::changeEvent(event);
    if (event->type() == QEvent::LayoutDirectionChange) {
        // The mark stands at the end the words start from.
        placeMark();
        update();
    }
}

void AirCallHeader::retranslate()
{
    linkWords = AirStrings::callLinkText(link);
    const QStringList said{name, Air::clockText(seconds), linkWords};
    setAccessibleName(said.join(QStringLiteral(", ")));
    placeMark();
    update();
}

void AirCallHeader::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    const QColor white = dark(Color::FixedWhite);
    const int side = Air::CallStage::selfInset();
    const int lineWidth = qMax(0, width() - 2 * side);
    const int flags = Qt::AlignHCenter | Qt::AlignVCenter | Qt::TextSingleLine;

    painter.setFont(air().font(TextRole::Display));
    const QRect nameRect(side, AirShadowedText::reach(), lineWidth,
                         air().lineHeight(TextRole::Display));
    const QString shownName =
        QFontMetricsF(painter.font()).elidedText(name, Qt::ElideRight, lineWidth);
    nameText.paint(painter, nameRect, flags, shownName, white);

    painter.setFont(air().font(TextRole::Body));
    const QRect timeRect(side, nameRect.y() + nameRect.height() + nameGap, lineWidth,
                         air().lineHeight(TextRole::Body));
    timeText.paint(painter, timeRect, flags, Air::clockText(seconds), white);

    const QRect chip = chipRect();
    painter.setPen(Qt::NoPen);
    painter.setBrush(dark(Color::Scrim));
    painter.drawRoundedRect(chip, chip.height() / 2.0, chip.height() / 2.0);
    const bool rightToLeft = layoutDirection() == Qt::RightToLeft;
    const int wordsStart = chipPadX + Size::linkMark + chipInnerGap;
    const int wordsX = rightToLeft ? chip.x() + chipPadX : chip.x() + wordsStart;
    const QRect wordsRect(wordsX, chip.y(), chip.width() - wordsStart - chipPadX, chip.height());
    painter.setFont(air().font(TextRole::Label));
    painter.setPen(white);
    const QString shownWords =
        QFontMetricsF(painter.font()).elidedText(linkWords, Qt::ElideRight, wordsRect.width());
    painter.drawText(wordsRect, flags, shownWords);
}
