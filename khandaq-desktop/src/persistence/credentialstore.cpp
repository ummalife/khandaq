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
        // KHANDAQ (audit #10): `item` is a live SecKeychainItemRef (a CoreFoundation object).
        // SecKeychainItemFreeContent's 2nd arg is a data pointer it free()s — passing a CF object ran
        // free() on a live CF allocation (heap corruption / crash). Release it the CF way.
        CFRelease(item);
    }

    return status == errSecSuccess;
#elif defined(Q_OS_WIN)
    remove(profile);

    const std::wstring targetW = targetName(profile).toStdWString();
    std::vector<wchar_t> target(targetW.begin(), targetW.end());
    target.push_back(L'\0');
    // KHANDAQ: UserName must be NUL-terminated — the 7-char "account" copy had no terminator, so
    // CredWriteW read past the buffer for the user name.
    // KHANDAQ (audit #12): the two `L"account"` literals are not guaranteed to name the same array
    // object (literal pooling is implementation-defined), so the pointer pair was not a range.
    // One named object, like `targetW` above.
    const std::wstring userW = L"account";
    std::vector<wchar_t> user(userW.begin(), userW.end());
    user.push_back(L'\0');
    // KHANDAQ (audit #12): two toStdWString() calls returned two distinct temporaries, so begin()
    // and end() came from different containers — mismatched iterators, undefined behaviour on every
    // credential save. Materialise one wstring and iterate that.
    const std::wstring secretW = password.toStdWString();
    std::vector<wchar_t> secret(secretW.begin(), secretW.end());

    CREDENTIALW cred = {};
    cred.Type = CRED_TYPE_GENERIC;
    cred.TargetName = target.data();
    cred.UserName = user.data();
    cred.CredentialBlobSize = static_cast<DWORD>(secret.size() * sizeof(wchar_t));
    cred.CredentialBlob = reinterpret_cast<LPBYTE>(secret.data());
    // KHANDAQ: audit A43 changed this to CRED_PERSIST_CURRENT_USER "to scope it to this user, not
    // all machine users". That constant does not exist in the Windows API — wincred.h defines only
    // CRED_PERSIST_SESSION, CRED_PERSIST_LOCAL_MACHINE and CRED_PERSIST_ENTERPRISE — so the Windows
    // cross-build has not compiled since, which is why nobody noticed the premise was also wrong.
    //
    // Persist does not control WHO can read the credential. Credential Manager is per-user by
    // construction: an entry written here lands in the calling user's vault and other users of the
    // machine cannot read it. The flag controls LIFETIME instead — SESSION dies with the logon
    // session, LOCAL_MACHINE keeps it for this user on this machine, ENTERPRISE additionally roams
    // it with the user profile. LOCAL_MACHINE was therefore already exactly what A43 asked for, and
    // it is also the least-roaming option that survives a reboot.
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
