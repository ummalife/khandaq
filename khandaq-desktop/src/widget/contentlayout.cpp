/*
    Copyright © 2015-2019 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "contentlayout.h"
#include "style.h"
#include "src/persistence/settings.h"
#include "src/widget/air/airstyle.h"
#include <QFrame>
#include <QStyleFactory>

ContentLayout::ContentLayout(Settings& settings_, Style& style_)
    : QVBoxLayout()
    , settings{settings_}
    , style{style_}
{
    init();
}

ContentLayout::ContentLayout(Settings& settings_, Style& style_, QWidget* parent)
    : QVBoxLayout(parent)
    , settings{settings_}
    , style{style_}
{
    init();

    // The conversation column stands on the surface of the current theme, in both themes.
    const auto applyPalette = [parent] {
        using AirTokens::Color;
        const AirStyle& air = AirStyle::instance();
        QPalette palette = parent->palette();
        palette.setBrush(QPalette::WindowText, air.color(Color::Ink));
        palette.setBrush(QPalette::Button, air.color(Color::Raised));
        palette.setBrush(QPalette::Light, air.color(Color::Raised));
        palette.setBrush(QPalette::Midlight, air.color(Color::Line));
        palette.setBrush(QPalette::Dark, air.color(Color::LineStrong));
        palette.setBrush(QPalette::Mid, air.color(Color::LineStrong));
        palette.setBrush(QPalette::Text, air.color(Color::Ink));
        palette.setBrush(QPalette::BrightText, air.color(Color::OnInk));
        palette.setBrush(QPalette::ButtonText, air.color(Color::Ink));
        palette.setBrush(QPalette::Base, air.color(Color::Raised));
        palette.setBrush(QPalette::Window, air.color(Color::Surface));
        palette.setBrush(QPalette::Shadow, air.color(Color::Shadow));
        palette.setBrush(QPalette::AlternateBase, air.color(Color::Sunken));
        palette.setBrush(QPalette::ToolTipBase, air.color(Color::Ink));
        palette.setBrush(QPalette::ToolTipText, air.color(Color::OnInk));

        palette.setBrush(QPalette::Disabled, QPalette::WindowText, air.color(Color::Ink3));
        palette.setBrush(QPalette::Disabled, QPalette::Text, air.color(Color::Ink3));
        palette.setBrush(QPalette::Disabled, QPalette::ButtonText, air.color(Color::Ink3));

        parent->setPalette(palette);
    };
    applyPalette();
    connect(&AirStyle::instance(), &AirStyle::changed, this, applyPalette);
}

ContentLayout::~ContentLayout()
{
    clear();

    mainHead->deleteLater();
    mainContent->deleteLater();
}

void ContentLayout::reloadTheme()
{
#ifndef Q_OS_MAC
    mainHead->setStyleSheet(style.getStylesheet("settings/mainHead.css", settings));
    mainContent->setStyleSheet(style.getStylesheet("window/general.css", settings));
#endif
}

void ContentLayout::clear()
{
    QLayoutItem* item;
    while ((item = mainHead->layout()->takeAt(0)) != nullptr) {
        item->widget()->hide();
        item->widget()->setParent(nullptr);
        delete item;
    }

    while ((item = mainContent->layout()->takeAt(0)) != nullptr) {
        item->widget()->hide();
        item->widget()->setParent(nullptr);
        delete item;
    }

    setFlush(false);
}

void ContentLayout::setFlush(bool flush)
{
    mainContent->layout()->setContentsMargins(flush ? QMargins() : contentMargins);
    mainHLine.setVisible(!flush);
}

void ContentLayout::init()
{
    setMargin(0);
    setSpacing(0);

    mainHead = new QWidget();
    mainHead->setLayout(new QVBoxLayout);
    mainHead->setSizePolicy(QSizePolicy::MinimumExpanding, QSizePolicy::Fixed);
    mainHead->layout()->setMargin(0);
    mainHead->layout()->setSpacing(0);
    mainHead->setMouseTracking(true);

    mainHLine.setFrameShape(QFrame::HLine);
    mainHLine.setFrameShadow(QFrame::Plain);
    const auto applyLineColor = [this] {
        QPalette palette = mainHLine.palette();
        palette.setBrush(QPalette::WindowText, AirStyle::instance().color(AirTokens::Color::Line));
        mainHLine.setPalette(palette);
    };
    applyLineColor();
    connect(&AirStyle::instance(), &AirStyle::changed, this, applyLineColor);

    mainContent = new QWidget();
    mainContent->setLayout(new QVBoxLayout);
    mainContent->setSizePolicy(QSizePolicy::MinimumExpanding, QSizePolicy::MinimumExpanding);
    contentMargins = mainContent->layout()->contentsMargins();

    if (QStyleFactory::keys().contains(settings.getStyle())
        && settings.getStyle() != "None") {
        mainHead->setStyle(QStyleFactory::create(settings.getStyle()));
        mainContent->setStyle(QStyleFactory::create(settings.getStyle()));
    }

    connect(&style, &Style::themeReload, this, &ContentLayout::reloadTheme);

    reloadTheme();

    mainHLineLayout.addSpacing(4);
    mainHLineLayout.addWidget(&mainHLine);
    mainHLineLayout.addSpacing(5);

    addWidget(mainHead);
    addLayout(&mainHLineLayout);
    addWidget(mainContent);
}
