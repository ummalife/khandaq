/*
    KHANDAQ (Figma): fullscreen in-app image viewer. See header.
*/

#include "imageviewerwidget.h"

#include <QFileInfo>
#include <QImageReader>
#include <QKeyEvent>
#include <QLabel>
#include <QMouseEvent>
#include <QVBoxLayout>
#include <QWheelEvent>

ImageViewerWidget::ImageViewerWidget(const QString& imagePath, QWidget* parent)
    : QDialog(parent)
    , imageLabel(nullptr)
    , scale(1.0)
{
    setWindowFlags(Qt::Window | Qt::FramelessWindowHint);
    setAttribute(Qt::WA_DeleteOnClose);
    setStyleSheet("QDialog { background: black; }");

    // KHANDAQ (audit 2026-08-20): probe the header before decoding, the same guard pixmapFromFile()
    // applies in src/widget/imagepreviewwidget.cpp. Without it this viewer was the way AROUND that
    // guard: a 100 KB PNG declaring 22000x22000 makes the chat-bubble thumbnail come out blank —
    // because the preview path rejected it — so the file merely looks broken, which is exactly what
    // invites the click that opens it here at full size. Qt5 has no per-reader allocation limit, so
    // the decode is an out-of-memory or a long freeze, chosen by whoever sent the file.
    {
        QImageReader probe(imagePath);
        const QSize dims = probe.size();
        static const qint64 kMaxPixels = 64LL * 1000 * 1000; // ~64 megapixels
        static const int kMaxSide = 16384;
        const bool implausible = dims.isValid()
            && (static_cast<qint64>(dims.width()) * static_cast<qint64>(dims.height()) > kMaxPixels
                || dims.width() > kMaxSide || dims.height() > kMaxSide);
        if (!implausible) {
            original.load(imagePath);
        }
        // Left null otherwise: applyZoom() below already handles an empty pixmap, so the viewer
        // opens empty rather than taking the process down.
    }

    imageLabel = new QLabel(this);
    imageLabel->setAlignment(Qt::AlignCenter);
    imageLabel->setStyleSheet("background: transparent;");

    QVBoxLayout* layout = new QVBoxLayout(this);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->addWidget(imageLabel);

    showFullScreen();
    applyZoom();
}

bool ImageViewerWidget::isImagePath(const QString& path)
{
    static const QStringList exts = {"png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "heif"};
    return exts.contains(QFileInfo(path).suffix().toLower());
}

void ImageViewerWidget::applyZoom()
{
    if (original.isNull()) {
        return;
    }
    const QSize target(static_cast<int>(width() * scale), static_cast<int>(height() * scale));
    imageLabel->setPixmap(original.scaled(target, Qt::KeepAspectRatio, Qt::SmoothTransformation));
}

void ImageViewerWidget::wheelEvent(QWheelEvent* event)
{
    const double step = event->angleDelta().y() > 0 ? 1.15 : (1.0 / 1.15);
    scale = qBound(0.2, scale * step, 6.0);
    applyZoom();
}

void ImageViewerWidget::mousePressEvent(QMouseEvent* event)
{
    Q_UNUSED(event);
    close();
}

void ImageViewerWidget::keyPressEvent(QKeyEvent* event)
{
    if (event->key() == Qt::Key_Escape) {
        close();
    } else {
        QDialog::keyPressEvent(event);
    }
}

void ImageViewerWidget::resizeEvent(QResizeEvent* event)
{
    QDialog::resizeEvent(event);
    applyZoom();
}
