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

#include "src/core/core.h"
#include "src/core/toxid.h"
#include "src/model/air/airconversation.h"
#include "src/model/air/airmodel.h"
#include "src/model/profile/iprofileinfo.h"
#include "src/widget/air/airnewcontact.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/contentlayout.h"
#include "src/widget/form/setpassworddialog.h"
#include "src/widget/translator.h"

#include <QBoxLayout>
#include <QDesktopServices>
#include <QDir>
#include <QFileDialog>
#include <QFileInfo>
#include <QFocusEvent>
#include <QImageReader>
#include <QInputDialog>
#include <QKeyEvent>
#include <QLabel>
#include <QLineEdit>
#include <QMenu>
#include <QPainter>
#include <QPainterPath>
#include <QPointer>
#include <QScrollArea>
#include <QStyle>
#include <QTextCursor>
#include <QTextEdit>
#include <QUrl>
#include <QWindow>

#include <algorithm>
#include <initializer_list>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Radius = AirTokens::Radius;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

/** An ID with "tox:" takes version 5 at level M: 37 modules a side, and the quiet zone round them. */
constexpr int qrCardModules = 37 + 2 * Air::qrQuietModules;
/** Pixels a module takes on screen, so the card is 270 a side. */
constexpr int qrModuleSide = Space::s6;
/** The card on its ground, which the column of Copy ID takes the width of. */
constexpr int qrColumnWidth = qrCardModules * qrModuleSide + 2 * Space::s16;
/** Pixels a module takes in the picture Copy QR code and Save QR code give, 540 a side. */
constexpr int qrPictureModule = 12;
/** The photo stands twice the size of the tile in the "you" block. */
constexpr int photoSide = Space::s64;

const char roleProperty[] = "airRole";
const char colorProperty[] = "airColor";

/** A label in a role of the type scale; restyle() colours it again from what it keeps. */
QLabel* makeLabel(TextRole role, Color color, QWidget* parent, bool wrap = true)
{
    auto* const label = new QLabel(parent);
    label->setTextFormat(Qt::PlainText);
    label->setWordWrap(wrap);
    label->setProperty(roleProperty, static_cast<int>(role));
    label->setProperty(colorProperty, static_cast<int>(color));
    label->setFont(air().font(role));
    AirPaint::textColor(label, air().color(color));
    return label;
}

/** kq-plate: the sunken ground of a card, without a frame. */
class Plate : public QWidget
{
public:
    explicit Plate(QWidget* parent)
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

/** A row of buttons stands one under another when the buttons do not fit in width side by side. */
void fitRow(QBoxLayout* row, int width)
{
    int needed = 0;
    int shown = 0;
    for (int i = 0; i < row->count(); ++i) {
        QWidget* const widget = row->itemAt(i)->widget();
        if (widget && !widget->isHidden()) {
            needed += widget->sizeHint().width();
            ++shown;
        }
    }
    needed += std::max(0, shown - 1) * row->spacing();
    row->setDirection(needed <= width ? QBoxLayout::LeftToRight : QBoxLayout::TopToBottom);
}

/** A row of buttons at the start edge, Copy ID and Copy QR code say. */
QBoxLayout* buttonRow(std::initializer_list<AirButton*> buttons)
{
    auto* const row = new QBoxLayout(QBoxLayout::LeftToRight);
    row->setSpacing(Space::s8);
    for (AirButton* const button : buttons) {
        row->addWidget(button, 0, Qt::AlignLeading);
    }
    row->addStretch(1);
    return row;
}

/**
 * Where to save a file: the dialog of the system, over window, with suffix added to a name typed
 * without it, so the file opens with what made it.
 */
QString askSavePath(QWidget* window, const QString& title, const QString& path,
                    const QString& filter, const QString& suffix)
{
    QFileDialog dialog(window, title, path, filter);
    dialog.setAcceptMode(QFileDialog::AcceptSave);
    dialog.setDefaultSuffix(suffix);
    if (dialog.exec() != QDialog::Accepted) {
        return QString();
    }
    return dialog.selectedFiles().value(0);
}
} // namespace

// ---- AirQrCard ----

AirQrCard::AirQrCard(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    // A QR code is never mirrored: it reads the same in every language.
    setLayoutDirection(Qt::LeftToRight);
}

void AirQrCard::setCode(const Air::QrModules& modules_)
{
    modules = modules_;
    update();
}

const Air::QrModules& AirQrCard::code() const
{
    return modules;
}

QSize AirQrCard::sizeHint() const
{
    const int side = qrCardModules * qrModuleSide;
    return QSize(side, side);
}

void AirQrCard::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setPen(Qt::NoPen);
    painter.setBrush(air().color(Color::FixedWhite));
    painter.drawRoundedRect(rect(), Radius::card, Radius::card);
    if (modules.side <= 0) {
        return;
    }
    // Whole pixels a module, so no module blurs into the next; the quiet zone takes the rest.
    const int span = modules.side + 2 * Air::qrQuietModules;
    const int module = std::max(1, std::min(width(), height()) / span);
    const int left = (width() - module * modules.side) / 2;
    const int top = (height() - module * modules.side) / 2;
    painter.setRenderHint(QPainter::Antialiasing, false);
    painter.setBrush(air().color(Color::FixedBlack));
    for (int y = 0; y < modules.side; ++y) {
        for (int x = 0; x < modules.side; ++x) {
            if (modules.isDark(x, y)) {
                painter.drawRect(left + x * module, top + y * module, module, module);
            }
        }
    }
}

// ---- AirYouPhoto ----

AirYouPhoto::AirYouPhoto(QWidget* parent)
    : QAbstractButton(parent)
{
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::StrongFocus);
    AirPaint::followTheme(this);
}

void AirYouPhoto::setPerson(const QString& name_, const QPixmap& photo_)
{
    name = name_;
    photo = photo_;
    update();
}

bool AirYouPhoto::hasPhoto() const
{
    return !photo.isNull();
}

QSize AirYouPhoto::sizeHint() const
{
    return QSize(photoSide, photoSide);
}

void AirYouPhoto::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setRenderHint(QPainter::SmoothPixmapTransform);
    const QRectF tile(rect());
    QPainterPath shape;
    shape.addRoundedRect(tile, Radius::card, Radius::card);

    if (!photo.isNull()) {
        // The photo fills the tile from its middle, as in the "you" block.
        const qreal dpr = devicePixelRatioF();
        QPixmap scaled = photo.scaled(QSize(qRound(width() * dpr), qRound(height() * dpr)),
                                      Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
        scaled.setDevicePixelRatio(dpr);
        const QSizeF shown = QSizeF(scaled.size()) / dpr;
        painter.save();
        painter.setClipPath(shape);
        painter.drawPixmap(QPointF((width() - shown.width()) / 2.0, (height() - shown.height()) / 2.0),
                           scaled);
        if (hovered || isDown()) {
            painter.fillPath(shape, air().color(Color::Scrim));
        }
        painter.restore();
        AirPaint::box(painter, tile, Radius::card, QColor(), air().color(Color::Line));
    } else {
        // Your own tile is ink, as in the "you" block, and answers the pointer as an ink button.
        const Color ground = isDown() ? Color::InkPressed : hovered ? Color::InkHover : Color::Ink;
        painter.fillPath(shape, air().color(ground));
        QFont font = air().font(TextRole::Caption);
        font.setWeight(QFont::DemiBold);
        // Air fonts are sized in points, so the scale of the tile goes on the point size.
        font.setPointSizeF(font.pointSizeF() * photoSide / static_cast<qreal>(Size::tileDesk));
        painter.setFont(font);
        painter.setPen(air().color(Color::OnInk));
        painter.drawText(rect(), Qt::AlignCenter, Air::initials(name));
    }
    if (AirPaint::keyboardFocus(this)) {
        AirPaint::focusRing(painter, tile, Radius::card);
    }
}

void AirYouPhoto::enterEvent(QEvent* event)
{
    hovered = true;
    update();
    QAbstractButton::enterEvent(event);
}

void AirYouPhoto::leaveEvent(QEvent* event)
{
    hovered = false;
    update();
    QAbstractButton::leaveEvent(event);
}

// ---- AirYouPage ----

AirYouPage::AirYouPage(IProfileInfo& profileInfo_, QWidget* parent)
    : QWidget(parent)
    , profileInfo{profileInfo_}
{
    setObjectName(QStringLiteral("youPage"));
    auto* const outer = new QVBoxLayout(this);
    outer->setContentsMargins(0, 0, 0, 0);
    outer->setSpacing(0);
    scroller = new QScrollArea(this);
    scroller->setFrameShape(QFrame::NoFrame);
    scroller->setWidgetResizable(true);
    scroller->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    // Tab stops on the controls of the page, which the scroller brings into view, not on itself.
    scroller->setFocusPolicy(Qt::NoFocus);
    outer->addWidget(scroller);
    content = new QWidget(scroller);
    page = new QVBoxLayout(content);
    page->setSpacing(Space::s32);
    scroller->setWidget(content);

    heading = makeLabel(TextRole::Display, Color::Ink, content, false);
    page->addWidget(heading);

    // First the QR code of your ID, beside your photo, name and status; above them when narrow.
    columns = new QBoxLayout(QBoxLayout::LeftToRight);
    columns->setSpacing(Space::s32);
    page->addLayout(columns);

    qrColumn = new QWidget(content);
    qrColumn->setFixedWidth(qrColumnWidth);
    auto* const qrBox = new QVBoxLayout(qrColumn);
    qrBox->setContentsMargins(0, 0, 0, 0);
    qrBox->setSpacing(Space::s12);
    // The white card stands on its ground, which sets it off in both themes.
    auto* const qrPlate = new Plate(qrColumn);
    auto* const plateBox = new QVBoxLayout(qrPlate);
    plateBox->setContentsMargins(Space::s16, Space::s16, Space::s16, Space::s16);
    qrCard = new AirQrCard(qrPlate);
    qrCard->setObjectName(QStringLiteral("youQr"));
    plateBox->addWidget(qrCard, 0, Qt::AlignCenter);
    qrBox->addWidget(qrPlate);
    qrHint = makeLabel(TextRole::Caption, Color::Ink3, qrColumn);
    qrBox->addWidget(qrHint);
    copyIdButton = new AirButton(AirButton::Kind::Ink, qrColumn);
    copyIdButton->setObjectName(QStringLiteral("youCopyId"));
    copyQrButton = new AirButton(AirButton::Kind::Plain, qrColumn);
    copyQrButton->setObjectName(QStringLiteral("youCopyQr"));
    qrActions = buttonRow({copyIdButton, copyQrButton});
    qrBox->addLayout(qrActions);
    saveQrButton = new AirButton(AirButton::Kind::Quiet, qrColumn);
    saveQrButton->setObjectName(QStringLiteral("youSaveQr"));
    qrBox->addWidget(saveQrButton, 0, Qt::AlignLeading);
    columns->addWidget(qrColumn, 0, Qt::AlignLeading | Qt::AlignTop);

    auto* const fields = new QVBoxLayout();
    fields->setSpacing(Space::s16);
    columns->addLayout(fields, 1);
    // The photo at the start of the name, both standing on the foot of the field.
    auto* const nameRow = new QHBoxLayout();
    nameRow->setSpacing(Space::s12);
    photoButton = new AirYouPhoto(content);
    photoButton->setObjectName(QStringLiteral("youPhoto"));
    nameRow->addWidget(photoButton, 0, Qt::AlignBottom);
    auto* const nameBox = new QVBoxLayout();
    nameBox->setSpacing(Space::s6);
    nameTitle = makeLabel(TextRole::Label, Color::Ink2, content, false);
    nameBox->addWidget(nameTitle);
    nameField = new AirTextField(AirTextField::Kind::Line, content);
    nameField->setObjectName(QStringLiteral("youName"));
    nameBox->addWidget(nameField);
    nameRow->addLayout(nameBox, 1);
    fields->addLayout(nameRow);
    auto* const statusBox = new QVBoxLayout();
    statusBox->setSpacing(Space::s6);
    statusTitle = makeLabel(TextRole::Label, Color::Ink2, content, false);
    statusBox->addWidget(statusTitle);
    statusField = new AirTextField(AirTextField::Kind::Line, content);
    statusField->setObjectName(QStringLiteral("youStatus"));
    statusBox->addWidget(statusField);
    fields->addLayout(statusBox);

    // Details: the ID in its three named parts, left to right in every language.
    QVBoxLayout* const details = section(fields, &detailsTitle);
    auto* const idPlate = new Plate(content);
    idPlate->setObjectName(QStringLiteral("youId"));
    idPlate->setLayoutDirection(Qt::LeftToRight);
    auto* const parts = new QHBoxLayout(idPlate);
    parts->setContentsMargins(Space::s16, Space::s16, Space::s16, Space::s16);
    parts->setSpacing(Space::s24);
    const char* const partNames[3] = {"youKey", "youAntispamPart", "youCheck"};
    for (int i = 0; i < 3; ++i) {
        auto* const part = new QVBoxLayout();
        part->setSpacing(Space::s4);
        partTitles[i] = makeLabel(TextRole::Caption, Color::Ink3, idPlate, false);
        partValues[i] = makeLabel(TextRole::Mono, Color::Ink, idPlate, false);
        partValues[i]->setObjectName(QLatin1String(partNames[i]));
        partValues[i]->setTextInteractionFlags(Qt::TextSelectableByMouse);
        part->addWidget(partTitles[i]);
        part->addWidget(partValues[i]);
        part->addStretch(1);
        parts->addLayout(part);
    }
    parts->addStretch(1);
    details->addWidget(idPlate);
    antispamButton = new AirButton(AirButton::Kind::Plain, content);
    antispamButton->setObjectName(QStringLiteral("youAntispam"));
    details->addWidget(antispamButton, 0, Qt::AlignLeading);
    fields->addStretch(1);

    // The password the profile and its history are encrypted with.
    QVBoxLayout* const password = section(page, &passwordTitle);
    passwordText = makeLabel(TextRole::Body, Color::Ink2, content);
    password->addWidget(passwordText);
    passwordButton = new AirButton(AirButton::Kind::Plain, content);
    passwordButton->setObjectName(QStringLiteral("youPassword"));
    passwordRemoveButton = new AirButton(AirButton::Kind::Plain, content);
    passwordRemoveButton->setObjectName(QStringLiteral("youPasswordRemove"));
    passwordActions = buttonRow({passwordButton, passwordRemoveButton});
    password->addLayout(passwordActions);

    // The file the profile is kept in on this computer.
    QVBoxLayout* const file = section(page, &fileTitle);
    fileName = makeLabel(TextRole::Body, Color::Ink, content);
    fileName->setObjectName(QStringLiteral("youFileName"));
    file->addWidget(fileName);
    fileNote = makeLabel(TextRole::Caption, Color::Ink3, content);
    file->addWidget(fileNote);
    renameButton = new AirButton(AirButton::Kind::Plain, content);
    renameButton->setObjectName(QStringLiteral("youRename"));
    exportButton = new AirButton(AirButton::Kind::Plain, content);
    exportButton->setObjectName(QStringLiteral("youExport"));
    folderButton = new AirButton(AirButton::Kind::Plain, content);
    folderButton->setObjectName(QStringLiteral("youShowFolder"));
    fileActions = buttonRow({renameButton, exportButton, folderButton});
    file->addLayout(fileActions);

    // Log out, with what it means said before it is pressed.
    auto* const logout = new QVBoxLayout();
    logout->setSpacing(Space::s8);
    logoutButton = new AirButton(AirButton::Kind::Plain, content);
    logoutButton->setObjectName(QStringLiteral("youLogout"));
    logout->addWidget(logoutButton, 0, Qt::AlignLeading);
    logoutNote = makeLabel(TextRole::Caption, Color::Ink2, content);
    logout->addWidget(logoutNote);
    page->addLayout(logout);

    // Deleting the profile, apart at the very bottom.
    page->addSpacing(Space::s48 - Space::s32);
    deleteButton = new AirButton(AirButton::Kind::Danger, content);
    deleteButton->setObjectName(QStringLiteral("youDelete"));
    page->addWidget(deleteButton, 0, Qt::AlignLeading);
    page->addStretch(1);

    connect(copyIdButton, &AirButton::clicked, this, [this] {
        profileInfo.copyId();
        say(AirStrings::text(Text::YouIdCopied));
    });
    connect(copyQrButton, &AirButton::clicked, this, [this] {
        profileInfo.copyQr(qrPicture());
        say(AirStrings::text(Text::YouQrCopied));
    });
    connect(saveQrButton, &AirButton::clicked, this, &AirYouPage::saveQr);
    connect(photoButton, &AirYouPhoto::clicked, this, &AirYouPage::showPhotoMenu);
    connect(nameField, &AirTextField::changed, this, [this] { keepWithin(Field::Name); });
    connect(nameField, &AirTextField::submitted, this, [this] { commit(Field::Name); });
    connect(statusField, &AirTextField::changed, this, [this] { keepWithin(Field::Status); });
    connect(statusField, &AirTextField::submitted, this, [this] { commit(Field::Status); });
    nameField->edit()->installEventFilter(this);
    statusField->edit()->installEventFilter(this);
    connect(antispamButton, &AirButton::clicked, this, &AirYouPage::askAntispam);
    connect(passwordButton, &AirButton::clicked, this, &AirYouPage::askPassword);
    connect(passwordRemoveButton, &AirButton::clicked, this, &AirYouPage::askRemovePassword);
    connect(renameButton, &AirButton::clicked, this, &AirYouPage::rename);
    connect(exportButton, &AirButton::clicked, this, &AirYouPage::exportProfile);
    connect(folderButton, &AirButton::clicked, this, [this] {
        QDesktopServices::openUrl(QUrl::fromLocalFile(profileInfo.getProfileFolder()));
    });
    connect(logoutButton, &AirButton::clicked, this, &AirYouPage::askLogout);
    connect(deleteButton, &AirButton::clicked, this, &AirYouPage::askDelete);

    profileInfo.connectTo_idChanged(this, [this](const ToxId&) { refreshId(); });
    profileInfo.connectTo_usernameChanged(this, [this](const QString&) { refreshFields(); });
    profileInfo.connectTo_statusMessageChanged(this, [this](const QString&) { refreshFields(); });
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirYouPage::restyle);
    Translator::registerHandler(std::bind(&AirYouPage::retranslate, this), this);
    restyle();
    refresh();
    retranslate();
    alignLabels();
    // The column keeps to the measure of text, in the middle of a wide window.
    scroller->viewport()->installEventFilter(this);
}

AirYouPage::~AirYouPage()
{
    Translator::unregister(this);
}

QVBoxLayout* AirYouPage::section(QVBoxLayout* column, QLabel** title)
{
    auto* const box = new QVBoxLayout();
    box->setSpacing(Space::s8);
    *title = makeLabel(TextRole::Label, Color::Ink3, content, false);
    box->addWidget(*title);
    auto* const body = new QVBoxLayout();
    body->setSpacing(Space::s8);
    box->addLayout(body);
    column->addLayout(box);
    return body;
}

void AirYouPage::showIn(ContentLayout* contentLayout)
{
    contentLayout->setFlush(true);
    contentLayout->mainContent->layout()->addWidget(this);
    show();
    refresh();
}

bool AirYouPage::isShown() const
{
    if (!isVisible()) {
        return false;
    }
    if (QWindow* const handle = window()->windowHandle()) {
        handle->alert(0);
    }
    return true;
}

void AirYouPage::refresh()
{
    refreshId();
    refreshFields();
    refreshProfile();
}

void AirYouPage::setPhoto(const QPixmap& photo)
{
    photoPixmap = photo;
    photoButton->setPerson(profileInfo.getUsername(), photoPixmap);
}

void AirYouPage::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.fillRect(rect(), air().color(Color::Surface));
}

void AirYouPage::changeEvent(QEvent* event)
{
    // The labels have taken the new direction already: Qt turns the children first.
    if (event->type() == QEvent::LayoutDirectionChange) {
        alignLabels();
    }
    QWidget::changeEvent(event);
}

bool AirYouPage::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == scroller->viewport() && event->type() == QEvent::Resize) {
        arrange(scroller->viewport()->width());
    }
    for (const Field which : {Field::Name, Field::Status}) {
        if (watched != fieldOf(which)->edit()) {
            continue;
        }
        if (event->type() == QEvent::FocusOut) {
            // Editing ends when the focus goes elsewhere, not while the field's own menu is open.
            if (static_cast<QFocusEvent*>(event)->reason() != Qt::PopupFocusReason) {
                commit(which);
            }
        } else if (event->type() == QEvent::KeyPress
                   && static_cast<QKeyEvent*>(event)->key() == Qt::Key_Escape
                   && fieldOf(which)->text() != savedValue(which)) {
            // Esc takes the edit back; with nothing to take back it goes on to the window.
            revert(which);
            return true;
        }
    }
    return QWidget::eventFilter(watched, event);
}

void AirYouPage::arrange(int width)
{
    const int side = std::max(Space::s32, (width - Size::textMeasure) / 2);
    page->setContentsMargins(side, Space::s32, side, Space::s48);
    const int room = std::max(0, width - 2 * side);
    // The fields want at least the width of the list beside the QR column.
    const bool sideBySide = room >= qrColumnWidth + columns->spacing() + Size::listColumn;
    columns->setDirection(sideBySide ? QBoxLayout::LeftToRight : QBoxLayout::TopToBottom);
    fitRow(qrActions, qrColumnWidth);
    fitRow(passwordActions, room);
    fitRow(fileActions, room);
}

void AirYouPage::alignLabels()
{
    // A label lines its text up by the script of the text: a profile file named in Latin letters
    // stood at the far edge of an Arabic page. Words stand at the start edge of the interface, as
    // in New contact; inside the plate of the ID, which reads left to right, that is the left.
    for (QLabel* const label : content->findChildren<QLabel*>()) {
        if (label->property(roleProperty).isValid()) {
            label->setAlignment(QStyle::visualAlignment(label->layoutDirection(),
                                                        Qt::AlignLeft | Qt::AlignVCenter));
        }
    }
}

void AirYouPage::retranslate()
{
    heading->setText(AirStrings::text(Text::You));
    qrCard->setAccessibleName(AirStrings::text(Text::YouQr));
    qrCard->setAccessibleDescription(AirStrings::text(Text::YouQrHint));
    qrHint->setText(AirStrings::text(Text::YouQrHint));
    copyIdButton->setText(AirStrings::text(Text::YouCopyId));
    copyQrButton->setText(AirStrings::text(Text::YouCopyQr));
    saveQrButton->setText(AirStrings::text(Text::YouSaveQr));
    photoButton->setAccessibleName(AirStrings::text(Text::YouPhoto));
    photoButton->setAccessibleDescription(AirStrings::text(Text::YouPhotoHint));
    photoButton->setToolTip(AirStrings::text(Text::YouPhotoHint));
    nameTitle->setText(AirStrings::text(Text::YouName));
    nameField->setPlaceholder(AirStrings::text(Text::YouNamePlaceholder));
    nameField->edit()->setAccessibleName(AirStrings::text(Text::YouName));
    statusTitle->setText(AirStrings::text(Text::YouStatus));
    statusField->setPlaceholder(AirStrings::text(Text::YouStatusPlaceholder));
    statusField->edit()->setAccessibleName(AirStrings::text(Text::YouStatus));
    detailsTitle->setText(AirStrings::text(Text::YouDetails));
    const Text partTexts[3] = {Text::YouPartKey, Text::YouPartAntispam, Text::YouPartCheck};
    for (int i = 0; i < 3; ++i) {
        partTitles[i]->setText(AirStrings::text(partTexts[i]));
        partValues[i]->setAccessibleName(AirStrings::text(partTexts[i]));
    }
    antispamButton->setText(AirStrings::text(Text::YouAntispam));
    passwordTitle->setText(AirStrings::text(Text::YouPassword));
    passwordRemoveButton->setText(AirStrings::text(Text::YouPasswordRemove));
    fileTitle->setText(AirStrings::text(Text::YouFile));
    fileNote->setText(AirStrings::text(Text::YouExportNote));
    renameButton->setText(AirStrings::text(Text::YouRename));
    exportButton->setText(AirStrings::text(Text::YouExport));
    folderButton->setText(AirStrings::text(Text::FileShowInFolder));
    logoutButton->setText(AirStrings::text(Text::YouLogout));
    deleteButton->setText(AirStrings::text(Text::YouDelete));
    refreshProfile();
}

void AirYouPage::restyle()
{
    // The legacy sheet of the window paints scroll areas white; the page paints its own ground.
    scroller->setStyleSheet(QStringLiteral("QScrollArea { background: transparent; }"));
    scroller->viewport()->setAutoFillBackground(false);
    content->setAutoFillBackground(false);
    for (QLabel* const label : content->findChildren<QLabel*>()) {
        const QVariant role = label->property(roleProperty);
        if (role.isValid()) {
            label->setFont(air().font(static_cast<TextRole>(role.toInt())));
            AirPaint::textColor(label, air().color(static_cast<Color>(
                                           label->property(colorProperty).toInt())));
        }
    }
    arrange(scroller->viewport()->width());
    update();
}

void AirYouPage::refreshId()
{
    const QString id = profileInfo.getSelfId().toString();
    // The ID as a Tox link, as the profile form put it in the code before: the phones read both.
    qrCard->setCode(Air::qrModules(QStringLiteral("tox:") + id));
    const Air::IdParts parts = Air::idParts(id);
    const QString values[3] = {parts.key, parts.antispam, parts.check};
    for (int i = 0; i < 3; ++i) {
        partValues[i]->setText(Air::keyLines(values[i]));
        partValues[i]->setAccessibleDescription(Air::keyGroups(values[i]).join(QLatin1Char(' ')));
    }
}

void AirYouPage::refreshFields()
{
    // A field that is being edited keeps what is typed in it until the editing ends.
    for (const Field which : {Field::Name, Field::Status}) {
        if (!fieldOf(which)->edit()->hasFocus()) {
            revert(which);
        }
    }
    photoButton->setPerson(profileInfo.getUsername(), photoPixmap);
}

void AirYouPage::refreshProfile()
{
    const bool encrypted = profileInfo.isEncrypted();
    passwordText->setText(AirStrings::text(encrypted ? Text::YouPasswordOn : Text::YouPasswordOff));
    passwordButton->setText(
        AirStrings::text(encrypted ? Text::YouPasswordChange : Text::YouPasswordSet));
    passwordRemoveButton->setVisible(encrypted);
    logoutNote->setText(logoutMessage());
    fileName->setText(profileInfo.getProfileName() + Core::TOX_EXT);
    folderButton->setToolTip(QDir::toNativeSeparators(profileInfo.getProfileFolder()));
    arrange(scroller->viewport()->width());
}

void AirYouPage::keepWithin(Field which)
{
    AirTextField* const field = fieldOf(which);
    QString& typed = which == Field::Name ? typedName : typedStatus;
    const int limit = which == Field::Name ? Air::nameLimitBytes : Air::statusLimitBytes;
    const QString now = field->text();
    const Air::FittedEdit fitted = Air::fitEdit(typed, now, limit);
    if (fitted.text != now) {
        field->setText(fitted.text);
        QTextCursor cursor = field->edit()->textCursor();
        cursor.setPosition(qBound(0, fitted.caret, fitted.text.size()));
        field->edit()->setTextCursor(cursor);
    }
    typed = fitted.text;
}

void AirYouPage::commit(Field which)
{
    const QString saved = savedValue(which);
    const QString typed = fieldOf(which)->text().trimmed();
    // Nothing new to save; an emptied name takes the name back (DESIGN.md, "You on the phone").
    if (typed == saved || (which == Field::Name && typed.isEmpty())) {
        revert(which);
        return;
    }
    if (which == Field::Name) {
        profileInfo.setUsername(typed);
    } else {
        profileInfo.setStatusMessage(typed);
    }
    // The field shows what Tox took, which is the old value when it took nothing.
    const bool taken = savedValue(which) == typed;
    revert(which);
    if (taken) {
        say(AirStrings::text(Text::YouSaved));
    }
}

void AirYouPage::revert(Field which)
{
    AirTextField* const field = fieldOf(which);
    const QString value = savedValue(which);
    if (field->text() != value) {
        field->setText(value);
    }
    (which == Field::Name ? typedName : typedStatus) = value;
}

AirTextField* AirYouPage::fieldOf(Field which) const
{
    return which == Field::Name ? nameField : statusField;
}

QString AirYouPage::savedValue(Field which) const
{
    return which == Field::Name ? profileInfo.getUsername() : profileInfo.getStatusMessage();
}

QImage AirYouPage::qrPicture() const
{
    return Air::qrImage(qrCard->code(), qrPictureModule);
}

void AirYouPage::say(const QString& text)
{
    if (isVisible()) {
        AirToast::post(this, text);
    }
}

void AirYouPage::showPhotoMenu()
{
    QMenu menu(this);
    QAction* const choose = menu.addAction(AirStrings::text(Text::YouPhotoChoose));
    QAction* const remove =
        photoButton->hasPhoto() ? menu.addAction(AirStrings::text(Text::YouPhotoRemove)) : nullptr;
    // Under the photo, from its start edge. The menu runs its own loop.
    const QRect tile = photoButton->rect();
    const QPoint start = layoutDirection() == Qt::RightToLeft ? tile.bottomRight() : tile.bottomLeft();
    const QPointer<AirYouPage> self(this);
    QAction* const chosen = menu.exec(photoButton->mapToGlobal(start + QPoint(0, Space::s4)));
    if (!self || !chosen) {
        return;
    }
    if (chosen == choose) {
        choosePhoto();
    } else if (remove && chosen == remove) {
        profileInfo.removeAvatar();
    }
}

void AirYouPage::choosePhoto()
{
    QStringList patterns;
    for (const QByteArray& format : QImageReader::supportedImageFormats()) {
        patterns.append(QStringLiteral("*.") + QString::fromLatin1(format));
    }
    const QPointer<AirYouPage> self(this);
    const QString path = QFileDialog::getOpenFileName(
        window(), AirStrings::text(Text::YouPhotoChoose), QDir::homePath(),
        AirStrings::text(Text::YouPhotoFilter).arg(patterns.join(QLatin1Char(' '))));
    if (self && !path.isEmpty()) {
        applyPhoto(path);
    }
}

void AirYouPage::applyPhoto(const QString& path)
{
    switch (profileInfo.setAvatar(path)) {
    case IProfileInfo::SetAvatarResult::OK:
    case IProfileInfo::SetAvatarResult::EmptyPath:
        // The photo comes back through the "you" block once it is saved.
        return;
    case IProfileInfo::SetAvatarResult::CanNotOpen:
    case IProfileInfo::SetAvatarResult::CanNotRead:
        AirDialog::notice(this, AirStrings::text(Text::YouPhotoFailedTitle),
                          AirStrings::text(Text::YouPhotoUnreadable));
        return;
    case IProfileInfo::SetAvatarResult::TooLarge:
        AirDialog::notice(this, AirStrings::text(Text::YouPhotoFailedTitle),
                          AirStrings::text(Text::YouPhotoTooLarge));
        return;
    }
}

void AirYouPage::saveQr()
{
    const QPointer<AirYouPage> self(this);
    const QString path =
        askSavePath(window(), AirStrings::text(Text::YouSaveQr),
                    QDir::home().filePath(profileInfo.getProfileName() + QStringLiteral(".png")),
                    AirStrings::text(Text::YouQrFilter), QStringLiteral("png"));
    if (self && !path.isEmpty()) {
        applySaveQr(path);
    }
}

void AirYouPage::applySaveQr(const QString& path)
{
    switch (profileInfo.saveQr(qrPicture(), path)) {
    case IProfileInfo::SaveResult::OK:
        say(AirStrings::text(Text::YouQrSaved));
        return;
    case IProfileInfo::SaveResult::EmptyPath:
        return;
    case IProfileInfo::SaveResult::NoWritePermission:
    case IProfileInfo::SaveResult::Error:
        AirDialog::notice(this, AirStrings::text(Text::YouQrSaveFailedTitle),
                          AirStrings::text(Text::YouNotWritable));
        return;
    }
}

void AirYouPage::exportProfile()
{
    askExport(this, profileInfo);
}

void AirYouPage::applyExport(const QString& path)
{
    finishExport(this, profileInfo, path);
}

void AirYouPage::askExport(QWidget* anchor, IProfileInfo& info)
{
    const QPointer<QWidget> origin(anchor);
    const QString path = askSavePath(anchor->window(), AirStrings::text(Text::YouExport),
                                     QDir::home().filePath(info.getProfileName() + Core::TOX_EXT),
                                     AirStrings::text(Text::YouExportFilter), Core::TOX_EXT.mid(1));
    if (origin && !path.isEmpty()) {
        finishExport(origin, info, path);
    }
}

void AirYouPage::finishExport(QWidget* anchor, IProfileInfo& info, const QString& path)
{
    switch (info.exportProfile(path)) {
    case IProfileInfo::SaveResult::OK:
        if (anchor->isVisible()) {
            AirToast::post(anchor, AirStrings::text(Text::YouExported));
        }
        return;
    case IProfileInfo::SaveResult::EmptyPath:
        return;
    case IProfileInfo::SaveResult::NoWritePermission:
    case IProfileInfo::SaveResult::Error:
        AirDialog::notice(anchor, AirStrings::text(Text::YouExportFailedTitle),
                          AirStrings::text(Text::YouNotWritable));
        return;
    }
}

void AirYouPage::rename()
{
    const QPointer<AirYouPage> self(this);
    bool ok = false;
    const QString name = QInputDialog::getText(window(), AirStrings::text(Text::YouRenameTitle),
                                               AirStrings::text(Text::YouRenameLabel),
                                               QLineEdit::Normal, profileInfo.getProfileName(), &ok);
    if (self && ok) {
        applyRename(name);
    }
}

void AirYouPage::applyRename(const QString& name)
{
    const QString wanted = name.trimmed();
    if (wanted.isEmpty() || wanted == profileInfo.getProfileName()) {
        return;
    }
    switch (profileInfo.renameProfile(wanted)) {
    case IProfileInfo::RenameResult::OK:
        refreshProfile();
        say(AirStrings::text(Text::YouRenamed));
        return;
    case IProfileInfo::RenameResult::EmptyName:
        return;
    case IProfileInfo::RenameResult::ProfileAlreadyExists:
        AirDialog::notice(this, AirStrings::text(Text::YouRenameFailedTitle),
                          AirStrings::text(Text::YouRenameExists).arg(wanted));
        return;
    case IProfileInfo::RenameResult::Error:
        AirDialog::notice(this, AirStrings::text(Text::YouRenameFailedTitle),
                          AirStrings::text(Text::YouRenameError));
        return;
    }
}

void AirYouPage::askPassword()
{
    const QPointer<AirYouPage> self(this);
    // On the heap: the window that owns the dialog may go while it is open, and the dialog with it.
    auto* const dialog =
        new SetPasswordDialog(AirStrings::text(Text::YouPasswordNew), QString(), window());
    const QPointer<SetPasswordDialog> open(dialog);
    const int result = dialog->exec();
    if (!open) {
        return;
    }
    const QString password = dialog->getPassword();
    delete dialog;
    if (self && result == QDialog::Accepted) {
        applyNewPassword(password);
    }
}

void AirYouPage::applyNewPassword(const QString& password)
{
    // Removing the password is a question of its own, with what it means.
    if (password.isEmpty()) {
        return;
    }
    const bool done = profileInfo.setPassword(password);
    refreshProfile();
    if (done) {
        say(AirStrings::text(Text::YouPasswordChanged));
    } else {
        AirDialog::notice(this, AirStrings::text(Text::YouPasswordFailedTitle),
                          AirStrings::text(Text::YouPasswordFailedMessage));
    }
}

void AirYouPage::askRemovePassword()
{
    const QPointer<AirYouPage> self(this);
    AirDialog::confirm(this, AirStrings::text(Text::YouPasswordRemoveTitle),
                       AirStrings::text(Text::YouPasswordRemoveMessage),
                       AirStrings::text(Text::YouPasswordRemoveAction), AirDialog::Verb::Danger,
                       [self] {
                           AirYouPage* const you = self.data();
                           if (!you) {
                               return;
                           }
                           const bool done = you->profileInfo.deletePassword();
                           you->refreshProfile();
                           if (done) {
                               you->say(AirStrings::text(Text::YouPasswordRemoved));
                           } else {
                               AirDialog::notice(you, AirStrings::text(Text::YouPasswordFailedTitle),
                                                 AirStrings::text(Text::YouPasswordFailedMessage));
                           }
                       });
}

void AirYouPage::askAntispam()
{
    const QPointer<AirYouPage> self(this);
    AirDialog::confirm(this, AirStrings::text(Text::YouAntispamTitle),
                       AirStrings::text(Text::YouAntispamMessage),
                       AirStrings::text(Text::YouAntispamAction), AirDialog::Verb::Ink, [self] {
                           AirYouPage* const you = self.data();
                           if (!you) {
                               return;
                           }
                           you->profileInfo.changeNospam();
                           you->refreshId();
                           you->say(AirStrings::text(Text::YouAntispamDone));
                       });
}

void AirYouPage::askLogout()
{
    const QPointer<AirYouPage> self(this);
    AirDialog::confirm(this, AirStrings::text(Text::YouLogoutTitle), logoutMessage(),
                       AirStrings::text(Text::YouLogout), AirDialog::Verb::Ink, [self] {
                           if (AirYouPage* const you = self.data()) {
                               you->profileInfo.logout();
                           }
                       });
}

void AirYouPage::askDelete()
{
    const QPointer<AirYouPage> self(this);
    AirDialog::confirm(this, AirStrings::text(Text::YouDeleteTitle),
                       AirStrings::text(Text::YouDeleteMessage).arg(profileInfo.getProfileName()),
                       AirStrings::text(Text::Delete), AirDialog::Verb::Danger, [self] {
                           if (AirYouPage* const you = self.data()) {
                               you->profileInfo.removeProfile();
                           }
                       });
}

QString AirYouPage::logoutMessage() const
{
    return AirStrings::text(profileInfo.isEncrypted() ? Text::YouLogoutMessage
                                                      : Text::YouLogoutMessageNoPassword);
}
