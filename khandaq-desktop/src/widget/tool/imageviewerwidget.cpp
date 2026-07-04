/*
    KHANDAQ (Figma): fullscreen in-app image viewer. See header.
*/

#include "imageviewerwidget.h"

#include <QFileInfo>
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

    original.load(imagePath);

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
