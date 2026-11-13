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


#include "loginscreen.h"

#include "src/core/core.h"
#include "src/model/air/airyou.h"
#include "src/persistence/credentialstore.h"
#include "src/persistence/paths.h"
#include "src/persistence/profile.h"
#include "src/persistence/settings.h"
#include "src/widget/air/airchatparts.h"
#include "src/widget/air/airgroupviews.h"
#include "src/widget/air/airnewcontact.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstartparts.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/air/airwalk.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/air/airyou.h"
#include "src/widget/style.h"
#include "src/widget/translator.h"

#include <QApplication>
#include <QBoxLayout>
#include <QClipboard>
#include <QCloseEvent>
#include <QDebug>
#include <QDir>
#include <QFileDialog>
#include <QFileInfo>
#include <QGraphicsOpacityEffect>
#include <QGuiApplication>
#include <QKeyEvent>
#include <QLabel>
#include <QLineEdit>
#include <QMenu>
#include <QMouseEvent>
#include <QPainter>
#include <QScreen>
#include <QScrollArea>
#include <QScrollBar>
#include <QStyle>
#include <QTextCursor>
#include <QTextEdit>
#include <QTimer>
#include <QVariantAnimation>

#include <initializer_list>

#ifdef KHANDAQ_SCREENSHOT_HOOK
#include <QFile>

#include <memory>
#endif

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
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

QString words(Text which)
{
    return AirStrings::text(which);
}

/**
 * The start window's sizes, all of them here: 560 px wide (02-plan.md, E19 on the desktop) and 640
 * high where the screen has room, one column of words and fields in the middle of the screen.
 * DESIGN.md records them with the main window's under Desktop stage 5; tokens.json has no window
 * sizes, so they stand here.
 */
constexpr int windowWidth = 560;
constexpr int windowHeight = 640;
/** Not shorter than this on a small screen: the title, a field and the bar still show. */
constexpr int windowLeastHeight = 400;
/** The QR code of step 3 takes 5 px a module, so it stands beside the ID within the window. */
constexpr int qrModulePixels = 5;
/** An ID with "tox:" takes version 5 at level M: 37 modules a side, and the quiet zone round them. */
constexpr int qrSide = (37 + 2 * Air::qrQuietModules) * qrModulePixels;
/** Pixels a module takes in the picture Copy QR code and Save QR code give, as on You. */
constexpr int qrPictureModule = 12;

const char roleProperty[] = "airRole";
const char colorProperty[] = "airColor";

/** A label in a role of the type scale; restyle() colours it again from what it keeps. */
QLabel* makeLabel(TextRole role, Color color, QWidget* parent)
{
    auto* const label = new QLabel(parent);
    label->setTextFormat(Qt::PlainText);
    label->setWordWrap(true);
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

/**
 * A switch at the start edge, its words beside it and what it means under them. A click on the
 * words turns it too, as a click on the words of a box does.
 */
class SwitchRow : public QWidget
{
public:
    explicit SwitchRow(QWidget* parent)
        : QWidget(parent)
    {
        auto* const row = new QHBoxLayout(this);
        row->setContentsMargins(0, 0, 0, 0);
        row->setSpacing(Space::s12);
        toggle = new AirSwitch(this);
        row->addWidget(toggle, 0, Qt::AlignTop);
        auto* const text = new QVBoxLayout;
        text->setSpacing(Space::s2);
        label = makeLabel(TextRole::Body, Color::Ink, this);
        note = makeLabel(TextRole::Caption, Color::Ink2, this);
        text->addWidget(label);
        text->addWidget(note);
        row->addLayout(text, 1);
    }

    AirSwitch* toggle = nullptr;
    QLabel* label = nullptr;
    QLabel* note = nullptr;

protected:
    void mousePressEvent(QMouseEvent* event) override
    {
        event->setAccepted(event->button() == Qt::LeftButton);
    }

    void mouseReleaseEvent(QMouseEvent* event) override
    {
        if (event->button() == Qt::LeftButton && rect().contains(event->pos()) && toggle->isEnabled()) {
            toggle->click();
        }
    }
};

/** Between from and to, as far as progress, from 0 to 1. */
int between(int from, int to, qreal progress)
{
    return from + qRound((to - from) * progress);
}
} // namespace

/**
 * One page of the start window: the way back and the progress of first launch at the top, the
 * title, its words and what the page holds in a column that scrolls, and the main button in a bar
 * at the bottom, with why it waits beside it (E21: a refusal is said next to the action).
 */
class LoginScreen::Page : public QWidget
{
public:
    explicit Page(QWidget* parent);

    AirIconButton* back = nullptr;
    AirStepProgress* progress = nullptr;
    QScrollArea* scroller = nullptr;
    QWidget* content = nullptr;
    QLabel* title = nullptr;
    QLabel* body = nullptr;
    QVBoxLayout* column = nullptr;
    QWidget* bar = nullptr;
    QLabel* reason = nullptr;
    AirButton* main = nullptr;

    /** The step of first launch this page is, from 1; 0 for sign-in and moving a profile in. */
    int step = 0;
    QString mainText;
    /** What the main button says while the page waits on the profile. */
    QString busyText;
    bool busy = false;
    /** Why the main button waits; empty while it can go. */
    std::function<QString()> refusal;
    std::function<void()> onMain;

protected:
    void paintEvent(QPaintEvent* event) override;
};

LoginScreen::Page::Page(QWidget* parent)
    : QWidget(parent)
{
    AirPaint::followTheme(this);
    auto* const outer = new QVBoxLayout(this);
    outer->setContentsMargins(0, 0, 0, 0);
    outer->setSpacing(0);

    // The way back at the start edge and the progress in the middle; both keep their room while
    // hidden, so nothing moves from page to page.
    auto* const top = new QHBoxLayout;
    top->setContentsMargins(Space::s24, Space::s16, Space::s24, 0);
    top->setSpacing(0);
    back = new AirIconButton(QStringLiteral("chevron-down"), this);
    back->setObjectName(QStringLiteral("startBack"));
    QSizePolicy keepRoom = back->sizePolicy();
    keepRoom.setRetainSizeWhenHidden(true);
    back->setSizePolicy(keepRoom);
    top->addWidget(back);
    top->addStretch(1);
    progress = new AirStepProgress(this);
    keepRoom = progress->sizePolicy();
    keepRoom.setRetainSizeWhenHidden(true);
    progress->setSizePolicy(keepRoom);
    top->addWidget(progress, 0, Qt::AlignVCenter);
    top->addStretch(1);
    top->addSpacing(Size::control);
    outer->addLayout(top);

    scroller = new QScrollArea(this);
    scroller->setFrameShape(QFrame::NoFrame);
    scroller->setWidgetResizable(true);
    scroller->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    scroller->setFocusPolicy(Qt::NoFocus);
    content = new QWidget(scroller);
    auto* const page = new QVBoxLayout(content);
    page->setContentsMargins(Space::s32, Space::s24, Space::s32, Space::s24);
    page->setSpacing(Space::s24);
    auto* const header = new QVBoxLayout;
    header->setSpacing(Space::s8);
    title = makeLabel(TextRole::Display, Color::Ink, content);
    body = makeLabel(TextRole::Body, Color::Ink2, content);
    header->addWidget(title);
    header->addWidget(body);
    page->addLayout(header);
    column = new QVBoxLayout;
    column->setSpacing(Space::s16);
    page->addLayout(column);
    page->addStretch(1);
    scroller->setWidget(content);
    outer->addWidget(scroller, 1);

    bar = new QWidget(this);
    auto* const barRow = new QHBoxLayout(bar);
    barRow->setContentsMargins(Space::s32, Space::s16, Space::s32, Space::s16);
    barRow->setSpacing(Space::s16);
    reason = makeLabel(TextRole::Caption, Color::Ink2, bar);
    // Hidden, it keeps its room, so the main button stays at the end edge on every page.
    QSizePolicy reasonRoom = reason->sizePolicy();
    reasonRoom.setRetainSizeWhenHidden(true);
    reason->setSizePolicy(reasonRoom);
    barRow->addWidget(reason, 1, Qt::AlignVCenter);
    main = new AirButton(AirButton::Kind::Ink, bar);
    main->setObjectName(QStringLiteral("startMain"));
    barRow->addWidget(main, 0, Qt::AlignVCenter);
    outer->addWidget(bar);
}

void LoginScreen::Page::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.fillRect(rect(), air().color(Color::Bg));
    // The bar of the main button stands under a hairline.
    painter.fillRect(QRectF(0, bar->y(), width(), Stroke::hairline), air().color(Color::Line));
}

LoginScreen::LoginScreen(Settings& settings_, Style& style, const QString& initialProfileName,
                         QWidget* parent)
    : QDialog(parent)
    , settings{settings_}
    , quitShortcut{QKeySequence(Qt::CTRL + Qt::Key_Q), this}
{
    setObjectName(QStringLiteral("startWindow"));
    setWindowTitle(QApplication::applicationName());
    // Maximised, the column would stand alone on a screen of ground (qTox #1973).
    setWindowFlags(windowFlags() & ~Qt::WindowMaximizeButtonHint & ~Qt::WindowContextHelpButtonHint);
    setSizeGripEnabled(false);
    AirStyle::instance().follow(settings, style, this);

    auto* const outer = new QVBoxLayout(this);
    outer->setContentsMargins(0, 0, 0, 0);
    outer->setSpacing(0);
    content = new QWidget(this);
    outer->addWidget(content);
    auto* const inner = new QVBoxLayout(content);
    inner->setContentsMargins(0, 0, 0, 0);
    inner->setSpacing(0);
    stack = new AirPageStack(content);
    inner->addWidget(stack);

    buildNamePage();
    buildPasswordPage();
    buildIdPage();
    buildSignInPage();
    buildImportPages();

    connect(stack, &AirPageStack::arrived, this, &LoginScreen::arrived);
    connect(&quitShortcut, &QShortcut::activated, this, [this] {
        if (!growing) {
            done(QDialog::Rejected);
        }
    });
    connect(&AirStyle::instance(), &AirStyle::changed, this, &LoginScreen::restyle);

    const QScreen* const screen = QGuiApplication::primaryScreen();
    const int room = screen ? screen->availableGeometry().height() - 2 * Space::s32 : windowHeight;
    setFixedSize(windowWidth, qBound(windowLeastHeight, room, windowHeight));

    restyle();
    retranslateUi();
    Translator::registerHandler(std::bind(&LoginScreen::retranslateUi, this), this);

    if (profileNames().isEmpty()) {
        startFirstLaunch(true);
    } else {
        showSignIn(initialProfileName, true);
    }

#ifdef KHANDAQ_SCREENSHOT_HOOK
    playShotHook();
#endif
}

LoginScreen::~LoginScreen()
{
    Translator::unregister(this);
}

// ---- Pages ----

LoginScreen::Page* LoginScreen::makePage(const QString& objectName)
{
    auto* const page = new Page(stack);
    page->setObjectName(objectName);
    stack->addPage(page);
    pages.append(page);
    connect(page->back, &AirIconButton::clicked, this, &LoginScreen::goBack);
    connect(page->main, &AirButton::clicked, this, [this, page] { submit(page); });
    return page;
}

void LoginScreen::buildNamePage()
{
    namePage = makePage(QStringLiteral("startName"));
    namePage->step = 1;
    namePage->progress->setStep(1);
    QWidget* const holder = namePage->content;
    nameField = new AirTextField(AirTextField::Kind::Line, holder);
    nameField->setObjectName(QStringLiteral("startNameField"));
    namePage->column->addWidget(nameField);
    nameMoveButton = new AirButton(AirButton::Kind::Quiet, holder);
    nameMoveButton->setObjectName(QStringLiteral("startMove"));
    namePage->column->addWidget(nameMoveButton, 0, Qt::AlignLeading);

    connect(nameField, &AirTextField::changed, this, [this] {
        keepNameWithin();
        refreshMain(namePage);
    });
    connect(nameField, &AirTextField::submitted, this, [this] { submit(namePage); });
    connect(nameMoveButton, &AirButton::clicked, this, &LoginScreen::chooseFile);
    namePage->refusal = [this] {
        return nameField->text().trimmed().isEmpty() ? words(Text::StartNameFirst) : QString();
    };
    namePage->onMain = [this] { go(passwordPage); };
}

void LoginScreen::buildPasswordPage()
{
    passwordPage = makePage(QStringLiteral("startPassword"));
    passwordPage->step = 2;
    passwordPage->progress->setStep(2);
    QWidget* const holder = passwordPage->content;

    // The two choices exclude each other within a parent of their own.
    auto* const choices = new QWidget(holder);
    auto* const choiceBox = new QVBoxLayout(choices);
    choiceBox->setContentsMargins(0, 0, 0, 0);
    choiceBox->setSpacing(Space::s12);
    setRow = new AirRadioRow(choices);
    setRow->setObjectName(QStringLiteral("startSetPassword"));
    skipRow = new AirRadioRow(choices);
    skipRow->setObjectName(QStringLiteral("startSkipPassword"));
    choiceBox->addWidget(setRow);
    choiceBox->addWidget(skipRow);
    setRow->setChecked(true);
    passwordPage->column->addWidget(choices);

    secretBox = new QWidget(holder);
    auto* const secrets = new QVBoxLayout(secretBox);
    secrets->setContentsMargins(0, 0, 0, 0);
    secrets->setSpacing(Space::s8);
    passField = new AirSecretField(secretBox);
    passField->setObjectName(QStringLiteral("startPasswordField"));
    repeatField = new AirSecretField(secretBox);
    repeatField->setObjectName(QStringLiteral("startRepeatField"));
    passNote = new AirFieldNote(secretBox);
    secrets->addWidget(passField);
    secrets->addWidget(repeatField);
    secrets->addWidget(passNote);
    passwordPage->column->addWidget(secretBox);

    auto* const autoLogin = new SwitchRow(holder);
    autoLogin->setObjectName(QStringLiteral("startAutoLogin"));
    autoLogin->toggle->setObjectName(QStringLiteral("startAutoLoginSwitch"));
    createAutoLoginRow = autoLogin;
    createAutoLogin = autoLogin->toggle;
    createAutoLoginLabel = autoLogin->label;
    createAutoLoginNote = autoLogin->note;
    passwordPage->column->addWidget(autoLogin);

    createNote = new AirFieldNote(holder);
    passwordPage->column->addWidget(createNote);

    connect(setRow, &AirRadioRow::toggled, this, &LoginScreen::refreshPasswordChoice);
    for (AirSecretField* const field : {passField, repeatField}) {
        connect(field, &AirSecretField::changed, this, [this] {
            createNote->clear();
            refreshMain(passwordPage);
        });
    }
    connect(passField, &AirSecretField::submitted, this,
            [this] { repeatField->edit()->setFocus(Qt::TabFocusReason); });
    connect(repeatField, &AirSecretField::submitted, this, [this] { submit(passwordPage); });
    connect(createNote, &AirFieldNote::detailsRequested, this, [this](const QString& details) {
        AirDialog::notice(this, words(Text::StartCreate), words(Text::StartCreateFailed), details);
    });
    passwordPage->refusal = [this]() -> QString {
        if (!setRow->isChecked()) {
            return QString();
        }
        using Problem = Air::NewPasswordProblem;
        switch (Air::newPasswordProblem(passField->text(), repeatField->text())) {
        case Problem::None:
            return QString();
        case Problem::Empty:
            return words(Text::StartPasswordEmpty);
        case Problem::Short:
            return words(Text::StartPasswordShort);
        case Problem::Weak:
            return words(Text::StartPasswordWeak);
        case Problem::RepeatFirst:
            return words(Text::StartPasswordRepeatFirst);
        case Problem::Mismatch:
            return words(Text::StartPasswordMismatch);
        }
        return QString();
    };
    passwordPage->onMain = [this] { create(); };
}

void LoginScreen::buildIdPage()
{
    idPage = makePage(QStringLiteral("startId"));
    idPage->step = 3;
    idPage->progress->setStep(3);
    QWidget* const holder = idPage->content;

    auto* const row = new QHBoxLayout;
    row->setSpacing(Space::s24);
    auto* const qrPlate = new Plate(holder);
    qrPlate->setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    auto* const qrBox = new QVBoxLayout(qrPlate);
    qrBox->setContentsMargins(Space::s16, Space::s16, Space::s16, Space::s16);
    qrCard = new AirQrCard(qrPlate);
    qrCard->setObjectName(QStringLiteral("startQr"));
    qrCard->setFixedSize(qrSide, qrSide);
    qrBox->addWidget(qrCard);
    row->addWidget(qrPlate, 0, Qt::AlignTop);
    idParts = new AirIdParts(holder);
    idParts->setObjectName(QStringLiteral("startIdParts"));
    row->addWidget(idParts, 0, Qt::AlignTop);
    row->addStretch(1);
    idPage->column->addLayout(row);

    shareButton = new AirButton(AirButton::Kind::Plain, holder);
    shareButton->setObjectName(QStringLiteral("startShare"));
    idPage->column->addWidget(shareButton, 0, Qt::AlignLeading);
    connect(shareButton, &AirButton::clicked, this, &LoginScreen::share);
    idPage->onMain = [this] { finishFirstLaunch(true); };
}

void LoginScreen::buildSignInPage()
{
    signInPage = makePage(QStringLiteral("signIn"));
    QWidget* const holder = signInPage->content;

    // The note stands right under the field it speaks of.
    auto* const fieldBox = new QVBoxLayout;
    fieldBox->setSpacing(Space::s8);
    signInField = new AirSecretField(holder);
    signInField->setObjectName(QStringLiteral("signInPasswordField"));
    signInNote = new AirFieldNote(holder);
    fieldBox->addWidget(signInField);
    fieldBox->addWidget(signInNote);
    signInPage->column->addLayout(fieldBox);

    auto* const helpRow = new QHBoxLayout;
    helpRow->setSpacing(Space::s16);
    forgotButton = new AirButton(AirButton::Kind::Quiet, holder);
    forgotButton->setObjectName(QStringLiteral("signInForgot"));
    withoutProxyButton = new AirButton(AirButton::Kind::Plain, holder);
    withoutProxyButton->setObjectName(QStringLiteral("signInWithoutProxy"));
    withoutProxyButton->hide();
    helpRow->addWidget(forgotButton);
    helpRow->addWidget(withoutProxyButton);
    helpRow->addStretch(1);
    signInPage->column->addLayout(helpRow);

    auto* const autoLogin = new SwitchRow(holder);
    autoLogin->setObjectName(QStringLiteral("signInAutoLogin"));
    autoLogin->toggle->setObjectName(QStringLiteral("signInAutoLoginSwitch"));
    signInAutoLoginRow = autoLogin;
    signInAutoLogin = autoLogin->toggle;
    signInAutoLoginLabel = autoLogin->label;
    signInAutoLoginNote = autoLogin->note;
    signInPage->column->addWidget(autoLogin);

    profilesBox = new QWidget(holder);
    auto* const profiles = new QVBoxLayout(profilesBox);
    profiles->setContentsMargins(0, Space::s8, 0, 0);
    profiles->setSpacing(Space::s8);
    profilesTitle = makeLabel(TextRole::Label, Color::Ink2, profilesBox);
    profilesPlate = new Plate(profilesBox);
    profilesList = new QVBoxLayout(profilesPlate);
    profilesList->setContentsMargins(Space::s16, Space::s12, Space::s16, Space::s12);
    profilesList->setSpacing(Space::s8);
    profilesNote = makeLabel(TextRole::Caption, Color::Ink2, profilesBox);
    profiles->addWidget(profilesTitle);
    profiles->addWidget(profilesPlate);
    profiles->addWidget(profilesNote);
    signInPage->column->addWidget(profilesBox);

    auto* const otherWays = new QVBoxLayout;
    otherWays->setSpacing(Space::s4);
    signInCreateButton = new AirButton(AirButton::Kind::Quiet, holder);
    signInCreateButton->setObjectName(QStringLiteral("signInCreate"));
    signInMoveButton = new AirButton(AirButton::Kind::Quiet, holder);
    signInMoveButton->setObjectName(QStringLiteral("signInMove"));
    otherWays->addWidget(signInCreateButton, 0, Qt::AlignLeading);
    otherWays->addWidget(signInMoveButton, 0, Qt::AlignLeading);
    signInPage->column->addLayout(otherWays);

    connect(signInField, &AirSecretField::changed, this, [this] {
        signInNote->clear();
        refreshMain(signInPage);
    });
    connect(signInField, &AirSecretField::submitted, this, [this] { submit(signInPage); });
    connect(signInNote, &AirFieldNote::detailsRequested, this, [this](const QString& details) {
        AirDialog::notice(this, words(Text::SignInFailedTitle), signInNote->text(), details);
    });
    connect(forgotButton, &AirButton::clicked, this, &LoginScreen::askForgot);
    connect(withoutProxyButton, &AirButton::clicked, this, [this] {
        AirDialog::confirm(this, words(Text::SignInWithoutProxyTitle),
                           words(Text::SignInWithoutProxyMessage), words(Text::SignInWithoutProxy),
                           AirDialog::Verb::Ink, [this] { signIn(true); });
    });
    // Only what the person does with the switch: the settings set it too (onAutoLoginChanged).
    connect(signInAutoLogin, &AirSwitch::clicked, this, [this](bool on) {
        emit autoLoginChanged(on);
        if (!on && CredentialStore::isSupported() && !chosenProfile.isEmpty()) {
            CredentialStore::remove(chosenProfile);
        }
    });
    connect(signInCreateButton, &AirButton::clicked, this, [this] { startFirstLaunch(false); });
    connect(signInMoveButton, &AirButton::clicked, this, &LoginScreen::chooseFile);
    signInPage->refusal = [this] {
        return chosenEncrypted && signInField->text().isEmpty() ? words(Text::SignInEnterPassword)
                                                                : QString();
    };
    signInPage->onMain = [this] { signIn(false); };
}

void LoginScreen::buildImportPages()
{
    importPasswordPage = makePage(QStringLiteral("importPassword"));
    QWidget* holder = importPasswordPage->content;
    auto* const fieldBox = new QVBoxLayout;
    fieldBox->setSpacing(Space::s8);
    importField = new AirSecretField(holder);
    importField->setObjectName(QStringLiteral("importPasswordField"));
    importNote = new AirFieldNote(holder);
    fieldBox->addWidget(importField);
    fieldBox->addWidget(importNote);
    importPasswordPage->column->addLayout(fieldBox);
    connect(importField, &AirSecretField::changed, this, [this] {
        importNote->clear();
        refreshMain(importPasswordPage);
    });
    connect(importField, &AirSecretField::submitted, this, [this] { submit(importPasswordPage); });
    importPasswordPage->refusal = [this] {
        return importField->text().isEmpty() ? words(Text::SignInEnterPassword) : QString();
    };
    importPasswordPage->onMain = [this] { readImport(); };

    previewPage = makePage(QStringLiteral("importPreview"));
    holder = previewPage->content;
    auto* const nameBox = new QVBoxLayout;
    nameBox->setSpacing(Space::s4);
    previewNameTitle = makeLabel(TextRole::Caption, Color::Ink2, holder);
    previewName = makeLabel(TextRole::Heading, Color::Ink, holder);
    previewName->setObjectName(QStringLiteral("importName"));
    nameBox->addWidget(previewNameTitle);
    nameBox->addWidget(previewName);
    previewPage->column->addLayout(nameBox);
    previewId = new AirIdParts(previewPage->content);
    previewId->setObjectName(QStringLiteral("importId"));
    previewPage->column->addWidget(previewId, 0, Qt::AlignLeading);
    otherFileButton = new AirButton(AirButton::Kind::Quiet, holder);
    otherFileButton->setObjectName(QStringLiteral("importOtherFile"));
    previewPage->column->addWidget(otherFileButton, 0, Qt::AlignLeading);
    connect(otherFileButton, &AirButton::clicked, this, &LoginScreen::chooseFile);
    previewPage->onMain = [this] { confirmMoveIn(); };

    problemPage = makePage(QStringLiteral("importProblem"));
    problemPage->onMain = [this] { chooseFile(); };
}

// ---- Going from page to page ----

void LoginScreen::go(Page* page)
{
    Page* const from = history.isEmpty() ? nullptr : history.last();
    if (from == page) {
        return;
    }
    if (from) {
        leave(from);
    }
    history.append(page);
    refreshMain(page);
    stack->showPage(page, from ? AirPageStack::Travel::Forward : AirPageStack::Travel::None);
}

void LoginScreen::restart(Page* page)
{
    Page* const from = history.isEmpty() ? nullptr : history.last();
    if (from && from != page) {
        leave(from);
    }
    history.clear();
    history.append(page);
    refreshMain(page);
    stack->showPage(page, from && from != page ? AirPageStack::Travel::Forward
                                               : AirPageStack::Travel::None);
}

void LoginScreen::goBack()
{
    if (history.isEmpty() || !canGoBack(history.last())) {
        return;
    }
    Page* const from = history.takeLast();
    leave(from);
    Page* const to = history.last();
    refreshMain(to);
    stack->showPage(to, AirPageStack::Travel::Back);
}

bool LoginScreen::canGoBack(const Page* page) const
{
    // The last step has nothing behind it: the profile is made.
    return history.size() > 1 && history.last() == page && page != idPage && !page->busy && !growing;
}

void LoginScreen::leave(Page* page)
{
    // A password stays only on the page it was typed on, while that page is open, and what was
    // said under its fields goes with it.
    for (AirSecretField* const field : page->findChildren<AirSecretField*>()) {
        field->clear();
    }
    for (AirFieldNote* const note : page->findChildren<AirFieldNote*>()) {
        note->clear();
    }
}

void LoginScreen::arrived(QWidget* widget)
{
    Page* page = nullptr;
    for (Page* const candidate : pages) {
        if (candidate == widget) {
            page = candidate;
        }
    }
    if (!page) {
        return;
    }
    if (page->step > 0) {
        page->progress->fillCurrent(true);
    }
    if (page == idPage && !idRevealed) {
        idRevealed = true;
        idParts->reveal(true);
    }
    // The field the page waits for has the focus; step 2 starts at its choice, read first.
    QWidget* focus = page->main;
    if (page == namePage) {
        focus = nameField->edit();
    } else if (page == passwordPage) {
        focus = setRow->isChecked() ? static_cast<QWidget*>(setRow) : skipRow;
    } else if (page == signInPage && chosenEncrypted) {
        focus = signInField->edit();
    } else if (page == importPasswordPage) {
        focus = importField->edit();
    }
    if (focus && focus->isEnabled()) {
        focus->setFocus(Qt::OtherFocusReason);
    }
}

void LoginScreen::refreshMain(Page* page)
{
    if (!page) {
        return;
    }
    const QString why = page->busy || !page->refusal ? QString() : page->refusal();
    page->main->setText(page->busy && !page->busyText.isEmpty() ? page->busyText : page->mainText);
    page->main->setEnabled(!page->busy && why.isEmpty() && static_cast<bool>(page->onMain));
    page->reason->setText(why);
    page->reason->setVisible(!why.isEmpty());
    page->back->setVisible(canGoBack(page));
    page->progress->setVisible(page->step > 0);
}

void LoginScreen::setBusy(Page* page, bool busy)
{
    page->busy = busy;
    busyPage = busy ? page : nullptr;
    // Nothing on the page changes while it waits.
    page->content->setEnabled(!busy);
    refreshMain(page);
}

void LoginScreen::submit(Page* page)
{
    if (page->busy || growing || stack->currentPage() != page || !page->onMain
        || !page->main->isEnabled()) {
        return;
    }
    page->onMain();
}

// ---- First launch ----

QStringList LoginScreen::profileNames() const
{
    return Profile::getAllProfileNames(settings);
}

QStringList LoginScreen::takenNames() const
{
    // The settings of a portable install lie beside the profiles, so their name is taken too.
    const QDir folder(settings.getPaths().getSettingsDirPath());
    QStringList names;
    const QFileInfoList files =
        folder.entryInfoList({QStringLiteral("*.tox"), QStringLiteral("*.ini"), QStringLiteral("*.db")},
                             QDir::Files | QDir::NoDotAndDotDot);
    for (const QFileInfo& file : files) {
        names.append(file.completeBaseName());
    }
    return names;
}

void LoginScreen::startFirstLaunch(bool replaceHistory)
{
    nameField->setText(QString());
    typedName.clear();
    setRow->setChecked(true);
    createAutoLogin->setChecked(false);
    createNote->clear();
    refreshPasswordChoice();
    refreshMain(namePage);
    if (replaceHistory) {
        restart(namePage);
    } else {
        go(namePage);
    }
}

void LoginScreen::keepNameWithin()
{
    const QString now = nameField->text();
    const Air::FittedEdit fitted = Air::fitEdit(typedName, now, Air::nameLimitBytes);
    if (fitted.text != now) {
        nameField->setText(fitted.text);
        QTextCursor cursor = nameField->edit()->textCursor();
        cursor.setPosition(qBound(0, fitted.caret, fitted.text.size()));
        nameField->edit()->setTextCursor(cursor);
    }
    typedName = fitted.text;
}

void LoginScreen::refreshPasswordChoice()
{
    const bool withPassword = setRow->isChecked();
    secretBox->setVisible(withPassword);
    // Logging in by itself keeps the password in the system's store; a profile without a password
    // has none to keep, and opens at start by the switch of sign-in.
    createAutoLoginRow->setVisible(withPassword && CredentialStore::isSupported());
    if (!withPassword) {
        passField->clear();
        repeatField->clear();
    }
    createNote->clear();
    refreshMain(passwordPage);
}

void LoginScreen::create()
{
    const bool withPassword = setRow->isChecked();
    const QString password = withPassword ? passField->text() : QString();
    const bool autoLogin =
        withPassword && !createAutoLoginRow->isHidden() && createAutoLogin->isChecked();
    createdName = nameField->text().trimmed();
    createdFile = Air::profileFileName(createdName, takenNames(), words(Text::StartDefaultFolder));
    createNote->clear();
    setBusy(passwordPage, true);
    // The button says what is going on before the key is derived from the password, a moment.
    passwordPage->main->repaint();
    const QString fileName = createdFile;
    const QString selfName = createdName;
    QTimer::singleShot(0, this, [this, fileName, selfName, password, autoLogin] {
        emit createNewProfile(fileName, selfName, password, autoLogin);
    });
}

void LoginScreen::onProfileCreated(const QString& id)
{
    setBusy(passwordPage, false);
    passField->clear();
    repeatField->clear();
    createdId = id;
#ifdef KHANDAQ_SCREENSHOT_HOOK
    if (hookOpensAtOnce) {
        emit readyToOpen(false);
        return;
    }
#endif
    qrCard->setCode(Air::qrModules(QStringLiteral("tox:") + id));
    idParts->setId(id);
    idRevealed = false;
    // The groups come in one after another once the page is in; with less motion they are there.
    if (!air().reducedMotion()) {
        idParts->conceal();
    }
    go(idPage);
}

void LoginScreen::share()
{
    auto* const menu = new QMenu(this);
    menu->setAttribute(Qt::WA_DeleteOnClose);
    menu->setObjectName(QStringLiteral("startShareMenu"));
    QAction* const copyId = menu->addAction(words(Text::YouCopyId));
    connect(copyId, &QAction::triggered, this, [this] {
        QClipboard* const clipboard = QApplication::clipboard();
        clipboard->setText(createdId, QClipboard::Clipboard);
        if (clipboard->supportsSelection()) {
            clipboard->setText(createdId, QClipboard::Selection);
        }
        AirToast::post(this, words(Text::YouIdCopied));
    });
    QAction* const copyQr = menu->addAction(words(Text::YouCopyQr));
    connect(copyQr, &QAction::triggered, this, [this] {
        QApplication::clipboard()->setImage(Air::qrImage(qrCard->code(), qrPictureModule));
        AirToast::post(this, words(Text::YouQrCopied));
    });
    QAction* const save = menu->addAction(words(Text::YouSaveQr));
    connect(save, &QAction::triggered, this, &LoginScreen::saveQr);
    // Under the button, from its start edge.
    const bool rightToLeft = layoutDirection() == Qt::RightToLeft;
    menu->popup(shareButton->mapToGlobal(
        QPoint(rightToLeft ? shareButton->width() : 0, shareButton->height())));
}

void LoginScreen::saveQr()
{
    QFileDialog dialog(this, words(Text::YouSaveQr),
                       QDir::home().filePath(createdFile + QStringLiteral(".png")),
                       words(Text::YouQrFilter));
    dialog.setAcceptMode(QFileDialog::AcceptSave);
    dialog.setDefaultSuffix(QStringLiteral("png"));
    if (dialog.exec() != QDialog::Accepted) {
        return;
    }
    const QString path = dialog.selectedFiles().value(0);
    if (path.isEmpty()) {
        return;
    }
    if (Air::qrImage(qrCard->code(), qrPictureModule).save(path, "PNG")) {
        AirToast::post(this, words(Text::YouQrSaved));
    } else {
        AirDialog::notice(this, words(Text::YouQrSaveFailedTitle), words(Text::YouNotWritable));
    }
}

void LoginScreen::finishFirstLaunch(bool addFirstPerson)
{
    if (growing) {
        return;
    }
    emit readyToOpen(addFirstPerson);
}

// ---- Sign-in ----

void LoginScreen::showSignIn(const QString& chosen, bool replaceHistory)
{
    const QStringList names = profileNames();
    buildProfileRows(names);
    QString name = chosen;
    if (!names.contains(name)) {
        name = settings.getCurrentProfile();
    }
    if (!names.contains(name)) {
        name = names.value(0);
    }
    chooseProfile(name);
    if (replaceHistory) {
        restart(signInPage);
    } else {
        go(signInPage);
    }
}

void LoginScreen::buildProfileRows(const QStringList& names)
{
    for (AirRadioRow* const row : profileRows) {
        // Out of the group at once, so the rows that stay exclude only each other.
        row->setParent(nullptr);
        row->deleteLater();
    }
    profileRows.clear();
    for (const QString& name : names) {
        auto* const row = new AirRadioRow(profilesPlate);
        row->setObjectName(QStringLiteral("signInProfile"));
        row->setText(name);
        if (!Profile::isEncrypted(name, settings.getPaths())) {
            row->setNote(words(Text::SignInNoPasswordShort));
        }
        row->setContextMenuPolicy(Qt::CustomContextMenu);
        profilesList->addWidget(row);
        profileRows.append(row);
        connect(row, &AirRadioRow::toggled, this, [this, name](bool on) {
            if (on && name != chosenProfile) {
                chooseProfile(name);
            }
        });
        connect(row, &QWidget::customContextMenuRequested, this,
                [this, row, name](const QPoint& position) {
                    showProfileMenu(name, row->mapToGlobal(position));
                });
    }
}

void LoginScreen::chooseProfile(const QString& name)
{
    chosenProfile = name;
    chosenEncrypted = !name.isEmpty() && Profile::isEncrypted(name, settings.getPaths());
    signInField->clear();
    signInNote->clear();
    withoutProxyButton->hide();
    for (AirRadioRow* const row : profileRows) {
        if (row->text() == name) {
            row->setChecked(true);
        }
    }
    refreshSignIn();
    if (stack->currentPage() == signInPage && chosenEncrypted) {
        signInField->edit()->setFocus(Qt::OtherFocusReason);
    }
}

void LoginScreen::refreshSignIn()
{
    signInPage->title->setText(chosenProfile);
    // A profile without a password says what that means before it opens.
    signInPage->body->setText(words(Text::SignInNoPassword));
    signInPage->body->setVisible(!chosenEncrypted);
    signInField->setVisible(chosenEncrypted);
    signInField->setPlaceholder(words(Text::StartPasswordPlaceholder));
    // Without a field, the line under it is there only to say why the profile did not open.
    signInNote->setVisible(chosenEncrypted || signInNote->showsError());
    forgotButton->setVisible(chosenEncrypted);
    // A password is kept for logging in by itself only where the system has a store for it.
    signInAutoLoginRow->setVisible(!chosenEncrypted || CredentialStore::isSupported());
    signInAutoLoginNote->setText(
        words(chosenEncrypted ? Text::StartAutoLoginNote : Text::SignInAutoLoginPlainNote));
    signInAutoLogin->setChecked(settings.getAutoLogin());
    profilesBox->setVisible(profileRows.size() > 1);
    refreshMain(signInPage);
}

void LoginScreen::signIn(bool withoutProxy)
{
    const QString name = chosenProfile;
    if (name.isEmpty()) {
        return;
    }
    const QString password = chosenEncrypted ? signInField->text() : QString();
    signInNote->clear();
    signInField->setInvalid(false);
    withoutProxyButton->hide();
    setBusy(signInPage, true);
    // The button says what is going on before the key is derived from the password, a moment.
    signInPage->main->repaint();
    QTimer::singleShot(0, this, [this, name, password, withoutProxy] {
        emit loadProfile(name, password, withoutProxy);
    });
}

void LoginScreen::onProfileLoaded()
{
    // The page stays as it is, its button saying the profile opens, while the window grows.
    movingIn.clear();
    signInField->clear();
    importField->clear();
    emit readyToOpen(false);
}

void LoginScreen::onProfileLoadFailed(const Air::OpenFailure& failure)
{
    Page* const page = busyPage;
    if (page) {
        setBusy(page, false);
    }
    if (!movingIn.isEmpty()) {
        // The copy goes again: the file it came from is where it was, and nothing half done stays.
        const QString name = movingIn;
        movingIn.clear();
        Profile::removeFiles(name, settings.getPaths());
        AirDialog::notice(this, words(Text::ImportFailedTitle),
                          AirStrings::openFailureText(failure.kind), failure.code);
        return;
    }
    if (page == passwordPage) {
        createNote->showError(words(Text::StartCreateFailed), failure.code);
        return;
    }
    showOpenFailure(failure, true);
}

void LoginScreen::showFailure(const QString& profileName, const Air::OpenFailure& failure)
{
    if (!failure.failed() || !profileNames().contains(profileName)) {
        return;
    }
    if (profileName != chosenProfile) {
        chooseProfile(profileName);
    }
    if (failure.kind == Air::OpenFailure::Kind::WrongPassword) {
        // The password kept for logging in by itself did not open it; nobody typed one here.
        signInNote->show();
        signInNote->showError(words(Text::AutoLoginFailed));
        return;
    }
    showOpenFailure(failure, false);
}

void LoginScreen::showOpenFailure(const Air::OpenFailure& failure, bool shake)
{
    using Kind = Air::OpenFailure::Kind;
    withoutProxyButton->setVisible(failure.kind == Kind::BadProxy);
    signInNote->show();
    if (failure.kind == Kind::WrongPassword) {
        signInNote->showError(AirStrings::openFailureText(failure.kind));
        signInField->setInvalid(true);
        if (shake) {
            signInField->shake();
        }
        signInField->edit()->setFocus(Qt::OtherFocusReason);
        signInField->edit()->selectAll();
    } else {
        // What support needs stays one click away, under Details.
        signInNote->showError(AirStrings::openFailureText(failure.kind), failure.code);
    }
    refreshMain(signInPage);
}

void LoginScreen::onAutoLoginChanged(bool state)
{
    signInAutoLogin->setChecked(state);
}

void LoginScreen::askForgot()
{
    const QString name = chosenProfile;
    AirDialog* const dialog =
        AirDialog::confirm(this, words(Text::SignInForgotTitle),
                           words(Text::SignInForgotMessage).arg(name + Core::TOX_EXT),
                           words(Text::SignInCreate), AirDialog::Verb::Ink,
                           [this] { startFirstLaunch(false); });
    if (dialog) {
        dialog->addChoice(words(Text::SignInDelete), [this, name] { askDelete(name); });
    }
}

void LoginScreen::askDelete(const QString& name)
{
    AirDialog::confirm(this, words(Text::YouDeleteTitle), words(Text::YouDeleteMessage).arg(name),
                       words(Text::Delete), AirDialog::Verb::Danger,
                       [this, name] { deleteProfile(name); });
}

void LoginScreen::deleteProfile(const QString& name)
{
    const QStringList left = Profile::removeFiles(name, settings.getPaths());
    // A password kept for logging in by itself goes with the profile.
    if (CredentialStore::isSupported()) {
        CredentialStore::remove(name);
    }
    const QStringList names = profileNames();
    if (names.isEmpty()) {
        startFirstLaunch(true);
    } else {
        showSignIn(names.contains(chosenProfile) ? chosenProfile : names.first(), true);
    }
    if (!left.isEmpty()) {
        AirDialog::notice(this, words(Text::YouDeleteFailedTitle),
                          words(Text::YouDeleteLeftFiles).arg(left.join(QStringLiteral(", "))));
    }
}

void LoginScreen::showProfileMenu(const QString& name, const QPoint& globalPos)
{
    if (growing || busyPage) {
        return;
    }
    auto* const menu = new QMenu(this);
    menu->setAttribute(Qt::WA_DeleteOnClose);
    menu->setObjectName(QStringLiteral("signInProfileMenu"));
    QAction* const remove = menu->addAction(words(Text::Delete));
    connect(remove, &QAction::triggered, this, [this, name] { askDelete(name); });
    menu->popup(globalPos);
}

void LoginScreen::tellLeftovers(const QStringList& files)
{
    if (files.isEmpty()) {
        return;
    }
    // Once the window is up: the question is anchored to it.
    QTimer::singleShot(0, this, [this, files] {
        AirDialog::notice(this, words(Text::YouDeleteFailedTitle),
                          words(Text::YouDeleteLeftFiles).arg(files.join(QStringLiteral(", "))));
    });
}

// ---- Moving a profile in ----

void LoginScreen::chooseFile()
{
    const QPointer<LoginScreen> self(this);
    const QString path = QFileDialog::getOpenFileName(this, words(Text::ImportTitle),
                                                      QDir::homePath(), words(Text::ImportFilter));
    // The dialog runs its own loop.
    if (self && !path.isEmpty()) {
        moveIn(path);
    }
}

void LoginScreen::moveIn(const QString& path)
{
    if (growing || busyPage) {
        return;
    }
    importPath = path;
    importPassword.clear();
    importField->clear();
    importNote->clear();
    importContents = ProfileFile::readFile(path, QString());
    using Problem = ProfileFile::Contents::Problem;
    Page* next = problemPage;
    if (importContents.problem == Problem::NeedsPassword) {
        next = importPasswordPage;
    } else if (importContents.problem == Problem::None) {
        next = previewPage;
    }
    importPasswordPage->body->setText(words(Text::ImportPasswordBody).arg(QFileInfo(path).fileName()));
    if (next == previewPage) {
        showPreview(importContents);
    } else if (next == problemPage) {
        showImportProblem(importContents);
    }
    // Another file replaces the pages of the one before: Back leaves moving in at once.
    while (history.size() > 1
           && (history.last() == importPasswordPage || history.last() == previewPage
               || history.last() == problemPage)) {
        leave(history.takeLast());
    }
    go(next);
}

void LoginScreen::readImport()
{
    const QString password = importField->text();
    setBusy(importPasswordPage, true);
    importPasswordPage->main->repaint();
    const ProfileFile::Contents contents = ProfileFile::readFile(importPath, password);
    setBusy(importPasswordPage, false);
    using Problem = ProfileFile::Contents::Problem;
    if (contents.problem == Problem::WrongPassword) {
        importNote->showError(AirStrings::importProblemText(contents.problem));
        importField->setInvalid(true);
        importField->shake();
        importField->edit()->setFocus(Qt::OtherFocusReason);
        importField->edit()->selectAll();
        return;
    }
    importContents = contents;
    if (contents.problem == Problem::None) {
        importPassword = password;
        showPreview(contents);
        go(previewPage);
        return;
    }
    showImportProblem(contents);
    go(problemPage);
}

void LoginScreen::showPreview(const ProfileFile::Contents& contents)
{
    importContents = contents;
    previewName->setText(contents.name.isEmpty() ? words(Text::ImportNoName) : contents.name);
    previewId->setId(contents.id);
    previewId->reveal(false);
}

void LoginScreen::showImportProblem(const ProfileFile::Contents& contents)
{
    importContents = contents;
    problemPage->body->setText(AirStrings::importProblemText(contents.problem));
}

void LoginScreen::confirmMoveIn()
{
    // The profile takes the name of its file, as on the device it came from; another profile of
    // that name stays as it is, and this one is numbered.
    const QString fallback =
        importContents.name.isEmpty() ? words(Text::StartDefaultFolder) : importContents.name;
    const QString name =
        Air::profileFileName(QFileInfo(importPath).completeBaseName(), takenNames(), fallback);
    const QString folder = settings.getPaths().getSettingsDirPath();
    const QString target = folder + name + Core::TOX_EXT;
    if (!QDir().mkpath(folder) || !QFile::copy(importPath, target)) {
        qWarning() << "Could not copy the profile file into the profiles folder";
        AirDialog::notice(this, words(Text::ImportFailedTitle), words(Text::ImportCopyFailed));
        return;
    }
    movingIn = name;
    setBusy(previewPage, true);
    previewPage->main->repaint();
    const QString password = importPassword;
    // KHANDAQ (deep review 2026-08-23, RR3-09): a password goes into the system credential store
    // only when the person asked for it. Logging in by itself follows the profile that opens last,
    // and nobody was asked about this one: its sign-in has the switch.
    if (!password.isEmpty() && settings.getAutoLogin()) {
        emit autoLoginChanged(false);
    }
    QTimer::singleShot(0, this, [this, name, password] { emit loadProfile(name, password, false); });
}

// ---- The window ----

void LoginScreen::growInto(const QRect& frame, const std::function<void()>& then)
{
    if (growing) {
        return;
    }
    growing = true;
    for (Page* const page : pages) {
        page->back->setVisible(false);
    }
    const QRect from = geometry();
    if (!isVisible() || air().reducedMotion() || !frame.isValid() || from == frame) {
        then();
        return;
    }
    // The words go first; then the ground grows into the place of the main window.
    auto* const fade = new QGraphicsOpacityEffect(content);
    content->setGraphicsEffect(fade);
    const QPointer<QGraphicsOpacityEffect> effect(fade);
    const int fadeTime = air().duration(Motion::tap);
    const int growTime = air().duration(Motion::reveal);
    const QEasingCurve curve = air().easeOut();
    setMinimumSize(0, 0);
    setMaximumSize(QWIDGETSIZE_MAX, QWIDGETSIZE_MAX);
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(0);
    animation->setEndValue(fadeTime + growTime);
    animation->setDuration(fadeTime + growTime);
    connect(animation, &QVariantAnimation::valueChanged, this,
            [this, effect, from, frame, fadeTime, growTime, curve](const QVariant& value) {
                const int time = value.toInt();
                if (time < fadeTime) {
                    if (effect) {
                        effect->setOpacity(1.0 - static_cast<qreal>(time) / fadeTime);
                    }
                    return;
                }
                if (content->isVisible()) {
                    content->hide();
                }
                const qreal progress =
                    curve.valueForProgress(qMin(1.0, static_cast<qreal>(time - fadeTime) / growTime));
                setGeometry(QRect(QPoint(between(from.left(), frame.left(), progress),
                                         between(from.top(), frame.top(), progress)),
                                  QSize(between(from.width(), frame.width(), progress),
                                        between(from.height(), frame.height(), progress))));
            });
    connect(animation, &QVariantAnimation::finished, this, [this, frame, then] {
        setGeometry(frame);
        then();
    });
    growth = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

bool LoginScreen::event(QEvent* event)
{
#ifdef Q_OS_MAC
    if (event->type() == QEvent::WindowActivate || event->type() == QEvent::WindowStateChange) {
        emit windowStateChanged(windowState());
    }
#endif
    return QDialog::event(event);
}

void LoginScreen::closeEvent(QCloseEvent* event)
{
    if (growing) {
        event->ignore();
        return;
    }
    if (stack->currentPage() == idPage) {
        // The profile is made: closing the last step opens it, without New contact.
        event->ignore();
        finishFirstLaunch(false);
        return;
    }
    QDialog::closeEvent(event);
}

void LoginScreen::keyPressEvent(QKeyEvent* event)
{
    // Escape goes a page back and never ends the app, as closing the window would.
    if (event->key() == Qt::Key_Escape || event->matches(QKeySequence::Cancel)) {
        event->accept();
        if (growing) {
            return;
        }
        if (!history.isEmpty() && history.last() == idPage) {
            finishFirstLaunch(false);
            return;
        }
        goBack();
        return;
    }
    // Enter presses the button with the focus. A field says Enter itself, and lets the key go on
    // to the window, so the window takes it only from a button.
    if ((event->key() == Qt::Key_Return || event->key() == Qt::Key_Enter) && !growing) {
        auto* const button = qobject_cast<QAbstractButton*>(QApplication::focusWidget());
        if (button && isAncestorOf(button) && button->isEnabled()) {
            event->accept();
            button->click();
            return;
        }
    }
    QDialog::keyPressEvent(event);
}

void LoginScreen::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.fillRect(rect(), air().color(Color::Bg));
}

void LoginScreen::restyle()
{
    for (QLabel* const label : findChildren<QLabel*>()) {
        const QVariant role = label->property(roleProperty);
        if (role.isValid()) {
            label->setFont(air().font(static_cast<TextRole>(role.toInt())));
            AirPaint::textColor(label, air().color(static_cast<Color>(
                                           label->property(colorProperty).toInt())));
        }
    }
    // A page longer than the window scrolls under a thin bar on the ground, as the main window's do.
    const QColor handle = air().color(Color::LineStrong);
    const QString scrollBar =
        QStringLiteral(
            "QScrollBar:vertical { background: transparent; width: %1px; margin: %2px 0; }"
            "QScrollBar::handle:vertical { background: rgba(%3, %4, %5, %6);"
            " border-radius: %7px; min-height: %8px; }"
            "QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical"
            " { height: 0; border: none; }"
            "QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical"
            " { background: none; }")
            .arg(Size::bar)
            .arg(Space::s4)
            .arg(handle.red())
            .arg(handle.green())
            .arg(handle.blue())
            .arg(handle.alpha())
            .arg(Size::bar / 2)
            .arg(Size::chip);
    for (Page* const page : pages) {
        page->scroller->setStyleSheet(QStringLiteral("QScrollArea { background: transparent; }"));
        page->scroller->verticalScrollBar()->setStyleSheet(scrollBar);
        page->scroller->viewport()->setAutoFillBackground(false);
        page->content->setAutoFillBackground(false);
        page->update();
    }
    update();
}

void LoginScreen::retranslateUi()
{
    const qreal backTurn = layoutDirection() == Qt::RightToLeft ? -90 : 90;
    for (Page* const page : pages) {
        page->back->setRotation(backTurn);
        page->back->setToolTip(words(Text::StartBack));
        page->back->setAccessibleName(words(Text::StartBack));
    }

    namePage->title->setText(words(Text::StartNameTitle));
    namePage->body->setText(words(Text::StartNameBody));
    namePage->mainText = words(Text::StartNext);
    nameField->setPlaceholder(words(Text::StartNamePlaceholder));
    nameField->edit()->setAccessibleName(words(Text::StartNamePlaceholder));
    nameMoveButton->setText(words(Text::StartMoveProfile));

    passwordPage->title->setText(words(Text::StartPasswordTitle));
    passwordPage->body->setText(words(Text::StartPasswordBody));
    passwordPage->mainText = words(Text::StartCreate);
    passwordPage->busyText = words(Text::StartCreating);
    setRow->setText(words(Text::StartPasswordSet));
    setRow->setNote(words(Text::StartPasswordSetDetail));
    skipRow->setText(words(Text::StartPasswordSkip));
    skipRow->setNote(words(Text::StartPasswordSkipDetail));
    passField->setPlaceholder(words(Text::StartPasswordPlaceholder));
    repeatField->setPlaceholder(words(Text::StartRepeatPlaceholder));
    passNote->setHint(words(Text::StartPasswordRule));
    createAutoLoginLabel->setText(words(Text::StartAutoLogin));
    createAutoLoginNote->setText(words(Text::StartAutoLoginNote));
    createAutoLogin->setAccessibleName(words(Text::StartAutoLogin));
    createAutoLogin->setAccessibleDescription(words(Text::StartAutoLoginNote));

    idPage->title->setText(words(Text::StartIdTitle));
    idPage->body->setText(words(Text::StartIdBody));
    idPage->mainText = words(Text::StartAddPerson);
    shareButton->setText(words(Text::StartShare));
    qrCard->setAccessibleName(words(Text::YouQr));
    for (AirIdParts* const parts : {idParts, previewId}) {
        parts->setTitles(words(Text::YouPartKey), words(Text::YouPartAntispam),
                         words(Text::YouPartCheck));
    }

    signInPage->mainText = words(Text::SignInGo);
    signInPage->busyText = words(Text::SignInOpening);
    forgotButton->setText(words(Text::SignInForgot));
    withoutProxyButton->setText(words(Text::SignInWithoutProxy));
    signInAutoLoginLabel->setText(words(Text::StartAutoLogin));
    signInAutoLogin->setAccessibleName(words(Text::StartAutoLogin));
    profilesTitle->setText(words(Text::SignInProfiles));
    profilesNote->setText(words(Text::SignInProfilesNote));
    for (AirRadioRow* const row : profileRows) {
        if (!Profile::isEncrypted(row->text(), settings.getPaths())) {
            row->setNote(words(Text::SignInNoPasswordShort));
        }
    }
    signInCreateButton->setText(words(Text::SignInCreate));
    signInMoveButton->setText(words(Text::StartMoveProfile));
    refreshSignIn();

    importPasswordPage->title->setText(words(Text::ImportTitle));
    importPasswordPage->body->setText(
        words(Text::ImportPasswordBody).arg(QFileInfo(importPath).fileName()));
    importPasswordPage->mainText = words(Text::StartNext);
    importPasswordPage->busyText = words(Text::ImportReading);
    importField->setPlaceholder(words(Text::StartPasswordPlaceholder));

    previewPage->title->setText(words(Text::ImportPreviewTitle));
    previewPage->body->setText(words(Text::ImportPreviewBody));
    previewPage->mainText = words(Text::ImportConfirm);
    previewPage->busyText = words(Text::ImportMoving);
    previewNameTitle->setText(words(Text::ImportName));
    previewName->setText(importContents.name.isEmpty() ? words(Text::ImportNoName)
                                                       : importContents.name);
    otherFileButton->setText(words(Text::ImportOtherFile));

    problemPage->title->setText(words(Text::ImportTitle));
    problemPage->body->setText(AirStrings::importProblemText(importContents.problem));
    problemPage->mainText = words(Text::ImportOtherFile);

    for (Page* const page : pages) {
        refreshMain(page);
    }
}

#ifdef KHANDAQ_SCREENSHOT_HOOK
/**
 * Test builds only (cmake -DKHANDAQ_SCREENSHOT_HOOK=ON). KHANDAQ_START_SCRIPT names a script of the
 * start window, one command a line (runStartScript). Without it, KHANDAQ_LOGIN_SHOT saves the
 * window as a PNG once it shows, then KHANDAQ_LOGIN_ACTION goes on: "load" logs in to the chosen
 * profile, "create:<name>" makes a profile of that name without a password and opens it, and
 * anything else closes the window, which ends the app.
 */
void LoginScreen::playShotHook()
{
    const QString script = qEnvironmentVariable("KHANDAQ_START_SCRIPT");
    if (!script.isEmpty()) {
        QTimer::singleShot(0, this, [this, script] { runStartScript(script); });
        return;
    }
    const QString shot = qEnvironmentVariable("KHANDAQ_LOGIN_SHOT");
    if (shot.isEmpty()) {
        return;
    }
    const QString action = qEnvironmentVariable("KHANDAQ_LOGIN_ACTION");
    QTimer::singleShot(2000, this, [this, shot, action] {
        if (!grab().save(shot)) {
            qWarning() << "Login screenshot not saved:" << shot;
        }
        const QString createPrefix = QStringLiteral("create:");
        if (action == QStringLiteral("load")) {
            signIn(false);
        } else if (action.startsWith(createPrefix)) {
            hookOpensAtOnce = true;
            nameField->setText(action.mid(createPrefix.size()));
            typedName = nameField->text();
            skipRow->setChecked(true);
            create();
        } else {
            reject();
        }
    });
}

/**
 * The commands: wait <ms>, size <width> <height>, theme <system|light|dark>, lang <code>,
 * focus <object name>, type <text> (into the widget with the focus), key <key>, click <object
 * name>, choose <profile>, menu <profile> (its right-click menu), import <path>, grab <file.png>,
 * grabpopup <file.png> (the open menu), frames <count> <ms> <path prefix> (the window saved every
 * that many milliseconds while the script goes on, to catch a motion), audit <label>, idle <ms>
 * <label> (the walk's checks of AirWalk) and quit.
 */
void LoginScreen::runStartScript(const QString& path)
{
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) {
        qWarning() << "Start script not readable:" << path;
        return;
    }
    auto lines = std::make_shared<QStringList>(QString::fromUtf8(file.readAll()).split('\n'));
    const auto visibleChild = [this](const QString& name) -> QWidget* {
        for (QWidget* const candidate : findChildren<QWidget*>(name)) {
            if (candidate->isVisible()) {
                return candidate;
            }
        }
        return nullptr;
    };
    auto* const runner = new QTimer(this);
    runner->setSingleShot(true);
    connect(runner, &QTimer::timeout, this, [this, lines, runner, visibleChild] {
        int delay = 150;
        while (!lines->isEmpty()) {
            const QString line = lines->takeFirst().trimmed();
            if (line.isEmpty() || line.startsWith('#')) {
                continue;
            }
            const QString command = line.section(' ', 0, 0);
            const QString rest = line.section(' ', 1);
            qDebug() << "Start script:" << line;
            if (command == QStringLiteral("wait")) {
                delay = rest.toInt();
            } else if (command == QStringLiteral("size")) {
                setFixedSize(rest.section(' ', 0, 0).toInt(), rest.section(' ', 1, 1).toInt());
            } else if (command == QStringLiteral("theme")) {
                settings.setThemeMode(rest == QStringLiteral("dark")    ? Settings::ThemeMode::Dark
                                      : rest == QStringLiteral("light") ? Settings::ThemeMode::Light
                                                                        : Settings::ThemeMode::System);
                delay = 600;
            } else if (command == QStringLiteral("lang")) {
                settings.setTranslation(rest);
                Translator::translate(rest);
                delay = 600;
            } else if (command == QStringLiteral("focus")) {
                QWidget* target = visibleChild(rest);
                if (auto* const secret = qobject_cast<AirSecretField*>(target)) {
                    target = secret->edit();
                } else if (auto* const field = qobject_cast<AirTextField*>(target)) {
                    target = field->edit();
                }
                if (target) {
                    activateWindow();
                    target->setFocus(Qt::TabFocusReason);
                } else {
                    qWarning() << "Nothing to focus:" << rest;
                }
            } else if (command == QStringLiteral("type")) {
                QWidget* const target = QApplication::focusWidget();
                if (target) {
                    QKeyEvent press(QEvent::KeyPress, Qt::Key_unknown, Qt::NoModifier, rest);
                    QCoreApplication::sendEvent(target, &press);
                    QKeyEvent release(QEvent::KeyRelease, Qt::Key_unknown, Qt::NoModifier, rest);
                    QCoreApplication::sendEvent(target, &release);
                } else {
                    qWarning() << "Nothing has the focus to type into";
                }
            } else if (command == QStringLiteral("key")) {
                activateWindow();
                const QKeySequence sequence = QKeySequence::fromString(rest);
                QWidget* target = QApplication::activePopupWidget();
                if (!target) {
                    target = QApplication::focusWidget();
                }
                if (!target) {
                    target = this;
                }
                if (sequence.isEmpty()) {
                    qWarning() << "Unknown key:" << rest;
                } else {
                    const int code = sequence[0];
                    const auto key = static_cast<Qt::Key>(code & ~Qt::KeyboardModifierMask);
                    const auto modifiers =
                        static_cast<Qt::KeyboardModifiers>(code & Qt::KeyboardModifierMask);
                    QString text;
                    if (key == Qt::Key_Return || key == Qt::Key_Enter) {
                        text = QStringLiteral("\r");
                    } else if (key == Qt::Key_Escape) {
                        text = QString(QChar(0x1b));
                    } else if (key == Qt::Key_Tab) {
                        text = QStringLiteral("\t");
                    }
                    const QPointer<QWidget> receiver(target);
                    QKeyEvent press(QEvent::KeyPress, key, modifiers, text);
                    QCoreApplication::sendEvent(target, &press);
                    if (receiver) {
                        QKeyEvent release(QEvent::KeyRelease, key, modifiers, text);
                        QCoreApplication::sendEvent(receiver, &release);
                    }
                }
            } else if (command == QStringLiteral("click")) {
                auto* const button = qobject_cast<QAbstractButton*>(visibleChild(rest));
                if (button) {
                    // After this line: a menu or a file dialog the click opens runs its own loop.
                    QTimer::singleShot(0, button, &QAbstractButton::click);
                } else {
                    qWarning() << "No button to click:" << rest;
                }
            } else if (command == QStringLiteral("reveal")) {
                // reveal <object name>: scrolls the page so the widget shows, as Tab would.
                QWidget* const target = visibleChild(rest);
                QScrollArea* scroller = nullptr;
                for (QWidget* parent = target ? target->parentWidget() : nullptr;
                     parent && !scroller; parent = parent->parentWidget()) {
                    scroller = qobject_cast<QScrollArea*>(parent);
                }
                if (scroller) {
                    scroller->ensureWidgetVisible(target);
                } else {
                    qWarning() << "Nothing to reveal:" << rest;
                }
            } else if (command == QStringLiteral("choose")) {
                chooseProfile(rest);
            } else if (command == QStringLiteral("menu")) {
                for (AirRadioRow* const row : profileRows) {
                    if (row->text() == rest) {
                        showProfileMenu(rest, row->mapToGlobal(row->rect().center()));
                    }
                }
            } else if (command == QStringLiteral("import")) {
                moveIn(rest);
            } else if (command == QStringLiteral("grab")) {
                if (!grab().save(rest)) {
                    qWarning() << "Start screenshot not saved:" << rest;
                }
            } else if (command == QStringLiteral("frames")) {
                AirWalk::frames(this, rest.section(' ', 0, 0).toInt(),
                                rest.section(' ', 1, 1).toInt(), rest.section(' ', 2));
            } else if (command == QStringLiteral("audit")) {
                AirWalk::audit(this, rest);
            } else if (command == QStringLiteral("idle")) {
                AirWalk::idle(this, rest.section(' ', 0, 0).toInt(), rest.section(' ', 1));
            } else if (command == QStringLiteral("grabpopup")) {
                QWidget* const popup = QApplication::activePopupWidget();
                if (!popup || !popup->grab().save(rest)) {
                    qWarning() << "No menu to save:" << rest;
                }
            } else if (command == QStringLiteral("quit")) {
                done(QDialog::Rejected);
                return;
            } else {
                qWarning() << "Unknown start script command:" << line;
            }
            break;
        }
        if (!lines->isEmpty()) {
            runner->start(delay);
        }
    });
    runner->start(0);
}
#endif
