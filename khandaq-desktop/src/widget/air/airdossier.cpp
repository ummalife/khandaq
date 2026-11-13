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

#include "airdossier.h"

#include "src/model/air/airmodel.h"
#include "src/widget/air/airchatparts.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"

#include <QApplication>
#include <QBoxLayout>
#include <QClipboard>
#include <QFileInfo>
#include <QGraphicsOpacityEffect>
#include <QKeyEvent>
#include <QLabel>
#include <QLinearGradient>
#include <QLocale>
#include <QMouseEvent>
#include <QPainter>
#include <QScrollArea>
#include <QStyle>
#include <QTimer>
#include <QVariantAnimation>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Elevation = AirTokens::Elevation;
namespace Motion = AirTokens::Motion;
namespace Radius = AirTokens::Radius;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;
namespace Stroke = AirTokens::Stroke;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

void paintText(QWidget* widget, Color color)
{
    AirPaint::textColor(widget, air().color(color));
}

QLabel* makeLabel(TextRole role, Color color, QWidget* parent, bool wrap = true)
{
    auto* const label = new QLabel(parent);
    label->setTextFormat(Qt::PlainText);
    label->setWordWrap(wrap);
    label->setFont(air().font(role));
    paintText(label, color);
    return label;
}

/** The two columns of sections stack once the dossier is narrower than this. */
constexpr int twoColumnWidth = 720;

/** kq-axis: 0, 6, 12, 18 and 24 under the big day strip, at the edges of their hours. */
class HourAxis : public QWidget
{
public:
    explicit HourAxis(QWidget* parent)
        : QWidget(parent)
    {
        setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
        setFixedHeight(air().lineHeight(TextRole::Caption) + Space::s4);
        AirPaint::followTheme(this);
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setFont(air().font(TextRole::Caption));
        painter.setPen(air().color(Color::Ink3));
        const bool rightToLeft = layoutDirection() == Qt::RightToLeft;
        // The strip keeps 3 px around its cells for the outline of the current hour.
        const qreal inset = 3;
        const qreal span = width() - 2 * inset;
        const QFontMetricsF metrics(painter.font());
        for (int hour = 0; hour <= 24; hour += 6) {
            const QString label = QString::number(hour);
            const qreal labelWidth = metrics.horizontalAdvance(label);
            // From the start edge: 0 starts at its hour, 24 ends at it, the others centre on it.
            const qreal at = inset + span * hour / 24.0;
            const qreal fromStart = hour == 0    ? at
                                    : hour == 24 ? at - labelWidth
                                                 : at - labelWidth / 2.0;
            const qreal x = rightToLeft ? width() - fromStart - labelWidth : fromStart;
            painter.drawText(QPointF(x, Space::s4 + metrics.ascent()), label);
        }
    }
};
} // namespace

AirDossier::AirDossier(QWidget* parent)
    : AirFoldPanel(parent)
{
    QWidget* const pageWidget = page();
    QVBoxLayout* const sections = pageLayout();

    // The person: tile, name, status message and "Rename".
    auto* const top = new QHBoxLayout();
    top->setSpacing(Space::s12);
    tile = new AirTile(Size::field, pageWidget);
    top->addWidget(tile, 0, Qt::AlignTop);
    auto* const names = new QVBoxLayout();
    names->setSpacing(Space::s2);
    nameLabel = makeLabel(TextRole::Heading, Color::Ink, pageWidget);
    statusLabel = makeLabel(TextRole::Caption, Color::Ink2, pageWidget);
    names->addWidget(nameLabel);
    names->addWidget(statusLabel);
    top->addLayout(names, 1);
    renameButton = new AirButton(AirButton::Kind::Quiet, pageWidget);
    top->addWidget(renameButton, 0, Qt::AlignTop);
    sections->addLayout(top);

    columns = new QBoxLayout(QBoxLayout::LeftToRight);
    columns->setSpacing(Space::s32);
    auto* const left = new QVBoxLayout();
    left->setSpacing(Space::s16);
    auto* const right = new QVBoxLayout();
    right->setSpacing(Space::s16);
    columns->addLayout(left, 1);
    columns->addLayout(right, 1);
    sections->addLayout(columns);

    // Link: the picture first, then the same in words.
    QVBoxLayout* body = section(left, &linkTitle);
    path = new AirPathPicture(pageWidget);
    body->addWidget(path);
    linkText = makeLabel(TextRole::Caption, Color::Ink2, pageWidget);
    body->addWidget(linkText);

    // The last day, hour by hour.
    body = section(left, &dayTitle);
    strip = new AirDayStrip(true, pageWidget);
    body->addWidget(strip);
    axis = new HourAxis(pageWidget);
    body->addWidget(axis);
    bestText = makeLabel(TextRole::Caption, Color::Ink2, pageWidget);
    body->addWidget(bestText);
    dayNote = makeLabel(TextRole::Caption, Color::Ink3, pageWidget);
    body->addWidget(dayNote);
    left->addStretch();

    // Notifications: whether they reach the person, and mute.
    body = section(right, &notifyTitle);
    noWake = makeLabel(TextRole::Caption, Color::Ink2, pageWidget);
    body->addWidget(noWake);
    auto* const muteRow = new QHBoxLayout();
    muteRow->setContentsMargins(0, Space::s4, 0, 0);
    muteLabel = makeLabel(TextRole::Body, Color::Ink, pageWidget, false);
    muteSwitch = new AirSwitch(pageWidget);
    muteRow->addWidget(muteLabel, 1);
    muteRow->addWidget(muteSwitch);
    body->addLayout(muteRow);
    mutedNote = makeLabel(TextRole::Caption, Color::Ink2, pageWidget);
    body->addWidget(mutedNote);

    // Key check.
    body = section(right, &verifyTitle);
    verifyText = makeLabel(TextRole::Caption, Color::Ink2, pageWidget);
    body->addWidget(verifyText);
    verifyBox = new QWidget(pageWidget);
    auto* const verifyLayout = new QVBoxLayout(verifyBox);
    verifyLayout->setContentsMargins(0, Space::s4, 0, 0);
    verifyLayout->setSpacing(Space::s8);
    verifyMessage = makeLabel(TextRole::Caption, Color::Ink2, verifyBox);
    verifyKey = makeLabel(TextRole::Mono, Color::Ink, verifyBox);
    verifyKey->setTextInteractionFlags(Qt::TextSelectableByMouse);
    matchButton = new AirButton(AirButton::Kind::Ink, verifyBox);
    verifyLayout->addWidget(verifyMessage);
    verifyLayout->addWidget(verifyKey);
    verifyLayout->addWidget(matchButton, 0, Qt::AlignLeading);
    verifyBox->hide();
    body->addWidget(verifyBox);
    verifyButton = new AirButton(AirButton::Kind::Plain, pageWidget);
    body->addWidget(verifyButton, 0, Qt::AlignLeading);

    // Key: in groups of four, to copy or show as a QR code.
    body = section(right, &keyTitle);
    keyText = makeLabel(TextRole::Mono, Color::Ink, pageWidget);
    keyText->setTextInteractionFlags(Qt::TextSelectableByMouse);
    body->addWidget(keyText);
    auto* const keyActions = new QHBoxLayout();
    keyActions->setContentsMargins(0, Space::s4, 0, 0);
    keyActions->setSpacing(Space::s8);
    copyButton = new AirButton(AirButton::Kind::Plain, pageWidget);
    qrButton = new AirButton(AirButton::Kind::Plain, pageWidget);
    keyActions->addWidget(copyButton);
    keyActions->addWidget(qrButton);
    keyActions->addStretch();
    body->addLayout(keyActions);

    // Files of the conversation.
    filesSection = new QWidget(pageWidget);
    auto* const filesColumn = new QVBoxLayout(filesSection);
    filesColumn->setContentsMargins(0, 0, 0, 0);
    filesColumn->setSpacing(Space::s4);
    filesTitle = makeLabel(TextRole::Label, Color::Ink3, filesSection, false);
    filesColumn->addWidget(filesTitle);
    filesList = new QVBoxLayout();
    filesList->setSpacing(Space::s2);
    filesColumn->addLayout(filesList);
    right->addWidget(filesSection);
    right->addStretch();

    // Block and remove, at the foot.
    auto* const foot = new QHBoxLayout();
    foot->setSpacing(Space::s12);
    blockButton = new AirButton(AirButton::Kind::Plain, pageWidget);
    blockButton->setObjectName(QStringLiteral("dossierBlock"));
    foot->addWidget(blockButton, 0, Qt::AlignTop);
    foot->addStretch(1);
    removeButton = new AirButton(AirButton::Kind::Danger, pageWidget);
    removeButton->setObjectName(QStringLiteral("dossierRemove"));
    foot->addWidget(removeButton, 0, Qt::AlignTop);
    sections->addLayout(foot);
    sections->addStretch();

    connect(renameButton, &AirButton::clicked, this, [this] {
        close();
        emit renameRequested();
    });
    connect(muteSwitch, &AirSwitch::toggled, this, [this](bool muted) {
        info.muted = muted;
        mutedNote->setVisible(muted);
        emit muteChanged(muted);
    });
    connect(verifyButton, &AirButton::clicked, this, [this] {
        if (!info.verifiedDate.isEmpty()) {
            emit verifiedChanged(false);
            return;
        }
        verifyBox->setVisible(!verifyBox->isVisible());
        place();
    });
    connect(matchButton, &AirButton::clicked, this, [this] {
        verifyBox->hide();
        emit verifiedChanged(true);
    });
    connect(copyButton, &AirButton::clicked, this, [this] {
        QApplication::clipboard()->setText(info.publicKey);
        copyButton->setText(AirStrings::text(Text::DossierKeyCopied));
        QTimer::singleShot(2000, copyButton, [this] {
            copyButton->setText(AirStrings::text(Text::DossierCopyKey));
            copyButton->updateGeometry();
        });
        copyButton->updateGeometry();
    });
    connect(qrButton, &AirButton::clicked, this, &AirDossier::showQr);
    connect(removeButton, &AirButton::clicked, this, [this] {
        // Asked here, as Block is, in place of qTox's own window with its "Remove friend" title and
        // a box to keep the history: as on the phones, the conversation goes with the contact.
        AirDialog::confirm(this, AirStrings::text(Text::DossierRemoveTitle).arg(info.name),
                           AirStrings::text(Text::DossierRemoveMessage),
                           AirStrings::text(Text::DossierRemove), AirDialog::Verb::Danger, [this] {
                               close();
                               emit removeRequested();
                           });
    });
    connect(blockButton, &AirButton::clicked, this, [this] {
        // Asked once, with the consequence in one sentence; the button is the verb.
        AirDialog::confirm(this, AirStrings::text(Text::BlockTitle).arg(info.name),
                           AirStrings::text(Text::BlockMessage),
                           AirStrings::text(Text::DossierBlock), AirDialog::Verb::Danger, [this] {
                               close();
                               emit blockRequested();
                           });
    });

    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirDossier::restyle);
    Translator::registerHandler(std::bind(&AirDossier::retranslate, this), this);
    restyle();
    retranslate();
}

AirDossier::~AirDossier()
{
    Translator::unregister(this);
}

QVBoxLayout* AirDossier::section(QBoxLayout* column, QLabel** title)
{
    auto* const box = new QVBoxLayout();
    box->setSpacing(Space::s4);
    *title = makeLabel(TextRole::Label, Color::Ink3, page(), false);
    box->addWidget(*title);
    auto* const body = new QVBoxLayout();
    body->setSpacing(Space::s6);
    box->addLayout(body);
    column->addLayout(box);
    return body;
}

void AirDossier::retranslate()
{
    renameButton->setText(AirStrings::text(Text::DossierRename));
    linkTitle->setText(AirStrings::text(Text::DossierLinkTitle));
    dayTitle->setText(AirStrings::text(Text::DossierDayTitle));
    dayNote->setText(AirStrings::text(Text::DayNote));
    notifyTitle->setText(AirStrings::text(Text::DossierNotificationsTitle));
    noWake->setText(AirStrings::text(Text::DossierNoWake));
    muteLabel->setText(AirStrings::text(Text::DossierMute));
    muteSwitch->setAccessibleName(muteLabel->text());
    mutedNote->setText(AirStrings::text(Text::DossierMutedNote));
    verifyTitle->setText(AirStrings::text(Text::DossierVerifyTitle));
    verifyMessage->setText(AirStrings::text(Text::VerifyMessage));
    matchButton->setText(AirStrings::text(Text::VerifyMatch));
    keyTitle->setText(AirStrings::text(Text::DossierKeyTitle));
    copyButton->setText(AirStrings::text(Text::DossierCopyKey));
    qrButton->setText(AirStrings::text(Text::DossierQr));
    filesTitle->setText(AirStrings::text(Text::DossierFilesTitle));
    blockButton->setText(AirStrings::text(Text::DossierBlock));
    blockButton->setToolTip(AirStrings::text(Text::DossierBlockHint));
    blockButton->setAccessibleDescription(AirStrings::text(Text::DossierBlockHint));
    removeButton->setText(AirStrings::text(Text::DossierRemove));
    // Every text of the dossier stands at the start edge of the interface, whatever script it is
    // in: a QLabel would align a Russian name or the digits of the key by their own direction.
    const Qt::Alignment start =
        QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter);
    for (QLabel* const label : page()->findChildren<QLabel*>()) {
        label->setAlignment(start);
    }
    setInfo(info);
}

void AirDossier::restyle()
{
    const struct
    {
        // Every member is set by the braces that make it: an aggregate, which an initializer here
        // would stop it being in C++11.
        // cppcheck-suppress uninitMemberVarNoCtor
        QLabel* label;
        TextRole role;
        Color color;
    } texts[] = {
        {nameLabel, TextRole::Heading, Color::Ink},
        {statusLabel, TextRole::Caption, Color::Ink2},
        {linkTitle, TextRole::Label, Color::Ink3},
        {linkText, TextRole::Caption, Color::Ink2},
        {dayTitle, TextRole::Label, Color::Ink3},
        {bestText, TextRole::Caption, Color::Ink2},
        {dayNote, TextRole::Caption, Color::Ink3},
        {notifyTitle, TextRole::Label, Color::Ink3},
        {noWake, TextRole::Caption, Color::Ink2},
        {muteLabel, TextRole::Body, Color::Ink},
        {mutedNote, TextRole::Caption, Color::Ink2},
        {verifyTitle, TextRole::Label, Color::Ink3},
        {verifyText, TextRole::Caption, Color::Ink2},
        {verifyMessage, TextRole::Caption, Color::Ink2},
        {verifyKey, TextRole::Mono, Color::Ink},
        {keyTitle, TextRole::Label, Color::Ink3},
        {keyText, TextRole::Mono, Color::Ink},
        {filesTitle, TextRole::Label, Color::Ink3},
    };
    for (const auto& text : texts) {
        text.label->setFont(air().font(text.role));
        paintText(text.label, text.color);
    }
    refreshFiles();
    update();
}

void AirDossier::setInfo(const AirDossierInfo& info_)
{
    info = info_;
    tile->setPerson(info.name, info.tone);
    tile->setPhoto(info.photo);
    nameLabel->setText(info.name);
    statusLabel->setText(info.statusMessage);
    statusLabel->setVisible(!info.statusMessage.isEmpty());

    path->setPerson(info.name, Air::initials(info.name), info.link);
    switch (info.link) {
    case Air::LinkState::Direct:
        linkText->setText(AirStrings::text(Text::DossierLinkDirect));
        break;
    case Air::LinkState::Relay:
        linkText->setText(AirStrings::text(Text::DossierLinkRelay));
        break;
    case Air::LinkState::None:
        linkText->setText(AirStrings::text(Text::DossierLinkNone));
        break;
    }

    strip->setStrip(info.strip, info.liveNow);
    strip->setAccessibleName(AirStrings::dayStripSpokenText(info.strip, info.locale, info.liveNow));
    bestText->setText(info.hasBest
                          ? AirStrings::text(Text::DossierBest).arg(info.bestFrom).arg(info.bestTo)
                          : AirStrings::dayHintText(info.strip, false, 0, 0));

    const QSignalBlocker blocker(muteSwitch);
    muteSwitch->setChecked(info.muted);
    mutedNote->setVisible(info.muted);

    const bool verified = !info.verifiedDate.isEmpty();
    verifyText->setText(verified ? AirStrings::text(Text::DossierVerified).arg(info.verifiedDate)
                                 : AirStrings::text(Text::DossierNotVerified));
    verifyButton->setText(AirStrings::text(verified ? Text::DossierUnverify : Text::DossierVerify));
    if (verified) {
        verifyBox->hide();
    }
    verifyKey->setText(Air::keyLines(info.publicKey));
    keyText->setText(Air::keyLines(info.publicKey));

    refreshFiles();
    place();
}

void AirDossier::refreshFiles()
{
    if (!filesList) {
        return;
    }
    while (QLayoutItem* item = filesList->takeAt(0)) {
        if (QWidget* widget = item->widget()) {
            widget->deleteLater();
        }
        delete item;
    }
    for (const AirDossierFile& file : info.files) {
        auto* const button = new AirButton(AirButton::Kind::Quiet, filesSection);
        button->setOwnWords(true);
        const QString size = AirStrings::byteSizeText(file.size, info.locale);
        // "name (size)", as the file notifications have it.
        button->setText(QStringLiteral("%1 (%2)").arg(file.name, size));
        button->setEnabled(!file.path.isEmpty() && QFileInfo::exists(file.path));
        const QString filePath = file.path;
        connect(button, &AirButton::clicked, this,
                [this, filePath] { emit fileOpenRequested(filePath); });
        filesList->addWidget(button, 0, Qt::AlignLeading);
    }
    // Tab reaches the files where they stand, before Block and Remove.
    AirFocus::inPlace(filesSection);
    filesSection->setVisible(!info.files.isEmpty());
}

void AirDossier::arrange(int width)
{
    columns->setDirection(width < twoColumnWidth ? QBoxLayout::TopToBottom : QBoxLayout::LeftToRight);
}

void AirDossier::aboutToFold()
{
    verifyBox->hide();
}

void AirDossier::showQr()
{
    showAirQr(this, AirStrings::text(Text::QrKeyHeading).arg(info.name), info.publicKey,
              AirStrings::text(Text::QrKeySubtitle));
}
