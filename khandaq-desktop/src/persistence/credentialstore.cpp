/*
    Copyright © 2026 Khandaq contributors

    This file is part of Khandaq, a Qt-based graphical interface for Tox.
*/

#include "credentialstore.h"

#include <QByteArray>

#include <string>
#include <tuple>
#include <vector>

#if defined(Q_OS_MAC)
#include <Security/Security.h>
#elif defined(Q_OS_WIN)
#include <windows.h>
#include <wincred.h>
#endif

namespace {
#if defined(Q_OS_MAC)
constexpr char SERVICE_NAME[] = "org.khandaq.messenger";
#elif defined(Q_OS_WIN)
QString targetName(const QString& profile)
{
    return QStringLiteral("Khandaq:%1").arg(profile);
}
#endif
} // namespace

bool CredentialStore::isSupported()
{
#if defined(Q_OS_MAC) || defined(Q_OS_WIN)
    return true;
#else
    return false;
#endif
}

bool CredentialStore::save(const QString& profile, const QString& password)
{
#if defined(Q_OS_MAC)
    remove(profile);

    const QByteArray service = QByteArray::fromRawData(SERVICE_NAME, sizeof(SERVICE_NAME) - 1);
    const QByteArray account = profile.toUtf8();
    const QByteArray secret = password.toUtf8();

    SecKeychainItemRef item = nullptr;
    const OSStatus status = SecKeychainAddGenericPassword(nullptr, static_cast<UInt32>(service.size()),
                                                          service.constData(),
                                                          static_cast<UInt32>(account.size()),
                                                          account.constData(),
                                                          static_cast<UInt32>(secret.size()),
                                                          secret.constData(), &item);
    if (item != nullptr) {
        SecKeychainItemFreeContent(nullptr, item);
    }

    return status == errSecSuccess;
#elif defined(Q_OS_WIN)
    remove(profile);

    const std::wstring targetW = targetName(profile).toStdWString();
    std::vector<wchar_t> target(targetW.begin(), targetW.end());
    target.push_back(L'\0');
    std::vector<wchar_t> user(L"account", L"account" + 7);
    std::vector<wchar_t> secret(password.toStdWString().begin(), password.toStdWString().end());

    CREDENTIALW cred = {};
    cred.Type = CRED_TYPE_GENERIC;
    cred.TargetName = target.data();
    cred.UserName = user.data();
    cred.CredentialBlobSize = static_cast<DWORD>(secret.size() * sizeof(wchar_t));
    cred.CredentialBlob = reinterpret_cast<LPBYTE>(secret.data());
    cred.Persist = CRED_PERSIST_LOCAL_MACHINE;

    return CredWriteW(&cred, 0) != FALSE;
#else
    std::ignore = profile;
    std::ignore = password;
    return false;
#endif
}

bool CredentialStore::load(const QString& profile, QString& password)
{
#if defined(Q_OS_MAC)
    const QByteArray service = QByteArray::fromRawData(SERVICE_NAME, sizeof(SERVICE_NAME) - 1);
    const QByteArray account = profile.toUtf8();

    UInt32 secretLength = 0;
    void* secretData = nullptr;
    SecKeychainItemRef item = nullptr;

    const OSStatus status = SecKeychainFindGenericPassword(
        nullptr, static_cast<UInt32>(service.size()), service.constData(),
        static_cast<UInt32>(account.size()), account.constData(), &secretLength, &secretData, &item);

    if (status != errSecSuccess || secretData == nullptr) {
        return false;
    }

    password = QString::fromUtf8(static_cast<const char*>(secretData), static_cast<int>(secretLength));
    SecKeychainItemFreeContent(nullptr, secretData);
    if (item != nullptr) {
        CFRelease(item);
    }
    return true;
#elif defined(Q_OS_WIN)
    std::wstring target = targetName(profile).toStdWString();
    PCREDENTIALW cred = nullptr;

    if (CredReadW(target.c_str(), CRED_TYPE_GENERIC, 0, &cred) == FALSE || cred == nullptr) {
        return false;
    }

    if (cred->CredentialBlob == nullptr || cred->CredentialBlobSize == 0) {
        password.clear();
    } else {
        const auto* blob = reinterpret_cast<const wchar_t*>(cred->CredentialBlob);
        const int charCount = static_cast<int>(cred->CredentialBlobSize / sizeof(wchar_t));
        password = QString::fromWCharArray(blob, charCount);
    }

    CredFree(cred);
    return true;
#else
    std::ignore = profile;
    std::ignore = password;
    return false;
#endif
}

bool CredentialStore::remove(const QString& profile)
{
#if defined(Q_OS_MAC)
    const QByteArray service = QByteArray::fromRawData(SERVICE_NAME, sizeof(SERVICE_NAME) - 1);
    const QByteArray account = profile.toUtf8();

    UInt32 secretLength = 0;
    void* secretData = nullptr;
    SecKeychainItemRef item = nullptr;

    const OSStatus status = SecKeychainFindGenericPassword(
        nullptr, static_cast<UInt32>(service.size()), service.constData(),
        static_cast<UInt32>(account.size()), account.constData(), &secretLength, &secretData, &item);

    if (status != errSecSuccess || item == nullptr) {
        if (secretData != nullptr) {
            SecKeychainItemFreeContent(nullptr, secretData);
        }
        return false;
    }

    SecKeychainItemFreeContent(nullptr, secretData);
    const OSStatus deleteStatus = SecKeychainItemDelete(item);
    CFRelease(item);
    return deleteStatus == errSecSuccess;
#elif defined(Q_OS_WIN)
    std::wstring target = targetName(profile).toStdWString();
    return CredDeleteW(target.c_str(), CRED_TYPE_GENERIC, 0) != FALSE;
#else
    std::ignore = profile;
    return false;
#endif
}
