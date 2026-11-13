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

#include "airmediaparts.h"

#include "airoverlay.h"
#include "airstrings.h"
#include "airstyle.h"
#include "airwidgets.h"

#include "audio/iaudiocontrol.h"
#include "audio/iaudiosink.h"
#include "src/model/air/airmedia.h"
#include "src/widget/translator.h"

#include <QDebug>
#include <QDesktopServices>
#include <QHBoxLayout>
#include <QLabel>
#include <QMouseEvent>
#include <QPainter>
#include <QPainterPath>
#include <QUrl>
#include <QVBoxLayout>
#include <QtConcurrent/QtConcurrentRun>

#include <cmath>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;
namespace Stroke = AirTokens::Stroke;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

/** How often the time played moves on while a note plays. */
constexpr int tickMs = 200;

/** How far ahead of what is heard the output gets the sound. */
constexpr qint64 leadMs = 2000;

/** The bars of the wave: the same heights as on the phones, 0.25 to 0.95 of the wave's height. */
constexpr int waveBars = 44;

double barHeight(int index)
{
    const double a = std::sin(index * 1.3);
    const double b = std::sin(index * 0.7 + 1.1);
    return 0.25 + 0.7 * std::fabs(a * 0.6 + b * 0.4);
}

using DecodedPcm = std::shared_ptr<AirVoiceDecoder::Pcm>;
} // namespace

// ---- AirProgressBar ----

AirProgressBar::AirProgressBar(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    setFixedHeight(Size::bar);
    setAttribute(Qt::WA_TransparentForMouseEvents);
    AirPaint::followTheme(this);
}

void AirProgressBar::setProgress(double value)
{
    const double clamped = std::isfinite(value) ? qBound(0.0, value, 1.0) : 0.0;
    if (qFuzzyCompare(clamped + 1.0, share + 1.0)) {
        return;
    }
    share = clamped;
    update();
}

double AirProgressBar::progress() const
{
    return share;
}

QSize AirProgressBar::sizeHint() const
{
    return QSize(Size::controlSmall * 4, Size::bar);
}

void AirProgressBar::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setPen(Qt::NoPen);
    const QRectF track(rect());
    const qreal radius = track.height() / 2;
    painter.setBrush(air().color(Color::Sunken));
    painter.drawRoundedRect(track, radius, radius);
    if (share <= 0) {
        return;
    }
    // The part done grows from the start of the line: the left in English, the right in Arabic.
    const qreal done = qMax(track.height(), track.width() * share);
    const QRectF fill = layoutDirection() == Qt::RightToLeft
                            ? QRectF(track.right() - done, track.top(), done, track.height())
                            : QRectF(track.left(), track.top(), done, track.height());
    painter.setBrush(air().color(Color::Ink));
    painter.drawRoundedRect(fill, radius, radius);
}

// ---- AirVoiceWave ----

AirVoiceWave::AirVoiceWave(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    setFixedHeight(Size::controlSmall);
    setCursor(Qt::PointingHandCursor);
    AirPaint::followTheme(this);
}

void AirVoiceWave::setPlayed(double value)
{
    if (held) {
        return;
    }
    showShare(value);
}

double AirVoiceWave::played() const
{
    return share;
}

void AirVoiceWave::showShare(double value)
{
    const double clamped = std::isfinite(value) ? qBound(0.0, value, 1.0) : 0.0;
    if (qFuzzyCompare(clamped + 1.0, share + 1.0)) {
        return;
    }
    share = clamped;
    update();
}

QSize AirVoiceWave::sizeHint() const
{
    const int width = static_cast<int>(std::ceil(waveBars * (Stroke::strong + Space::s2)));
    return QSize(width, Size::controlSmall);
}

void AirVoiceWave::paintEvent(QPaintEvent*)
{
    if (width() <= 0 || height() <= 0) {
        return;
    }
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setPen(Qt::NoPen);
    const qreal spacing = Space::s2;
    const qreal barWidth =
        qMax<qreal>(Stroke::strong, (width() - spacing * (waveBars - 1)) / waveBars);
    const qreal step = barWidth + spacing;
    const int playedBars = static_cast<int>(std::lround(waveBars * share));
    const bool rightToLeft = layoutDirection() == Qt::RightToLeft;
    const QColor playedColor = air().color(isEnabled() ? Color::Ink : Color::Ink3);
    const QColor restColor = air().color(isEnabled() ? Color::LineStrong : Color::Line);
    for (int index = 0; index < waveBars; ++index) {
        const qreal barHeightPx = qMax<qreal>(Space::s2, barHeight(index) * height());
        const int slot = rightToLeft ? waveBars - 1 - index : index;
        const QRectF bar(slot * step, (height() - barHeightPx) / 2, barWidth, barHeightPx);
        painter.setBrush(index < playedBars ? playedColor : restColor);
        painter.drawRoundedRect(bar, barWidth / 2, barWidth / 2);
    }
}

double AirVoiceWave::shareAt(const QPoint& point) const
{
    if (width() <= 0) {
        return 0.0;
    }
    qreal x = point.x();
    // The note runs from the start edge: the right one in Arabic.
    if (layoutDirection() == Qt::RightToLeft) {
        x = width() - x;
    }
    return qBound(0.0, x / width(), 1.0);
}

void AirVoiceWave::mousePressEvent(QMouseEvent* event)
{
    if (event->button() != Qt::LeftButton || !isEnabled()) {
        QWidget::mousePressEvent(event);
        return;
    }
    held = true;
    showShare(shareAt(event->pos()));
}

void AirVoiceWave::mouseMoveEvent(QMouseEvent* event)
{
    if (!held) {
        QWidget::mouseMoveEvent(event);
        return;
    }
    showShare(shareAt(event->pos()));
}

void AirVoiceWave::mouseReleaseEvent(QMouseEvent* event)
{
    if (!held || event->button() != Qt::LeftButton) {
        QWidget::mouseReleaseEvent(event);
        return;
    }
    held = false;
    // The sound moves once, where the drag ends, rather than at every step of it.
    showShare(shareAt(event->pos()));
    emit seekRequested(share);
}

// ---- AirVoicePlayer ----

AirVoicePlayer& AirVoicePlayer::instance()
{
    static AirVoicePlayer player;
    return player;
}

AirVoicePlayer::AirVoicePlayer()
{
    ticker.setInterval(tickMs);
    connect(&ticker, &QTimer::timeout, this, &AirVoicePlayer::tick);
    connect(&reading, &QFutureWatcher<DecodedPcm>::finished, this, &AirVoicePlayer::onRead);
}

AirVoicePlayer::~AirVoicePlayer()
{
    reading.waitForFinished();
}

void AirVoicePlayer::setAudio(IAudioControl* audio_)
{
    if (audio == audio_) {
        return;
    }
    // The sink belongs to the output it came from: it goes before the output does.
    stop();
    sink.reset();
    audio = audio_;
}

void AirVoicePlayer::toggle(const QString& path)
{
    if (path == current && wanted) {
        // Pause where it stands.
        offsetMs = currentPositionMs();
        wanted = false;
        pendingShare = -1;
        silence();
        emit changed(path);
        return;
    }
    select(path);
    wanted = true;
    if (pcm) {
        playFrom(offsetMs);
    } else {
        read(path);
    }
    emit changed(path);
}

void AirVoicePlayer::seek(const QString& path, double share)
{
    select(path);
    const double clamped = std::isfinite(share) ? qBound(0.0, share, 1.0) : 0.0;
    wanted = true;
    const qint64 length = pcm ? pcm->lengthMs() : 0;
    if (length > 0) {
        playFrom(static_cast<qint64>(std::llround(clamped * static_cast<double>(length))));
    } else {
        // The share becomes a time once the note is read and its length known.
        pendingShare = clamped;
        read(path);
    }
    emit changed(path);
}

void AirVoicePlayer::stop()
{
    if (current.isEmpty()) {
        return;
    }
    wanted = false;
    offsetMs = 0;
    pendingShare = -1;
    silence();
    emit changed(current);
}

bool AirVoicePlayer::isPlaying(const QString& path) const
{
    return wanted && path == current;
}

qint64 AirVoicePlayer::positionMs(const QString& path) const
{
    return path == current ? currentPositionMs() : 0;
}

qint64 AirVoicePlayer::lengthMs(const QString& path) const
{
    return path == current && pcm ? pcm->lengthMs() : -1;
}

void AirVoicePlayer::select(const QString& path)
{
    if (path == current) {
        return;
    }
    // Another note: the one before goes back to its start.
    const QString before = current;
    silence();
    wanted = false;
    offsetMs = 0;
    pendingShare = -1;
    pcm.reset();
    current = path;
    if (!before.isEmpty()) {
        emit changed(before);
    }
}

void AirVoicePlayer::read(const QString& path)
{
    if (reading.isRunning() && readingPath == path) {
        return;
    }
    if (!audio || !AirVoiceDecoder::canDecode()) {
        handOver(path);
        return;
    }
    readingPath = path;
    reading.setFuture(QtConcurrent::run([path]() {
        auto decoded = std::make_shared<AirVoiceDecoder::Pcm>();
        if (!AirVoiceDecoder::decode(path, decoded.get())) {
            decoded.reset();
        }
        return decoded;
    }));
}

void AirVoicePlayer::onRead()
{
    const QString path = readingPath;
    const DecodedPcm decoded = reading.result();
    readingPath.clear();
    if (path != current) {
        // Another note was chosen while this one was read.
        return;
    }
    if (!decoded) {
        handOver(path);
        return;
    }
    pcm = decoded;
    if (pendingShare >= 0) {
        offsetMs =
            static_cast<qint64>(std::llround(pendingShare * static_cast<double>(pcm->lengthMs())));
        pendingShare = -1;
    }
    if (wanted) {
        playFrom(offsetMs);
    }
    emit changed(path);
}

void AirVoicePlayer::playFrom(qint64 ms)
{
    const qint64 length = pcm ? pcm->lengthMs() : 0;
    if (length <= 0) {
        return;
    }
    offsetMs = qBound<qint64>(0, ms, length);
    if (offsetMs >= length) {
        // From the end means from the start again.
        offsetMs = 0;
    }
    silence();
    sink = audio ? audio->makeSink() : nullptr;
    if (!sink) {
        handOver(current);
        return;
    }
    startSample = static_cast<size_t>(offsetMs * pcm->sampleRate / 1000);
    nextSample = startSample;
    clock.start();
    feed();
    ticker.start();
}

void AirVoicePlayer::feed()
{
    if (!sink || !pcm || pcm->sampleRate <= 0 || !clock.isValid()) {
        return;
    }
    // A little ahead of what is heard, in pieces of half a second: the output never runs dry and
    // never holds more than a few pieces, so a seek or a pause costs nothing.
    const size_t total = pcm->samples.size();
    const size_t rate = static_cast<size_t>(pcm->sampleRate);
    const size_t ahead = static_cast<size_t>(clock.elapsed() + leadMs) * rate / 1000;
    const size_t until = qMin(total, startSample + ahead);
    const size_t piece = rate / 2;
    while (nextSample < until) {
        const size_t count = qMin(piece, total - nextSample);
        sink->playAudioBuffer(pcm->samples.data() + nextSample, static_cast<int>(count), 1,
                              pcm->sampleRate);
        nextSample += count;
    }
}

void AirVoicePlayer::tick()
{
    if (!wanted || !pcm) {
        ticker.stop();
        return;
    }
    if (currentPositionMs() >= pcm->lengthMs()) {
        // The end: the note goes back to its start, ready to be heard again.
        wanted = false;
        offsetMs = 0;
        silence();
    } else {
        feed();
    }
    emit changed(current);
}

void AirVoicePlayer::silence()
{
    sink.reset();
    ticker.stop();
    clock.invalidate();
}

void AirVoicePlayer::handOver(const QString& path)
{
    // This build cannot play it here: the system's player takes the file.
    wanted = false;
    offsetMs = 0;
    pendingShare = -1;
    silence();
#ifdef KHANDAQ_SCREENSHOT_HOOK
    // The walk never hands a file to the system: its player would open on the screen of the
    // computer the walk runs on.
    qWarning() << "Voice note not played here:" << path;
    emit failed(path);
#else
    if (!QDesktopServices::openUrl(QUrl::fromLocalFile(path))) {
        emit failed(path);
    }
#endif
    emit changed(path);
}

qint64 AirVoicePlayer::currentPositionMs() const
{
    if (!wanted || !sink || !clock.isValid()) {
        return offsetMs;
    }
    const qint64 length = pcm ? pcm->lengthMs() : 0;
    return qMin(length, offsetMs + clock.elapsed());
}

// ---- AirVoiceNote ----

AirVoiceNote::AirVoiceNote(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    button = new AirButton(AirButton::Kind::Plain, this);
    button->setObjectName(QStringLiteral("voiceListen"));
    wave = new AirVoiceWave(this);
    time = new QLabel(this);
    time->setTextFormat(Qt::PlainText);
    time->setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Preferred);

    auto* const layout = new QHBoxLayout(this);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(Space::s10);
    layout->addWidget(button);
    layout->addWidget(wave, 1);
    layout->addWidget(time);

    connect(button, &AirButton::clicked, this, [this] {
        if (here) {
            AirVoicePlayer::instance().toggle(path);
        }
    });
    connect(wave, &AirVoiceWave::seekRequested, this, [this](double share) {
        if (here) {
            AirVoicePlayer::instance().seek(path, share);
        }
    });
    AirVoicePlayer& player = AirVoicePlayer::instance();
    connect(&player, &AirVoicePlayer::changed, this,
            static_cast<void (AirVoiceNote::*)(const QString&)>(&AirVoiceNote::refresh));
    connect(&player, &AirVoicePlayer::failed, this, &AirVoiceNote::showFailure);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirVoiceNote::restyle);
    Translator::registerHandler(std::bind(&AirVoiceNote::retranslate, this), this);
    restyle();
    retranslate();
}

AirVoiceNote::~AirVoiceNote()
{
    Translator::unregister(this);
}

void AirVoiceNote::setNote(const QString& path_, qint64 lengthMs, bool here_, const QString& sizeText_)
{
    path = path_;
    headerLengthMs = lengthMs;
    here = here_;
    sizeText = sizeText_;
    refresh();
}

QString AirVoiceNote::spokenText() const
{
    return AirStrings::text(Text::FileNameAndSize).arg(AirStrings::text(Text::VoiceNote), time->text());
}

void AirVoiceNote::restyle()
{
    time->setFont(air().font(TextRole::Caption));
    AirPaint::textColor(time, air().color(Color::Ink3));
    wave->update();
}

void AirVoiceNote::retranslate()
{
    // Listen and Pause take the same width, so the wave does not move when the words change.
    const QString listen = AirStrings::text(Text::VoiceListen);
    const QString pause = AirStrings::text(Text::VoicePause);
    button->setText(pause);
    const int pauseWidth = button->sizeHint().width();
    button->setText(listen);
    button->setFixedWidth(qMax(pauseWidth, button->sizeHint().width()));
    refresh();
}

void AirVoiceNote::refresh(const QString& changedPath)
{
    if (changedPath == path) {
        refresh();
    }
}

void AirVoiceNote::refresh()
{
    const AirVoicePlayer& player = AirVoicePlayer::instance();
    const bool playing = here && player.isPlaying(path);
    button->setText(AirStrings::text(playing ? Text::VoicePause : Text::VoiceListen));
    button->setEnabled(here);
    wave->setEnabled(here);

    qint64 length = player.lengthMs(path);
    if (length <= 0) {
        length = headerLengthMs;
    }
    const qint64 position = player.positionMs(path);
    wave->setPlayed(length > 0 ? static_cast<double>(position) / static_cast<double>(length) : 0.0);
    // At rest the length of the note, while it plays the time played so far. A note whose length
    // is not known yet, as one not here, says its size instead of "0:00".
    QString words;
    if (playing) {
        words = Air::clockText(position / 1000);
    } else if (length > 0 || sizeText.isEmpty()) {
        words = Air::clockText(qMax<qint64>(0, length) / 1000);
    } else {
        words = sizeText;
    }
    time->setText(words);
    button->setAccessibleDescription(words);
    setAccessibleName(spokenText());
}

void AirVoiceNote::showFailure(const QString& failedPath)
{
    if (failedPath != path || !isVisible()) {
        return;
    }
    AirDialog::notice(this, AirStrings::text(Text::VoiceFailedTitle), AirStrings::text(Text::TryAgain));
}

// ---- AirCallBand ----

AirCallBand::AirCallBand(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    glyph = new QLabel(this);
    glyph->setFixedSize(Size::icon, Size::icon);
    state = new QLabel(this);
    state->setTextFormat(Qt::PlainText);
    note = new QLabel(this);
    note->setTextFormat(Qt::PlainText);
    note->setWordWrap(true);

    auto* const words = new QVBoxLayout();
    words->setContentsMargins(0, 0, 0, 0);
    words->setSpacing(Space::s2);
    words->addWidget(state);
    words->addWidget(note);

    const auto makeButton = [this](AirButton::Kind kind, const char* name) {
        auto* const button = new AirButton(kind, this);
        button->setObjectName(QLatin1String(name));
        return button;
    };
    declineButton = makeButton(AirButton::Kind::Danger, "callDecline");
    videoButton = makeButton(AirButton::Kind::Plain, "callAnswerVideo");
    answerButton = makeButton(AirButton::Kind::Ink, "callAnswer");
    microphoneButton = makeButton(AirButton::Kind::Plain, "callMicrophone");
    soundButton = makeButton(AirButton::Kind::Plain, "callSound");
    endButton = makeButton(AirButton::Kind::Danger, "callEnd");
    cancelButton = makeButton(AirButton::Kind::Plain, "callWaitCancel");

    auto* const layout = new QHBoxLayout(this);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(Space::s8);
    layout->addWidget(glyph, 0, Qt::AlignVCenter);
    layout->addSpacing(Space::s2);
    layout->addLayout(words, 1);
    layout->addSpacing(Space::s8);
    for (AirButton* const button : {declineButton, videoButton, answerButton, microphoneButton,
                                    soundButton, endButton, cancelButton}) {
        layout->addWidget(button, 0, Qt::AlignVCenter);
    }

    connect(declineButton, &AirButton::clicked, this, &AirCallBand::declineRequested);
    connect(videoButton, &AirButton::clicked, this, [this] { emit answerRequested(true); });
    connect(answerButton, &AirButton::clicked, this, [this] { emit answerRequested(false); });
    connect(microphoneButton, &AirButton::clicked, this, &AirCallBand::microphoneToggled);
    connect(soundButton, &AirButton::clicked, this, &AirCallBand::soundToggled);
    connect(endButton, &AirButton::clicked, this, &AirCallBand::endRequested);
    connect(cancelButton, &AirButton::clicked, this, &AirCallBand::waitCancelled);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirCallBand::restyle);
    Translator::registerHandler(std::bind(&AirCallBand::retranslate, this), this);
    restyle();
    setPhase(Phase::None);
}

AirCallBand::~AirCallBand()
{
    Translator::unregister(this);
}

void AirCallBand::setPhase(Phase phase_, const QString& person_, bool video_)
{
    current = phase_;
    person = person_;
    video = video_;
    if (current != Phase::Active) {
        seconds = 0;
    }
    declineButton->setVisible(current == Phase::Incoming);
    videoButton->setVisible(current == Phase::Incoming && video);
    answerButton->setVisible(current == Phase::Incoming);
    microphoneButton->setVisible(current == Phase::Active);
    soundButton->setVisible(current == Phase::Active);
    endButton->setVisible(current == Phase::Outgoing || current == Phase::Active);
    cancelButton->setVisible(current == Phase::Waiting);
    note->setVisible(current == Phase::Waiting);
    setVisible(current != Phase::None);
    retranslate();
}

AirCallBand::Phase AirCallBand::phase() const
{
    return current;
}

void AirCallBand::setSeconds(qint64 seconds_)
{
    seconds = seconds_;
    if (current == Phase::Active) {
        retranslate();
    }
}

void AirCallBand::setMuted(bool microphone, bool sound)
{
    microphoneMuted = microphone;
    soundMuted = sound;
    retranslate();
}

void AirCallBand::restyle()
{
    glyph->setPixmap(air().icon(QStringLiteral("phone"), air().color(Color::Ink2), Size::icon,
                                devicePixelRatioF()));
    state->setFont(air().font(TextRole::Label));
    AirPaint::textColor(state, air().color(Color::Ink));
    note->setFont(air().font(TextRole::Caption));
    AirPaint::textColor(note, air().color(Color::Ink2));
}

void AirCallBand::retranslate()
{
    QString words;
    switch (current) {
    case Phase::None:
        break;
    case Phase::Incoming:
        words = AirStrings::text(Text::CallIncoming);
        break;
    case Phase::Outgoing:
        words = AirStrings::text(Text::CallCalling);
        break;
    case Phase::Active: {
        Air::CallEntry entry;
        entry.seconds = seconds;
        words = AirStrings::callEntryText(entry);
        break;
    }
    case Phase::Waiting:
        words = AirStrings::text(Text::CallWaiting).arg(person);
        break;
    }
    state->setText(words);
    note->setText(AirStrings::text(Text::CallWaitingNote));
    declineButton->setText(AirStrings::text(Text::CallDecline));
    videoButton->setText(AirStrings::text(Text::CallAnswerVideo));
    answerButton->setText(AirStrings::text(Text::CallAnswer));
    microphoneButton->setText(
        AirStrings::text(microphoneMuted ? Text::CallMicUnmute : Text::CallMicMute));
    soundButton->setText(AirStrings::text(soundMuted ? Text::CallSoundUnmute : Text::CallSoundMute));
    endButton->setText(AirStrings::text(Text::CallEnd));
    cancelButton->setText(AirStrings::text(Text::CallCancel));
    setAccessibleName(words);
    state->setAccessibleName(words);
}
