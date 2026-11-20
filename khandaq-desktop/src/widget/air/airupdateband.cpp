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

#include "airupdateband.h"

#include "src/net/updatecheck.h"
#include "src/net/updateinstaller.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"

#include <QAccessible>
#include <QDebug>
#include <QDesktopServices>
#include <QFileInfo>
#include <QGraphicsOpacityEffect>
#include <QHBoxLayout>
#include <QLabel>
#include <QPainter>
#include <QUrl>
#include <QVariantAnimation>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
using Stage = UpdateInstaller::Stage;
using Failure = UpdateInstaller::Failure;
namespace Motion = AirTokens::Motion;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

Text failureText(Failure failure)
{
    switch (failure) {
    case Failure::None:
    case Failure::Download:
        break;
    case Failure::Integrity:
        return Text::UpdateFailedIntegrity;
    case Failure::Unpack:
        return Text::UpdateFailedUnpack;
    case Failure::Declined:
        return Text::UpdateFailedDeclined;
    case Failure::Start:
        return Text::UpdateFailedStart;
    }
    return Text::UpdateFailedDownload;
}
} // namespace

AirUpdateBand::AirUpdateBand(UpdateCheck& check_, UpdateInstaller& installer_, QWidget* parent)
    : QWidget(parent)
    , check{check_}
    , installer{installer_}
{
    setObjectName(QStringLiteral("airUpdateBand"));
    setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Fixed);

    content = new QWidget(this);
    glyph = new QLabel(content);
    glyph->setFixedSize(Size::icon, Size::icon);
    title = new QLabel(content);
    title->setTextFormat(Qt::PlainText);
    note = new QLabel(content);
    note->setTextFormat(Qt::PlainText);
    note->setWordWrap(true);
    notesButton = new AirButton(AirButton::Kind::Quiet, content);
    notesButton->setObjectName(QStringLiteral("updateNotes"));
    revealButton = new AirButton(AirButton::Kind::Quiet, content);
    revealButton->setObjectName(QStringLiteral("updateReveal"));
    installButton = new AirButton(AirButton::Kind::Ink, content);
    installButton->setObjectName(QStringLiteral("updateInstall"));
    hideButton = new AirIconButton(QStringLiteral("x"), content);
    hideButton->setObjectName(QStringLiteral("updateHide"));

    auto* const layout = new QHBoxLayout(content);
    layout->setContentsMargins(Space::s16, Space::s8, Space::s8, Space::s8);
    layout->setSpacing(Space::s8);
    layout->addWidget(glyph, 0, Qt::AlignVCenter);
    layout->addWidget(title, 0, Qt::AlignVCenter);
    layout->addWidget(note, 1, Qt::AlignVCenter);
    layout->addWidget(notesButton, 0, Qt::AlignVCenter);
    layout->addWidget(revealButton, 0, Qt::AlignVCenter);
    layout->addWidget(installButton, 0, Qt::AlignVCenter);
    layout->addWidget(hideButton, 0, Qt::AlignVCenter);

    motion = new QVariantAnimation(this);
    connect(motion, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        if (fading) {
            fade(value.toReal());
        } else {
            shown = value.toReal();
            place();
        }
    });
    connect(motion, &QVariantAnimation::finished, this, [this] {
        fade(1.0);
        if (!goingIn) {
            shown = 0;
            hide();
        }
    });

    connect(installButton, &AirButton::clicked, this, [this] {
        if (check.hasOffer()) {
            installer.install(check.offer());
        }
    });
    connect(notesButton, &AirButton::clicked, this, &AirUpdateBand::openNotes);
    connect(revealButton, &AirButton::clicked, this, &AirUpdateBand::revealFile);
    connect(hideButton, &AirIconButton::clicked, this, &AirUpdateBand::dismiss);
    connect(&check, &UpdateCheck::updateAvailable, this, &AirUpdateBand::follow);
    connect(&installer, &UpdateInstaller::changed, this, &AirUpdateBand::follow);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirUpdateBand::restyle);
    Translator::registerHandler(std::bind(&AirUpdateBand::retranslate, this), this);

    restyle();
    retranslate();
    hide();
}

AirUpdateBand::~AirUpdateBand()
{
    Translator::unregister(this);
}

void AirUpdateBand::reopen()
{
    dismissed = false;
    follow();
}

void AirUpdateBand::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.fillRect(rect(), air().color(Color::Sunken));
    painter.fillRect(QRect(0, height() - 1, width(), 1), air().color(Color::Line));
}

void AirUpdateBand::resizeEvent(QResizeEvent* event)
{
    QWidget::resizeEvent(event);
    place();
}

void AirUpdateBand::follow()
{
    // The file left to install by hand is shown in the file manager once, as it is ready.
    if (installer.stage() == Stage::Manual && installer.file() != revealed) {
        revealFile();
    }
    const bool wanted = !dismissed && check.hasOffer();
    restyle();
    retranslate();
    if (wanted && (isHidden() || !goingIn)) {
        qDebug() << "Update band: shown";
        slide(true);
        // Said once, as it comes; the rest is in its name for whoever looks.
        QAccessibleEvent alert(this, QAccessible::Alert);
        QAccessible::updateAccessibility(&alert);
    } else if (!wanted && !isHidden() && goingIn) {
        slide(false);
    } else {
        place();
    }
}

void AirUpdateBand::dismiss()
{
    // A download that is running stops: the band that shows it goes.
    installer.cancel();
    dismissed = true;
    qDebug() << "Update band: hidden until the next start";
    follow();
}

void AirUpdateBand::revealFile()
{
    revealed = installer.file();
    if (!revealed.isEmpty()) {
        QDesktopServices::openUrl(QUrl::fromLocalFile(QFileInfo(revealed).absolutePath()));
    }
}

void AirUpdateBand::openNotes()
{
    const QUrl notesUrl =
        UpdateManifest::notesFor(check.release(), Translator::locale().name());
    if (notesUrl.isValid()) {
        QDesktopServices::openUrl(notesUrl);
    }
}

void AirUpdateBand::slide(bool in)
{
    if (in == goingIn && (motion->state() == QAbstractAnimation::Running || isHidden() != in)) {
        return;
    }
    motion->stop();
    goingIn = in;
    // Reduced motion: no slide, the band is there or gone at once and its words fade.
    fading = air().reducedMotion();
    motion->setDuration(air().duration(in ? Motion::layout : Motion::state));
    motion->setEasingCurve(air().easeOut());
    if (in) {
        QWidget::show();
    }
    if (fading) {
        shown = 1;
        place();
        motion->setStartValue(in ? 0.0 : 1.0);
        motion->setEndValue(in ? 1.0 : 0.0);
    } else {
        motion->setStartValue(shown);
        motion->setEndValue(in ? 1.0 : 0.0);
    }
    motion->start();
}

void AirUpdateBand::place()
{
    const int full = contentHeight();
    const int tall = qRound(full * shown);
    content->setGeometry(0, tall - full, width(), full);
    setFixedHeight(tall);
}

int AirUpdateBand::contentHeight() const
{
    const QWidget* const holder = parentWidget();
    const int across = width() > 0 || !holder ? width() : holder->width();
    const int forWidth = content->hasHeightForWidth() ? content->heightForWidth(across) : -1;
    return forWidth > 0 ? forWidth : content->sizeHint().height();
}

void AirUpdateBand::fade(qreal opacity)
{
    if (opacity >= 1.0) {
        content->setGraphicsEffect(nullptr);
        return;
    }
    auto* effect = qobject_cast<QGraphicsOpacityEffect*>(content->graphicsEffect());
    if (!effect) {
        effect = new QGraphicsOpacityEffect(content);
        content->setGraphicsEffect(effect);
    }
    effect->setOpacity(opacity);
}

void AirUpdateBand::restyle()
{
    const bool failed = installer.stage() == Stage::Failed;
    glyph->setPixmap(air().icon(failed ? QStringLiteral("alert") : QStringLiteral("arrow-up"),
                                air().color(failed ? Color::Danger : Color::Ink2), Size::icon,
                                devicePixelRatioF()));
    title->setFont(air().font(TextRole::Label));
    AirPaint::textColor(title, air().color(Color::Ink));
    note->setFont(air().font(TextRole::Caption));
    AirPaint::textColor(note, air().color(failed ? Color::Danger : Color::Ink2));
    update();
}

void AirUpdateBand::retranslate()
{
    const Stage stage = installer.stage();
    const QString version =
        check.hasOffer() ? UpdateManifest::toString(check.offer().version) : QString();
    const bool manual = stage == Stage::Manual;
    title->setText(AirStrings::text(manual ? Text::UpdateDownloaded : Text::UpdateAvailable)
                       .arg(version));

    QString words;
    switch (stage) {
    case Stage::Idle:
        break;
    case Stage::Downloading:
        words = AirStrings::text(Text::UpdateDownloading).arg(installer.percent());
        break;
    case Stage::Preparing:
        words = AirStrings::text(Text::UpdatePreparing);
        break;
    case Stage::Restarting:
        words = AirStrings::text(Text::UpdateRestarting);
        break;
    case Stage::Manual:
        words = installer.command().isEmpty()
                    ? AirStrings::text(Text::UpdateManualFile)
                    : AirStrings::text(Text::UpdateManualDeb).arg(installer.command());
        break;
    case Stage::Failed:
        words = AirStrings::text(failureText(installer.failure()));
        break;
    }
    note->setText(words);
    note->setVisible(!words.isEmpty());
    // The command is there to be copied.
    note->setTextInteractionFlags(manual ? Qt::TextSelectableByMouse : Qt::NoTextInteraction);

    notesButton->setText(AirStrings::text(Text::UpdateNotes));
    revealButton->setText(AirStrings::text(Text::FileShowInFolder));
    installButton->setText(AirStrings::text(Text::UpdateInstall));
    hideButton->setToolTip(AirStrings::text(Text::UpdateHide));
    hideButton->setAccessibleName(AirStrings::text(Text::UpdateHide));
    const bool busy =
        stage == Stage::Downloading || stage == Stage::Preparing || stage == Stage::Restarting;
    notesButton->setVisible(UpdateManifest::notesFor(check.release(), Translator::locale().name()).isValid());
    revealButton->setVisible(manual);
    installButton->setVisible(!busy && !manual);
    hideButton->setVisible(stage != Stage::Preparing && stage != Stage::Restarting);

    setAccessibleName(title->text());
    setAccessibleDescription(note->text());
    place();
}
