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

#include "airstyle.h"

#include "src/persistence/settings.h"
#include "src/platform/systemappearance.h"
#include "src/widget/style.h"
#include "src/widget/translator.h"

#include <QApplication>
#include <QDebug>
#include <QFile>
#include <QFontDatabase>
#include <QGraphicsOpacityEffect>
#include <QGuiApplication>
#include <QLabel>
#include <QPainter>
#include <QPalette>
#include <QPropertyAnimation>
#include <QScreen>
#include <QSvgRenderer>
#include <QTimer>
#include <QWidget>
#include <QtMath>

#include <algorithm>

namespace {

// How often the system appearance is asked while the app follows it. A change in System Settings
// therefore shows within this time, without hooking into each platform's notifications.
constexpr int systemPollInterval = 2000;

qreal pointsFromPixels(qreal pixels)
{
    const QScreen* screen = QGuiApplication::primaryScreen();
    const qreal dpi = screen != nullptr ? screen->logicalDotsPerInchY() : 96.0;
    return pixels * 72.0 / dpi;
}

QEasingCurve bezier(const double (&points)[4])
{
    QEasingCurve curve(QEasingCurve::BezierSpline);
    curve.addCubicBezierSegment(QPointF(points[0], points[1]), QPointF(points[2], points[3]),
                                QPointF(1.0, 1.0));
    return curve;
}

SystemAppearance::Appearance appearanceFor(Settings::ThemeMode mode)
{
    switch (mode) {
    case Settings::ThemeMode::Light:
        return SystemAppearance::Appearance::Light;
    case Settings::ThemeMode::Dark:
        return SystemAppearance::Appearance::Dark;
    case Settings::ThemeMode::System:
        break;
    }
    return SystemAppearance::Appearance::System;
}

} // namespace

AirStyle& AirStyle::instance()
{
    static AirStyle* style = new AirStyle();
    return *style;
}

AirStyle::AirStyle()
    : QObject(nullptr)
    , reduced{SystemAppearance::prefersReducedMotion()}
{
    // The application font follows the language: Arabic text leads with Plex Sans Arabic.
    Translator::registerHandler(
        [this] {
            QApplication::setFont(font(AirTokens::TextRole::Body));
            emit changed();
        },
        this);
}

void AirStyle::installFonts()
{
    for (const char* resource : AirTokens::fontResources) {
        if (QFontDatabase::addApplicationFont(QString::fromLatin1(resource)) == -1) {
            qWarning() << "Couldn't load font" << resource;
        }
    }
#if QT_VERSION < QT_VERSION_CHECK(5, 13, 0)
    substituteTextFamilies();
#endif
    QApplication::setFont(instance().font(AirTokens::TextRole::Body));
}

void AirStyle::substituteTextFamilies()
{
    const QString text = QString::fromLatin1(AirTokens::textFamily);
    const QString arabic = QString::fromLatin1(AirTokens::arabicFamily);
    // The table keeps the names in lower case.
    if (!QFont::substitutes(text).contains(arabic, Qt::CaseInsensitive)) {
        QFont::insertSubstitution(text, arabic);
    }
    if (!QFont::substitutes(arabic).contains(text, Qt::CaseInsensitive)) {
        QFont::insertSubstitution(arabic, text);
    }
}

void AirStyle::refresh()
{
    emit changed();
}

bool AirStyle::isDark() const
{
    return dark;
}

bool AirStyle::reducedMotion() const
{
    return reduced;
}

QColor AirStyle::color(AirTokens::Color role) const
{
    const int index = static_cast<int>(role);
    return QColor::fromRgba(dark ? AirTokens::darkColors[index] : AirTokens::lightColors[index]);
}

QColor AirStyle::tone(int index) const
{
    constexpr int count = AirTokens::toneCount;
    const int wrapped = ((index % count) + count) % count;
    return color(AirTokens::tones[wrapped]);
}

QFont AirStyle::font(AirTokens::TextRole role) const
{
    const AirTokens::TypeStyle& type = AirTokens::desktopType[static_cast<int>(role)];
    QFont result;
    if (type.family == AirTokens::FontFamily::Mono) {
        const QString mono = QString::fromLatin1(AirTokens::monoFamily);
        result.setFamily(mono);
#if QT_VERSION >= QT_VERSION_CHECK(5, 13, 0)
        // The copy of the application font keeps its fallback list, and a run in a text layout
        // takes that list before the family: the list names only the mono family. Before Qt 5.13
        // a font has no such list, and the family alone decides.
        result.setFamilies({mono});
#endif
        result.setStyleHint(QFont::Monospace);
    } else {
        const QString text = QString::fromLatin1(AirTokens::textFamily);
        const QString arabic = QString::fromLatin1(AirTokens::arabicFamily);
        const bool rightToLeft = QGuiApplication::layoutDirection() == Qt::RightToLeft;
        const QStringList families =
            rightToLeft ? QStringList{arabic, text} : QStringList{text, arabic};
        result.setFamily(families.first());
#if QT_VERSION >= QT_VERSION_CHECK(5, 13, 0)
        result.setFamilies(families);
#endif
        result.setStyleHint(QFont::SansSerif);
    }
    result.setPointSizeF(pointsFromPixels(type.pixelSize));
    result.setWeight(type.weight);
    return result;
}

int AirStyle::lineHeight(AirTokens::TextRole role) const
{
    return qRound(AirTokens::desktopType[static_cast<int>(role)].lineHeight);
}

QPixmap AirStyle::icon(const QString& name, const QColor& color, int size, qreal devicePixelRatio) const
{
    const QString key =
        QStringLiteral("%1|%2|%3|%4").arg(name, color.name(QColor::HexArgb)).arg(size).arg(devicePixelRatio);
    const auto cached = iconCache.constFind(key);
    if (cached != iconCache.constEnd()) {
        return cached.value();
    }

    QFile file(QStringLiteral(":/img/air/%1.svg").arg(name));
    if (!file.open(QIODevice::ReadOnly)) {
        qWarning() << "Missing Air icon" << name;
        return QPixmap();
    }
    // The set is stroked in black so one file serves every colour.
    QByteArray svg = file.readAll();
    svg.replace("#000000", color.name(QColor::HexRgb).toLatin1());

    QSvgRenderer renderer(svg);
    const int pixels = qMax(1, qCeil(size * devicePixelRatio));
    QPixmap pixmap(pixels, pixels);
    pixmap.fill(Qt::transparent);
    {
        QPainter painter(&pixmap);
        painter.setRenderHint(QPainter::Antialiasing);
        painter.setOpacity(color.alphaF());
        renderer.render(&painter, QRectF(0, 0, pixels, pixels));
    }
    pixmap.setDevicePixelRatio(devicePixelRatio);
    iconCache.insert(key, pixmap);
    return pixmap;
}

int AirStyle::duration(int milliseconds) const
{
    return reduced ? std::min(milliseconds, AirTokens::Motion::reducedMaxDuration) : milliseconds;
}

QEasingCurve AirStyle::easeOut() const
{
    return bezier(AirTokens::Motion::easeOut);
}

QEasingCurve AirStyle::easeStandard() const
{
    return bezier(AirTokens::Motion::easeStandard);
}

void AirStyle::follow(Settings& settings_, Style& style_, QWidget* window_)
{
    settings = &settings_;
    style = &style_;
    window = window_;

    if (pollTimer == nullptr) {
        pollTimer = new QTimer(this);
        pollTimer->setInterval(systemPollInterval);
        connect(pollTimer, &QTimer::timeout, this, &AirStyle::pollSystem);
        connect(settings, &Settings::themeModeChanged, this, [this] { apply(true); });
    }
    pollTimer->start();
    apply(false);
}

void AirStyle::pollSystem()
{
    if (settings == nullptr) {
        return;
    }
    const bool reducedNow = SystemAppearance::prefersReducedMotion();
    const bool motionChanged = reducedNow != reduced;
    reduced = reducedNow;
    if (settings->getThemeMode() == Settings::ThemeMode::System
        && Style::isDarkTheme(*settings) != dark) {
        apply(true);
    } else if (motionChanged) {
        emit changed();
    }
}

void AirStyle::apply(bool animate)
{
    if (settings == nullptr || style == nullptr) {
        return;
    }
    SystemAppearance::setApplicationAppearance(appearanceFor(settings->getThemeMode()));
    reduced = SystemAppearance::prefersReducedMotion();

    const bool nextDark = Style::isDarkTheme(*settings);
    if (applied && nextDark == dark) {
        return;
    }

    QPixmap before;
    if (animate && applied && window && window->isVisible()) {
        before = window->grab();
    }

    dark = nextDark;
    applied = true;
    iconCache.clear();
    applyApplicationPalette();
    // The older screens read palette.ini of the theme folder that matches dark.
    style->setThemeColor(*settings, settings->getThemeColor());
    style->applyTheme();
    emit changed();

    if (!before.isNull()) {
        crossFade(before);
    }
}

void AirStyle::applyApplicationPalette()
{
    using AirTokens::Color;
    QPalette palette;
    const auto set = [&palette](QPalette::ColorRole role, const QColor& value) {
        palette.setColor(QPalette::All, role, value);
    };
    set(QPalette::Window, color(Color::Surface));
    set(QPalette::WindowText, color(Color::Ink));
    set(QPalette::Base, color(Color::Raised));
    set(QPalette::AlternateBase, color(Color::Sunken));
    set(QPalette::ToolTipBase, color(Color::Ink));
    set(QPalette::ToolTipText, color(Color::OnInk));
    set(QPalette::PlaceholderText, color(Color::Ink3));
    set(QPalette::Text, color(Color::Ink));
    set(QPalette::Button, color(Color::Raised));
    set(QPalette::ButtonText, color(Color::Ink));
    set(QPalette::BrightText, color(Color::OnInk));
    set(QPalette::Light, color(Color::Raised));
    set(QPalette::Midlight, color(Color::Line));
    set(QPalette::Mid, color(Color::LineStrong));
    set(QPalette::Dark, color(Color::LineStrong));
    set(QPalette::Shadow, color(Color::Shadow));
    set(QPalette::Highlight, color(Color::Selected));
    set(QPalette::HighlightedText, color(Color::Ink));
    set(QPalette::Link, color(Color::Ink));
    set(QPalette::LinkVisited, color(Color::Ink2));
    for (QPalette::ColorRole role : {QPalette::WindowText, QPalette::Text, QPalette::ButtonText}) {
        palette.setColor(QPalette::Disabled, role, color(Color::Ink3));
    }
    QApplication::setPalette(palette);
}

void AirStyle::changeUnderCrossFade(const std::function<void()>& change)
{
    QPixmap before;
    if (window && window->isVisible()) {
        before = window->grab();
    }
    change();
    if (!before.isNull()) {
        crossFade(before);
    }
}

void AirStyle::crossFade(const QPixmap& before)
{
    if (!window) {
        return;
    }
    // The old theme lies over the new one and fades away: a theme change never jumps.
    auto* cover = new QLabel(window);
    cover->setAttribute(Qt::WA_TransparentForMouseEvents);
    cover->setPixmap(before);
    cover->setGeometry(window->rect());
    auto* opacity = new QGraphicsOpacityEffect(cover);
    opacity->setOpacity(1.0);
    cover->setGraphicsEffect(opacity);
    cover->show();
    cover->raise();

    auto* fade = new QPropertyAnimation(opacity, "opacity", cover);
    fade->setDuration(duration(AirTokens::Motion::state));
    fade->setStartValue(1.0);
    fade->setEndValue(0.0);
    fade->setEasingCurve(easeStandard());
    connect(fade, &QPropertyAnimation::finished, cover, &QObject::deleteLater);
    fade->start();
}
