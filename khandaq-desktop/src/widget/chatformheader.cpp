/*
    Copyright © 2017-2019 by The qTox Project Contributors

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

#include "chatformheader.h"

#include "src/persistence/settings.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/style.h"
#include "src/widget/tool/croppinglabel.h"
#include "src/widget/translator.h"

#include <QAbstractButton>
#include <QEvent>
#include <QHBoxLayout>
#include <QLabel>
#include <QPainter>
#include <QPushButton>
#include <QTextDocument>
#include <QVBoxLayout>
#include <QVariantAnimation>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Motion = AirTokens::Motion;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;
namespace Stroke = AirTokens::Stroke;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

const QString STYLE_PATH = QStringLiteral("chatForm/buttons.css");

const QString STATE_NAME[] = {
    QString{},
    QStringLiteral("green"),
    QStringLiteral("red"),
    QStringLiteral("yellow"),
    QStringLiteral("yellow"),
};

template <class T, class Fun>
QPushButton* createButton(const QString& name, T* self, Fun onClickSlot,
    Settings& settings, Style& style)
{
    QPushButton* btn = new QPushButton();
    btn->setAttribute(Qt::WA_LayoutUsesWidgetRect);
    btn->setObjectName(name);
    btn->setStyleSheet(style.getStylesheet(STYLE_PATH, settings));
    QObject::connect(btn, &QPushButton::clicked, self, onClickSlot);
    return btn;
}

void setToolTipAndName(QAbstractButton* button, const QString& text)
{
    button->setToolTip(text);
    button->setAccessibleName(text);
}

template<class State>
void setStateName(QAbstractButton* btn, State state)
{
    const int index = static_cast<int>(state);
    btn->setProperty("state", STATE_NAME[index]);
    btn->setEnabled(index != 0);
}

void setInk(QLabel* label, TextRole role, Color ink)
{
    label->setFont(air().font(role));
    QPalette palette = label->palette();
    palette.setColor(QPalette::WindowText, air().color(ink));
    label->setPalette(palette);
}

} // namespace

namespace ChatFormHeaderParts {

/**
 * The name with its chevron and the presence line under it, as one button: a click anywhere on
 * it unfolds the dossier. The labels inside leave their clicks to it.
 */
class Title : public QAbstractButton
{
public:
    explicit Title(QWidget* parent)
        : QAbstractButton(parent)
    {
        setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Preferred);
    }

    void setChevronVisible(bool visible)
    {
        chevron = visible;
        update();
    }

    void setOpen(bool open)
    {
        const qreal target = open ? 180.0 : 0.0;
        if (motion) {
            motion->stop();
        }
        if (qFuzzyCompare(angle + 1.0, target + 1.0)) {
            return;
        }
        if (air().reducedMotion() || !isVisible()) {
            angle = target;
            update();
            return;
        }
        auto* const animation = new QVariantAnimation(this);
        animation->setStartValue(angle);
        animation->setEndValue(target);
        animation->setDuration(air().duration(Motion::reveal));
        animation->setEasingCurve(air().easeOut());
        connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
            angle = value.toReal();
            update();
        });
        motion = animation;
        animation->start(QAbstractAnimation::DeleteWhenStopped);
    }

    /** Where the chevron sits: right after the name, centred on its line. */
    QWidget* nameWidget = nullptr;

protected:
    void paintEvent(QPaintEvent* event) override;

    void enterEvent(QEvent* event) override
    {
        update();
        QAbstractButton::enterEvent(event);
    }

    void leaveEvent(QEvent* event) override
    {
        update();
        QAbstractButton::leaveEvent(event);
    }

private:
    bool chevron = false;
    qreal angle = 0;
    QPointer<QVariantAnimation> motion;
};

void Title::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    if (chevron && nameWidget) {
        const QRect name = nameWidget->geometry();
        const int side = Size::iconSmall;
        const bool rtl = layoutDirection() == Qt::RightToLeft;
        const qreal x = rtl ? name.left() - Space::s4 - side : name.right() + 1 + Space::s4;
        const QPointF centre(x + side / 2.0, name.center().y() + 1);
        const QColor ink = underMouse() ? air().color(Color::Ink) : air().color(Color::Ink2);
        const QPixmap glyph =
            air().icon(QStringLiteral("chevron-down"), ink, side, devicePixelRatioF());
        painter.save();
        painter.setRenderHint(QPainter::SmoothPixmapTransform);
        painter.translate(centre);
        painter.rotate(angle);
        painter.drawPixmap(QPointF(-side / 2.0, -side / 2.0), glyph);
        painter.restore();
    }
    if (AirPaint::keyboardFocus(this)) {
        AirPaint::focusRing(painter, QRectF(rect()), AirTokens::Radius::control);
    }
}

} // namespace ChatFormHeaderParts

ChatFormHeader::ChatFormHeader(Settings& settings_, Style& style_, QWidget* parent)
    : QWidget(parent)
    , mode{Mode::AV}
    , callState{CallButtonState::Disabled}
    , videoState{CallButtonState::Disabled}
    , volState{ToolButtonState::Disabled}
    , micState{ToolButtonState::Disabled}
    , settings{settings_}
    , style{style_}
{
    // sg-dossier-head: 12 px above and 16 px at the sides; the day band closes it with a line.
    column = new QVBoxLayout(this);
    column->setContentsMargins(Space::s16, Space::s12, Space::s16, Space::s10);
    column->setSpacing(Space::s8);

    auto* const headLayout = new QHBoxLayout();
    headLayout->setContentsMargins(0, 0, 0, 0);
    headLayout->setSpacing(Space::s12);

    tile = new AirTile(Size::tileDesk, this);

    title = new ChatFormHeaderParts::Title(this);
    title->setObjectName(QStringLiteral("chatTitle"));
    auto* const titleLayout = new QVBoxLayout(title);
    titleLayout->setContentsMargins(0, 0, 0, 0);
    titleLayout->setSpacing(Space::s2);

    auto* const nameLine = new QHBoxLayout();
    nameLine->setContentsMargins(0, 0, 0, 0);
    nameLine->setSpacing(0);
    nameLabel = new CroppingLabel(title);
    nameLabel->setObjectName("nameLabel");
    nameLabel->setTextFormat(Qt::PlainText);
    nameLabel->setEditable(true);
    connect(nameLabel, &CroppingLabel::editFinished, this, &ChatFormHeader::nameChanged);
    title->nameWidget = nameLabel;
    // The label grows up to its name and no further, so the chevron follows the name; the
    // stretch after it takes the rest of the line.
    nameLine->addWidget(nameLabel, 1);
    nameLine->addSpacing(Space::s4 + Size::iconSmall);
    nameLine->addStretch(0);
    titleLayout->addLayout(nameLine);

    // kq-presence: the link mark and the words, 8 px apart.
    auto* const presenceLine = new QHBoxLayout();
    presenceLine->setContentsMargins(0, 0, 0, 0);
    presenceLine->setSpacing(Space::s8);
    presenceMark = new AirLinkMark(title);
    presenceLabel = new QLabel(title);
    presenceLabel->setTextFormat(Qt::PlainText);
    presenceLine->addWidget(presenceMark, 0, Qt::AlignVCenter);
    presenceLine->addWidget(presenceLabel, 1);
    titleLayout->addLayout(presenceLine);
    presenceMark->hide();
    presenceLabel->hide();

    // What the forms add under the name: the members of a group, the time of a call.
    headTextLayout = new QVBoxLayout();
    headTextLayout->setContentsMargins(0, 0, 0, 0);
    headTextLayout->setSpacing(Space::s2);

    auto* const textColumn = new QVBoxLayout();
    textColumn->setContentsMargins(0, 0, 0, 0);
    textColumn->setSpacing(Space::s2);
    textColumn->addWidget(title);
    textColumn->addLayout(headTextLayout);

    searchButton = new AirIconButton(QStringLiteral("search"), this);
    videoButton = new AirIconButton(QStringLiteral("video"), this);
    callButton = new AirIconButton(QStringLiteral("phone"), this);
    videoButton->setObjectName(QStringLiteral("chatVideoCall"));
    callButton->setObjectName(QStringLiteral("chatCall"));
    connect(searchButton, &AirIconButton::clicked, this, &ChatFormHeader::searchRequested);
    connect(videoButton, &AirIconButton::clicked, this, &ChatFormHeader::videoCallTriggered);
    connect(callButton, &AirIconButton::clicked, this, &ChatFormHeader::callTriggered);
    connect(title, &QAbstractButton::clicked, this, [this] {
        if (dossierEnabled) {
            emit dossierToggled();
        }
    });

    // The microphone and sound switches of a call are on its band now; these stay hidden.
    micButton = createButton("micButton", this, &ChatFormHeader::micMuteToggle, settings, style);
    volButton = createButton("volButton", this, &ChatFormHeader::volMuteToggle, settings, style);
    micButton->hide();
    volButton->hide();

    // A group has no calls: where the buttons would be, the glyph and the words say so.
    noCallsNote = new QWidget(this);
    auto* const noCallsLayout = new QHBoxLayout(noCallsNote);
    noCallsLayout->setContentsMargins(Space::s8, 0, Space::s4, 0);
    noCallsLayout->setSpacing(Space::s6);
    noCallsGlyph = new QLabel(noCallsNote);
    noCallsGlyph->setFixedSize(Size::iconSmall, Size::iconSmall);
    noCallsWords = new QLabel(noCallsNote);
    noCallsWords->setTextFormat(Qt::PlainText);
    // In a narrow window the words take two lines rather than run past the edge.
    noCallsWords->setWordWrap(true);
    noCallsLayout->addWidget(noCallsGlyph, 0, Qt::AlignVCenter);
    noCallsLayout->addWidget(noCallsWords, 1, Qt::AlignVCenter);
    noCallsNote->setFixedHeight(Size::control);
    noCallsNote->hide();

    auto* const actions = new QHBoxLayout();
    actions->setContentsMargins(0, 0, 0, 0);
    actions->setSpacing(Space::s4);
    actions->addWidget(micButton);
    actions->addWidget(volButton);
    actions->addWidget(searchButton);
    actions->addWidget(videoButton);
    actions->addWidget(callButton);
    actions->addWidget(noCallsNote);

    headLayout->addWidget(tile, 0, Qt::AlignTop);
    headLayout->addLayout(textColumn, 1);
    headLayout->addLayout(actions);
    headLayout->setAlignment(actions, Qt::AlignTop);
    column->addLayout(headLayout);

    // The day band lines up with the name.
    dayBand = new QWidget(this);
    auto* const bandLayout = new QHBoxLayout(dayBand);
    bandLayout->setContentsMargins(Size::tileDesk + Space::s12, 0, 0, 0);
    bandLayout->setSpacing(Space::s12);
    dayStrip = new AirDayStrip(false, dayBand);
    dayHint = new QLabel(dayBand);
    dayHint->setTextFormat(Qt::PlainText);
    // In a narrow column the hours of the last day go on to a second line, as on the phones,
    // instead of widening the header past the column.
    dayHint->setWordWrap(true);
    bandLayout->addWidget(dayStrip, 0, Qt::AlignVCenter);
    bandLayout->addWidget(dayHint, 1, Qt::AlignVCenter);
    column->addWidget(dayBand);
    dayBand->hide();

    setDossierEnabled(false);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &ChatFormHeader::restyle);
    restyle();

    updateButtonsView();
    Translator::registerHandler(std::bind(&ChatFormHeader::retranslateUi, this), this);

    connect(&style, &Style::themeReload, this, &ChatFormHeader::reloadTheme);
}

ChatFormHeader::~ChatFormHeader()
{
    Translator::unregister(this);
}

void ChatFormHeader::setName(const QString& newName)
{
    nameLabel->setText(newName);
    // The label hugs the name, so the chevron follows it; a long name is cut with an ellipsis.
    nameLabel->minimizeMaximumWidth();
    nameLabel->setToolTip(Qt::convertFromPlainText(newName, Qt::WhiteSpaceNormal));
    title->setAccessibleName(newName);
}

void ChatFormHeader::setMode(ChatFormHeader::Mode mode_)
{
    mode = mode_;
    refreshCallButtons();
}

void ChatFormHeader::setCallsBusy(bool busy)
{
    callsBusy = busy;
    refreshCallButtons();
}

void ChatFormHeader::setNoCallsNote(bool shown)
{
    noCallsNote->setVisible(shown);
}

void ChatFormHeader::refreshCallButtons()
{
    callButton->setVisible((mode & Mode::Audio) && !callsBusy);
    videoButton->setVisible((mode & Mode::Video) && !callsBusy);
}

void ChatFormHeader::setTile(const QString& name, int tone, AirTile::Kind kind)
{
    tile->setPerson(name, tone);
    tile->setKind(kind);
}

void ChatFormHeader::setPresence(Air::LinkState link, const QString& text)
{
    presenceMark->setState(link);
    presenceLabel->setText(text);
    presenceMark->setVisible(!text.isEmpty());
    presenceLabel->setVisible(!text.isEmpty());
}

void ChatFormHeader::setSubtitle(const QString& text)
{
    presenceMark->hide();
    presenceLabel->setText(text);
    presenceLabel->setVisible(!text.isEmpty());
}

void ChatFormHeader::addBand(QWidget* band)
{
    auto* const holder = new QWidget(this);
    auto* const layout = new QHBoxLayout(holder);
    layout->setContentsMargins(Size::tileDesk + Space::s12, 0, 0, 0);
    layout->setSpacing(0);
    band->setParent(holder);
    layout->addWidget(band, 1);
    column->addWidget(holder);
    // The holder shows only with its band, so a hidden band leaves no gap in the column.
    holder->setVisible(!band->isHidden());
    band->installEventFilter(this);
}

bool ChatFormHeader::eventFilter(QObject* watched, QEvent* event)
{
    if (event->type() == QEvent::ShowToParent || event->type() == QEvent::HideToParent) {
        auto* const band = qobject_cast<QWidget*>(watched);
        QWidget* const holder = band ? band->parentWidget() : nullptr;
        if (holder && holder->parentWidget() == this) {
            holder->setVisible(event->type() == QEvent::ShowToParent);
        }
    }
    return QWidget::eventFilter(watched, event);
}

void ChatFormHeader::setDayBand(const Air::DayStrip& strip, bool liveNow, const QString& hint)
{
    dayStrip->setStrip(strip, liveNow);
    const QString code = settings.getTranslation();
    const QLocale locale = code.isEmpty() ? QLocale::system() : QLocale(code);
    dayStrip->setAccessibleName(AirStrings::dayStripSpokenText(strip, locale, liveNow));
    dayHint->setText(hint);
}

void ChatFormHeader::setDayBandVisible(bool visible)
{
    dayBand->setVisible(visible);
}

void ChatFormHeader::setDossierEnabled(bool enabled)
{
    dossierEnabled = enabled;
    title->setChevronVisible(enabled);
    title->setFocusPolicy(enabled ? Qt::StrongFocus : Qt::NoFocus);
    title->setCursor(enabled ? Qt::PointingHandCursor : Qt::ArrowCursor);
    retranslateUi();
}

void ChatFormHeader::setNameEditable(bool editable)
{
    nameLabel->setEditable(editable);
}

void ChatFormHeader::setGroupDossier(bool group)
{
    groupDossier = group;
    retranslateUi();
}

void ChatFormHeader::setDossierOpen(bool open)
{
    dossierOpen = open;
    title->setOpen(open);
    retranslateUi();
}

void ChatFormHeader::beginRename()
{
    nameLabel->editBegin();
}

void ChatFormHeader::setSearchVisible(bool visible)
{
    searchButton->setVisible(visible);
}

void ChatFormHeader::retranslateUi()
{
    setToolTipAndName(callButton, AirStrings::text(Text::ChatCall));
    setToolTipAndName(videoButton, AirStrings::text(Text::ChatVideo));
    setToolTipAndName(micButton,
                      AirStrings::text(micState == ToolButtonState::On ? Text::CallMicUnmute
                                                                       : Text::CallMicMute));
    setToolTipAndName(volButton,
                      AirStrings::text(volState == ToolButtonState::On ? Text::CallSoundUnmute
                                                                       : Text::CallSoundMute));
    const QString noCalls = AirStrings::text(Text::GroupNoCalls);
    noCallsWords->setText(noCalls);
    noCallsNote->setToolTip(AirStrings::text(Text::GroupNoCallsNote));
    noCallsNote->setAccessibleName(noCalls);
    noCallsNote->setAccessibleDescription(noCallsNote->toolTip());
    searchButton->setToolTip(AirStrings::text(Text::ChatSearch));
    searchButton->setAccessibleName(searchButton->toolTip());
    const Text openHint = groupDossier ? Text::GroupDossierHint : Text::ChatDossierHint;
    title->setToolTip(dossierEnabled ? AirStrings::text(dossierOpen ? Text::DossierCloseHint : openHint)
                                     : QString());
}

void ChatFormHeader::updateButtonsView()
{
    setStateName(micButton, micState);
    setStateName(volButton, volState);
    callButton->setEnabled(callState != CallButtonState::Disabled);
    videoButton->setEnabled(videoState != CallButtonState::Disabled);
    // A call in progress, or one we are making, ends with the same button: it turns red.
    callButton->setDanger(callState == CallButtonState::InCall
                          || callState == CallButtonState::Outgoing);
    videoButton->setDanger(videoState == CallButtonState::InCall
                           || videoState == CallButtonState::Outgoing);
    retranslateUi();
    Style::repolish(micButton);
    Style::repolish(volButton);
}

void ChatFormHeader::updateExtensionSupport(ExtensionSet extensions)
{
    // The redesign shows no extension mark: what a message needs is negotiated quietly.
    std::ignore = extensions;
}

void ChatFormHeader::updateCallButtons(bool online, bool audio, bool video)
{
    std::ignore = online;
    const bool audioAvaliable = mode & Mode::Audio;
    const bool videoAvaliable = mode & Mode::Video;
    if (!audioAvaliable) {
        callState = CallButtonState::Disabled;
    } else if (video) {
        callState = CallButtonState::Disabled;
    } else if (audio) {
        callState = CallButtonState::InCall;
    } else {
        callState = CallButtonState::Avaliable;
    }

    if (!videoAvaliable) {
        videoState = CallButtonState::Disabled;
    } else if (video) {
        videoState = CallButtonState::InCall;
    } else if (audio) {
        videoState = CallButtonState::Disabled;
    } else {
        videoState = CallButtonState::Avaliable;
    }

    updateButtonsView();
}

void ChatFormHeader::updateMuteMicButton(bool active, bool inputMuted)
{
    micButton->setEnabled(active);
    if (active) {
        micState = inputMuted ? ToolButtonState::On : ToolButtonState::Off;
    } else {
        micState = ToolButtonState::Disabled;
    }

    updateButtonsView();
}

void ChatFormHeader::updateMuteVolButton(bool active, bool outputMuted)
{
    volButton->setEnabled(active);
    if (active) {
        volState = outputMuted ? ToolButtonState::On : ToolButtonState::Off;
    } else {
        volState = ToolButtonState::Disabled;
    }

    updateButtonsView();
}

void ChatFormHeader::setAvatar(const QPixmap &img)
{
    tile->setPhoto(img);
}

QSize ChatFormHeader::getAvatarSize() const
{
    return tile->sizeHint();
}

void ChatFormHeader::reloadTheme()
{
    volButton->setStyleSheet(style.getStylesheet(STYLE_PATH, settings));
    micButton->setStyleSheet(style.getStylesheet(STYLE_PATH, settings));
}

void ChatFormHeader::restyle()
{
    setInk(nameLabel, TextRole::Heading, Color::Ink);
    nameLabel->minimizeMaximumWidth();
    setInk(presenceLabel, TextRole::Caption, Color::Ink2);
    setInk(dayHint, TextRole::Caption, Color::Ink2);
    setInk(noCallsWords, TextRole::Caption, Color::Ink2);
    noCallsGlyph->setPixmap(air().icon(QStringLiteral("phone"), air().color(Color::Ink3),
                                       Size::iconSmall, devicePixelRatioF()));
    update();
}

void ChatFormHeader::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    // The line that closes the head, across the whole column.
    painter.fillRect(QRect(0, height() - static_cast<int>(Stroke::hairline), width(),
                           static_cast<int>(Stroke::hairline)),
                     air().color(Color::Line));
}

void ChatFormHeader::addWidget(QWidget* widget, int stretch, Qt::Alignment alignment)
{
    headTextLayout->addWidget(widget, stretch, alignment);
}

void ChatFormHeader::addLayout(QLayout* layout)
{
    headTextLayout->addLayout(layout);
}

void ChatFormHeader::addStretch()
{
    headTextLayout->addStretch();
}
