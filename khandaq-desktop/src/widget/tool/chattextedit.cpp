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
#include <QMetaMethod>
#include <QMimeData>
#include <QResizeEvent>
#include <QSyntaxHighlighter>
#include <QTextBlock>
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

/**
 * The files a text names, one per line, or none: a text is files only when every line of it
 * names one. A text with a path among its lines is text, and the first line that names no file
 * ends the search, so a long text costs no look at the disk for each of its lines.
 */
QStringList localFilePathsFromText(QString text)
{
    text.replace(QLatin1Char('\r'), QLatin1Char('\n'));
    QStringList paths;
    for (const QString& line : text.split(QLatin1Char('\n'), QString::SkipEmptyParts)) {
        if (line.trimmed().isEmpty()) {
            continue;
        }
        const QStringList found = existingLocalFilePaths({line});
        if (found.isEmpty()) {
            return {};
        }
        paths.append(found);
    }
    return paths;
}

/** The files a paste brings: its local file URLs, or else the files its text names (text). */
QStringList localFilePathsFromMimeData(const QMimeData* mimeData, const QString& text)
{
    if (!mimeData) {
        return {};
    }

    if (mimeData->hasUrls()) {
        QStringList candidates;
        for (const QUrl& url : mimeData->urls()) {
            if (url.isLocalFile()) {
                candidates.append(url.toLocalFile());
            } else {
                candidates.append(url.toString());
            }
        }
        const QStringList paths = existingLocalFilePaths(candidates);
        if (!paths.isEmpty()) {
            return paths;
        }
    }

    return localFilePathsFromText(text);
}

/**
 * Whether a text with no spaces around it is one web, file or data address and nothing more:
 * what a browser's Copy image may put as the text of the picture.
 */
bool isOnlyAnAddress(const QString& text)
{
    for (const QChar c : text) {
        if (c.isSpace()) {
            return false;
        }
    }
    static const QStringList schemes{QStringLiteral("http"), QStringLiteral("https"),
                                     QStringLiteral("file"), QStringLiteral("data")};
    const QUrl url(text);
    return url.isValid() && schemes.contains(url.scheme(), Qt::CaseInsensitive);
}

/**
 * Whether the HTML of a paste shows pictures and no text: the img a browser's Copy image puts
 * beside the picture. A spreadsheet or an editor that puts a picture of the cells or the lines
 * beside their text has that text in its HTML too, a cell holding only a link among them.
 */
bool showsOnlyPictures(const QString& html)
{
    if (html.isEmpty()) {
        return false;
    }
    // Parsed only: nothing is laid out, so no picture it names is loaded.
    QTextDocument document;
    document.setHtml(html);
    bool picture = false;
    for (QTextBlock block = document.begin(); block.isValid(); block = block.next()) {
        for (QTextBlock::iterator it = block.begin(); !it.atEnd(); ++it) {
            const QTextFragment fragment = it.fragment();
            if (fragment.charFormat().isImageFormat()) {
                picture = true;
            } else if (!fragment.text().trimmed().isEmpty()) {
                return false;
            }
        }
    }
    return picture;
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
    // KHANDAQ (0.2.56): setting the width lays the whole text out again, even the same width, and
    // this runs at every keystroke: a long pasted text froze the window at each letter.
    const qreal width = viewport()->width();
    if (doc->textWidth() != width) {
        doc->setTextWidth(width);
    }

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

ChatTextEdit::PasteKind ChatTextEdit::pasteKind(bool hasLocalFiles, const QString& text,
                                                bool hasImage, const QString& html)
{
    if (hasLocalFiles) {
        return PasteKind::Files;
    }
    // KHANDAQ (0.2.56): the text before a picture. Excel, Word and other programs put a picture
    // of what was copied beside its text, and the field took the picture: in a group, where no
    // picture is taken, nothing came at all. A browser's Copy image puts no text, or an address
    // beside the picture and HTML that shows only the picture: that is the picture. The address
    // is not matched with the src of the img, as a browser may put the address of the picture it
    // chose from srcset or of a link around it.
    const QString trimmed = text.trimmed();
    if (!trimmed.isEmpty()
        && !(hasImage && isOnlyAnAddress(trimmed) && showsOnlyPictures(html))) {
        return PasteKind::Text;
    }
    return hasImage ? PasteKind::Image : PasteKind::Text;
}

bool ChatTextEdit::tryHandlePaste(const QMimeData* mimeData)
{
    if (!mimeData) {
        return false;
    }

    const QString text = mimeData->hasText() ? mimeData->text() : QString();
    const QStringList paths = localFilePathsFromMimeData(mimeData, text);
    // KHANDAQ (0.2.56): a picture counts only where it is taken, a chat with a person and, since
    // 0.2.60, a group (GenericChatForm::takePastedImages); in notes the text beside it is pasted
    // instead of nothing.
    const QMetaMethod imageSignal = QMetaMethod::fromSignal(&ChatTextEdit::pasteImage);
    const bool takesImage = mimeData->hasImage() && isSignalConnected(imageSignal);
    const QString html = takesImage && mimeData->hasHtml() ? mimeData->html() : QString();
    switch (pasteKind(!paths.isEmpty(), text, takesImage, html)) {
    case PasteKind::Files:
        emit pasteFiles(paths);
        return true;
    case PasteKind::Text:
        // QTextEdit inserts it as plain text.
        return false;
    case PasteKind::Image:
        break;
    }

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
    return false;
}
