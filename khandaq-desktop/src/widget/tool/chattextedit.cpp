/*
    Copyright © 2014-2019 by The qTox Project Contributors

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

#include "chattextedit.h"

#include "src/persistence/smileypack.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/translator.h"

#include <QApplication>
#include <QClipboard>
#include <QFileInfo>
#include <QImage>
#include <QKeyEvent>
#include <QMimeData>
#include <QResizeEvent>
#include <QSyntaxHighlighter>
#include <QTextDocument>
#include <QUrl>
#include <QtGlobal>

namespace {

/**
 * @brief Sets the joined emoji sequences of the field in the colour emoji font of the system
 *
 * KHANDAQ (QA live 2026-11-14): Qt 5 took the members of a sequence that have a text glyph (♀, ⚕,
 * ☠, ❤) from the text font, and the field showed 👨‍⚕️ as 👨 and ⚕ and 🏴‍☠️ as a flag and a skull,
 * while the message showed one picture. Only the drawing changes: a highlighter formats the
 * layout, not the text, so what is sent and the typing notice stay as they were.
 */
class EmojiSequenceHighlighter final : public QSyntaxHighlighter
{
public:
    explicit EmojiSequenceHighlighter(QTextDocument* document)
        : QSyntaxHighlighter(document)
    {
        emojiFormat.setFontFamily(SmileyPack::emojiFontFamily());
    }

protected:
    void highlightBlock(const QString& text) override
    {
        for (const auto& range : SmileyPack::emojiFontRanges(text)) {
            setFormat(range.first, range.second - range.first, emojiFormat);
        }
    }

private:
    QTextCharFormat emojiFormat;
};

QString localPathFromUriOrPath(const QString& value)
{
    const QString trimmed = value.trimmed();
    if (trimmed.isEmpty()) {
        return {};
    }

    if (trimmed.startsWith(QStringLiteral("file://"), Qt::CaseInsensitive)) {
        const QString localPath = QUrl(trimmed).toLocalFile();
        if (!localPath.isEmpty()) {
            return localPath;
        }
    }

    if (trimmed.size() >= 3 && trimmed.at(1) == QLatin1Char(':')) {
        return trimmed;
    }

    if (trimmed.startsWith(QLatin1String("\\\\"))) {
        return trimmed;
    }

    return {};
}

QStringList existingLocalFilePaths(const QStringList& candidates)
{
    QStringList paths;
    for (const QString& candidate : candidates) {
        const QString localPath = localPathFromUriOrPath(candidate);
        if (localPath.isEmpty()) {
            continue;
        }

        const QFileInfo info(localPath);
        if (info.exists() && info.isFile()) {
            paths.append(info.absoluteFilePath());
        }
    }

    return paths;
}

QStringList localFilePathsFromMimeData(const QMimeData* mimeData)
{
    if (!mimeData) {
        return {};
    }

    QStringList candidates;
    if (mimeData->hasUrls()) {
        for (const QUrl& url : mimeData->urls()) {
            if (url.isLocalFile()) {
                candidates.append(url.toLocalFile());
            } else {
                candidates.append(url.toString());
            }
        }
    }

    if (mimeData->hasText()) {
        QString text = mimeData->text();
        text.replace(QLatin1Char('\r'), QLatin1Char('\n'));
        for (const QString& line : text.split(QLatin1Char('\n'), QString::SkipEmptyParts)) {
            candidates.append(line);
        }
    }

    return existingLocalFilePaths(candidates);
}

// kq-compose textarea: as high as "Send" for one line, growing up to the compose maximum, with
// 8 px above the text and the rest below it (the padding is set by the form's style sheet).
constexpr int MESSAGE_EDIT_MIN_HEIGHT = AirTokens::Size::control;
constexpr int MESSAGE_EDIT_MAX_HEIGHT = AirTokens::Size::composeMax;
constexpr int MESSAGE_EDIT_VERTICAL_PADDING = AirTokens::Space::s8 + AirTokens::Space::s8 - 1;

} // namespace

ChatTextEdit::ChatTextEdit(QWidget* parent)
    : QTextEdit(parent)
{
    retranslateUi();
    setAcceptRichText(false);
    setAcceptDrops(false);
    setLineWrapMode(QTextEdit::WidgetWidth);
    setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setMinimumHeight(MESSAGE_EDIT_MIN_HEIGHT);
    setMaximumHeight(MESSAGE_EDIT_MAX_HEIGHT);
    // The padding of the field is all the room around the text.
    document()->setDocumentMargin(0);
    new EmojiSequenceHighlighter(document());

    connect(this, &QTextEdit::textChanged, this, &ChatTextEdit::updateHeightForContent);

    Translator::registerHandler(std::bind(&ChatTextEdit::retranslateUi, this), this);

    updateHeightForContent();
}

ChatTextEdit::~ChatTextEdit()
{
    Translator::unregister(this);
}

void ChatTextEdit::keyPressEvent(QKeyEvent* event)
{
    int key = event->key();
    if ((key == Qt::Key_Enter || key == Qt::Key_Return) && !(event->modifiers() & Qt::ShiftModifier)) {
        emit enterPressed();
        return;
    }
    if (key == Qt::Key_Escape) {
        emit escapePressed();
        return;
    }
    if (key == Qt::Key_Tab) {
        if (event->modifiers())
            event->ignore();
        else {
            emit tabPressed();
            event->ignore();
        }
        return;
    }
    if (key == Qt::Key_Up && toPlainText().isEmpty()) {
        if (editLast && editLast()) {
            setFocus();
            moveCursor(QTextCursor::MoveOperation::End, QTextCursor::MoveMode::MoveAnchor);
            return;
        }
        setPlainText(lastMessage);
        setFocus();
        moveCursor(QTextCursor::MoveOperation::End, QTextCursor::MoveMode::MoveAnchor);
        return;
    }
    if (event->matches(QKeySequence::Paste)) {
        const QClipboard* const clipboard = QApplication::clipboard();
        if (clipboard && tryHandlePaste(clipboard->mimeData())) {
            return;
        }
    }
    emit keyPressed();
    QTextEdit::keyPressEvent(event);
}

void ChatTextEdit::insertFromMimeData(const QMimeData* source)
{
    if (tryHandlePaste(source)) {
        return;
    }

    QTextEdit::insertFromMimeData(source);
}

void ChatTextEdit::setLastMessage(QString lm)
{
    lastMessage = lm;
}

void ChatTextEdit::setEditLastHandler(std::function<bool()> handler)
{
    editLast = std::move(handler);
}

void ChatTextEdit::retranslateUi()
{
    setPlaceholderText(tr("Type your message here..."));
}

void ChatTextEdit::resizeEvent(QResizeEvent* event)
{
    QTextEdit::resizeEvent(event);
    updateHeightForContent();
}

void ChatTextEdit::updateHeightForContent()
{
    QTextDocument* const doc = document();
    doc->setTextWidth(viewport()->width());

    const int contentHeight = static_cast<int>(doc->size().height());
    const int targetHeight =
        qBound(MESSAGE_EDIT_MIN_HEIGHT, contentHeight + MESSAGE_EDIT_VERTICAL_PADDING, MESSAGE_EDIT_MAX_HEIGHT);

    if (height() != targetHeight) {
        setFixedHeight(targetHeight);
    }

    setVerticalScrollBarPolicy(targetHeight >= MESSAGE_EDIT_MAX_HEIGHT ? Qt::ScrollBarAsNeeded
                                                                     : Qt::ScrollBarAlwaysOff);
}

void ChatTextEdit::sendKeyEvent(QKeyEvent* event)
{
    emit keyPressEvent(event);
}

bool ChatTextEdit::tryHandlePaste(const QMimeData* mimeData)
{
    if (!mimeData) {
        return false;
    }

    if (mimeData->hasImage()) {
        const QImage image = qvariant_cast<QImage>(mimeData->imageData());
        if (!image.isNull()) {
            emit pasteImage(QPixmap::fromImage(image));
            return true;
        }

        const QClipboard* const clipboard = QApplication::clipboard();
        if (clipboard) {
            const QPixmap pixmap = clipboard->pixmap();
            if (!pixmap.isNull()) {
                emit pasteImage(pixmap);
                return true;
            }
        }
    }

    const QStringList paths = localFilePathsFromMimeData(mimeData);
    if (!paths.isEmpty()) {
        emit pasteFiles(paths);
        return true;
    }

    return false;
}
