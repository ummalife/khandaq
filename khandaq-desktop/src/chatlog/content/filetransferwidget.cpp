/*
    Copyright © 2014-2019 by The qTox Project Contributors
    Copyright © 2026 by The Khandaq Project

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

#include "filetransferwidget.h"

#include "src/core/corefile.h"
#include "src/persistence/settings.h"
#include "src/widget/air/airmediaparts.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/style.h"
#include "src/widget/tool/imageviewerwidget.h"
#include "src/widget/tool/imessageboxmanager.h"
#include "src/widget/translator.h"

#include <QDebug>
#include <QDesktopServices>
#include <QDir>
#include <QFile>
#include <QFileDialog>
#include <QFileInfo>
#include <QFontMetricsF>
#include <QHBoxLayout>
#include <QImageReader>
#include <QLabel>
#include <QMouseEvent>
#include <QPainter>
#include <QPainterPath>
#include <QStyle>
#include <QUrl>
#include <QVBoxLayout>
#include <QtConcurrent/QtConcurrentRun>
#include <QtMath>

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

/**
 * The box of a photo, as on the phones (LoadingImageView.swift): 240 wide at most and 300 high at
 * most, in the photo's own shape. The design system has no token for it yet.
 */
constexpr int previewMaxWidth = 240;
constexpr int previewMaxHeight = 300;

/**
 * A picture a contact sent is read only when it is one of these formats and its header does not
 * claim a size that would take the process down (the guard of pixmapFromFile in
 * imagepreviewwidget.cpp, KHANDAQ security D-2): no SVG, at most 64 megapixels, 16384 a side.
 */
constexpr qint64 previewMaxPixels = 64LL * 1000 * 1000;
constexpr int previewMaxSide = 16384;

/** How often a moving transfer redraws its bar and its words. */
constexpr int progressEveryMs = 250;

bool previewFormat(const QString& path)
{
    static const QStringList formats = {QStringLiteral("png"), QStringLiteral("jpg"),
                                        QStringLiteral("jpeg"), QStringLiteral("gif")};
    return formats.contains(QFileInfo(path).suffix().toLower());
}

/** The size of the picture as it shows, upright; invalid when it must not be read. */
QSize uprightSize(QImageReader& reader)
{
    QSize size = reader.size();
    if (!size.isValid() || size.width() > previewMaxSide || size.height() > previewMaxSide
        || static_cast<qint64>(size.width()) * size.height() > previewMaxPixels) {
        return QSize();
    }
    if (reader.transformation() & QImageIOHandler::TransformationRotate90) {
        size.transpose();
    }
    return size;
}

/** The box a picture of that size takes: its own shape, never larger than the picture itself. */
QSize boxFor(const QSize& picture)
{
    if (!picture.isValid() || picture.isEmpty()) {
        return QSize();
    }
    if (picture.width() <= previewMaxWidth && picture.height() <= previewMaxHeight) {
        return picture;
    }
    return picture.scaled(previewMaxWidth, previewMaxHeight, Qt::KeepAspectRatio);
}

/** Reads the picture at path scaled to pixels (upright), off the main thread. */
QImage readPicture(const QString& path, const QSize& pixels)
{
    QImageReader reader(path);
    reader.setAutoTransform(true);
    const QSize upright = uprightSize(reader);
    if (!upright.isValid() || !pixels.isValid()) {
        return QImage();
    }
    // The reader scales before it turns the picture upright.
    const bool turned = reader.transformation() & QImageIOHandler::TransformationRotate90;
    reader.setScaledSize(turned ? pixels.transposed() : pixels);
    return reader.read();
}
} // namespace

namespace FileEntryParts {

/** The file name on one line, cut in the middle when it is long, in its own direction. */
class Name : public QWidget
{
public:
    explicit Name(QWidget* parent)
        : QWidget(parent)
    {
        setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Fixed);
        setFixedHeight(qCeil(air().lineHeight(TextRole::Label)));
        AirPaint::followTheme(this);
    }
    ~Name() override;

    void setText(const QString& text_)
    {
        text = text_;
        update();
    }

    QSize minimumSizeHint() const override
    {
        return QSize(Size::icon, height());
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        QFont font = air().font(TextRole::Label);
        font.setWeight(QFont::DemiBold);
        painter.setFont(font);
        painter.setPen(air().color(Color::Ink));
        const QString shown = QFontMetricsF(font).elidedText(text, Qt::ElideMiddle, width());
        const Qt::Alignment start =
            QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter);
        AirPaint::ownText(painter, rect(), static_cast<int>(start) | Qt::TextSingleLine, shown);
    }

private:
    QString text;
};

Name::~Name() = default;

/** The box of a photo: the sunken ground until the picture has come, then the picture. */
class Preview : public QWidget
{
public:
    explicit Preview(QWidget* parent)
        : QWidget(parent)
    {
        setCursor(Qt::PointingHandCursor);
        AirPaint::followTheme(this);
    }
    ~Preview() override;

    void setPicture(const QImage& image)
    {
        picture = image;
        update();
    }

    std::function<void()> onClick;

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        painter.setRenderHint(QPainter::SmoothPixmapTransform);
        QPainterPath box;
        box.addRoundedRect(QRectF(rect()), Radius::card, Radius::card);
        painter.setClipPath(box);
        painter.fillRect(rect(), air().color(Color::Sunken));
        if (!picture.isNull()) {
            painter.drawImage(QRectF(rect()), picture);
        }
    }

    void mouseReleaseEvent(QMouseEvent* event) override
    {
        if (event->button() == Qt::LeftButton && rect().contains(event->pos()) && onClick) {
            onClick();
            return;
        }
        QWidget::mouseReleaseEvent(event);
    }

private:
    QImage picture;
};

Preview::~Preview() = default;

} // namespace FileEntryParts

using FileEntryParts::Name;
using FileEntryParts::Preview;

FileTransferWidget::FileTransferWidget(QWidget* parent, CoreFile& _coreFile, ToxFile file,
                                       Settings& settings_, Style& style_,
                                       IMessageBoxManager& messageBoxManager_)
    : QWidget(parent)
    , coreFile{_coreFile}
    , fileInfo(file)
    , settings(settings_)
    , style{style_}
    , messageBoxManager{messageBoxManager_}
{
    // The entry lies on the ground of the transcript.
    setAttribute(Qt::WA_TranslucentBackground, true);
    setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Preferred);

    auto* const column = new QVBoxLayout(this);
    column->setContentsMargins(0, 0, 0, 0);
    column->setSpacing(Space::s6);

    preview = new Preview(this);
    preview->onClick = [this] { open(); };
    preview->hide();
    // At the start edge of the entry: the layout turns Leading into the right edge in Arabic.
    column->addWidget(preview, 0, Qt::AlignLeading);

    voice = new AirVoiceNote(this);
    voice->hide();
    connect(voice, &AirVoiceNote::listenRequested, this, [this] { acceptVoiceNote(true); });
    column->addWidget(voice);

    // The glyph, the name and the size on one line.
    head = new QWidget(this);
    auto* const headLayout = new QHBoxLayout(head);
    headLayout->setContentsMargins(0, 0, 0, 0);
    headLayout->setSpacing(Space::s10);
    icon = new QLabel(head);
    icon->setFixedSize(Size::icon, Size::icon);
    name = new Name(head);
    size = new QLabel(head);
    size->setTextFormat(Qt::PlainText);
    size->setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    headLayout->addWidget(icon, 0, Qt::AlignVCenter);
    headLayout->addWidget(name, 1, Qt::AlignVCenter);
    headLayout->addWidget(size, 0, Qt::AlignVCenter);
    column->addWidget(head);

    bar = new AirProgressBar(this);
    bar->hide();
    column->addWidget(bar);

    note = new QLabel(this);
    note->setTextFormat(Qt::PlainText);
    note->setWordWrap(true);
    note->hide();
    column->addWidget(note);

    actionsRow = new QWidget(this);
    actionsLayout = new QHBoxLayout(actionsRow);
    actionsLayout->setContentsMargins(0, 0, 0, 0);
    actionsLayout->setSpacing(Space::s16);
    actionsLayout->addStretch(1);
    actionsRow->hide();
    column->addWidget(actionsRow);

    connect(&previewLoad, &QFutureWatcher<QImage>::finished, this,
            &FileTransferWidget::onPreviewLoaded);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &FileTransferWidget::restyle);
    Translator::registerHandler(std::bind(&FileTransferWidget::retranslate, this), this);

    // restyle() shows the state too.
    restyle();
}

FileTransferWidget::~FileTransferWidget()
{
    Translator::unregister(this);
    previewLoad.waitForFinished();
}

bool FileTransferWidget::isActive() const
{
    return active;
}

void FileTransferWidget::onFileTransferUpdate(ToxFile file)
{
    const bool sameState = file.status == fileInfo.status
                           && file.pauseStatus.localPaused() == fileInfo.pauseStatus.localPaused()
                           && file.pauseStatus.remotePaused() == fileInfo.pauseStatus.remotePaused()
                           && file.filePath == fileInfo.filePath;
    fileInfo = file;
    // While it moves, the bar and the words go on a few times a second, not at every packet.
    if (sameState && file.status == ToxFile::TRANSMITTING && lastProgress.isValid()
        && lastProgress.elapsed() < progressEveryMs) {
        return;
    }
    lastProgress.start();
    refresh();
}

void FileTransferWidget::setConversation(bool group_, const QString& personName_, bool personOnline_)
{
    if (group == group_ && personName == personName_ && personOnline == personOnline_) {
        return;
    }
    group = group_;
    personName = personName_;
    personOnline = personOnline_;
    refresh();
}

Air::EntryStatus FileTransferWidget::status() const
{
    if (group || fileInfo.direction != ToxFile::SENDING) {
        return Air::EntryStatus::None;
    }
    return Air::fileStatus(fileInfo.status, personOnline);
}

bool FileTransferWidget::hasHeightForWidth() const
{
    return true;
}

int FileTransferWidget::heightForWidth(int width) const
{
    const int height = layout()->heightForWidth(width);
    return height >= 0 ? height : layout()->sizeHint().height();
}

QSize FileTransferWidget::sizeHint() const
{
    const QSize hint = layout()->sizeHint();
    return QSize(hint.width(), heightForWidth(qMax(hint.width(), width())));
}

Air::FileFacts FileTransferWidget::facts() const
{
    Air::FileFacts result;
    result.status = fileInfo.status;
    result.incoming = fileInfo.direction == ToxFile::RECEIVING;
    result.pausedHere = fileInfo.pauseStatus.localPaused();
    result.pausedThere = fileInfo.pauseStatus.remotePaused();
    result.size = static_cast<qint64>(fileInfo.progress.getFileSize());
    result.progress = fileInfo.progress.getProgress();
    result.group = group;
    result.voiceNote = Air::isVoiceNote(fileInfo.fileName);
    const bool onDisk = !fileInfo.filePath.isEmpty() && QFileInfo(fileInfo.filePath).isFile();
    result.here = fileInfo.status == ToxFile::FINISHED && onDisk;
    result.canSendAgain = !result.incoming && !group && !resent && personOnline && onDisk;
    return result;
}

QString FileTransferWidget::sizeText() const
{
    return AirStrings::byteSizeText(static_cast<qint64>(fileInfo.progress.getFileSize()),
                                    Translator::locale());
}

void FileTransferWidget::restyle()
{
    icon->setPixmap(air().icon(QStringLiteral("file"), air().color(Color::Ink2), Size::icon,
                               devicePixelRatioF()));
    size->setFont(air().font(TextRole::Caption));
    AirPaint::textColor(size, air().color(Color::Ink3));
    note->setFont(air().font(TextRole::Caption));
    refresh();
}

void FileTransferWidget::retranslate()
{
    // The actions, the note and the sizes are words of the interface.
    shownActions.clear();
    refresh();
}

void FileTransferWidget::refresh()
{
    const Air::FileFacts now = facts();
    const Air::FileEntryState state = Air::fileEntryState(now);
    active = fileInfo.status != ToxFile::FINISHED && fileInfo.status != ToxFile::CANCELED
             && fileInfo.status != ToxFile::BROKEN;

    // A photo that is here shows itself; a voice note is a player; any other file is its line.
    const bool picture = !now.voiceNote && !fileInfo.filePath.isEmpty()
                         && (now.here || !now.incoming) && previewFormat(fileInfo.filePath);
    if (picture && previewPath != fileInfo.filePath) {
        previewPath = fileInfo.filePath;
        loadPreview();
    } else if (!picture && !previewPath.isEmpty()) {
        previewPath.clear();
        preview->hide();
    }

    voice->setVisible(now.voiceNote);
    if (now.voiceNote) {
        if (now.here && voicePath != fileInfo.filePath) {
            voicePath = fileInfo.filePath;
            QFile file(voicePath);
            voiceLengthMs = file.open(QIODevice::ReadOnly) ? Air::mp4DurationMs(file) : -1;
        }
        // A note of a person that waits for Accept: Listen fetches it (acceptVoiceNote).
        const bool fetchable = now.incoming && !now.group && fileInfo.status == ToxFile::INITIALIZING;
        voice->setNote(fileInfo.filePath, voiceLengthMs, now.here, sizeText(), fetchable);
        if (playWhenHere && now.here) {
            playWhenHere = false;
            AirVoicePlayer& player = AirVoicePlayer::instance();
            if (!player.isPlaying(fileInfo.filePath)) {
                player.toggle(fileInfo.filePath);
            }
        } else if (fileInfo.status == ToxFile::CANCELED || fileInfo.status == ToxFile::BROKEN) {
            playWhenHere = false;
        }
    }

    // Under a picture or a voice note the line is the transfer alone.
    const bool showsHead = !now.voiceNote && preview->isHidden();
    head->setVisible(showsHead);
    name->setText(now.voiceNote ? AirStrings::text(Text::VoiceNote) : fileInfo.fileName);
    size->setText(sizeText());

    bar->setVisible(state.progress >= 0);
    bar->setProgress(qMax(0.0, state.progress));

    const QString words = AirStrings::fileNoteText(state, personName, Translator::locale());
    note->setText(words);
    note->setVisible(!words.isEmpty());
    AirPaint::textColor(note, air().color(state.isProblem() ? Color::Danger : Color::Ink3));

    setActions(state.actions);

    // What a screen reader says for the entry: the name and the size, then the state.
    QString spoken =
        now.voiceNote ? voice->spokenText()
                      : AirStrings::text(Text::FileNameAndSize).arg(fileInfo.fileName, sizeText());
    if (!words.isEmpty()) {
        spoken = AirStrings::text(Text::FileNameAndSize).arg(spoken, words);
    }
    setAccessibleName(spoken);

    tellHeight();
}

void FileTransferWidget::setActions(const QVector<Air::FileAction>& actions)
{
    if (actions == shownActions && actionButtons.size() == actions.size()) {
        actionsRow->setVisible(!actions.isEmpty());
        return;
    }
    for (AirButton* const button : actionButtons) {
        button->deleteLater();
    }
    actionButtons.clear();
    shownActions = actions;
    int at = 0;
    for (const Air::FileAction action : actions) {
        auto* const button = new AirButton(AirButton::Kind::Quiet, actionsRow);
        button->setText(AirStrings::fileActionText(action));
        // The action the entry waits for in ink, the way out in ink-2.
        button->setEmphasis(action == Air::FileAction::Accept || action == Air::FileAction::SendAgain
                            || action == Air::FileAction::Resume);
        button->setObjectName(QStringLiteral("fileAction%1").arg(static_cast<int>(action)));
        connect(button, &AirButton::clicked, this, [this, action] { act(action); });
        actionsLayout->insertWidget(at++, button, 0, Qt::AlignVCenter);
        actionButtons.append(button);
    }
    actionsRow->setVisible(!actions.isEmpty());
}

void FileTransferWidget::act(Air::FileAction action)
{
    const bool incoming = fileInfo.direction == ToxFile::RECEIVING;
    switch (action) {
    case Air::FileAction::Accept:
        accept();
        return;
    case Air::FileAction::Decline:
        coreFile.rejectFileRecvRequest(fileInfo.friendId, fileInfo.fileNum);
        return;
    case Air::FileAction::Pause:
    case Air::FileAction::Resume:
        coreFile.pauseResumeFile(fileInfo.friendId, fileInfo.fileNum);
        return;
    case Air::FileAction::Cancel:
        if (incoming) {
            coreFile.cancelFileRecv(fileInfo.friendId, fileInfo.fileNum);
        } else {
            coreFile.cancelFileSend(fileInfo.friendId, fileInfo.fileNum);
        }
        return;
    case Air::FileAction::SendAgain:
        sendAgain();
        return;
    case Air::FileAction::Open:
        open();
        return;
    case Air::FileAction::ShowInFolder:
        showInFolder();
        return;
    }
}

void FileTransferWidget::accept()
{
    if (Air::isVoiceNote(fileInfo.fileName)) {
        acceptVoiceNote(false);
        return;
    }
    acceptAsked();
}

void FileTransferWidget::acceptVoiceNote(bool play)
{
    // KHANDAQ (QA live 2026-11-14): a voice note is listened to, not kept as a file. It had to be
    // accepted and saved somewhere before Listen would play it. Accept and Listen now take it into
    // the app's own folder without asking where. It still comes only when one of them is pressed.
    const QDir appData(settings.getPaths().getAppDataDirPath());
    const QString folder = appData.filePath(QStringLiteral("voice"));
    QString path;
    if (appData.mkpath(QStringLiteral("voice"))) {
        path = Air::freeFilePath(folder, fileInfo.fileName,
                                 [](const QString& candidate) { return QFileInfo::exists(candidate); });
    }
    QFile probe(path);
    const bool writable = !path.isEmpty() && probe.open(QIODevice::WriteOnly);
    if (writable) {
        probe.remove();
    }
    if (!writable) {
        // The folder of the app cannot be written to: where the file goes is asked, as for any file.
        qWarning() << "Voice notes cannot be written to" << folder;
        acceptAsked();
        return;
    }
    playWhenHere = play;
    coreFile.acceptFileRecvRequest(fileInfo.friendId, fileInfo.fileNum, path);
}

void FileTransferWidget::acceptAsked()
{
    // Asked over the window, which it belongs to, rather than as a window of its own.
    const QString path = QFileDialog::getSaveFileName(window(), AirStrings::text(Text::FileSaveTitle),
                                                      settings.getGlobalAutoAcceptDir()
                                                          + QLatin1Char('/') + fileInfo.fileName);
    if (path.isEmpty()) {
        return;
    }
    // Whether the place can be written to; the file dialog asked before replacing a file there.
    QFile probe(path);
    const bool writable = probe.open(QIODevice::WriteOnly);
    probe.remove();
    if (!writable) {
        AirDialog::notice(this, AirStrings::text(Text::FileAcceptFailedTitle),
                          AirStrings::text(Text::FileNotWritable));
        return;
    }
    coreFile.acceptFileRecvRequest(fileInfo.friendId, fileInfo.fileNum, path);
}

void FileTransferWidget::sendAgain()
{
    const QFileInfo info(fileInfo.filePath);
    if (!info.isFile() || info.size() <= 0) {
        AirDialog::notice(this, AirStrings::text(Text::FileNotSentTitle),
                          AirStrings::text(Text::FileUnreadable).arg(fileInfo.fileName));
        return;
    }
    if (!personOnline) {
        AirDialog::notice(this, AirStrings::text(Text::FileNotSentTitle),
                          AirStrings::text(Text::FileOffline).arg(personName));
        return;
    }
    resent = true;
    coreFile.sendFile(fileInfo.friendId, fileInfo.fileName, fileInfo.filePath, info.size());
    refresh();
}

void FileTransferWidget::open()
{
    if (fileInfo.status != ToxFile::FINISHED && fileInfo.direction == ToxFile::RECEIVING) {
        return;
    }
    // Pictures open in the viewer of the app; anything else as the system opens it, an
    // executable only after a question.
    if (ImageViewerWidget::isImagePath(fileInfo.filePath)) {
        (new ImageViewerWidget(fileInfo.filePath))->show();
    } else {
        messageBoxManager.confirmExecutableOpen(QFileInfo(fileInfo.filePath));
    }
}

void FileTransferWidget::showInFolder()
{
    const QString folder = QFileInfo(fileInfo.filePath).dir().path();
    QDesktopServices::openUrl(QUrl::fromLocalFile(folder));
}

void FileTransferWidget::loadPreview()
{
    QImageReader reader(previewPath);
    const QSize box = boxFor(uprightSize(reader));
    if (!box.isValid()) {
        preview->hide();
        return;
    }
    // The box takes its size at once, so the line sizes itself once, before the picture comes.
    preview->setFixedSize(box);
    preview->setPicture(QImage());
    preview->show();
    const qreal ratio = qMax<qreal>(1.0, devicePixelRatioF());
    const QSize pixels(qRound(box.width() * ratio), qRound(box.height() * ratio));
    const QString path = previewPath;
    previewLoad.setFuture(QtConcurrent::run([path, pixels]() { return readPicture(path, pixels); }));
}

void FileTransferWidget::onPreviewLoaded()
{
    const QImage image = previewLoad.result();
    if (image.isNull()) {
        // Not a picture after all: the entry goes back to its line.
        preview->hide();
        refresh();
        return;
    }
    QImage shown = image;
    shown.setDevicePixelRatio(qMax<qreal>(1.0, devicePixelRatioF()));
    preview->setPicture(shown);
}

void FileTransferWidget::tellHeight()
{
    // The constructor gets here through restyle(), so the call is spelled out: the class is final,
    // and this is the function dynamic binding would pick at any other time as well.
    if (FileTransferWidget::heightForWidth(width()) != height()) {
        updateGeometry();
        emit heightChanged();
    }
}
