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

#pragma once

#include "src/model/air/airconversation.h"
#include "src/model/air/airgroup.h"
#include "src/model/air/airmedia.h"
#include "src/model/air/airmodel.h"
#include "src/model/air/airnetwork.h"
#include "src/model/air/airstart.h"
#include "src/persistence/profilefile.h"

#include <QDate>
#include <QLocale>
#include <QString>
#include <QVector>

/**
 * Every word the Air screen says, in one place: the redesign's vocabulary (design/redesign/02-plan.md,
 * section 7) with the same wording as the iOS and Android clients. The source texts are English; the
 * translations live in the translations/ directory under the "Air" context.
 */
namespace AirStrings {

enum class Text
{
    You,
    YouHint,
    StatusMenu,
    NetDirect,
    NetRelay,
    NetConnecting,
    NetOffline,
    NetHint,
    CommandPlaceholder,
    CommandKeyHint,
    NewContact,
    Groups,
    FileTransfers,
    Favorites,
    Settings,
    FilterRequests,
    FilterTransit,
    SectionLive,
    SectionAway,
    SectionSaved,
    SectionMessages,
    RequestTag,
    RequestKey,
    RequestAccept,
    RequestDecline,
    ReachLive,
    ReachRelay,
    ReachPending,
    ReachConnecting,
    ReachGroupOnline,
    ReachGroupNone,
    ReachJustNow,
    ReachMinutes,
    ReachHours,
    ReachDays,
    ReachLongAgo,
    DeliveryTransit,
    DeliverySent,
    DeliveryDelivered,
    DeliveryFailed,
    TransitFlag,
    UnreadCount,
    Mentioned,
    MentionsYou,
    TranscriptName,
    HintRequestSent,
    HintNoMessages,
    Typing,
    FileMessage,
    RowActions,
    AddFavorite,
    RemoveFavorite,
    EmptyNothingTitle,
    EmptyNothingBody,
    EmptyNoMatchTitle,
    EmptyNoMatchBody,
    EmptyNoRequestsTitle,
    EmptyNoRequestsBody,
    EmptyNoTransitTitle,
    EmptyNoTransitBody,
    EmptyNoFavoritesTitle,
    EmptyNoFavoritesBody,
    EmptyShowAll,
    ThemeLabel,
    ThemeSystem,
    ThemeLight,
    ThemeDark,
    AliasPrompt,
    GroupNamePrompt,
    ChatList,
    // The conversation (stage 2).
    DeliverySending,
    ChatSearch,
    ChatCall,
    ChatVideo,
    ChatDossierHint,
    DossierCloseHint,
    DayToday,
    DayYesterday,
    PresenceDirect,
    PresenceDirectSince,
    PresenceRelay,
    PresenceRelaySince,
    PresencePending,
    AwayJustNow,
    AwayMinutes,
    AwayHours,
    AwayDays,
    AwayLong,
    LinkDirect,
    LinkRelay,
    LinkNone,
    DayTitle,
    DayEmpty,
    DayBest,
    DayRanges,
    DayRange,
    DayA11y,
    DayA11yNone,
    DayNowOnline,
    DayNote,
    ComposePlaceholder,
    ComposeSend,
    ComposeAttach,
    ComposeHintLive,
    ComposeHintWait,
    ComposeHintSaved,
    ComposeHintSelfConnecting,
    ComposeHintSelfOffline,
    BytesLeft,
    BytesOverParts,
    TrayHead,
    TrayWait,
    TrayItemHint,
    TrayCollapseHint,
    TrayExpandHint,
    TrayCancel,
    TrayEdit,
    FailedRetry,
    FailedRetryHint,
    FailedItemHint,
    Retry,
    DossierLinkTitle,
    DossierLinkDirect,
    DossierLinkRelay,
    DossierLinkNone,
    PathRelay,
    DossierDayTitle,
    DossierBest,
    DossierNotificationsTitle,
    DossierNoWake,
    DossierMute,
    DossierUnmute,
    DossierMutedNote,
    DossierVerifyTitle,
    DossierNotVerified,
    DossierVerified,
    DossierVerify,
    DossierUnverify,
    VerifyMessage,
    VerifyMatch,
    DossierKeyTitle,
    DossierCopyKey,
    DossierKeyCopied,
    DossierQr,
    QrKeyHeading,
    QrKeySubtitle,
    DossierFilesTitle,
    DossierBlock,
    DossierBlockHint,
    BlockTitle,
    BlockMessage,
    Cancel,
    BlockedTitle,
    BlockedNone,
    BlockedSectionNote,
    BlockedEmpty,
    BlockedNote,
    Unblock,
    KeyUnblocked,
    DossierRemove,
    DossierRemoveTitle,
    DossierRemoveMessage,
    DossierRename,
    SavedTitle,
    HintSaved,
    NoteDelete,
    NoteDeleteTitle,
    NoteDeleteMessage,
    Delete,
    SizeBytes,
    SizeKilobytes,
    SizeMegabytes,
    SizeGigabytes,
    TransferSpeed,
    // New contact (E11) and the start of an ID in the command line (E3).
    NcIdLabel,
    NcIdPlaceholder,
    NcRulerKey,
    NcRulerAntispam,
    NcRulerCheck,
    NcRulerPart,
    NcVerdictEmpty,
    NcVerdictLeft,
    NcVerdictBad,
    NcVerdictOwn,
    NcVerdictContact,
    NcVerdictTheyAsked,
    NcVerdictBlocked,
    NcVerdictOk,
    NcWhyWhole,
    NcWhyOwn,
    NcWhyFix,
    NcWhyContact,
    NcWhyTheyAsked,
    NcOpenChat,
    NcOpenRequests,
    NcPaste,
    NcClear,
    NcMessageLabel,
    NcMessageNote,
    NcMessageTooLong,
    NcGreeting,
    NcSend,
    NcSendFailed,
    NcOwnTitle,
    NcOwnNote,
    NcCopy,
    NcCopied,
    NcImport,
    NcSentTitle,
    NcSentText,
    NcAcceptedTitle,
    NcAcceptedText,
    NcThem,
    NcDone,
    NcPathWaiting,
    NcPathLive,
    Close,
    CmdNewContact,
    CmdIdOk,
    CmdIdLeft,
    CmdIdOwn,
    CmdIdBad,
    CmdIdContact,
    NoChatOpen,
    // Requests and group invitations (E12).
    InviteTag,
    InviteFrom,
    InviteWait,
    InviteJoin,
    RequestDeclined,
    InviteDeclined,
    Undo,
    RequestAcceptFailed,
    InviteJoinFailed,
    // Groups, roles, the observer, New group and private messages (E13 to E15).
    GroupRoleFounder,
    GroupRoleModerator,
    GroupRoleMember,
    GroupRoleObserver,
    GroupYouFounder,
    GroupYouModerator,
    GroupYouMember,
    GroupYouObserver,
    GroupHeader,
    GroupHeaderNone,
    GroupHeaderOffline,
    GroupHintOffline,
    GroupHintSelfConnecting,
    GroupHintSelfOffline,
    GroupHintAlone,
    GroupHintNobody,
    GroupHintOneOther,
    GroupGetNow,
    GroupGetNowSpoken,
    GroupDossierHint,
    GroupReadOnlyObserver,
    GroupReadOnlyModerators,
    GroupReadOnlyFounder,
    GroupAskModerator,
    GroupAskFounder,
    GroupAskNobody,
    GroupAskChoose,
    GroupMemberStatus,
    GroupMemberOffline,
    GroupMemberYou,
    GroupMemberHint,
    GroupYourRole,
    GroupWhoGetsNow,
    GroupWhoGetsNowNote,
    GroupMembers,
    GroupSearchMembers,
    GroupNobodyFound,
    GroupKey,
    GroupCopyKey,
    GroupInvite,
    GroupKeyCopied,
    GroupQrHeading,
    GroupQrSubtitle,
    GroupMutedNote,
    GroupUnmutedNote,
    GroupLeave,
    GroupLeaveNote,
    GroupLeaveTitle,
    GroupLeaveMessage,
    GroupLeaveVerb,
    GroupWritePrivately,
    GroupMakeModerator,
    GroupMakeMember,
    GroupMakeObserver,
    GroupRemove,
    GroupMakeModeratorTitle,
    GroupMakeMemberTitle,
    GroupMakeObserverTitle,
    GroupRemoveTitle,
    GroupMakeModeratorMessage,
    GroupMakeMemberMessage,
    GroupMakeObserverMessage,
    GroupRemoveMessage,
    GroupNowModerator,
    GroupNowMember,
    GroupNowObserver,
    GroupRemoved,
    GroupChangeFailed,
    GroupInviteTitle,
    InviteOnlineNote,
    InviteChooseFirst,
    InviteWhenOnline,
    InviteNoContacts,
    InviteNobodyFound,
    InviteSearch,
    NewGroupTitle,
    NewGroupName,
    NewGroupNamePlaceholder,
    NewGroupNameNote,
    NewGroupPrivacy,
    PrivacyInviteOnly,
    PrivacyInviteOnlyNote,
    PrivacyOpen,
    PrivacyOpenNote,
    NewGroupInviteNote,
    NewGroupCreate,
    NewGroupNameFirst,
    NewGroupNameTooLong,
    NewGroupFailed,
    NewGroupJoinLink,
    NewGroupCreateLink,
    JoinTitle,
    JoinKeyNote,
    JoinKeyBad,
    JoinAlready,
    JoinOpen,
    PrivateTitle,
    PrivateNote,
    PrivateEmpty,
    PrivateReceived,
    PrivateFailed,
    GroupMemberMute,
    GroupMemberUnmute,
    GroupMemberMuted,
    GroupMemberUnmuted,
    GroupMemberHide,
    GroupMemberShow,
    GroupMemberHidden,
    GroupMemberShown,
    GroupMembersCount,
    GroupHintAll,
    GroupHintSome,
    GroupBytesOver,
    InviteSendCount,
    InviteSent,
    NewGroupCreateInvite,
    GroupObserverNoPrivate,
    // Files, voice notes and calls in the transcript (E8, E9).
    FileAccept,
    FileDecline,
    FilePause,
    FileResume,
    FileCancel,
    FileSendAgain,
    FileOpen,
    FileShowInFolder,
    FileLeft,
    FilePaused,
    FilePausedThere,
    FileWaitsAccept,
    FileDownloadStopped,
    FileNotSent,
    FileNameAndSize,
    FileSaveTitle,
    GroupFileLimit,
    FileNotSentTitle,
    FileAcceptFailedTitle,
    FileOffline,
    FileTooLarge,
    FileEmpty,
    FileUnreadable,
    FileNotWritable,
    FileGroupNotConnected,
    VoiceNote,
    VoiceListen,
    VoicePause,
    VoiceFailedTitle,
    TryAgain,
    Ok,
    Details,
    SupportCode,
    CopyCode,
    CallEntry,
    CallEntryCutOff,
    CallMissed,
    CallNoAnswer,
    CallOutgoing,
    CallIncoming,
    CallCalling,
    CallOfflineTitle,
    CallOfflineWait,
    CallWaitAction,
    CallWaiting,
    CallWaitingNote,
    CallCancel,
    CallMicTitle,
    CallMicMessage,
    CallCameraTitle,
    CallCameraMessage,
    CallOpenSettings,
    CallAudioOnly,
    CallAnswer,
    CallAnswerVideo,
    CallDecline,
    CallEnd,
    CallMicMute,
    CallMicUnmute,
    CallSoundMute,
    CallSoundUnmute,
    CallStartFailedTitle,
    CallAnswerFailedTitle,
    CallAlreadyOn,
    CallNotCalling,
    GroupNoCalls,
    GroupNoCallsNote,
    YouQr,
    YouQrHint,
    YouCopyId,
    YouIdCopied,
    YouCopyQr,
    YouQrCopied,
    YouSaveQr,
    YouQrFilter,
    YouQrSaved,
    YouQrSaveFailedTitle,
    YouPhoto,
    YouPhotoHint,
    YouPhotoChoose,
    YouPhotoRemove,
    YouPhotoFilter,
    YouPhotoFailedTitle,
    YouPhotoUnreadable,
    YouPhotoTooLarge,
    YouName,
    YouNamePlaceholder,
    YouStatus,
    YouStatusPlaceholder,
    YouSaved,
    YouDetails,
    YouPartKey,
    YouPartAntispam,
    YouPartCheck,
    YouAntispam,
    YouAntispamTitle,
    YouAntispamMessage,
    YouAntispamAction,
    YouAntispamDone,
    YouPassword,
    YouPasswordOn,
    YouPasswordOff,
    YouPasswordSet,
    YouPasswordChange,
    YouPasswordNew,
    YouPasswordChanged,
    YouPasswordRemove,
    YouPasswordRemoveTitle,
    YouPasswordRemoveMessage,
    YouPasswordRemoveAction,
    YouPasswordRemoved,
    YouPasswordFailedTitle,
    YouPasswordFailedMessage,
    YouFile,
    YouRename,
    YouRenameTitle,
    YouRenameLabel,
    YouRenamed,
    YouRenameFailedTitle,
    YouRenameExists,
    YouRenameError,
    YouExport,
    YouExportNote,
    YouExportFilter,
    YouExported,
    YouExportFailedTitle,
    YouNotWritable,
    YouLogout,
    YouLogoutTitle,
    YouLogoutMessage,
    YouLogoutMessageNoPassword,
    YouDelete,
    YouDeleteTitle,
    YouDeleteMessage,
    YouDeleteFailedTitle,
    YouDeleteLeftFiles,
    // First launch, sign-in and moving a profile in (E19, E20).
    StartStep,
    StartBack,
    StartNameTitle,
    StartNameBody,
    StartNamePlaceholder,
    StartNameFirst,
    StartNext,
    StartMoveProfile,
    StartDefaultFolder,
    StartPasswordTitle,
    StartPasswordBody,
    StartPasswordSet,
    StartPasswordSetDetail,
    StartPasswordSkip,
    StartPasswordSkipDetail,
    StartPasswordPlaceholder,
    StartRepeatPlaceholder,
    StartPasswordRule,
    StartPasswordEmpty,
    StartPasswordShort,
    StartPasswordWeak,
    StartPasswordRepeatFirst,
    StartPasswordMismatch,
    StartShowPassword,
    StartHidePassword,
    StartAutoLogin,
    StartAutoLoginNote,
    StartCreate,
    StartCreating,
    StartCreateFailed,
    StartIdTitle,
    StartIdBody,
    StartShare,
    StartAddPerson,
    SignInGo,
    SignInOpening,
    SignInEnterPassword,
    SignInNoPassword,
    SignInNoPasswordShort,
    SignInAutoLoginPlainNote,
    SignInProfiles,
    SignInProfilesNote,
    SignInForgot,
    SignInForgotTitle,
    SignInForgotMessage,
    SignInCreate,
    SignInDelete,
    SignInWrongPassword,
    SignInDamaged,
    SignInNoAccess,
    SignInInUse,
    SignInBadProxy,
    SignInWithoutProxy,
    SignInWithoutProxyTitle,
    SignInWithoutProxyMessage,
    SignInFailed,
    SignInFailedTitle,
    ImportTitle,
    ImportFilter,
    ImportPasswordBody,
    ImportReading,
    ImportWrongPassword,
    ImportPreviewTitle,
    ImportPreviewBody,
    ImportName,
    ImportNoName,
    ImportConfirm,
    ImportMoving,
    ImportOtherFile,
    ImportNotProfile,
    ImportDamaged,
    ImportUnreadable,
    ImportCopyFailed,
    ImportFailedTitle,
    // Settings (E18)
    SettingsSections,
    SettingsAppearance,
    SettingsNotifications,
    SettingsPrivacy,
    SettingsCalls,
    SettingsTransfer,
    SettingsNetwork,
    SettingsWindow,
    SettingsAbout,
    SettingsTheme,
    SettingsLanguage,
    SettingsList,
    SettingsListRegular,
    SettingsListCompact,
    SettingsConversations,
    SettingsFont,
    SettingsFontApp,
    SettingsFontValue,
    SettingsFontChoose,
    SettingsFormatting,
    SettingsFormattingPlain,
    SettingsFormattingMarks,
    SettingsFormattingHidden,
    SettingsSmileys,
    SettingsSmileyPack,
    SettingsSmileySize,
    SettingsPixels,
    SettingsNameColors,
    SettingsGroupEvents,
    SettingsGroupEventsDetail,
    SettingsStatusChanges,
    SettingsSpelling,
    SettingsNotify,
    SettingsPreview,
    SettingsPreviewNote,
    SettingsSystemNotices,
    SettingsSystemNoticesTray,
    SettingsSystemNoticesNoTray,
    SettingsOpenWindow,
    SettingsSound,
    SettingsBusySound,
    SettingsGroupsTitle,
    SettingsMentionsOnly,
    SettingsMentionsOnlyDetail,
    SettingsTyping,
    SettingsHistory,
    SettingsHistoryDetail,
    SettingsHistoryEraseTitle,
    SettingsHistoryEraseMessage,
    SettingsHistoryEraseAction,
    SettingsHistoryErased,
    HiddenTitle,
    HiddenSectionNote,
    HiddenEmpty,
    HiddenShown,
    SettingsImport,
    SettingsImportTitle,
    SettingsImportMessage,
    SettingsImportAction,
    SettingsImportMove,
    SettingsImportFailedTitle,
    SettingsPortable,
    SettingsPortableDetail,
    SettingsUdp,
    SettingsUdpProxy,
    SettingsIpv6,
    SettingsLan,
    SettingsProxy,
    SettingsProxyNone,
    SettingsProxyAddress,
    SettingsProxyPort,
    SettingsNetworkRestart,
    SettingsAttachments,
    SettingsAttachmentsAlways,
    SettingsAttachmentsSmall,
    SettingsAttachmentsNever,
    SettingsAttachmentsLimit,
    SettingsSaveTo,
    SettingsDownloadsFolder,
    SettingsChange,
    SettingsAttachmentsNote,
    SettingsAttachmentsGroups,
    SettingsLog,
    SettingsLogSave,
    SettingsLogCopy,
    SettingsLogSaved,
    SettingsLogCopied,
    SettingsLogFailedTitle,
    SettingsLogFilter,
    SettingsLogNote,
    NetworkTitleDirect,
    NetworkTitleRelay,
    NetworkTitleConnecting,
    NetworkTitleOffline,
    NetworkMeaningDirect,
    NetworkMeaningRelay,
    NetworkMeaningConnecting,
    NetworkMeaningOffline,
    NetworkInternet,
    NetworkInternetYes,
    NetworkInternetNo,
    NetworkLink,
    NetworkLinkDirect,
    NetworkLinkRelay,
    NetworkLinkNone,
    NetworkNodes,
    NetworkNodesNote,
    NetworkReconnect,
    NetworkReconnectToast,
    SettingsRestore,
    SettingsRestoreTitle,
    SettingsRestoreMessage,
    SettingsRestoreAction,
    SettingsRestored,
    SettingsStartup,
    SettingsAutorun,
    SettingsTray,
    SettingsTrayIcon,
    SettingsStartInTray,
    SettingsMinimizeToTray,
    SettingsCloseToTray,
    SettingsLightIcon,
    SettingsWindows,
    SettingsSeparate,
    SettingsWindowEach,
    SettingsStatusTitle,
    SettingsAway,
    SettingsMinutes,
    SettingsAwayNever,
    SettingsVersion,
    SettingsCommit,
    SettingsToxcore,
    SettingsQt,
    SettingsCheckUpdates,
    SettingsUpdateDownload,
    SettingsOwner,
    SettingsBasedOn,
    SettingsWebsite,
    SettingsContributors,
    SettingsReport,
    SettingsLicense,
    // Notifications and the band in the window (E22).
    NotifyNewMessage,
    NotifyNewGroupMessage,
    NotifyIncomingFile,
    NotifyNewMessages,
    NotifyMessagesFrom,
    NotifyInvite,
    NotifyInviteTo,
    BandOpensChat,
    BandOpensRequest,
    BandOpensInvite,
    // Errors and questions (E21).
    ErrorAddContactTitle,
    ErrorJoinGroupTitle,
    ErrorRoleTitle,
    ErrorKickTitle,
    ErrorInviteTitle,
    ErrorInviteSomeTitle,
    InviteRetry,
    AutoLoginFailed,
    ScreenshotNotSaved,
    LinkLocalTitle,
    LinkLocalMessage,
    LinkOpen,
    ProgramTitle,
    ProgramMessage,
    ClearScreenTitle,
    ClearScreenMessage,
    ClearScreenAction,
    MessageUnknownFormat,
    MessageEdited,
    ReplyUnknownSender,
    SpokenReply,
};

/** The translation context of every Air string. */
constexpr char context[] = "Air";

QString text(Text id);
/** A text with a number in it (%n), in the plural form the language gives that number. */
QString text(Text id, int count);
/** The texts that take a number and come in plural forms. */
bool isNumerus(Text id);
/** The untranslated English source, for tests that compare translations with it. */
const char* source(Text id);
QVector<Text> all();

/** Times and counters use the Latin digits of the Plex type, in every language. */
QString latinDigits(QString text, const QLocale& locale);

/** "online", "3 h ago", "waiting for reply": the reach mark of a row. */
QString reachText(const Air::Reach& reach);
/**
 * The line under your name: "online, direct", "online, via a relay", "connecting", "no network:
 * messages wait". It names no count of nodes: that stands in the Network section of Settings.
 */
QString networkText(Air::NetworkState state);
/** Why a profile did not open, under the field of sign-in: what happened and what to do (E20). */
QString openFailureText(Air::OpenFailure::Kind kind);
/** What is wrong with a profile file chosen to be moved in; empty when nothing is. */
QString importProblemText(ProfileFile::Contents::Problem problem);
/** The Network section of Settings: its title, what the state means, and its rows. */
QString networkTitle(Air::NetworkState state);
QString networkMeaning(Air::NetworkState state);
QString networkLinkText(Air::NetworkReport::Link link);
QString sectionTitle(Air::SectionKind kind);
QString deliveryText(Air::Delivery delivery);
QString emptyTitle(Air::EmptyState state);
QString emptyBody(Air::EmptyState state);

/** The word a screen reader says for the mark of one of our messages; empty for none. */
QString statusText(Air::EntryStatus status);
/**
 * A size as the phones write it: counted in thousands ("154 KB", "12.6 MB", "1.25 GB"), the
 * number in the way of the language ("12,6 МБ") with Latin digits, and "1 MB" rather than
 * "1,000 KB" when the amount rounds up to the next unit. Below zero counts as zero.
 */
QString byteSizeText(qint64 bytes, const QLocale& locale);
/** "1.2 MB/s": how fast a file travels. */
QString speedText(qint64 bytesPerSecond, const QLocale& locale);
/** "Today", "Yesterday", or the date, with the year only when it is not this year. */
QString dayMarkText(const QDate& date, const QDate& today, const QLocale& locale);
/**
 * The quiet line under the name: "online since 9:40, direct link", "offline for 25 minutes",
 * "typing". sinceTime is when this device saw the person come online, empty when unknown.
 */
QString presenceText(const Air::Reach& reach, const QString& sinceTime, bool isTyping);
/** "offline for 25 minutes", in the plural form the number takes. */
QString awayText(qint64 seconds);
/** What a screen reader says for the link mark. */
QString linkText(Air::LinkState link);
/** The line under the field; name is the person's short name. */
QString composeHintText(const Air::ComposeHint& hint, const QString& name);
/** "120 bytes left", or once over: "6 bytes over one message: it goes in parts". */
QString composeCounterText(int left);
/** "In transit: 3 messages". */
QString trayHeadText(int count);
/**
 * The words of a message where a person reads them (stage 3, item 10, DESIGN.md "Unknown
 * markup"): what its entry shows (Air::entryWords), or for a form this version cannot read the
 * sentence that says so. The list, the notifications and the bands read a message through it.
 */
QString messageWords(const QString& raw);
/** Who wrote the message a reply quotes, as the quote names them: Unknown when it names nobody. */
QString replyName(const QString& name);

// ---- what a screen reader says (stage 3, item 9) ----

/**
 * A row of the list, as the phones say it (iOS AirRowCell): the name, how they are reached, the
 * state of our last message, the preview, a mention of you, what waits in transit, the unread
 * messages and the time, joined with commas. Nothing the row does not show is said.
 */
QString spokenRow(const Air::Entry& entry);
/**
 * A card above the sections: a request says its label, the time, the person's words and the start
 * of the key; an invitation its label, the time, the group, who invites and, while they are away,
 * that joining waits for them.
 */
QString spokenRequest(const Air::Request& request);
/** A message the command line found: the conversation, its words and the time. */
QString spokenMessageHit(const Air::MessageHit& hit);
/** A heading of the list: the section and how many it holds. */
QString spokenSection(Air::SectionKind kind, int count);
/**
 * An entry of the transcript, as iOS says it: who wrote it and when, the message it answers, its
 * words, that it mentions you, its notes (that its author changed it, the reactions on it) and the
 * state of a message of ours. An empty part is left out.
 */
QString spokenEntry(const QString& author, const QString& time, const QString& words,
                    Air::EntryStatus status, bool mentionsYou, const QString& quote = QString(),
                    const QString& notes = QString());
/** "In reply to Марьям Хасанова, the quoted words": the quote of a reply; Unknown for no name. */
QString spokenQuote(const QString& name, const QString& preview);
/** "edited, ❤️ 2": what the notes under an entry say. */
QString spokenNotes(bool edited, const QString& reactions);
/** "9:00 to 11:00": a run of the day strip. */
QString dayRangeText(int from, int to);
/** What a screen reader says for the day strip. */
QString dayStripSpokenText(const Air::DayStrip& strip, const QLocale& locale, bool nowOnline);
/** The words beside the compact day strip: the best hours, the runs of the past day, or none. */
QString dayHintText(const Air::DayStrip& strip, bool hasBest, int bestFrom, int bestTo);

// ---- groups (E13 to E15) ----

/** "founder", "moderator", "member", "observer". */
QString groupRoleText(Air::GroupRole role);
/** Your role in the dossier: what it may do, in one or two sentences. */
QString groupRoleExplanation(Air::GroupRole role);
/** "9 members, 4 online now", "nobody online now" or "not connected to the group". */
QString groupHeaderText(const Air::GroupReach& reach);
/** The line under the field: who gets a message sent now. */
QString groupHintText(const Air::GroupHint& hint, const Air::GroupReach& reach);
/** The bar in place of the field; empty while you may write. */
QString groupReadOnlyText(Air::ComposeRight right);
/** Its button: whom to write to; empty while you may write. */
QString groupAskText(Air::ComposeRight right);
/**
 * Why that button waits, or empty while it can ask: toxcore lets an observer send nothing in the
 * group, private messages included; otherwise nobody may be known to ask yet.
 */
QString groupAskUnavailableText(Air::ComposeRight right, bool nobodyToAsk);
/** The length counter of a group field: the bytes left, or over the limit. */
QString groupCounterText(int left);
/** The second line of a member row: the role, then online or offline. */
QString groupMemberStatusText(const Air::GroupMember& member);
/** The verb of an action on a member, as the menu and the confirmation button say it. */
QString memberActionText(Air::MemberAction action);
/** The question before it, starting with the name so no language has to decline it. */
QString memberActionTitle(Air::MemberAction action, const QString& name);
/** Its consequence in one sentence. */
QString memberActionMessage(Air::MemberAction action);
/** What the toast says once it is done. */
QString memberActionDone(Air::MemberAction action, const QString& name);
QString groupPrivacyText(Air::GroupPrivacy privacy);
QString groupPrivacyNote(Air::GroupPrivacy privacy);

// ---- files, voice notes and calls (E8, E9) ----

/** The words of an action on a file entry, as its quiet button says them. */
QString fileActionText(Air::FileAction action);
/**
 * The line under a file: what it waits for, how much is left or how it ended; empty when there
 * is nothing to say. person is the short name of the other person of a conversation.
 */
QString fileNoteText(const Air::FileEntryState& state, const QString& person, const QLocale& locale);
/** "Call, 4:12", "Call, 4:12, cut off", "Missed call", "No answer". */
QString callEntryText(const Air::CallEntry& entry);
/** "A group takes files up to 200 MB": the limit of a group file, said before one is chosen. */
QString groupFileLimitText(const QLocale& locale);

} // namespace AirStrings
