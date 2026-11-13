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

#include "airband.h"

#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"

#include <QEvent>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QLineEdit>
#include <QPainter>

using AirStrings::Text;
using AirTokens::Color;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;

namespace {
// The band is as tall as the conversation header, so the two read as one line across the window.
constexpr int bandHeight = 56;
// You and your node take the width of the list column below them, less the band's padding, so the
// command line starts where the conversation does.
constexpr int selfBlockWidth = Size::listColumn - 2 * Space::s16;
} // namespace

AirBand::AirBand(QWidget* parent)
    : QWidget(parent)
{
    setObjectName(QStringLiteral("airBand"));
    setFixedHeight(bandHeight);
    setAutoFillBackground(false);

    auto* layout = new QHBoxLayout(this);
    layout->setContentsMargins(Space::s16, 0, Space::s12, 0);
    layout->setSpacing(Space::s16);

    auto* selfBlock = new QWidget(this);
    selfBlock->setMaximumWidth(selfBlockWidth);
    selfBlock->setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Fixed);
    auto* selfLayout = new QHBoxLayout(selfBlock);
    selfLayout->setContentsMargins(0, 0, 0, 0);
    selfLayout->setSpacing(Space::s4);
    self = new AirSelfButton(selfBlock);
    self->setObjectName(QStringLiteral("bandSelf"));
    statusButton = new AirIconButton(QStringLiteral("chevron-down"), selfBlock);
    selfLayout->addWidget(self, 1);
    selfLayout->addWidget(statusButton);
    layout->addWidget(selfBlock);

    command = new AirCommandField(this);
    command->lineEdit()->installEventFilter(this);
    layout->addWidget(command, 1);

    layout->addStretch(0);

    auto* actions = new QHBoxLayout();
    actions->setSpacing(Space::s6);
    requestsPill = new AirPill(AirPill::Kind::Plain, this);
    transitPill = new AirPill(AirPill::Kind::Transit, this);
    newContactButton = new AirIconButton(QStringLiteral("user-plus"), this);
    groupsButton = new AirIconButton(QStringLiteral("users"), this);
    transfersButton = new AirIconButton(QStringLiteral("file"), this);
    favoritesButton = new AirIconButton(QStringLiteral("bookmark"), this);
    favoritesButton->setCheckable(true);
    settingsButton = new AirIconButton(QStringLiteral("settings"), this);
    actions->addWidget(requestsPill);
    actions->addWidget(transitPill);
    actions->addSpacing(Space::s6);
    actions->addWidget(newContactButton);
    actions->addWidget(groupsButton);
    actions->addWidget(transfersButton);
    actions->addWidget(favoritesButton);
    actions->addWidget(settingsButton);
    layout->addLayout(actions);

    connect(self, &AirSelfButton::clicked, this, &AirBand::profileRequested);
    connect(self->networkLine(), &AirNetworkLine::clicked, this, &AirBand::networkRequested);
    connect(statusButton, &AirIconButton::clicked, this, [this] {
        emit statusMenuRequested(statusButton->mapToGlobal(QPoint(0, statusButton->height())));
    });
    connect(command->lineEdit(), &QLineEdit::textChanged, this, &AirBand::onCommandEdited);
    connect(requestsPill, &AirPill::clicked, this,
            [this] { emit filterToggled(Air::Filter::Requests); });
    connect(transitPill, &AirPill::clicked, this,
            [this] { emit filterToggled(Air::Filter::Transit); });
    connect(favoritesButton, &AirIconButton::clicked, this,
            [this] { emit filterToggled(Air::Filter::Favorites); });
    connect(newContactButton, &AirIconButton::clicked, this, &AirBand::newContactRequested);
    connect(groupsButton, &AirIconButton::clicked, this, &AirBand::groupsRequested);
    connect(transfersButton, &AirIconButton::clicked, this, &AirBand::transfersRequested);
    connect(settingsButton, &AirIconButton::clicked, this, &AirBand::settingsRequested);
    connect(&AirStyle::instance(), &AirStyle::changed, this, [this] { update(); });

    retranslate();
    Translator::registerHandler(std::bind(&AirBand::retranslate, this), this);
}

AirBand::~AirBand()
{
    Translator::unregister(this);
}

void AirBand::retranslate()
{
    self->setAccessibleName(AirStrings::text(Text::You));
    self->setAccessibleDescription(AirStrings::text(Text::YouHint));
    self->setToolTip(AirStrings::text(Text::YouHint));
    self->networkLine()->setToolTip(AirStrings::text(Text::NetHint));
    self->networkLine()->setAccessibleDescription(AirStrings::text(Text::NetHint));
    statusButton->setToolTip(AirStrings::text(Text::StatusMenu));
    statusButton->setAccessibleName(AirStrings::text(Text::StatusMenu));
    command->setPlaceholder(AirStrings::text(Text::CommandPlaceholder));
    command->setKeyHint(AirStrings::text(Text::CommandKeyHint));
    requestsPill->setText(AirStrings::text(Text::FilterRequests));
    transitPill->setText(AirStrings::text(Text::FilterTransit));

    const auto name = [](AirIconButton* button, Text text) {
        button->setToolTip(AirStrings::text(text));
        button->setAccessibleName(AirStrings::text(text));
    };
    name(newContactButton, Text::NewContact);
    name(groupsButton, Text::Groups);
    name(transfersButton, Text::FileTransfers);
    name(favoritesButton, Text::Favorites);
    name(settingsButton, Text::Settings);
    setNetwork(network);
    requestsPill->updateGeometry();
    transitPill->updateGeometry();
}

void AirBand::setSelf(const QString& name, const QPixmap& avatar)
{
    self->setName(name);
    self->setAvatar(avatar);
}

void AirBand::setNetwork(Air::NetworkState state)
{
    // Only a new state cross-fades; a new language just changes the words.
    const bool newState = state != network;
    network = state;
    AirNetworkLine::Dot dot = AirNetworkLine::Dot::Live;
    switch (state) {
    case Air::NetworkState::Direct:
    case Air::NetworkState::Relay:
        dot = AirNetworkLine::Dot::Live;
        break;
    case Air::NetworkState::Connecting:
        dot = AirNetworkLine::Dot::Waiting;
        break;
    case Air::NetworkState::Offline:
        dot = AirNetworkLine::Dot::Danger;
        break;
    }
    self->networkLine()->setState(AirStrings::networkText(state), dot, newState);
}

void AirBand::setCounts(const Air::Counts& counts)
{
    requestsPill->setCount(counts.requests);
    transitPill->setCount(counts.transit);
}

void AirBand::setFilter(Air::Filter filter)
{
    requestsPill->setChecked(filter == Air::Filter::Requests);
    transitPill->setChecked(filter == Air::Filter::Transit);
    favoritesButton->setChecked(filter == Air::Filter::Favorites);
}

QLineEdit* AirBand::commandLine() const
{
    return command->lineEdit();
}

void AirBand::focusCommandLine()
{
    command->lineEdit()->setFocus(Qt::ShortcutFocusReason);
    command->lineEdit()->selectAll();
}

QSize AirBand::minimumSizeHint() const
{
    QSize hint = QWidget::minimumSizeHint();
    hint.setHeight(bandHeight);
    return hint;
}

void AirBand::onCommandEdited(const QString& text)
{
    const Air::Command parsed = Air::Command::parse(text);
    if (parsed.type == Air::Command::Type::ContactAddress) {
        command->lineEdit()->clear();
        emit contactAddressEntered(parsed.value);
        return;
    }
    emit idStartChanged(Air::commandIdCandidate(text));
    emit queryChanged(parsed.type == Air::Command::Type::Query ? parsed.value : QString());
}

bool AirBand::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == command->lineEdit() && event->type() == QEvent::KeyPress) {
        auto* key = static_cast<QKeyEvent*>(event);
        switch (key->key()) {
        case Qt::Key_Down:
            emit navigateRequested(1);
            return true;
        case Qt::Key_Up:
            emit navigateRequested(-1);
            return true;
        case Qt::Key_Return:
        case Qt::Key_Enter: {
            // The start of an ID goes to New contact, which says what is missing or wrong.
            const QString idStart = Air::commandIdCandidate(command->lineEdit()->text());
            if (!idStart.isEmpty()) {
                command->lineEdit()->clear();
                emit contactAddressEntered(idStart);
                return true;
            }
            emit activateRequested();
            return true;
        }
        case Qt::Key_Escape:
            if (!command->lineEdit()->text().isEmpty()) {
                command->lineEdit()->clear();
            } else {
                command->lineEdit()->clearFocus();
            }
            return true;
        default:
            break;
        }
    }
    return QWidget::eventFilter(watched, event);
}

void AirBand::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    const AirStyle& air = AirStyle::instance();
    painter.fillRect(rect(), air.color(Color::Surface));
    painter.fillRect(QRect(0, height() - 1, width(), 1), air.color(Color::Line));
}
