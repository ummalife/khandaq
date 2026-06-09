/*
    Copyright © 2026 Khandaq contributors

    This file is part of Khandaq, a Qt-based graphical interface for Tox.
*/

#pragma once

#include <QString>

class CredentialStore
{
public:
    static bool isSupported();
    static bool save(const QString& profile, const QString& password);
    static bool load(const QString& profile, QString& password);
    static bool remove(const QString& profile);
};
