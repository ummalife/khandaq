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

#include "airsettings.h"

#include "src/model/air/airconversation.h"
#include "src/model/air/airpreferences.h"
#include "src/persistence/igroupsettings.h"
#include "src/widget/air/airlocal.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"

#include <QBoxLayout>
#include <QLabel>
#include <QPainter>
#include <QStyle>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Radius = AirTokens::Radius;
namespace Space = AirTokens::Space;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

QLabel* makeLabel(TextRole role, Color color, QWidget* parent)
{
    auto* const label = new QLabel(parent);
    label->setTextFormat(Qt::PlainText);
    label->setWordWrap(true);
    label->setFont(air().font(role));
    AirPaint::textColor(label, air().color(color));
    return label;
}

/**
 * One key on the sunken ground of a tile, which sets it apart from the white page: the key in four
 * lines of four groups, then the button that takes it off its list.
 */
class KeyPlate : public QWidget
{
public:
    explicit KeyPlate(QWidget* parent)
        : QWidget(parent)
    {
        AirPaint::followTheme(this);
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        painter.setPen(Qt::NoPen);
        painter.setBrush(air().color(Color::Sunken));
        painter.drawRoundedRect(rect(), Radius::card, Radius::card);
    }
};

/** The head of a list of keys: its title with how many on one row, the way the other settings
 * rows show their value, and what the list means under it. */
void buildHead(QWidget* owner, QVBoxLayout* page, QLabel** title, QLabel** value, QLabel** note)
{
    auto* const row = new QHBoxLayout();
    row->setSpacing(Space::s12);
    *title = makeLabel(TextRole::Body, Color::Ink, owner);
    *value = makeLabel(TextRole::Body, Color::Ink2, owner);
    (*value)->setWordWrap(false);
    row->addWidget(*title, 1);
    row->addWidget(*value, 0, Qt::AlignTrailing | Qt::AlignVCenter);
    page->addLayout(row);
    *note = makeLabel(TextRole::Caption, Color::Ink3, owner);
    page->addWidget(*note);
}

void restyleHead(QLabel* title, QLabel* value, QLabel* note)
{
    // The labels take the colours of the theme now in use.
    AirPaint::textColor(title, air().color(Color::Ink));
    AirPaint::textColor(value, air().color(Color::Ink2));
    AirPaint::textColor(note, air().color(Color::Ink3));
    title->setFont(air().font(TextRole::Body));
    value->setFont(air().font(TextRole::Body));
    note->setFont(air().font(TextRole::Caption));
}

void clearList(QVBoxLayout* list)
{
    while (QLayoutItem* item = list->takeAt(0)) {
        if (QWidget* widget = item->widget()) {
            widget->deleteLater();
        }
        delete item;
    }
}

void addText(QVBoxLayout* list, QWidget* listBox, const QString& text)
{
    QLabel* const label = makeLabel(TextRole::Body, Color::Ink2, listBox);
    label->setText(text);
    list->addWidget(label);
}

/** The key on its plate, with the button that takes it off the list. */
AirButton* addKeyPlate(QVBoxLayout* list, QWidget* listBox, const QString& key,
                       const QString& action, const QString& objectName)
{
    const Qt::Alignment start = QStyle::visualAlignment(listBox->layoutDirection(), Qt::AlignLeft);
    auto* const plate = new KeyPlate(listBox);
    auto* const column = new QVBoxLayout(plate);
    column->setContentsMargins(Space::s16, Space::s16, Space::s16, Space::s16);
    column->setSpacing(Space::s12);
    QLabel* const keyLabel = makeLabel(TextRole::Mono, Color::Ink, plate);
    keyLabel->setWordWrap(false);
    keyLabel->setTextInteractionFlags(Qt::TextSelectableByMouse);
    // A key reads left to right in every language, like the ID itself.
    keyLabel->setLayoutDirection(Qt::LeftToRight);
    keyLabel->setAlignment(start);
    keyLabel->setText(Air::keyLines(key));
    keyLabel->setAccessibleName(AirStrings::text(Text::DossierKeyTitle));
    keyLabel->setAccessibleDescription(Air::keyGroups(key).join(QLatin1Char(' ')));
    auto* const button = new AirButton(AirButton::Kind::Plain, plate);
    button->setObjectName(objectName);
    button->setText(action);
    column->addWidget(keyLabel, 0, start);
    column->addWidget(button, 0, start);
    list->addWidget(plate);
    return button;
}

QVBoxLayout* buildList(QWidget* owner, QVBoxLayout* page, QWidget** listBox)
{
    *listBox = new QWidget(owner);
    auto* const list = new QVBoxLayout(*listBox);
    list->setContentsMargins(0, Space::s4, 0, 0);
    list->setSpacing(Space::s8);
    page->addWidget(*listBox);
    return list;
}
} // namespace

// ---- AirBlockedKeys ----

AirBlockedKeys::AirBlockedKeys(AirLocal& airLocal_, QWidget* parent)
    : QWidget(parent)
    , airLocal{airLocal_}
{
    setObjectName(QStringLiteral("blockedKeys"));
    auto* const page = new QVBoxLayout(this);
    page->setContentsMargins(0, 0, 0, 0);
    page->setSpacing(Space::s8);
    buildHead(this, page, &title, &value, &sectionNote);
    list = buildList(this, page, &listBox);

    connect(&airLocal, &AirLocal::blockListChanged, this, &AirBlockedKeys::rebuild);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirBlockedKeys::restyle);
    Translator::registerHandler(std::bind(&AirBlockedKeys::retranslate, this), this);
    retranslate();
}

AirBlockedKeys::~AirBlockedKeys()
{
    Translator::unregister(this);
}

void AirBlockedKeys::retranslate()
{
    title->setText(AirStrings::text(Text::BlockedTitle));
    sectionNote->setText(AirStrings::text(Text::BlockedSectionNote));
    rebuild();
}

void AirBlockedKeys::restyle()
{
    restyleHead(title, value, sectionNote);
    rebuild();
}

void AirBlockedKeys::rebuild()
{
    const QStringList keys = airLocal.blockedKeys();
    value->setText(keys.isEmpty() ? AirStrings::text(Text::BlockedNone) : QString::number(keys.size()));
    clearList(list);
    if (keys.isEmpty()) {
        addText(list, listBox, AirStrings::text(Text::BlockedEmpty));
        return;
    }
    addText(list, listBox, AirStrings::text(Text::BlockedNote));
    for (const QString& key : keys) {
        AirButton* const unblock = addKeyPlate(list, listBox, key, AirStrings::text(Text::Unblock),
                                               QStringLiteral("unblockKey"));
        connect(unblock, &AirButton::clicked, this, [this, key] {
            AirToast::post(this, AirStrings::text(Text::KeyUnblocked));
            airLocal.unblock(key);
        });
    }
    // Tab reaches Unblock where the keys stand, not after everything else in the window.
    AirFocus::inPlace(listBox);
}

// ---- AirHiddenMembers ----

AirHiddenMembers::AirHiddenMembers(IGroupSettings& settings_, QWidget* parent)
    : QWidget(parent)
    , settings{settings_}
{
    setObjectName(QStringLiteral("hiddenMembers"));
    auto* const page = new QVBoxLayout(this);
    page->setContentsMargins(0, 0, 0, 0);
    page->setSpacing(Space::s8);
    buildHead(this, page, &title, &value, &sectionNote);
    list = buildList(this, page, &listBox);

    // A member hidden or shown again from a group dossier shows here at once.
    settings.connectTo_blackListChanged(this, [this](const QStringList&) { rebuild(); });
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirHiddenMembers::restyle);
    Translator::registerHandler(std::bind(&AirHiddenMembers::retranslate, this), this);
    retranslate();
}

AirHiddenMembers::~AirHiddenMembers()
{
    Translator::unregister(this);
}

void AirHiddenMembers::retranslate()
{
    title->setText(AirStrings::text(Text::HiddenTitle));
    sectionNote->setText(AirStrings::text(Text::HiddenSectionNote));
    rebuild();
}

void AirHiddenMembers::restyle()
{
    restyleHead(title, value, sectionNote);
    rebuild();
}

void AirHiddenMembers::rebuild()
{
    const QStringList keys = Air::hiddenKeys(settings.getBlackList());
    value->setText(keys.isEmpty() ? AirStrings::text(Text::BlockedNone) : QString::number(keys.size()));
    clearList(list);
    if (keys.isEmpty()) {
        addText(list, listBox, AirStrings::text(Text::HiddenEmpty));
        return;
    }
    for (const QString& key : keys) {
        AirButton* const show =
            addKeyPlate(list, listBox, key, AirStrings::text(Text::GroupMemberShow),
                        QStringLiteral("showMember"));
        connect(show, &AirButton::clicked, this, [this, key] {
            AirToast::post(this, AirStrings::text(Text::HiddenShown));
            settings.setBlackList(Air::withoutHiddenKey(settings.getBlackList(), key));
        });
    }
    // Tab reaches Show where the keys stand, not after everything else in the window.
    AirFocus::inPlace(listBox);
}
